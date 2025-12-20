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
    LOW(
        displayName = "Low",
        maxDimensionPx = 320,
        targetSizeBytes = 32 * 1024L, // 32KB
        initialQuality = 60,
        minQuality = 30,
        description = "32KB max - optimized for LoRa and BLE",
    ),
    MEDIUM(
        displayName = "Medium",
        maxDimensionPx = 800,
        targetSizeBytes = 128 * 1024L, // 128KB
        initialQuality = 75,
        minQuality = 40,
        description = "128KB max - balanced for mixed networks",
    ),
    HIGH(
        displayName = "High",
        maxDimensionPx = 2048,
        targetSizeBytes = 512 * 1024L, // 512KB
        initialQuality = 90,
        minQuality = 50,
        description = "512KB max - good quality for general use",
    ),
    ORIGINAL(
        displayName = "Original",
        maxDimensionPx = Int.MAX_VALUE,
        targetSizeBytes = 250 * 1024 * 1024L, // 250MB
        initialQuality = 95,
        minQuality = 90,
        description = "250MB max - minimal compression for fast networks",
    ),
    AUTO(
        displayName = "Auto",
        maxDimensionPx = 2048, // Default, will be overridden by detection
        targetSizeBytes = 512 * 1024L, // Default, will be overridden by detection
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
        fun fromName(name: String): ImageCompressionPreset =
            entries.find { it.name == name } ?: DEFAULT
    }
}
