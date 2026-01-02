package com.lxmf.messenger.reticulum.ble.client

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BleScanner.removeDevice() functionality.
 *
 * Tests the connection retry mechanism added for failed BLE connections:
 * - removeDevice() removes a device from the cache
 * - Removed devices can be rediscovered on subsequent scans
 * - Removing non-existent devices is a no-op
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScannerRemoveDeviceTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var scanner: BleScanner

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)

        every { mockBluetoothAdapter.isEnabled } returns true
        every { mockBluetoothAdapter.bluetoothLeScanner } returns null // No actual scanning

        scanner = BleScanner(mockContext, mockBluetoothAdapter)
    }

    @After
    fun tearDown() {
        scanner.shutdown()
        clearAllMocks()
    }

    @Test
    fun `removeDevice removes device from discovered devices`() =
        runTest {
            val address = "AA:BB:CC:DD:EE:FF"

            // Add a device to the cache using reflection
            addDeviceToCache(address, "TestDevice")

            // Verify device is in cache
            assertTrue(
                "Device should be in cache before removal",
                scanner.discoveredDevices.value.containsKey(address),
            )

            // Remove the device
            scanner.removeDevice(address)

            // Verify device is no longer in cache
            assertFalse(
                "Device should not be in cache after removal",
                scanner.discoveredDevices.value.containsKey(address),
            )
        }

    @Test
    fun `removeDevice with non-existent address is no-op`() =
        runTest {
            val existingAddress = "AA:BB:CC:DD:EE:FF"
            val nonExistentAddress = "11:22:33:44:55:66"

            // Add a device to the cache
            addDeviceToCache(existingAddress, "TestDevice")

            val sizeBefore = scanner.discoveredDevices.value.size

            // Try to remove non-existent device
            scanner.removeDevice(nonExistentAddress)

            // Verify cache size unchanged
            assertEquals(
                "Cache size should not change when removing non-existent device",
                sizeBefore,
                scanner.discoveredDevices.value.size,
            )

            // Verify existing device still present
            assertTrue(
                "Existing device should still be in cache",
                scanner.discoveredDevices.value.containsKey(existingAddress),
            )
        }

    @Test
    fun `removeDevice updates discoveredDevices StateFlow`() =
        runTest {
            val address = "AA:BB:CC:DD:EE:FF"

            // Add device
            addDeviceToCache(address, "TestDevice")

            // Collect initial state
            val initialDevices = scanner.discoveredDevices.value
            assertEquals(1, initialDevices.size)

            // Remove device
            scanner.removeDevice(address)

            // Collect updated state
            val updatedDevices = scanner.discoveredDevices.value
            assertEquals(0, updatedDevices.size)
        }

    @Test
    fun `removeDevice only removes specified device`() =
        runTest {
            val address1 = "AA:BB:CC:DD:EE:01"
            val address2 = "AA:BB:CC:DD:EE:02"
            val address3 = "AA:BB:CC:DD:EE:03"

            // Add multiple devices
            addDeviceToCache(address1, "Device1")
            addDeviceToCache(address2, "Device2")
            addDeviceToCache(address3, "Device3")

            assertEquals(3, scanner.discoveredDevices.value.size)

            // Remove only address2
            scanner.removeDevice(address2)

            // Verify only address2 was removed
            assertEquals(2, scanner.discoveredDevices.value.size)
            assertTrue(scanner.discoveredDevices.value.containsKey(address1))
            assertFalse(scanner.discoveredDevices.value.containsKey(address2))
            assertTrue(scanner.discoveredDevices.value.containsKey(address3))
        }

    @Test
    fun `removed device can be re-added to cache`() =
        runTest {
            val address = "AA:BB:CC:DD:EE:FF"

            // Add device
            addDeviceToCache(address, "TestDevice")
            assertTrue(scanner.discoveredDevices.value.containsKey(address))

            // Remove device
            scanner.removeDevice(address)
            assertFalse(scanner.discoveredDevices.value.containsKey(address))

            // Re-add device (simulating rediscovery)
            addDeviceToCache(address, "TestDevice-Rediscovered")
            assertTrue(scanner.discoveredDevices.value.containsKey(address))
        }

    // ========== Helper Methods ==========

    /**
     * Add a device to the scanner's internal cache using reflection.
     * This simulates device discovery without actual BLE scanning.
     */
    private suspend fun addDeviceToCache(
        address: String,
        name: String?,
    ) {
        val device =
            com.lxmf.messenger.reticulum.ble.model.BleDevice(
                address = address,
                name = name,
                rssi = -70,
                lastSeen = System.currentTimeMillis(),
            )

        // Access private devices map and mutex
        val devicesField = BleScanner::class.java.getDeclaredField("devices")
        devicesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val devices = devicesField.get(scanner) as MutableMap<String, Any>

        val mutexField = BleScanner::class.java.getDeclaredField("devicesMutex")
        mutexField.isAccessible = true
        val mutex = mutexField.get(scanner) as kotlinx.coroutines.sync.Mutex

        val discoveredDevicesField = BleScanner::class.java.getDeclaredField("_discoveredDevices")
        discoveredDevicesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val discoveredDevicesFlow =
            discoveredDevicesField.get(scanner)
                as kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.lxmf.messenger.reticulum.ble.model.BleDevice>>

        mutex.withLock {
            devices[address] = device
            discoveredDevicesFlow.value = devices.toMap() as Map<String, com.lxmf.messenger.reticulum.ble.model.BleDevice>
        }
    }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLock(action: () -> T): T {
        lock()
        try {
            return action()
        } finally {
            unlock()
        }
    }
}
