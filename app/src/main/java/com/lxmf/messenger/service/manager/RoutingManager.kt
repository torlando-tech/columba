package com.lxmf.messenger.service.manager

import android.util.Log
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
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

    /**
     * Probe link speed to a destination by establishing a link.
     *
     * @param destHash Destination hash bytes
     * @param timeoutSeconds How long to wait for link establishment
     * @param deliveryMethod "direct" or "propagated" - affects which link to check/establish
     * @return JSON string with probe result containing status, rates, RTT, hops
     */
    fun probeLinkSpeed(
        destHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("probe_link_speed", destHash, timeoutSeconds.toDouble(), deliveryMethod)
                buildProbeResultJson(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error probing link speed", e)
                JSONObject().apply {
                    put("status", "error")
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "probeLinkSpeed called but wrapper is null")
            JSONObject().apply {
                put("status", "not_initialized")
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Build JSON result from Python probe_link_speed dict response.
     */
    private fun buildProbeResultJson(result: com.chaquo.python.PyObject): String {
        return JSONObject().apply {
            put("status", result.getDictValue("status")?.toString() ?: "error")
            putLongIfPresent(this, result, "establishment_rate_bps")
            putLongIfPresent(this, result, "expected_rate_bps")
            putDoubleIfPresent(this, result, "rtt_seconds")
            putIntIfPresent(this, result, "hops")
            put("link_reused", result.getDictValue("link_reused")?.toBoolean() ?: false)
            putLongIfPresent(this, result, "next_hop_bitrate_bps")
            putStringIfPresent(this, result, "error")
        }.toString()
    }

    /** Helper to extract Long value from Python dict if present and valid. */
    private fun putLongIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            json.put(key, str.toDoubleOrNull()?.toLong() ?: 0L)
        }
    }

    /** Helper to extract Double value from Python dict if present and valid. */
    private fun putDoubleIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            json.put(key, str.toDoubleOrNull() ?: 0.0)
        }
    }

    /** Helper to extract Int value from Python dict if present and valid. */
    private fun putIntIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            json.put(key, str.toIntOrNull() ?: 0)
        }
    }

    /** Helper to extract String value from Python dict if present and valid. */
    private fun putStringIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            json.put(key, str)
        }
    }
}
