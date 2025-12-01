package com.lxmf.messenger.service.binder

import android.util.Log
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
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
    private val state: ServiceState,
    private val wrapperManager: PythonWrapperManager,
    private val identityManager: IdentityManager,
    private val routingManager: RoutingManager,
    private val messagingManager: MessagingManager,
    private val pollingManager: PollingManager,
    private val broadcaster: CallbackBroadcaster,
    private val lockManager: LockManager,
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
                    beforeInit = { wrapper ->
                        // Setup BLE bridge BEFORE Python initialization
                        // (AndroidBLEDriver needs kotlin_bridge during Reticulum startup)
                        try {
                            wrapper.callAttr("set_ble_bridge", bleCoordinator.getBridge())
                            Log.d(TAG, "BLE bridge set before Python initialization")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set BLE bridge before init: ${e.message}", e)
                        }
                    },
                    onSuccess = {
                        // Execute directly - we're already in a coroutine from the outer scope.launch
                        // Wrap in try-catch to ensure callback is always called and locks are released on error
                        try {
                            // Setup remaining bridges AFTER Python is initialized
                            setupBridges()

                            // Acquire locks
                            lockManager.acquireAll()

                            // Start polling
                            pollingManager.startAnnouncesPolling()
                            pollingManager.startMessagesPolling()

                            // Announce LXMF destination
                            announceLxmfDestination()

                            // Notify success
                            callback.onInitializationComplete(
                                JSONObject().apply {
                                    put("success", true)
                                }.toString(),
                            )

                            // Update status
                            state.networkStatus.set("READY")
                            broadcaster.broadcastStatusChange("READY")
                            notificationManager.updateNotification("READY")

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

    override fun shutdown() {
        try {
            val stackTrace = Thread.currentThread().stackTrace.take(10).joinToString("\n  ")
            Log.i(TAG, "shutdown() called from:\n  $stackTrace")
            Log.d(TAG, "Shutting down Reticulum (async)")

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
            debugInfo.put("storage_path", result.getDictValue("storage_path")?.toString() ?: "")
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
                    ifaceJson.put("name", iface.getDictValue("name")?.toString() ?: "")
                    ifaceJson.put("type", iface.getDictValue("type")?.toString() ?: "")
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

    // ===========================================
    // Private Helpers
    // ===========================================

    private fun setupBridges() {
        // Note: BLE bridge is set in beforeInit callback (before Python initialization)
        // because AndroidBLEDriver needs it during Reticulum startup

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

        // Setup delivery status callback
        try {
            wrapperManager.setDeliveryStatusCallback { statusJson ->
                pollingManager.handleDeliveryStatusEvent(statusJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set delivery status callback: ${e.message}", e)
        }

        // Setup message received callback
        try {
            wrapperManager.setMessageReceivedCallback { messageJson ->
                pollingManager.handleMessageReceivedEvent(messageJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set message received callback: ${e.message}", e)
        }
    }

    private suspend fun announceLxmfDestination() {
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
