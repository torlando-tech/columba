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
class RoutingManager(
    private val wrapperManager: PythonWrapperManager,
) {
    companion object {
        private const val TAG = "RoutingManager"

        /**
         * How long to cache path table results before hitting Python again.
         * The path table changes infrequently relative to how often it's polled (every 30s).
         * Caching prevents 39+ second binder-blocking Python calls from saturating
         * the 1MB binder buffer and cascading into Room DeadObjectException crashes.
         */
        private const val PATH_TABLE_CACHE_TTL_MS = 30_000L
    }

    // Cached path table result and timestamp to avoid repeated slow Python calls
    @Volatile private var cachedPathTableJson: String? = null

    @Volatile private var cachedPathTableTimestamp: Long = 0L

    /**
     * Check if a path to destination exists.
     *
     * @param destHash Destination hash bytes
     * @return true if path exists, false otherwise
     */
    fun hasPath(destHash: ByteArray): Boolean =
        wrapperManager.withWrapper { wrapper ->
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

    /**
     * Request a path to destination.
     *
     * @param destHash Destination hash bytes
     * @return JSON string with result
     */
    fun requestPath(destHash: ByteArray): String =
        wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("request_path", destHash)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting path", e)
                JSONObject()
                    .apply {
                        put("success", false)
                        put("error", e.message)
                    }.toString()
            }
        } ?: run {
            Log.w(TAG, "requestPath called but wrapper is null (service not initialized)")
            JSONObject()
                .apply {
                    put("success", false)
                    put("error", "Service not initialized")
                }.toString()
        }

    /**
     * Persist Reticulum's transport data (path table, destinations) to disk.
     * Called periodically for crash resilience.
     */
    fun persistTransportData() {
        wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("persist_transport_data")
                val success = result.callAttr("get", "success").toBoolean()
                if (!success) {
                    val error = result.callAttr("get", "error").toString()
                    Log.w(TAG, "persistTransportData failed: $error")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting transport data", e)
            }
        } ?: Log.w(TAG, "persistTransportData called but wrapper is null")
    }

    /**
     * Get hop count to destination.
     *
     * @param destHash Destination hash bytes
     * @return Hop count, or -1 if unknown or error
     */
    fun getHopCount(destHash: ByteArray): Int =
        wrapperManager.withWrapper { wrapper ->
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

    /**
     * Get list of destination hashes from RNS path table.
     *
     * Returns a cached result if available and fresh (within [PATH_TABLE_CACHE_TTL_MS]).
     * This prevents the Python call (which can take 39+ seconds on large path tables)
     * from blocking a binder thread and saturating the 1MB binder buffer.
     *
     * @return JSON string containing array of hex-encoded destination hashes
     */
    @Synchronized
    fun getPathTableHashes(): String {
        val now = System.currentTimeMillis()
        val cached = cachedPathTableJson
        if (cached != null && (now - cachedPathTableTimestamp) < PATH_TABLE_CACHE_TTL_MS) {
            return cached
        }

        val result =
            wrapperManager.withWrapper { wrapper ->
                try {
                    val pyResult = wrapper.callAttr("get_path_table")
                    val hashes = pyResult.asList().map { it.toString() }
                    JSONArray(hashes).toString()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting path table hashes", e)
                    null
                }
            }

        if (result != null) {
            cachedPathTableJson = result
            cachedPathTableTimestamp = System.currentTimeMillis()
            return result
        }
        // Return stale cache on error if available, otherwise empty
        return cached ?: "[]"
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
    ): String =
        wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("probe_link_speed", destHash, timeoutSeconds.toDouble(), deliveryMethod)
                buildProbeResultJson(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error probing link speed", e)
                JSONObject()
                    .apply {
                        put("status", "error")
                        put("error", e.message)
                    }.toString()
            }
        } ?: run {
            Log.w(TAG, "probeLinkSpeed called but wrapper is null")
            JSONObject()
                .apply {
                    put("status", "not_initialized")
                    put("error", "Service not initialized")
                }.toString()
        }

    /**
     * Build JSON result from Python probe_link_speed dict response.
     */
    private fun buildProbeResultJson(result: com.chaquo.python.PyObject): String =
        JSONObject()
            .apply {
                put("status", result.getDictValue("status")?.toString() ?: "error")
                putLongIfPresent(this, result, "establishment_rate_bps")
                putLongIfPresent(this, result, "expected_rate_bps")
                putDoubleIfPresent(this, result, "rtt_seconds")
                putIntIfPresent(this, result, "hops")
                put("link_reused", result.getDictValue("link_reused")?.toBoolean() ?: false)
                putLongIfPresent(this, result, "next_hop_bitrate_bps")
                putStringIfPresent(this, result, "error")
            }.toString()

    /** Helper to extract Long value from Python dict if present and valid. */
    private fun putLongIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            str.toDoubleOrNull()?.toLong()?.let { json.put(key, it) }
        }
    }

    /** Helper to extract Double value from Python dict if present and valid. */
    private fun putDoubleIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            str.toDoubleOrNull()?.let { json.put(key, it) }
        }
    }

    /** Helper to extract Int value from Python dict if present and valid. */
    private fun putIntIfPresent(
        json: JSONObject,
        result: com.chaquo.python.PyObject,
        key: String,
    ) {
        result.getDictValue(key)?.toString()?.takeIf { it != "None" && it.isNotEmpty() }?.let { str ->
            str.toIntOrNull()?.let { json.put(key, it) }
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
