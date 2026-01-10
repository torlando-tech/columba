package com.lxmf.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import com.lxmf.messenger.data.model.ImageCompressionPreset
import java.io.ByteArrayOutputStream
import kotlin.math.max

@Suppress("TooManyFunctions") // Utility object with cohesive image processing functions
object ImageUtils {
    private const val TAG = "ImageUtils"

    const val MAX_IMAGE_SIZE_BYTES = 512 * 1024 // 512KB for efficient mesh network transmission
    const val MAX_IMAGE_DIMENSION = 2048 // pixels
    const val HEAVY_COMPRESSION_THRESHOLD = 50 // Quality below this is considered "heavy"
    private const val MAX_PREVIEW_DIMENSION = 4096 // Max for loading to avoid Canvas limits
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
     * Compress an image to WebP format with quality reduction as needed.
     * Provides detailed result information to check if heavy compression was applied.
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
        var bitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        return try {
            // Get original file size for logging
            val originalSize =
                context.contentResolver.openInputStream(uri)?.use {
                    it.available()
                } ?: 0

            // Load bitmap from URI with subsampling to avoid memory issues
            bitmap =
                loadBitmap(context, uri, MAX_IMAGE_DIMENSION) ?: run {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return null
                }

            // Scale down to exact dimensions if needed (subsampling gives approximate size)
            scaledBitmap = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)

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

            val exceedsSizeLimit = compressed.size > maxSizeBytes

            Log.d(
                TAG,
                "Compressed image: ${originalSize / 1024}KB -> ${compressed.size / 1024}KB " +
                    "(quality: $finalQuality, scaled: ${scaledBitmap != bitmap}, exceeds: $exceedsSizeLimit)",
            )

            CompressedImage(compressed, "webp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            null
        } finally {
            // Always recycle bitmaps to prevent memory leaks
            scaledBitmap?.takeIf { it != bitmap }?.recycle()
            bitmap?.recycle()
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

    /**
     * Load a bitmap from URI, subsampling if needed to avoid memory issues.
     * Also handles EXIF orientation to ensure correct rotation.
     *
     * @param context Android context
     * @param uri Image URI
     * @param maxDimension Maximum dimension to load (uses subsampling for larger images)
     * @return Loaded bitmap with correct orientation, or null on failure
     */
    private fun loadBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_PREVIEW_DIMENSION,
    ): Bitmap? {
        // Always cap to MAX_PREVIEW_DIMENSION to prevent Canvas size crashes
        // even if caller requests larger (e.g., ORIGINAL preset with Int.MAX_VALUE)
        val effectiveMaxDimension = minOf(maxDimension, MAX_PREVIEW_DIMENSION)

        var loadedBitmap: Bitmap? = null
        return try {
            // First, get the image dimensions without loading
            val options =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            // Calculate sample size to fit within effectiveMaxDimension
            val imageWidth = options.outWidth
            val imageHeight = options.outHeight
            val sampleSize = calculateSampleSize(imageWidth, imageHeight, effectiveMaxDimension)

            if (sampleSize > 1) {
                Log.d(TAG, "Subsampling image (${imageWidth}x$imageHeight) with sampleSize=$sampleSize")
            }

            // Read EXIF orientation before loading
            val orientation = getExifOrientation(context, uri)

            // Now load with subsampling
            val loadOptions =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

            loadedBitmap =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, loadOptions)
                } ?: return null

            // Apply EXIF rotation if needed
            val result = applyExifOrientation(loadedBitmap, orientation)
            // Clear reference - applyExifOrientation either returns same bitmap or recycles original
            loadedBitmap = null
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap", e)
            null
        } finally {
            // Only recycle if we haven't successfully processed it
            loadedBitmap?.recycle()
        }
    }

    /**
     * Get EXIF orientation from image URI.
     */
    @androidx.annotation.VisibleForTesting
    internal fun getExifOrientation(
        context: Context,
        uri: Uri,
    ): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Apply EXIF orientation to bitmap, returning a new rotated bitmap if needed.
     */
    @androidx.annotation.VisibleForTesting
    internal fun applyExifOrientation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap // No rotation needed
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
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
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    /**
     * Calculate the sample size for BitmapFactory to subsample an image during loading.
     * Uses powers of 2 as required by BitmapFactory.
     *
     * @param imageWidth Original image width in pixels
     * @param imageHeight Original image height in pixels
     * @param maxDimension Target maximum dimension
     * @return Sample size (1, 2, 4, 8, etc.) for inSampleSize
     */
    @androidx.annotation.VisibleForTesting
    internal fun calculateSampleSize(
        imageWidth: Int,
        imageHeight: Int,
        maxDimension: Int,
    ): Int {
        var sampleSize = 1

        if (imageWidth > maxDimension || imageHeight > maxDimension) {
            val halfWidth = imageWidth / 2
            val halfHeight = imageHeight / 2

            while ((halfWidth / sampleSize) >= maxDimension ||
                (halfHeight / sampleSize) >= maxDimension
            ) {
                sampleSize *= 2
            }
        }

        return sampleSize
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
        var bitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        return try {
            // Get original file size
            val originalSize = getFileSize(context, uri)

            // Load bitmap from URI with subsampling to avoid memory issues
            bitmap =
                loadBitmap(context, uri, preset.maxDimensionPx) ?: run {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return null
                }

            // Scale down to exact dimensions if needed (subsampling gives approximate size)
            scaledBitmap = scaleDownIfNeeded(bitmap, preset.maxDimensionPx)

            // Compress to JPEG with progressive quality reduction
            var quality = preset.initialQuality
            var compressed: ByteArray

            do {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                compressed = stream.toByteArray()
                quality -= 10
            } while (compressed.size > preset.targetSizeBytes && quality >= preset.minQuality)

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
        } finally {
            // Always recycle bitmaps to prevent memory leaks
            scaledBitmap?.takeIf { it != bitmap }?.recycle()
            bitmap?.recycle()
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
