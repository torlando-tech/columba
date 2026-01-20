package com.lxmf.messenger.data.model

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DeviceClassifierTest {

    private lateinit var mockCache: DeviceTypeCache
    private lateinit var classifier: DeviceClassifier
    private lateinit var mockDevice: BluetoothDevice

    @Before
    fun setup() {
        mockCache = mock(DeviceTypeCache::class.java)
        classifier = DeviceClassifier(mockCache)
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
        // Should not check cache when found in BLE scan
        verify(mockCache, never()).getCachedType(address)
    }

    @Test
    fun `classifyDevice returns Cached when device not in BLE scan but in cache`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        `when`(mockCache.getCachedType(address)).thenReturn(BluetoothType.CLASSIC)

        val bleDeviceAddresses = emptySet<String>()
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.Cached)
        assertEquals(BluetoothType.CLASSIC, (result as DeviceClassifier.ClassificationResult.Cached).type)
        verify(mockCache).getCachedType(address)
    }

    @Test
    fun `classifyDevice returns Unknown when device not in BLE scan and not in cache`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        `when`(mockCache.getCachedType(address)).thenReturn(null)

        val bleDeviceAddresses = emptySet<String>()
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        assertTrue(result is DeviceClassifier.ClassificationResult.Unknown)
        verify(mockCache).getCachedType(address)
    }

    @Test
    fun `classifyDevice handles BLE cache entry correctly`() {
        val address = "AA:BB:CC:DD:EE:FF"
        `when`(mockDevice.address).thenReturn(address)
        `when`(mockCache.getCachedType(address)).thenReturn(BluetoothType.BLE)

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
        `when`(mockCache.getCachedType(address)).thenReturn(BluetoothType.CLASSIC)

        val bleDeviceAddresses = setOf(address)
        val result = classifier.classifyDevice(mockDevice, bleDeviceAddresses)

        // Should return ConfirmedBle, not Cached
        assertTrue(result is DeviceClassifier.ClassificationResult.ConfirmedBle)
        verify(mockCache, never()).getCachedType(address)
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
        verify(mockCache).cacheType(address, BluetoothType.BLE)
    }

    @Test
    fun `cacheDeviceType caches CLASSIC type`() {
        val address = "AA:BB:CC:DD:EE:FF"
        classifier.cacheDeviceType(address, BluetoothType.CLASSIC)
        verify(mockCache).cacheType(address, BluetoothType.CLASSIC)
    }

    @Test
    fun `cacheDeviceType does not cache UNKNOWN type`() {
        val address = "AA:BB:CC:DD:EE:FF"
        classifier.cacheDeviceType(address, BluetoothType.UNKNOWN)
        verify(mockCache, never()).cacheType(address, BluetoothType.UNKNOWN)
    }

    @Test
    fun `cacheDeviceType handles multiple addresses independently`() {
        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        classifier.cacheDeviceType(address1, BluetoothType.BLE)
        classifier.cacheDeviceType(address2, BluetoothType.CLASSIC)

        verify(mockCache).cacheType(address1, BluetoothType.BLE)
        verify(mockCache).cacheType(address2, BluetoothType.CLASSIC)
    }
}
