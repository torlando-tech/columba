package com.lxmf.messenger.reticulum.ble.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.lxmf.messenger.reticulum.ble.client.BleGattClient
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.model.BleConnectionState
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.model.BleDevice
import com.lxmf.messenger.reticulum.ble.server.BleAdvertiser
import com.lxmf.messenger.reticulum.ble.server.BleGattServer
import com.lxmf.messenger.reticulum.ble.util.BleOperationQueue
import com.lxmf.messenger.reticulum.ble.util.BlePairingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Central coordinator for BLE operations in dual-mode (central + peripheral).
 *
 * This manager orchestrates all BLE components:
 * - BleScanner: Discovery of nearby Reticulum nodes
 * - BleGattClient: Connecting to peers as central
 * - BleGattServer: Accepting connections as peripheral
 * - BleAdvertiser: Advertising our presence
 *
 * **Key responsibilities:**
 * - Coordinate dual-mode operation (central + peripheral simultaneously)
 * - Manage connection pool with max connection limits
 * - Handle dual connections (same peer via both modes)
 * - Route data between components (data arrives pre-fragmented from Python)
 * - Maintain connection state
 * - Handle connection prioritization and blacklisting
 *
 * **Connection Model:**
 * Each peer can have up to 2 connections:
 * - Central connection: We connect to their GATT server
 * - Peripheral connection: They connect to our GATT server
 *
 * **Data Flow:**
 * Python BLEInterface handles fragmentation/reassembly. Kotlin acts as pure transport.
 *
 * @property context Application context
 * @property bluetoothManager Bluetooth manager
 * @property scope Coroutine scope for async operations
 */
class BleConnectionManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:BLE:K:ConnMgr"
    }

    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val operationQueue = BleOperationQueue(scope)

    // BLE Components (public for BleService access)
    val scanner = BleScanner(context, bluetoothAdapter, scope)
    private val gattClient = BleGattClient(context, bluetoothAdapter, operationQueue, scope)
    val gattServer = BleGattServer(context, bluetoothManager, scope)
    val advertiser = BleAdvertiser(context, bluetoothAdapter, scope)

    // Pairing handler for auto-confirming BLE pairing requests (prevents system dialog)
    private val pairingHandler = BlePairingHandler(context)

    // Connection tracking

    /**
     * Peer identity tracking for stable identification across MAC address rotations.
     *
     * Android devices rotate their BLE MAC addresses every ~15 minutes for privacy.
     * This class tracks peers by their Reticulum Transport identity hash instead of MAC,
     * allowing reliable bidirectional mesh networking.
     *
     * @property identityHash 32-character hex string (16-byte Reticulum Transport identity hash)
     * @property currentMac Most recently observed MAC address for this peer
     * @property observedMacs All MAC addresses observed for this identity (tracks rotation history)
     * @property hasCentralConnection True if we have an active central connection to this peer
     * @property hasPeripheralConnection True if this peer has an active connection to our GATT server
     * @property centralMtu Negotiated MTU for central connection
     * @property peripheralMtu Negotiated MTU for peripheral connection
     * @property firstSeen Timestamp when this identity was first discovered
     * @property lastSeen Timestamp when this identity was last seen (any MAC)
     */
    private data class PeerIdentity(
        val identityHash: String,
        var currentMac: String,
        val observedMacs: MutableSet<String> = mutableSetOf(currentMac),
        var hasCentralConnection: Boolean = false,
        var hasPeripheralConnection: Boolean = false,
        var centralMtu: Int = BleConstants.MIN_MTU,
        var peripheralMtu: Int = BleConstants.MIN_MTU,
        val firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
    ) {
        val isConnected: Boolean
            get() = hasCentralConnection || hasPeripheralConnection

        val effectiveMtu: Int
            get() = maxOf(centralMtu, peripheralMtu)

        /**
         * Update the current MAC for this peer (handles MAC rotation).
         */
        fun updateMac(newMac: String) {
            if (currentMac != newMac) {
                observedMacs.add(newMac)
                currentMac = newMac
                lastSeen = System.currentTimeMillis()
            }
        }
    }

    private data class ConnectionInfo(
        val address: String,
        var hasCentralConnection: Boolean = false, // We connected to them
        var hasPeripheralConnection: Boolean = false, // They connected to us
        var centralMtu: Int = BleConstants.MIN_MTU,
        var peripheralMtu: Int = BleConstants.MIN_MTU,
        val connectedAt: Long = System.currentTimeMillis(),
    ) {
        val isConnected: Boolean
            get() = hasCentralConnection || hasPeripheralConnection

        val effectiveMtu: Int
            get() = maxOf(centralMtu, peripheralMtu)
    }

    // Identity-based peer tracking (Protocol v2)
    private val peers = ConcurrentHashMap<String, PeerIdentity>() // identityHash -> PeerIdentity
    private val macToIdentity = ConcurrentHashMap<String, String>() // MAC -> identityHash

    // Legacy MAC-based tracking (kept for backwards compatibility with Protocol v1)
    // ConcurrentHashMap provides thread-safe atomic operations without explicit locking
    private val connections = ConcurrentHashMap<String, ConnectionInfo>()

    // Connection blacklist (identityHash -> blacklist until timestamp, fallback to MAC for v1)
    private val connectionBlacklist = ConcurrentHashMap<String, Long>()

    // State
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    // Callbacks
    var onDeviceDiscovered: ((BleDevice) -> Unit)? = null
    var onConnected: ((String, String) -> Unit)? = null // address, type (central/peripheral)
    var onDisconnected: ((String, String?) -> Unit)? = null // address, reason
    var onDataReceived: ((String, ByteArray) -> Unit)? = null // address, complete packet
    var onStateChanged: ((BleConnectionState) -> Unit)? = null

    // Statistics
    data class Statistics(
        var totalCentralConnections: Long = 0,
        var totalPeripheralConnections: Long = 0,
        var packetsReceived: Long = 0,
        var packetsSent: Long = 0,
        var bytesReceived: Long = 0,
        var bytesSent: Long = 0,
        var fragmentsReceived: Long = 0,
        var fragmentsSent: Long = 0,
    )

    private val stats = Statistics()

    /**
     * Detailed information about a connected peer (for UI display).
     */
    data class PeerConnectionDetails(
        val identityHash: String,
        val peerName: String,
        val currentMac: String,
        val observedMacs: Set<String>,
        val hasCentralConnection: Boolean,
        val hasPeripheralConnection: Boolean,
        val centralMtu: Int,
        val peripheralMtu: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val connectedAt: Long,
        val rssi: Int,
        val bytesReceived: Long,
        val bytesSent: Long,
    )

    // BLE packet for Python bridge
    data class BlePacket(
        val address: String,
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as BlePacket
            if (address != other.address) return false
            if (!data.contentEquals(other.data)) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    // Queue for packets to forward to Python/Reticulum
    private val incomingPacketQueue = ConcurrentLinkedQueue<BlePacket>()

    init {
        setupCallbacks()
    }

    /**
     * Start BLE operations in dual-mode.
     *
     * @param deviceName Device name for advertising
     * @param enableCentral Enable central mode (scanning and connecting)
     * @param enablePeripheral Enable peripheral mode (advertising and accepting)
     * @return Result indicating success or failure
     */
    suspend fun start(
        deviceName: String,
        enableCentral: Boolean = true,
        enablePeripheral: Boolean = true,
    ): Result<Unit> {
        return try {
            Log.d(TAG, "Starting BLE operations (central: $enableCentral, peripheral: $enablePeripheral)")

            // Register pairing handler to auto-confirm BLE pairing requests
            pairingHandler.register()

            // Start peripheral mode (server + advertising)
            if (enablePeripheral) {
                val serverResult = gattServer.open()
                if (serverResult.isFailure) {
                    return Result.failure(
                        Exception("Failed to start GATT server: ${serverResult.exceptionOrNull()?.message}"),
                    )
                }

                val advertiseResult = advertiser.startAdvertising(deviceName)
                if (advertiseResult.isFailure) {
                    Log.w(TAG, "Failed to start advertising: ${advertiseResult.exceptionOrNull()?.message}")
                    // Continue anyway - peripheral mode can work without advertising
                }
            }

            // Start central mode (scanning)
            if (enableCentral) {
                val scanResult = scanner.startScanning()
                if (scanResult.isFailure) {
                    return Result.failure(
                        Exception("Failed to start scanning: ${scanResult.exceptionOrNull()?.message}"),
                    )
                }

                updateState(BleConnectionState.Scanning)
            }

            Log.d(TAG, "BLE operations started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE operations", e)
            updateState(BleConnectionState.Error(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /**
     * Stop all BLE operations.
     */
    suspend fun stop() {
        Log.d(TAG, "Stopping BLE operations")

        // Stop scanning and advertising
        scanner.stopScanning()
        advertiser.stopAdvertising()

        // Disconnect all connections
        disconnectAll()

        // Close server
        gattServer.close()

        // Unregister pairing handler
        pairingHandler.unregister()

        updateState(BleConnectionState.Idle)
        Log.d(TAG, "BLE operations stopped")
    }

    /**
     * Set the transport identity hash on all BLE components.
     * Required for BLE Protocol v2.2 identity-based peer tracking.
     *
     * @param identityHash 16-byte Reticulum Transport identity hash
     */
    fun setTransportIdentity(identityHash: ByteArray) {
        require(identityHash.size == 16) {
            "Transport identity hash must be exactly 16 bytes, got ${identityHash.size}"
        }

        Log.i(TAG, "Setting transport identity on all BLE components")
        Log.i(TAG, "  Identity: ${identityHash.joinToString("") { "%02x".format(it) }}")

        // Set on all components for Protocol v2.2 compliance
        gattClient.setTransportIdentity(identityHash)
        gattServer.setTransportIdentity(identityHash)
        advertiser.setTransportIdentity(identityHash)

        Log.d(TAG, "Transport identity set on client, server, and advertiser")
    }

    /**
     * Connect to a specific device (central mode).
     *
     * @param address Device MAC address
     * @return Result indicating success or failure
     */
    suspend fun connectToDevice(address: String): Result<Unit> {
        // Check blacklist (identity-aware)
        if (isBlacklisted(address)) {
            val peerKey = getPeerKey(address)
            val blacklistExpiry = connectionBlacklist[peerKey] ?: 0L
            val remainingMs = blacklistExpiry - System.currentTimeMillis()
            return Result.failure(
                IllegalStateException("Device $address (key: $peerKey) is blacklisted for ${remainingMs / 1000}s"),
            )
        }

        // Check if already connected (ConcurrentHashMap.get() is thread-safe)
        connections[address]?.let { info ->
            if (info.hasCentralConnection) {
                Log.d(TAG, "Already connected to $address as central")
                return Result.success(Unit)
            }
        }

        // Check connection limit
        if (!canAcceptNewConnection()) {
            return Result.failure(
                IllegalStateException("Connection limit reached (${BleConstants.MAX_CONNECTIONS})"),
            )
        }

        // Connect via GATT client
        return gattClient.connect(address)
    }

    /**
     * Disconnect from a specific device.
     *
     * @param address Device MAC address
     */
    suspend fun disconnectDevice(address: String) {
        // ConcurrentHashMap.get() and remove() are atomic
        val info = connections[address]

        if (info?.hasCentralConnection == true) {
            gattClient.disconnect(address)
        }

        // Note: We can't forcibly disconnect peripheral connections
        // (centrals disconnect from us)

        // Safe to check and remove: even if state changes between read and remove,
        // connection callbacks will handle re-adding if needed
        if (info != null && !info.isConnected) {
            connections.remove(address)
            cleanupPeer(address)
        }
    }

    /**
     * Disconnect from all devices.
     */
    suspend fun disconnectAll() {
        val addresses = connections.keys.toList() // ConcurrentHashMap.keys is thread-safe
        addresses.forEach { disconnectDevice(it) }
    }

    /**
     * Send data to a specific peer.
     *
     * Data will be automatically fragmented if needed and sent via the best available connection.
     *
     * @param address Peer MAC address
     * @param data Complete packet to send
     * @return Result indicating success or failure
     */
    suspend fun sendData(
        address: String,
        data: ByteArray,
    ): Result<Unit> {
        Log.d(TAG, "sendData() called: address=$address, data.size=${data.size}")

        val info = connections[address] // ConcurrentHashMap.get() is thread-safe
        if (info == null) {
            Log.w(TAG, "sendData() failed: Not connected to $address")
            return Result.failure(IllegalStateException("Not connected to $address"))
        }

        if (!info.isConnected) {
            Log.w(TAG, "sendData() failed: No active connection to $address")
            return Result.failure(IllegalStateException("No active connection to $address"))
        }

        return try {
            // Data is pre-fragmented by Python BLEInterface - pass through unchanged
            Log.d(TAG, "Sending ${data.size} byte fragment to $address")

            // Send via best available connection
            val sendResult =
                if (info.hasCentralConnection) {
                    // Prefer central connection (we control it)
                    sendFragmentsViaCentral(address, listOf(data))
                } else {
                    // Fall back to peripheral connection
                    sendFragmentsViaPeripheral(address, listOf(data))
                }

            if (sendResult.isSuccess) {
                stats.packetsSent++
                stats.bytesSent += data.size
            }

            sendResult
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data to $address", e)
            Result.failure(e)
        }
    }

    /**
     * Get list of connected peer addresses.
     */
    fun getConnectedPeers(): List<String> {
        // ConcurrentHashMap.values provides a consistent snapshot
        return connections.values.filter { it.isConnected }.map { it.address }
    }

    /**
     * Get current statistics.
     */
    fun getStatistics(): Statistics = stats.copy()

    /**
     * Get diagnostic info about peer tracking (for troubleshooting).
     */
    fun getDiagnosticInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== BLE Peer Tracking Diagnostics ===")
        sb.appendLine("Identity-Based Peers: ${peers.size}")
        peers.forEach { (identityHash, peer) ->
            sb.appendLine("  Identity: $identityHash")
            sb.appendLine("    Current MAC: ${peer.currentMac}")
            sb.appendLine("    Observed MACs: ${peer.observedMacs}")
            sb.appendLine("    Central: ${peer.hasCentralConnection} (MTU: ${peer.centralMtu})")
            sb.appendLine("    Peripheral: ${peer.hasPeripheralConnection} (MTU: ${peer.peripheralMtu})")
            sb.appendLine("    First seen: ${peer.firstSeen}, Last seen: ${peer.lastSeen}")
        }
        sb.appendLine("MAC Mappings: ${macToIdentity.size}")
        macToIdentity.forEach { (mac, identity) ->
            sb.appendLine("  $mac -> $identity")
        }
        sb.appendLine("Blacklist: ${connectionBlacklist.size}")
        sb.appendLine("===================================")
        return sb.toString()
    }

    /**
     * Get discovered devices sorted by priority.
     */
    suspend fun getDiscoveredDevices(): List<BleDevice> {
        return scanner.getDevicesSortedByPriority()
    }

    /**
     * Get detailed information about all connected peers.
     * Combines peer identity, connection state, and device info.
     */
    suspend fun getConnectedPeersDetails(): List<PeerConnectionDetails> {
        val connectedPeers = mutableListOf<PeerConnectionDetails>()
        val discoveredDevices = scanner.getDevicesSortedByPriority()
        val deviceMap = discoveredDevices.associateBy { it.address }

        // Get connected peers from identity tracking
        peers.values.filter { it.isConnected }.forEach { peer ->
            val device = deviceMap[peer.currentMac]
            val connection = connections[peer.currentMac] // ConcurrentHashMap.get() is thread-safe

            connectedPeers.add(
                PeerConnectionDetails(
                    identityHash = peer.identityHash,
                    peerName = device?.name ?: "Unknown",
                    currentMac = peer.currentMac,
                    observedMacs = peer.observedMacs.toSet(),
                    hasCentralConnection = peer.hasCentralConnection,
                    hasPeripheralConnection = peer.hasPeripheralConnection,
                    centralMtu = peer.centralMtu,
                    peripheralMtu = peer.peripheralMtu,
                    firstSeen = peer.firstSeen,
                    lastSeen = peer.lastSeen,
                    connectedAt = connection?.connectedAt ?: peer.firstSeen,
                    rssi = device?.rssi ?: -100,
                    bytesReceived = 0, // TODO: Track per-peer stats
                    bytesSent = 0, // TODO: Track per-peer stats
                ),
            )
        }

        return connectedPeers
    }

    // ========== Private Helper Methods ==========

    private fun setupCallbacks() {
        // Scanner callbacks
        scanner.onDeviceDiscovered = { device ->
            scope.launch {
                handleDeviceDiscovered(device)
            }
        }

        // GATT Client callbacks
        gattClient.onConnected = { address, mtu ->
            scope.launch {
                handleCentralConnected(address, mtu)
            }
        }

        gattClient.onDisconnected = { address, reason ->
            scope.launch {
                handleCentralDisconnected(address, reason)
            }
        }

        gattClient.onConnectionFailed = { address, error ->
            scope.launch {
                handleCentralConnectionFailed(address, error)
            }
        }

        gattClient.onDataReceived = { address, fragment ->
            scope.launch {
                handleDataReceived(address, fragment)
            }
        }

        gattClient.onMtuChanged = { address, mtu ->
            scope.launch {
                handleCentralMtuChanged(address, mtu)
            }
        }

        gattClient.onIdentityReceived = { address, identityHash ->
            scope.launch {
                handleIdentityReceived(address, identityHash)
            }
        }

        // GATT Server callbacks
        gattServer.onCentralConnected = { address, mtu ->
            scope.launch {
                handlePeripheralConnected(address)
            }
        }

        gattServer.onCentralDisconnected = { address ->
            scope.launch {
                handlePeripheralDisconnected(address)
            }
        }

        gattServer.onDataReceived = { address, fragment ->
            scope.launch {
                handleDataReceived(address, fragment)
            }
        }

        gattServer.onMtuChanged = { address, mtu ->
            scope.launch {
                handlePeripheralMtuChanged(address, mtu)
            }
        }

        gattServer.onIdentityReceived = { address, identityHash ->
            scope.launch {
                handleIdentityReceived(address, identityHash)
            }
        }
    }

    private suspend fun handleDeviceDiscovered(device: BleDevice) {
        Log.d(TAG, "Discovered device: ${device.address} (${device.name}) RSSI: ${device.rssi} dBm")

        // Validate device has Reticulum service UUID before connecting
        val hasReticulumService =
            device.serviceUuids?.any {
                it.equals(BleConstants.SERVICE_UUID.toString(), ignoreCase = true)
            } ?: false

        if (!hasReticulumService) {
            Log.w(TAG, "Device ${device.address} discovered but missing Reticulum service UUID, ignoring")
            Log.d(TAG, "  Advertised services: ${device.serviceUuids}")
            return
        }

        onDeviceDiscovered?.invoke(device)

        // Auto-connect if under connection limit and not blacklisted
        if (canAcceptNewConnection() && !isBlacklisted(device.address)) {
            // ConcurrentHashMap.get() is thread-safe
            val alreadyConnected = connections[device.address]?.hasCentralConnection == true

            if (!alreadyConnected) {
                Log.d(TAG, "Auto-connecting to ${device.address} (verified Reticulum service)")
                connectToDevice(device.address)
            }
        }
    }

    private fun handleCentralConnected(
        address: String,
        mtu: Int,
    ) {
        // ConcurrentHashMap.getOrPut() is atomic
        val info = connections.getOrPut(address) { ConnectionInfo(address) }
        info.hasCentralConnection = true
        info.centralMtu = mtu

        stats.totalCentralConnections++

        Log.d(TAG, "Central connection established: $address (MTU: $mtu)")
        onConnected?.invoke(address, "central")
        updateConnectionState()
    }

    private fun handleCentralDisconnected(
        address: String,
        reason: String?,
    ) {
        // ConcurrentHashMap operations are atomic
        val info = connections[address]
        if (info != null) {
            info.hasCentralConnection = false
            // Safe to remove even with race conditions: callbacks will re-add if reconnected
            if (!info.isConnected) {
                connections.remove(address)
                cleanupPeer(address)
            }
        }

        Log.d(TAG, "Central connection lost: $address (reason: $reason)")
        onDisconnected?.invoke(address, reason)
        updateConnectionState()
    }

    private suspend fun handleCentralConnectionFailed(
        address: String,
        error: String,
    ) {
        Log.w(TAG, "Central connection failed: $address ($error)")

        // Add to blacklist with exponential backoff (use identity-based key)
        val peerKey = getPeerKey(address)
        val device = scanner.getDevice(address)
        device?.let {
            val updatedDevice = it.recordConnectionFailure()

            if (updatedDevice.failedConnections >= BleConstants.MAX_CONNECTION_FAILURES) {
                val backoffMs =
                    BleConstants.CONNECTION_RETRY_BACKOFF_MS *
                        (1L shl (updatedDevice.failedConnections - BleConstants.MAX_CONNECTION_FAILURES))
                val maxBackoff = BleConstants.MAX_CONNECTION_RETRY_BACKOFF_MS
                val blacklistDuration = minOf(backoffMs, maxBackoff)

                connectionBlacklist[peerKey] = System.currentTimeMillis() + blacklistDuration
                Log.d(TAG, "Blacklisted $address (key: $peerKey) for ${blacklistDuration / 1000}s")
            }
        }

        // Remove from scanner cache so device can be rediscovered and reconnected
        // This enables retry on next scan (unless blacklisted above)
        scanner.removeDevice(address)
    }

    private fun handlePeripheralConnected(address: String) {
        // ConcurrentHashMap.getOrPut() is atomic
        val info = connections.getOrPut(address) { ConnectionInfo(address) }
        info.hasPeripheralConnection = true

        stats.totalPeripheralConnections++

        Log.d(TAG, "Peripheral connection established: $address")
        onConnected?.invoke(address, "peripheral")
        updateConnectionState()
    }

    private fun handlePeripheralDisconnected(address: String) {
        // ConcurrentHashMap operations are atomic
        val info = connections[address]
        if (info != null) {
            info.hasPeripheralConnection = false
            // Safe to remove even with race conditions: callbacks will re-add if reconnected
            if (!info.isConnected) {
                connections.remove(address)
                cleanupPeer(address)
            }
        }

        Log.d(TAG, "Peripheral connection lost: $address")
        onDisconnected?.invoke(address, null)
        updateConnectionState()
    }

    /**
     * Process received data fragment (called from GATT callbacks).
     * Data arrives as pre-fragmented packets from remote peer.
     * Pass directly to Python for reassembly by BLEInterface.
     */
    private fun handleDataReceived(
        address: String,
        fragment: ByteArray,
    ) {
        try {
            stats.fragmentsReceived++

            Log.d(TAG, "Received ${fragment.size} byte fragment from $address")

            // Queue fragment for Python/Reticulum (Python BLEInterface handles reassembly)
            incomingPacketQueue.offer(BlePacket(address, fragment))
            Log.d(TAG, "Fragment queued for Python (queue size: ${incomingPacketQueue.size})")

            // Also invoke callback for BleTestScreen
            onDataReceived?.invoke(address, fragment)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing received data from $address", e)
        }
    }

    private fun handleCentralMtuChanged(
        address: String,
        mtu: Int,
    ) {
        // Update identity-based peer if available
        val identityHash = macToIdentity[address]
        if (identityHash != null) {
            peers[identityHash]?.centralMtu = mtu
        }

        // ConcurrentHashMap.get() is thread-safe, property modification on retrieved object is safe
        connections[address]?.centralMtu = mtu

        Log.d(TAG, "Central MTU changed for $address: $mtu")
    }

    private fun handlePeripheralMtuChanged(
        address: String,
        mtu: Int,
    ) {
        // Update identity-based peer if available
        val identityHash = macToIdentity[address]
        if (identityHash != null) {
            peers[identityHash]?.peripheralMtu = mtu
        }

        // ConcurrentHashMap.get() is thread-safe, property modification on retrieved object is safe
        connections[address]?.peripheralMtu = mtu

        Log.d(TAG, "Peripheral MTU changed for $address: $mtu")
    }

    private fun handleIdentityReceived(
        address: String,
        identityHash: String,
    ) {
        Log.d(TAG, "Identity received: $address -> $identityHash")

        // Check if we've seen this identity before with a different MAC
        val existingPeer = peers[identityHash]
        if (existingPeer != null && existingPeer.currentMac != address) {
            val oldMac = existingPeer.currentMac
            Log.i(TAG, "MAC rotation detected for $identityHash: $oldMac -> $address")

            // Update MAC mapping
            existingPeer.updateMac(address)
            macToIdentity[address] = identityHash
        } else {
            // New identity or same MAC
            val isNew = existingPeer == null
            val peer =
                peers.getOrPut(identityHash) {
                    PeerIdentity(identityHash = identityHash, currentMac = address)
                }
            peer.updateMac(address)
            macToIdentity[address] = identityHash

            if (isNew) {
                Log.d(TAG, "New peer identity registered: $identityHash")
            }
        }

        // Update connection info with identity (ConcurrentHashMap operations are thread-safe)
        connections[address]?.let { info ->
            // Transfer connection state to identity-based tracking
            val peer = peers[identityHash]
            if (peer != null) {
                peer.hasCentralConnection = info.hasCentralConnection
                peer.hasPeripheralConnection = info.hasPeripheralConnection
                peer.centralMtu = info.centralMtu
                peer.peripheralMtu = info.peripheralMtu
            }
        }

        Log.d(TAG, "Peer tracking: ${peers.size} identities, ${macToIdentity.size} MAC mappings")
    }

    /**
     * Get the tracking key for a peer (identity if known, otherwise MAC).
     * Used for keying blacklists and connection tracking.
     */
    private fun getPeerKey(address: String): String {
        return macToIdentity[address] ?: address
    }

    /**
     * Get the current MAC address for an identity (if known).
     */
    private fun getIdentityMac(identityHash: String): String? {
        return peers[identityHash]?.currentMac
    }

    /**
     * Check if an address or identity is blacklisted.
     */
    private fun isBlacklistedByKey(key: String): Boolean {
        val blacklistUntil = connectionBlacklist[key] ?: return false
        if (System.currentTimeMillis() < blacklistUntil) {
            return true
        }
        // Expired, remove from blacklist
        connectionBlacklist.remove(key)
        return false
    }

    private suspend fun sendFragmentsViaCentral(
        address: String,
        fragments: List<ByteArray>,
    ): Result<Unit> {
        return try {
            for (fragment in fragments) {
                val result = gattClient.sendData(address, fragment)
                if (result.isFailure) {
                    return result
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendFragmentsViaPeripheral(
        address: String,
        fragments: List<ByteArray>,
    ): Result<Unit> {
        return try {
            for (fragment in fragments) {
                val result = gattServer.notifyCentrals(fragment, address)
                if (result.isFailure) {
                    return result
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanupPeer(address: String) {
        // Remove MAC mapping if this peer has an identity
        val identityHash = macToIdentity.remove(address)
        if (identityHash != null) {
            // Only remove peer if no other MACs reference it
            val hasOtherMacs = macToIdentity.values.contains(identityHash)
            if (!hasOtherMacs) {
                peers.remove(identityHash)
                Log.d(TAG, "Removed peer identity: $identityHash")
            }
        }
    }

    private fun canAcceptNewConnection(): Boolean {
        // ConcurrentHashMap.values provides a consistent snapshot
        val currentConnections = connections.values.count { it.isConnected }
        return currentConnections < BleConstants.MAX_CONNECTIONS
    }

    private fun isBlacklisted(address: String): Boolean {
        // Check both identity and MAC-based blacklists
        val peerKey = getPeerKey(address)
        return isBlacklistedByKey(peerKey)
    }

    private fun updateConnectionState() {
        val peers = getConnectedPeers()
        // ConcurrentHashMap.values provides a consistent snapshot
        val centralConns = connections.values.count { it.hasCentralConnection }
        val peripheralConns = connections.values.count { it.hasPeripheralConnection }

        val newState =
            when {
                peers.isEmpty() && scanner.isScanning.value -> BleConnectionState.Scanning
                peers.isNotEmpty() ->
                    BleConnectionState.Connected(
                        peers = peers,
                        centralConnections = centralConns,
                        peripheralConnections = peripheralConns,
                    )
                else -> BleConnectionState.Idle
            }

        updateState(newState)
    }

    private fun updateState(state: BleConnectionState) {
        _connectionState.value = state
        onStateChanged?.invoke(state)
    }

    /**
     * Drain the incoming packet queue for Python/Reticulum.
     *
     * Returns all pending packets and clears the queue.
     * Called by ReticulumService to forward packets to Python.
     *
     * @return List of BlePacket objects
     */
    fun drainIncomingQueue(): List<BlePacket> {
        val packets = mutableListOf<BlePacket>()
        while (true) {
            val packet = incomingPacketQueue.poll() ?: break
            packets.add(packet)
        }
        if (packets.isNotEmpty()) {
            Log.d(TAG, "Drained ${packets.size} packets for Reticulum")
        }
        return packets
    }

    /**
     * Get the current queue size (for monitoring).
     */
    fun getQueueSize(): Int = incomingPacketQueue.size

    /**
     * Shutdown the connection manager.
     */
    suspend fun shutdown() {
        stop()

        operationQueue.shutdown()
        scanner.shutdown()
        gattClient.shutdown()
        gattServer.shutdown()
        advertiser.shutdown()
        scope.cancel()
    }
}
