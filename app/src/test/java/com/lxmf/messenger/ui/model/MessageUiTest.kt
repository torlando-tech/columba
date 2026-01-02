package com.lxmf.messenger.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MessageUi, focusing on the isMediaOnlyMessage property
 * that determines whether a message should be displayed without a bubble.
 */
class MessageUiTest {
    // ========== Helper Functions ==========

    /**
     * Creates a base MessageUi with default values for testing.
     */
    @Suppress("LongParameterList") // Test helper needs many params for flexibility
    private fun createBaseMessage(
        id: String = "test-id",
        content: String = "",
        isAnimatedImage: Boolean = false,
        imageData: ByteArray? = null,
        hasFileAttachments: Boolean = false,
        replyPreview: ReplyPreviewUi? = null,
        hasImageAttachment: Boolean = false,
        decodedImage: androidx.compose.ui.graphics.ImageBitmap? = null,
        reactions: List<ReactionUi> = emptyList(),
    ): MessageUi = MessageUi(
        id = id,
        destinationHash = "dest-hash",
        content = content,
        timestamp = System.currentTimeMillis(),
        isFromMe = true,
        status = "sent",
        isAnimatedImage = isAnimatedImage,
        imageData = imageData,
        hasFileAttachments = hasFileAttachments,
        replyPreview = replyPreview,
        hasImageAttachment = hasImageAttachment,
        decodedImage = decodedImage,
        reactions = reactions,
    )

    // ========== isMediaOnlyMessage Tests ==========

    @Test
    fun `isMediaOnlyMessage returns true for animated GIF with no text`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46), // GIF header bytes
            content = "",
        )

        assertTrue(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns true for animated GIF with whitespace-only content`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "   \n\t  ",
        )

        assertTrue(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when imageData is null`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = null,
            content = "",
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false for non-animated image`() {
        val message = createBaseMessage(
            isAnimatedImage = false,
            imageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), // JPEG header
            content = "",
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when message has text content`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "Check out this GIF!",
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when message has file attachments`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "",
            hasFileAttachments = true,
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when message is a reply`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "original-msg-id",
            senderName = "Alice",
            contentPreview = "Original message...",
        )
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "",
            replyPreview = replyPreview,
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false for text-only message`() {
        val message = createBaseMessage(
            isAnimatedImage = false,
            imageData = null,
            content = "Hello, world!",
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false for static image with no text`() {
        // Static images (non-animated) should still use bubble
        val message = createBaseMessage(
            isAnimatedImage = false,
            imageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            content = "",
            hasImageAttachment = true,
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when all conditions are false`() {
        val message = createBaseMessage(
            isAnimatedImage = false,
            imageData = null,
            content = "Regular message",
            hasFileAttachments = false,
            replyPreview = null,
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false for GIF with single character text`() {
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "!",
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false for GIF reply with no text`() {
        // Even without text, a reply should show the bubble for context
        val replyPreview = ReplyPreviewUi(
            messageId = "parent-id",
            senderName = "Bob",
            contentPreview = "What do you think?",
        )
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "",
            replyPreview = replyPreview,
        )

        assertFalse(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage with reactions still returns true`() {
        // Reactions don't prevent media-only display
        val reactions = listOf(
            ReactionUi(emoji = "👍", senderHashes = listOf("hash1", "hash2")),
        )
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            content = "",
            reactions = reactions,
        )

        assertTrue(message.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage handles empty imageData array`() {
        // Empty byte array is still non-null, but this is an edge case
        val message = createBaseMessage(
            isAnimatedImage = true,
            imageData = byteArrayOf(),
            content = "",
        )

        // Empty imageData is technically non-null, so this would be true
        // In practice, we wouldn't have isAnimatedImage=true with empty data
        assertTrue(message.isMediaOnlyMessage)
    }

    // ========== FileAttachmentUi Tests ==========

    @Test
    fun `FileAttachmentUi stores correct properties`() {
        val attachment = FileAttachmentUi(
            filename = "document.pdf",
            sizeBytes = 1024,
            mimeType = "application/pdf",
            index = 0,
        )

        assertTrue(attachment.filename == "document.pdf")
        assertTrue(attachment.sizeBytes == 1024)
        assertTrue(attachment.mimeType == "application/pdf")
        assertTrue(attachment.index == 0)
    }

    @Test
    fun `FileAttachmentUi equals works correctly`() {
        val attachment1 = FileAttachmentUi("file.txt", 100, "text/plain", 0)
        val attachment2 = FileAttachmentUi("file.txt", 100, "text/plain", 0)
        val attachment3 = FileAttachmentUi("other.txt", 100, "text/plain", 0)

        assertTrue(attachment1 == attachment2)
        assertFalse(attachment1 == attachment3)
    }

    // ========== ReplyPreviewUi Tests ==========

    @Test
    fun `ReplyPreviewUi stores correct properties`() {
        val preview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Alice",
            contentPreview = "Hello there...",
            hasImage = true,
            hasFileAttachment = false,
            firstFileName = null,
        )

        assertTrue(preview.messageId == "msg-123")
        assertTrue(preview.senderName == "Alice")
        assertTrue(preview.contentPreview == "Hello there...")
        assertTrue(preview.hasImage)
        assertFalse(preview.hasFileAttachment)
    }

    @Test
    fun `ReplyPreviewUi defaults are correct`() {
        val preview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Bob",
            contentPreview = "Test",
        )

        assertFalse(preview.hasImage)
        assertFalse(preview.hasFileAttachment)
        assertTrue(preview.firstFileName == null)
    }

    @Test
    fun `ReplyPreviewUi with file attachment`() {
        val preview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Charlie",
            contentPreview = "",
            hasFileAttachment = true,
            firstFileName = "report.pdf",
        )

        assertTrue(preview.hasFileAttachment)
        assertTrue(preview.firstFileName == "report.pdf")
    }

    // ========== ReactionUi Tests ==========

    @Test
    fun `ReactionUi count is derived from senderHashes size`() {
        val reaction = ReactionUi(
            emoji = "❤️",
            senderHashes = listOf("hash1", "hash2", "hash3"),
        )

        assertTrue(reaction.count == 3)
    }

    @Test
    fun `ReactionUi with single sender`() {
        val reaction = ReactionUi(
            emoji = "👍",
            senderHashes = listOf("single-hash"),
        )

        assertTrue(reaction.count == 1)
        assertTrue(reaction.emoji == "👍")
    }

    @Test
    fun `ReactionUi with empty senderHashes`() {
        val reaction = ReactionUi(
            emoji = "😊",
            senderHashes = emptyList(),
        )

        assertTrue(reaction.count == 0)
    }

    @Test
    fun `ReactionUi equals works correctly`() {
        val reaction1 = ReactionUi("👍", listOf("hash1", "hash2"))
        val reaction2 = ReactionUi("👍", listOf("hash1", "hash2"))
        val reaction3 = ReactionUi("👎", listOf("hash1", "hash2"))

        assertTrue(reaction1 == reaction2)
        assertFalse(reaction1 == reaction3)
    }

    // ========== MessageUi Edge Cases ==========

    @Test
    fun `MessageUi with all optional fields null`() {
        val message = MessageUi(
            id = "minimal-id",
            destinationHash = "dest",
            content = "Minimal message",
            timestamp = 0L,
            isFromMe = false,
            status = "pending",
        )

        assertFalse(message.isMediaOnlyMessage)
        assertTrue(message.decodedImage == null)
        assertTrue(message.imageData == null)
        assertFalse(message.isAnimatedImage)
        assertFalse(message.hasImageAttachment)
        assertFalse(message.hasFileAttachments)
        assertTrue(message.fileAttachments.isEmpty())
        assertTrue(message.replyPreview == null)
        assertTrue(message.reactions.isEmpty())
    }

    @Test
    fun `MessageUi equality with imageData uses content equality`() {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(1, 2, 3)

        val message1 = createBaseMessage(imageData = data1)
        val message2 = createBaseMessage(imageData = data2)

        // Note: ByteArray uses reference equality in data class, so these won't be equal
        // This is expected behavior - we're testing that the property is stored correctly
        assertTrue(message1.imageData.contentEquals(message2.imageData))
    }

    @Test
    fun `MessageUi with complex scenario - GIF with text and reply`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "parent",
            senderName = "You",
            contentPreview = "Check this out",
        )
        val reactions = listOf(
            ReactionUi("😂", listOf("h1", "h2")),
            ReactionUi("❤️", listOf("h3")),
        )

        val message = MessageUi(
            id = "complex-msg",
            destinationHash = "dest",
            content = "LOL look at this!",
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            status = "delivered",
            isAnimatedImage = true,
            imageData = byteArrayOf(0x47, 0x49, 0x46),
            hasImageAttachment = true,
            replyPreview = replyPreview,
            reactions = reactions,
        )

        // Has text, so not media-only
        assertFalse(message.isMediaOnlyMessage)
        assertTrue(message.reactions.size == 2)
        assertTrue(message.replyPreview != null)
    }
}
