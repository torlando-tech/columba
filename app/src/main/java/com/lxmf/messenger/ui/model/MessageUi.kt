package com.lxmf.messenger.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * UI model for messages with pre-decoded images.
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
     * Raw LXMF fields JSON. Included when hasImageAttachment is true to enable
     * async image loading. Null for messages without image attachments.
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
)
