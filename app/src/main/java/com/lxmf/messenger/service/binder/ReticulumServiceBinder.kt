package com.lxmf.messenger.service.binder

import android.content.Context
import android.util.Log
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.reticulum.rnode.KotlinRNodeBridge
import com.lxmf.messenger.reticulum.rnode.RNodeErrorListener
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.MessagingManager
import com.lxmf.messenger.service.manager.PollingManager
import com.lxmf.messenger.service.manager.PythonWrapperManager
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
import com.lxmf.messenger.service.manager.RoutingManager
import com.lxmf.messenger.service.manager.ServiceNotificationManager
import com.lxmf.messenger.service.state.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AIDL binder implementation for ReticulumService.
 *
 * Implements all 26 IReticulumService methods by delegating to appropriate managers.
 * This class is the IPC boundary between the main app and the service process.
 *
 * Thread Safety:
 * - All methods are called on Binder threads
 * - Async operations use serviceScope for coroutines
 * - State is managed through ServiceState atomic operations
 */
class ReticulumServiceBinder(
    private val context: Context,
    private val state: ServiceState,
    private val wrapperManager: PythonWrapperManager,
    private val identityManager: IdentityManager,
    private val routingManager: RoutingManager,
    private val messagingManager: MessagingManager,
    private val pollingManager: PollingManager,
    private val broadcaster: CallbackBroadcaster,
    private val lockManager: LockManager,
    private val maintenanceManager: MaintenanceManager,
    private val notificationManager: ServiceNotificationManager,
    private val bleCoordinator: BleCoordinator,
    private val scope: CoroutineScope,
    private val onInitialized: () -> Unit,
    private val onShutdown: () -> Unit,
    private val onForceExit: () -> Unit,
) : IReticulumService.Stub() {
    companion object {
        private const val TAG = "ReticulumServiceBinder"
    }

    // RNode bridge - created lazily when needed
    private var rnodeBridge: KotlinRNodeBridge? = null

    // ===========================================
    // Lifecycle Methods
    // ===========================================

    override fun initialize(
        configJson: String,
        callback: IInitializationCallback,
    ) {
        Log.d(TAG, "Initialize called (async)")

        scope.launch {
            try {
                Log.d(TAG, "=== Binder: Starting Reticulum Initialization ===")

                // Update status
                state.networkStatus.set("INITIALIZING")
                broadcaster.broadcastStatusChange("INITIALIZING")
                notificationManager.updateNotification("INITIALIZING")

                // Initialize wrapper
                wrapperManager.initialize(
                    configJson = configJson,
                    beforeInit = { wrapper -> setupPreInitializationBridges(wrapper) },
                    onSuccess = { isSharedInstance ->
                        // Execute directly - we're already in a coroutine from the outer scope.launch
                        // Wrap in try-catch to ensure callback is always called and locks are released on error
                        try {
                            // Setup remaining bridges AFTER Python is initialized
                            setupBridges()

                            // Acquire locks
                            lockManager.acquireAll()

                            // Start maintenance job to refresh locks before timeout
                            maintenanceManager.start()

                            // Start polling
                            pollingManager.startAnnouncesPolling()
                            pollingManager.startMessagesPolling()

                            // Announce LXMF destination
                            announceLxmfDestination()

                            // Notify success with shared instance status
                            callback.onInitializationComplete(
                                JSONObject().apply {
                                    put("success", true)
                                    put("is_shared_instance", isSharedInstance)
                                }.toString(),
                            )

                            // Update status
                            state.networkStatus.set("READY")
                            broadcaster.broadcastStatusChange("READY")
                            notificationManager.updateNotification("READY")

                            // Broadcast initial state for event-driven updates
                            broadcastDebugInfoUpdate()
                            broadcastInterfaceStatusUpdate()

                            onInitialized()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during post-initialization setup", e)
                            // Clean up on failure - release any acquired resources
                            lockManager.releaseAll()
                            pollingManager.stopAll()

                            val errorMsg = e.message ?: "Post-initialization setup failed"
                            state.networkStatus.set("ERROR:$errorMsg")
                            broadcaster.broadcastStatusChange("ERROR:$errorMsg")
                            notificationManager.updateNotification("ERROR")

                            callback.onInitializationError(errorMsg)
                        }
                    },
                    onError = { error ->
                        // Clean up on failure
                        lockManager.releaseAll()
                        pollingManager.stopAll()

                        state.networkStatus.set("ERROR:$error")
                        broadcaster.broadcastStatusChange("ERROR:$error")
                        notificationManager.updateNotification("ERROR:$error")

                        callback.onInitializationError(error)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                callback.onInitializationError(e.message ?: "Unknown error")
            }
        }
    }

    private fun setupPreInitializationBridges(wrapper: com.chaquo.python.PyObject) {
        // Setup BLE bridge BEFORE Python initialization
        // (AndroidBLEDriver needs kotlin_bridge during Reticulum startup)
        try {
            wrapper.callAttr("set_ble_bridge", bleCoordinator.getBridge())
            Log.d(TAG, "BLE bridge set before Python initialization")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set BLE bridge before init: ${e.message}", e)
        }

        // Setup RNode bridge BEFORE Python initialization
        // (ColumbaRNodeInterface needs kotlin_rnode_bridge during initialization)
        try {
            rnodeBridge = KotlinRNodeBridge(context)

            // Register error listener to surface RNode errors to UI
            rnodeBridge?.addErrorListener(
                object : RNodeErrorListener {
                    override fun onRNodeError(
                        errorCode: Int,
                        errorMessage: String,
                    ) {
                        Log.w(TAG, "RNode error surfaced to service: ($errorCode) $errorMessage")
                        // Broadcast error as status change so UI can display it
                        broadcaster.broadcastStatusChange("RNODE_ERROR:$errorMessage")
                    }
                },
            )

            // Register online status listener to trigger UI refresh when RNode connects/disconnects
            rnodeBridge?.addOnlineStatusListener(
                object : com.lxmf.messenger.reticulum.rnode.RNodeOnlineStatusListener {
                    override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                        Log.d(TAG, "████ RNODE ONLINE STATUS CHANGED ████ online=$isOnline")
                        // Broadcast status change so UI can refresh interface list
                        broadcaster.broadcastStatusChange(
                            if (isOnline) "RNODE_ONLINE" else "RNODE_OFFLINE",
                        )
                        // Also broadcast interface status and debug info for event-driven updates
                        scope.launch(Dispatchers.IO) {
                            broadcastInterfaceStatusUpdate()
                            broadcastDebugInfoUpdate()
                        }
                    }
                },
            )

            wrapper.callAttr("set_rnode_bridge", rnodeBridge)
            Log.d(TAG, "RNode bridge set before Python initialization")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set RNode bridge before init: ${e.message}", e)
        }

        // Setup delivery status callback BEFORE Python initialization
        // This ensures messages sent during init get their status reported
        try {
            val deliveryCallback: (String) -> Unit = { statusJson ->
                pollingManager.handleDeliveryStatusEvent(statusJson)
            }
            wrapper.callAttr("set_delivery_status_callback", deliveryCallback)
            Log.d(TAG, "Delivery status callback set before Python initialization")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set delivery status callback before init: ${e.message}", e)
        }
    }

    override fun shutdown() {
        try {
            val stackTrace = Thread.currentThread().stackTrace.take(10).joinToString("\n  ")
            Log.i(TAG, "shutdown() called from:\n  $stackTrace")
            Log.d(TAG, "Shutting down Reticulum (async)")

            // Stop maintenance job
            maintenanceManager.stop()

            // Stop polling immediately
            pollingManager.stopAll()

            // Release locks
            lockManager.releaseAll()

            // Update status
            state.networkStatus.set("RESTARTING")
            broadcaster.broadcastStatusChange("RESTARTING")
            notificationManager.updateNotification("RESTARTING")

            // Shutdown wrapper asynchronously
            scope.launch(Dispatchers.IO) {
                wrapperManager.shutdown {
                    if (state.isCurrentGeneration(state.initializationGeneration.get())) {
                        state.networkStatus.set("SHUTDOWN")
                        broadcaster.broadcastStatusChange("SHUTDOWN")
                        notificationManager.updateNotification("SHUTDOWN")
                        onShutdown()
                    } else {
                        Log.d(TAG, "Skipping stale shutdown callback (generation mismatch)")
                    }
                }
            }

            Log.d(TAG, "Shutdown initiated (Python cleanup running in background)")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    override fun getStatus(): String = state.networkStatus.get()

    override fun isInitialized(): Boolean {
        val initialized = state.isInitialized()
        if (!initialized) {
            Log.w(TAG, "isInitialized() = false (wrapper=${state.wrapper != null}, status=${state.networkStatus.get()})")
        }
        return initialized
    }

    override fun forceExit() {
        Log.i(TAG, "forceExit() called - shutting down and killing process")
        try {
            shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown in forceExit()", e)
        }
        onForceExit()
    }

    // ===========================================
    // Identity Methods
    // ===========================================

    override fun createIdentity(): String = identityManager.createIdentity()

    override fun loadIdentity(path: String): String = identityManager.loadIdentity(path)

    override fun saveIdentity(
        privateKey: ByteArray,
        path: String,
    ): String = identityManager.saveIdentity(privateKey, path)

    override fun createIdentityWithName(displayName: String): String {
        return identityManager.createIdentityWithName(displayName)
    }

    override fun deleteIdentityFile(identityHash: String): String = identityManager.deleteIdentityFile(identityHash)

    override fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): String = identityManager.importIdentityFile(fileData, displayName)

    override fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray = identityManager.exportIdentityFile(identityHash, filePath)

    override fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): String = identityManager.recoverIdentityFile(identityHash, keyData, filePath)

    override fun getLxmfIdentity(): String = identityManager.getLxmfIdentity()

    override fun getLxmfDestination(): String = identityManager.getLxmfDestination()

    // ===========================================
    // Routing Methods
    // ===========================================

    override fun hasPath(destHash: ByteArray): Boolean = routingManager.hasPath(destHash)

    override fun requestPath(destHash: ByteArray): String = routingManager.requestPath(destHash)

    override fun getHopCount(destHash: ByteArray): Int = routingManager.getHopCount(destHash)

    override fun getPathTableHashes(): String = routingManager.getPathTableHashes()

    // ===========================================
    // Messaging Methods
    // ===========================================

    override fun sendLxmfMessage(
        destHash: ByteArray,
        content: String,
        sourceIdentityPrivateKey: ByteArray,
        imageData: ByteArray?,
        imageFormat: String?,
    ): String = messagingManager.sendLxmfMessage(destHash, content, sourceIdentityPrivateKey, imageData, imageFormat)

    override fun sendPacket(
        destHash: ByteArray,
        data: ByteArray,
        packetType: String,
    ): String = messagingManager.sendPacket(destHash, data, packetType)

    override fun createDestination(
        identityJson: String,
        direction: String,
        destType: String,
        appName: String,
        aspectsJson: String,
    ): String = messagingManager.createDestination(identityJson, direction, destType, appName, aspectsJson)

    override fun announceDestination(
        destHash: ByteArray,
        appData: ByteArray?,
    ): String = messagingManager.announceDestination(destHash, appData)

    override fun restorePeerIdentities(peerIdentitiesJson: String): String {
        return messagingManager.restorePeerIdentities(peerIdentitiesJson)
    }

    override fun restoreAnnounceIdentities(announcesJson: String): String {
        return messagingManager.restoreAnnounceIdentities(announcesJson)
    }

    // ===========================================
    // Callback Methods
    // ===========================================

    override fun registerCallback(callback: IReticulumServiceCallback) {
        broadcaster.register(callback)
        Log.d(TAG, "Callback registered")
    }

    override fun unregisterCallback(callback: IReticulumServiceCallback) {
        broadcaster.unregister(callback)
        Log.d(TAG, "Callback unregistered")
    }

    override fun registerReadinessCallback(callback: IReadinessCallback) {
        broadcaster.registerReadinessCallback(callback)
    }

    override fun setConversationActive(active: Boolean) {
        pollingManager.setConversationActive(active)
    }

    // ===========================================
    // Debug Methods
    // ===========================================

    override fun getDebugInfo(): String {
        return try {
            val result = wrapperManager.getDebugInfo() ?: return "{}"

            val debugInfo = JSONObject()
            debugInfo.put("initialized", result.getDictValue("initialized")?.toBoolean() ?: false)
            debugInfo.put("reticulum_available", result.getDictValue("reticulum_available")?.toBoolean() ?: false)
            debugInfo.put("storage_path", result.getDictValue("storage_path")?.toString().orEmpty())
            debugInfo.put("identity_count", result.getDictValue("identity_count")?.toInt() ?: 0)
            debugInfo.put("destination_count", result.getDictValue("destination_count")?.toInt() ?: 0)
            debugInfo.put("pending_announces", result.getDictValue("pending_announces")?.toInt() ?: 0)
            debugInfo.put("transport_enabled", result.getDictValue("transport_enabled")?.toBoolean() ?: false)

            // Add lock status
            val lockStatus = lockManager.getLockStatus()
            debugInfo.put("multicast_lock_held", lockStatus.multicastHeld)
            debugInfo.put("wifi_lock_held", lockStatus.wifiHeld)
            debugInfo.put("wake_lock_held", lockStatus.wakeHeld)

            // Add interfaces
            val interfacesList = result.getDictValue("interfaces")?.asList()
            if (interfacesList != null) {
                val interfacesJson = JSONArray()
                for (ifaceObj in interfacesList) {
                    val iface = ifaceObj as? com.chaquo.python.PyObject ?: continue
                    val ifaceJson = JSONObject()
                    ifaceJson.put("name", iface.getDictValue("name")?.toString().orEmpty())
                    ifaceJson.put("type", iface.getDictValue("type")?.toString().orEmpty())
                    ifaceJson.put("online", iface.getDictValue("online")?.toBoolean() ?: false)
                    interfacesJson.put(ifaceJson)
                }
                debugInfo.put("interfaces", interfacesJson)
            }

            debugInfo.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting debug info", e)
            JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    override fun getBleConnectionDetails(): String = bleCoordinator.getConnectionDetailsJson()

    override fun recallIdentity(destHash: ByteArray): String =
        try {
            val destHashHex = destHash.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Attempting to recall identity for dest hash: ${destHashHex.take(16)}...")

            val result =
                wrapperManager.withWrapper { wrapper ->
                    wrapper.callAttr("recall_identity", destHashHex)
                }

            if (result != null) {
                val found = result.getDictValue("found")?.toBoolean() ?: false
                if (found) {
                    val publicKey = result.getDictValue("public_key")?.toString().orEmpty()
                    Log.d(TAG, "Identity found for ${destHashHex.take(16)}...")
                    JSONObject().apply {
                        put("found", true)
                        put("public_key", publicKey)
                    }.toString()
                } else {
                    Log.d(TAG, "No identity found for ${destHashHex.take(16)}...")
                    JSONObject().apply {
                        put("found", false)
                    }.toString()
                }
            } else {
                Log.d(TAG, "Wrapper returned null for recall_identity")
                JSONObject().apply {
                    put("found", false)
                }.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recalling identity", e)
            JSONObject().apply {
                put("found", false)
                put("error", e.message)
            }.toString()
        }

    override fun getRNodeRssi(): Int {
        val bridge = rnodeBridge ?: return -100
        // Trigger an RSSI read and return current value
        bridge.requestRssiUpdate()
        return bridge.getRssi()
    }

    override fun reconnectRNodeInterface() {
        Log.d(TAG, "████ RECONNECT RNODE ████ reconnectRNodeInterface() called")
        scope.launch(Dispatchers.IO) {
            try {
                wrapperManager.withWrapper { wrapper ->
                    Log.d(TAG, "████ RECONNECT RNODE ████ calling Python initialize_rnode_interface()")
                    val result = wrapper.callAttr("initialize_rnode_interface")

                    @Suppress("UNCHECKED_CAST")
                    val resultDict = result?.asMap() as? Map<com.chaquo.python.PyObject, com.chaquo.python.PyObject>
                    val success = resultDict?.entries?.find { it.key.toString() == "success" }?.value?.toBoolean() ?: false
                    if (success) {
                        val message = resultDict?.entries?.find { it.key.toString() == "message" }?.value?.toString()
                        Log.d(TAG, "████ RECONNECT RNODE SUCCESS ████ ${message ?: "success"}")
                    } else {
                        val error = resultDict?.entries?.find { it.key.toString() == "error" }?.value?.toString() ?: "Unknown error"
                        Log.w(TAG, "████ RECONNECT RNODE FAILED ████ $error")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "████ RECONNECT RNODE ERROR ████", e)
            }
        }
    }

    override fun isSharedInstanceAvailable(): Boolean {
        return try {
            wrapperManager.withWrapper { wrapper ->
                wrapper.callAttr("check_shared_instance_available")?.toBoolean() ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking shared instance availability", e)
            false
        }
    }

    override fun getFailedInterfaces(): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                wrapper.callAttr("get_failed_interfaces")?.toString() ?: "[]"
            } ?: "[]"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting failed interfaces", e)
            "[]"
        }
    }

    // ===========================================
    // Propagation Node Support
    // ===========================================

    override fun setOutboundPropagationNode(destHash: ByteArray?): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                val result = wrapper.callAttr("set_outbound_propagation_node", destHash)
                result?.toString() ?: """{"success": false, "error": "No result"}"""
            } ?: """{"success": false, "error": "Wrapper not initialized"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error setting propagation node", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    override fun getOutboundPropagationNode(): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                val result = wrapper.callAttr("get_outbound_propagation_node")
                result?.toString() ?: """{"success": false, "error": "No result"}"""
            } ?: """{"success": false, "error": "Wrapper not initialized"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting propagation node", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    override fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                val result =
                    wrapper.callAttr(
                        "request_messages_from_propagation_node",
                        identityPrivateKey,
                        maxMessages,
                    )
                result?.toString() ?: """{"success": false, "error": "No result"}"""
            } ?: """{"success": false, "error": "Wrapper not initialized"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting messages from propagation node", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    override fun getPropagationState(): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                val result = wrapper.callAttr("get_propagation_state")
                result?.toString() ?: """{"success": false, "error": "No result"}"""
            } ?: """{"success": false, "error": "Wrapper not initialized"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting propagation state", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    override fun sendLxmfMessageWithMethod(
        destHash: ByteArray,
        content: String,
        sourceIdentityPrivateKey: ByteArray,
        deliveryMethod: String,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
    ): String {
        return try {
            wrapperManager.withWrapper { wrapper ->
                val result =
                    wrapper.callAttr(
                        "send_lxmf_message_with_method",
                        destHash,
                        content,
                        sourceIdentityPrivateKey,
                        deliveryMethod,
                        tryPropagationOnFail,
                        imageData,
                        imageFormat,
                    )
                // Use PythonResultConverter to properly convert Python dict to JSON
                // (bytes values like message_hash need Base64 encoding)
                PythonResultConverter.convertSendMessageResult(result)
            } ?: """{"success": false, "error": "Wrapper not initialized"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error sending LXMF message with method", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    override fun provideAlternativeRelay(relayHash: ByteArray?) {
        try {
            Log.d(
                TAG,
                "Providing alternative relay: ${relayHash?.joinToString("") { "%02x".format(it) }?.take(16) ?: "null"}",
            )
            wrapperManager.provideAlternativeRelay(relayHash)
        } catch (e: Exception) {
            Log.e(TAG, "Error providing alternative relay", e)
        }
    }

    // ===========================================
    // Event Broadcasting Helpers
    // ===========================================

    /**
     * Broadcast current debug info to all registered callbacks.
     * Called when relevant state changes (initialization, lock changes, interface status).
     */
    private fun broadcastDebugInfoUpdate() {
        try {
            val debugInfoJson = getDebugInfo()
            broadcaster.broadcastDebugInfoChange(debugInfoJson)
            Log.d(TAG, "Debug info broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting debug info", e)
        }
    }

    /**
     * Broadcast current interface status to all registered callbacks.
     * Called when interface online/offline status changes.
     */
    private fun broadcastInterfaceStatusUpdate() {
        try {
            val result = wrapperManager.getDebugInfo()
            val interfacesList = result?.getDictValue("interfaces")?.asList()

            val statusMap = JSONObject()
            interfacesList?.mapNotNull { ifaceObj ->
                (ifaceObj as? com.chaquo.python.PyObject)?.let { iface ->
                    val name = iface.getDictValue("name")?.toString()
                    val online = iface.getDictValue("online")?.toBoolean() ?: false
                    name?.let { Pair(it, online) }
                }
            }?.forEach { (name, online) ->
                statusMap.put(name, online)
            }

            broadcaster.broadcastInterfaceStatusChange(statusMap.toString())
            Log.d(TAG, "Interface status broadcast sent: $statusMap")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting interface status", e)
        }
    }

    // ===========================================
    // Private Helpers
    // ===========================================

    private fun setupBridges() {
        // Note: BLE bridge is set in beforeInit callback (before Python initialization)
        // because AndroidBLEDriver needs it during Reticulum startup

        // Initialize RNode interface if configured
        // (RNode bridge was set in beforeInit, but interface needs to be started after RNS init)
        try {
            wrapperManager.withWrapper { wrapper ->
                val result = wrapper.callAttr("initialize_rnode_interface")

                @Suppress("UNCHECKED_CAST")
                val resultDict = result?.asMap() as? Map<com.chaquo.python.PyObject, com.chaquo.python.PyObject>
                val success = resultDict?.entries?.find { it.key.toString() == "success" }?.value?.toBoolean() ?: false
                if (success) {
                    val message = resultDict?.entries?.find { it.key.toString() == "message" }?.value?.toString()
                    if (message != null) {
                        Log.d(TAG, "RNode interface: $message")
                    } else {
                        Log.i(TAG, "RNode interface initialized successfully")
                    }
                } else {
                    val error = resultDict?.entries?.find { it.key.toString() == "error" }?.value?.toString() ?: "Unknown error"
                    Log.e(TAG, "Failed to initialize RNode interface: $error")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize RNode interface: ${e.message}", e)
        }

        // Setup Reticulum bridge for event-driven announces
        try {
            wrapperManager.setupReticulumBridge {
                scope.launch {
                    try {
                        val announces =
                            wrapperManager.withWrapper { wrapper ->
                                wrapper.callAttr("get_pending_announces")?.asList()
                            }
                        if (announces != null && announces.isNotEmpty()) {
                            Log.d(TAG, "Event-driven: processing ${announces.size} announces")
                            for (announceObj in announces) {
                                pollingManager.handleAnnounceEvent(announceObj as com.chaquo.python.PyObject)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing event-driven announces", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set ReticulumBridge: ${e.message}", e)
        }

        // Note: Delivery status callback is set in beforeInit block to catch early messages

        // Setup message received callback
        try {
            wrapperManager.setMessageReceivedCallback { messageJson ->
                pollingManager.handleMessageReceivedEvent(messageJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set message received callback: ${e.message}", e)
        }

        // Setup alternative relay callback for propagation failover
        try {
            wrapperManager.setAlternativeRelayCallback { requestJson ->
                Log.d(TAG, "Alternative relay requested: $requestJson")
                broadcaster.broadcastAlternativeRelayRequest(requestJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set alternative relay callback: ${e.message}", e)
        }
    }

    private fun announceLxmfDestination() {
        try {
            val destResult =
                wrapperManager.withWrapper { wrapper ->
                    wrapper.callAttr("get_lxmf_destination")
                }

            if (destResult != null) {
                val lxmfHash = destResult.getDictValue("hash")?.toJava(ByteArray::class.java) as? ByteArray
                if (lxmfHash != null) {
                    val announceResult =
                        wrapperManager.withWrapper { wrapper ->
                            wrapper.callAttr("announce_destination", lxmfHash, null as ByteArray?)
                        }
                    val success = announceResult?.getDictValue("success")?.toBoolean() ?: false
                    if (success) {
                        Log.d(TAG, "Announced LXMF destination on network")
                    } else {
                        Log.e(TAG, "Failed to announce LXMF destination")
                    }
                } else {
                    Log.e(TAG, "Could not get LXMF destination hash for announce")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error announcing LXMF destination", e)
        }
    }
}
