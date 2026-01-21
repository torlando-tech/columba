package com.lxmf.messenger.data.model

import android.bluetooth.BluetoothDevice

/**
 * Classifies Bluetooth devices as BLE or Classic based on scan results and cached data.
 * Extracted from RNodeWizardViewModel for testability.
 *
 * @param deviceTypeCache Cache for storing and retrieving device type classifications
 */
class DeviceClassifier(
    private val deviceTypeCache: DeviceTypeCache,
) {
    /**
     * Result of device classification.
     */
    sealed class ClassificationResult {
        /**
         * Device was found in BLE scan, confirmed as BLE.
         */
        object ConfirmedBle : ClassificationResult()

        /**
         * Device has a cached type from previous scans.
         * @param type The cached Bluetooth type
         */
        data class Cached(val type: BluetoothType) : ClassificationResult()

        /**
         * Device type is unknown (not in BLE scan, no cache entry).
         */
        object Unknown : ClassificationResult()
    }

    /**
     * Classifies a bonded Bluetooth device based on BLE scan results and cache.
     *
     * Classification rules:
     * 1. If device address is in bleDeviceAddresses, it's confirmed BLE
     * 2. Otherwise, check the cache for previous classification
     * 3. If no cache entry exists, mark as UNKNOWN
     *
     * @param device The Bluetooth device to classify
     * @param bleDeviceAddresses Set of addresses found in the most recent BLE scan
     * @return ClassificationResult indicating how the device was classified
     */
    fun classifyDevice(
        device: BluetoothDevice,
        bleDeviceAddresses: Set<String>,
    ): ClassificationResult {
        val address = device.address

        return when {
            bleDeviceAddresses.contains(address) -> ClassificationResult.ConfirmedBle
            else -> {
                val cachedType = deviceTypeCache.getCachedType(address)
                if (cachedType != null) {
                    ClassificationResult.Cached(cachedType)
                } else {
                    ClassificationResult.Unknown
                }
            }
        }
    }

    /**
     * Determines if a Bluetooth device should be included in RNode discovery.
     *
     * Inclusion criteria:
     * - Device name must start with "RNode " (case-insensitive)
     *
     * @param device The Bluetooth device to check
     * @return true if the device should be included in discovery results
     */
    fun shouldIncludeInDiscovery(device: BluetoothDevice): Boolean {
        val name = device.name
        return name?.startsWith("RNode ", ignoreCase = true) == true
    }

    /**
     * Caches a device type classification for future use.
     * Does not cache UNKNOWN types.
     *
     * @param address The device address
     * @param type The Bluetooth type to cache
     */
    fun cacheDeviceType(
        address: String,
        type: BluetoothType,
    ) {
        if (type != BluetoothType.UNKNOWN) {
            deviceTypeCache.cacheType(address, type)
        }
    }
}

/**
 * Interface for caching and retrieving Bluetooth device type classifications.
 * Allows the DeviceClassifier to be tested without SharedPreferences.
 */
interface DeviceTypeCache {
    /**
     * Retrieves the cached Bluetooth type for a device address.
     *
     * @param address The device MAC address
     * @return The cached BluetoothType, or null if not cached
     */
    fun getCachedType(address: String): BluetoothType?

    /**
     * Caches a Bluetooth type for a device address.
     *
     * @param address The device MAC address
     * @param type The Bluetooth type to cache
     */
    fun cacheType(
        address: String,
        type: BluetoothType,
    )
}
