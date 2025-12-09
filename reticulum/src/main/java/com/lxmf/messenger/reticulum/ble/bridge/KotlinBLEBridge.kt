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
class KotlinBLEBridge(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
) {
    companion object {
        private const val TAG = "Columba:Kotlin:BLEBridge"
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
    private val bluetoothAdapter = bluetoothManager.adapter
    private val operationQueue = BleOperationQueue(scope)

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

    // BLE Components
    private val scanner = BleScanner(context, bluetoothAdapter, scope)
    private val gattClient = BleGattClient(context, bluetoothAdapter, operationQueue, scope)
    private val gattServer = BleGattServer(context, bluetoothManager, scope)
    private val advertiser = BleAdvertiser(context, bluetoothAdapter, scope)
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

    fun setOnDeviceDiscovered(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onDeviceDiscovered = callback
    }

    fun setOnConnected(callback: PyObject) {
        // VALIDATION: Accept PyObject from Python - validation happens at call time
        onConnected = callback
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

    // State
    @Volatile
    private var isStarted = false

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
     * Data class to track peer connection state.
     */
    private data class PeerConnection(
        val address: String,
        var mtu: Int = BleConstants.MIN_MTU,
        var isCentral: Boolean = false, // true if we connected to them
        var isPeripheral: Boolean = false, // true if they connected to us
        var identityHash: String? = null, // 32-char hex
        val connectedAt: Long = System.currentTimeMillis(),
    )

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

            // Verify Bluetooth is enabled
            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth is disabled")
                return Result.failure(Exception("Bluetooth is disabled"))
            }

            // Setup component callbacks
            setupCallbacks()

            // Start GATT server (peripheral mode)
            gattServer.open().fold(
                onSuccess = { Log.d(TAG, "GATT server opened successfully") },
                onFailure = {
                    Log.e(TAG, "Failed to open GATT server", it)
                    return Result.failure(it)
                },
            )

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
                scanner.stopScanning()

                // Close GATT server if it was opened
                gattServer.close()

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
            gattServer.close()
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
            if (storedServiceUuid == null || storedRxCharUuid == null ||
                storedTxCharUuid == null || storedIdentityCharUuid == null
            ) {
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
                storedServiceUuid!!,
                storedRxCharUuid!!,
                storedTxCharUuid!!,
                storedIdentityCharUuid!!,
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

        // Update all BLE components
        gattClient.setTransportIdentity(identityBytes)
        gattServer.setTransportIdentity(identityBytes)
        advertiser.setTransportIdentity(identityBytes)

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
            scanner.startScanning().onSuccess {
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
            scanner.stopScanning()
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

            advertiser.startAdvertising(deviceName).onSuccess {
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
            advertiser.stopAdvertising()
            Log.d(TAG, "Advertising stopped")
        }
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
     * @param peerAddress BLE MAC address of peer
     * @return true if we should connect (our MAC < peer MAC), false otherwise
     */
    fun shouldConnect(peerAddress: String): Boolean {
        try {
            // Get local MAC address
            val localAddress = bluetoothAdapter.address
            if (localAddress == null) {
                Log.w(TAG, "Local MAC address unavailable, falling back to always connect")
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
     * Uses MAC-based sorting to prevent dual connections:
     * - Lower MAC initiates connection (central)
     * - Higher MAC waits for connection (peripheral)
     *
     * Launches coroutines internally - safe to call from Python.
     *
     * @param address BLE MAC address
     */
    suspend fun connect(address: String) {
        try {
            Log.i(TAG, "Connecting to $address...")

            // MAC-based connection deduplication
            if (!shouldConnect(address)) {
                Log.i(TAG, "Skipping connection to $address (MAC sorting: our MAC is higher, wait for them to connect)")
                return
            }

            // Check if already connected as peripheral (avoid dual connections)
            connectedPeers[address]?.let { peer ->
                if (peer.isPeripheral) {
                    Log.i(TAG, "Already connected to $address as peripheral, skipping central connection")
                    return
                }
                if (peer.isCentral) {
                    Log.w(TAG, "Already connected to $address as central")
                    return
                }
            }

            // Check connection limit
            val centralCount = connectedPeers.count { it.value.isCentral }
            if (centralCount >= BleConstants.MAX_CONNECTIONS) {
                Log.w(TAG, "Maximum central connections reached ($centralCount/${BleConstants.MAX_CONNECTIONS})")
                return
            }

            // Track as pending central connection (for race condition fix in stale detection)
            // This prevents the connection from being treated as "stale" when identity arrives
            // before handlePeerConnected() has added the peer to connectedPeers
            pendingCentralConnections.add(address)

            // Connect via GATT client
            gattClient.connect(address)
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
                gattClient.disconnect(address)
            }
            if (peer.isPeripheral) {
                gattServer.disconnectCentral(address)
            }

            // Cleanup will happen in onDisconnected callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from $address", e)
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
    suspend fun send(
        address: String,
        data: ByteArray,
    ) {
        var targetAddress = address
        try {
            var peer = connectedPeers[address]

            // If peer not found, try resolving via identity (handles deduplication address changes)
            if (peer == null) {
                val identityHash = addressToIdentity[address]
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

            // Data is now a pre-formatted fragment from the Python layer
            Log.d(TAG, "Sending ${data.size} byte fragment to $targetAddress")

            // Prefer central connection (we write to their RX)
            if (peer.isCentral) {
                gattClient.sendData(targetAddress, data).fold(
                    onSuccess = { Log.v(TAG, "Fragment sent via central to $targetAddress") },
                    onFailure = { Log.e(TAG, "Failed to send fragment via central to $targetAddress", it) },
                )
            }
            // Otherwise use peripheral connection (notify their TX)
            else if (peer.isPeripheral) {
                gattServer.notifyCentrals(data, targetAddress).fold(
                    onSuccess = { Log.v(TAG, "Fragment sent via peripheral to $targetAddress") },
                    onFailure = { Log.e(TAG, "Failed to send fragment via peripheral to $targetAddress", it) },
                )
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
     * Get detailed information about all connected peers.
     * Combines connection state with device discovery info.
     *
     * @return List of connection details for all connected peers
     */
    suspend fun getConnectionDetails(): List<BleConnectionDetails> {
        val details = mutableListOf<BleConnectionDetails>()
        val discoveredDevices = scanner.getDevicesSortedByPriority()
        val deviceMap = discoveredDevices.associateBy { it.address }

        peersMutex.withLock {
            connectedPeers.values.forEach { peer ->
                val device = deviceMap[peer.address]

                details.add(
                    BleConnectionDetails(
                        identityHash = peer.identityHash ?: "unknown",
                        peerName = device?.name ?: "No Name",
                        currentMac = peer.address,
                        hasCentralConnection = peer.isCentral,
                        hasPeripheralConnection = peer.isPeripheral,
                        mtu = peer.mtu,
                        connectedAt = peer.connectedAt,
                        firstSeen = device?.firstSeen ?: peer.connectedAt,
                        lastSeen = device?.lastSeen ?: System.currentTimeMillis(),
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
    fun getConnectionDetailsSync(): List<BleConnectionDetails> {
        val details = mutableListOf<BleConnectionDetails>()

        // Get snapshot of scanner devices (thread-safe, non-blocking)
        val deviceMap = scanner.getDevicesSnapshot()

        // Safe iteration - connectedPeers is ConcurrentHashMap
        connectedPeers.values.forEach { peer ->
            val device = deviceMap[peer.address]

            details.add(
                BleConnectionDetails(
                    identityHash = peer.identityHash ?: "unknown",
                    peerName = device?.name ?: "No Name", // Real device name from scanner
                    currentMac = peer.address,
                    hasCentralConnection = peer.isCentral,
                    hasPeripheralConnection = peer.isPeripheral,
                    mtu = peer.mtu,
                    connectedAt = peer.connectedAt,
                    firstSeen = device?.firstSeen ?: peer.connectedAt,
                    lastSeen = device?.lastSeen ?: System.currentTimeMillis(),
                    rssi = device?.rssi ?: -100, // Real RSSI from scanner, -100 fallback
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
    private fun setupCallbacks() {
        // Scanner callbacks
        scanner.onDeviceDiscovered = { device: BleDevice ->
            // Skip devices that match recently deduplicated identities
            // (prevents reconnecting as central to a peer we're already connected to as peripheral)
            if (!shouldSkipDiscoveredDevice(device.name)) {
                Log.d(TAG, "Device discovered: ${device.address} (${device.name}) RSSI=${device.rssi}")
                // Convert List to Array for Python compatibility (Chaquopy converts arrays to Python lists)
                val serviceUuidsArray = device.serviceUuids?.toTypedArray()
                onDeviceDiscovered?.callAttr("__call__", device.address, device.name, device.rssi, serviceUuidsArray)
            }
        }

        // GATT Client callbacks (central mode)
        gattClient.onConnected = { address: String, mtu: Int ->
            scope.launch {
                handlePeerConnected(address, mtu, isCentral = true)
            }
        }

        gattClient.onDisconnected = { address: String, reason: String? ->
            scope.launch {
                handlePeerDisconnected(address, isCentral = true)
            }
        }

        gattClient.onConnectionFailed = { address: String, error: String ->
            // Clean up pending central tracking when connection fails
            pendingCentralConnections.remove(address)
            Log.d(TAG, "Central connection failed to $address: $error")
        }

        gattClient.onDataReceived = { address: String, data: ByteArray ->
            scope.launch {
                handleDataReceived(address, data)
            }
        }

        gattClient.onMtuChanged = { address: String, mtu: Int ->
            scope.launch {
                handleMtuChanged(address, mtu)
            }
        }

        gattClient.onIdentityReceived = { address: String, identityHash: String ->
            scope.launch {
                handleIdentityReceived(address, identityHash, isCentralConnection = true)
            }
        }

        // GATT Server callbacks (peripheral mode)
        gattServer.onCentralConnected = { address: String, mtu: Int ->
            scope.launch {
                handlePeerConnected(address, mtu, isCentral = false)
            }
        }

        gattServer.onCentralDisconnected = { address: String ->
            scope.launch {
                handlePeerDisconnected(address, isCentral = false)
            }
        }

        gattServer.onDataReceived = { address: String, data: ByteArray ->
            scope.launch {
                handleDataReceived(address, data)
            }
        }

        gattServer.onMtuChanged = { address: String, mtu: Int ->
            scope.launch {
                handleMtuChanged(address, mtu)
            }
        }

        gattServer.onIdentityReceived = { address: String, identityHash: String ->
            scope.launch {
                handleIdentityReceived(address, identityHash, isCentralConnection = false)
            }
        }
    }

    /**
     * Handle peer connection established.
     */
    @Suppress("LongMethod") // Length due to verbose logging format, not complexity
    private suspend fun handlePeerConnected(
        address: String,
        mtu: Int,
        isCentral: Boolean,
    ) {
        // Remove from pending central connections now that connection is established
        if (isCentral) {
            pendingCentralConnections.remove(address)
        }

        // Track if we need to deduplicate (will do outside mutex)
        var needsDedupeInConnect = false
        var weKeepCentralInConnect = false

        peersMutex.withLock {
            val peer =
                connectedPeers.getOrPut(address) {
                    PeerConnection(address, mtu)
                }

            if (isCentral) {
                peer.isCentral = true
            } else {
                peer.isPeripheral = true
            }
            peer.mtu = maxOf(peer.mtu, mtu) // Use best MTU

            // Check if we already received identity for this peer (race condition fix)
            val existingIdentity = addressToIdentity[address]
            if (existingIdentity != null && peer.identityHash == null) {
                peer.identityHash = existingIdentity
            }

            // Detect dual connection: both central and peripheral established

            if (peer.isCentral && peer.isPeripheral) {
                Log.w(TAG, "Dual connection detected for $address")

                // Track for production monitoring
                dualConnectionRaceCount++
                Log.d(TAG, "Dual connection count: $dualConnectionRaceCount")

                // Use identity-based sorting to determine which connection to keep
                // This is deterministic and works with MAC rotation
                val peerIdentity = peer.identityHash
                val localIdentity = transportIdentityHash

                if (peerIdentity != null && localIdentity != null) {
                    // Convert local identity to hex string for comparison
                    val localIdentityHex = localIdentity.joinToString("") { "%02x".format(it) }

                    // Lower identity hash keeps central role
                    weKeepCentralInConnect = localIdentityHex < peerIdentity
                    needsDedupeInConnect = true

                    if (weKeepCentralInConnect) {
                        Log.i(TAG, "Identity sorting: keeping central, will close peripheral for $address (local=$localIdentityHex < peer=$peerIdentity)")
                        peer.isPeripheral = false
                    } else {
                        Log.i(TAG, "Identity sorting: keeping peripheral, will close central to $address (local=$localIdentityHex >= peer=$peerIdentity)")
                        peer.isCentral = false
                    }
                } else {
                    // Identity not yet received - keep both for now, will deduplicate when identity arrives
                    Log.d(TAG, "Dual connection for $address - waiting for identity to deduplicate")
                }
            }

            Log.i(TAG, "Peer connected: $address (central=$isCentral, MTU=$mtu)")

            // Notify Python (only once per peer, not per connection type)
            if (peer.isCentral && !peer.isPeripheral || !peer.isCentral && peer.isPeripheral) {
                val identityHash = addressToIdentity[address]

                // DEBUG: Log decision point for Python notification
                Log.w(
                    TAG,
                    "[CALLBACK] handlePeerConnected: address=$address, " +
                        "identityHash=${identityHash?.take(16)}, " +
                        "will_notify_immediately=${identityHash != null}",
                )

                if (identityHash != null) {
                    // Identity already available - notify Python immediately
                    Log.w(
                        TAG,
                        "[CALLBACK] handlePeerConnected: IMMEDIATE notification to Python " +
                            "for $address with identity ${identityHash.take(16)}...",
                    )
                    notifyPythonConnected(address, peer.mtu, peer.isCentral, peer.isPeripheral, identityHash)
                } else {
                    // Identity not yet received - defer Python notification
                    // The onIdentityReceived callback will complete the notification
                    pendingConnections[address] =
                        PendingConnection(
                            address = address,
                            mtu = peer.mtu,
                            isCentral = peer.isCentral,
                            isPeripheral = peer.isPeripheral,
                        )
                    Log.w(
                        TAG,
                        "[CALLBACK] handlePeerConnected: DEFERRED notification " +
                            "for $address (waiting for identity)",
                    )
                    Log.d(TAG, "Connection $address pending - waiting for identity before notifying Python")
                }
            } else {
                Log.w(
                    TAG,
                    "[CALLBACK] handlePeerConnected: NOT notifying Python for $address " +
                        "(dual connection: central=${peer.isCentral}, peripheral=${peer.isPeripheral})",
                )
            }
        }

        // Perform disconnect outside mutex to avoid deadlock
        if (needsDedupeInConnect) {
            if (weKeepCentralInConnect) {
                Log.i(TAG, "Disconnecting peripheral (server) for $address")
                gattServer.disconnectCentral(address)
                Log.d(TAG, "Disconnect peripheral request completed for $address")
            } else {
                Log.i(TAG, "Disconnecting central (client) to $address")
                gattClient.disconnect(address)
                Log.d(TAG, "Disconnect central request completed for $address")
            }
        }
    }

    /**
     * Handle peer disconnection.
     */
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

            if (isCentral) {
                peer.isCentral = false
                // Also clean up pending central tracking
                pendingCentralConnections.remove(address)
            } else {
                peer.isPeripheral = false
            }

            // If no connections remain, remove peer and clean up mappings
            if (!peer.isCentral && !peer.isPeripheral) {
                connectedPeers.remove(address)

                // Remove any pending connection (race condition cleanup)
                pendingConnections.remove(address)

                // Clean up address mappings for this identity
                val identityHash = addressToIdentity.remove(address)
                if (identityHash != null) {
                    // Check if this identity has any other active addresses
                    // (could have accumulated during MAC rotations)
                    val hasOtherAddress = addressToIdentity.values.any { it == identityHash }
                    if (!hasOtherAddress) {
                        // Identity fully disconnected - clean up reverse mapping
                        identityToAddress.remove(identityHash)
                        // Allow reconnection as central again
                        recentlyDeduplicatedIdentities.remove(identityHash)
                        Log.d(TAG, "Cleaned up all mappings for identity $identityHash")
                    } else {
                        Log.d(TAG, "Identity $identityHash still has other address mappings")
                    }
                }

                Log.i(TAG, "Peer disconnected: $address")
                onDisconnected?.callAttr("__call__", address)
            } else {
                Log.d(TAG, "Peer $address partially disconnected (central=${peer.isCentral}, peripheral=${peer.isPeripheral})")
            }
        }
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
     * @param identityHash 32-char hex identity string
     */
    private fun notifyPythonConnected(
        address: String,
        mtu: Int,
        isCentral: Boolean,
        isPeripheral: Boolean,
        identityHash: String,
    ) {
        // Determine role: "central" = we connected to them, "peripheral" = they connected to us
        val roleString = if (isCentral && !isPeripheral) "central" else "peripheral"
        Log.w(
            TAG,
            "[CALLBACK] notifyPythonConnected: CALLING Python " +
                "onConnected(address=$address, mtu=$mtu, role=$roleString, " +
                "identity=${identityHash.take(16)}...)",
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
    private suspend fun handleDataReceived(
        address: String,
        fragment: ByteArray,
    ) {
        try {
            // VALIDATION: Check fragment size before processing
            if (fragment.size > MAX_BLE_PACKET_SIZE) {
                Log.w(TAG, "BLE packet too large: ${fragment.size} bytes (max $MAX_BLE_PACKET_SIZE), discarding")
                return
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
     * Detects and rejects duplicate connections when the same identity
     * connects from a rotated MAC address (Android MAC rotation).
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

        // Check for duplicate identity BEFORE acquiring mutex for disconnect operations
        val existingAddress = identityToAddress[identityHash]
        if (existingAddress != null && existingAddress != address) {
            // Same identity, different MAC - check if existing connection is still active
            val existingPeer = connectedPeers[existingAddress]
            // Also check for in-progress central connections (race condition fix)
            // When identity arrives before handlePeerConnected, peer isn't in connectedPeers yet
            val isPendingCentral = pendingCentralConnections.contains(existingAddress)
            val existingPeerHasConnection = existingPeer?.isCentral == true || existingPeer?.isPeripheral == true
            val isActiveConnection = existingPeerHasConnection || isPendingCentral
            if (isActiveConnection) {
                // Only treat as duplicate if SAME connection direction
                // Central-to-peripheral and peripheral-to-central are valid dual connections
                val existingIsCentral = existingPeer?.isCentral == true || isPendingCentral
                val existingIsPeripheral = existingPeer?.isPeripheral == true
                val existingIsSameDirection =
                    if (isCentralConnection) {
                        existingIsCentral // We're central now, was existing also central?
                    } else {
                        existingIsPeripheral // We're peripheral now, was existing also peripheral?
                    }

                if (existingIsSameDirection) {
                    // MAC rotation detected: same identity, same direction, different MAC
                    // The NEW connection is valid - peer can only be at one MAC address at a time
                    // Trust the new connection and clean up the old one
                    Log.i(TAG, "MAC rotation: identity $identityHash migrating from $existingAddress to $address")

                    // Disconnect old address (may already be dead, that's OK)
                    if (existingIsCentral) {
                        gattClient.disconnect(existingAddress)
                    } else {
                        gattServer.disconnectCentral(existingAddress)
                    }

                    // Clean up old tracking entries
                    // KEEP addressToIdentity[existingAddress] for send() address resolution
                    // When Python sends to old address, send() can resolve: old → identity → new
                    peersMutex.withLock {
                        connectedPeers.remove(existingAddress)
                        // Don't remove addressToIdentity[existingAddress] - needed for address resolution
                        identityToAddress.remove(identityHash)
                    }
                    // Also clean up pending central tracking
                    pendingCentralConnections.remove(existingAddress)

                    // Fall through to accept the new connection
                } else {
                    // VALID dual connection: same identity but opposite directions (MAC rotation case)
                    // Existing peer has one direction, new connection has opposite
                    Log.i(TAG, "Dual connection via MAC rotation: $identityHash has both central and peripheral")
                    Log.d(TAG, "  Existing: $existingAddress (central=$existingIsCentral, peripheral=$existingIsPeripheral)")
                    Log.d(TAG, "  New: $address (${if (isCentralConnection) "central" else "peripheral"})")

                    // Apply identity-based sorting to deduplicate
                    val localIdentity = transportIdentityHash
                    if (localIdentity != null) {
                        val localIdentityHex = localIdentity.joinToString("") { "%02x".format(it) }
                        val weKeepCentral = localIdentityHex < identityHash

                        // Determine which connection to disconnect
                        // If weKeepCentral: keep central (our connection to them), close peripheral (their connection to us)
                        // If !weKeepCentral: keep peripheral, close central
                        val centralAddr = if (isCentralConnection) address else existingAddress
                        val peripheralAddr = if (isCentralConnection) existingAddress else address

                        if (weKeepCentral) {
                            Log.i(TAG, "Identity sorting (MAC rotation): keeping central at $centralAddr, closing peripheral at $peripheralAddr")
                            Log.d(TAG, "  (local=$localIdentityHex < peer=$identityHash)")
                            // Close peripheral - the device that connected to us
                            gattServer.disconnectCentral(peripheralAddr)
                            // Clean up the peripheral peer connection, but KEEP addressToIdentity
                            // so that send() can resolve old addresses via identity lookup
                            peersMutex.withLock {
                                connectedPeers.remove(peripheralAddr)
                                // Keep addressToIdentity[peripheralAddr] for identity-based resolution
                            }
                        } else {
                            Log.i(TAG, "Identity sorting (MAC rotation): keeping peripheral at $peripheralAddr, closing central to $centralAddr")
                            Log.d(TAG, "  (local=$localIdentityHex >= peer=$identityHash)")
                            // Close central - our connection to them
                            gattClient.disconnect(centralAddr)
                            // Clean up the central peer connection, but KEEP addressToIdentity
                            // so that send() can resolve old addresses via identity lookup
                            peersMutex.withLock {
                                connectedPeers.remove(centralAddr)
                                // Keep addressToIdentity[centralAddr] for identity-based resolution
                            }
                            // Also clean up pending central tracking
                            pendingCentralConnections.remove(centralAddr)
                            // Prevent scanner from reconnecting as central for a while
                            // (we're already connected as peripheral to this identity)
                            recentlyDeduplicatedIdentities[identityHash] = System.currentTimeMillis()
                            Log.d(TAG, "Added $identityHash to deduplication cooldown (60s)")
                        }

                        // Update identity mapping to remaining address
                        val remainingAddr = if (weKeepCentral) centralAddr else peripheralAddr
                        peersMutex.withLock {
                            identityToAddress[identityHash] = remainingAddr
                        }

                        Log.i(TAG, "Dual connection deduplicated - $identityHash now only via $remainingAddr")
                        // Continue to notify Python about the remaining connection
                    } else {
                        Log.w(TAG, "Cannot deduplicate dual connection - local identity not set")
                        // Clean up old MAC mapping and continue with both for now
                        peersMutex.withLock {
                            addressToIdentity.remove(existingAddress)
                        }
                    }
                }
            } else {
                // Old connection is stale/gone, clean up old mapping AND connection
                Log.i(TAG, "Identity $identityHash migrating from stale $existingAddress to $address")

                // Clean up the stale connection fully
                val stalePeer = connectedPeers.remove(existingAddress)
                if (stalePeer != null) {
                    Log.d(TAG, "Removing stale connection entry for $existingAddress")
                    // Disconnect if still somehow connected
                    if (stalePeer.isCentral) {
                        gattClient.disconnect(existingAddress)
                    }
                    if (stalePeer.isPeripheral) {
                        gattServer.disconnectCentral(existingAddress)
                    }
                    // Notify Python of disconnect
                    onDisconnected?.callAttr("__call__", existingAddress)
                }

                // Clean up pending connections for stale address
                pendingConnections.remove(existingAddress)

                // Clean up address mapping
                peersMutex.withLock {
                    addressToIdentity.remove(existingAddress)
                }
            }
        }

        // Track if we need to deduplicate after mutex
        var needsDedupe = false
        var weKeepCentral = false

        // Track pending connection that was waiting for identity (race condition fix)
        var completedPending: PendingConnection? = null

        peersMutex.withLock {
            // Update mappings
            identityToAddress[identityHash] = address
            addressToIdentity[address] = identityHash

            // Update peer connection (if it exists yet)
            val peer = connectedPeers[address]
            if (peer != null) {
                peer.identityHash = identityHash

                // Check if this completes a dual connection that needs deduplication
                val localIdentity = transportIdentityHash
                if (peer.isCentral && peer.isPeripheral && localIdentity != null) {
                    val localIdentityHex = localIdentity.joinToString("") { "%02x".format(it) }
                    weKeepCentral = localIdentityHex < identityHash
                    needsDedupe = true
                    Log.i(TAG, "Identity received for dual connection $address - will deduplicate (local=$localIdentityHex, peer=$identityHash)")
                }
            }

            // Check for pending connection that was waiting for identity
            completedPending = pendingConnections.remove(address)
            if (completedPending != null) {
                Log.d(TAG, "Completing pending connection for $address - identity now available")
            }

            Log.i(TAG, "Identity received from $address: $identityHash")
        }

        // Deduplicate dual connection (outside mutex to avoid deadlock)
        if (needsDedupe) {
            val peer = connectedPeers[address]
            if (peer != null) {
                if (weKeepCentral) {
                    Log.i(TAG, "Identity sorting (deferred): keeping central, closing peripheral for $address")
                    gattServer.disconnectCentral(address)
                    peer.isPeripheral = false
                } else {
                    Log.i(TAG, "Identity sorting (deferred): keeping peripheral, closing central to $address")
                    gattClient.disconnect(address)
                    peer.isCentral = false
                }
            }
        }

        // Complete pending connection notification (race condition fix)
        // This fires the deferred onConnected callback with the now-available identity
        completedPending?.let { pending ->
            Log.w(
                TAG,
                "[CALLBACK] handleIdentityReceived: Completing DEFERRED connection " +
                    "for $address with identity ${identityHash.take(16)}...",
            )
            Log.i(TAG, "Notifying Python of deferred connection: $address with identity $identityHash")
            notifyPythonConnected(
                address = pending.address,
                mtu = pending.mtu,
                isCentral = pending.isCentral,
                isPeripheral = pending.isPeripheral,
                identityHash = identityHash,
            )
        } ?: run {
            Log.w(
                TAG,
                "[CALLBACK] handleIdentityReceived: NO pending connection for $address " +
                    "(identity arrived but no deferred connection)",
            )
        }

        // Notify Python (outside mutex to avoid blocking)
        Log.w(TAG, "[CALLBACK] handleIdentityReceived: Calling onIdentityReceived Python callback for $address")
        onIdentityReceived?.callAttr("__call__", address, identityHash)

        // NOTE: BLE bonding was attempted but Android-to-Android requires Numeric Comparison
        // (user must confirm 6-digit code) because both devices have displays. This is
        // Android's security model - we cannot force "Just Works" pairing.
        // Instead, we rely entirely on app-layer identity tracking for MAC rotation handling.
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
