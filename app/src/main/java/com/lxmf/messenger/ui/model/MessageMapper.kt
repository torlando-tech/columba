package com.lxmf.messenger.ui.model

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lxmf.messenger.data.repository.Message
import org.json.JSONObject
import java.io.File

private const val TAG = "MessageMapper"

/**
 * Marker key indicating a field is stored on disk.
 * Must match AttachmentStorageManager.FILE_REF_KEY
 */
private const val FILE_REF_KEY = "_file_ref"

/**
 * Converts a domain Message to MessageUi.
 *
 * This function checks the ImageCache for pre-decoded images to avoid blocking
 * the main thread. If an image exists but isn't cached, the message will have
 * hasImageAttachment=true but decodedImage=null, signaling that async loading is needed.
 *
 * This is safe to call on the main thread because:
 * - Cache lookup is fast (O(1) LruCache access)
 * - No disk I/O or image decoding happens here
 * - Image decoding happens asynchronously via decodeAndCacheImage()
 */
fun Message.toMessageUi(): MessageUi {
    val hasImage = hasImageField(fieldsJson)
    val cachedImage = if (hasImage) ImageCache.get(id) else null

    return MessageUi(
        id = id,
        destinationHash = destinationHash,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = status,
        decodedImage = cachedImage,
        hasImageAttachment = hasImage,
        // Include fieldsJson only if there's an uncached image (needed for async loading)
        fieldsJson = if (hasImage && cachedImage == null) fieldsJson else null,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
    )
}

/**
 * Check if the message has an image field (type 6) in its JSON.
 * This is a fast check that doesn't decode anything.
 * Returns false for invalid JSON (malformed messages should not show images).
 */
@Suppress("SwallowedException") // Invalid JSON is expected to fail silently here
private fun hasImageField(fieldsJson: String?): Boolean {
    if (fieldsJson == null) return false
    return try {
        val fields = JSONObject(fieldsJson)
        val field6 = fields.opt("6")
        when {
            field6 is JSONObject && field6.has(FILE_REF_KEY) -> true
            field6 is String && field6.isNotEmpty() -> true
            else -> false
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Decode and cache the image for a message.
 *
 * IMPORTANT: Call this from a background thread (Dispatchers.IO).
 * This function performs disk I/O and expensive image decoding.
 *
 * @param messageId The message ID (used as cache key)
 * @param fieldsJson The message's fields JSON containing the image data
 * @return The decoded ImageBitmap, or null if decoding fails
 */
fun decodeAndCacheImage(
    messageId: String,
    fieldsJson: String?,
): ImageBitmap? {
    // Check cache first (in case another coroutine already decoded it)
    ImageCache.get(messageId)?.let { return it }

    val decoded = decodeImageFromFields(fieldsJson)
    if (decoded != null) {
        ImageCache.put(messageId, decoded)
        Log.d(TAG, "Decoded and cached image for message ${messageId.take(8)}...")
    }
    return decoded
}

/**
 * Decodes LXMF image field (type 6) from hex string to ImageBitmap.
 *
 * Supports two formats:
 * 1. Inline hex string: "6": "ffda8e..." (original format)
 * 2. File reference: "6": {"_file_ref": "/path/to/file"} (large attachments saved to disk)
 *
 * IMPORTANT: This performs disk I/O and CPU-intensive decoding.
 * Must be called from a background thread.
 *
 * Returns null if no image field exists or decoding fails.
 */
private fun decodeImageFromFields(fieldsJson: String?): ImageBitmap? {
    if (fieldsJson == null) return null

    return try {
        val fields = JSONObject(fieldsJson)

        // Get field 6 (IMAGE) - could be string or object with file reference
        val field6 = fields.opt("6") ?: return null

        val hexImageData: String =
            when {
                // File reference: load from disk
                field6 is JSONObject && field6.has(FILE_REF_KEY) -> {
                    val filePath = field6.getString(FILE_REF_KEY)
                    loadAttachmentFromDisk(filePath) ?: return null
                }
                // Inline hex string
                field6 is String && field6.isNotEmpty() -> field6
                else -> return null
            }

        // Convert hex string to bytes
        val imageBytes =
            hexImageData.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

        // Decode bitmap
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode image", e)
        null
    }
}

/**
 * Load attachment data from disk.
 *
 * IMPORTANT: This performs disk I/O. Must be called from a background thread.
 *
 * @param filePath Absolute path to attachment file
 * @return Attachment data (hex-encoded string), or null if not found
 */
private fun loadAttachmentFromDisk(filePath: String): String? {
    return try {
        val file = File(filePath)
        if (file.exists()) {
            file.readText().also {
                Log.d(TAG, "Loaded attachment from disk: $filePath (${it.length} chars)")
            }
        } else {
            Log.w(TAG, "Attachment file not found: $filePath")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load attachment from disk: $filePath", e)
        null
    }
}
