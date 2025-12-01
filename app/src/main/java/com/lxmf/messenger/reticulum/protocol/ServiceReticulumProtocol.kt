package com.lxmf.messenger.reticulum.protocol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.model.PacketType
import com.lxmf.messenger.reticulum.model.ReceivedPacket
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.service.ReticulumService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service-based implementation of ReticulumProtocol.
 * Communicates with ReticulumService via IPC instead of calling Python directly.
 *
 * This solves the Chaquopy threading limitation by running Python/RNS in a separate
 * service process with proper background threading support.
 */
class ServiceReticulumProtocol(
    private val context: Context,
    private val settingsRepository: com.lxmf.messenger.repository.SettingsRepository,
) : ReticulumProtocol {
    companion object {
        private const val TAG = "ServiceReticulumProtocol"
    }

    private var service: IReticulumService? = null
    private var isBound = false

    // Thread-safety: Lock for service binding state (continuation, service, isBound)
    private val bindLock = Any()

    // Lifecycle-aware coroutine scope for background operations
    // SupervisorJob ensures one failed coroutine doesn't cancel others
    private val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Phase 2.1: StateFlow for reactive status updates (replaces polling)
    // Initialize to CONNECTING since we don't know service state until we query it
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.CONNECTING)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // Phase 2, Task 2.3: Readiness tracking for explicit service binding notification
    // Thread-safe: Protected by bindLock to prevent race between callback and continuation storage
    private var readinessContinuation: kotlin.coroutines.Continuation<Unit>? = null
    private var serviceReadyBeforeContinuationSet = false
    private var bindStartTime: Long = 0

    // Flow for announces
    private val announceFlow =
        MutableSharedFlow<AnnounceEvent>(
            replay = 0,
            extraBufferCapacity = 100,
        )

    // Flow for packets
    private val packetFlow =
        MutableSharedFlow<ReceivedPacket>(
            replay = 0,
            extraBufferCapacity = 100,
        )

    // Flow for link events
    private val linkFlow =
        MutableSharedFlow<LinkEvent>(
            replay = 0,
            extraBufferCapacity = 100,
        )

    // Flow for messages
    // replay = 10 buffers recent messages so they're delivered even if
    // MessagingViewModel subscribes after message arrives (e.g., app was closed)
    private val messageFlow =
        MutableSharedFlow<ReceivedMessage>(
            replay = 10,
            extraBufferCapacity = 100,
        )

    // Flow for delivery status updates (sent messages)
    private val deliveryStatusFlow =
        MutableSharedFlow<DeliveryStatusUpdate>(
            replay = 10,
            extraBufferCapacity = 100,
        )

    init {
        // Load last known status as an optimistic hint while we query actual status
        // This reduces the visual flicker during app restart
        protocolScope.launch(Dispatchers.IO) {
            try {
                val lastStatus = settingsRepository.lastServiceStatusFlow.first()
                if (lastStatus != "UNKNOWN") {
                    // Show CONNECTING while we verify the actual state
                    // The onServiceConnected will update with real status
                    Log.d(
                        TAG,
                        "Loaded last known status: $lastStatus (showing CONNECTING until verified)",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load last known status", e)
            }
        }
    }

    // Service callback implementation
    private val serviceCallback =
        object : IReticulumServiceCallback.Stub() {
            override fun onAnnounce(announceJson: String) {
                try {
                    Log.i(TAG, "🔔 ServiceCallback.onAnnounce() CALLED - announce received from ReticulumService!")
                    Log.i(TAG, "   JSON length: ${announceJson.length} bytes")

                    val json = JSONObject(announceJson)

                    val destinationHash = json.optString("destination_hash").toByteArrayFromBase64()
                    val identityHash = json.optString("identity_hash").toByteArrayFromBase64()
                    val publicKey = json.optString("public_key").toByteArrayFromBase64()
                    val appData = json.optString("app_data")?.toByteArrayFromBase64()
                    val hops = json.optInt("hops", 0)
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    val aspect = if (json.has("aspect") && !json.isNull("aspect")) json.optString("aspect") else null
                    val receivingInterface =
                        if (json.has("interface") && !json.isNull("interface")) {
                            json.optString("interface").takeIf { it.isNotBlank() && it != "None" }
                        } else {
                            null
                        }

                    Log.i(
                        TAG,
                        "   Parsed: hash=${destinationHash?.take(
                            8,
                        )?.joinToString("") { "%02x".format(it) }}, hops=$hops, interface=$receivingInterface",
                    )

                    if (destinationHash != null && identityHash != null && publicKey != null) {
                        val identity =
                            Identity(
                                hash = identityHash,
                                publicKey = publicKey,
                                privateKey = null,
                            )

                        // Detect node type from app_data and aspect
                        val nodeType = NodeTypeDetector.detectNodeType(appData, aspect)

                        val event =
                            AnnounceEvent(
                                destinationHash = destinationHash,
                                identity = identity,
                                appData = appData,
                                hops = hops,
                                timestamp = timestamp,
                                nodeType = nodeType,
                                receivingInterface = receivingInterface,
                                aspect = aspect,
                            )

                        Log.i(TAG, "   NodeType detected: $nodeType, Aspect: $aspect")
                        Log.d(TAG, "Emitting announce to announceFlow...")
                        val emitResult = announceFlow.tryEmit(event)
                        if (emitResult) {
                            Log.d(TAG, "Announce emitted to flow")
                        } else {
                            Log.e(TAG, "Failed to emit announce to flow (buffer full?)")
                        }
                        Log.d(TAG, "Announce received via service: ${destinationHash.take(8).joinToString("") { "%02x".format(it) }}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling announce callback", e)
                }
            }

            override fun onPacket(packetJson: String) {
                try {
                    val json = JSONObject(packetJson)
                    // Packet event parsing not yet implemented
                    Log.d(TAG, "Packet received via service")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling packet callback", e)
                }
            }

            override fun onMessage(messageJson: String) {
                try {
                    val json = JSONObject(messageJson)

                    val messageHash = json.optString("message_hash", "")
                    val content = json.optString("content", "")
                    val sourceHash = json.optString("source_hash")?.toByteArrayFromBase64() ?: byteArrayOf()
                    val destHash = json.optString("destination_hash")?.toByteArrayFromBase64() ?: byteArrayOf()
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    // Extract LXMF fields (attachments, images, etc.) if present
                    val fieldsJson = json.optJSONObject("fields")?.toString()

                    val message =
                        ReceivedMessage(
                            messageHash = messageHash,
                            content = content,
                            sourceHash = sourceHash,
                            destinationHash = destHash,
                            timestamp = timestamp,
                            fieldsJson = fieldsJson,
                        )

                    messageFlow.tryEmit(message)
                    Log.d(TAG, "Message received via service: ${messageHash.take(16)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message callback", e)
                }
            }

            override fun onLinkEvent(linkEventJson: String) {
                try {
                    val json = JSONObject(linkEventJson)
                    // Link event parsing not yet implemented
                    Log.d(TAG, "Link event received via service")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling link event callback", e)
                }
            }

            override fun onStatusChanged(status: String) {
                // Phase 2.1: Emit to StateFlow for reactive updates
                val newStatus =
                    when {
                        status == "READY" -> NetworkStatus.READY
                        status == "INITIALIZING" -> NetworkStatus.INITIALIZING
                        status.startsWith("ERROR:") -> NetworkStatus.ERROR(status.substringAfter("ERROR:"))
                        else -> NetworkStatus.SHUTDOWN
                    }
                _networkStatus.value = newStatus
                Log.d(TAG, "Status changed: $newStatus (StateFlow updated)")

                // Persist status for recovery after app restart
                protocolScope.launch(Dispatchers.IO) {
                    try {
                        settingsRepository.saveServiceStatus(status)
                        Log.d(TAG, "Persisted service status: $status")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist service status", e)
                    }
                }
            }

            override fun onDeliveryStatus(statusJson: String) {
                try {
                    Log.d(TAG, "Delivery status update received: $statusJson")
                    val json = JSONObject(statusJson)

                    val messageHash = json.optString("message_hash")
                    val status = json.optString("status")
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                    val update =
                        DeliveryStatusUpdate(
                            messageHash = messageHash,
                            status = status,
                            timestamp = timestamp,
                        )

                    deliveryStatusFlow.tryEmit(update)
                    Log.d(TAG, "Delivery status emitted to flow: hash=${messageHash.take(16)}..., status=$status")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling delivery status callback", e)
                }
            }
        }

    // Phase 2, Task 2.3: Readiness callback for explicit service binding notification
    // Thread-safe: Uses bindLock to synchronize with bindService() continuation storage
    private val readinessCallback =
        object : IReadinessCallback.Stub() {
            override fun onServiceReady() {
                val elapsed = System.currentTimeMillis() - bindStartTime
                Log.d(TAG, "Service ready callback received - binding completed in ${elapsed}ms (target: < 100ms)")

                synchronized(bindLock) {
                    val cont = readinessContinuation
                    if (cont != null) {
                        cont.resume(Unit)
                        readinessContinuation = null
                    } else {
                        // Callback fired before continuation was set - flag it
                        serviceReadyBeforeContinuationSet = true
                        Log.d(TAG, "Service ready callback fired before continuation set - flagging")
                    }
                }
            }
        }

    // Service connection
    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                Log.d(TAG, "Service connected")
                service = IReticulumService.Stub.asInterface(binder)
                isBound = true

                // Query actual service status immediately to update UI (with timeout)
                protocolScope.launch(Dispatchers.IO) {
                    try {
                        val actualStatus =
                            withTimeoutOrNull(3000) {
                                service?.getStatus()
                            }
                        _networkStatus.value =
                            when {
                                actualStatus == "READY" -> NetworkStatus.READY
                                actualStatus == "INITIALIZING" -> NetworkStatus.INITIALIZING
                                actualStatus?.startsWith("ERROR:") == true ->
                                    NetworkStatus.ERROR(
                                        actualStatus.substringAfter("ERROR:"),
                                    )
                                actualStatus == null -> {
                                    Log.w(TAG, "Timeout querying service status (3s), assuming SHUTDOWN")
                                    NetworkStatus.SHUTDOWN
                                }
                                else -> NetworkStatus.SHUTDOWN
                            }
                        Log.d(
                            TAG,
                            "Detected actual service status on reconnect: $actualStatus",
                        )

                        // Persist the detected status
                        if (actualStatus != null) {
                            try {
                                settingsRepository.saveServiceStatus(actualStatus)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist detected status", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query service status on reconnect", e)
                        _networkStatus.value = NetworkStatus.SHUTDOWN
                    }
                }

                // Register callbacks
                try {
                    service?.registerCallback(serviceCallback)
                    service?.registerReadinessCallback(readinessCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering callbacks", e)
                    synchronized(bindLock) {
                        readinessContinuation?.resumeWithException(e)
                        readinessContinuation = null
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Service disconnected")
                service = null
                isBound = false
                _networkStatus.value = NetworkStatus.SHUTDOWN
            }
        }

    /**
     * Bind to the service before using any other methods.
     * This starts the service and establishes IPC connection.
     *
     * Phase 2, Task 2.3: Now waits for explicit readiness signal from service
     * instead of arbitrary delays. Target: < 100ms from bind to ready.
     *
     * Thread-safe: Uses bindLock to synchronize with readinessCallback.
     */
    suspend fun bindService() =
        suspendCancellableCoroutine { continuation ->
            try {
                bindStartTime = System.currentTimeMillis()
                Log.d(TAG, "Binding to ReticulumService...")

                // Thread-safe: Check if callback already fired before storing continuation
                synchronized(bindLock) {
                    if (serviceReadyBeforeContinuationSet) {
                        // Callback already fired - resume immediately
                        serviceReadyBeforeContinuationSet = false
                        Log.d(TAG, "Service was already ready - resuming immediately")
                        continuation.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    // Store continuation for readiness callback
                    readinessContinuation = continuation
                }

                // Start service first (use startForegroundService for Android O+)
                val startIntent =
                    Intent(context, ReticulumService::class.java).apply {
                        action = ReticulumService.ACTION_START
                    }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }

                // Bind to service
                val bindIntent = Intent(context, ReticulumService::class.java)
                val bound =
                    context.bindService(
                        bindIntent,
                        serviceConnection,
                        Context.BIND_AUTO_CREATE,
                    )

                if (!bound) {
                    synchronized(bindLock) {
                        readinessContinuation = null
                    }
                    continuation.resumeWithException(RuntimeException("Failed to bind to service"))
                }
                // Note: Continuation will be resumed by readinessCallback.onServiceReady()
                // when the service is actually ready for IPC calls
            } catch (e: Exception) {
                synchronized(bindLock) {
                    readinessContinuation = null
                }
                continuation.resumeWithException(e)
            }

            // Cleanup on cancellation
            continuation.invokeOnCancellation {
                synchronized(bindLock) {
                    readinessContinuation = null
                }
            }
        }

    /**
     * Unbind from the service.
     */
    fun unbindService() {
        try {
            if (isBound) {
                service?.unregisterCallback(serviceCallback)
                context.unbindService(serviceConnection)
                isBound = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        }
    }

    /**
     * Clean up resources and cancel all coroutines.
     * Should be called when ServiceReticulumProtocol is no longer needed.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up ServiceReticulumProtocol resources")
        protocolScope.cancel()
        unbindService()
    }

    /**
     * Get the current service status.
     * @return Result with status string: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     */
    suspend fun getStatus(): Result<String> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            service.getStatus()
        }
    }

    /**
     * Check if the service is initialized and ready to use.
     * @return Result with true if initialized, false otherwise
     */
    suspend fun isInitialized(): Result<Boolean> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            service.isInitialized()
        }
    }

    /**
     * Wait for the service to reach READY status.
     * Phase 2.1: Uses reactive StateFlow instead of polling.
     *
     * @param timeoutMs Maximum time to wait in milliseconds (default 5000ms)
     * @return Result<Unit> Success if READY within timeout, failure otherwise
     */
    suspend fun waitForReady(timeoutMs: Long = 5000): Result<Unit> {
        return runCatching {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "Waiting for service to become READY (timeout: ${timeoutMs}ms)")

            // Event-driven with StateFlow (no polling)
            val success =
                withTimeoutOrNull(timeoutMs) {
                    networkStatus.first { status ->
                        when (status) {
                            is NetworkStatus.READY -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                Log.d(TAG, "Service became READY after ${elapsed}ms (< 10ms target)")
                                true
                            }
                            is NetworkStatus.ERROR -> {
                                throw RuntimeException("Service entered ERROR state: $status")
                            }
                            else -> false // Keep waiting for READY
                        }
                    }
                }

            if (success == null) {
                throw RuntimeException("Timeout waiting for service to become READY (status: ${networkStatus.value})")
            }
        }
    }

    override suspend fun initialize(config: ReticulumConfig): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            Log.d(TAG, "Initializing via service (async)")

            // Use suspendCancellableCoroutine for async callback
            // This provides clean suspend function API while using callback underneath
            suspendCancellableCoroutine { continuation ->
                val callback =
                    object : IInitializationCallback.Stub() {
                        override fun onInitializationComplete(result: String) {
                            Log.d(TAG, "Initialization successful")
                            continuation.resume(Unit)
                        }

                        override fun onInitializationError(error: String) {
                            Log.e(TAG, "Initialization failed: $error")
                            continuation.resumeWithException(RuntimeException(error))
                        }
                    }

                try {
                    val configJson = buildConfigJson(config)
                    service.initialize(configJson, callback)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun buildConfigJson(config: ReticulumConfig): String {
        val json = JSONObject()
        json.put("storagePath", config.storagePath)
        json.put("logLevel", config.logLevel.name)
        json.put("allowAnonymous", config.allowAnonymous)

        // Include identity file path if specified
        if (config.identityFilePath != null) {
            json.put("identity_file_path", config.identityFilePath)
        }

        // Include display name if specified
        if (config.displayName != null) {
            json.put("display_name", config.displayName)
        }

        val interfacesArray = JSONArray()
        for (iface in config.enabledInterfaces) {
            val ifaceJson = JSONObject()
            when (iface) {
                is InterfaceConfig.AutoInterface -> {
                    ifaceJson.put("type", "AutoInterface")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("group_id", iface.groupId)
                    ifaceJson.put("discovery_scope", iface.discoveryScope)
                    ifaceJson.put("discovery_port", iface.discoveryPort)
                    ifaceJson.put("data_port", iface.dataPort)
                    ifaceJson.put("mode", iface.mode)
                }
                is InterfaceConfig.TCPClient -> {
                    ifaceJson.put("type", "TCPClient")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("target_host", iface.targetHost)
                    ifaceJson.put("target_port", iface.targetPort)
                    ifaceJson.put("kiss_framing", iface.kissFraming)
                    ifaceJson.put("mode", iface.mode)
                }
                is InterfaceConfig.RNode -> {
                    ifaceJson.put("type", "RNode")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("port", iface.port)
                    ifaceJson.put("frequency", iface.frequency)
                    ifaceJson.put("bandwidth", iface.bandwidth)
                    ifaceJson.put("tx_power", iface.txPower)
                    ifaceJson.put("spreading_factor", iface.spreadingFactor)
                    ifaceJson.put("coding_rate", iface.codingRate)
                    ifaceJson.put("mode", iface.mode)
                }
                is InterfaceConfig.UDP -> {
                    ifaceJson.put("type", "UDP")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("listen_ip", iface.listenIp)
                    ifaceJson.put("listen_port", iface.listenPort)
                    ifaceJson.put("forward_ip", iface.forwardIp)
                    ifaceJson.put("forward_port", iface.forwardPort)
                    ifaceJson.put("mode", iface.mode)
                }
                is InterfaceConfig.AndroidBLE -> {
                    ifaceJson.put("type", "AndroidBLE")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("device_name", iface.deviceName)
                    ifaceJson.put("max_connections", iface.maxConnections)
                    ifaceJson.put("mode", iface.mode)
                }
            }
            interfacesArray.put(ifaceJson)
        }
        json.put("enabledInterfaces", interfacesArray)

        return json.toString()
    }

    override suspend fun shutdown(): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            Log.d(TAG, "Initiating shutdown via service...")
            service.shutdown()

            // Reactive shutdown completion tracking
            // The service broadcasts status changes via onStatusChanged() callback
            // Wait for NetworkStatus.SHUTDOWN event instead of polling
            Log.d(TAG, "Waiting for shutdown completion (reactive via StateFlow)...")

            val shutdownComplete =
                withTimeoutOrNull(5000) {
                    networkStatus.first { status ->
                        status == NetworkStatus.SHUTDOWN || status is NetworkStatus.ERROR
                    }
                }

            if (shutdownComplete == null) {
                Log.w(TAG, "Shutdown wait timeout after 5000ms")
            } else {
                Log.d(TAG, "Shutdown complete, status: $shutdownComplete")
            }

            // Status already set by onStatusChanged callback, but ensure it's set
            if (_networkStatus.value != NetworkStatus.SHUTDOWN && shutdownComplete == null) {
                _networkStatus.value = NetworkStatus.SHUTDOWN
            }

            Log.d(TAG, "ServiceReticulumProtocol shutdown complete")
        }
    }

    override suspend fun createIdentity(): Result<Identity> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.createIdentity()
            Log.d(TAG, "createIdentity response: $resultJson")
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.optString("error", "Unknown error"))
            }

            val hashStr = result.optString("hash", null)
            val publicKeyStr = result.optString("public_key", null)
            val privateKeyStr = result.optString("private_key", null)

            if (hashStr == null || publicKeyStr == null || privateKeyStr == null) {
                throw RuntimeException(
                    "Missing identity fields in response. hash=$hashStr, publicKey=$publicKeyStr, privateKey=$privateKeyStr",
                )
            }

            Identity(
                hash = hashStr.toByteArrayFromBase64() ?: byteArrayOf(),
                publicKey = publicKeyStr.toByteArrayFromBase64() ?: byteArrayOf(),
                privateKey = privateKeyStr.toByteArrayFromBase64(),
            )
        }
    }

    override suspend fun loadIdentity(path: String): Result<Identity> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.loadIdentity(path)
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.getString("error"))
            }

            Identity(
                hash = result.getString("hash").toByteArrayFromBase64() ?: byteArrayOf(),
                publicKey = result.getString("public_key").toByteArrayFromBase64() ?: byteArrayOf(),
                privateKey = result.getString("private_key").toByteArrayFromBase64(),
            )
        }
    }

    override suspend fun saveIdentity(
        identity: Identity,
        path: String,
    ): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.saveIdentity(identity.privateKey ?: byteArrayOf(), path)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }
        }
    }

    override suspend fun recallIdentity(hash: ByteArray): Identity? {
        // Not implemented yet
        return null
    }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            val resultJson = service.createIdentityWithName(displayName)

            val result = JSONObject(resultJson)
            val map = mutableMapOf<String, Any>()

            if (result.has("identity_hash")) {
                map["identity_hash"] = result.getString("identity_hash")
            }
            if (result.has("destination_hash")) {
                map["destination_hash"] = result.getString("destination_hash")
            }
            if (result.has("file_path")) {
                map["file_path"] = result.getString("file_path")
            }
            if (result.has("key_data")) {
                // Decode base64 back to ByteArray
                result.getString("key_data").toByteArrayFromBase64()?.let { keyData ->
                    map["key_data"] = keyData
                }
            }
            if (result.has("error")) {
                map["error"] = result.getString("error")
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to create identity with name", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun deleteIdentityFile(identityHash: String): Map<String, Any> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            val resultJson = service.deleteIdentityFile(identityHash)

            val result = JSONObject(resultJson)
            val map = mutableMapOf<String, Any>()

            if (result.has("success")) {
                map["success"] = result.getBoolean("success")
            }
            if (result.has("error")) {
                map["error"] = result.getString("error")
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to delete identity file", e)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            val resultJson = service.importIdentityFile(fileData, displayName)

            val result = JSONObject(resultJson)
            val map = mutableMapOf<String, Any>()

            if (result.has("identity_hash")) {
                map["identity_hash"] = result.getString("identity_hash")
            }
            if (result.has("destination_hash")) {
                map["destination_hash"] = result.getString("destination_hash")
            }
            if (result.has("file_path")) {
                map["file_path"] = result.getString("file_path")
            }
            if (result.has("key_data")) {
                // Decode base64 back to ByteArray
                result.getString("key_data").toByteArrayFromBase64()?.let { keyData ->
                    map["key_data"] = keyData
                }
            }
            if (result.has("error")) {
                map["error"] = result.getString("error")
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to import identity file", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            service.exportIdentityFile(identityHash, filePath)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to export identity file", e)
            ByteArray(0)
        }
    }

    override suspend fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): Map<String, Any> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            val resultJson = service.recoverIdentityFile(identityHash, keyData, filePath)

            val result = JSONObject(resultJson)
            val map = mutableMapOf<String, Any>()

            if (result.has("success")) {
                map["success"] = result.getBoolean("success")
            }
            if (result.has("file_path")) {
                map["file_path"] = result.getString("file_path")
            }
            if (result.has("error")) {
                map["error"] = result.getString("error")
            }

            map
        }.getOrElse { e ->
            Log.e(TAG, "Failed to recover identity file", e)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            // Build identity JSON (encode ByteArrays as Base64)
            val identityJson =
                JSONObject().apply {
                    put("hash", identity.hash.toBase64())
                    put("public_key", identity.publicKey.toBase64())
                    put("private_key", identity.privateKey.toBase64())
                }.toString()

            // Build aspects JSON
            val aspectsJson = JSONArray(aspects).toString()

            val directionStr =
                when (direction) {
                    is Direction.IN -> "IN"
                    is Direction.OUT -> "OUT"
                }

            val typeStr =
                when (type) {
                    is DestinationType.SINGLE -> "SINGLE"
                    is DestinationType.GROUP -> "GROUP"
                    is DestinationType.PLAIN -> "PLAIN"
                }

            val resultJson =
                service.createDestination(
                    identityJson,
                    directionStr,
                    typeStr,
                    appName,
                    aspectsJson,
                )
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.getString("error"))
            }

            Destination(
                hash = result.getString("hash").toByteArrayFromBase64() ?: byteArrayOf(),
                hexHash = result.getString("hex_hash"),
                identity = identity,
                direction = direction,
                type = type,
                appName = appName,
                aspects = aspects,
            )
        }
    }

    override suspend fun announceDestination(
        destination: Destination,
        appData: ByteArray?,
    ): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.announceDestination(destination.hash, appData)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }
        }
    }

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val typeStr =
                when (packetType) {
                    is PacketType.DATA -> "DATA"
                    is PacketType.ANNOUNCE -> "ANNOUNCE"
                    is PacketType.LINKREQUEST -> "LINKREQUEST"
                    is PacketType.PROOF -> "PROOF"
                }

            val resultJson = service.sendPacket(destination.hash, data, typeStr)
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.getString("error"))
            }

            PacketReceipt(
                hash = result.getString("receipt_hash").toByteArrayFromBase64() ?: byteArrayOf(),
                delivered = result.optBoolean("delivered", false),
                timestamp = result.optLong("timestamp", System.currentTimeMillis()),
            )
        }
    }

    override fun observePackets(): Flow<ReceivedPacket> = packetFlow

    override suspend fun establishLink(destination: Destination): Result<Link> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override suspend fun closeLink(link: Link): Result<Unit> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override suspend fun sendOverLink(
        link: Link,
        data: ByteArray,
    ): Result<Unit> {
        return Result.failure(NotImplementedError("Links not yet implemented"))
    }

    override fun observeLinks(): Flow<LinkEvent> = linkFlow

    override suspend fun hasPath(destinationHash: ByteArray): Boolean {
        return try {
            service?.hasPath(destinationHash) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking path", e)
            false
        }
    }

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.requestPath(destinationHash)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }
        }
    }

    override fun getHopCount(destinationHash: ByteArray): Int? {
        return try {
            val count = service?.getHopCount(destinationHash) ?: -1
            if (count >= 0) count else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hop count", e)
            null
        }
    }

    override suspend fun getPathTableHashes(): List<String> {
        return try {
            val jsonString = service?.getPathTableHashes() ?: "[]"
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting path table hashes", e)
            emptyList()
        }
    }

    override fun observeAnnounces(): Flow<AnnounceEvent> = announceFlow

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
    ): Result<MessageReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val privateKey = sourceIdentity.privateKey ?: throw IllegalArgumentException("Source identity must have private key")

            val resultJson = service.sendLxmfMessage(destinationHash, content, privateKey, imageData, imageFormat)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val msgHash = result.optString("message_hash")?.toByteArrayFromBase64() ?: byteArrayOf()
            val timestamp = result.optLong("timestamp", System.currentTimeMillis())
            val destHash = result.optString("destination_hash")?.toByteArrayFromBase64() ?: byteArrayOf()

            MessageReceipt(
                messageHash = msgHash,
                timestamp = timestamp,
                destinationHash = destHash,
            )
        }
    }

    override fun observeMessages(): Flow<ReceivedMessage> = messageFlow

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = deliveryStatusFlow

    /**
     * Restore peer identities from stored public keys to enable message sending to known peers.
     * This should be called after initialization to restore identities from the database.
     */
    suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            Log.d(TAG, "restorePeerIdentities: Processing ${peerIdentities.size} peer identities")

            // Build JSON array of peer identities
            val identitiesArray = JSONArray()
            for ((index, pair) in peerIdentities.withIndex()) {
                val (hashStr, publicKey) = pair

                // Log details for first few identities
                if (index < 3) {
                    Log.d(TAG, "restorePeerIdentities: [$index] hash=$hashStr, publicKeyLength=${publicKey.size}")
                }

                val base64Key = publicKey.toBase64()
                if (base64Key == null) {
                    Log.e(TAG, "restorePeerIdentities: [$index] Failed to encode public key to base64 for hash $hashStr")
                    continue
                }

                val identityObj =
                    JSONObject().apply {
                        put("identity_hash", hashStr) // Changed from "hash" to "identity_hash" to match Python expectations
                        put("public_key", base64Key)
                    }
                identitiesArray.put(identityObj)
            }

            Log.d(TAG, "restorePeerIdentities: Built JSON array with ${identitiesArray.length()} identities")

            // Log a sample of the JSON for debugging
            if (identitiesArray.length() > 0) {
                Log.d(TAG, "restorePeerIdentities: Sample JSON: ${identitiesArray.getJSONObject(0)}")
            }

            val resultJson = service.restorePeerIdentities(identitiesArray.toString())
            Log.d(TAG, "restorePeerIdentities: Got result from service: $resultJson")

            val result = JSONObject(resultJson)

            if (result.optBoolean("success", false)) {
                val restoredCount = result.optInt("restored_count", 0)
                Log.d(TAG, "Restored $restoredCount peer identities")
                restoredCount
            } else {
                val error = result.optString("error", "Unknown error")
                Log.e(TAG, "Failed to restore peer identities: $error")
                throw RuntimeException("Failed to restore peer identities: $error")
            }
        }
    }

    suspend fun getLxmfDestination(): Result<com.lxmf.messenger.reticulum.model.Destination> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.lxmfDestination
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.optString("error", "Unknown error"))
            }

            val hashStr = result.optString("hash", null)
            val hexHash = result.optString("hex_hash", null)

            if (hashStr == null || hexHash == null) {
                throw RuntimeException("Missing LXMF destination fields")
            }

            // Get the identity to construct full destination object
            val identity = getLxmfIdentity().getOrThrow()

            com.lxmf.messenger.reticulum.model.Destination(
                hash = hashStr.toByteArrayFromBase64() ?: byteArrayOf(),
                hexHash = hexHash,
                identity = identity,
                direction = com.lxmf.messenger.reticulum.model.Direction.IN,
                type = com.lxmf.messenger.reticulum.model.DestinationType.SINGLE,
                appName = "lxmf",
                aspects = listOf("delivery"),
            )
        }
    }

    suspend fun getLxmfIdentity(): Result<Identity> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.lxmfIdentity
            val result = JSONObject(resultJson)

            if (result.has("error")) {
                throw RuntimeException(result.optString("error", "Unknown error"))
            }

            val hashStr = result.optString("hash", null)
            val publicKeyStr = result.optString("public_key", null)
            val privateKeyStr = result.optString("private_key", null)

            if (hashStr == null || publicKeyStr == null || privateKeyStr == null) {
                throw RuntimeException("Missing LXMF identity fields")
            }

            Identity(
                hash = hashStr.toByteArrayFromBase64() ?: byteArrayOf(),
                publicKey = publicKeyStr.toByteArrayFromBase64() ?: byteArrayOf(),
                privateKey = privateKeyStr.toByteArrayFromBase64(),
            )
        }
    }

    override suspend fun getDebugInfo(): Map<String, Any> {
        return try {
            val service = this.service ?: return mapOf("error" to "Service not bound")

            val resultJson = service.debugInfo
            val result = JSONObject(resultJson)

            val debugInfo = mutableMapOf<String, Any>()
            debugInfo["initialized"] = result.optBoolean("initialized", false)
            debugInfo["reticulum_available"] = result.optBoolean("reticulum_available", false)
            debugInfo["storage_path"] = result.optString("storage_path", "")
            debugInfo["identity_count"] = result.optInt("identity_count", 0)
            debugInfo["destination_count"] = result.optInt("destination_count", 0)
            debugInfo["pending_announces"] = result.optInt("pending_announces", 0)
            debugInfo["transport_enabled"] = result.optBoolean("transport_enabled", false)
            debugInfo["multicast_lock_held"] = result.optBoolean("multicast_lock_held", false)
            debugInfo["wifi_lock_held"] = result.optBoolean("wifi_lock_held", false)
            debugInfo["wake_lock_held"] = result.optBoolean("wake_lock_held", false)

            // Extract interfaces
            val interfacesList = mutableListOf<Map<String, Any>>()
            val interfacesArray = result.optJSONArray("interfaces")
            if (interfacesArray != null) {
                for (i in 0 until interfacesArray.length()) {
                    val iface = interfacesArray.getJSONObject(i)
                    interfacesList.add(
                        mapOf(
                            "name" to iface.optString("name", ""),
                            "type" to iface.optString("type", ""),
                            "online" to iface.optBoolean("online", false),
                        ),
                    )
                }
            }
            debugInfo["interfaces"] = interfacesList

            debugInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error getting debug info", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    override fun setConversationActive(active: Boolean) {
        try {
            service?.setConversationActive(active)
            Log.d(TAG, "Set conversation active state: $active")
        } catch (e: RemoteException) {
            Log.e(TAG, "Error setting conversation active state", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error setting conversation active state", e)
        }
    }

    /**
     * Get BLE connection details from the service.
     */
    fun getBleConnectionDetails(): String {
        return try {
            val service = this.service ?: return "[]"
            service.bleConnectionDetails
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BLE connection details", e)
            "[]"
        }
    }

    /**
     * Trigger an auto-announce with the provided display name.
     * This is a convenience method used by the auto-announce feature that handles
     * retrieving the LXMF identity and destination automatically.
     *
     * @param displayName The display name to include in the announce app_data
     * @return Result indicating success or failure
     */
    suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> {
        return runCatching {
            Log.d(TAG, "Triggering auto-announce with display name: $displayName")

            // Get LXMF identity and destination
            val identity = getLxmfIdentity().getOrThrow()
            val destination = getLxmfDestination().getOrThrow()

            // Announce with display name as app_data
            announceDestination(
                destination = destination,
                appData = displayName.toByteArray(),
            ).getOrThrow()

            Log.d(TAG, "Auto-announce successful")
        }
    }

    // Helper extension functions
    private fun String.toByteArrayFromBase64(): ByteArray? {
        return try {
            android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun ByteArray?.toBase64(): String? {
        return this?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
