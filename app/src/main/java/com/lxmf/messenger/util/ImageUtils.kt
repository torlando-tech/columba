package com.lxmf.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImageUtils {
    private const val TAG = "ImageUtils"

    const val MAX_IMAGE_SIZE_BYTES = 512 * 1024 // 512KB for efficient mesh network transmission
    const val MAX_IMAGE_DIMENSION = 2048 // pixels
    const val HEAVY_COMPRESSION_THRESHOLD = 50 // Quality below this is considered "heavy"
    val SUPPORTED_IMAGE_FORMATS = setOf("jpg", "jpeg", "png", "webp")

    data class CompressedImage(
        val data: ByteArray,
        val format: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CompressedImage

            if (!data.contentEquals(other.data)) return false
            if (format != other.format) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + format.hashCode()
            return result
        }
    }

    /**
     * Result of image compression with metadata about the compression process.
     */
    data class CompressionResult(
        val data: ByteArray,
        val format: String,
        val originalSizeBytes: Int,
        val compressedSizeBytes: Int,
        val qualityUsed: Int,
        val wasScaledDown: Boolean,
        val exceedsSizeLimit: Boolean,
    ) {
        /** True if heavy compression was needed (quality below threshold) or size limit exceeded */
        val needsUserConfirmation: Boolean
            get() = qualityUsed < HEAVY_COMPRESSION_THRESHOLD || exceedsSizeLimit

        /** Human-readable compression ratio (e.g., "75% smaller") */
        val compressionRatioText: String
            get() {
                if (originalSizeBytes == 0) return "N/A"
                val reduction = ((1 - compressedSizeBytes.toFloat() / originalSizeBytes) * 100).toInt()
                return "$reduction% smaller"
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CompressionResult

            if (!data.contentEquals(other.data)) return false
            if (format != other.format) return false
            if (originalSizeBytes != other.originalSizeBytes) return false
            if (compressedSizeBytes != other.compressedSizeBytes) return false
            if (qualityUsed != other.qualityUsed) return false
            if (wasScaledDown != other.wasScaledDown) return false
            if (exceedsSizeLimit != other.exceedsSizeLimit) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + format.hashCode()
            result = 31 * result + originalSizeBytes
            result = 31 * result + compressedSizeBytes
            result = 31 * result + qualityUsed
            result = 31 * result + wasScaledDown.hashCode()
            result = 31 * result + exceedsSizeLimit.hashCode()
            return result
        }
    }

    /**
     * Compresses an image with detailed result information.
     * Use this when you need to check if heavy compression was applied.
     */
    fun compressImageWithMetadata(
        context: Context,
        uri: Uri,
        maxSizeBytes: Int = MAX_IMAGE_SIZE_BYTES,
    ): CompressionResult? {
        return try {
            // Get original file size
            val originalSize = context.contentResolver.openInputStream(uri)?.use {
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
            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

            CompressionResult(
                data = compressed,
                format = "webp",
                originalSizeBytes = originalSize,
                compressedSizeBytes = compressed.size,
                qualityUsed = finalQuality,
                wasScaledDown = wasScaledDown,
                exceedsSizeLimit = exceedsSizeLimit,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            null
        }
    }

    /**
     * Simple compression that returns just the compressed data.
     * Use [compressImageWithMetadata] if you need compression details.
     */
    fun compressImage(
        context: Context,
        uri: Uri,
        maxSizeBytes: Int = MAX_IMAGE_SIZE_BYTES,
    ): CompressedImage? {
        return compressImageWithMetadata(context, uri, maxSizeBytes)?.let {
            CompressedImage(it.data, it.format)
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
}
