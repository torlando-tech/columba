package com.lxmf.messenger.test

import com.lxmf.messenger.data.model.BleConnectionInfo
import com.lxmf.messenger.data.model.ConnectionType

/**
 * Test fixtures and factory functions for BLE connection testing.
 */
object BleTestFixtures {
    /**
     * Creates a test BleConnectionInfo with customizable parameters.
     */
    fun createBleConnectionInfo(
        identityHash: String = "abcd1234efgh5678ijkl9012mnop3456",
        peerName: String = "RNS-TestPeer",
        currentMac: String = "AA:BB:CC:DD:EE:FF",
        connectionType: ConnectionType = ConnectionType.BOTH,
        rssi: Int = -70,
        mtu: Int = 512,
        connectedAt: Long = System.currentTimeMillis() - 60000, // 1 minute ago
        firstSeen: Long = System.currentTimeMillis() - 300000, // 5 minutes ago
        lastSeen: Long = System.currentTimeMillis(),
        bytesReceived: Long = 1024,
        bytesSent: Long = 2048,
        packetsReceived: Long = 10,
        packetsSent: Long = 20,
        successRate: Double = 1.0,
    ): BleConnectionInfo {
        return BleConnectionInfo(
            identityHash = identityHash,
            peerName = peerName,
            currentMac = currentMac,
            connectionType = connectionType,
            rssi = rssi,
            mtu = mtu,
            connectedAt = connectedAt,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            bytesReceived = bytesReceived,
            bytesSent = bytesSent,
            packetsReceived = packetsReceived,
            packetsSent = packetsSent,
            successRate = successRate,
        )
    }

    /**
     * Creates a list of test BLE connections with varying properties.
     */
    fun createMultipleConnections(
        count: Int = 3,
        rssiRange: IntRange = -90..-50,
    ): List<BleConnectionInfo> {
        val connections = mutableListOf<BleConnectionInfo>()
        val types = listOf(ConnectionType.CENTRAL, ConnectionType.PERIPHERAL, ConnectionType.BOTH)

        for (i in 0 until count) {
            connections.add(
                createBleConnectionInfo(
                    identityHash = "test${i}hash${"0".repeat(24)}",
                    peerName = "RNS-Peer$i",
                    currentMac = "AA:BB:CC:DD:EE:${String.format("%02X", i)}",
                    connectionType = types[i % types.size],
                    rssi = rssiRange.random(),
                    connectedAt = System.currentTimeMillis() - (i * 60000L),
                ),
            )
        }

        return connections
    }

    /**
     * Creates a JSON string representing BLE connection details (for IPC mocking).
     */
    fun createBleConnectionsJson(connections: List<BleConnectionInfo>): String {
        val jsonObjects =
            connections.map { connection ->
                val hasCentral =
                    connection.connectionType == ConnectionType.CENTRAL ||
                        connection.connectionType == ConnectionType.BOTH
                val hasPeripheral =
                    connection.connectionType == ConnectionType.PERIPHERAL ||
                        connection.connectionType == ConnectionType.BOTH

                """{"identityHash":"${connection.identityHash}","peerName":"${connection.peerName}","currentMac":"${connection.currentMac}","hasCentralConnection":$hasCentral,"hasPeripheralConnection":$hasPeripheral,"mtu":${connection.mtu},"connectedAt":${connection.connectedAt},"firstSeen":${connection.firstSeen},"lastSeen":${connection.lastSeen},"rssi":${connection.rssi}}"""
            }

        return "[${jsonObjects.joinToString(",")}]"
    }

    /**
     * Creates an empty JSON response.
     */
    fun createEmptyJson(): String = "[]"

    /**
     * Creates malformed JSON for error testing.
     */
    fun createMalformedJson(): String = "{invalid json"

    /**
     * Creates JSON with missing required fields.
     */
    fun createIncompleteJson(): String {
        return """[{"identityHash": "test123"}]"""
    }

    /**
     * Test data constants
     */
    object Constants {
        const val TEST_IDENTITY_HASH = "1234567890abcdef1234567890abcdef"
        const val TEST_PEER_NAME = "RNS-TestDevice"
        const val TEST_MAC_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val TEST_MTU = 512

        // RSSI thresholds for signal quality
        const val RSSI_EXCELLENT = -40
        const val RSSI_GOOD = -60
        const val RSSI_FAIR = -75
        const val RSSI_POOR = -90

        // Boundary values
        const val RSSI_EXCELLENT_BOUNDARY = -50
        const val RSSI_GOOD_BOUNDARY = -70
        const val RSSI_FAIR_BOUNDARY = -85
    }
}
