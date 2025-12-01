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
}
