package com.lxmf.messenger.service.manager

import android.content.Context
import android.util.Log
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates BLE bridge operations for the Reticulum service.
 *
 * This is a thin wrapper around KotlinBLEBridge singleton that provides
 * a clean interface for service operations.
 */
class BleCoordinator(private val context: Context) {
    companion object {
        private const val TAG = "BleCoordinator"
    }

    /**
     * Get the BLE bridge instance.
     */
    fun getBridge(): KotlinBLEBridge = KotlinBLEBridge.getInstance(context)

    /**
     * Start the BLE bridge with custom UUIDs (for testing).
     *
     * @param serviceUuid Custom service UUID
     * @param rxCharUuid Custom RX characteristic UUID
     * @param txCharUuid Custom TX characteristic UUID
     * @param identityCharUuid Custom identity characteristic UUID
     */
    suspend fun start(
        serviceUuid: String,
        rxCharUuid: String,
        txCharUuid: String,
        identityCharUuid: String,
    ) {
        Log.d(TAG, "Starting BLE bridge with custom UUIDs")
        getBridge().start(serviceUuid, rxCharUuid, txCharUuid, identityCharUuid)
    }

    /**
     * Restart the BLE bridge (e.g., after permissions granted).
     */
    suspend fun restart() {
        Log.d(TAG, "Restarting BLE bridge")
        try {
            getBridge().restart()
            Log.d(TAG, "BLE restart complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting BLE bridge", e)
            throw e
        }
    }

    /**
     * Get BLE connection details for all currently connected peers.
     *
     * @return JSON string containing array of connection details
     */
    fun getConnectionDetailsJson(): String {
        return try {
            Log.d(TAG, "Getting BLE connection details")
            val details = getBridge().getConnectionDetailsSync()
            Log.d(TAG, "Found ${details.size} BLE connections")

            val jsonArray = JSONArray()
            details.forEach { detail ->
                val jsonObj =
                    JSONObject().apply {
                        put("identityHash", detail.identityHash)
                        put("peerName", detail.peerName)
                        put("currentMac", detail.currentMac)
                        put("hasCentralConnection", detail.hasCentralConnection)
                        put("hasPeripheralConnection", detail.hasPeripheralConnection)
                        put("mtu", detail.mtu)
                        put("connectedAt", detail.connectedAt)
                        put("firstSeen", detail.firstSeen)
                        put("lastSeen", detail.lastSeen)
                        put("rssi", detail.rssi)
                    }
                jsonArray.put(jsonObj)
            }

            jsonArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BLE connection details", e)
            "[]" // Return empty array on error
        }
    }
}
