package com.lxmf.messenger.data.model

import kotlin.math.roundToInt

/**
 * Bandwidth estimates for different Reticulum interface types.
 * Used for transfer time calculation and auto-preset selection.
 *
 * All bandwidth values are in bits per second (bps).
 */
object InterfaceBandwidth {
    // LoRa (RNode) bandwidth estimates

    /** Worst case: SF12, 125kHz - very slow but long range */
    const val RNODE_SF12_125KHZ_BPS = 50

    /** Best case: SF7, 500kHz - faster but shorter range */
    const val RNODE_SF7_500KHZ_BPS = 5_000

    /** Default estimate for RNode when SF/BW unknown */
    const val RNODE_DEFAULT_BPS = 1_000

    // BLE bandwidth estimate

    /** AndroidBLE: ~1.8 KB/s sustained throughput with ATT overhead */
    const val ANDROID_BLE_BPS = 14_400

    // High-speed interface estimates (conservative)

    /** TCP Client: 1 Mbps minimum (conservative estimate) */
    const val TCP_CLIENT_BPS = 1_000_000

    /** Auto Interface (LAN multicast): ~10 Mbps */
    const val AUTO_INTERFACE_BPS = 10_000_000

    /** UDP Interface: ~10 Mbps */
    const val UDP_INTERFACE_BPS = 10_000_000

    /**
     * Calculate approximate RNode bandwidth based on LoRa parameters.
     *
     * Based on LoRa symbol rate formula: BW / (2^SF) * CR
     *
     * @param spreadingFactor LoRa spreading factor (7-12)
     * @param bandwidthHz LoRa bandwidth in Hz (e.g., 125000, 250000, 500000)
     * @param codingRate Coding rate numerator (5-8, default 5 for 4/5)
     * @return Estimated throughput in bits per second
     */
    fun calculateRNodeBandwidth(
        spreadingFactor: Int,
        bandwidthHz: Int,
        codingRate: Int = 5,
    ): Int {
        // Symbol rate = BW / (2^SF)
        val symbolRate = bandwidthHz.toDouble() / (1 shl spreadingFactor)
        // Bits per symbol = SF * CR/(CR+4)
        // For CR=5 (4/5): 4/5 = 0.8
        val codingRateRatio = (codingRate - 4).toDouble() / codingRate
        val bitsPerSymbol = spreadingFactor * codingRateRatio
        // Raw bit rate
        val rawBitRate = symbolRate * bitsPerSymbol
        // Account for Reticulum overhead (~50% effective throughput)
        return (rawBitRate * 0.5).roundToInt().coerceAtLeast(RNODE_SF12_125KHZ_BPS)
    }

    /**
     * Get bandwidth estimate for an interface type name.
     *
     * @param interfaceTypeName The type name from InterfaceConfig (e.g., "RNodeInterface", "TCPClient")
     * @return Estimated bandwidth in bps, defaulting to TCP_CLIENT_BPS for unknown types
     */
    fun getBandwidthForInterfaceType(interfaceTypeName: String): Int =
        when {
            interfaceTypeName.contains("RNode", ignoreCase = true) -> RNODE_DEFAULT_BPS
            interfaceTypeName.contains("BLE", ignoreCase = true) -> ANDROID_BLE_BPS
            interfaceTypeName.contains("TCP", ignoreCase = true) -> TCP_CLIENT_BPS
            interfaceTypeName.contains("Auto", ignoreCase = true) -> AUTO_INTERFACE_BPS
            interfaceTypeName.contains("UDP", ignoreCase = true) -> UDP_INTERFACE_BPS
            else -> TCP_CLIENT_BPS // Conservative default
        }

    /**
     * Determine if an interface type is considered "slow" (LoRa/BLE).
     */
    fun isSlowInterface(interfaceTypeName: String): Boolean =
        interfaceTypeName.contains("RNode", ignoreCase = true) ||
            interfaceTypeName.contains("BLE", ignoreCase = true)
}
