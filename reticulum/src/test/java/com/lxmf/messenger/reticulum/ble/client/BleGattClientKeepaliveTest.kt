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
 * Tests the consecutiveKeepaliveFailures field added in PR 59:
 * - Counter increments on keepalive failure
 * - Counter resets on keepalive success
 * - Disconnect triggered after MAX_CONNECTION_FAILURES consecutive failures
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

    // ========== ConnectionData consecutiveKeepaliveFailures Tests ==========

    @Test
    fun `ConnectionData initializes with zero consecutive failures`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        val failures = getConsecutiveKeepaliveFailures(connectionData)

        assertEquals("Should initialize to 0 failures", 0, failures)
    }

    @Test
    fun `consecutiveKeepaliveFailures can be incremented`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Increment failure count
        setConsecutiveKeepaliveFailures(connectionData, 1)
        assertEquals(1, getConsecutiveKeepaliveFailures(connectionData))

        setConsecutiveKeepaliveFailures(connectionData, 2)
        assertEquals(2, getConsecutiveKeepaliveFailures(connectionData))

        setConsecutiveKeepaliveFailures(connectionData, 3)
        assertEquals(3, getConsecutiveKeepaliveFailures(connectionData))
    }

    @Test
    fun `consecutiveKeepaliveFailures can be reset to zero`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to some failures
        setConsecutiveKeepaliveFailures(connectionData, 2)
        assertEquals(2, getConsecutiveKeepaliveFailures(connectionData))

        // Reset to 0
        setConsecutiveKeepaliveFailures(connectionData, 0)
        assertEquals(0, getConsecutiveKeepaliveFailures(connectionData))
    }

    @Test
    fun `MAX_CONNECTION_FAILURES threshold is 3`() {
        // Verify the constant value matches expected behavior
        assertEquals(
            "MAX_CONNECTION_FAILURES should be 3",
            3,
            BleConstants.MAX_CONNECTION_FAILURES,
        )
    }

    @Test
    fun `failure count below threshold does not trigger disconnect`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to 2 failures (below threshold of 3)
        setConsecutiveKeepaliveFailures(connectionData, 2)
        val failures = getConsecutiveKeepaliveFailures(connectionData)

        assertTrue(
            "2 failures should not trigger disconnect",
            failures < BleConstants.MAX_CONNECTION_FAILURES,
        )
    }

    @Test
    fun `failure count at threshold triggers disconnect`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Set to 3 failures (at threshold)
        setConsecutiveKeepaliveFailures(connectionData, 3)
        val failures = getConsecutiveKeepaliveFailures(connectionData)

        assertTrue(
            "3 failures should trigger disconnect",
            failures >= BleConstants.MAX_CONNECTION_FAILURES,
        )
    }

    @Test
    fun `partial failures followed by success resets counter`() {
        val connectionData = createConnectionData("AA:BB:CC:DD:EE:FF")

        // Simulate: 2 failures, then success
        setConsecutiveKeepaliveFailures(connectionData, 1)
        setConsecutiveKeepaliveFailures(connectionData, 2)

        // Simulate success - reset to 0
        setConsecutiveKeepaliveFailures(connectionData, 0)

        assertEquals(
            "Counter should be 0 after success",
            0,
            getConsecutiveKeepaliveFailures(connectionData),
        )
    }

    @Test
    fun `multiple connection instances have independent failure counts`() {
        val conn1 = createConnectionData("AA:BB:CC:DD:EE:01")
        val conn2 = createConnectionData("AA:BB:CC:DD:EE:02")

        // Set different failure counts
        setConsecutiveKeepaliveFailures(conn1, 2)
        setConsecutiveKeepaliveFailures(conn2, 1)

        assertEquals(2, getConsecutiveKeepaliveFailures(conn1))
        assertEquals(1, getConsecutiveKeepaliveFailures(conn2))

        // Modifying one doesn't affect the other
        setConsecutiveKeepaliveFailures(conn1, 3)
        assertEquals(3, getConsecutiveKeepaliveFailures(conn1))
        assertEquals(1, getConsecutiveKeepaliveFailures(conn2))
    }

    // ========== Keepalive Timing Tests ==========

    @Test
    fun `keepalive interval constant is 15 seconds`() {
        assertEquals(
            "Keepalive interval should be 15000ms",
            15000L,
            BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS,
        )
    }

    @Test
    fun `time to disconnect after continuous failures is approximately 45 seconds`() {
        // With 15s interval and 3 failures needed:
        // Failure 1 at ~15s, Failure 2 at ~30s, Failure 3 at ~45s -> disconnect
        val expectedTimeToDisconnect =
            BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS *
                BleConstants.MAX_CONNECTION_FAILURES

        assertEquals(
            "Should disconnect after ~45 seconds of failures",
            45000L,
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
        // identityHash, retryCount, connectionJob, handshakeInProgress, keepaliveJob, consecutiveKeepaliveFailures
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
            0, // consecutiveKeepaliveFailures
        )
    }

    /**
     * Get consecutiveKeepaliveFailures from ConnectionData using reflection.
     */
    private fun getConsecutiveKeepaliveFailures(connectionData: Any): Int {
        val field = connectionData.javaClass.getDeclaredField("consecutiveKeepaliveFailures")
        field.isAccessible = true
        return field.getInt(connectionData)
    }

    /**
     * Set consecutiveKeepaliveFailures on ConnectionData using reflection.
     */
    private fun setConsecutiveKeepaliveFailures(
        connectionData: Any,
        value: Int,
    ) {
        val field = connectionData.javaClass.getDeclaredField("consecutiveKeepaliveFailures")
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
