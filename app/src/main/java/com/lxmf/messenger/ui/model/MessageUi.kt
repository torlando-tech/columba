package com.lxmf.messenger.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * UI model for messages with pre-decoded images and file attachments.
 *
 * This is a wrapper around the domain Message model that includes
 * pre-decoded image data to avoid expensive decoding during composition.
 *
 * @Immutable annotation enables Compose skippability optimizations:
 * - Items won't recompose unless data actually changes
 * - Reduces recomposition storms during scroll
 * - Critical for smooth 60 FPS scrolling performance
 */
@Immutable
data class MessageUi(
    val id: String,
    val destinationHash: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: String,
    /**
     * Pre-decoded image bitmap. If the message contains an LXMF image field (type 6),
     * it's decoded asynchronously and cached in ImageCache.
     *
     * This avoids expensive hex parsing and BitmapFactory.decodeByteArray() calls
     * during composition, which was the primary cause of scroll lag.
     *
     * The decoding happens on IO threads and is retrieved from cache during composition.
     */
    val decodedImage: ImageBitmap? = null,
    /**
     * Indicates whether this message has an image attachment that needs to be decoded.
     * When true but decodedImage is null, the UI should show a loading placeholder
     * while the image is being decoded asynchronously.
     */
    val hasImageAttachment: Boolean = false,
    /**
     * Raw image bytes for animated GIF support.
     * When isAnimatedImage is true, this contains the GIF bytes for Coil to render.
     * For static images, decodedImage is used instead for efficiency.
     */
    val imageData: ByteArray? = null,
    /**
     * Indicates whether the image is animated (GIF).
     * When true, imageData should be used with Coil for animated rendering.
     * When false, decodedImage (static bitmap) should be used for efficiency.
     */
    val isAnimatedImage: Boolean = false,
    /**
     * Raw LXMF fields JSON. Included when hasImageAttachment or hasFileAttachments is true
     * to enable async loading. Null for messages without attachments.
     */
    val fieldsJson: String? = null,
    /**
     * Delivery method used when sending: "opportunistic", "direct", or "propagated".
     * Null for received messages or messages sent before this feature was added.
     */
    val deliveryMethod: String? = null,
    /**
     * Error message if delivery failed (when status == "failed").
     * Null for successful deliveries or messages without errors.
     */
    val errorMessage: String? = null,
    /**
     * List of file attachments (LXMF field 5).
     * Each attachment contains metadata (filename, size, MIME type) for display.
     * Actual file data is loaded on-demand when user taps to save/open.
     */
    val fileAttachments: List<FileAttachmentUi> = emptyList(),
    /**
     * Indicates whether this message has file attachments.
     * Used to quickly determine if file attachment UI should be rendered.
     */
    val hasFileAttachments: Boolean = false,
    /**
     * ID of the message this is replying to, if any.
     * Extracted from LXMF field 16 {"reply_to": "message_id"}.
     */
    val replyToMessageId: String? = null,
    /**
     * Preview data for the message being replied to.
     * Loaded asynchronously from the database. Null if not a reply or not yet loaded.
     */
    val replyPreview: ReplyPreviewUi? = null,
    /**
     * List of emoji reactions on this message.
     * Each reaction contains the emoji and list of sender hashes who reacted with it.
     * Parsed from LXMF Field 16 reactions map (emoji to sender hash list).
     */
    val reactions: List<ReactionUi> = emptyList(),
    /**
     * Hop count when message was received.
     * Null for sent messages or messages received before this feature was added.
     * 0 means direct delivery, higher numbers indicate multi-hop routing.
     */
    val receivedHopCount: Int? = null,
    /**
     * Interface name through which message was received.
     * Examples: "AutoInterface", "TCPClient", "AndroidBle", "RNodeInterface".
     * Null for sent messages or messages received before this feature was added.
     */
    val receivedInterface: String? = null,
    /**
     * RSSI in dBm when message was received via radio interface (RNode, BLE).
     * Typically ranges from -30 (excellent) to -120 (very weak).
     * Null for TCP/AutoInterface, sent messages, or pre-feature messages.
     */
    val receivedRssi: Int? = null,
    /**
     * SNR in dB when message was received via radio interface (RNode only).
     * Typically ranges from -20 (very poor) to +20 (excellent).
     * Null for BLE/TCP/AutoInterface, sent messages, or pre-feature messages.
     */
    val receivedSnr: Float? = null,
) {
    /**
     * Whether this message should be displayed as a standalone media item without a bubble.
     *
     * Returns true when the message is:
     * - An animated GIF (isAnimatedImage = true)
     * - Has image data loaded (imageData != null)
     * - Has no text content (content is blank)
     * - Has no file attachments
     * - Is not a reply to another message
     *
     * This allows GIF-only messages to be displayed large without a bubble background,
     * similar to how Signal displays media-only messages.
     */
    val isMediaOnlyMessage: Boolean
        get() =
            isAnimatedImage &&
                imageData != null &&
                content.isBlank() &&
                !hasFileAttachments &&
                replyPreview == null

    /**
     * Whether this message is a pending file notification.
     *
     * These are lightweight messages sent when a file message falls back to propagation,
     * notifying the recipient that a file is coming via relay.
     */
    val isPendingFileNotification: Boolean
        get() = fieldsJson?.contains("pending_file_notification") == true

    /**
     * Whether this notification has been superseded by the actual file arrival.
     *
     * When the file message arrives, the notification is marked as superseded
     * and should no longer be displayed.
     */
    val isSuperseded: Boolean
        get() = fieldsJson?.contains("\"superseded\":true") == true

    /**
     * Extract pending file info if this is a notification message.
     *
     * Parses the pending_file_notification from Field 16 to get file details.
     * Returns null if not a pending file notification or parsing fails.
     */
    @Suppress("SwallowedException") // JSON parse failures expected, return null
    val pendingFileInfo: PendingFileInfo?
        get() {
            if (!isPendingFileNotification || fieldsJson == null) return null
            return try {
                val json = org.json.JSONObject(fieldsJson)
                val field16 = json.optJSONObject("16") ?: return null
                val notification = field16.optJSONObject("pending_file_notification") ?: return null
                PendingFileInfo(
                    originalMessageId = notification.optString("original_message_id", ""),
                    filename = notification.optString("filename", "file"),
                    fileCount = notification.optInt("file_count", 1),
                    totalSize = notification.optLong("total_size", 0),
                )
            } catch (e: Exception) {
                null
            }
        }
}

/**
 * UI representation of a file attachment.
 *
 * Contains metadata for display purposes. The actual file bytes are loaded
 * on-demand when the user taps to save or open the file, avoiding memory
 * pressure from holding large attachments in memory.
 *
 * @property filename The original filename including extension
 * @property sizeBytes The size of the file in bytes
 * @property mimeType The MIME type for icon selection and file handling
 * @property index Position in the attachment list for loading the data later
 */
@Immutable
data class FileAttachmentUi(
    val filename: String,
    val sizeBytes: Int,
    val mimeType: String,
    val index: Int,
)

/**
 * UI representation of a reply preview.
 *
 * Contains the minimal data needed to display a reply preview in a message bubble.
 * This includes sender info, truncated content, and attachment indicators.
 *
 * @property messageId The ID of the original message being replied to
 * @property senderName "You" if from current user, otherwise the peer's display name
 * @property contentPreview Truncated content (max 100 chars) for preview
 * @property hasImage Whether the original message has an image attachment
 * @property hasFileAttachment Whether the original message has file attachments
 * @property firstFileName First filename if file attachments present
 */
@Immutable
data class ReplyPreviewUi(
    val messageId: String,
    val senderName: String,
    val contentPreview: String,
    val hasImage: Boolean = false,
    val hasFileAttachment: Boolean = false,
    val firstFileName: String? = null,
)

/**
 * UI representation of an emoji reaction on a message.
 *
 * Represents a single emoji type with all senders who reacted with it.
 * Parsed from LXMF Field 16 reactions dictionary.
 *
 * @property emoji The emoji character (e.g., "👍", "❤️")
 * @property senderHashes List of destination hashes of users who sent this reaction
 * @property count Number of users who reacted with this emoji (derived from senderHashes.size)
 */
@Immutable
data class ReactionUi(
    val emoji: String,
    val senderHashes: List<String>,
    val count: Int = senderHashes.size,
)

/**
 * UI representation of a pending file notification.
 *
 * This is displayed when a sender's file message fell back to propagation,
 * notifying the recipient that a file is arriving via relay.
 *
 * @property originalMessageId The hash of the original file message
 * @property filename The first filename being sent
 * @property fileCount Total number of files in the attachment
 * @property totalSize Total size of all attachments in bytes
 */
@Immutable
data class PendingFileInfo(
    val originalMessageId: String,
    val filename: String,
    val fileCount: Int,
    val totalSize: Long,
)
