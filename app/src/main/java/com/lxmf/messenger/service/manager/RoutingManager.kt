package com.lxmf.messenger.service.manager

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages routing and path table operations for Reticulum.
 *
 * Handles:
 * - Path existence checks
 * - Path requests
 * - Hop count queries
 * - Path table retrieval
 */
class RoutingManager(private val wrapperManager: PythonWrapperManager) {
    companion object {
        private const val TAG = "RoutingManager"
    }

    /**
     * Check if a path to destination exists.
     *
     * @param destHash Destination hash bytes
     * @return true if path exists, false otherwise
     */
    fun hasPath(destHash: ByteArray): Boolean {
        return wrapperManager.withWrapper { wrapper ->
            try {
                wrapper.callAttr("has_path", destHash).toBoolean()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking path", e)
                false
            }
        } ?: run {
            Log.w(TAG, "hasPath called but wrapper is null")
            false
        }
    }

    /**
     * Request a path to destination.
     *
     * @param destHash Destination hash bytes
     * @return JSON string with result
     */
    fun requestPath(destHash: ByteArray): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("request_path", destHash)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting path", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "requestPath called but wrapper is null (service not initialized)")
            JSONObject().apply {
                put("success", false)
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Get hop count to destination.
     *
     * @param destHash Destination hash bytes
     * @return Hop count, or -1 if unknown or error
     */
    fun getHopCount(destHash: ByteArray): Int {
        return wrapperManager.withWrapper { wrapper ->
            try {
                wrapper.callAttr("get_hop_count", destHash).toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting hop count", e)
                -1
            }
        } ?: run {
            Log.w(TAG, "getHopCount called but wrapper is null")
            -1
        }
    }

    /**
     * Get list of destination hashes from RNS path table.
     *
     * @return JSON string containing array of hex-encoded destination hashes
     */
    fun getPathTableHashes(): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("get_path_table")
                // Convert Python list to JSON array
                val hashes = result.asList().map { it.toString() }
                JSONArray(hashes).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting path table hashes", e)
                "[]"
            }
        } ?: run {
            Log.w(TAG, "getPathTableHashes called but wrapper is null")
            "[]"
        }
    }
}
