package com.lxmf.messenger.reticulum.ble.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a discovered BLE device running Reticulum.
 *
 * @property address MAC address of the BLE device (e.g., "AA:BB:CC:DD:EE:FF")
 * @property name Device name advertised via BLE (may be null)
 * @property rssi Signal strength in dBm (-100 to 0, closer to 0 is stronger)
 * @property identityHash 32-character hex string representing the 16-byte Reticulum Transport identity hash.
 *                        Always null during discovery. Only set after GATT connection when Identity
 *                        characteristic (Protocol v2.2) is read. NOT parsed from device name.
 * @property firstSeen Timestamp when device was first discovered
 * @property lastSeen Timestamp when device was last seen
 * @property connectionAttempts Number of times we've tried to connect
 * @property successfulConnections Number of successful connections
 * @property failedConnections Number of failed connection attempts
 */
@Parcelize
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>? = null,
    val identityHash: String? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var connectionAttempts: Int = 0,
    var successfulConnections: Int = 0,
    var failedConnections: Int = 0,
) : Parcelable {
    /**
     * Calculate connection success rate (0.0 to 1.0).
     */
    fun getSuccessRate(): Double {
        return if (connectionAttempts > 0) {
            successfulConnections.toDouble() / connectionAttempts
        } else {
            0.0
        }
    }

    /**
     * Calculate priority score for connection selection.
     *
     * Scoring algorithm from ble-reticulum Python implementation:
     * - RSSI: 60% weight (0-70 points)
     * - Connection history: 30% weight (0-50 points)
     * - Recency: 10% weight (0-25 points)
     *
     * @param minRssi Minimum RSSI threshold (typically -85 dBm)
     * @return Score from 0-145, higher is better
     */
    fun calculatePriorityScore(minRssi: Int = -85): Double {
        // RSSI component (60% weight, 0-70 points)
        val rssiNormalized = (rssi - minRssi).toDouble() / (0 - minRssi)
        val rssiScore = rssiNormalized * 70

        // Connection success rate (30% weight, 0-50 points)
        val historyScore = getSuccessRate() * 50

        // Recency bonus (10% weight, 0-25 points)
        val ageSeconds = (System.currentTimeMillis() - lastSeen) / 1000.0
        val recencyScore = maxOf(0.0, 25 - (ageSeconds / 10))

        return rssiScore + historyScore + recencyScore
    }

    /**
     * Update last seen timestamp and RSSI.
     */
    fun updateDiscovery(newRssi: Int): BleDevice {
        return copy(
            rssi = newRssi,
            lastSeen = System.currentTimeMillis(),
        )
    }

    /**
     * Record a connection attempt.
     */
    fun recordConnectionAttempt(): BleDevice {
        return copy(connectionAttempts = connectionAttempts + 1)
    }

    /**
     * Record a successful connection.
     */
    fun recordConnectionSuccess(): BleDevice {
        return copy(successfulConnections = successfulConnections + 1)
    }

    /**
     * Record a failed connection.
     */
    fun recordConnectionFailure(): BleDevice {
        return copy(failedConnections = failedConnections + 1)
    }
}
