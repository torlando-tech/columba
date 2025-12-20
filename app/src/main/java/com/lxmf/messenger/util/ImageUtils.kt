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
    val SUPPORTED_IMAGE_FORMATS = setOf("jpg", "jpeg", "png", "webp")

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

    fun compressImage(
        context: Context,
        uri: Uri,
        maxSizeBytes: Int = MAX_IMAGE_SIZE_BYTES,
    ): CompressedImage? {
        return try {
            // Load bitmap from URI
            val bitmap =
                loadBitmap(context, uri) ?: run {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return null
                }

            // Scale down if dimensions exceed max
            val scaledBitmap = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)

            // Compress to JPEG with progressive quality reduction
            var quality = 90
            var compressed: ByteArray

            do {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                compressed = stream.toByteArray()
                quality -= 10
            } while (compressed.size > maxSizeBytes && quality > 10)

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            Log.d(TAG, "Compressed image to ${compressed.size} bytes (quality: ${quality + 10})")
            CompressedImage(compressed, "jpg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
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
