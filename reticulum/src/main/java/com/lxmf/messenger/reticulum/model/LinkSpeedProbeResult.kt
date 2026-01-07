package com.lxmf.messenger.reticulum.model

/**
 * Result of a link speed probe operation.
 *
 * Contains metrics measured during link establishment that can be used
 * to estimate transfer speeds across the entire path to a destination.
 *
 * @property status The probe status: "success", "no_path", "no_identity", "timeout", or "error"
 * @property establishmentRateBps Bits per second measured during link handshake
 * @property expectedRateBps Bits per second from actual transfers (if link was reused)
 * @property rttSeconds Round-trip time in seconds
 * @property hops Number of hops to destination
 * @property linkReused True if an existing active link was used instead of establishing a new one
 * @property error Error message if status is "error"
 */
data class LinkSpeedProbeResult(
    val status: String,
    val establishmentRateBps: Long?,
    val expectedRateBps: Long?,
    val rttSeconds: Double?,
    val hops: Int?,
    val linkReused: Boolean,
    val nextHopBitrateBps: Long? = null, // First hop interface bitrate (heuristic)
    val error: String? = null,
) {
    /**
     * Whether the probe was successful.
     */
    val isSuccess: Boolean
        get() = status == "success"

    /**
     * Get the best available rate estimate in bits per second.
     * Prefers expected_rate (from actual transfers) over establishment_rate.
     */
    val bestRateBps: Long?
        get() = expectedRateBps ?: establishmentRateBps

    /**
     * Calculate estimated transfer time for a given size in bytes.
     * Returns time in seconds, or null if no rate is available.
     */
    fun estimateTransferTimeSeconds(sizeBytes: Long): Double? {
        val rateBps = bestRateBps ?: return null
        if (rateBps <= 0) return null
        val sizeBits = sizeBytes * 8
        return sizeBits.toDouble() / rateBps
    }

    companion object {
        /**
         * Create a result from a Python dict response.
         */
        fun fromMap(map: Map<String, Any?>): LinkSpeedProbeResult {
            return LinkSpeedProbeResult(
                status = map["status"] as? String ?: "error",
                establishmentRateBps = (map["establishment_rate_bps"] as? Number)?.toLong(),
                expectedRateBps = (map["expected_rate_bps"] as? Number)?.toLong(),
                rttSeconds = (map["rtt_seconds"] as? Number)?.toDouble(),
                hops = (map["hops"] as? Number)?.toInt(),
                linkReused = map["link_reused"] as? Boolean ?: false,
                error = map["error"] as? String,
            )
        }
    }
}
