package com.lxmf.messenger.data.model

/**
 * Represents the type of BLE connection with a peer.
 */
enum class ConnectionType {
    /** We connected to the peer as a central (GATT client) */
    CENTRAL,

    /** The peer connected to us as a peripheral (GATT server) */
    PERIPHERAL,

    /** Both central and peripheral connections are active */
    BOTH,
}

/**
 * Represents detailed information about an active BLE peer connection.
 *
 * @property identityHash The Reticulum identity hash of the peer (32-char hex string)
 * @property peerName The advertised BLE device name
 * @property currentMac The current MAC address of the peer
 * @property connectionType Type of connection (central, peripheral, or both)
 * @property rssi Signal strength in dBm (typically -100 to 0)
 * @property mtu Maximum transmission unit size for the connection
 * @property connectedAt Timestamp when the connection was established (milliseconds since epoch)
 * @property firstSeen Timestamp when the peer was first discovered (milliseconds since epoch)
 * @property lastSeen Timestamp of last communication with peer (milliseconds since epoch)
 * @property bytesReceived Total bytes received from this peer
 * @property bytesSent Total bytes sent to this peer
 * @property packetsReceived Total packets received from this peer
 * @property packetsSent Total packets sent to this peer
 * @property successRate Connection success rate (0.0 to 1.0)
 */
@androidx.compose.runtime.Immutable
data class BleConnectionInfo(
    val identityHash: String,
    val peerName: String,
    val currentMac: String,
    val connectionType: ConnectionType,
    val rssi: Int,
    val mtu: Int,
    val connectedAt: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val bytesReceived: Long,
    val bytesSent: Long,
    val packetsReceived: Long,
    val packetsSent: Long,
    val successRate: Double,
) {
    /**
     * Returns a shortened version of the identity hash (first 8 characters).
     */
    val shortIdentityHash: String
        get() = identityHash.take(8)

    /**
     * Returns the connection duration in milliseconds.
     */
    val connectionDurationMs: Long
        get() = System.currentTimeMillis() - connectedAt

    /**
     * Returns a signal quality rating based on RSSI.
     * Excellent: > -50 dBm
     * Good: -50 to -70 dBm
     * Fair: -70 to -85 dBm
     * Poor: < -85 dBm
     */
    val signalQuality: SignalQuality
        get() =
            when {
                rssi > -50 -> SignalQuality.EXCELLENT
                rssi > -70 -> SignalQuality.GOOD
                rssi > -85 -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }

    /**
     * Returns total data transferred (sent + received) in bytes.
     */
    val totalBytesTransferred: Long
        get() = bytesSent + bytesReceived
}

/**
 * Signal quality rating for a BLE connection.
 */
enum class SignalQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
}

/**
 * Represents the state of BLE connections, including adapter availability.
 */
sealed class BleConnectionsState {
    /** Bluetooth adapter is disabled at the system level */
    data object BluetoothDisabled : BleConnectionsState()

    /** Loading connection data */
    data object Loading : BleConnectionsState()

    /** Successfully loaded connection data */
    data class Success(val connections: List<BleConnectionInfo>) : BleConnectionsState()

    /** Error loading connection data */
    data class Error(val message: String) : BleConnectionsState()
}
