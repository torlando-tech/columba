package com.lxmf.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.lxmf.messenger.data.model.ImageCompressionPreset
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImageUtils {
    private const val TAG = "ImageUtils"

    const val MAX_IMAGE_SIZE_BYTES = 512 * 1024 // 512KB for efficient mesh network transmission
    const val MAX_IMAGE_DIMENSION = 2048 // pixels
    const val HEAVY_COMPRESSION_THRESHOLD = 50 // Quality below this is considered "heavy"
    val SUPPORTED_IMAGE_FORMATS = setOf("jpg", "jpeg", "png", "webp", "gif")

    /**
     * Result of image compression with metadata.
     */
    data class CompressionResult(
        val compressedImage: CompressedImage,
        val originalSizeBytes: Long,
        val meetsTargetSize: Boolean,
        val targetSizeBytes: Long,
        val preset: ImageCompressionPreset,
    )

    /**
     * Estimated transfer time with formatted display string.
     */
    data class TransferTimeEstimate(
        val seconds: Int,
        val formattedTime: String,
    )

    /**
     * Result of image compression.
     *
     * @property data The compressed image bytes
     * @property format The image format (e.g., "jpg", "gif")
     * @property isAnimated True if this is an animated image (GIF) that was preserved
     */
    data class CompressedImage(
        val data: ByteArray,
        val format: String,
        val isAnimated: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CompressedImage

            if (!data.contentEquals(other.data)) return false
            if (format != other.format) return false
            if (isAnimated != other.isAnimated) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + format.hashCode()
            result = 31 * result + isAnimated.hashCode()
            return result
        }
    }

    /**
     * Check if the given bytes represent an animated GIF.
     *
     * Checks for GIF89a header and looks for NETSCAPE application extension
     * which indicates animation.
     *
     * @param bytes Raw image bytes
     * @return True if this is an animated GIF
     */
    @Suppress("ReturnCount") // Multiple returns improve readability for sequential checks
    fun isAnimatedGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false

        // Check for GIF header (GIF87a or GIF89a)
        val header = String(bytes.sliceArray(0..5), Charsets.US_ASCII)
        if (header != "GIF89a" && header != "GIF87a") return false

        // Look for NETSCAPE2.0 application extension (indicates animation)
        if (hasNetscapeExtension(bytes)) return true

        // Fallback: check for multiple graphic control extensions (multiple frames)
        return hasMultipleFrames(bytes)
    }

    /**
     * Check for NETSCAPE2.0 application extension which indicates animation looping.
     */
    private fun hasNetscapeExtension(bytes: ByteArray): Boolean {
        // Pattern: 0x21 0xFF 0x0B "NETSCAPE2.0"
        val netscapeSignature = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        for (i in 0 until bytes.size - netscapeSignature.size - 3) {
            if (bytes[i] == 0x21.toByte() &&
                bytes[i + 1] == 0xFF.toByte() &&
                bytes[i + 2] == 0x0B.toByte()
            ) {
                // Check if NETSCAPE2.0 follows
                var matches = true
                for (j in netscapeSignature.indices) {
                    if (bytes[i + 3 + j] != netscapeSignature[j]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
        }
        return false
    }

    /**
     * Check for multiple graphic control extensions which indicate multiple frames.
     * Pattern: 0x21 0xF9 (Graphic Control Extension)
     */
    private fun hasMultipleFrames(bytes: ByteArray): Boolean {
        var graphicControlCount = 0
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte()) {
                graphicControlCount++
                if (graphicControlCount > 1) return true
            }
        }
        return false
    }

    /**
     * Compresses an image with detailed result information.
     * Use this when you need to check if heavy compression was applied.
     */
    /**
     * Compress an image to WebP format with quality reduction as needed.
     *
     * @param context Android context
     * @param uri URI of the image to compress
     * @param maxSizeBytes Maximum size in bytes (default 512KB)
     * @return CompressedImage or null on failure
     */
    fun compressImage(
        context: Context,
        uri: Uri,
        maxSizeBytes: Int = MAX_IMAGE_SIZE_BYTES,
    ): CompressedImage? {
        return try {
            // Get original file size for logging
            val originalSize =
                context.contentResolver.openInputStream(uri)?.use {
                    it.available()
                } ?: 0

            // Load bitmap from URI
            val bitmap =
                loadBitmap(context, uri) ?: run {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return null
                }

            // Scale down if dimensions exceed max
            val scaledBitmap = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
            val wasScaledDown = scaledBitmap != bitmap

            // Compress to WebP with progressive quality reduction
            // WebP provides better compression and strips EXIF metadata for Sideband interop
            val webpFormat =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }

            var quality = 90
            var compressed: ByteArray

            do {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(webpFormat, quality, stream)
                compressed = stream.toByteArray()
                quality -= 10
            } while (compressed.size > maxSizeBytes && quality > 10)

            // Restore the actual quality used (loop decrements before exit check)
            val finalQuality = quality + 10

            if (wasScaledDown) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            val exceedsSizeLimit = compressed.size > maxSizeBytes

            Log.d(
                TAG,
                "Compressed image: ${originalSize / 1024}KB -> ${compressed.size / 1024}KB " +
                    "(quality: $finalQuality, scaled: $wasScaledDown, exceeds: $exceedsSizeLimit)",
            )

            CompressedImage(compressed, "webp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            null
        }
    }

    /**
     * Compress an image while preserving GIF animation when possible.
     *
     * For animated GIFs under the size limit, the original bytes are passed through unchanged
     * to preserve animation. For oversized GIFs or other formats, standard compression is applied
     * (converting to JPEG, which loses animation).
     *
     * @param context Android context
     * @param uri URI of the image to compress
     * @param maxSizeBytes Maximum size in bytes (default 512KB)
     * @return CompressedImage with isAnimated flag set appropriately, or null on error
     */
    fun compressImagePreservingAnimation(
        context: Context,
        uri: Uri,
        maxSizeBytes: Int = MAX_IMAGE_SIZE_BYTES,
    ): CompressedImage? {
        return try {
            // Read raw bytes from URI
            val rawBytes =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: run {
                    Log.e(TAG, "Failed to read bytes from URI")
                    return null
                }

            // Check if it's an animated GIF
            if (isAnimatedGif(rawBytes)) {
                if (rawBytes.size <= maxSizeBytes) {
                    // GIF is small enough, preserve animation
                    Log.d(TAG, "Preserving animated GIF (${rawBytes.size} bytes)")
                    return CompressedImage(rawBytes, "gif", isAnimated = true)
                } else {
                    // GIF is too large, must compress (loses animation)
                    Log.w(
                        TAG,
                        "Animated GIF too large (${rawBytes.size} bytes), " +
                            "compressing to JPEG (animation will be lost)",
                    )
                }
            }

            // For non-GIFs or oversized GIFs, use standard compression
            compressImage(context, uri, maxSizeBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            null
        }
    }

    private fun loadBitmap(
        context: Context,
        uri: Uri,
    ): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap", e)
            null
        }
    }

    private fun scaleDownIfNeeded(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxCurrentDimension = max(width, height)

        if (maxCurrentDimension <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / maxCurrentDimension
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Scaling image from ${width}x$height to ${newWidth}x$newHeight")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun getImageFormat(
        uri: Uri,
        context: Context,
    ): String? {
        return try {
            context.contentResolver.getType(uri)?.let { mimeType ->
                when (mimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get image format", e)
            null
        }
    }

    fun isImageFormatSupported(format: String?): Boolean {
        return format?.lowercase() in SUPPORTED_IMAGE_FORMATS
    }

    /**
     * Compress an image using the specified preset's parameters.
     *
     * @param context Android context for content resolver access
     * @param uri URI of the image to compress
     * @param preset Compression preset to use
     * @return CompressionResult with compressed image and metadata, or null on failure
     */
    fun compressImageWithPreset(
        context: Context,
        uri: Uri,
        preset: ImageCompressionPreset,
    ): CompressionResult? {
        return try {
            // Get original file size
            val originalSize = getFileSize(context, uri)

            // Load bitmap from URI
            val bitmap =
                loadBitmap(context, uri) ?: run {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return null
                }

            // Scale down if dimensions exceed preset's max
            val scaledBitmap = scaleDownIfNeeded(bitmap, preset.maxDimensionPx)

            // Compress to JPEG with progressive quality reduction
            var quality = preset.initialQuality
            var compressed: ByteArray

            do {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                compressed = stream.toByteArray()
                quality -= 10
            } while (compressed.size > preset.targetSizeBytes && quality >= preset.minQuality)

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            val meetsTarget = compressed.size <= preset.targetSizeBytes

            Log.d(
                TAG,
                "Compressed image with preset ${preset.name}: " +
                    "${compressed.size} bytes (target: ${preset.targetSizeBytes}, meets: $meetsTarget)",
            )

            CompressionResult(
                compressedImage = CompressedImage(compressed, "jpg"),
                originalSizeBytes = originalSize,
                meetsTargetSize = meetsTarget,
                targetSizeBytes = preset.targetSizeBytes,
                preset = preset,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image with preset", e)
            null
        }
    }

    /**
     * Calculate estimated transfer time for a given file size and bandwidth.
     *
     * @param sizeBytes File size in bytes
     * @param bandwidthBps Network bandwidth in bits per second
     * @return TransferTimeEstimate with seconds and formatted time string
     */
    fun calculateTransferTime(
        sizeBytes: Long,
        bandwidthBps: Int,
    ): TransferTimeEstimate {
        if (bandwidthBps <= 0) {
            return TransferTimeEstimate(0, "Unknown")
        }

        val sizeBits = sizeBytes * 8
        val seconds = (sizeBits / bandwidthBps).toInt()

        val formattedTime =
            when {
                seconds < 1 -> "< 1s"
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> {
                    val minutes = seconds / 60
                    val remainingSeconds = seconds % 60
                    if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
                }
                else -> {
                    val hours = seconds / 3600
                    val remainingMinutes = (seconds % 3600) / 60
                    if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
                }
            }

        return TransferTimeEstimate(seconds, formattedTime)
    }

    /**
     * Get the file size of a URI in bytes.
     */
    private fun getFileSize(
        context: Context,
        uri: Uri,
    ): Long {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size", e)
            0L
        }
    }
}
