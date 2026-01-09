@file:Suppress("TooManyFunctions") // Message mapping requires multiple utilities for different field types

package com.lxmf.messenger.ui.model

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lxmf.messenger.data.repository.Message
import com.lxmf.messenger.util.FileUtils
import com.lxmf.messenger.util.ImageUtils
import org.json.JSONArray
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

    val hasFiles = hasFileAttachmentsField(fieldsJson)
    // DEBUG: Log file attachment detection
    if (fieldsJson?.contains("\"5\"") == true) {
        Log.d(TAG, "Message ${id.take(16)}... has field 5, hasFiles=$hasFiles, json=${fieldsJson?.take(200)}")
    }
    val fileAttachmentsList = if (hasFiles) parseFileAttachments(fieldsJson) else emptyList()

    // Get reply-to message ID: prefer DB column, fallback to parsing field 16
    val replyId = replyToMessageId ?: parseReplyToFromField16(fieldsJson)

    // Parse emoji reactions from field 16
    val reactionsList = parseReactionsFromField16(fieldsJson)

    // Determine if we need to preserve fieldsJson for UI components
    // (uncached image, file attachments, or pending file notification)
    val hasUncachedImage = hasImage && cachedImage == null
    val needsFieldsJson = hasUncachedImage || hasFiles || hasPendingFileNotification(fieldsJson)

    return MessageUi(
        id = id,
        destinationHash = destinationHash,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = status,
        decodedImage = cachedImage,
        hasImageAttachment = hasImage,
        fileAttachments = fileAttachmentsList,
        hasFileAttachments = hasFiles,
        fieldsJson = if (needsFieldsJson) fieldsJson else null,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
        replyToMessageId = replyId,
        // Note: replyPreview is loaded asynchronously by the ViewModel
        reactions = reactionsList,
    )
}

/**
 * Parse the reply_to message ID from LXMF field 16 (app extensions).
 *
 * Field 16 is structured as: {"reply_to": "message_id", ...}
 * This allows for future extensibility (reactions, mentions, etc.)
 *
 * @param fieldsJson The message's fields JSON
 * @return The reply_to message ID, or null if not present or parsing fails
 */
@Suppress("SwallowedException", "ReturnCount") // Invalid JSON is expected to fail silently here
private fun parseReplyToFromField16(fieldsJson: String?): String? {
    if (fieldsJson == null) return null
    return try {
        val fields = JSONObject(fieldsJson)
        val field16 = fields.optJSONObject("16") ?: return null
        // Check for JSON null value explicitly
        if (field16.isNull("reply_to")) return null
        val replyTo = field16.optString("reply_to", "")
        replyTo.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse emoji reactions from LXMF field 16 (app extensions).
 *
 * Field 16 reactions structure: {"reactions": {"👍": ["sender_hash1", "sender_hash2"], "❤️": ["sender_hash3"]}}
 * Each emoji key maps to an array of sender destination hashes who reacted with that emoji.
 *
 * @param fieldsJson The message's fields JSON
 * @return List of ReactionUi objects, or empty list if not present or parsing fails
 */
@Suppress("SwallowedException", "ReturnCount") // Invalid JSON is expected to fail silently here
fun parseReactionsFromField16(fieldsJson: String?): List<ReactionUi> {
    if (fieldsJson == null) return emptyList()
    return try {
        val fields = JSONObject(fieldsJson)
        val field16 = fields.optJSONObject("16") ?: return emptyList()
        val reactionsObj = field16.optJSONObject("reactions") ?: return emptyList()

        val reactions = mutableListOf<ReactionUi>()
        val keys = reactionsObj.keys()
        while (keys.hasNext()) {
            val emoji = keys.next()
            val sendersArray = reactionsObj.optJSONArray(emoji) ?: continue
            val senderHashes = mutableListOf<String>()
            for (i in 0 until sendersArray.length()) {
                // Skip null values in the array
                if (sendersArray.isNull(i)) continue
                val sender = sendersArray.optString(i, "")
                if (sender.isNotEmpty()) {
                    senderHashes.add(sender)
                }
            }
            if (senderHashes.isNotEmpty()) {
                reactions.add(ReactionUi(emoji = emoji, senderHashes = senderHashes))
            }
        }
        reactions
    } catch (e: Exception) {
        emptyList()
    }
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
 * Result of decoding image data from a message.
 *
 * @property rawBytes Raw image bytes (for animated GIFs with Coil)
 * @property bitmap Decoded static bitmap (for non-animated images)
 * @property isAnimated True if this is an animated GIF
 */
data class DecodedImageResult(
    val rawBytes: ByteArray,
    val bitmap: ImageBitmap?,
    val isAnimated: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodedImageResult

        if (!rawBytes.contentEquals(other.rawBytes)) return false
        if (bitmap != other.bitmap) return false
        if (isAnimated != other.isAnimated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rawBytes.contentHashCode()
        result = 31 * result + (bitmap?.hashCode() ?: 0)
        result = 31 * result + isAnimated.hashCode()
        return result
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
 * Decode image data from a message, detecting if it's an animated GIF.
 *
 * IMPORTANT: Call this from a background thread (Dispatchers.IO).
 * This function performs disk I/O and expensive image decoding.
 *
 * For animated GIFs, returns raw bytes without decoding to bitmap (for Coil).
 * For static images, returns both raw bytes and decoded bitmap (bitmap cached).
 *
 * @param messageId The message ID (used as cache key for static images)
 * @param fieldsJson The message's fields JSON containing the image data
 * @return DecodedImageResult with raw bytes, optional bitmap, and animated flag
 */
fun decodeImageWithAnimation(
    messageId: String,
    fieldsJson: String?,
): DecodedImageResult? {
    if (fieldsJson == null) return null

    return try {
        // Get raw image bytes
        val rawBytes = extractImageBytes(fieldsJson) ?: return null

        // Check if it's an animated GIF
        val isAnimated = ImageUtils.isAnimatedGif(rawBytes)

        if (isAnimated) {
            // Animated GIF - don't decode to bitmap, just return raw bytes
            Log.d(TAG, "Detected animated GIF for message ${messageId.take(8)}... (${rawBytes.size} bytes)")
            DecodedImageResult(rawBytes, null, isAnimated = true)
        } else {
            // Static image - decode to bitmap and cache
            // Use subsampling for large images to avoid memory/rendering issues
            val bitmap =
                ImageCache.get(messageId) ?: run {
                    // First pass: get dimensions
                    val boundsOptions =
                        BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

                    // Calculate sample size for display (max 1024px for message bubbles)
                    val maxDisplayDimension = 1024
                    val sampleSize =
                        ImageUtils.calculateSampleSize(
                            boundsOptions.outWidth,
                            boundsOptions.outHeight,
                            maxDisplayDimension,
                        )

                    // Second pass: decode with subsampling
                    val decodeOptions =
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)?.asImageBitmap()

                    if (sampleSize > 1) {
                        Log.d(TAG, "Subsampled image for display: sampleSize=$sampleSize")
                    }
                    decoded?.let { ImageCache.put(messageId, it) }
                    decoded
                }
            Log.d(TAG, "Decoded static image for message ${messageId.take(8)}... (${rawBytes.size} bytes)")
            DecodedImageResult(rawBytes, bitmap, isAnimated = false)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode image with animation check", e)
        null
    }
}

/**
 * Extract raw image bytes from fields JSON.
 *
 * IMPORTANT: Call this from a background thread (Dispatchers.IO).
 *
 * @param fieldsJson The message's fields JSON containing the image data
 * @return Raw image bytes, or null if not found
 */
@Suppress("ReturnCount")
private fun extractImageBytes(fieldsJson: String?): ByteArray? {
    if (fieldsJson == null) return null

    return try {
        val fields = JSONObject(fieldsJson)
        val field6 = fields.opt("6") ?: return null

        val hexImageData: String =
            when {
                field6 is JSONObject && field6.has(FILE_REF_KEY) -> {
                    val filePath = field6.getString(FILE_REF_KEY)
                    loadAttachmentFromDisk(filePath) ?: return null
                }
                field6 is String && field6.isNotEmpty() -> field6
                else -> return null
            }

        hexStringToByteArray(hexImageData)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to extract image bytes", e)
        null
    }
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

/**
 * Check if the message has a file attachments field (type 5) in its JSON.
 * This is a fast check that doesn't decode anything.
 * Returns false for invalid JSON (malformed messages should not show files).
 */
@Suppress("SwallowedException") // Invalid JSON is expected to fail silently here
private fun hasFileAttachmentsField(fieldsJson: String?): Boolean {
    if (fieldsJson == null) return false
    return try {
        val fields = JSONObject(fieldsJson)
        val field5 = fields.opt("5")
        // File attachments are always a JSON array (from Sideband or our app)
        field5 is JSONArray && field5.length() > 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Check if the message has a pending file notification in field 16.
 * This indicates the sender's file is coming via propagation relay.
 */
@Suppress("SwallowedException") // Invalid JSON is expected to fail silently here
private fun hasPendingFileNotification(fieldsJson: String?): Boolean {
    if (fieldsJson == null) return false
    return fieldsJson.contains("pending_file_notification")
}

/**
 * Parse file attachment metadata from LXMF field 5.
 *
 * Supports two formats:
 * 1. Inline from Sideband: "5": [{"filename": "doc.pdf", "data": "hex...", "size": 12345}, ...]
 * 2. Optimized format: "5": [{"filename": "doc.pdf", "size": 12345, "_data_ref": "/path"}, ...]
 *    (metadata inline, file data on disk per-file - faster for large files)
 *
 * This is safe to call on the main thread because it only parses metadata,
 * not the actual file data. The hex "data" field is skipped during parsing.
 *
 * @param fieldsJson The message's fields JSON containing file attachment data
 * @return List of FileAttachmentUi with metadata, or empty list if parsing fails
 */
@Suppress("SwallowedException", "ReturnCount") // Invalid JSON is expected to fail silently
private fun parseFileAttachments(fieldsJson: String?): List<FileAttachmentUi> {
    if (fieldsJson == null) return emptyList()

    return try {
        val fields = JSONObject(fieldsJson)
        val field5 = fields.optJSONArray("5") ?: return emptyList()
        // Metadata is always inline (fast - no disk I/O for metadata parsing)
        parseFileAttachmentsArray(field5)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse file attachments", e)
        emptyList()
    }
}

/**
 * Parse a JSONArray of file attachments into FileAttachmentUi objects.
 *
 * Expected format: [{"filename": "doc.pdf", "size": 12345, "data": "hex..."}, ...]
 *
 * @param attachmentsArray JSONArray containing file attachment objects
 * @return List of FileAttachmentUi with metadata
 */
private fun parseFileAttachmentsArray(attachmentsArray: JSONArray): List<FileAttachmentUi> {
    val result = mutableListOf<FileAttachmentUi>()

    for (i in 0 until attachmentsArray.length()) {
        try {
            val attachment = attachmentsArray.getJSONObject(i)
            val filename = attachment.optString("filename", "unknown")
            val sizeBytes = attachment.optInt("size", 0)
            val mimeType = FileUtils.getMimeTypeFromFilename(filename)

            result.add(
                FileAttachmentUi(
                    filename = filename,
                    sizeBytes = sizeBytes,
                    mimeType = mimeType,
                    index = i,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse file attachment at index $i", e)
            // Skip this attachment and continue with the rest
        }
    }

    return result
}

/**
 * Marker key for per-file data reference (optimized format).
 */
private const val DATA_REF_KEY = "_data_ref"

/**
 * Load file attachment data by index.
 *
 * Supports two formats:
 * 1. Inline data from Sideband: {"data": "hex..."}
 * 2. Optimized format: {"_data_ref": "/path/to/5_0"} (per-file disk storage)
 *
 * IMPORTANT: This performs disk I/O and may return large byte arrays.
 * Must be called from a background thread.
 *
 * @param fieldsJson The message's fields JSON containing file attachment data
 * @param index The index of the file attachment to load
 * @return File data as bytes, or null if not found or loading fails
 */
@Suppress("ReturnCount")
fun loadFileAttachmentData(
    fieldsJson: String?,
    index: Int,
): ByteArray? {
    if (fieldsJson == null) return null

    return try {
        val fields = JSONObject(fieldsJson)
        val attachmentsArray = fields.optJSONArray("5") ?: return null

        if (index < 0 || index >= attachmentsArray.length()) {
            Log.w(TAG, "File attachment index out of bounds: $index (array size: ${attachmentsArray.length()})")
            return null
        }

        val attachment = attachmentsArray.getJSONObject(index)

        // Optimized format: data stored per-file on disk
        if (attachment.has(DATA_REF_KEY)) {
            val filePath = attachment.getString(DATA_REF_KEY)
            val hexData = loadAttachmentFromDisk(filePath) ?: return null
            return hexStringToByteArray(hexData).also {
                Log.d(TAG, "Loaded file attachment at index $index from disk (${it.size} bytes)")
            }
        }

        // Inline data format (from Sideband or small files)
        val hexData = attachment.optString("data", "")
        if (hexData.isEmpty()) {
            Log.w(TAG, "File attachment at index $index has no data")
            return null
        }

        // Convert hex string to bytes efficiently (avoid chunked/map overhead for large files)
        hexStringToByteArray(hexData)
            .also {
                Log.d(TAG, "Loaded file attachment at index $index inline (${it.size} bytes)")
            }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load file attachment data at index $index", e)
        null
    }
}

/**
 * Data class for file attachment metadata.
 */
data class FileAttachmentInfo(
    val filename: String,
    val mimeType: String,
)

/**
 * Load file attachment metadata (filename and MIME type) by index.
 *
 * This is fast because metadata is always inline - no disk I/O needed.
 *
 * @param fieldsJson The message's fields JSON containing file attachment data
 * @param index The index of the file attachment
 * @return FileAttachmentInfo or null if not found
 */
@Suppress("ReturnCount") // Early returns for null checks improve readability
fun loadFileAttachmentMetadata(
    fieldsJson: String?,
    index: Int,
): FileAttachmentInfo? {
    if (fieldsJson == null) return null

    return try {
        val fields = JSONObject(fieldsJson)
        val attachmentsArray = fields.optJSONArray("5") ?: return null

        if (index < 0 || index >= attachmentsArray.length()) {
            return null
        }

        val attachment = attachmentsArray.getJSONObject(index)
        val filename = attachment.optString("filename", "unknown")
        val mimeType = FileUtils.getMimeTypeFromFilename(filename)

        FileAttachmentInfo(filename = filename, mimeType = mimeType)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load file attachment metadata at index $index", e)
        null
    }
}

/**
 * Efficiently convert a hex string to byte array.
 * Uses direct array allocation and character arithmetic instead of
 * chunked/map which creates many intermediate objects.
 */
private fun hexStringToByteArray(hex: String): ByteArray {
    val len = hex.length
    val result = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val high = Character.digit(hex[i], 16)
        val low = Character.digit(hex[i + 1], 16)
        result[i / 2] = ((high shl 4) or low).toByte()
        i += 2
    }
    return result
}
