package network.columba.app.data.model

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * Image compression presets for adaptive network-aware compression.
 * Presets are ordered from most aggressive compression (LOW) to least (ORIGINAL).
 *
 * @property displayNameRes User-facing name for the preset
 * @property maxDimensionPx Maximum image dimension in pixels (width or height)
 * @property targetSizeBytes Target file size in bytes after compression
 * @property initialQuality Starting JPEG/WebP quality (0-100)
 * @property minQuality Minimum quality to try before giving up on target size
 * @property descriptionRes Brief description for settings UI
 */
enum class ImageCompressionPreset(
    @StringRes val displayNameRes: Int,
    val maxDimensionPx: Int,
    val targetSizeBytes: Long,
    val initialQuality: Int,
    val minQuality: Int,
    @StringRes val descriptionRes: Int,
) {
    // 32KB target
    LOW(
        displayNameRes = R.string.compression_preset_low,
        maxDimensionPx = 320,
        targetSizeBytes = 32 * 1024L,
        initialQuality = 60,
        minQuality = 30,
        descriptionRes = R.string.compression_desc_low,
    ),

    // 128KB target
    MEDIUM(
        displayNameRes = R.string.compression_preset_medium,
        maxDimensionPx = 800,
        targetSizeBytes = 128 * 1024L,
        initialQuality = 75,
        minQuality = 40,
        descriptionRes = R.string.compression_desc_medium,
    ),

    // 512KB target
    HIGH(
        displayNameRes = R.string.compression_preset_high,
        maxDimensionPx = 2048,
        targetSizeBytes = 512 * 1024L,
        initialQuality = 90,
        minQuality = 50,
        descriptionRes = R.string.compression_desc_high,
    ),

    // 25MB target
    ORIGINAL(
        displayNameRes = R.string.compression_preset_original,
        // 8K resolution - exceeds Android Canvas limit if higher
        maxDimensionPx = 8192,
        targetSizeBytes = 25 * 1024 * 1024L,
        initialQuality = 95,
        minQuality = 90,
        descriptionRes = R.string.compression_desc_original,
    ),

    // Default values (will be overridden by detection)
    AUTO(
        displayNameRes = R.string.compression_preset_auto,
        maxDimensionPx = 2048,
        targetSizeBytes = 512 * 1024L,
        initialQuality = 90,
        minQuality = 50,
        descriptionRes = R.string.compression_desc_auto,
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
