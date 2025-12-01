package com.lxmf.messenger.ui.model

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lxmf.messenger.data.repository.Message
import org.json.JSONObject
import java.io.File

/**
 * Marker key indicating a field is stored on disk.
 * Must match AttachmentStorageManager.FILE_REF_KEY
 */
private const val FILE_REF_KEY = "_file_ref"

/**
 * Converts a domain Message to MessageUi with pre-decoded images.
 *
 * This function performs the expensive image decoding operation that was previously
 * happening during composition, causing scroll lag.
 *
 * IMPORTANT: This should be called in a background thread (e.g., via map on Flow)
 * to avoid blocking the UI thread.
 */
fun Message.toMessageUi(): MessageUi {
    val decodedImage = decodeImageFromFields(fieldsJson)

    return MessageUi(
        id = id,
        destinationHash = destinationHash,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = status,
        decodedImage = decodedImage,
    )
}

/**
 * Decodes LXMF image field (type 6) from hex string to ImageBitmap.
 *
 * Supports two formats:
 * 1. Inline hex string: "6": "ffda8e..." (original format)
 * 2. File reference: "6": {"_file_ref": "/path/to/file"} (large attachments saved to disk)
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
        android.util.Log.e("MessageMapper", "Failed to decode image", e)
        null
    }
}

/**
 * Load attachment data from disk.
 *
 * @param filePath Absolute path to attachment file
 * @return Attachment data (hex-encoded string), or null if not found
 */
private fun loadAttachmentFromDisk(filePath: String): String? {
    return try {
        val file = File(filePath)
        if (file.exists()) {
            file.readText().also {
                android.util.Log.d("MessageMapper", "Loaded attachment from disk: $filePath (${it.length} chars)")
            }
        } else {
            android.util.Log.w("MessageMapper", "Attachment file not found: $filePath")
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("MessageMapper", "Failed to load attachment from disk: $filePath", e)
        null
    }
}
