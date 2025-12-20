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
)

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
