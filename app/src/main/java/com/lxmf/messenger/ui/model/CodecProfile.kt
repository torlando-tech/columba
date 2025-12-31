package com.lxmf.messenger.ui.model

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
    LATENCY_ULTRA_LOW(
        code = 0x70,
        displayName = "Ultra Low Latency",
        description = "Opus - Minimized delay for real-time",
    ),
    LATENCY_LOW(
        code = 0x80,
        displayName = "Low Latency",
        description = "Opus - Reduced delay",
    ),
    ;

    companion object {
        val DEFAULT = QUALITY_MEDIUM

        fun fromCode(code: Int): CodecProfile? = entries.find { it.code == code }
    }
}
