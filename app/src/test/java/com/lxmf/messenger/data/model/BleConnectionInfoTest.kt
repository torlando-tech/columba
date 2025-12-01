package com.lxmf.messenger.data.model

import com.lxmf.messenger.test.BleTestFixtures
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_EXCELLENT
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_EXCELLENT_BOUNDARY
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_FAIR
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_FAIR_BOUNDARY
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_GOOD
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_GOOD_BOUNDARY
import com.lxmf.messenger.test.BleTestFixtures.Constants.RSSI_POOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for BleConnectionInfo data model.
 * Tests computed properties, signal quality mapping, and edge cases.
 */
class BleConnectionInfoTest {
    // ========== Short Identity Hash Tests ==========

    @Test
    fun `shortIdentityHash returns first 8 characters`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "abcd1234efgh5678ijkl9012mnop3456",
            )

        assertEquals("abcd1234", connection.shortIdentityHash)
    }

    @Test
    fun `shortIdentityHash handles short hash correctly`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "short",
            )

        assertEquals("short", connection.shortIdentityHash)
    }

    @Test
    fun `shortIdentityHash handles empty hash`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "",
            )

        assertEquals("", connection.shortIdentityHash)
    }

    // ========== Connection Duration Tests ==========

    @Test
    fun `connectionDurationMs calculates correct duration`() {
        val connectedAt = System.currentTimeMillis() - 60000 // 1 minute ago
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                connectedAt = connectedAt,
            )

        val duration = connection.connectionDurationMs
        assertTrue("Duration should be approximately 60000ms", duration in 59000..61000)
    }

    @Test
    fun `connectionDurationMs handles zero duration`() {
        val now = System.currentTimeMillis()
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                connectedAt = now,
            )

        val duration = connection.connectionDurationMs
        assertTrue("Duration should be close to 0", duration < 100)
    }

    @Test
    fun `connectionDurationMs handles long duration`() {
        val connectedAt = System.currentTimeMillis() - 86400000 // 24 hours ago
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                connectedAt = connectedAt,
            )

        val duration = connection.connectionDurationMs
        assertTrue("Duration should be approximately 24 hours", duration in 86300000..86500000)
    }

    // ========== Signal Quality Tests ==========

    @Test
    fun `signalQuality EXCELLENT for rssi greater than -50`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_EXCELLENT)
        assertEquals(SignalQuality.EXCELLENT, connection.signalQuality)
    }

    @Test
    fun `signalQuality GOOD for rssi between -70 and -50`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_GOOD)
        assertEquals(SignalQuality.GOOD, connection.signalQuality)
    }

    @Test
    fun `signalQuality FAIR for rssi between -85 and -70`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_FAIR)
        assertEquals(SignalQuality.FAIR, connection.signalQuality)
    }

    @Test
    fun `signalQuality POOR for rssi less than -85`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_POOR)
        assertEquals(SignalQuality.POOR, connection.signalQuality)
    }

    // Boundary value tests
    @Test
    fun `signalQuality EXCELLENT at -49 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -49)
        assertEquals(SignalQuality.EXCELLENT, connection.signalQuality)
    }

    @Test
    fun `signalQuality GOOD at -50 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_EXCELLENT_BOUNDARY)
        assertEquals(SignalQuality.GOOD, connection.signalQuality)
    }

    @Test
    fun `signalQuality GOOD at -69 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -69)
        assertEquals(SignalQuality.GOOD, connection.signalQuality)
    }

    @Test
    fun `signalQuality FAIR at -70 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_GOOD_BOUNDARY)
        assertEquals(SignalQuality.FAIR, connection.signalQuality)
    }

    @Test
    fun `signalQuality FAIR at -84 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -84)
        assertEquals(SignalQuality.FAIR, connection.signalQuality)
    }

    @Test
    fun `signalQuality POOR at -85 boundary`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = RSSI_FAIR_BOUNDARY)
        assertEquals(SignalQuality.POOR, connection.signalQuality)
    }

    // Edge cases
    @Test
    fun `signalQuality handles zero rssi`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = 0)
        assertEquals(SignalQuality.EXCELLENT, connection.signalQuality)
    }

    @Test
    fun `signalQuality handles extremely negative rssi`() {
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -120)
        assertEquals(SignalQuality.POOR, connection.signalQuality)
    }

    // ========== Total Bytes Transferred Tests ==========

    @Test
    fun `totalBytesTransferred sums sent and received bytes`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                bytesSent = 1024,
                bytesReceived = 2048,
            )

        assertEquals(3072, connection.totalBytesTransferred)
    }

    @Test
    fun `totalBytesTransferred handles zero bytes`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                bytesSent = 0,
                bytesReceived = 0,
            )

        assertEquals(0, connection.totalBytesTransferred)
    }

    @Test
    fun `totalBytesTransferred handles large values`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                bytesSent = 1_000_000_000,
                bytesReceived = 2_000_000_000,
            )

        assertEquals(3_000_000_000, connection.totalBytesTransferred)
    }

    // ========== Connection Type Tests ==========

    @Test
    fun `ConnectionType enum has all expected values`() {
        val types = ConnectionType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(ConnectionType.CENTRAL))
        assertTrue(types.contains(ConnectionType.PERIPHERAL))
        assertTrue(types.contains(ConnectionType.BOTH))
    }

    // ========== Signal Quality Enum Tests ==========

    @Test
    fun `SignalQuality enum has all expected values`() {
        val qualities = SignalQuality.values()
        assertEquals(4, qualities.size)
        assertTrue(qualities.contains(SignalQuality.EXCELLENT))
        assertTrue(qualities.contains(SignalQuality.GOOD))
        assertTrue(qualities.contains(SignalQuality.FAIR))
        assertTrue(qualities.contains(SignalQuality.POOR))
    }

    // ========== Data Class Equality Tests ==========

    @Test
    fun `two BleConnectionInfo with same values are equal`() {
        // Use explicit timestamp values to avoid race condition with System.currentTimeMillis()
        val fixedTime = 1000000L
        val connection1 =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "test123",
                peerName = "Peer1",
                rssi = -70,
                connectedAt = fixedTime,
                firstSeen = fixedTime,
                lastSeen = fixedTime,
            )
        val connection2 =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "test123",
                peerName = "Peer1",
                rssi = -70,
                connectedAt = fixedTime,
                firstSeen = fixedTime,
                lastSeen = fixedTime,
            )

        assertEquals(connection1, connection2)
    }

    @Test
    fun `two BleConnectionInfo with different values are not equal`() {
        val connection1 =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "test123",
                peerName = "Peer1",
            )
        val connection2 =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "test456",
                peerName = "Peer2",
            )

        assertTrue(connection1 != connection2)
    }

    // ========== Edge Case Integration Tests ==========

    @Test
    fun `handles connection with all minimum values`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "",
                peerName = "",
                currentMac = "",
                rssi = -120,
                mtu = 23, // BLE minimum MTU
                bytesReceived = 0,
                bytesSent = 0,
                packetsReceived = 0,
                packetsSent = 0,
                successRate = 0.0,
            )

        assertEquals("", connection.shortIdentityHash)
        assertEquals(SignalQuality.POOR, connection.signalQuality)
        assertEquals(0, connection.totalBytesTransferred)
        assertTrue(connection.connectionDurationMs >= 0)
    }

    @Test
    fun `handles connection with all maximum practical values`() {
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                identityHash = "a".repeat(32),
                peerName = "Very Long Peer Name With Many Characters",
                rssi = 0,
                mtu = 512,
                bytesReceived = Long.MAX_VALUE / 2,
                bytesSent = Long.MAX_VALUE / 2,
                successRate = 1.0,
            )

        assertEquals("aaaaaaaa", connection.shortIdentityHash)
        assertEquals(SignalQuality.EXCELLENT, connection.signalQuality)
        assertEquals(Long.MAX_VALUE - 1, connection.totalBytesTransferred)
    }
}
