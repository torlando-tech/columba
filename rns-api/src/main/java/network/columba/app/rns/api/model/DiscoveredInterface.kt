package network.columba.app.rns.api.model

/**
 * Information about an interface discovered via RNS 1.1.x interface discovery.
 *
 * RNS 1.1.0+ interface discovery provides detailed information about interfaces
 * announced by other nodes on the network, including TCP servers, RNodes, and
 * other interface types.
 */
data class DiscoveredInterface(
    // Core identification
    val name: String,
    // "TCPServerInterface", "RNodeInterface", etc.
    val type: String,
    // Transport identity hash.
    val transportId: String?,
    // Network identity hash.
    val networkId: String?,
    // Status information
    // "available", "unknown", "stale"
    val status: String,
    // 1000 = available, 100 = unknown, 0 = stale
    val statusCode: Int,
    // Unix timestamp in seconds.
    val lastHeard: Long,
    // Number of times this interface has been discovered.
    val heardCount: Int,
    // Distance in hops from discovery source.
    val hops: Int,
    // Proof-of-work stamp value.
    val stampValue: Int,
    // TCP-specific fields
    // Host/IP for TCP interfaces or b32 address for I2P.
    val reachableOn: String?,
    val port: Int?,
    // Radio-specific fields (RNode, Weave, KISS)
    // Frequency in Hz.
    val frequency: Long?,
    // Bandwidth in Hz.
    val bandwidth: Int?,
    // LoRa spreading factor (5-12).
    val spreadingFactor: Int?,
    // LoRa coding rate (5-8).
    val codingRate: Int?,
    // Modulation type.
    val modulation: String?,
    // Channel number (for Weave).
    val channel: Int?,
    // Location (optional, for interfaces that share location)
    val latitude: Double?,
    val longitude: Double?,
    // Altitude in meters.
    val height: Double?,
    // IFAC (Interface Access Code) — when the remote interface publishes its IFAC
    // identity, peers adding this interface locally must match network_name and
    // passphrase or the IFAC handshake fails and no packets get through.
    val ifacNetname: String? = null,
    val ifacNetkey: String? = null,
    // Additional raw announce fields exposed for the "all fields" card view.
    /** Whether the remote interface is a transport (routing) node. */
    val transport: Boolean = false,
    /** Unique identifier for this announce (hex SHA256 of transportId + name). */
    val discoveryHash: String? = null,
    /** When the remote generated the announce (unix seconds). */
    val receivedAt: Long = 0L,
    /** When we first discovered this interface locally (unix seconds). */
    val discoveredAt: Long = 0L,
) {
    /**
     * Returns true if this is a TCP-based interface.
     * BackboneInterface is the RNS 1.1.x upgraded TCP connection type.
     */
    val isTcpInterface: Boolean
        get() = type in listOf("TCPServerInterface", "TCPClientInterface", "BackboneInterface")

    /**
     * Returns true if this is a radio-based interface.
     */
    val isRadioInterface: Boolean
        get() = type in listOf("RNodeInterface", "WeaveInterface", "KISSInterface")

    /**
     * Returns true if location information is available.
     */
    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    companion object {
        // Status codes matching RNS
        const val STATUS_AVAILABLE = 1000
        const val STATUS_UNKNOWN = 100
        const val STATUS_STALE = 0

        /**
         * Parse a JSON array string into a list of DiscoveredInterface objects.
         */
        fun parseFromJson(jsonString: String): List<DiscoveredInterface> {
            val jsonArray = org.json.JSONArray(jsonString)
            return (0 until jsonArray.length()).map { i ->
                parseItem(jsonArray.getJSONObject(i))
            }
        }

        private fun parseItem(item: org.json.JSONObject): DiscoveredInterface =
            DiscoveredInterface(
                // Core identification
                name = item.optString("name", "Unknown"),
                type = item.optString("type", "Unknown"),
                transportId = item.optString("transport_id", "").ifEmpty { null },
                networkId = item.optString("network_id", "").ifEmpty { null },
                // Status information
                status = item.optString("status", "unknown"),
                statusCode = item.optInt("status_code", STATUS_UNKNOWN),
                lastHeard = item.optLong("last_heard", 0),
                heardCount = item.optInt("heard_count", 0),
                hops = item.optInt("hops", 0),
                stampValue = item.optInt("stamp_value", 0),
                // TCP-specific
                reachableOn = item.optString("reachable_on", "").ifEmpty { null },
                port = item.optIntOrNull("port"),
                // Radio-specific
                frequency = item.optLongOrNull("frequency"),
                bandwidth = item.optIntOrNull("bandwidth"),
                spreadingFactor = item.optIntOrNull("spreading_factor"),
                codingRate = item.optIntOrNull("coding_rate"),
                modulation = item.optString("modulation", "").ifEmpty { null },
                channel = item.optIntOrNull("channel"),
                // Location
                latitude = item.optDoubleOrNull("latitude"),
                longitude = item.optDoubleOrNull("longitude"),
                height = item.optDoubleOrNull("height"),
                // IFAC
                ifacNetname = item.optString("ifac_netname", "").ifEmpty { null },
                ifacNetkey = item.optString("ifac_netkey", "").ifEmpty { null },
                // Additional raw announce fields
                transport = item.optBoolean("transport", false),
                discoveryHash = item.optString("discovery_hash", "").ifEmpty { null },
                receivedAt = item.optLong("received", 0L),
                discoveredAt = item.optLong("discovered", 0L),
            )

        // JSON extension helpers for nullable values
        private fun org.json.JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

        private fun org.json.JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

        private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    }
}
