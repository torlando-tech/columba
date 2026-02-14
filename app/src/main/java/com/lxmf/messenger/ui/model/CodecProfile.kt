package com.lxmf.messenger.ui.model

import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult

/**
 * Audio codec profiles for voice calls.
 *
 * These map to LXST's Telephony.Profiles constants:
 * - Codec2 profiles (0x10-0x30): Lower bandwidth, works over very slow links
 * - Opus profiles (0x40-0x60): Higher quality, requires more bandwidth
 * - Latency profiles (0x70-0x80): Optimized for low delay
 */
enum class CodecProfile(
    val code: Int,
    val displayName: String,
    val description: String,
    val isExperimental: Boolean = false,
) {
    BANDWIDTH_ULTRA_LOW(
        code = 0x10,
        displayName = "Ultra Low Bandwidth",
        description = "Codec2 700C - Best for very slow connections",
    ),
    BANDWIDTH_VERY_LOW(
        code = 0x20,
        displayName = "Very Low Bandwidth",
        description = "Codec2 1600 - Good for slow connections",
    ),
    BANDWIDTH_LOW(
        code = 0x30,
        displayName = "Low Bandwidth",
        description = "Codec2 3200 - Balanced for limited bandwidth",
    ),
    QUALITY_MEDIUM(
        code = 0x40,
        displayName = "Medium Quality",
        description = "Opus - Good balance of quality and bandwidth",
    ),
    QUALITY_HIGH(
        code = 0x50,
        displayName = "High Quality",
        description = "Opus - Higher fidelity audio",
    ),
    QUALITY_MAX(
        code = 0x60,
        displayName = "Maximum Quality",
        description = "Opus - Best audio, requires more bandwidth",
    ),
    LATENCY_LOW(
        code = 0x80,
        displayName = "Low Latency",
        description = "Opus - Reduced delay, 20ms frames",
        isExperimental = true,
    ),
    LATENCY_ULTRA_LOW(
        code = 0x70,
        displayName = "Ultra Low Latency",
        description = "Opus - Minimized delay, 10ms frames",
        isExperimental = true,
    ),
    ;

    companion object {
        val DEFAULT = QUALITY_MEDIUM

        fun fromCode(code: Int): CodecProfile? = entries.find { it.code == code }

        /**
         * Get a conservative bandwidth estimate from link probe results.
         *
         * Uses min of establishment rate and next hop bitrate for safety.
         * The next hop interface bitrate only tells us about the first hop -
         * there could be slower hops further along the path in a mesh network.
         * The establishment rate measures actual end-to-end path performance.
         *
         * @param probe The link speed probe result
         * @return Conservative bandwidth estimate in bits per second, or null if no data
         */
        fun getConservativeBandwidthBps(probe: LinkSpeedProbeResult): Long? {
            // Best case: actual measured throughput from prior transfers
            val expected = probe.expectedRateBps
            if (expected != null && expected > 0) {
                return expected
            }
            val establishment = probe.establishmentRateBps?.takeIf { it > 0 }
            val nextHop = probe.nextHopBitrateBps?.takeIf { it > 0 }
            return when {
                establishment != null && nextHop != null -> minOf(establishment, nextHop)
                establishment != null -> establishment
                nextHop != null -> nextHop
                else -> null
            }
        }

        /**
         * Recommend a codec profile based on link probe results.
         *
         * Uses conservative bandwidth thresholds with headroom for overhead:
         * - Codec2 700C (0.7 kbps): recommend when < 1.5 kbps
         * - Codec2 1600 (1.6 kbps): recommend when 1.5-4 kbps
         * - Codec2 3200 (3.2 kbps): recommend when 4-10 kbps
         * - Opus low (~12 kbps): recommend when 10-32 kbps
         * - Opus medium (~24 kbps): recommend when 32-64 kbps
         * - Opus high (~48 kbps): recommend when > 64 kbps
         *
         * @param probe The link speed probe result
         * @return Recommended codec profile based on available bandwidth
         */
        fun recommendFromProbe(probe: LinkSpeedProbeResult): CodecProfile {
            val bandwidthBps = getConservativeBandwidthBps(probe) ?: return DEFAULT
            val kbps = bandwidthBps / 1000.0
            return when {
                kbps < 1.5 -> BANDWIDTH_ULTRA_LOW // Codec2 700C
                kbps < 4 -> BANDWIDTH_VERY_LOW // Codec2 1600
                kbps < 10 -> BANDWIDTH_LOW // Codec2 3200
                kbps < 32 -> QUALITY_MEDIUM // Opus low
                kbps < 64 -> QUALITY_HIGH // Opus medium
                else -> QUALITY_MAX // Opus high
            }
        }
    }
}
