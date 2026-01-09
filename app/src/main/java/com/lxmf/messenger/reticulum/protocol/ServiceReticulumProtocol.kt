package com.lxmf.messenger.reticulum.protocol

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R
import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.model.PacketType
import com.lxmf.messenger.reticulum.model.ReceivedPacket
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.service.ReticulumService
import com.lxmf.messenger.service.manager.parseIdentityResultJson
import com.lxmf.messenger.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
        private const val BIND_TIMEOUT_MS = 30_000L // 30s timeout for service binding

        // Auto-rebind configuration for when Android kills the service process
        private const val REBIND_INITIAL_DELAY_MS = 1_000L // 1 second initial delay
        private const val REBIND_MAX_DELAY_MS = 60_000L // Max 60 seconds between attempts
        private const val REBIND_MAX_ATTEMPTS = 10 // Maximum rebind attempts before giving up
        private const val REBIND_BACKOFF_MULTIPLIER = 2.0 // Exponential backoff multiplier

        // Notification constants - must match ServiceNotificationManager
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "reticulum_service"
    }

    private var service: IReticulumService? = null
    private var isBound = false

    // Auto-rebind state tracking
    private var isIntentionalUnbind = false // True when we explicitly call unbindService()
    private var rebindAttempts = 0
    private var rebindJob: kotlinx.coroutines.Job? = null

    // Thread-safety: Lock for service binding state (continuation, service, isBound)
    private val bindLock = Any()

    // Lifecycle-aware coroutine scope for background operations
    // SupervisorJob ensures one failed coroutine doesn't cancel others
    private val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Phase 2.1: StateFlow for reactive status updates (replaces polling)
    // Initialize to CONNECTING since we don't know service state until we query it
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.CONNECTING)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // SharedFlow for interface status change events (triggers UI refresh)
    // replay=0 means events are not replayed to late subscribers
    private val _interfaceStatusChanged = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val interfaceStatusChanged: SharedFlow<Unit> = _interfaceStatusChanged.asSharedFlow()

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

    // Event-driven flows (Phase: eliminate polling)
    // BLE connection state changes
    private val _bleConnectionsFlow =
        MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 1,
        )
    val bleConnectionsFlow: SharedFlow<String> = _bleConnectionsFlow.asSharedFlow()

    // Debug info changes (lock state, interface status, etc.)
    private val _debugInfoFlow =
        MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 1,
        )
    val debugInfoFlow: SharedFlow<String> = _debugInfoFlow.asSharedFlow()

    // Interface online/offline status changes
    private val _interfaceStatusFlow =
        MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 1,
        )
    val interfaceStatusFlow: SharedFlow<String> = _interfaceStatusFlow.asSharedFlow()

    // Location telemetry from contacts sharing their location
    private val _locationTelemetryFlow =
        MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val locationTelemetryFlow: SharedFlow<String> = _locationTelemetryFlow.asSharedFlow()

    // Emoji reactions received for messages
    private val _reactionReceivedFlow =
        MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val reactionReceivedFlow: SharedFlow<String> = _reactionReceivedFlow.asSharedFlow()

    // Propagation sync state changes (for real-time sync progress)
    private val _propagationStateFlow =
        MutableSharedFlow<PropagationState>(
            replay = 1,
            extraBufferCapacity = 1,
        )
    val propagationStateFlow: SharedFlow<PropagationState> = _propagationStateFlow.asSharedFlow()

    /**
     * Handler for alternative relay requests from the service.
     * Set by ColumbaApplication to provide PropagationNodeManager integration.
     * Called when Python needs an alternative relay for message retry.
     *
     * @param excludeHashes List of relay hashes to exclude
     * @param callback Function to call with the alternative relay hash (or null if none)
     */
    var alternativeRelayHandler: (suspend (excludeHashes: List<String>) -> ByteArray?)? = null

    /**
     * Callback invoked when the service is reconnected but needs reinitialization.
     * This happens when Android killed the service process and we successfully rebound,
     * but Python/Reticulum hasn't been started yet (service reports SHUTDOWN status).
     *
     * Set by ColumbaApplication to trigger the normal initialization flow
     * (build config, call initialize(), restore peer identities, etc.)
     */
    var onServiceNeedsInitialization: (suspend () -> Unit)? = null

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
            @Suppress("LongMethod")
            override fun onAnnounce(announceJson: String) {
                try {
                    Log.i(TAG, "🔔 ServiceCallback.onAnnounce() CALLED - announce received from ReticulumService!")
                    Log.i(TAG, "   JSON length: ${announceJson.length} bytes")

                    val json = JSONObject(announceJson)

                    val destinationHash = json.optString("destination_hash").toByteArrayFromBase64()
                    val identityHash = json.optString("identity_hash").toByteArrayFromBase64()
                    val publicKey = json.optString("public_key").toByteArrayFromBase64()
                    val appData = json.optString("app_data").toByteArrayFromBase64()
                    val hops = json.optInt("hops", 0)
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    val aspect = if (json.has("aspect") && !json.isNull("aspect")) json.optString("aspect") else null
                    val receivingInterface =
                        if (json.has("interface") && !json.isNull("interface")) {
                            json.optString("interface").takeIf { it.isNotBlank() && it != "None" }
                        } else {
                            null
                        }
                    val displayName =
                        if (json.has("display_name") && !json.isNull("display_name")) {
                            json.optString("display_name").takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    val stampCost =
                        if (json.has("stamp_cost") && !json.isNull("stamp_cost")) {
                            json.optInt("stamp_cost")
                        } else {
                            null
                        }
                    val stampCostFlexibility =
                        if (json.has("stamp_cost_flexibility") && !json.isNull("stamp_cost_flexibility")) {
                            json.optInt("stamp_cost_flexibility")
                        } else {
                            null
                        }
                    val peeringCost =
                        if (json.has("peering_cost") && !json.isNull("peering_cost")) {
                            json.optInt("peering_cost")
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
                                displayName = displayName,
                                stampCost = stampCost,
                                stampCostFlexibility = stampCostFlexibility,
                                peeringCost = peeringCost,
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
                    val message = parseMessageJson(messageJson)
                    messageFlow.tryEmit(message)
                    Log.d(TAG, "Message received via service: ${message.messageHash.take(16)} (publicKey=${message.publicKey != null})")
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
                // Handle RNode online/offline status changes - emit event to trigger UI refresh
                if (status == "RNODE_ONLINE" || status == "RNODE_OFFLINE") {
                    Log.d(TAG, "████ RNODE STATUS EVENT ████ $status - triggering interface refresh")
                    _interfaceStatusChanged.tryEmit(Unit)
                    return // Don't update network status for interface-specific events
                }

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

            override fun onAlternativeRelayRequested(requestJson: String) {
                try {
                    Log.d(TAG, "Alternative relay requested: $requestJson")
                    val json = JSONObject(requestJson)

                    // Parse exclude_relays list from request
                    val excludeArray = json.optJSONArray("exclude_relays")
                    val excludeHashes = mutableListOf<String>()
                    if (excludeArray != null) {
                        for (i in 0 until excludeArray.length()) {
                            excludeHashes.add(excludeArray.getString(i))
                        }
                    }

                    // Call the handler asynchronously
                    protocolScope.launch {
                        try {
                            val handler = alternativeRelayHandler
                            if (handler != null) {
                                val alternativeRelay = handler(excludeHashes)
                                // Provide the result back to the service
                                service?.provideAlternativeRelay(alternativeRelay)
                                Log.d(
                                    TAG,
                                    "Alternative relay provided: ${alternativeRelay?.toHexString()?.take(16) ?: "null"}",
                                )
                            } else {
                                Log.w(TAG, "No alternative relay handler set, providing null")
                                service?.provideAlternativeRelay(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling alternative relay request", e)
                            service?.provideAlternativeRelay(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing alternative relay request", e)
                }
            }

            override fun onBleConnectionChanged(connectionDetailsJson: String) {
                try {
                    Log.d(TAG, "BLE connection changed: ${connectionDetailsJson.take(100)}...")
                    _bleConnectionsFlow.tryEmit(connectionDetailsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling BLE connection callback", e)
                }
            }

            override fun onDebugInfoChanged(debugInfoJson: String) {
                try {
                    Log.d(TAG, "Debug info changed")
                    _debugInfoFlow.tryEmit(debugInfoJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling debug info callback", e)
                }
            }

            override fun onInterfaceStatusChanged(interfaceStatusJson: String) {
                try {
                    Log.d(TAG, "Interface status changed: $interfaceStatusJson")
                    _interfaceStatusFlow.tryEmit(interfaceStatusJson)
                    // Also trigger the existing interface status changed flow for backwards compatibility
                    _interfaceStatusChanged.tryEmit(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling interface status callback", e)
                }
            }

            override fun onLocationTelemetry(locationJson: String) {
                try {
                    Log.d(TAG, "📍 Location telemetry received: ${locationJson.take(100)}...")
                    _locationTelemetryFlow.tryEmit(locationJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling location telemetry callback", e)
                }
            }

            override fun onReactionReceived(reactionJson: String) {
                try {
                    Log.d(TAG, "😀 Reaction received: $reactionJson")
                    _reactionReceivedFlow.tryEmit(reactionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling reaction received callback", e)
                }
            }

            override fun onPropagationStateChanged(stateJson: String) {
                try {
                    Log.d(TAG, "Propagation state changed: $stateJson")
                    val json = JSONObject(stateJson)
                    val state =
                        PropagationState(
                            state = json.optInt("state", 0),
                            stateName = json.optString("state_name", "unknown"),
                            progress = json.optDouble("progress", 0.0).toFloat(),
                            messagesReceived = json.optInt("messages_received", 0),
                        )
                    _propagationStateFlow.tryEmit(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling propagation state callback", e)
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

                // Track if this was a rebind (service process was killed and restarted)
                // Must capture this BEFORE resetting rebindAttempts
                val wasRebind = rebindAttempts > 0

                // Reset rebind state on successful connection
                if (wasRebind) {
                    Log.i(TAG, "Service reconnected successfully after $rebindAttempts rebind attempt(s)")
                }
                rebindAttempts = 0
                rebindJob?.cancel()
                rebindJob = null
                isIntentionalUnbind = false // Reset for future disconnects

                // Query actual service status immediately to update UI (with timeout)
                protocolScope.launch(Dispatchers.IO) {
                    try {
                        val actualStatus =
                            withTimeoutOrNull(3000) {
                                service?.getStatus()
                            }
                        val newNetworkStatus =
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
                        _networkStatus.value = newNetworkStatus
                        Log.d(
                            TAG,
                            "Detected actual service status on reconnect: $actualStatus (wasRebind=$wasRebind)",
                        )

                        // Persist the detected status
                        if (actualStatus != null) {
                            try {
                                settingsRepository.saveServiceStatus(actualStatus)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist detected status", e)
                            }
                        }

                        // If this was a rebind and service needs initialization, trigger the callback
                        // This happens when Android killed the service process and we successfully rebound,
                        // but Python/Reticulum hasn't been started (service reports SHUTDOWN)
                        if (wasRebind && newNetworkStatus == NetworkStatus.SHUTDOWN) {
                            val initCallback = onServiceNeedsInitialization
                            if (initCallback != null) {
                                Log.i(TAG, "Service reconnected but needs initialization - triggering callback")
                                try {
                                    initCallback()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error during reinitialization callback", e)
                                    _networkStatus.value = NetworkStatus.ERROR("Reinitialization failed: ${e.message}")
                                }
                            } else {
                                Log.w(TAG, "Service needs initialization but no callback is set")
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
                Log.w(TAG, "Service disconnected unexpectedly - process may have been killed by Android")
                service = null
                isBound = false

                // Attempt to rebind if this wasn't an intentional disconnect
                // onServiceDisconnected is only called when the service process dies unexpectedly,
                // NOT when we call unbindService() explicitly
                if (!isIntentionalUnbind) {
                    Log.i(TAG, "Initiating automatic rebind to recover service connection")
                    // Update the notification from app process to show reconnecting status
                    // This overwrites the stale "Connected" notification from the dead service process
                    updateNotificationFromAppProcess("CONNECTING")
                    attemptRebind()
                } else {
                    _networkStatus.value = NetworkStatus.SHUTDOWN
                    updateNotificationFromAppProcess("SHUTDOWN")
                }
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
     *
     * @throws TimeoutCancellationException if service doesn't become ready within BIND_TIMEOUT_MS
     */
    suspend fun bindService() =
        withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    // Reset rebind state for fresh binding attempt
                    isIntentionalUnbind = false
                    rebindAttempts = 0

                    bindStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Binding to ReticulumService (timeout: ${BIND_TIMEOUT_MS}ms)...")

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

                // Cleanup on cancellation (including timeout)
                continuation.invokeOnCancellation {
                    Log.d(TAG, "bindService continuation cancelled")
                    synchronized(bindLock) {
                        readinessContinuation = null
                    }
                }
            }
        }

    /**
     * Unbind from the service.
     */
    fun unbindService() {
        try {
            if (isBound) {
                isIntentionalUnbind = true // Mark as intentional so we don't auto-rebind
                rebindJob?.cancel() // Cancel any pending rebind attempts
                rebindJob = null
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
        isIntentionalUnbind = true // Prevent any rebind attempts during cleanup
        rebindJob?.cancel()
        rebindJob = null
        protocolScope.cancel()
        unbindService()
    }

    /**
     * Update the foreground service notification from the app process.
     * This is used when the service process dies and the notification becomes stale.
     * By posting a new notification with the same ID, we overwrite the stale one.
     *
     * @param status The status to display: "CONNECTING", "SHUTDOWN", "ERROR:message", etc.
     */
    private fun updateNotificationFromAppProcess(status: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val (statusText, detailText) =
                when {
                    status == "READY" ->
                        "Connected - Mesh network active" to
                            "Background service running. Keep battery optimization disabled for reliable message delivery."
                    status == "INITIALIZING" -> "Starting mesh network..." to "Connecting to mesh network..."
                    status == "CONNECTING" -> "Reconnecting..." to "Service was interrupted. Attempting to reconnect..."
                    status.startsWith("ERROR:") -> "Error - Tap to view" to status.substringAfter("ERROR:")
                    else -> "Disconnected" to "Service not running"
                }

            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Columba Mesh Network")
                    .setContentText(statusText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Updated notification from app process: $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification from app process", e)
        }
    }

    /**
     * Attempt to rebind to the service after unexpected disconnection.
     * Uses exponential backoff to avoid overwhelming the system.
     */
    private fun attemptRebind() {
        // Don't rebind if we intentionally unbound
        if (isIntentionalUnbind) {
            Log.d(TAG, "Skipping rebind - unbind was intentional")
            return
        }

        // Don't exceed max attempts
        if (rebindAttempts >= REBIND_MAX_ATTEMPTS) {
            Log.e(TAG, "Max rebind attempts ($REBIND_MAX_ATTEMPTS) reached, giving up")
            _networkStatus.value = NetworkStatus.ERROR("Service connection lost - please restart the app")
            updateNotificationFromAppProcess("ERROR:Connection lost - please restart the app")
            return
        }

        // Cancel any existing rebind job
        rebindJob?.cancel()

        // Calculate delay with exponential backoff
        val multiplier = REBIND_BACKOFF_MULTIPLIER
        var backoffFactor = 1.0
        repeat(rebindAttempts) { backoffFactor *= multiplier }
        val delay =
            minOf(
                (REBIND_INITIAL_DELAY_MS * backoffFactor).toLong(),
                REBIND_MAX_DELAY_MS,
            )

        rebindAttempts++
        Log.i(TAG, "Scheduling rebind attempt $rebindAttempts/$REBIND_MAX_ATTEMPTS in ${delay}ms")

        // Update status to show we're reconnecting
        _networkStatus.value = NetworkStatus.CONNECTING

        rebindJob =
            protocolScope.launch {
                try {
                    kotlinx.coroutines.delay(delay)

                    // Double-check we still need to rebind
                    if (isIntentionalUnbind || isBound) {
                        Log.d(TAG, "Rebind cancelled - intentional=$isIntentionalUnbind, bound=$isBound")
                        return@launch
                    }

                    Log.i(TAG, "Attempting rebind to ReticulumService (attempt $rebindAttempts)")

                    // Start the service first (it may have been killed)
                    val startIntent =
                        Intent(context, ReticulumService::class.java).apply {
                            action = ReticulumService.ACTION_START
                        }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }

                    // Bind to the service
                    val bindIntent = Intent(context, ReticulumService::class.java)
                    val bound =
                        context.bindService(
                            bindIntent,
                            serviceConnection,
                            Context.BIND_AUTO_CREATE,
                        )

                    if (bound) {
                        Log.i(TAG, "Rebind initiated successfully")
                        // Note: onServiceConnected will be called and will reset rebindAttempts
                    } else {
                        Log.e(TAG, "Failed to initiate rebind, scheduling retry")
                        attemptRebind() // Try again with increased delay
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during rebind attempt", e)
                    if (!isIntentionalUnbind) {
                        attemptRebind() // Try again
                    }
                }
            }
    }

    /**
     * Get the current service status.
     * @return Result with status string: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     */
    fun getStatus(): Result<String> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            service.getStatus()
        }
    }

    /**
     * Check if the service is initialized and ready to use.
     * @return Result with true if initialized, false otherwise
     */
    fun isInitialized(): Result<Boolean> {
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
                            Log.d(TAG, "Initialization successful: $result")

                            // Parse and save shared instance status
                            try {
                                val jsonResult = JSONObject(result)
                                val isSharedInstance = jsonResult.optBoolean("is_shared_instance", false)
                                Log.d(TAG, "Shared instance mode: $isSharedInstance")

                                // Save to settings repository (launch in scope since we're in callback)
                                protocolScope.launch {
                                    try {
                                        settingsRepository.saveIsSharedInstance(isSharedInstance)
                                        Log.d(TAG, "Saved shared instance status to settings")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to save shared instance status", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse initialization result", e)
                            }

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

    @androidx.annotation.VisibleForTesting
    internal fun buildConfigJson(config: ReticulumConfig): String {
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
                    iface.networkName?.let { ifaceJson.put("network_name", it) }
                    iface.passphrase?.let { ifaceJson.put("passphrase", it) }
                }
                is InterfaceConfig.RNode -> {
                    ifaceJson.put("type", "RNode")
                    ifaceJson.put("name", iface.name)
                    ifaceJson.put("target_device_name", iface.targetDeviceName)
                    ifaceJson.put("connection_mode", iface.connectionMode)
                    iface.tcpHost?.let { ifaceJson.put("tcp_host", it) }
                    ifaceJson.put("tcp_port", iface.tcpPort)
                    ifaceJson.put("frequency", iface.frequency)
                    ifaceJson.put("bandwidth", iface.bandwidth)
                    ifaceJson.put("tx_power", iface.txPower)
                    ifaceJson.put("spreading_factor", iface.spreadingFactor)
                    ifaceJson.put("coding_rate", iface.codingRate)
                    iface.stAlock?.let { ifaceJson.put("st_alock", it) }
                    iface.ltAlock?.let { ifaceJson.put("lt_alock", it) }
                    ifaceJson.put("mode", iface.mode)
                    ifaceJson.put("enable_framebuffer", iface.enableFramebuffer)
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

        // Shared instance preference
        json.put("prefer_own_instance", config.preferOwnInstance)

        // RPC key for shared instance authentication (optional)
        config.rpcKey?.let { json.put("rpc_key", it) }

        // Transport node setting
        json.put("enable_transport", config.enableTransport)

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
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.recallIdentity(hash)
            val result = JSONObject(resultJson)

            if (result.optBoolean("found", false)) {
                val publicKeyHex = result.optString("public_key", "")
                if (publicKeyHex.isNotEmpty()) {
                    val publicKey =
                        publicKeyHex
                            .chunked(2)
                            .map { it.toInt(16).toByte() }
                            .toByteArray()
                    // Return an Identity with just the public key
                    // The hash is derived from the public key in Reticulum
                    Identity(
                        hash = hash,
                        publicKey = publicKey,
                        // We don't have the private key for recalled identities
                        privateKey = null,
                    )
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")
            val resultJson = service.createIdentityWithName(displayName)
            parseIdentityResultJson(resultJson)
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
            parseIdentityResultJson(resultJson)
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

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult {
        return try {
            val service =
                this.service ?: return LinkSpeedProbeResult(
                    status = "not_bound",
                    establishmentRateBps = null,
                    expectedRateBps = null,
                    rttSeconds = null,
                    hops = null,
                    linkReused = false,
                    error = "Service not bound",
                )

            val resultJson = service.probeLinkSpeed(destinationHash, timeoutSeconds, deliveryMethod)
            val result = JSONObject(resultJson)

            LinkSpeedProbeResult(
                status = result.optString("status", "error"),
                establishmentRateBps = if (result.isNull("establishment_rate_bps")) null else result.optLong("establishment_rate_bps"),
                expectedRateBps = if (result.isNull("expected_rate_bps")) null else result.optLong("expected_rate_bps"),
                rttSeconds = if (result.isNull("rtt_seconds")) null else result.optDouble("rtt_seconds"),
                hops = if (result.isNull("hops")) null else result.optInt("hops"),
                linkReused = result.optBoolean("link_reused", false),
                nextHopBitrateBps = if (result.isNull("next_hop_bitrate_bps")) null else result.optLong("next_hop_bitrate_bps"),
                error = result.optString("error").takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error probing link speed", e)
            LinkSpeedProbeResult(
                status = "error",
                establishmentRateBps = null,
                expectedRateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
                error = e.message,
            )
        }
    }

    // ==================== Conversation Link Management ====================

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> =
        runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.establishLink(destinationHash, timeoutSeconds)
            val result = JSONObject(resultJson)

            ConversationLinkResult(
                isActive = result.optBoolean("link_active", false),
                establishmentRateBps =
                    if (result.isNull("establishment_rate_bps")) {
                        null
                    } else {
                        result.optLong("establishment_rate_bps")
                    },
                alreadyExisted = result.optBoolean("already_existed", false),
                error = result.optString("error").takeIf { it.isNotEmpty() },
            )
        }

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> =
        runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.closeLink(destinationHash)
            val result = JSONObject(resultJson)

            result.optBoolean("was_active", false)
        }

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult {
        return try {
            val service =
                this.service ?: return ConversationLinkResult(
                    isActive = false,
                    error = "Service not bound",
                )

            val resultJson = service.getLinkStatus(destinationHash)
            val result = JSONObject(resultJson)

            ConversationLinkResult(
                isActive = result.optBoolean("active", false),
                establishmentRateBps =
                    if (result.isNull("establishment_rate_bps")) {
                        null
                    } else {
                        result.optLong("establishment_rate_bps")
                    },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting link status", e)
            ConversationLinkResult(isActive = false, error = e.message)
        }
    }

    override fun observeAnnounces(): Flow<AnnounceEvent> = announceFlow

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val privateKey = sourceIdentity.privateKey ?: throw IllegalArgumentException("Source identity must have private key")

            // Convert List<Pair<String, ByteArray>> to Map<String, ByteArray> for AIDL
            val fileAttachmentsMap = fileAttachments?.associate { (filename, bytes) -> filename to bytes }

            val resultJson = service.sendLxmfMessage(destinationHash, content, privateKey, imageData, imageFormat, fileAttachmentsMap)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val msgHash = result.optString("message_hash").toByteArrayFromBase64() ?: byteArrayOf()
            val timestamp = result.optLong("timestamp", System.currentTimeMillis())
            val destHash = result.optString("destination_hash").toByteArrayFromBase64() ?: byteArrayOf()

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
    fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> {
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
                error("Failed to restore peer identities: $error")
            }
        }
    }

    /**
     * Restore announce identities from stored public keys to enable message sending to announced peers.
     * Uses bulk restore with direct dict population for maximum performance.
     */
    fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            Log.d(TAG, "restoreAnnounceIdentities: Processing ${announces.size} announce identities")

            // Build JSON array of announce identities
            val announcesArray = JSONArray()
            for ((index, pair) in announces.withIndex()) {
                val (destHashStr, publicKey) = pair

                // Log details for first few announces
                if (index < 3) {
                    Log.d(TAG, "restoreAnnounceIdentities: [$index] destHash=$destHashStr, publicKeyLength=${publicKey.size}")
                }

                val base64Key = publicKey.toBase64()
                if (base64Key == null) {
                    Log.e(TAG, "restoreAnnounceIdentities: [$index] Failed to encode public key to base64 for hash $destHashStr")
                    continue
                }

                val announceObj =
                    JSONObject().apply {
                        put("destination_hash", destHashStr)
                        put("public_key", base64Key)
                    }
                announcesArray.put(announceObj)
            }

            Log.d(TAG, "restoreAnnounceIdentities: Built JSON array with ${announcesArray.length()} announces")

            val resultJson = service.restoreAnnounceIdentities(announcesArray.toString())
            Log.d(TAG, "restoreAnnounceIdentities: Got result from service: $resultJson")

            val result = JSONObject(resultJson)

            if (result.optBoolean("success", false)) {
                val restoredCount = result.optInt("restored_count", 0)
                Log.d(TAG, "Restored $restoredCount announce identities")
                restoredCount
            } else {
                val error = result.optString("error", "Unknown error")
                Log.e(TAG, "Failed to restore announce identities: $error")
                error("Failed to restore announce identities: $error")
            }
        }
    }

    @Suppress("ThrowsCount") // Multiple validation checks require distinct error messages
    fun getLxmfDestination(): Result<com.lxmf.messenger.reticulum.model.Destination> {
        return runCatching {
            val service = checkNotNull(this.service) { "Service not bound" }

            val resultJson = service.lxmfDestination
            val result = JSONObject(resultJson)

            require(!result.has("error")) { result.optString("error", "Unknown error") }

            val hashStr = result.optString("hash", null)
            val hexHash = result.optString("hex_hash", null)

            require(hashStr != null && hexHash != null) { "Missing LXMF destination fields" }

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

    @Suppress("ThrowsCount") // Multiple validation checks require distinct error messages
    fun getLxmfIdentity(): Result<Identity> {
        return runCatching {
            val service = checkNotNull(this.service) { "Service not bound" }

            val resultJson = service.lxmfIdentity
            val result = JSONObject(resultJson)

            require(!result.has("error")) { result.optString("error", "Unknown error") }

            val hashStr = result.optString("hash", null)
            val publicKeyStr = result.optString("public_key", null)
            val privateKeyStr = result.optString("private_key", null)

            require(hashStr != null && publicKeyStr != null && privateKeyStr != null) {
                "Missing LXMF identity fields"
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

            // Process persistence debug info
            debugInfo["heartbeat_age_seconds"] = result.optLong("heartbeat_age_seconds", -1)
            debugInfo["health_check_running"] = result.optBoolean("health_check_running", false)
            debugInfo["network_monitor_running"] = result.optBoolean("network_monitor_running", false)
            debugInfo["maintenance_running"] = result.optBoolean("maintenance_running", false)
            debugInfo["last_lock_refresh_age_seconds"] = result.optLong("last_lock_refresh_age_seconds", -1)
            debugInfo["failed_interface_count"] = result.optInt("failed_interface_count", 0)

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

    override suspend fun getFailedInterfaces(): List<FailedInterface> {
        return try {
            val service = this.service ?: return emptyList()
            val resultJson = service.failedInterfaces
            FailedInterface.parseFromJson(resultJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting failed interfaces", e)
            emptyList()
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

    override suspend fun reconnectRNodeInterface() {
        try {
            service?.reconnectRNodeInterface()
            Log.i(TAG, "Triggered RNode interface reconnection")
        } catch (e: RemoteException) {
            Log.e(TAG, "Error triggering RNode reconnection", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error triggering RNode reconnection", e)
        }
    }

    // ==================== PROPAGATION NODE SUPPORT ====================

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.setOutboundPropagationNode(destHash)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            Log.d(TAG, "Propagation node ${if (destHash != null) "set to ${destHash.toHexString()}" else "cleared"}")
        }
    }

    override suspend fun getOutboundPropagationNode(): Result<String?> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.getOutboundPropagationNode()
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            result.optString("propagation_node").takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.requestMessagesFromPropagationNode(identityPrivateKey, maxMessages)
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val state = result.optInt("state", 0)
            PropagationState(
                state = state,
                stateName = result.optString("state_name", "unknown"),
                progress = result.optDouble("progress", 0.0).toFloat(),
                messagesReceived = result.optInt("messages_received", 0),
            )
        }
    }

    override suspend fun getPropagationState(): Result<PropagationState> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val resultJson = service.getPropagationState()
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            PropagationState(
                state = result.optInt("state", 0),
                stateName = result.optString("state_name", "unknown"),
                progress = result.optDouble("progress", 0.0).toFloat(),
                messagesReceived = result.optInt("messages_received", 0),
            )
        }
    }

    override suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
        replyToMessageId: String?,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val privateKey = sourceIdentity.privateKey ?: throw IllegalArgumentException("Source identity must have private key")

            val methodString =
                when (deliveryMethod) {
                    DeliveryMethod.OPPORTUNISTIC -> "opportunistic"
                    DeliveryMethod.DIRECT -> "direct"
                    DeliveryMethod.PROPAGATED -> "propagated"
                }

            // Partition attachments into small (bytes via Binder) and large (file paths)
            // This avoids Android Binder IPC transaction size limits (~1MB)
            val smallAttachments = mutableMapOf<String, ByteArray>()
            val largeAttachmentPaths = mutableMapOf<String, String>()

            fileAttachments?.forEach { (filename, bytes) ->
                if (bytes.size <= FileUtils.FILE_TRANSFER_THRESHOLD) {
                    smallAttachments[filename] = bytes
                } else {
                    // Write large file to temp on IO thread and pass path
                    val tempFile =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            FileUtils.writeTempAttachment(context, filename, bytes)
                        }
                    largeAttachmentPaths[filename] = tempFile.absolutePath
                    Log.d(TAG, "Large attachment '$filename' (${bytes.size} bytes) written to temp file")
                }
            }

            // Handle large images by writing to temp file to bypass Binder IPC limits
            var smallImageData: ByteArray? = null
            var imageDataPath: String? = null
            if (imageData != null) {
                if (imageData.size <= FileUtils.FILE_TRANSFER_THRESHOLD) {
                    smallImageData = imageData
                } else {
                    // Write large image to temp on IO thread and pass path
                    val tempFile =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            FileUtils.writeTempAttachment(context, "image.$imageFormat", imageData)
                        }
                    imageDataPath = tempFile.absolutePath
                    Log.d(TAG, "Large image (${imageData.size} bytes) written to temp file")
                }
            }

            val resultJson =
                service.sendLxmfMessageWithMethod(
                    destinationHash,
                    content,
                    privateKey,
                    methodString,
                    tryPropagationOnFail,
                    smallImageData,
                    imageFormat,
                    imageDataPath,
                    smallAttachments.ifEmpty { null },
                    largeAttachmentPaths.ifEmpty { null },
                    replyToMessageId,
                    iconAppearance?.iconName,
                    iconAppearance?.foregroundColor,
                    iconAppearance?.backgroundColor,
                )
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val msgHash = result.optString("message_hash").toByteArrayFromBase64() ?: byteArrayOf()
            val timestamp = result.optLong("timestamp", System.currentTimeMillis())
            val destHash = result.optString("destination_hash").toByteArrayFromBase64() ?: byteArrayOf()
            val actualMethod = result.optString("delivery_method", methodString)

            Log.d(TAG, "Message sent with method=$actualMethod")

            MessageReceipt(
                messageHash = msgHash,
                timestamp = timestamp,
                destinationHash = destHash,
            )
        }
    }

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val privateKey = sourceIdentity.privateKey ?: throw IllegalArgumentException("Source identity must have private key")

            val resultJson =
                service.sendLocationTelemetry(
                    destinationHash,
                    locationJson,
                    privateKey,
                )
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val msgHash = result.optString("message_hash").toByteArrayFromBase64() ?: byteArrayOf()
            val timestamp = result.optLong("timestamp", System.currentTimeMillis())
            val destHash = result.optString("destination_hash").toByteArrayFromBase64() ?: byteArrayOf()

            Log.d(TAG, "📍 Location telemetry sent to ${destinationHash.joinToString("") { "%02x".format(it) }.take(16)}")

            MessageReceipt(
                messageHash = msgHash,
                timestamp = timestamp,
                destinationHash = destHash,
            )
        }
    }

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> {
        return runCatching {
            val service = this.service ?: throw IllegalStateException("Service not bound")

            val privateKey = sourceIdentity.privateKey ?: throw IllegalArgumentException("Source identity must have private key")

            val resultJson =
                service.sendReaction(
                    destinationHash,
                    targetMessageId,
                    emoji,
                    privateKey,
                )
            val result = JSONObject(resultJson)

            if (!result.optBoolean("success", false)) {
                val error = result.optString("error", "Unknown error")
                throw RuntimeException(error)
            }

            val msgHash = result.optString("message_hash").toByteArrayFromBase64() ?: byteArrayOf()
            val timestamp = result.optLong("timestamp", System.currentTimeMillis())
            val destHash = result.optString("destination_hash").toByteArrayFromBase64() ?: byteArrayOf()

            Log.d(TAG, "😀 Reaction $emoji sent to ${destinationHash.joinToString("") { "%02x".format(it) }.take(16)}")

            MessageReceipt(
                messageHash = msgHash,
                timestamp = timestamp,
                destinationHash = destHash,
            )
        }
    }

    // ==================== MESSAGE SIZE LIMITS ====================

    /**
     * Update the incoming message size limit at runtime.
     * This controls the maximum size of LXMF messages that can be received.
     * Messages exceeding this limit will be rejected by the LXMF router.
     *
     * @param limitKb Size limit in KB (e.g., 1024 for 1MB, 131072 for 128MB "unlimited")
     */
    fun setIncomingMessageSizeLimit(limitKb: Int) {
        try {
            service?.setIncomingMessageSizeLimit(limitKb)
            Log.d(TAG, "Updated incoming message size limit to ${limitKb}KB")
        } catch (e: RemoteException) {
            Log.e(TAG, "Error setting incoming message size limit", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error setting incoming message size limit", e)
        }
    }

    // ==================== BLE SUPPORT ====================

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
     * Get the current RSSI of the active RNode BLE connection.
     *
     * @return RSSI in dBm, or -100 if not connected or not available
     */
    fun getRNodeRssi(): Int {
        return try {
            val service = this.service ?: return -100
            service.rNodeRssi
        } catch (e: Exception) {
            Log.e(TAG, "Error getting RNode RSSI", e)
            -100
        }
    }

    /**
     * Check if a shared Reticulum instance is currently available.
     * This queries the Python layer's port probe to detect if another app
     * (e.g., Sideband) is running a shared RNS instance on localhost:37428.
     *
     * @return true if a shared instance is available and responding, false otherwise
     */
    suspend fun isSharedInstanceAvailable(): Boolean {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                service?.isSharedInstanceAvailable ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Error querying shared instance availability", e)
                false
            }
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

    /**
     * Parse a message JSON string into a ReceivedMessage.
     * Extracted for testability.
     */
    internal fun parseMessageJson(messageJson: String): ReceivedMessage {
        val json = JSONObject(messageJson)

        val messageHash = json.optString("message_hash", "")
        val content = json.optString("content", "")
        val sourceHash = json.optString("source_hash").toByteArrayFromBase64() ?: byteArrayOf()
        val destHash = json.optString("destination_hash").toByteArrayFromBase64() ?: byteArrayOf()
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
        // Extract LXMF fields (attachments, images, etc.) if present
        val fieldsJson = json.optJSONObject("fields")?.toString()
        // Extract sender's public key if available
        val publicKeyB64 = json.optString("public_key", null)
        val publicKey = publicKeyB64?.takeIf { it.isNotEmpty() }?.toByteArrayFromBase64()

        // Extract icon appearance (LXMF Field 4 - Sideband/MeshChat interop)
        // Check both top-level "icon_appearance" (callback path) and "fields.4" (polling path)
        val iconAppearance =
            (
                json.optJSONObject("icon_appearance")
                    ?: json.optJSONObject("fields")?.optJSONObject("4")
            )?.let { iconJson ->
                try {
                    IconAppearance(
                        iconName = iconJson.optString("icon_name", ""),
                        foregroundColor = iconJson.optString("foreground_color", ""),
                        backgroundColor = iconJson.optString("background_color", ""),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse icon appearance", e)
                    null
                }
            }

        return ReceivedMessage(
            messageHash = messageHash,
            content = content,
            sourceHash = sourceHash,
            destinationHash = destHash,
            timestamp = timestamp,
            fieldsJson = fieldsJson,
            publicKey = publicKey,
            iconAppearance = iconAppearance,
        )
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
