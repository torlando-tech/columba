
package com.lxmf.messenger.data.model

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Fake implementation of DeviceTypeCache for testing.
 * Uses a simple HashMap to store cached types.
 */
private class FakeDeviceTypeCache : DeviceTypeCache {
    private val cache = mutableMapOf<String, BluetoothType>()

    override fun getCachedType(address: String): BluetoothType? = cache[address]

    override fun cacheType(
        address: String,
        type: BluetoothType,
    ) {
        cache[address] = type
    }

    fun clear() = cache.clear()
}

class DeviceClassifierTest {
    private lateinit var fakeCache: FakeDeviceTypeCache
    private lateinit var classifier: DeviceClassifier
    private lateinit var mockDevice: BluetoothDevice

    @Before
    fun setup() {
        fakeCache = FakeDeviceTypeCache()
        classifier = DeviceClassifier(fakeCache)
        mockDevice = mock(BluetoothDevice::class.java)
    }

    // ==================== classifyDevice Tests ====================

    @Test
    fun `classifyDevice returns ConfirmedBle when device found in BLE scan`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)

        val bleDeviceAddresses = setOf(address)
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.ConfirmedBle)
    }

    @Test
    fun `classifyDevice returns Cached when device not in BLE scan but in cache`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        // Pre-populate the cache
        fakeCache.cacheType(address, BluetoothType.CLASSIC)

        val bleDeviceAddresses = emptySet<String>()
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.Cached)
        assertEquals(BluetoothType.CLASSIC, (result as DeviceClassifier.ClassificationResult.Cached).type)
    }

    @Test
    fun `classifyDevice returns Unknown when device not in BLE scan and not in cache`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        // Cache is empty by default

        val bleDeviceAddresses = emptySet<String>()
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.Unknown)
    }

    @Test
    fun `classifyDevice handles BLE cache entry correctly`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        // Pre-populate the cache with BLE type
        fakeCache.cacheType(address, BluetoothType.BLE)

        val bleDeviceAddresses = emptySet<String>()
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.Cached)
        assertEquals(BluetoothType.BLE, (result as DeviceClassifier.ClassificationResult.Cached).type)
    }

    @Test
    fun `classifyDevice prefers BLE scan over cache`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        // Device has CLASSIC cached, but found in BLE scan
        fakeCache.cacheType(address, BluetoothType.CLASSIC)

        val bleDeviceAddresses = setOf(address)
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        // Should return ConfirmedBle, not Cached
        assertTrue(result is DeviceClassifier.ClassificationResult.ConfirmedBle)
    }

    @Test
    fun `classifyDevice handles multiple devices in BLE scan`() {
        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"
        val bleDeviceAddresses = setOf(address1, address2)

        // Test device 1
        `when`(mockDevice.address).thenReturn(address1)
        val result1 = classifier.classifyDevice(mockDevice, bleDeviceAddresses)
        assertTrue(result1 is DeviceClassifier.ClassificationResult.ConfirmedBle)

        // Test device 2
        `when`(mockDevice.address).thenReturn(address2)
        val result2 = classifier.classifyDevice(mockDevice, bleDeviceAddresses)
        assertTrue(result2 is DeviceClassifier.ClassificationResult.ConfirmedBle)
    }

    // ==================== shouldIncludeInDiscovery Tests ====================

    @Test
    fun `shouldIncludeInDiscovery returns true for RNode device`() {
        `when`(mockDevice.name).thenReturn("RNode T3")
        assertTrue(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns true for rnode lowercase`() {
        `when`(mockDevice.name).thenReturn("rnode device")
        assertTrue(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns true for RNODE uppercase`() {
        `when`(mockDevice.name).thenReturn("RNODE DEVICE")
        assertTrue(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns true for mixed case RNode`() {
        `when`(mockDevice.name).thenReturn("RnOdE Test")
        assertTrue(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns false for non-RNode device`() {
        `when`(mockDevice.name).thenReturn("HC-05")
        assertFalse(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns false for null device name`() {
        `when`(mockDevice.name).thenReturn(null)
        assertFalse(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns false for empty device name`() {
        `when`(mockDevice.name).thenReturn("")
        assertFalse(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery returns false for device containing RNode but not starting with it`() {
        `when`(mockDevice.name).thenReturn("My RNode Device")
        assertFalse(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery requires space after RNode`() {
        `when`(mockDevice.name).thenReturn("RNodeDevice")
        assertFalse(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    @Test
    fun `shouldIncludeInDiscovery accepts exactly RNode with space`() {
        `when`(mockDevice.name).thenReturn("RNode ")
        assertTrue(classifier.shouldIncludeInDiscovery(mockDevice))
    }

    // ==================== cacheDeviceType Tests ====================

    @Test
    fun `cacheDeviceType caches BLE type`() {
        val address = "AA:BB:CC:DD:EE:FF"

        classifier.cacheDeviceType(address, BluetoothType.BLE)

        assertEquals(BluetoothType.BLE, fakeCache.getCachedType(address))
    }

    @Test
    fun `cacheDeviceType caches CLASSIC type`() {
        val address = "AA:BB:CC:DD:EE:FF"

        classifier.cacheDeviceType(address, BluetoothType.CLASSIC)

        assertEquals(BluetoothType.CLASSIC, fakeCache.getCachedType(address))
    }

    @Test
    fun `cacheDeviceType does not cache UNKNOWN type`() {
        val address = "AA:BB:CC:DD:EE:FF"

        classifier.cacheDeviceType(address, BluetoothType.UNKNOWN)

        assertNull(fakeCache.getCachedType(address))
    }

    @Test
    fun `cacheDeviceType handles multiple addresses independently`() {
        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        classifier.cacheDeviceType(address1, BluetoothType.BLE)
        classifier.cacheDeviceType(address2, BluetoothType.CLASSIC)

        assertEquals(BluetoothType.BLE, fakeCache.getCachedType(address1))
        assertEquals(BluetoothType.CLASSIC, fakeCache.getCachedType(address2))
    }
}
