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
     * it's decoded in the repository layer (off the UI thread) and cached here.
     *
     * This avoids expensive hex parsing and BitmapFactory.decodeByteArray() calls
     * during composition, which was the primary cause of scroll lag.
     */
    val decodedImage: ImageBitmap? = null,
)
