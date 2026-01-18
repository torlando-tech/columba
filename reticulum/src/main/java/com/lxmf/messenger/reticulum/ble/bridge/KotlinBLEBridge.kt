package com.lxmf.messenger.reticulum.ble.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.ble.client.BleGattClient
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.model.BleDevice
import com.lxmf.messenger.reticulum.ble.server.BleAdvertiser
import com.lxmf.messenger.reticulum.ble.server.BleGattServer
import com.lxmf.messenger.reticulum.ble.util.BleOperationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin BLE Bridge for Python Driver.
 *
 * This is the single entry point for Python's AndroidBLEDriver to interact with
 * the native Android BLE stack. It provides a simplified API that hides the
 * complexity of Android BLE operations while preserving critical features:
 *
 * - **Adaptive scanning**: Smart intervals (5s-30s) based on discovery activity
 * - **MTU negotiation**: Up to 517 bytes to reduce fragmentation
 * - **Dual-mode operation**: Simultaneous central + peripheral mode
 * - **Protocol v2.2**: Identity-based peer tracking (handles MAC rotation)
 * - **Pure transport layer**: Passes pre-fragmented data from Python unchanged
 * - **Error recovery**: Retry logic with exponential backoff
 * - **Performance optimizations**: Operation queue, connection pooling
 *
 * **Thread Safety**: All public methods are thread-safe and can be called from
 * Python threads via Chaquopy.
 *
 * **Lifecycle**: Call start() to initialize, stop() to cleanup.
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 *
 * @property context Application context
 * @property bluetoothManager Android Bluetooth manager
 */
@SuppressLint("MissingPermission")
@Suppress("InjectDispatcher") // Bridge class doesn't use DI - dispatchers are used for BLE operations
class KotlinBLEBridge(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
) {
    companion object {
        private const val TAG = "Columba:BLE:K:Bridge"
        private const val MAX_BLE_PACKET_SIZE = 512 // Maximum BLE packet size in bytes for validation

        @Volatile
        private var instance: KotlinBLEBridge? = null

        /**
         * Get or create singleton instance.
         */
        fun getInstance(context: Context): KotlinBLEBridge {
            return instance ?: synchronized(this) {
                instance ?: KotlinBLEBridge(
                    context.applicationContext,
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager,
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val operationQueue = BleOperationQueue(scope)

    /**
     * Indicates whether Bluetooth hardware is available on this device.
     * When false, all BLE operations will fail gracefully without crashing.
     */
    val isBluetoothAvailable: Boolean = bluetoothAdapter != null

    // Bluetooth adapter state monitoring
    private val _adapterState =
        MutableStateFlow(
            if (bluetoothAdapter?.isEnabled == true) {
                BluetoothAdapter.STATE_ON
            } else {
                BluetoothAdapter.STATE_OFF
            },
        )
    val adapterState: StateFlow<Int> = _adapterState.asStateFlow()

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state =
                        intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.STATE_OFF,
                        )
                    val previousState =
                        intent.getIntExtra(
                            BluetoothAdapter.EXTRA_PREVIOUS_STATE,
                            BluetoothAdapter.STATE_OFF,
                        )

                    Log.i(TAG, "Bluetooth adapter state changed: ${stateToString(previousState)} -> ${stateToString(state)}")
                    _adapterState.value = state

                    scope.launch {
                        handleAdapterStateChange(state)
                    }
                }
            }
        }

    private var isReceiverRegistered = false

    // BLE Components - nullable when Bluetooth hardware is unavailable
    private val scanner: BleScanner? = bluetoothAdapter?.let { BleScanner(context, it, scope) }
    private val gattClient: BleGattClient? = bluetoothAdapter?.let { BleGattClient(context, it, operationQueue, scope) }
    private val gattServer: BleGattServer? = if (bluetoothAdapter != null) BleGattServer(context, bluetoothManager, scope) else null
    private val advertiser: BleAdvertiser? = bluetoothAdapter?.let { BleAdvertiser(context, it, scope) }
    // NOTE: BleBondManager not used - Android-to-Android requires Numeric Comparison (user confirmation)
    // which defeats the purpose of automatic reconnection. Relying on app-layer identity tracking instead.

    init {
        // Register Bluetooth adapter state receiver immediately upon creation
        // This ensures we can detect BT state changes even if BLE bridge hasn't started yet
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(bluetoothStateReceiver, filter)
            isReceiverRegistered = true
            Log.i(TAG, "Bluetooth adapter state receiver registered - monitoring ${BluetoothAdapter.ACTION_STATE_CHANGED}")
            Log.d(TAG, "Current BT state at registration: ${stateToString(_adapterState.value)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth state receiver in init", e)
        }
    }

    // Connection tracking
    private val connectedPeers = ConcurrentHashMap<String, PeerConnection>()
    private val peersMutex = Mutex()

    // Identity tracking (Protocol v2.2)
    private val addressToIdentity = ConcurrentHashMap<String, String>() // address -> 32-char hex identity
    private val identityToAddress = ConcurrentHashMap<String, String>() // identity -> address
    // Stale address cache: maps recently-disconnected peripheral addresses to their identity
    // This allows send() to resolve old addresses when Python's peer_address hasn't updated yet
    private val staleAddressToIdentity = ConcurrentHashMap<String, String>() // disconnected address -> identity

    // Pending connections waiting for identity (race condition fix)
    // When onConnected fires before onIdentityReceived, we defer Python notification
    private val pendingConnections = ConcurrentHashMap<String, PendingConnection>()

    // Deduplication set for identity callbacks (prevents duplicate notifications)
    // Cleared when Bluetooth is disabled to allow fresh connections
    private val processedIdentityCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Track in-progress central connections (for race condition fix in stale detection)
    // When identity arrives via central handshake, the peer isn't in connectedPeers yet.
    // This set prevents treating such connections as "stale" during deduplication.
    private val pendingCentralConnections = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Track identities that were recently deduplicated (central closed, peripheral kept)
    // Maps identity hash -> timestamp when deduplicated
    // Prevents scanner from reconnecting as central to a peer we just deduplicated
    private val recentlyDeduplicatedIdentities = ConcurrentHashMap<String, Long>()

    // How long to prevent reconnection after deduplication (60 seconds)
    private val deduplicationCooldownMs = 60_000L

    // Track addresses that are currently in deduplication (one connection closing)
    // Maps address -> DeduplicationTracker with timestamp and which connection is closing
    // During the grace period, we don't clean up the peer for unexpected disconnects
    data class DeduplicationTracker(
        val timestamp: Long,
        val closingCentral: Boolean, // true if closing central, false if closing peripheral
    )
    private val deduplicationInProgress = ConcurrentHashMap<String, DeduplicationTracker>()

    // Grace period after deduplication starts - ignore spurious disconnect events during this time
    // This handles Android BLE stack bugs where closing one GATT connection might trigger
    // spurious disconnect events for other connections to the same device
    private val deduplicationGracePeriodMs = 2_000L

    // TEST HOOK: Delay between reading deduplicationState and using it in send()
    // This widens the TOCTOU race window for testing. Set to 0 in production.
    @androidx.annotation.VisibleForTesting
    internal var testDelayAfterStateReadMs: Long = 0

    /**
     * Data class for connections awaiting identity before Python notification.
     */
    private data class PendingConnection(
        val address: String,
        val mtu: Int,
        val isCentral: Boolean,
        val isPeripheral: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    // Local transport identity
    @Volatile
    private var transportIdentityHash: ByteArray? = null

    // Production metrics for monitoring (Phase 3 Task 3.2)
    // TODO: Integrate with metrics infrastructure (Firebase/Grafana/custom) when available
    @Volatile
    private var dualConnectionRaceCount: Long = 0

    // Python callbacks (set by AndroidBLEDriver)
    // Using PyObject for Chaquopy compatibility - allows Python lambdas to be passed
    @Volatile
    private var onDeviceDiscovered: PyObject? = null

    @Volatile
    private var onConnected: PyObject? = null

    @Volatile
    private var onDisconnected: PyObject? = null

    @Volatile
    private var onDataReceived: PyObject? = null

    @Volatile
    private var onIdentityReceived: PyObject? = null

    @Volatile
    private var onMtuNegotiated: PyObject? = null

    @Volatile
    private var onAddressChanged: PyObject? = null

    @Volatile
    private var onDuplicateIdentityDetected: PyObject? = null

    fun setOnDeviceDiscovered(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onDeviceDiscovered = callback
    }

    fun setOnConnected(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onConnected = callback

        // Sync existing connections to Python when callback is registered
        // This handles the case where connections were established before Python started
        scope.launch {
            syncExistingConnectionsToPython()
        }
    }

    /**
     * Sync existing connections to Python.
     * Called when Python registers the onConnected callback to ensure Python knows
     * about connections that were established before Python started.
     */
    private suspend fun syncExistingConnectionsToPython() {
        // Collect peers to sync outside the lock to avoid holding lock during callbacks
        val peersToSync = mutableListOf<Triple<String, PeerConnection, String>>()

        peersMutex.withLock {
            connectedPeers.forEach { (address, peer) ->
                val identityHash = peer.identityHash ?: addressToIdentity[address]
                if (identityHash != null) {
                    peersToSync.add(Triple(address, peer, identityHash))
                }
            }
        }

        if (peersToSync.isNotEmpty()) {
            Log.i(TAG, "Syncing ${peersToSync.size} existing connections to Python")

            peersToSync.forEach { (address, peer, identityHash) ->
                Log.d(TAG, "Syncing connection: $address with identity ${identityHash.take(16)}...")
                notifyPythonConnected(
                    address = address,
                    mtu = peer.mtu,
                    isCentral = peer.isCentral,
                    isPeripheral = peer.isPeripheral,
                    identityHash = identityHash,
                )

                // Also sync MTU to ensure fragmenter/reassembler are created
                onMtuNegotiated?.callAttr("__call__", address, peer.mtu)
            }
        } else {
            Log.d(TAG, "No existing connections with identity to sync to Python")
        }
    }

    fun setOnDisconnected(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onDisconnected = callback
    }

    fun setOnDataReceived(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onDataReceived = callback
    }

    fun setOnIdentityReceived(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onIdentityReceived = callback
    }

    fun setOnMtuNegotiated(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onMtuNegotiated = callback
    }

    fun setOnAddressChanged(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        // Called when address changes during dual connection deduplication
        onAddressChanged = callback
    }

    fun setOnDuplicateIdentityDetected(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        // Called when identity is received to check if it's a duplicate (MAC rotation).
        // Python's _check_duplicate_identity returns True if duplicate, False otherwise.
        // If duplicate, connection should be rejected with safe message (no blacklist trigger).
        onDuplicateIdentityDetected = callback
    }

    // Native connection change listeners (for IPC callbacks, not Python)
    private val connectionChangeListeners = mutableListOf<ConnectionChangeListener>()
    private val listenerLock = Any()

    /**
     * Listener interface for BLE connection state changes.
     * Used by BleCoordinator to broadcast events via AIDL.
     */
    interface ConnectionChangeListener {
        fun onConnectionsChanged(connectionDetailsJson: String)
    }

    /**
     * Register a listener for connection state changes.
     * Thread-safe: synchronized on listenerLock.
     */
    fun addConnectionChangeListener(listener: ConnectionChangeListener) {
        synchronized(listenerLock) {
            connectionChangeListeners.add(listener)
            Log.d(TAG, "Connection change listener added (total: ${connectionChangeListeners.size})")
        }
    }

    /**
     * Unregister a connection state listener.
     * Thread-safe: synchronized on listenerLock.
     */
    fun removeConnectionChangeListener(listener: ConnectionChangeListener) {
        synchronized(listenerLock) {
            connectionChangeListeners.remove(listener)
            Log.d(TAG, "Connection change listener removed (total: ${connectionChangeListeners.size})")
        }
    }

    /**
     * Notify all listeners of connection changes.
     * Called internally when connections are established or dropped.
     */
    private fun notifyConnectionChange() {
        val json = buildConnectionDetailsJson()
        synchronized(listenerLock) {
            connectionChangeListeners.forEach { listener ->
                try {
                    listener.onConnectionsChanged(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying connection change listener", e)
                }
            }
        }
    }

    /**
     * Build JSON string of current connection details for listeners.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun buildConnectionDetailsJson(): String {
        return try {
            val deviceMap = scanner?.getDevicesSnapshot() ?: emptyMap()
            val jsonArray = org.json.JSONArray()

            // Deduplicate by identity - keep only the best peer per identity
            // (prefer central connection, then most recent activity)
            val bestPeerByIdentity = mutableMapOf<String, PeerConnection>()
            connectedPeers.values.forEach { peer ->
                val identity = peer.identityHash ?: addressToIdentity[peer.address] ?: "unknown_${peer.address}"
                val existing = bestPeerByIdentity[identity]
                val dominated =
                    when {
                        existing == null -> false
                        // Prefer peer with central connection
                        peer.isCentral && !existing.isCentral -> false
                        existing.isCentral && !peer.isCentral -> true
                        // Prefer peer with more recent activity
                        peer.lastActivity > existing.lastActivity -> false
                        else -> true
                    }
                if (!dominated) {
                    bestPeerByIdentity[identity] = peer
                }
            }

            bestPeerByIdentity.values.forEach { peer ->
                val device = deviceMap[peer.address]
                // Use stored peer.rssi first, then fall back to scanner cache
                val rssi = if (peer.rssi != -100) peer.rssi else device?.rssi ?: -100
                // Use effective connection type based on deduplication state
                val effectiveCentral =
                    when (peer.deduplicationState) {
                        DeduplicationState.CLOSING_CENTRAL -> false
                        else -> peer.isCentral
                    }
                val effectivePeripheral =
                    when (peer.deduplicationState) {
                        DeduplicationState.CLOSING_PERIPHERAL -> false
                        else -> peer.isPeripheral
                    }
                val jsonObj =
                    org.json.JSONObject().apply {
                        put("identityHash", peer.identityHash ?: "unknown")
                        put("address", peer.address)
                        put("hasCentralConnection", effectiveCentral)
                        put("hasPeripheralConnection", effectivePeripheral)
                        put("mtu", peer.mtu)
                        put("connectedAt", peer.connectedAt)
                        put("rssi", rssi)
                        put("peerName", device?.name ?: peer.identityHash?.take(8) ?: "Unknown")
                        put("firstSeen", device?.firstSeen ?: peer.connectedAt)
                        put("lastSeen", peer.lastActivity)
                    }
                jsonArray.put(jsonObj)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building connection details JSON", e)
            "[]"
        }
    }

    // State
    @Volatile
    private var isStarted = false

    // Periodic refresh job for real-time RSSI updates (only when UI is visible)
    private var connectionRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * Start periodic refresh of connection details (for real-time RSSI updates).
     * Call this when the BLE connections screen becomes visible.
     */
    fun startPeriodicConnectionRefresh() {
        if (connectionRefreshJob?.isActive == true) return
        connectionRefreshJob =
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000L)
                    if (connectedPeers.isNotEmpty()) {
                        // Update RSSI values from scanner cache
                        val deviceMap = scanner?.getDevicesSnapshot() ?: emptyMap()
                        connectedPeers.values.forEach { peer ->
                            val device = deviceMap[peer.address]
                            if (device != null && device.rssi != -100) {
                                peer.rssi = device.rssi
                            }
                        }
                        notifyConnectionChange()
                    }
                }
            }
        Log.d(TAG, "Started periodic connection refresh")
    }

    /**
     * Stop periodic refresh of connection details.
     * Call this when the BLE connections screen is no longer visible.
     */
    fun stopPeriodicConnectionRefresh() {
        connectionRefreshJob?.cancel()
        connectionRefreshJob = null
        Log.d(TAG, "Stopped periodic connection refresh")
    }

    // Stored UUIDs for restart capability
    @Volatile
    private var storedServiceUuid: String? = null

    @Volatile
    private var storedRxCharUuid: String? = null

    @Volatile
    private var storedTxCharUuid: String? = null

    @Volatile
    private var storedIdentityCharUuid: String? = null

    @Volatile
    private var storedDeviceName: String? = null

    // Callback to trigger when identity becomes available (used during restart)
    @Volatile
    private var identityReadyCallback: (() -> Unit)? = null

    /**
     * Tracks deduplication state when closing one of two connections to the same peer.
     * Used to report correct connection type to UI while disconnect is pending.
     */
    private enum class DeduplicationState {
        NONE, // Normal state - use actual isCentral/isPeripheral flags
        CLOSING_CENTRAL, // Keeping peripheral, central disconnect is pending
        CLOSING_PERIPHERAL, // Keeping central, peripheral disconnect is pending
    }

    /**
     * Action to take after mutex for deduplication (avoids calling disconnect inside mutex).
     */
    private enum class DedupeAction {
        NONE,
        CLOSE_CENTRAL,
        CLOSE_PERIPHERAL,
    }

    /**
     * Data class to track peer connection state.
     */
    private data class PeerConnection(
        val address: String,
        var mtu: Int = BleConstants.MIN_MTU,
        var isCentral: Boolean = false, // true if we connected to them
        var isPeripheral: Boolean = false, // true if they connected to us
        var identityHash: String? = null, // 32-char hex
        val connectedAt: Long = System.currentTimeMillis(),
        var rssi: Int = -100, // Last known RSSI, -100 = unknown
        var lastActivity: Long = System.currentTimeMillis(), // Last data exchange
        var deduplicationState: DeduplicationState = DeduplicationState.NONE,
    ) {
        // Mutex to protect deduplicationState access during send operations
        // Ensures state reads and send path selection are atomic with state changes
        val stateMutex: Mutex = Mutex()
    }

    /**
     * Detailed information about a connected peer (for UI display).
     */
    data class BleConnectionDetails(
        val identityHash: String,
        val peerName: String,
        val currentMac: String,
        val hasCentralConnection: Boolean,
        val hasPeripheralConnection: Boolean,
        val mtu: Int,
        val connectedAt: Long,
        val firstSeen: Long,
        val lastSeen: Long,
        val rssi: Int,
    )

    /**
     * Initialize BLE stack.
     *
     * Launches coroutines internally - safe to call from Python.
     *
     * @param serviceUuid Reticulum service UUID string
     * @param rxCharUuid RX characteristic UUID string
     * @param txCharUuid TX characteristic UUID string
     * @param identityCharUuid Identity characteristic UUID string (Protocol v2.2)
     */
    fun startAsync(
        serviceUuid: String,
        rxCharUuid: String,
        txCharUuid: String,
        identityCharUuid: String,
    ) {
        scope.launch {
            start(serviceUuid, rxCharUuid, txCharUuid, identityCharUuid)
        }
    }

    suspend fun start(
        serviceUuid: String,
        rxCharUuid: String,
        txCharUuid: String,
        identityCharUuid: String,
    ): Result<Unit> {
        return try {
            if (isStarted) {
                Log.w(TAG, "BLE bridge already started")
                return Result.success(Unit)
            }

            Log.i(TAG, "Starting BLE bridge...")

            // Store UUIDs for restart capability
            storedServiceUuid = serviceUuid
            storedRxCharUuid = rxCharUuid
            storedTxCharUuid = txCharUuid
            storedIdentityCharUuid = identityCharUuid

            // Verify Bluetooth hardware is available
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth hardware is not available")
                return Result.failure(Exception("Bluetooth hardware is not available"))
            }

            // Verify Bluetooth is enabled
            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth is disabled")
                return Result.failure(Exception("Bluetooth is disabled"))
            }

            // Setup component callbacks
            setupCallbacks()

            // Start GATT server (peripheral mode)
            gattServer?.open()?.fold(
                onSuccess = { Log.d(TAG, "GATT server opened successfully") },
                onFailure = {
                    Log.e(TAG, "Failed to open GATT server", it)
                    return Result.failure(it)
                },
            ) ?: return Result.failure(Exception("GATT server not available"))

            // Bluetooth adapter state receiver is registered in init block
            Log.d(TAG, "Bluetooth state receiver already active (registered in init)")

            isStarted = true
            Log.i(TAG, "BLE bridge started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE bridge", e)

            // Clean up partially initialized state to prevent resource leaks
            try {
                // Stop scanner if it was started
                scanner?.stopScanning()

                // Close GATT server if it was opened
                gattServer?.close()

                // Unregister receiver if it was registered
                if (isReceiverRegistered) {
                    try {
                        context.unregisterReceiver(bluetoothStateReceiver)
                        isReceiverRegistered = false
                    } catch (unregisterException: IllegalArgumentException) {
                        Log.w(TAG, "Receiver was not registered during cleanup", unregisterException)
                    }
                }

                Log.d(TAG, "Cleanup completed after initialization failure")
            } catch (cleanupException: Exception) {
                Log.e(TAG, "Error during initialization cleanup", cleanupException)
            }

            Result.failure(e)
        }
    }

    fun stopAsync() {
        scope.launch {
            stop()
        }
    }

    /**
     * Shutdown BLE stack.
     *
     * Launches coroutines internally - safe to call from Python.
     */
    suspend fun stop() {
        if (!isStarted) {
            return
        }

        Log.i(TAG, "Stopping BLE bridge...")

        // Each cleanup step is wrapped in try-catch to ensure continuation even if one fails
        // This prevents partial cleanup that could cause resource leaks

        // Stop scanning
        try {
            stopScanning()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scanner during cleanup", e)
        }

        // Stop advertising
        try {
            stopAdvertising()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertiser during cleanup", e)
        }

        // Disconnect all peers
        try {
            connectedPeers.keys.toList().forEach { address ->
                try {
                    disconnect(address)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting from $address during cleanup", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iterating connected peers during cleanup", e)
        }

        // Close GATT server
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server during cleanup", e)
        }

        // Unregister Bluetooth adapter state receiver
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Bluetooth adapter state receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered during cleanup")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver during cleanup", e)
            }
        }

        // Clear state (should never fail, but wrap anyway)
        try {
            connectedPeers.clear()
            addressToIdentity.clear()
            identityToAddress.clear()
            pendingConnections.clear()
            processedIdentityCallbacks.clear()
            pendingCentralConnections.clear()
            recentlyDeduplicatedIdentities.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing state during cleanup", e)
        }

        isStarted = false
        Log.i(TAG, "BLE bridge stopped")
    }

    /**
     * Check if BLE bridge is currently started.
     * @return true if BLE is started and operational, false otherwise
     */
    fun isStarted(): Boolean = isStarted

    /**
     * Restart BLE interface after permissions granted.
     *
     * This method is called when the user grants Bluetooth permissions after
     * BLE failed to start during app initialization. It stops the current
     * BLE stack (if running) and restarts it with the previously stored UUIDs.
     *
     * After restart, this automatically starts scanning and advertising to restore
     * full BLE functionality (discovery and visibility).
     *
     * Safe to call from any thread - launches coroutines internally.
     */
    suspend fun restart() {
        try {
            Log.i(TAG, "Restarting BLE bridge after permission grant...")

            // Check if we have stored UUIDs (BLE was started before)
            val serviceUuid = storedServiceUuid
            val rxCharUuid = storedRxCharUuid
            val txCharUuid = storedTxCharUuid
            val identityCharUuid = storedIdentityCharUuid
            @Suppress("ComplexCondition") // All 4 UUIDs must be present to restart
            if (serviceUuid == null || rxCharUuid == null || txCharUuid == null || identityCharUuid == null) {
                Log.w(TAG, "Cannot restart BLE - no stored UUIDs (BLE was never started)")
                return
            }

            // Preserve transport identity before stopping (stays in memory)
            val savedIdentity = transportIdentityHash

            // Stop BLE if it's currently running
            if (isStarted) {
                Log.d(TAG, "Stopping BLE before restart...")
                stop()
                // Give it a moment to fully stop
                delay(500)
            }

            // Restart with stored UUIDs
            Log.d(TAG, "Starting BLE with stored UUIDs...")
            start(
                serviceUuid,
                rxCharUuid,
                txCharUuid,
                identityCharUuid,
            ).fold(
                onSuccess = {
                    Log.i(TAG, "BLE restart successful - interface is now operational")

                    // Restore transport identity if it was set before
                    savedIdentity?.let { identity ->
                        Log.d(TAG, "Restoring transport identity after restart")
                        setIdentity(identity)
                    }

                    // Start scanning to discover peer devices
                    Log.d(TAG, "Starting BLE scanning after restart...")
                    startScanning().onSuccess {
                        Log.d(TAG, "Scanner started after restart")
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to start scanner after restart: ${error.message}", error)
                    }

                    // Start advertising when identity is ready
                    if (savedIdentity != null) {
                        // Identity already available - advertise immediately
                        val systemDeviceName = bluetoothAdapter?.name ?: "Reticulum"
                        Log.d(TAG, "Starting BLE advertising after restart with system name '$systemDeviceName'...")
                        startAdvertising(systemDeviceName).onSuccess {
                            Log.d(TAG, "Advertiser started after restart")
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to start advertising after restart: ${error.message}", error)
                        }
                    } else {
                        // Wait for identity to be set by Python
                        Log.d(TAG, "Waiting for identity before starting advertising...")
                        identityReadyCallback = {
                            scope.launch {
                                val systemDeviceName = bluetoothAdapter?.name ?: "Reticulum"
                                Log.d(TAG, "Identity ready - starting BLE advertising with system name '$systemDeviceName'...")
                                startAdvertising(systemDeviceName).onSuccess {
                                    Log.d(TAG, "Advertiser started after identity set")
                                }.onFailure { error ->
                                    Log.e(TAG, "Failed to start advertising: ${error.message}", error)
                                }
                            }
                        }

                        // Set a timeout in case Python never calls setIdentity()
                        scope.launch {
                            delay(30000) // 30 second timeout
                            if (identityReadyCallback != null) {
                                Log.w(TAG, "Timeout waiting for identity - advertising not started")
                                identityReadyCallback = null
                            }
                        }
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "BLE restart failed: ${error.message}", error)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during BLE restart", e)
        }
    }

    /**
     * Set local transport identity.
     *
     * @param identityBytes 16-byte transport identity hash
     */
    fun setIdentity(identityBytes: ByteArray) {
        require(identityBytes.size == 16) { "Identity must be 16 bytes" }
        transportIdentityHash = identityBytes

        // Update all BLE components (if available)
        gattClient?.setTransportIdentity(identityBytes)
        gattServer?.setTransportIdentity(identityBytes)
        advertiser?.setTransportIdentity(identityBytes)

        Log.i(TAG, "Transport identity set: ${identityBytes.joinToString("") { "%02x".format(it) }}")

        // Trigger any pending identity callback (e.g., from restart waiting for identity)
        identityReadyCallback?.let { callback ->
            Log.d(TAG, "Triggering identity ready callback")
            callback()
            identityReadyCallback = null
        }
    }

    suspend fun startScanning(): Result<Unit> =
        withContext(Dispatchers.Default) {
            val scannerInstance = scanner
            if (scannerInstance == null) {
                Log.e(TAG, "Cannot start scanning - Bluetooth not available")
                return@withContext Result.failure(Exception("Bluetooth not available"))
            }
            scannerInstance.startScanning().onSuccess {
                Log.d(TAG, "Scanning started")
            }.onFailure {
                Log.e(TAG, "Failed to start scanning", it)
            }
        }

    fun startScanningAsync() {
        scope.launch {
            startScanning()
        }
    }

    fun stopScanningAsync() {
        scope.launch {
            stopScanning()
        }
    }

    /**
     * Stop BLE scanning.
     */
    suspend fun stopScanning() =
        withContext(Dispatchers.Default) {
            scanner?.stopScanning()
            Log.d(TAG, "Scanning stopped")
        }

    fun startAdvertisingAsync(deviceName: String = "Reticulum") {
        scope.launch {
            startAdvertising(deviceName)
        }
    }

    /**
     * Start BLE advertising.
     *
     * @param deviceName Device name to advertise (identity will be appended)
     */
    suspend fun startAdvertising(deviceName: String = "Reticulum"): Result<Unit> =
        withContext(Dispatchers.Default) {
            // Store device name for restart capability
            storedDeviceName = deviceName

            val advertiserInstance = advertiser
            if (advertiserInstance == null) {
                Log.e(TAG, "Cannot start advertising - Bluetooth not available")
                return@withContext Result.failure(Exception("Bluetooth not available"))
            }
            advertiserInstance.startAdvertising(deviceName).onSuccess {
                Log.d(TAG, "Advertising started")
            }.onFailure {
                Log.e(TAG, "Failed to start advertising", it)
            }
        }

    fun stopAdvertisingAsync() {
        scope.launch {
            stopAdvertising()
        }
    }

    /**
     * Stop BLE advertising.
     *
     * Launches coroutines internally - safe to call from Python.
     */
    fun stopAdvertising() {
        scope.launch {
            advertiser?.stopAdvertising()
            Log.d(TAG, "Advertising stopped")
        }
    }

    /**
     * Ensure advertising is active, restarting if necessary.
     *
     * Use this to check and recover advertising that may have been
     * silently stopped by Android (app backgrounding, screen off, etc.).
     *
     * @return true if advertising was already active, false if restart was triggered
     */
    fun ensureAdvertising(): Boolean {
        val advertiserInstance = advertiser ?: return true // No Bluetooth, nothing to do
        if (!advertiserInstance.isAdvertising.value && transportIdentityHash != null) {
            scope.launch {
                val name = storedDeviceName ?: "Reticulum"
                Log.d(TAG, "ensureAdvertising: restarting as '$name'")
                advertiserInstance.startAdvertising(name)
            }
            return false // Was not advertising, now restarting
        }
        return true // Already advertising (or no identity set)
    }

    /**
     * Check if a discovered device should be skipped due to recent deduplication.
     *
     * Protocol v2.2 includes 3 bytes (6 hex chars) of identity in the advertised name.
     * If the name matches an identity that was recently deduplicated (we're already
     * connected as peripheral), skip the central connection attempt.
     *
     * @param deviceName Advertised device name (e.g., "RNS-272b4c" or "Reticulum-272b4c")
     * @return true if the device should be skipped, false otherwise
     */
    @Suppress("ReturnCount")
    private fun shouldSkipDiscoveredDevice(deviceName: String?): Boolean {
        if (deviceName == null) return false

        // Clean up old entries first
        val now = System.currentTimeMillis()
        recentlyDeduplicatedIdentities.entries.removeIf { now - it.value > deduplicationCooldownMs }

        if (recentlyDeduplicatedIdentities.isEmpty()) return false

        // Extract identity prefix from device name
        // Protocol v2.2 format: "RNS-XXXXXX" or "Reticulum-XXXXXX" where X is hex
        val identityPrefix =
            when {
                deviceName.startsWith("RNS-") -> deviceName.removePrefix("RNS-").lowercase()
                deviceName.startsWith("Reticulum-") -> deviceName.removePrefix("Reticulum-").lowercase()
                else -> return false
            }

        // Check if any recently deduplicated identity starts with this prefix
        // (device name has 6 hex chars = 3 bytes, full identity is 32 hex chars)
        val matchingIdentity = recentlyDeduplicatedIdentities.keys.find { it.startsWith(identityPrefix) }
        if (matchingIdentity != null) {
            Log.i(TAG, "Skipping discovered device with name '$deviceName' - matches recently deduplicated identity $matchingIdentity")
        }
        return matchingIdentity != null
    }

    /**
     * Determine if we should initiate connection to a peer based on MAC address sorting.
     *
     * Algorithm: Lower MAC address initiates connection (acts as central),
     * higher MAC address waits (acts as peripheral only).
     *
     * This prevents dual connections (both central and peripheral to same peer)
     * by ensuring both devices independently agree on connection direction.
     *
     * NOTE: This method is exposed for Python (BLEInterface) to call BEFORE connect().
     * The MAC sorting decision has been moved to Python layer per RFC architecture.
     * Kotlin no longer automatically filters in connect() - Python must check first.
     *
     * @param peerAddress BLE MAC address of peer
     * @return true if we should connect (our MAC < peer MAC), false otherwise
     */
    fun shouldConnect(peerAddress: String): Boolean {
        try {
            // Get local MAC address
            val localAddress = bluetoothAdapter?.address
            if (localAddress == null) {
                Log.w(TAG, "Local MAC address unavailable (no Bluetooth?), falling back to always connect")
                return true
            }

            // Strip colons and convert to lowercase for comparison
            val localMacStripped = localAddress.replace(":", "").lowercase()
            val peerMacStripped = peerAddress.replace(":", "").lowercase()

            // Convert to Long for numerical comparison
            val localMacInt =
                try {
                    localMacStripped.toLong(16)
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Invalid local MAC format: $localAddress, falling back to always connect")
                    return true
                }

            val peerMacInt =
                try {
                    peerMacStripped.toLong(16)
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Invalid peer MAC format: $peerAddress, falling back to always connect")
                    return true
                }

            // Lower MAC initiates connection
            val shouldConnect = localMacInt < peerMacInt

            Log.d(TAG, "MAC comparison: local=$localAddress ($localMacInt), peer=$peerAddress ($peerMacInt), shouldConnect=$shouldConnect")

            return shouldConnect
        } catch (e: Exception) {
            Log.e(TAG, "Error in MAC comparison, falling back to always connect", e)
            return true
        }
    }

    fun connectAsync(address: String) {
        scope.launch {
            connect(address)
        }
    }

    /**
     * Connect to a peer (central mode).
     *
     * NOTE: MAC sorting has been moved to Python (BLEInterface).
     * Python should call shouldConnect() first to decide whether to connect.
     * This method now connects unconditionally when called.
     *
     * Launches coroutines internally - safe to call from Python.
     *
     * @param address BLE MAC address
     */
    suspend fun connect(address: String) {
        try {
            Log.i(TAG, "Connecting to $address...")

            // NOTE: MAC sorting moved to Python. Python should call shouldConnect() first.
            // We still check for existing connections to avoid duplicate GATT operations.
            connectedPeers[address]?.let { peer ->
                if (peer.isCentral) {
                    Log.w(TAG, "Already connected to $address as central")
                    return
                }
                // NOTE: Dual connection handling moved to Python.
                // We no longer skip connecting if peripheral exists - Python decides.
            }

            // Check connection limit
            val centralCount = connectedPeers.count { it.value.isCentral }
            if (centralCount >= BleConstants.MAX_CONNECTIONS) {
                Log.w(TAG, "Maximum central connections reached ($centralCount/${BleConstants.MAX_CONNECTIONS})")
                return
            }

            // Check if GATT client is available
            val client = gattClient
            if (client == null) {
                Log.w(TAG, "Cannot connect to $address - Bluetooth not available")
                return
            }

            // Track as pending central connection (for race condition fix in stale detection)
            // This prevents the connection from being treated as "stale" when identity arrives
            // before handlePeerConnected() has added the peer to connectedPeers
            pendingCentralConnections.add(address)

            // Connect via GATT client
            client.connect(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $address", e)
        }
    }

    fun disconnectAsync(address: String) {
        scope.launch {
            disconnect(address)
        }
    }

    /**
     * Disconnect from a peer.
     *
     * Launches coroutines internally - safe to call from Python.
     *
     * @param address BLE MAC address
     */
    suspend fun disconnect(address: String) {
        try {
            Log.i(TAG, "Disconnecting from $address...")

            val peer = connectedPeers[address]
            if (peer == null) {
                Log.w(TAG, "No connection found for $address")
                return
            }

            // Disconnect based on connection type
            if (peer.isCentral) {
                gattClient?.disconnect(address)
            }
            if (peer.isPeripheral) {
                gattServer?.disconnectCentral(address)
            }

            // Cleanup will happen in onDisconnected callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from $address", e)
        }
    }

    /**
     * Disconnect only our central connection TO a peer (async wrapper).
     * Used by Python for deduplication when keeping peripheral.
     */
    fun disconnectCentralAsync(address: String) {
        scope.launch {
            disconnectCentral(address)
        }
    }

    /**
     * Disconnect only our central connection TO a peer.
     *
     * Used for deduplication when Python wants to keep the peripheral
     * connection (them connected to us) but close our central connection.
     *
     * @param address BLE MAC address
     */
    suspend fun disconnectCentral(address: String) {
        try {
            Log.i(TAG, "Disconnecting central connection to $address...")
            gattClient?.disconnect(address)

            // Update peer state - mark as no longer central
            peersMutex.withLock {
                connectedPeers[address]?.isCentral = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect central from $address", e)
        }
    }

    /**
     * Disconnect only the peripheral connection FROM a peer (async wrapper).
     * Used by Python for deduplication when keeping central.
     */
    fun disconnectPeripheralAsync(address: String) {
        scope.launch {
            disconnectPeripheral(address)
        }
    }

    /**
     * Disconnect only the peripheral connection FROM a peer.
     *
     * Used for deduplication when Python wants to keep our central
     * connection (us connected to them) but close the peripheral connection.
     *
     * @param address BLE MAC address
     */
    suspend fun disconnectPeripheral(address: String) {
        try {
            Log.i(TAG, "Disconnecting peripheral connection from $address...")
            gattServer?.disconnectCentral(address)

            // Update peer state - mark as no longer peripheral
            peersMutex.withLock {
                connectedPeers[address]?.isPeripheral = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect peripheral from $address", e)
        }
    }

    fun sendAsync(
        address: String,
        data: ByteArray,
    ) {
        scope.launch {
            send(address, data)
        }
    }

    /**
     * Send data to a peer.
     *
     * Data arrives pre-fragmented from Python BLEInterface - passed through unchanged.
     * Each call sends a single fragment to the remote peer's GATT RX characteristic.
     *
     * Launches coroutines internally - safe to call from Python.
     *
     * @param address BLE MAC address
     * @param data Pre-fragmented data bytes (single fragment)
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // Address resolution and deduplication state handling require these checks
    suspend fun send(
        address: String,
        data: ByteArray,
    ) {
        var targetAddress = address
        try {
            var peer = connectedPeers[address]

            // If peer not found, try resolving via identity (handles deduplication address changes)
            if (peer == null) {
                // First try current addressToIdentity mapping
                var identityHash = addressToIdentity[address]

                // If not found, check stale address cache (handles MAC rotation during disconnect)
                if (identityHash == null) {
                    identityHash = staleAddressToIdentity[address]
                    if (identityHash != null) {
                        Log.d(TAG, "Found identity $identityHash for stale address $address in cache")
                    }
                }

                if (identityHash != null) {
                    val currentAddress = identityToAddress[identityHash]
                    if (currentAddress != null && currentAddress != address) {
                        peer = connectedPeers[currentAddress]
                        if (peer != null) {
                            Log.i(TAG, "Address $address resolved to $currentAddress via identity $identityHash")
                            targetAddress = currentAddress
                        }
                    }
                }
            }

            if (peer == null) {
                Log.w(TAG, "Cannot send to $address - not connected (identity lookup also failed)")
                return
            }

            // Update last activity timestamp for this peer
            peer.lastActivity = System.currentTimeMillis()

            // Data is now a pre-formatted fragment from the Python layer
            Log.d(TAG, "Sending ${data.size} byte fragment to $targetAddress")

            // CRITICAL SECTION: Acquire per-peer mutex to ensure state read and send are atomic
            // This prevents TOCTOU race condition where deduplicationState changes between
            // computing useCentral/usePeripheral and actually using those values.
            peer.stateMutex.withLock {
                // TEST HOOK: Widen TOCTOU race window for testing (inside mutex)
                if (testDelayAfterStateReadMs > 0) {
                    kotlinx.coroutines.delay(testDelayAfterStateReadMs)
                }

                // Read state and make decision while holding mutex
                val useCentral = peer.isCentral && peer.deduplicationState != DeduplicationState.CLOSING_CENTRAL
                val usePeripheral = peer.isPeripheral && peer.deduplicationState != DeduplicationState.CLOSING_PERIPHERAL

                if (useCentral) {
                    gattClient?.sendData(targetAddress, data)?.fold(
                        onSuccess = { Log.v(TAG, "Fragment sent via central to $targetAddress") },
                        onFailure = { Log.e(TAG, "Failed to send fragment via central to $targetAddress", it) },
                    ) ?: Log.w(TAG, "Cannot send via central - Bluetooth not available")
                }
                // Otherwise use peripheral connection (notify their TX)
                else if (usePeripheral) {
                    gattServer?.notifyCentrals(data, targetAddress)?.fold(
                        onSuccess = { Log.v(TAG, "Fragment sent via peripheral to $targetAddress") },
                        onFailure = { Log.e(TAG, "Failed to send fragment via peripheral to $targetAddress", it) },
                    ) ?: Log.w(TAG, "Cannot send via peripheral - Bluetooth not available")
                }
                // Both paths blocked during deduplication
                else if (peer.deduplicationState != DeduplicationState.NONE) {
                    Log.w(TAG, "Cannot send to $targetAddress - deduplication in progress (state=${peer.deduplicationState})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data to $targetAddress (requested: $address)", e)
        }
    }

    /**
     * Get list of connected peer addresses.
     */
    fun getConnectedPeers(): List<String> {
        return connectedPeers.keys.toList()
    }

    /**
     * Get peer identity by address.
     *
     * @param address BLE MAC address
     * @return 32-character hex identity string, or null if unknown
     */
    fun getPeerIdentity(address: String): String? {
        return addressToIdentity[address]
    }

    /**
     * Get peer address by identity.
     *
     * @param identityHash 32-character hex identity string
     * @return BLE MAC address, or null if not connected
     */
    fun getPeerAddress(identityHash: String): String? {
        return identityToAddress[identityHash]
    }

    /**
     * Request identity resync for a peer.
     *
     * Called by Python when it receives data from a peer but has no identity mapping.
     * This can happen if Python's disconnect callback fired but Kotlin maintained
     * the GATT connection.
     *
     * @param address BLE MAC address of the peer
     * @return true if identity was found and callback fired, false otherwise
     */
    fun requestIdentityResync(address: String): Boolean {
        Log.d(TAG, "Identity resync requested for $address")

        // Try to get identity from our tracking
        val identityHash = addressToIdentity[address]
        if (identityHash != null) {
            Log.i(TAG, "Identity resync: found identity ${identityHash.take(16)}... for $address")

            // Re-fire the identity received callback to Python
            onIdentityReceived?.callAttr("__call__", address, identityHash)

            // Also re-fire connected callback to restore full state
            val peer = connectedPeers[address]
            if (peer != null) {
                val roleString =
                    when {
                        peer.isCentral && peer.isPeripheral -> "both"
                        peer.isCentral -> "central"
                        peer.isPeripheral -> "peripheral"
                        else -> "unknown"
                    }
                onConnected?.callAttr("__call__", address, peer.mtu, roleString, identityHash)
                Log.d(TAG, "Identity resync: re-fired onConnected for $address")
            }
            return true
        }

        // No identity found in our tracking
        val peer = connectedPeers[address]
        if (peer != null) {
            Log.w(
                TAG,
                "Identity resync: peer $address is connected but has no tracked identity " +
                    "(central=${peer.isCentral}, peripheral=${peer.isPeripheral})",
            )
        } else {
            Log.w(TAG, "Identity resync: peer $address is not connected")
        }

        return false
    }

    /**
     * Get detailed information about all connected peers.
     * Combines connection state with device discovery info.
     *
     * @return List of connection details for all connected peers
     */
    suspend fun getConnectionDetails(): List<BleConnectionDetails> {
        val details = mutableListOf<BleConnectionDetails>()
        val discoveredDevices = scanner?.getDevicesSortedByPriority() ?: emptyList()
        val deviceMap = discoveredDevices.associateBy { it.address }

        peersMutex.withLock {
            connectedPeers.values.forEach { peer ->
                val device = deviceMap[peer.address]
                // Fallback to addressToIdentity if peer.identityHash not set (race condition)
                val identity = peer.identityHash ?: addressToIdentity[peer.address]
                // Use effective connection type based on deduplication state
                val effectiveCentral =
                    when (peer.deduplicationState) {
                        DeduplicationState.CLOSING_CENTRAL -> false
                        else -> peer.isCentral
                    }
                val effectivePeripheral =
                    when (peer.deduplicationState) {
                        DeduplicationState.CLOSING_PERIPHERAL -> false
                        else -> peer.isPeripheral
                    }

                details.add(
                    BleConnectionDetails(
                        identityHash = identity ?: "unknown",
                        peerName = device?.name ?: identity?.take(8) ?: "Unknown",
                        currentMac = peer.address,
                        hasCentralConnection = effectiveCentral,
                        hasPeripheralConnection = effectivePeripheral,
                        mtu = peer.mtu,
                        connectedAt = peer.connectedAt,
                        firstSeen = device?.firstSeen ?: peer.connectedAt,
                        lastSeen = peer.lastActivity,
                        rssi = device?.rssi ?: -100,
                    ),
                )
            }
        }

        return details
    }

    /**
     * Synchronous version of getConnectionDetails() for use in AIDL interfaces.
     * Safe to call from any thread - connectedPeers is a ConcurrentHashMap.
     *
     * Note: This version doesn't include scanner discovery data (name, rssi) since
     * accessing that requires a mutex lock. Only connection state is returned.
     * Added to avoid blocking the caller thread in AIDL per Phase 1.2 policy.
     *
     * @return List of connection details for all connected peers (without scanner data)
     */
    @Suppress("CyclomaticComplexMethod")
    fun getConnectionDetailsSync(): List<BleConnectionDetails> {
        val details = mutableListOf<BleConnectionDetails>()

        // Get snapshot of scanner devices (thread-safe, non-blocking)
        val deviceMap = scanner?.getDevicesSnapshot() ?: emptyMap()

        // Deduplicate by identity - keep only the best peer per identity
        // (prefer central connection, then most recent activity)
        val bestPeerByIdentity = mutableMapOf<String, PeerConnection>()
        connectedPeers.values.forEach { peer ->
            val identity = peer.identityHash ?: addressToIdentity[peer.address] ?: "unknown_${peer.address}"
            val existing = bestPeerByIdentity[identity]
            val dominated =
                when {
                    existing == null -> false
                    // Prefer peer with central connection
                    peer.isCentral && !existing.isCentral -> false
                    existing.isCentral && !peer.isCentral -> true
                    // Prefer peer with more recent activity
                    peer.lastActivity > existing.lastActivity -> false
                    else -> true
                }
            if (!dominated) {
                bestPeerByIdentity[identity] = peer
            }
        }

        // Build details from deduplicated peers
        bestPeerByIdentity.values.forEach { peer ->
            val device = deviceMap[peer.address]
            // Fallback to addressToIdentity if peer.identityHash not set (race condition)
            val identity = peer.identityHash ?: addressToIdentity[peer.address]

            val finalRssi = if (peer.rssi != -100) peer.rssi else device?.rssi ?: -100
            // Use effective connection type based on deduplication state
            val effectiveCentral =
                when (peer.deduplicationState) {
                    DeduplicationState.CLOSING_CENTRAL -> false
                    else -> peer.isCentral
                }
            val effectivePeripheral =
                when (peer.deduplicationState) {
                    DeduplicationState.CLOSING_PERIPHERAL -> false
                    else -> peer.isPeripheral
                }
            details.add(
                BleConnectionDetails(
                    identityHash = identity ?: "unknown",
                    peerName = device?.name ?: identity?.take(8) ?: "Unknown",
                    currentMac = peer.address,
                    hasCentralConnection = effectiveCentral,
                    hasPeripheralConnection = effectivePeripheral,
                    mtu = peer.mtu,
                    connectedAt = peer.connectedAt,
                    firstSeen = device?.firstSeen ?: peer.connectedAt,
                    lastSeen = peer.lastActivity,
                    rssi = finalRssi,
                ),
            )
        }

        return details
    }

    /**
     * Get the count of dual-connection race conditions detected.
     * This metric tracks how often both central and peripheral connections are
     * established simultaneously before MAC sorting can prevent it.
     *
     * Phase 3 Task 3.2: Production observability for race condition frequency.
     * Useful for monitoring connection quality and validating MAC sorting effectiveness.
     *
     * @return Total count of race conditions since bridge started
     */
    fun getDualConnectionRaceCount(): Long {
        return dualConnectionRaceCount
    }

    /**
     * Setup callbacks for all BLE components.
     */
    @Suppress("LongMethod")
    private fun setupCallbacks() {
        // Scanner callbacks (if available)
        scanner?.onDeviceDiscovered = { device: BleDevice ->
            // Skip devices that match recently deduplicated identities
            // (prevents reconnecting as central to a peer we're already connected to as peripheral)
            if (!shouldSkipDiscoveredDevice(device.name)) {
                Log.d(TAG, "Device discovered: ${device.address} (${device.name}) RSSI=${device.rssi}")
                // Convert List to Array for Python compatibility (Chaquopy converts arrays to Python lists)
                val serviceUuidsArray = device.serviceUuids?.toTypedArray()
                onDeviceDiscovered?.callAttr("__call__", device.address, device.name, device.rssi, serviceUuidsArray)
            }
        }

        // GATT Client callbacks (central mode, if available)
        gattClient?.onConnected = { address: String, mtu: Int ->
            scope.launch {
                handlePeerConnected(address, mtu, isCentral = true)
            }
        }

        gattClient?.onDisconnected = { address: String, reason: String? ->
            scope.launch {
                handlePeerDisconnected(address, isCentral = true)
            }
        }

        gattClient?.onConnectionFailed = { address: String, error: String ->
            // Clean up pending central tracking when connection fails
            pendingCentralConnections.remove(address)
            Log.d(TAG, "Central connection failed to $address: $error")

            // Central handshake failed - try to complete via peripheral if connected
            // This handles dual-connection scenarios where both devices connect as central
            // and the central handshake times out (e.g., notification enable timeout)
            scope.launch {
                val identityHash = addressToIdentity[address]
                if (identityHash != null) {
                    val completed = gattServer?.completeConnectionWithIdentity(address, identityHash) ?: false
                    if (completed) {
                        Log.i(TAG, "Central failed, completed peripheral connection for $address")
                    }
                }
            }
        }

        gattClient?.onDataReceived = { address: String, data: ByteArray ->
            scope.launch {
                handleDataReceived(address, data)
            }
        }

        gattClient?.onMtuChanged = { address: String, mtu: Int ->
            scope.launch {
                handleMtuChanged(address, mtu)
            }
        }

        gattClient?.onIdentityReceived = { address: String, identityHash: String ->
            scope.launch {
                handleIdentityReceived(address, identityHash, isCentralConnection = true)
            }
        }

        // GATT Server callbacks (peripheral mode, if available)
        gattServer?.onCentralConnected = { address: String, mtu: Int ->
            scope.launch {
                handlePeerConnected(address, mtu, isCentral = false)
            }
        }

        gattServer?.onCentralDisconnected = { address: String ->
            scope.launch {
                handlePeerDisconnected(address, isCentral = false)
            }
        }

        gattServer?.onDataReceived = { address: String, data: ByteArray ->
            scope.launch {
                handleDataReceived(address, data)
            }
        }

        gattServer?.onMtuChanged = { address: String, mtu: Int ->
            scope.launch {
                handleMtuChanged(address, mtu)
            }
        }

        gattServer?.onIdentityReceived = { address: String, identityHash: String ->
            scope.launch {
                handleIdentityReceived(address, identityHash, isCentralConnection = false)
            }
        }

        // Advertiser callbacks (for logging and potential Python notification, if available)
        advertiser?.onAdvertisingStarted = { deviceName: String ->
            Log.i(TAG, "Advertising started: $deviceName")
        }
        advertiser?.onAdvertisingStopped = {
            Log.i(TAG, "Advertising stopped")
        }
        advertiser?.onAdvertisingFailed = { errorCode: Int, message: String ->
            Log.e(TAG, "Advertising failed: $message (code: $errorCode)")
        }
    }

    /**
     * Handle peer connection established.
     *
     * NOTE: Deduplication logic has been moved to Python (BLEInterface).
     * Kotlin now reports ALL connections to Python, and Python decides which to close.
     * This follows the RFC architecture where protocol logic belongs in BLEInterface.
     */
    @Suppress("LongMethod")
    private suspend fun handlePeerConnected(
        address: String,
        mtu: Int,
        isCentral: Boolean,
    ) {
        // Remove from pending central connections now that connection is established
        if (isCentral) {
            pendingCentralConnections.remove(address)
        }

        // Get RSSI from scanner cache at connection time (before MAC rotation)
        val scannedDevice = scanner?.getDevicesSnapshot()?.get(address)
        val rssiAtConnection = scannedDevice?.rssi ?: -100

        // Track deduplication action to perform after mutex (can't call disconnect inside mutex)
        var dedupeAction = DedupeAction.NONE

        peersMutex.withLock {
            val peer =
                connectedPeers.getOrPut(address) {
                    PeerConnection(address, mtu, rssi = rssiAtConnection)
                }

            if (isCentral) {
                peer.isCentral = true
            } else {
                peer.isPeripheral = true
            }
            peer.mtu = maxOf(peer.mtu, mtu) // Use best MTU
            // Update RSSI if we got a valid one (might be first connection as central)
            if (rssiAtConnection != -100) {
                peer.rssi = rssiAtConnection
            }

            // Check if we already received identity for this peer (race condition fix)
            val existingIdentity = addressToIdentity[address]
            if (existingIdentity != null && peer.identityHash == null) {
                peer.identityHash = existingIdentity
            }

            // Handle dual connection - decide which to keep based on identity comparison
            if (peer.isCentral && peer.isPeripheral) {
                dualConnectionRaceCount++
                Log.d(TAG, "Dual connection count: $dualConnectionRaceCount")

                val peerIdentity = addressToIdentity[address]
                val localIdentityBytes = transportIdentityHash
                if (peerIdentity != null && localIdentityBytes != null) {
                    val localIdentityHex = localIdentityBytes.joinToString("") { "%02x".format(it) }
                    // Acquire per-peer mutex before changing deduplicationState
                    // This ensures send() sees consistent state
                    peer.stateMutex.withLock {
                        // Lower identity hash keeps central role
                        if (localIdentityHex < peerIdentity) {
                            Log.i(TAG, "Deduplication: keeping central (local=$localIdentityHex < peer=$peerIdentity)")
                            peer.deduplicationState = DeduplicationState.CLOSING_PERIPHERAL
                            dedupeAction = DedupeAction.CLOSE_PERIPHERAL
                        } else {
                            Log.i(TAG, "Deduplication: keeping peripheral (local=$localIdentityHex > peer=$peerIdentity)")
                            peer.deduplicationState = DeduplicationState.CLOSING_CENTRAL
                            dedupeAction = DedupeAction.CLOSE_CENTRAL
                        }
                    }
                    // Add deduplication cooldown to prevent immediate reconnection as central
                    // This prevents reconnection storms when one side keeps trying to reconnect
                    recentlyDeduplicatedIdentities[peerIdentity] = System.currentTimeMillis()
                    Log.d(TAG, "Added $peerIdentity to deduplication cooldown (${deduplicationCooldownMs / 1000}s)")
                } else {
                    Log.w(TAG, "Dual connection but identity not yet available - deferring deduplication")
                }
            }

            Log.i(TAG, "Peer connected: $address (central=$isCentral, peripheral=${peer.isPeripheral}, dedupe=${peer.deduplicationState}, MTU=$mtu)")

            // ALWAYS notify Python of the connection - Python handles deduplication
            val identityHash = addressToIdentity[address]

            Log.w(
                TAG,
                "[CALLBACK] handlePeerConnected: address=$address, " +
                    "identityHash=${identityHash?.take(16)}, " +
                    "isCentral=$isCentral, isPeripheral=${peer.isPeripheral}",
            )

            // ALWAYS notify Python immediately - Python handles timeout for missing identity
            // This follows RFC architecture: protocol decisions belong in BLEInterface.py
            Log.w(
                TAG,
                "[CALLBACK] handlePeerConnected: Notifying Python for $address " +
                    "(identity=${identityHash?.take(16) ?: "pending"})",
            )
            notifyPythonConnected(address, peer.mtu, isCentral, peer.isPeripheral, identityHash)

            // Track pending connection if identity not yet received
            // When identity arrives via handleIdentityReceived, we'll notify Python again
            if (identityHash == null) {
                pendingConnections[address] =
                    PendingConnection(
                        address = address,
                        mtu = peer.mtu,
                        isCentral = isCentral,
                        isPeripheral = peer.isPeripheral,
                    )
                Log.d(TAG, "Connection $address pending identity - Python will handle timeout")
            }
        }

        // Execute deduplication disconnect outside mutex to avoid deadlock
        when (dedupeAction) {
            DedupeAction.CLOSE_CENTRAL -> {
                // Track that deduplication is in progress for this address
                // This prevents premature cleanup if Android fires spurious disconnect events
                deduplicationInProgress[address] = DeduplicationTracker(
                    timestamp = System.currentTimeMillis(),
                    closingCentral = true,
                )
                Log.i(TAG, "Deduplication: disconnecting central connection to $address")
                gattClient?.disconnect(address)
            }
            DedupeAction.CLOSE_PERIPHERAL -> {
                // Track that deduplication is in progress for this address
                deduplicationInProgress[address] = DeduplicationTracker(
                    timestamp = System.currentTimeMillis(),
                    closingCentral = false,
                )
                Log.i(TAG, "Deduplication: disconnecting peripheral connection from $address")
                gattServer?.disconnectCentral(address)
            }
            DedupeAction.NONE -> { /* No deduplication needed */ }
        }

        // Notify native listeners of connection state change
        notifyConnectionChange()
    }

    /**
     * Handle peer disconnection.
     *
     * Complexity justified: Handles deduplication protection to work around Android BLE
     * stack bugs where closing one GATT connection may fire spurious disconnect events
     * for other connections to the same device.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun handlePeerDisconnected(
        address: String,
        isCentral: Boolean,
    ) {
        peersMutex.withLock {
            val peer = connectedPeers[address]
            if (peer == null) {
                Log.w(TAG, "Disconnect notification for unknown peer: $address")
                return
            }

            // Check if deduplication is in progress for this address
            // If so, we might need to ignore spurious disconnects for the kept connection
            val dedupeTracker = deduplicationInProgress[address]
            if (dedupeTracker != null) {
                val elapsed = System.currentTimeMillis() - dedupeTracker.timestamp
                if (elapsed < deduplicationGracePeriodMs) {
                    // Within grace period - check if this is the expected disconnect
                    val isExpectedDisconnect = (isCentral && dedupeTracker.closingCentral) ||
                        (!isCentral && !dedupeTracker.closingCentral)

                    if (!isExpectedDisconnect) {
                        // This disconnect is for the connection we're KEEPING, not closing
                        // This is a spurious disconnect from Android BLE stack - ignore it
                        Log.w(
                            TAG,
                            "DEDUP PROTECTION: Ignoring spurious disconnect for $address " +
                                "(received ${if (isCentral) "central" else "peripheral"} disconnect, " +
                                "but we're closing ${if (dedupeTracker.closingCentral) "central" else "peripheral"}). " +
                                "This is an Android BLE stack bug.",
                        )
                        return
                    }
                    // Expected disconnect - process normally and clear tracker
                    Log.d(TAG, "Deduplication disconnect processed for $address (${if (isCentral) "central" else "peripheral"})")
                    deduplicationInProgress.remove(address)
                }
            }

            if (isCentral) {
                peer.isCentral = false
                // Also clean up pending central tracking
                pendingCentralConnections.remove(address)
                // Clear deduplication state if we were closing this connection
                // Acquire per-peer mutex to ensure send() sees consistent state
                peer.stateMutex.withLock {
                    if (peer.deduplicationState == DeduplicationState.CLOSING_CENTRAL) {
                        peer.deduplicationState = DeduplicationState.NONE
                        Log.d(TAG, "Deduplication complete: central connection closed for $address")
                    }
                }
            } else {
                peer.isPeripheral = false
                // Clear deduplication state if we were closing this connection
                // Acquire per-peer mutex to ensure send() sees consistent state
                peer.stateMutex.withLock {
                    if (peer.deduplicationState == DeduplicationState.CLOSING_PERIPHERAL) {
                        peer.deduplicationState = DeduplicationState.NONE
                        Log.d(TAG, "Deduplication complete: peripheral connection closed for $address")
                    }
                }
            }

            // If no connections remain, remove peer and clean up mappings
            if (!peer.isCentral && !peer.isPeripheral) {
                // Safety check: if we still have deduplication tracker, something went wrong
                // This shouldn't happen since we check at the start of the function, but log anyway
                val remainingTracker = deduplicationInProgress.remove(address)
                if (remainingTracker != null) {
                    val elapsed = System.currentTimeMillis() - remainingTracker.timestamp
                    Log.w(
                        TAG,
                        "DEDUP WARNING: Both connections closed for $address despite deduplication protection " +
                            "(${elapsed}ms since deduplication started). This shouldn't happen.",
                    )
                }

                connectedPeers.remove(address)

                // Remove any pending connection (race condition cleanup)
                pendingConnections.remove(address)

                // Clean up address mappings for this identity
                val identityHash = addressToIdentity.remove(address)
                if (identityHash != null) {
                    // Check if this identity has any other active addresses
                    // (could have accumulated during MAC rotations)
                    val otherAddresses = addressToIdentity.entries.filter { it.value == identityHash }.map { it.key }
                    if (otherAddresses.isEmpty()) {
                        // Identity fully disconnected - clean up reverse mapping
                        identityToAddress.remove(identityHash)
                        // Allow reconnection as central again
                        recentlyDeduplicatedIdentities.remove(identityHash)
                        // Clean up any stale entries for this identity
                        staleAddressToIdentity.entries.removeIf { it.value == identityHash }
                        Log.d(TAG, "Cleaned up all mappings for identity $identityHash")
                    } else {
                        // Identity still connected at another address - cache the stale mapping
                        // This allows send() to resolve the old address to the current one
                        staleAddressToIdentity[address] = identityHash

                        // If identityToAddress points to the disconnecting address, update it to another active address
                        if (identityToAddress[identityHash] == address) {
                            // Prefer an address with a connected peer, especially one with central connection
                            val bestAddress = otherAddresses.maxByOrNull { otherAddr ->
                                val peer = connectedPeers[otherAddr]
                                when {
                                    peer == null -> 0
                                    peer.isCentral -> 2
                                    peer.isPeripheral -> 1
                                    else -> 0
                                }
                            }
                            if (bestAddress != null) {
                                identityToAddress[identityHash] = bestAddress
                                Log.d(TAG, "Updated identityToAddress[$identityHash] from disconnected $address to $bestAddress")
                            }
                        }
                        Log.d(TAG, "Cached stale address $address -> $identityHash (identity still has other addresses: $otherAddresses)")
                    }
                }

                // Clean up identity callback deduplication for this address
                // This allows the identity callback to be processed on reconnection
                // (fixes bug where static-MAC devices like Linux couldn't reconnect)
                processedIdentityCallbacks.removeIf { it.startsWith("$address:") }

                Log.i(TAG, "Peer disconnected: $address")
                onDisconnected?.callAttr("__call__", address)

                // Remove from scanner cache so device can be rediscovered and reconnected
                // This enables automatic reconnection on next scan cycle
                scanner?.removeDevice(address)
            } else {
                Log.d(TAG, "Peer $address partially disconnected (central=${peer.isCentral}, peripheral=${peer.isPeripheral})")
            }
        }

        // Notify native listeners of connection state change
        notifyConnectionChange()
    }

    /**
     * Handle MTU change for a peer.
     */
    private suspend fun handleMtuChanged(
        address: String,
        mtu: Int,
    ) {
        peersMutex.withLock {
            val peer = connectedPeers[address]
            if (peer != null) {
                peer.mtu = maxOf(peer.mtu, mtu)
                Log.d(TAG, "MTU updated for $address: ${peer.mtu}")
            }
        }

        // Notify Python of MTU negotiation
        onMtuNegotiated?.callAttr("__call__", address, mtu)
    }

    /**
     * Notify Python of a new peer connection.
     *
     * This helper is used both for immediate notification (when identity is already available)
     * and deferred notification (after identity is received for pending connections).
     *
     * @param address BLE MAC address
     * @param mtu Negotiated MTU size
     * @param isCentral True if we connected to them (central role)
     * @param isPeripheral True if they connected to us (peripheral role)
     * @param identityHash 32-char hex identity string, or null if identity pending
     */
    private fun notifyPythonConnected(
        address: String,
        mtu: Int,
        isCentral: Boolean,
        isPeripheral: Boolean,
        identityHash: String?,
    ) {
        // Determine role: "central" = we connected to them, "peripheral" = they connected to us
        val roleString = if (isCentral && !isPeripheral) "central" else "peripheral"
        val identityLog = identityHash?.take(16) ?: "pending"
        Log.w(
            TAG,
            "[CALLBACK] notifyPythonConnected: CALLING Python " +
                "onConnected(address=$address, mtu=$mtu, role=$roleString, " +
                "identity=$identityLog...)",
        )
        Log.d(TAG, "Notifying Python of connection: $address (role=$roleString, identity=$identityHash)")
        onConnected?.callAttr("__call__", address, mtu, roleString, identityHash)
        Log.w(TAG, "[CALLBACK] notifyPythonConnected: Python callback completed for $address")
    }

    /**
     * Handle incoming data fragment from a peer.
     *
     * Reassembles fragments into complete packets and forwards to Python.
     */
    private fun handleDataReceived(
        address: String,
        fragment: ByteArray,
    ) {
        try {
            // VALIDATION: Check fragment size before processing
            if (fragment.size > MAX_BLE_PACKET_SIZE) {
                Log.w(TAG, "BLE packet too large: ${fragment.size} bytes (max $MAX_BLE_PACKET_SIZE), discarding")
                return
            }

            // Update last activity timestamp for this peer
            // Use identity-based lookup to handle MAC address rotation
            var peer = connectedPeers[address]
            var resolvedAddress = address
            if (peer == null) {
                // Try resolving via identity (handles address changes after deduplication)
                val identityHash = addressToIdentity[address]
                Log.d(TAG, "[LAST_ACTIVITY] address=$address not in connectedPeers, identityHash=$identityHash")
                if (identityHash != null) {
                    val currentAddress = identityToAddress[identityHash]
                    Log.d(TAG, "[LAST_ACTIVITY] identity $identityHash -> currentAddress=$currentAddress")
                    if (currentAddress != null) {
                        peer = connectedPeers[currentAddress]
                        resolvedAddress = currentAddress
                        Log.d(TAG, "[LAST_ACTIVITY] resolved peer at $currentAddress: ${peer != null}")
                    }
                }
            }
            if (peer != null) {
                peer.lastActivity = System.currentTimeMillis()
                Log.d(TAG, "[LAST_ACTIVITY] Updated lastActivity for $resolvedAddress")
            } else {
                Log.w(TAG, "[LAST_ACTIVITY] Could not find peer for $address")
            }

            // Pass the raw fragment to the Python layer for reassembly
            Log.d(TAG, "Received ${fragment.size} byte fragment from $address")

            // VALIDATION: Try-catch around Python callback to prevent crashes
            try {
                onDataReceived?.callAttr("__call__", address, fragment)
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Python onDataReceived callback", e)
                // Don't crash - log and continue
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing fragment from $address", e)
        }
    }

    /**
     * Handle identity received from peer (Protocol v2.2).
     *
     * Maps the peer's identity to their current MAC address.
     *
     * NOTE: Deduplication and MAC rotation handling has been moved to Python (BLEInterface).
     * Kotlin now just tracks identity for address resolution and notifies Python.
     * Python decides what to do with duplicate identities or dual connections.
     */
    private suspend fun handleIdentityReceived(
        address: String,
        identityHash: String,
        isCentralConnection: Boolean,
    ) {
        // Deduplication check - ignore duplicate callbacks for same address+identity
        // This prevents race conditions when the same identity notification fires multiple times
        val dedupeKey = "$address:$identityHash"
        if (!processedIdentityCallbacks.add(dedupeKey)) {
            Log.d(TAG, "Ignoring duplicate identity callback for $address ($identityHash)")
            return
        }

        // Check for duplicate identity (Android MAC rotation) via Python callback
        // Python's _check_duplicate_identity returns True if this identity is already connected
        // at a different address, meaning this is a MAC rotation attempt that should be rejected.
        val duplicateCallback = onDuplicateIdentityDetected
        if (duplicateCallback != null) {
            try {
                // Convert hex string to ByteArray for Python
                val identityBytes = identityHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val isDuplicate = duplicateCallback.callAttr("__call__", address, identityBytes)

                // Python returns True (boolean) if duplicate, which Chaquopy converts to Boolean
                if (isDuplicate?.toBoolean() == true) {
                    Log.w(
                        TAG,
                        "Duplicate identity rejected for $address - identity ${identityHash.take(16)}... " +
                            "already connected at different MAC (MAC rotation)",
                    )
                    // Disconnect this connection since it's a duplicate
                    // Use safe log message format that doesn't trigger blacklist
                    // ("Duplicate identity rejected for" doesn't match "Connection failed to" pattern)
                    scope.launch {
                        if (isCentralConnection) {
                            gattClient?.disconnect(address)
                        } else {
                            gattServer?.disconnectCentral(address)
                        }
                    }
                    // Remove from processed callbacks to allow retry after original disconnects
                    processedIdentityCallbacks.remove(dedupeKey)
                    return
                }
            } catch (e: Exception) {
                // Log but don't fail - if callback has issues, allow connection to proceed
                Log.w(TAG, "Error in duplicate identity callback for $address: ${e.message}")
            }
        }

        // Track pending connection that was waiting for identity (race condition fix)
        var completedPending: PendingConnection? = null

        // Track address change for MAC rotation notification to Python
        // Pair of (oldAddress, newAddress) - tells Python to redirect from old to new
        var addressChangeNotification: Pair<String, String>? = null

        peersMutex.withLock {
            // Update peer with identity if it exists
            // NOTE: We do NOT remove old peers here - let disconnect callbacks handle that.
            // Removing peers here causes issues when MAC rotation creates a new connection
            // before the old one has disconnected (the old connection may still be valid).
            val peerAtNewAddress = connectedPeers[address]
            if (peerAtNewAddress != null) {
                peerAtNewAddress.identityHash = identityHash

                // Decide whether to update identity→address mapping
                // Prefer addresses with central connection (more reliable for sending)
                val existingAddress = identityToAddress[identityHash]
                val existingPeer = existingAddress?.let { connectedPeers[it] }

                // Verify actual GATT connection state - peer entry may be stale if disconnect
                // notification was missed. This prevents keeping mappings to dead connections.
                val existingActuallyHasCentral = existingAddress != null &&
                    existingPeer?.isCentral == true &&
                    gattClient?.isConnected(existingAddress) == true

                if (existingPeer?.isCentral == true && !existingActuallyHasCentral) {
                    Log.w(
                        TAG,
                        "Stale central connection detected for $existingAddress " +
                            "(peer says central=true but GATT not connected)",
                    )
                }

                val shouldUpdate =
                    when {
                        existingAddress == null -> true // No existing mapping
                        existingPeer == null -> true // Old peer no longer exists
                        // Prefer peer with central connection
                        peerAtNewAddress.isCentral && !existingActuallyHasCentral -> true
                        // If new has both, prefer it
                        peerAtNewAddress.isCentral && peerAtNewAddress.isPeripheral -> true
                        // If existing has central and new doesn't, keep existing
                        existingActuallyHasCentral && !peerAtNewAddress.isCentral -> false
                        // Default: use the newer address
                        else -> true
                    }

                if (shouldUpdate) {
                    identityToAddress[identityHash] = address
                    Log.d(TAG, "Updated identity→address mapping: $identityHash → $address (central=${peerAtNewAddress.isCentral})")

                    // Track address change for MAC rotation notification
                    // Always notify Python when address changes so it can update address_to_identity
                    // Python needs to know ALL addresses that map to this identity for receiving data
                    if (existingAddress != null && existingAddress != address) {
                        addressChangeNotification = Pair(existingAddress, address)
                        Log.i(TAG, "Address changed for identity $identityHash: $existingAddress → $address (central=${peerAtNewAddress.isCentral})")
                    }
                } else {
                    Log.d(TAG, "Keeping existing identity→address mapping: $identityHash → $existingAddress (existing has live central)")
                    // DON'T send address change notification here!
                    // Python's on_device_connected already added address_to_identity for the new address.
                    // Sending a redirect (new → existing) would DELETE the new address mapping,
                    // preventing Python from receiving data from the new address.
                    // Python's peer_address already points to the central address (correct for sending).
                    if (existingAddress != null && existingAddress != address) {
                        Log.d(TAG, "NOT notifying Python: $address already handled by on_device_connected, peer_address stays $existingAddress")
                    }
                }
            } else {
                // Peer doesn't exist yet - update mapping only if no existing valid mapping
                val existingAddress = identityToAddress[identityHash]
                val existingPeer = existingAddress?.let { connectedPeers[it] }
                if (existingPeer == null) {
                    identityToAddress[identityHash] = address
                    Log.d(TAG, "Set identity→address mapping: $identityHash → $address (no existing peer)")
                } else {
                    Log.d(TAG, "Identity $identityHash already mapped to $existingAddress with valid peer, not overwriting with $address")
                }
            }

            // Always update address→identity mapping (for data routing from this address)
            addressToIdentity[address] = identityHash
            // Remove from stale cache if present (address is now active again)
            staleAddressToIdentity.remove(address)

            // Check for pending connection that was waiting for identity
            completedPending = pendingConnections.remove(address)
            if (completedPending != null) {
                Log.d(TAG, "Completing pending connection for $address - identity now available")
            }

            Log.i(TAG, "Identity received from $address: $identityHash (isCentral=$isCentralConnection)")
        }

        // Complete pending connection notification (race condition fix)
        // This fires the deferred onConnected callback with the now-available identity
        completedPending?.let { pending ->
            val currentPeer = connectedPeers[address]
            Log.w(
                TAG,
                "[CALLBACK] handleIdentityReceived: Completing DEFERRED connection " +
                    "for $address with identity ${identityHash.take(16)}...",
            )
            Log.i(
                TAG,
                "Notifying Python of deferred connection: $address with identity $identityHash " +
                    "(pending: central=${pending.isCentral}/peripheral=${pending.isPeripheral}, " +
                    "current: central=${currentPeer?.isCentral}/peripheral=${currentPeer?.isPeripheral})",
            )
            notifyPythonConnected(
                address = pending.address,
                mtu = currentPeer?.mtu ?: pending.mtu,
                isCentral = currentPeer?.isCentral ?: pending.isCentral,
                isPeripheral = currentPeer?.isPeripheral ?: pending.isPeripheral,
                identityHash = identityHash,
            )
        } ?: run {
            Log.w(
                TAG,
                "[CALLBACK] handleIdentityReceived: NO pending connection for $address " +
                    "(identity arrived but no deferred connection)",
            )
        }

        // Notify Python of identity (Python handles deduplication, MAC rotation, etc.)
        Log.w(TAG, "[CALLBACK] handleIdentityReceived: Calling onIdentityReceived Python callback for $address")
        onIdentityReceived?.callAttr("__call__", address, identityHash)

        // Notify Python of address change for MAC rotation handling
        addressChangeNotification?.let { (oldAddress, newAddress) ->
            Log.w(
                TAG,
                "[CALLBACK] handleIdentityReceived: Calling onAddressChanged Python callback " +
                    "($oldAddress → $newAddress, identity=$identityHash)",
            )
            onAddressChanged?.callAttr("__call__", oldAddress, newAddress, identityHash)
        }
    }

    /**
     * Handle Bluetooth adapter state changes.
     * Proactively manages BLE operations when adapter is disabled/enabled.
     */
    private suspend fun handleAdapterStateChange(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                Log.w(TAG, "Bluetooth disabled - clearing all connections")
                // Immediately clear all connections since they're invalid
                connectedPeers.keys.toList().forEach { address ->
                    handlePeerDisconnected(address, isCentral = true)
                    handlePeerDisconnected(address, isCentral = false)
                }
                connectedPeers.clear()
                addressToIdentity.clear()
                identityToAddress.clear()
                pendingConnections.clear()
                processedIdentityCallbacks.clear()
                pendingCentralConnections.clear()
                recentlyDeduplicatedIdentities.clear()

                // Stop BLE operations (but keep isStarted=true for auto-restart)
                stopScanning()
                stopAdvertising()
            }
            BluetoothAdapter.STATE_ON -> {
                Log.i(TAG, "Bluetooth enabled - auto-restarting BLE interface")
                if (isStarted) {
                    // Auto-restart scanning and advertising
                    storedServiceUuid?.let { serviceUuid ->
                        Log.d(TAG, "Restarting scanner...")
                        startScanning().onFailure { error ->
                            Log.e(TAG, "Failed to restart scanner: ${error.message}")
                        }

                        // Restart advertising if we have identity
                        if (transportIdentityHash != null) {
                            val deviceName = storedDeviceName ?: "Reticulum"
                            Log.d(TAG, "Restarting advertising with name '$deviceName'...")
                            startAdvertising(deviceName).onFailure { error ->
                                Log.e(TAG, "Failed to restart advertising: ${error.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert Bluetooth adapter state to human-readable string.
     */
    private fun stateToString(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }

    /**
     * Cleanup on instance destruction.
     */
    fun shutdown() {
        scope.launch {
            stop()
        }
        scope.cancel()
    }
}
