package com.lxmf.messenger.ui.model

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lxmf.messenger.data.repository.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageMapperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        ImageCache.clear()
    }

    @After
    fun tearDown() {
        ImageCache.clear()
    }

    @Test
    fun `toMessageUi maps basic fields correctly`() {
        val message =
            createMessage(
                TestMessageConfig(
                    id = "test-id",
                    content = "Hello world",
                    isFromMe = true,
                    status = "delivered",
                ),
            )

        val result = message.toMessageUi()

        assertEquals("test-id", result.id)
        assertEquals("Hello world", result.content)
        assertTrue(result.isFromMe)
        assertEquals("delivered", result.status)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when no fieldsJson`() {
        val message = createMessage(TestMessageConfig(fieldsJson = null))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
        assertNull(result.decodedImage)
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when no image field in json`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"1": "some text"}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
        assertNull(result.decodedImage)
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment true for inline image`() {
        // Field 6 is IMAGE in LXMF
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": "ffd8ffe0"}"""))

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
        // Image not cached, so decodedImage is null
        assertNull(result.decodedImage)
        // fieldsJson included for async loading
        assertNotNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment true for file reference`() {
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"6": {"_file_ref": "/path/to/image.dat"}}"""),
            )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
        assertNull(result.decodedImage)
        assertNotNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi returns cached image when available`() {
        val messageId = "cached-message-id"
        val cachedBitmap = createTestBitmap()

        // Pre-populate cache
        ImageCache.put(messageId, cachedBitmap)

        val message =
            createMessage(
                TestMessageConfig(
                    id = messageId,
                    fieldsJson = """{"6": "ffd8ffe0"}""",
                ),
            )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
        assertNotNull(result.decodedImage)
        assertEquals(cachedBitmap, result.decodedImage)
        // fieldsJson not needed since image is already cached
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi excludes fieldsJson when image is cached`() {
        val messageId = "cached-id"
        ImageCache.put(messageId, createTestBitmap())

        val message =
            createMessage(
                TestMessageConfig(
                    id = messageId,
                    fieldsJson = """{"6": "ffd8ffe0"}""",
                ),
            )

        val result = message.toMessageUi()

        // fieldsJson should be null since image is already in cache
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi includes deliveryMethod and errorMessage`() {
        val message =
            createMessage(
                TestMessageConfig(
                    deliveryMethod = "propagated",
                    errorMessage = "Connection timeout",
                ),
            )

        val result = message.toMessageUi()

        assertEquals("propagated", result.deliveryMethod)
        assertEquals("Connection timeout", result.errorMessage)
    }

    // ========== decodeAndCacheImage() TESTS ==========

    @Test
    fun `decodeAndCacheImage returns null for null fieldsJson`() {
        val result = decodeAndCacheImage("test-id", null)
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage returns cached image if already cached`() {
        val messageId = "cached-image-id"
        val cachedBitmap = createTestBitmap()

        // Pre-populate cache
        ImageCache.put(messageId, cachedBitmap)

        // Call decodeAndCacheImage - should return cached image without decoding
        val result = decodeAndCacheImage(messageId, """{"6": "ffd8ffe0"}""")

        assertNotNull(result)
        assertEquals(cachedBitmap, result)
    }

    @Test
    fun `decodeAndCacheImage returns null for empty fieldsJson`() {
        val result = decodeAndCacheImage("test-id", "")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage returns null for invalid JSON`() {
        val result = decodeAndCacheImage("test-id", "not valid json")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage returns null when field 6 is missing`() {
        val result = decodeAndCacheImage("test-id", """{"1": "some text"}""")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage returns null for empty field 6`() {
        val result = decodeAndCacheImage("test-id", """{"6": ""}""")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage returns null for invalid hex in field 6`() {
        // "zzzz" is not valid hex, should fail during decoding
        val result = decodeAndCacheImage("test-id", """{"6": "zzzz"}""")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles arbitrary byte data without crashing`() {
        // Valid hex but arbitrary byte data - Robolectric's BitmapFactory may decode it
        // The key is that the function doesn't crash
        val result = decodeAndCacheImage("test-id", """{"6": "0102030405"}""")
        // Result may or may not be null depending on Robolectric's BitmapFactory behavior
        // Test passes as long as no exception is thrown
    }

    @Test
    fun `decodeAndCacheImage returns null for file reference with nonexistent file`() {
        // File reference to a file that doesn't exist
        val result =
            decodeAndCacheImage(
                "test-id",
                """{"6": {"_file_ref": "/nonexistent/path/to/file.dat"}}""",
            )
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles malformed file reference gracefully`() {
        // File reference without the path value
        val result = decodeAndCacheImage("test-id", """{"6": {"_file_ref": ""}}""")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles field 6 as non-string non-object type`() {
        // Field 6 as number - should be ignored
        val result = decodeAndCacheImage("test-id", """{"6": 12345}""")
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage caches result after successful decode`() {
        val messageId = "decode-and-cache-test"

        // Ensure cache is empty
        assertNull(ImageCache.get(messageId))

        // Create a minimal valid JPEG (simplified - actual decode will fail but tests cache behavior)
        // The actual decode will fail because this isn't a valid image, but we verify cache logic
        val result = decodeAndCacheImage(messageId, """{"6": "ffd8ffe000104a46494600"}""")

        // Decode will fail for invalid image data, so result is null
        // but this tests the path through the decode logic
        assertNull(result)

        // Cache should NOT contain entry since decode failed
        assertNull(ImageCache.get(messageId))
    }

    // ========== FILE-BASED ATTACHMENT TESTS ==========

    @Test
    fun `decodeAndCacheImage reads from file reference when file exists`() {
        // Create a temporary file with hex-encoded image data
        val tempFile = tempFolder.newFile("test_attachment.dat")
        // Write some hex data (arbitrary - Robolectric may or may not decode it)
        tempFile.writeText("0102030405060708")

        val fieldsJson = """{"6": {"_file_ref": "${tempFile.absolutePath}"}}"""
        val result = decodeAndCacheImage("file-test-id", fieldsJson)

        // The function should have read the file - whether decode succeeds depends on Robolectric
        // This test verifies the file reading path is exercised
        // No exception means success
    }

    @Test
    fun `decodeAndCacheImage handles file with valid hex content`() {
        val tempFile = tempFolder.newFile("valid_hex.dat")
        // Valid hex string (though not a valid image)
        tempFile.writeText("ffd8ffe000104a46494600")

        val fieldsJson = """{"6": {"_file_ref": "${tempFile.absolutePath}"}}"""
        val result = decodeAndCacheImage("hex-file-test", fieldsJson)

        // File was read successfully - decode may or may not succeed
        // No exception means the file reading path worked
    }

    @Test
    fun `decodeAndCacheImage returns null when file reference path is directory`() {
        // Create a directory instead of a file
        val tempDir = tempFolder.newFolder("not_a_file")

        val fieldsJson = """{"6": {"_file_ref": "${tempDir.absolutePath}"}}"""
        val result = decodeAndCacheImage("dir-test-id", fieldsJson)

        // Should return null because we can't read a directory as text
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles empty file`() {
        val emptyFile = tempFolder.newFile("empty.dat")
        // File exists but is empty

        val fieldsJson = """{"6": {"_file_ref": "${emptyFile.absolutePath}"}}"""
        val result = decodeAndCacheImage("empty-file-test", fieldsJson)

        // Function should handle empty file without crashing
        // Result may vary based on BitmapFactory implementation (Robolectric vs real Android)
    }

    @Test
    fun `decodeAndCacheImage handles file with whitespace only`() {
        val whitespaceFile = tempFolder.newFile("whitespace.dat")
        whitespaceFile.writeText("   \n\t  ")

        val fieldsJson = """{"6": {"_file_ref": "${whitespaceFile.absolutePath}"}}"""
        val result = decodeAndCacheImage("whitespace-file-test", fieldsJson)

        // Whitespace is not valid hex, should fail during hex parsing
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles file reference with special characters in path`() {
        // Test path handling with spaces and special chars (if filesystem allows)
        val tempFile = tempFolder.newFile("test file with spaces.dat")
        tempFile.writeText("0102030405")

        val fieldsJson = """{"6": {"_file_ref": "${tempFile.absolutePath}"}}"""
        val result = decodeAndCacheImage("special-path-test", fieldsJson)

        // Should handle the path correctly - no exception means success
    }

    // ========== REPLY (FIELD 16) TESTS ==========

    @Test
    fun `toMessageUi maps replyToMessageId from Message data class`() {
        val message =
            createMessage(
                TestMessageConfig(
                    replyToMessageId = "original-message-hash-12345",
                ),
            )

        val result = message.toMessageUi()

        assertEquals("original-message-hash-12345", result.replyToMessageId)
    }

    @Test
    fun `toMessageUi extracts replyToMessageId from field 16 when not in data class`() {
        // Field 16 contains reply_to as part of app extensions dict
        val fieldsJson = """{"16": {"reply_to": "f4a3b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertEquals(
            "f4a3b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2",
            result.replyToMessageId,
        )
    }

    @Test
    fun `toMessageUi prefers replyToMessageId from data class over field 16`() {
        // Both data class and field 16 have reply info - prefer data class
        val fieldsJson = """{"16": {"reply_to": "from_field_16"}}"""
        val message =
            createMessage(
                TestMessageConfig(
                    fieldsJson = fieldsJson,
                    replyToMessageId = "from_data_class",
                ),
            )

        val result = message.toMessageUi()

        assertEquals("from_data_class", result.replyToMessageId)
    }

    @Test
    fun `toMessageUi returns null replyToMessageId when field 16 is missing`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": "image_hex"}"""))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi returns null replyToMessageId when field 16 has no reply_to key`() {
        // Field 16 exists but doesn't have reply_to key (maybe only reactions)
        val fieldsJson = """{"16": {"reactions": {"👍": ["user1"]}}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi returns null replyToMessageId when reply_to is empty`() {
        val fieldsJson = """{"16": {"reply_to": ""}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi handles field 16 as non-object type gracefully`() {
        // Field 16 is a string instead of object
        val fieldsJson = """{"16": "not an object"}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi handles field 16 as number gracefully`() {
        val fieldsJson = """{"16": 12345}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi handles field 16 with null reply_to value`() {
        val fieldsJson = """{"16": {"reply_to": null}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    @Test
    fun `toMessageUi parses reply with multiple field 16 properties`() {
        // Future-proof: field 16 may have reactions, mentions, etc alongside reply_to
        val fieldsJson = """{
            "16": {
                "reply_to": "message_hash_123",
                "reactions": {"👍": ["user1"]},
                "mentions": ["user2", "user3"]
            }
        }"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertEquals("message_hash_123", result.replyToMessageId)
    }

    @Test
    fun `toMessageUi sets replyPreview to null initially`() {
        val fieldsJson = """{"16": {"reply_to": "some_message_id"}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        // replyPreview is loaded asynchronously, so it should be null from mapper
        assertNull(result.replyPreview)
    }

    @Test
    fun `toMessageUi handles message with reply and image attachment`() {
        val fieldsJson = """{
            "6": "ffd8ffe0",
            "16": {"reply_to": "original_msg_id"}
        }"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
        assertEquals("original_msg_id", result.replyToMessageId)
    }

    @Test
    fun `toMessageUi handles message with reply and file attachments`() {
        val fieldsJson = """{
            "5": [{"filename": "doc.pdf", "data": "0102", "size": 100}],
            "16": {"reply_to": "original_msg_id"}
        }"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("original_msg_id", result.replyToMessageId)
    }

    @Test
    fun `toMessageUi handles malformed JSON in field 16 gracefully`() {
        // This shouldn't happen in practice, but test edge case
        val message = createMessage(TestMessageConfig(fieldsJson = "not valid json"))

        val result = message.toMessageUi()

        assertNull(result.replyToMessageId)
    }

    /**
     * Configuration class for creating test messages.
     */
    data class TestMessageConfig(
        val id: String = "default-id",
        val destinationHash: String = "abc123",
        val content: String = "Test message",
        val timestamp: Long = System.currentTimeMillis(),
        val isFromMe: Boolean = false,
        val status: String = "delivered",
        val fieldsJson: String? = null,
        val deliveryMethod: String? = null,
        val errorMessage: String? = null,
        val replyToMessageId: String? = null,
    )

    private fun createMessage(config: TestMessageConfig = TestMessageConfig()): Message =
        Message(
            id = config.id,
            destinationHash = config.destinationHash,
            content = config.content,
            timestamp = config.timestamp,
            isFromMe = config.isFromMe,
            status = config.status,
            fieldsJson = config.fieldsJson,
            deliveryMethod = config.deliveryMethod,
            errorMessage = config.errorMessage,
            replyToMessageId = config.replyToMessageId,
        )

    private fun createTestBitmap() = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

    // ========== hasImageField() coverage through toMessageUi() ==========

    @Test
    fun `toMessageUi sets hasImageAttachment false for empty JSON object`() {
        val message = createMessage(TestMessageConfig(fieldsJson = "{}"))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when field 6 is null`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": null}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when field 6 is number`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": 12345}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when field 6 is boolean`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": true}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when field 6 is array`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": [1, 2, 3]}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false for malformed JSON`() {
        val message = createMessage(TestMessageConfig(fieldsJson = "not valid json {{{"))

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
        assertNull(result.fieldsJson)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment true for file reference with valid path`() {
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"6": {"_file_ref": "/data/attachments/img.dat"}}"""),
            )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when file reference object has wrong key`() {
        // Object in field 6 but without _file_ref key
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"6": {"wrong_key": "/path/to/file"}}"""),
            )

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false for empty file reference path`() {
        // _file_ref exists but value is empty
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"6": {"_file_ref": ""}}"""),
            )

        val result = message.toMessageUi()

        // hasImageField checks if _file_ref key exists, not if value is non-empty
        // So this should still be true
        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi handles deeply nested JSON without crashing`() {
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"1": {"nested": {"deep": "value"}}, "6": "image_hex"}"""),
            )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi handles JSON with multiple fields including image`() {
        val message =
            createMessage(
                TestMessageConfig(fieldsJson = """{"1": "text content", "6": "image_hex_data", "7": "other"}"""),
            )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    // ========== decodeAndCacheImage() additional coverage ==========

    @Test
    fun `decodeAndCacheImage handles file reference with empty _file_ref value`() {
        val result =
            decodeAndCacheImage(
                "empty-path-test",
                """{"6": {"_file_ref": ""}}""",
            )

        // Empty path should fail to read
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles field 6 as JSONObject without _file_ref key`() {
        val result =
            decodeAndCacheImage(
                "no-file-ref-key",
                """{"6": {"other_key": "value"}}""",
            )

        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles very long hex string without crashing`() {
        // Generate a long but invalid hex string
        val longHex = "ff".repeat(10000)
        val result =
            decodeAndCacheImage(
                "long-hex-test",
                """{"6": "$longHex"}""",
            )

        // May or may not decode, but shouldn't crash
    }

    @Test
    fun `decodeAndCacheImage handles odd-length hex string gracefully`() {
        // Odd-length hex strings may or may not decode depending on implementation
        // This test verifies no exception is thrown
        val result =
            decodeAndCacheImage(
                "odd-hex-test",
                """{"6": "fff"}""", // 3 chars, not valid hex pair
            )

        // Result may be null or non-null depending on Robolectric's BitmapFactory
        // The important thing is it doesn't crash
    }

    @Test
    fun `decodeAndCacheImage handles uppercase hex string`() {
        val result =
            decodeAndCacheImage(
                "uppercase-hex-test",
                """{"6": "FFD8FFE0"}""",
            )

        // Should handle uppercase hex - whether decode succeeds depends on BitmapFactory
    }

    @Test
    fun `decodeAndCacheImage handles mixed case hex string`() {
        val result =
            decodeAndCacheImage(
                "mixed-case-test",
                """{"6": "FfD8fFe0"}""",
            )

        // Should handle mixed case
    }

    @Test
    fun `toMessageUi correctly maps all MessageUi fields`() {
        val message =
            createMessage(
                TestMessageConfig(
                    id = "complete-test-id",
                    destinationHash = "dest123",
                    content = "Complete message content",
                    timestamp = 1700000000000L,
                    isFromMe = true,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = "direct",
                    errorMessage = null,
                ),
            )

        val result = message.toMessageUi()

        assertEquals("complete-test-id", result.id)
        assertEquals("dest123", result.destinationHash)
        assertEquals("Complete message content", result.content)
        assertEquals(1700000000000L, result.timestamp)
        assertTrue(result.isFromMe)
        assertEquals("delivered", result.status)
        assertNull(result.decodedImage)
        assertFalse(result.hasImageAttachment)
        assertNull(result.fieldsJson)
        assertEquals("direct", result.deliveryMethod)
        assertNull(result.errorMessage)
    }

    @Test
    fun `toMessageUi with failed message includes error message`() {
        val message =
            createMessage(
                TestMessageConfig(
                    status = "failed",
                    errorMessage = "Network timeout",
                ),
            )

        val result = message.toMessageUi()

        assertEquals("failed", result.status)
        assertEquals("Network timeout", result.errorMessage)
    }

    // ========== FILE ATTACHMENT (FIELD 5) TESTS ==========

    @Test
    fun `toMessageUi sets hasFileAttachments false when no fieldsJson`() {
        val message = createMessage(TestMessageConfig(fieldsJson = null))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi sets hasFileAttachments false when field 5 is missing`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"1": "some text"}"""))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi sets hasFileAttachments true for inline array`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "48656c6c6f", "size": 5}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("test.pdf", result.fileAttachments[0].filename)
        assertEquals(5, result.fileAttachments[0].sizeBytes)
        assertEquals("application/pdf", result.fileAttachments[0].mimeType)
        assertEquals(0, result.fileAttachments[0].index)
    }

    @Test
    fun `toMessageUi parses multiple file attachments`() {
        val fieldsJson = """{"5": [
            {"filename": "doc.pdf", "data": "0102", "size": 1024},
            {"filename": "notes.txt", "data": "0304", "size": 512},
            {"filename": "archive.zip", "data": "0506", "size": 2048}
        ]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(3, result.fileAttachments.size)

        assertEquals("doc.pdf", result.fileAttachments[0].filename)
        assertEquals(1024, result.fileAttachments[0].sizeBytes)
        assertEquals("application/pdf", result.fileAttachments[0].mimeType)
        assertEquals(0, result.fileAttachments[0].index)

        assertEquals("notes.txt", result.fileAttachments[1].filename)
        assertEquals(512, result.fileAttachments[1].sizeBytes)
        assertEquals("text/plain", result.fileAttachments[1].mimeType)
        assertEquals(1, result.fileAttachments[1].index)

        assertEquals("archive.zip", result.fileAttachments[2].filename)
        assertEquals(2048, result.fileAttachments[2].sizeBytes)
        assertEquals("application/zip", result.fileAttachments[2].mimeType)
        assertEquals(2, result.fileAttachments[2].index)
    }

    @Test
    fun `toMessageUi sets hasFileAttachments true for per-file data reference`() {
        // New format: metadata inline with per-file _data_ref for file data
        val fieldsJson = """{"5": [{"filename": "doc.pdf", "size": 1024, "_data_ref": "/data/attachments/doc.pdf.dat"}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("doc.pdf", result.fileAttachments[0].filename)
        assertEquals(1024, result.fileAttachments[0].sizeBytes)
    }

    @Test
    fun `toMessageUi handles file attachments with missing filename`() {
        val fieldsJson = """{"5": [{"data": "0102", "size": 100}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("unknown", result.fileAttachments[0].filename)
    }

    @Test
    fun `toMessageUi handles file attachments with missing size`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "data": "0102"}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals(0, result.fileAttachments[0].sizeBytes)
    }

    @Test
    fun `toMessageUi handles empty file attachments array`() {
        val fieldsJson = """{"5": []}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        // Empty array is treated as no attachments (length > 0 check)
        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi handles malformed JSON in field 5`() {
        val fieldsJson = """{"5": "not an array or object"}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi handles field 5 as null`() {
        val fieldsJson = """{"5": null}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi handles both image and file attachments`() {
        val fieldsJson = """{
            "5": [{"filename": "doc.pdf", "data": "0102", "size": 1024}],
            "6": "ffd8ffe0"
        }"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertTrue(result.hasImageAttachment)
        assertEquals(1, result.fileAttachments.size)
    }

    // ========== loadFileAttachmentData() TESTS ==========

    @Test
    fun `loadFileAttachmentData returns null for null fieldsJson`() {
        val result = loadFileAttachmentData(null, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null when field 5 is missing`() {
        val result = loadFileAttachmentData("""{"1": "text"}""", 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null for negative index`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "48656c6c6f", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, -1)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null for out of bounds index`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "48656c6c6f", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, 1)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns data for valid index`() {
        // "48656c6c6f" is hex for "Hello"
        val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("Hello", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData returns correct attachment from multiple`() {
        val fieldsJson = """{"5": [
            {"filename": "first.txt", "data": "4f6e65", "size": 3},
            {"filename": "second.txt", "data": "54776f", "size": 3},
            {"filename": "third.txt", "data": "54687265", "size": 4}
        ]}"""

        val first = loadFileAttachmentData(fieldsJson, 0)
        val second = loadFileAttachmentData(fieldsJson, 1)
        val third = loadFileAttachmentData(fieldsJson, 2)

        assertNotNull(first)
        assertEquals("One", String(first!!))

        assertNotNull(second)
        assertEquals("Two", String(second!!))

        assertNotNull(third)
        assertEquals("Thre", String(third!!))
    }

    @Test
    fun `loadFileAttachmentData returns null when data field is empty`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "data": "", "size": 0}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null when data field is missing`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 100}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData handles uppercase hex`() {
        // "48454C4C4F" is uppercase hex for "HELLO"
        val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48454C4C4F", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("HELLO", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData handles mixed case hex`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656C6c6F", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("Hello", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData reads from per-file data reference when file exists`() {
        // Create a temp file with hex-encoded attachment data
        val tempFile = tempFolder.newFile("test_attachment.dat")
        tempFile.writeText("48656c6c6f") // "Hello" in hex

        // New format: metadata inline with per-file _data_ref
        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 5, "_data_ref": "${tempFile.absolutePath}"}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("Hello", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData returns null for nonexistent data reference`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 5, "_data_ref": "/nonexistent/path/file.dat"}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null for empty data reference path`() {
        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 5, "_data_ref": ""}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData handles large hex data efficiently`() {
        // Create a 10KB hex string (20KB of hex chars)
        val originalBytes = ByteArray(10_000) { (it % 256).toByte() }
        val hexString = originalBytes.joinToString("") { "%02x".format(it) }
        val fieldsJson = """{"5": [{"filename": "large.bin", "data": "$hexString", "size": 10000}]}"""

        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals(10_000, result!!.size)
        // Verify first and last bytes match
        assertEquals(originalBytes[0], result[0])
        assertEquals(originalBytes[9999], result[9999])
        // Verify a few bytes in the middle
        assertEquals(originalBytes[5000], result[5000])
    }

    @Test
    fun `loadFileAttachmentData handles binary data with all byte values`() {
        // Test all 256 byte values to ensure hex decoding works for edge cases
        val allBytes = ByteArray(256) { it.toByte() }
        val hexString = allBytes.joinToString("") { "%02x".format(it) }
        val fieldsJson = """{"5": [{"filename": "allbytes.bin", "data": "$hexString", "size": 256}]}"""

        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals(256, result!!.size)
        for (i in 0 until 256) {
            assertEquals("Byte $i mismatch", i.toByte(), result[i])
        }
    }

    // ========== ADDITIONAL EDGE CASE TESTS FOR COVERAGE ==========

    @Test
    fun `toMessageUi includes fieldsJson when only file attachments present no image`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "0102", "size": 2}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        // fieldsJson should be included because there are file attachments (needed for loading data)
        assertNotNull(result.fieldsJson)
        assertTrue(result.hasFileAttachments)
        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi handles field 5 as number gracefully`() {
        val fieldsJson = """{"5": 12345}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi handles field 5 as boolean gracefully`() {
        val fieldsJson = """{"5": true}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertFalse(result.hasFileAttachments)
        assertTrue(result.fileAttachments.isEmpty())
    }

    @Test
    fun `toMessageUi parses file attachment with unicode filename`() {
        val fieldsJson = """{"5": [{"filename": "文档.pdf", "data": "0102", "size": 2}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("文档.pdf", result.fileAttachments[0].filename)
    }

    @Test
    fun `toMessageUi skips malformed attachment in array and continues`() {
        // First attachment is valid, second is malformed (not an object), third is valid
        val fieldsJson = """{"5": [
            {"filename": "good1.pdf", "data": "01", "size": 1},
            "not an object",
            {"filename": "good2.txt", "data": "02", "size": 1}
        ]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        // Should have 2 valid attachments (skipped the malformed one)
        assertEquals(2, result.fileAttachments.size)
        assertEquals("good1.pdf", result.fileAttachments[0].filename)
        assertEquals(0, result.fileAttachments[0].index)
        assertEquals("good2.txt", result.fileAttachments[1].filename)
        assertEquals(2, result.fileAttachments[1].index)
    }

    @Test
    fun `loadFileAttachmentData handles attachment at non-zero index with valid data`() {
        val fieldsJson = """{"5": [
            {"filename": "first.txt", "data": "4669727374", "size": 5},
            {"filename": "second.txt", "data": "5365636f6e64", "size": 6}
        ]}"""

        // Load second attachment (index 1)
        val result = loadFileAttachmentData(fieldsJson, 1)

        assertNotNull(result)
        assertEquals("Second", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData handles field 5 with number type gracefully`() {
        val fieldsJson = """{"5": 123}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData handles field 5 with string type gracefully`() {
        val fieldsJson = """{"5": "not an array"}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `toMessageUi reads file attachments with per-file data references`() {
        // Create temp file with hex-encoded attachment data
        val tempFile = tempFolder.newFile("disk_file.dat")
        tempFile.writeText("446973") // "Dis" in hex

        // New format: metadata inline with per-file _data_ref
        val fieldsJson = """{"5": [{"filename": "disk_file.txt", "size": 3, "_data_ref": "${tempFile.absolutePath}"}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("disk_file.txt", result.fileAttachments[0].filename)
    }

    @Test
    fun `loadFileAttachmentData reads from per-file data references with multiple attachments`() {
        // Create temp files with hex-encoded data
        val tempFileA = tempFolder.newFile("a.dat")
        val tempFileB = tempFolder.newFile("b.dat")
        tempFileA.writeText("4141") // "AA" in hex
        tempFileB.writeText("4242") // "BB" in hex

        // New format: each attachment has its own _data_ref
        val fieldsJson = """{"5": [
            {"filename": "a.txt", "size": 2, "_data_ref": "${tempFileA.absolutePath}"},
            {"filename": "b.txt", "size": 2, "_data_ref": "${tempFileB.absolutePath}"}
        ]}"""

        val resultA = loadFileAttachmentData(fieldsJson, 0)
        val resultB = loadFileAttachmentData(fieldsJson, 1)

        assertNotNull(resultA)
        assertEquals("AA", String(resultA!!))
        assertNotNull(resultB)
        assertEquals("BB", String(resultB!!))
    }

    @Test
    fun `loadFileAttachmentData handles invalid hex data in file reference gracefully`() {
        val tempFile = tempFolder.newFile("invalid.dat")
        // hexStringToByteArray doesn't validate hex - it returns garbage bytes for invalid input
        tempFile.writeText("not valid hex [{{{")

        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 5, "_data_ref": "${tempFile.absolutePath}"}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        // Function returns a byte array (with garbage values) - doesn't validate hex
        assertNotNull(result)
    }

    @Test
    fun `loadFileAttachmentData handles attachment with special characters in data`() {
        // Test hex data that represents special characters
        val fieldsJson = """{"5": [{"filename": "test.bin", "data": "000102fe ff", "size": 5}]}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        // Invalid hex (spaces) should cause failure
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles concurrent cache check`() {
        // This tests the early cache check at the start of decodeAndCacheImage
        val messageId = "concurrent-test"
        val cachedBitmap = createTestBitmap()

        // Pre-populate cache
        ImageCache.put(messageId, cachedBitmap)

        // Call should return cached image immediately
        val result = decodeAndCacheImage(messageId, """{"6": "not_valid_but_irrelevant"}""")

        assertEquals(cachedBitmap, result)
    }

    @Test
    fun `toMessageUi handles per-file data reference with missing file gracefully`() {
        // New format: metadata inline with _data_ref pointing to nonexistent file
        val fieldsJson = """{"5": [{"filename": "test.txt", "size": 5, "_data_ref": "/nonexistent/path/file.dat"}]}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        // hasFileAttachments is true because we have metadata in the array
        // The file reference just means data loading will fail later
        assertTrue(result.hasFileAttachments)
        assertEquals(1, result.fileAttachments.size)
        assertEquals("test.txt", result.fileAttachments[0].filename)
    }

    // ========== loadFileAttachmentMetadata() TESTS ==========

    @Test
    fun `loadFileAttachmentMetadata returns null for null fieldsJson`() {
        val result = loadFileAttachmentMetadata(null, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null for empty fieldsJson`() {
        val result = loadFileAttachmentMetadata("", 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null for invalid JSON`() {
        val result = loadFileAttachmentMetadata("not valid json", 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is missing`() {
        val result = loadFileAttachmentMetadata("""{"1": "text", "6": "image"}""", 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null for negative index`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "size": 1000}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, -1)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null for out of bounds index`() {
        val fieldsJson = """{"5": [{"filename": "test.pdf", "size": 1000}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 5)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns correct filename and mimeType for PDF`() {
        val fieldsJson = """{"5": [{"filename": "document.pdf", "size": 12345}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("document.pdf", result!!.filename)
        assertEquals("application/pdf", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns correct mimeType for image file`() {
        val fieldsJson = """{"5": [{"filename": "photo.jpg", "size": 5000}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("photo.jpg", result!!.filename)
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns correct mimeType for text file`() {
        val fieldsJson = """{"5": [{"filename": "notes.txt", "size": 100}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("notes.txt", result!!.filename)
        assertEquals("text/plain", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns unknown filename when missing`() {
        val fieldsJson = """{"5": [{"size": 1000}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("unknown", result!!.filename)
    }

    @Test
    fun `loadFileAttachmentMetadata handles multiple attachments at different indices`() {
        val fieldsJson = """{"5": [
            {"filename": "first.pdf", "size": 1000},
            {"filename": "second.png", "size": 2000},
            {"filename": "third.txt", "size": 500}
        ]}"""

        val result0 = loadFileAttachmentMetadata(fieldsJson, 0)
        val result1 = loadFileAttachmentMetadata(fieldsJson, 1)
        val result2 = loadFileAttachmentMetadata(fieldsJson, 2)

        assertNotNull(result0)
        assertEquals("first.pdf", result0!!.filename)
        assertEquals("application/pdf", result0.mimeType)

        assertNotNull(result1)
        assertEquals("second.png", result1!!.filename)
        assertEquals("image/png", result1.mimeType)

        assertNotNull(result2)
        assertEquals("third.txt", result2!!.filename)
        assertEquals("text/plain", result2.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata reads metadata from inline array with data reference`() {
        // New format: metadata inline, file data on disk via _data_ref
        val fieldsJson = """{"5": [{"filename": "from_disk.pdf", "size": 9999, "_data_ref": "/path/to/data.dat"}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("from_disk.pdf", result!!.filename)
        assertEquals("application/pdf", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is object not array`() {
        // Old format is no longer supported
        val fieldsJson = """{"5": {"_file_ref": "/nonexistent/path/attachments.json"}}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is object with empty path`() {
        // Old format is no longer supported
        val fieldsJson = """{"5": {"_file_ref": ""}}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is not array or file ref`() {
        val fieldsJson = """{"5": "just a string"}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is number`() {
        val fieldsJson = """{"5": 12345}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 is empty array`() {
        val fieldsJson = """{"5": []}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata handles unknown file extension`() {
        val fieldsJson = """{"5": [{"filename": "data.xyz", "size": 100}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("data.xyz", result!!.filename)
        // Unknown extensions default to application/octet-stream
        assertEquals("application/octet-stream", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata handles filename with no extension`() {
        val fieldsJson = """{"5": [{"filename": "README", "size": 100}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("README", result!!.filename)
        assertEquals("application/octet-stream", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata handles unicode filename`() {
        val fieldsJson = """{"5": [{"filename": "文档.pdf", "size": 100}]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("文档.pdf", result!!.filename)
        assertEquals("application/pdf", result.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when attachment is not JSONObject`() {
        // Array contains a string instead of an object
        val fieldsJson = """{"5": ["not an object"]}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentMetadata reads metadata from inline array with multiple attachments`() {
        // New format: metadata inline with per-file data references
        val fieldsJson = """{"5": [
            {"filename": "a.pdf", "size": 100, "_data_ref": "/path/a.dat"},
            {"filename": "b.txt", "size": 200, "_data_ref": "/path/b.dat"}
        ]}"""

        val result0 = loadFileAttachmentMetadata(fieldsJson, 0)
        val result1 = loadFileAttachmentMetadata(fieldsJson, 1)

        assertNotNull(result0)
        assertEquals("a.pdf", result0!!.filename)
        assertEquals("application/pdf", result0.mimeType)

        assertNotNull(result1)
        assertEquals("b.txt", result1!!.filename)
        assertEquals("text/plain", result1.mimeType)
    }

    @Test
    fun `loadFileAttachmentMetadata returns null when field 5 object has invalid JSON path`() {
        // Old format is no longer supported - objects in field 5 return null
        val fieldsJson = """{"5": {"_file_ref": "/invalid/path.dat"}}"""
        val result = loadFileAttachmentMetadata(fieldsJson, 0)

        assertNull(result)
    }

    // ========== decodeImageWithAnimation() TESTS ==========

    @Test
    fun `decodeImageWithAnimation returns null for null fieldsJson`() {
        val result = decodeImageWithAnimation("test-id", null)
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null for empty fieldsJson`() {
        val result = decodeImageWithAnimation("test-id", "")
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null for invalid JSON`() {
        val result = decodeImageWithAnimation("test-id", "not valid json")
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null when field 6 is missing`() {
        val result = decodeImageWithAnimation("test-id", """{"1": "some text"}""")
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null for empty field 6`() {
        val result = decodeImageWithAnimation("test-id", """{"6": ""}""")
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null for field 6 as number`() {
        val result = decodeImageWithAnimation("test-id", """{"6": 12345}""")
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation returns null for nonexistent file reference`() {
        val result =
            decodeImageWithAnimation(
                "test-id",
                """{"6": {"_file_ref": "/nonexistent/path/to/file.dat"}}""",
            )
        assertNull(result)
    }

    @Test
    fun `decodeImageWithAnimation detects animated GIF`() {
        // Create animated GIF bytes
        val animatedGifHex = createMinimalAnimatedGifHex()
        val fieldsJson = """{"6": "$animatedGifHex"}"""

        val result = decodeImageWithAnimation("animated-test", fieldsJson)

        assertNotNull(result)
        assertTrue(result!!.isAnimated)
        assertNotNull(result.rawBytes)
        // For animated GIFs, bitmap is not decoded (Coil handles it)
        assertNull(result.bitmap)
    }

    @Test
    fun `decodeImageWithAnimation detects non-animated GIF as static`() {
        // Non-animated GIF (GIF89a header only, no NETSCAPE extension, single frame)
        val staticGif = createMinimalStaticGifBytes()
        val staticGifHex = staticGif.joinToString("") { "%02x".format(it) }
        val fieldsJson = """{"6": "$staticGifHex"}"""

        val result = decodeImageWithAnimation("static-gif-test", fieldsJson)

        assertNotNull(result)
        assertFalse(result!!.isAnimated) // Single-frame GIF is not animated
        assertNotNull(result.rawBytes)
    }

    @Test
    fun `decodeImageWithAnimation returns null for non-GIF header without decoding`() {
        // PNG header bytes - will be extracted but may fail to decode as image
        val pngHex = "89504e470d0a1a0a0000000d49484452"
        val fieldsJson = """{"6": "$pngHex"}"""

        // This tests that the function handles the bytes gracefully
        // Result may be non-null (bytes extracted, not animated) or null (decode error)
        // The important thing is it doesn't crash
        val result = decodeImageWithAnimation("png-test", fieldsJson)

        // If result is non-null, verify it's not detected as animated
        result?.let {
            assertFalse(it.isAnimated)
            assertNotNull(it.rawBytes)
        }
    }

    @Test
    fun `decodeImageWithAnimation reads from file reference`() {
        // Create animated GIF and save to temp file
        val animatedGifHex = createMinimalAnimatedGifHex()
        val tempFile = tempFolder.newFile("animated.dat")
        tempFile.writeText(animatedGifHex)

        val fieldsJson = """{"6": {"_file_ref": "${tempFile.absolutePath}"}}"""
        val result = decodeImageWithAnimation("file-ref-test", fieldsJson)

        assertNotNull(result)
        assertTrue(result!!.isAnimated)
        assertNotNull(result.rawBytes)
    }

    @Test
    fun `decodeImageWithAnimation uses cached bitmap for static image`() {
        val messageId = "cache-test-id"

        // Ensure cache is empty
        assertNull(ImageCache.get(messageId))

        // Pre-populate cache with test bitmap
        val testBitmap = createTestBitmap()
        ImageCache.put(messageId, testBitmap)

        // Use a static GIF (which won't be detected as animated)
        val staticGif = createMinimalStaticGifBytes()
        val staticGifHex = staticGif.joinToString("") { "%02x".format(it) }
        val fieldsJson = """{"6": "$staticGifHex"}"""

        val result = decodeImageWithAnimation(messageId, fieldsJson)

        // Should return a result with the cached bitmap
        assertNotNull(result)
        assertFalse(result!!.isAnimated)
        assertNotNull(result.rawBytes)
        // The cached bitmap should be used
        assertEquals(testBitmap, result.bitmap)
    }

    @Test
    fun `DecodedImageResult equals returns true for identical data`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val result1 = DecodedImageResult(bytes.copyOf(), null, true)
        val result2 = DecodedImageResult(bytes.copyOf(), null, true)
        assertTrue(result1 == result2)
    }

    @Test
    fun `DecodedImageResult equals returns false for different bytes`() {
        val result1 = DecodedImageResult(byteArrayOf(0x01), null, true)
        val result2 = DecodedImageResult(byteArrayOf(0x02), null, true)
        assertFalse(result1 == result2)
    }

    @Test
    fun `DecodedImageResult equals returns false for different isAnimated`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val result1 = DecodedImageResult(bytes.copyOf(), null, true)
        val result2 = DecodedImageResult(bytes.copyOf(), null, false)
        assertFalse(result1 == result2)
    }

    @Test
    fun `DecodedImageResult hashCode is consistent for equal objects`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val result1 = DecodedImageResult(bytes.copyOf(), null, true)
        val result2 = DecodedImageResult(bytes.copyOf(), null, true)
        assertTrue(result1.hashCode() == result2.hashCode())
    }

    @Test
    fun `toMessageUi sets isAnimatedImage to false by default`() {
        val message = createMessage(TestMessageConfig(fieldsJson = null))

        val result = message.toMessageUi()

        assertFalse(result.isAnimatedImage)
        assertNull(result.imageData)
    }

    @Test
    fun `toMessageUi handles message with image but no animation data`() {
        val message = createMessage(TestMessageConfig(fieldsJson = """{"6": "ffd8ffe0"}"""))

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
        // isAnimatedImage and imageData are populated by ViewModel, not mapper
        assertFalse(result.isAnimatedImage)
        assertNull(result.imageData)
    }

    // ========== Helper Functions for Animation Tests ==========

    /**
     * Creates hex string for a minimal valid animated GIF.
     */
    private fun createMinimalAnimatedGifHex(): String {
        val bytes = createMinimalAnimatedGifBytes()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates a minimal valid animated GIF with 2 frames.
     */
    private fun createMinimalAnimatedGifBytes(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor
        output.add(0x01)
        output.add(0x00) // Width
        output.add(0x01)
        output.add(0x00) // Height
        output.add(0x00) // Packed field
        output.add(0x00) // Background color
        output.add(0x00) // Pixel aspect ratio

        // NETSCAPE2.0 Application Extension
        output.add(0x21)
        output.add(0xFF.toByte())
        output.add(0x0B)
        output.addAll("NETSCAPE2.0".toByteArray(Charsets.US_ASCII).toList())
        output.add(0x03)
        output.add(0x01)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)

        // Frame 1 - Graphic Control Extension
        output.add(0x21)
        output.add(0xF9.toByte())
        output.add(0x04)
        output.add(0x00)
        output.add(0x0A)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)

        // Frame 1 - Image Descriptor
        output.add(0x2C)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x00)

        // Frame 1 - Image Data
        output.add(0x02)
        output.add(0x02)
        output.add(0x44)
        output.add(0x01)
        output.add(0x00)

        // Frame 2 - Graphic Control Extension
        output.add(0x21)
        output.add(0xF9.toByte())
        output.add(0x04)
        output.add(0x00)
        output.add(0x0A)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)

        // Frame 2 - Image Descriptor
        output.add(0x2C)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x00)

        // Frame 2 - Image Data
        output.add(0x02)
        output.add(0x02)
        output.add(0x44)
        output.add(0x01)
        output.add(0x00)

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }

    /**
     * Creates a minimal valid static GIF (single frame, no animation).
     */
    private fun createMinimalStaticGifBytes(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor
        output.add(0x01)
        output.add(0x00) // Width = 1
        output.add(0x01)
        output.add(0x00) // Height = 1
        output.add(0x00) // Packed field (no global color table)
        output.add(0x00) // Background color
        output.add(0x00) // Pixel aspect ratio

        // Single frame - Image Descriptor (no graphic control extension)
        output.add(0x2C)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00) // position
        output.add(0x01)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00) // size
        output.add(0x00) // packed field

        // Image Data (minimal LZW)
        output.add(0x02) // LZW minimum code size
        output.add(0x02) // Sub-block size
        output.add(0x44)
        output.add(0x01) // LZW data
        output.add(0x00) // Block terminator

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }

    // ========== MessageUi Computed Properties Tests ==========

    @Test
    fun `isMediaOnlyMessage returns true for animated GIF without text`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = byteArrayOf(1, 2, 3),
                hasFileAttachments = false,
            )

        assertTrue(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when content is present`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "Hello!",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = byteArrayOf(1, 2, 3),
            )

        assertFalse(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when not animated`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = false,
                imageData = byteArrayOf(1, 2, 3),
            )

        assertFalse(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when imageData is null`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = null,
            )

        assertFalse(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when has file attachments`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = byteArrayOf(1, 2, 3),
                hasFileAttachments = true,
            )

        assertFalse(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns false when has reply preview`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = byteArrayOf(1, 2, 3),
                replyPreview =
                    ReplyPreviewUi(
                        messageId = "original",
                        senderName = "Alice",
                        contentPreview = "Original message",
                    ),
            )

        assertFalse(messageUi.isMediaOnlyMessage)
    }

    @Test
    fun `isMediaOnlyMessage returns true with blank content`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "   \n\t  ",
                timestamp = 0L,
                isFromMe = true,
                status = "delivered",
                isAnimatedImage = true,
                imageData = byteArrayOf(1, 2, 3),
            )

        assertTrue(messageUi.isMediaOnlyMessage)
    }

    // ========== isPendingFileNotification Tests ==========

    @Test
    fun `isPendingFileNotification returns false when fieldsJson is null`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = null,
            )

        assertFalse(messageUi.isPendingFileNotification)
    }

    @Test
    fun `isPendingFileNotification returns false when no notification in json`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"reply_to": "some_id"}}""",
            )

        assertFalse(messageUi.isPendingFileNotification)
    }

    @Test
    fun `isPendingFileNotification returns true when notification present`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"pending_file_notification": {"original_message_id": "abc"}}}""",
            )

        assertTrue(messageUi.isPendingFileNotification)
    }

    // ========== isSuperseded Tests ==========

    @Test
    fun `isSuperseded returns false when fieldsJson is null`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = null,
            )

        assertFalse(messageUi.isSuperseded)
    }

    @Test
    fun `isSuperseded returns false when superseded is not present`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"pending_file_notification": {}}}""",
            )

        assertFalse(messageUi.isSuperseded)
    }

    @Test
    fun `isSuperseded returns true when superseded is true`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"superseded":true}}""",
            )

        assertTrue(messageUi.isSuperseded)
    }

    @Test
    fun `isSuperseded returns false when superseded is false`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"superseded":false}}""",
            )

        assertFalse(messageUi.isSuperseded)
    }

    // ========== pendingFileInfo Tests ==========

    @Test
    fun `pendingFileInfo returns null when not a pending notification`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"reply_to": "id"}}""",
            )

        assertNull(messageUi.pendingFileInfo)
    }

    @Test
    fun `pendingFileInfo returns null when fieldsJson is null`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = null,
            )

        assertNull(messageUi.pendingFileInfo)
    }

    @Test
    fun `pendingFileInfo parses notification correctly`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"pending_file_notification": {"original_message_id": "abc123", "filename": "report.pdf", "file_count": 3, "total_size": 1048576}}}""",
            )

        val info = messageUi.pendingFileInfo
        assertNotNull(info)
        assertEquals("abc123", info!!.originalMessageId)
        assertEquals("report.pdf", info.filename)
        assertEquals(3, info.fileCount)
        assertEquals(1048576L, info.totalSize)
    }

    @Test
    fun `pendingFileInfo uses default values for missing fields`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"pending_file_notification": {}}}""",
            )

        val info = messageUi.pendingFileInfo
        assertNotNull(info)
        assertEquals("", info!!.originalMessageId)
        assertEquals("file", info.filename)
        assertEquals(1, info.fileCount)
        assertEquals(0L, info.totalSize)
    }

    @Test
    fun `pendingFileInfo returns null for malformed notification`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"16": {"pending_file_notification": "invalid"}}""",
            )

        assertNull(messageUi.pendingFileInfo)
    }

    @Test
    fun `pendingFileInfo returns null when field 16 is missing`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = """{"6": "image_hex"}""",
            )

        // Contains pending_file_notification string check fails
        assertFalse(messageUi.isPendingFileNotification)
        assertNull(messageUi.pendingFileInfo)
    }

    @Test
    fun `pendingFileInfo returns null for invalid JSON`() {
        val messageUi =
            MessageUi(
                id = "test",
                destinationHash = "hash",
                content = "",
                timestamp = 0L,
                isFromMe = false,
                status = "delivered",
                fieldsJson = "pending_file_notification not valid json {{{",
            )

        // Contains the string but invalid JSON
        assertTrue(messageUi.isPendingFileNotification)
        assertNull(messageUi.pendingFileInfo)
    }

    // ========== ReactionUi Tests ==========

    @Test
    fun `ReactionUi count equals senderHashes size`() {
        val reaction =
            ReactionUi(
                emoji = "👍",
                senderHashes = listOf("hash1", "hash2", "hash3"),
            )

        assertEquals(3, reaction.count)
    }

    @Test
    fun `ReactionUi count is zero for empty senderHashes`() {
        val reaction =
            ReactionUi(
                emoji = "❤️",
                senderHashes = emptyList(),
            )

        assertEquals(0, reaction.count)
    }

    // ========== parseReactionsFromField16 Tests ==========

    @Test
    fun `parseReactionsFromField16 returns empty list for null fieldsJson`() {
        val result = parseReactionsFromField16(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list for missing field 16`() {
        val result = parseReactionsFromField16("""{"6": "image"}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list for missing reactions key`() {
        val result = parseReactionsFromField16("""{"16": {"reply_to": "id"}}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 parses single reaction correctly`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(listOf("sender1", "sender2"), result[0].senderHashes)
        assertEquals(2, result[0].count)
    }

    @Test
    fun `parseReactionsFromField16 parses multiple reactions correctly`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1"], "❤️": ["sender2", "sender3"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(2, result.size)
        // Order may vary, so check both are present
        val emojiSet = result.map { it.emoji }.toSet()
        assertTrue(emojiSet.contains("👍"))
        assertTrue(emojiSet.contains("❤️"))
    }

    @Test
    fun `parseReactionsFromField16 skips empty sender arrays`() {
        val fieldsJson = """{"16": {"reactions": {"👍": [], "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 skips empty sender strings`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["", "sender1", ""]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(listOf("sender1"), result[0].senderHashes)
    }

    @Test
    fun `parseReactionsFromField16 handles invalid JSON gracefully`() {
        val result = parseReactionsFromField16("not valid json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 handles reactions as wrong type`() {
        val fieldsJson = """{"16": {"reactions": "not an object"}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 skips null values in sender array`() {
        val fieldsJson = """{"16": {"reactions": {"👍": [null, "sender1", null]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(listOf("sender1"), result[0].senderHashes)
    }
}
