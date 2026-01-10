package com.lxmf.messenger.data.model

/**
 * Image compression presets for adaptive network-aware compression.
 * Presets are ordered from most aggressive compression (LOW) to least (ORIGINAL).
 *
 * @property displayName User-facing name for the preset
 * @property maxDimensionPx Maximum image dimension in pixels (width or height)
 * @property targetSizeBytes Target file size in bytes after compression
 * @property initialQuality Starting JPEG/WebP quality (0-100)
 * @property minQuality Minimum quality to try before giving up on target size
 * @property description Brief description for settings UI
 */
enum class ImageCompressionPreset(
    val displayName: String,
    val maxDimensionPx: Int,
    val targetSizeBytes: Long,
    val initialQuality: Int,
    val minQuality: Int,
    val description: String,
) {
    // 32KB target
    LOW(
        displayName = "Low",
        maxDimensionPx = 320,
        targetSizeBytes = 32 * 1024L,
        initialQuality = 60,
        minQuality = 30,
        description = "32KB max - optimized for LoRa and BLE",
    ),

    // 128KB target
    MEDIUM(
        displayName = "Medium",
        maxDimensionPx = 800,
        targetSizeBytes = 128 * 1024L,
        initialQuality = 75,
        minQuality = 40,
        description = "128KB max - balanced for mixed networks",
    ),

    // 512KB target
    HIGH(
        displayName = "High",
        maxDimensionPx = 2048,
        targetSizeBytes = 512 * 1024L,
        initialQuality = 90,
        minQuality = 50,
        description = "512KB max - good quality for general use",
    ),

    // 25MB target
    ORIGINAL(
        displayName = "Original",
        maxDimensionPx = 8192, // 8K resolution - exceeds Android Canvas limit if higher
        targetSizeBytes = 25 * 1024 * 1024L,
        initialQuality = 95,
        minQuality = 90,
        description = "25MB max - minimal compression for fast networks",
    ),

    // Default values (will be overridden by detection)
    AUTO(
        displayName = "Auto",
        maxDimensionPx = 2048,
        targetSizeBytes = 512 * 1024L,
        initialQuality = 90,
        minQuality = 50,
        description = "Automatically select based on enabled interfaces",
    ),
    ;

    companion object {
        val DEFAULT = AUTO

        /**
         * Parse a preset from its name, falling back to DEFAULT if not found.
         */
        fun fromName(name: String): ImageCompressionPreset = entries.find { it.name == name } ?: DEFAULT
    }
}
