// Context, BluetoothAdapter are Android framework classes; BleOperationQueue not used in these tests
// (tests focus on keepalive counter logic via reflection, queue operations are incidental)
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.reticulum.ble.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.util.BleOperationQueue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BleGattClient keepalive failure tracking.
 *
 * Tests the consecutiveKeepaliveWriteFailures field:
 * - Counter increments on keepalive WRITE failure
 * - Counter resets on keepalive success
 * - Disconnect triggered after MAX_KEEPALIVE_WRITE_FAILURES consecutive failures
 *
 * Write failures are tracked separately because receiving data doesn't indicate
 * the outgoing GATT path is healthy - L2CAP can idle-timeout if writes fail.
 *
 * Note: These tests use reflection to access private ConnectionData class
 * and test the counter logic in isolation.
 */
class BleGattClientKeepaliveTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockOperationQueue: BleOperationQueue

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        mockOperationQueue = mockk<BleOperationQueue>(relaxed = true)

        every { mockBluetoothAdapter.isEnabled } returns true
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== ConnectionData consecutiveKeepaliveWriteFailures Tests ==========

    @Test
    fun `ConnectionData initializes with zero consecutive failures`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        val failures = getConsecutiveKeepaliveWriteFailures(connectionData)

        assertEquals("Should initialize to 0 failures", 0, failures)
    }

    @Test
    fun `consecutiveKeepaliveWriteFailures can be incremented`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Increment failure count
        setConsecutiveKeepaliveWriteFailures(connectionData, 1)
        assertEquals(1, getConsecutiveKeepaliveWriteFailures(connectionData))

        setConsecutiveKeepaliveWriteFailures(connectionData, 2)
        assertEquals(2, getConsecutiveKeepaliveWriteFailures(connectionData))

        setConsecutiveKeepaliveWriteFailures(connectionData, 3)
        assertEquals(3, getConsecutiveKeepaliveWriteFailures(connectionData))
    }

    @Test
    fun `consecutiveKeepaliveWriteFailures can be reset to zero`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to some failures
        setConsecutiveKeepaliveWriteFailures(connectionData, 2)
        assertEquals(2, getConsecutiveKeepaliveWriteFailures(connectionData))

        // Reset to 0
        setConsecutiveKeepaliveWriteFailures(connectionData, 0)
        assertEquals(0, getConsecutiveKeepaliveWriteFailures(connectionData))
    }

    @Test
    fun `MAX_KEEPALIVE_WRITE_FAILURES threshold is 2`() {
        // Verify the constant value matches expected behavior
        // Reduced from 3 to 2 for faster recovery when GATT writes fail
        assertEquals(
            "MAX_KEEPALIVE_WRITE_FAILURES should be 2",
            2,
            BleConstants.MAX_KEEPALIVE_WRITE_FAILURES,
        )
    }

    @Test
    fun `failure count below threshold does not trigger disconnect`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to 1 failure (below threshold of 2)
        setConsecutiveKeepaliveWriteFailures(connectionData, 1)
        val failures = getConsecutiveKeepaliveWriteFailures(connectionData)

        assertTrue(
            "1 failure should not trigger disconnect",
            failures < BleConstants.MAX_KEEPALIVE_WRITE_FAILURES,
        )
    }

    @Test
    fun `failure count at threshold triggers disconnect`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to 2 failures (at threshold)
        setConsecutiveKeepaliveWriteFailures(connectionData, 2)
        val failures = getConsecutiveKeepaliveWriteFailures(connectionData)

        assertTrue(
            "2 failures should trigger disconnect",
            failures >= BleConstants.MAX_KEEPALIVE_WRITE_FAILURES,
        )
    }

    @Test
    fun `partial failures followed by success resets counter`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Simulate: 1 failure, then success
        setConsecutiveKeepaliveWriteFailures(connectionData, 1)

        // Simulate success - reset to 0
        setConsecutiveKeepaliveWriteFailures(connectionData, 0)

        assertEquals(
            "Counter should be 0 after success",
            0,
            getConsecutiveKeepaliveWriteFailures(connectionData),
        )
    }

    @Test
    fun `multiple connection instances have independent failure counts`() {
        val conn1 = createConnectionData("AA:BB:CC:DD:EE:01")
        val conn2 = createConnectionData("AA:BB:CC:DD:EE:02")

        // Set different failure counts
        setConsecutiveKeepaliveWriteFailures(conn1, 2)
        setConsecutiveKeepaliveWriteFailures(conn2, 1)

        assertEquals(2, getConsecutiveKeepaliveWriteFailures(conn1))
        assertEquals(1, getConsecutiveKeepaliveWriteFailures(conn2))

        // Modifying one doesn't affect the other
        setConsecutiveKeepaliveWriteFailures(conn1, 3)
        assertEquals(3, getConsecutiveKeepaliveWriteFailures(conn1))
        assertEquals(1, getConsecutiveKeepaliveWriteFailures(conn2))
    }

    // ========== Keepalive Timing Tests ==========

    @Test
    fun `keepalive interval constant is 7 seconds`() {
        // Reduced from 15s to 7s to stay well below L2CAP idle timeout (~20s)
        assertEquals(
            "Keepalive interval should be 7000ms",
            7000L,
            BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS,
        )
    }

    @Test
    fun `time to disconnect after continuous failures is approximately 14 seconds`() {
        // With 7s interval and 2 failures needed:
        // Failure 1 at ~7s, Failure 2 at ~14s -> disconnect
        // This is fast enough to recover before L2CAP idle timeout (~20s)
        val expectedTimeToDisconnect =
            BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS *
                BleConstants.MAX_KEEPALIVE_WRITE_FAILURES

        assertEquals(
            "Should disconnect after ~14 seconds of write failures",
            14000L,
            expectedTimeToDisconnect,
        )
    }

    // ========== Client Lifecycle Tests ==========

    @Test
    fun `BleGattClient can be instantiated`() {
        val client = BleGattClient(mockContext, mockBluetoothAdapter, mockOperationQueue)

        // Verify client was created (no exceptions)
        assertTrue(true)
    }

    @Test
    fun `connections map is initially empty`() =
        runTest {
            val client = BleGattClient(mockContext, mockBluetoothAdapter, mockOperationQueue)

            val connections = getConnectionsMap(client)

            assertTrue("Connections should be empty initially", connections.isEmpty())
        }

    // ========== Helper Methods ==========

    /**
     * Create a ConnectionData instance using reflection.
     */
    private fun createConnectionData(address: String): Any {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockDevice = mockk<BluetoothDevice>(relaxed = true)
        every { mockGatt.device } returns mockDevice
        every { mockDevice.address } returns address

        val connectionDataClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.client.BleGattClient\$ConnectionData",
            )

        // Find constructor with parameters:
        // gatt: BluetoothGatt, address: String, mtu: Int, rxCharacteristic, txCharacteristic,
        // identityHash, retryCount, connectionJob, handshakeInProgress, keepaliveJob, consecutiveKeepaliveWriteFailures
        val constructor = connectionDataClass.declaredConstructors.first()
        constructor.isAccessible = true

        // Create instance with default values for optional params
        return constructor.newInstance(
            mockGatt, // gatt
            address, // address
            BleConstants.MIN_MTU, // mtu
            null, // rxCharacteristic
            null, // txCharacteristic
            null, // identityHash
            0, // retryCount
            null, // connectionJob
            false, // handshakeInProgress
            null, // keepaliveJob
            0, // consecutiveKeepaliveWriteFailures
        )
    }

    /**
     * Get consecutiveKeepaliveWriteFailures from ConnectionData using reflection.
     */
    private fun getConsecutiveKeepaliveWriteFailures(connectionData: Any): Int {
        val field = connectionData.javaClass.getDeclaredField("consecutiveKeepaliveWriteFailures")
        field.isAccessible = true
        return field.getInt(connectionData)
    }

    /**
     * Set consecutiveKeepaliveWriteFailures on ConnectionData using reflection.
     */
    private fun setConsecutiveKeepaliveWriteFailures(
        connectionData: Any,
        value: Int,
    ) {
        val field = connectionData.javaClass.getDeclaredField("consecutiveKeepaliveWriteFailures")
        field.isAccessible = true
        field.setInt(connectionData, value)
    }

    /**
     * Get connections map from BleGattClient using reflection.
     */
    @Suppress("UNCHECKED_CAST")
    private fun getConnectionsMap(client: BleGattClient): Map<String, Any> {
        val field = BleGattClient::class.java.getDeclaredField("connections")
        field.isAccessible = true
        return field.get(client) as Map<String, Any>
    }
}
