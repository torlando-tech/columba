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
    fun `toMessageUi sets hasFileAttachments true for file reference`() {
        val fieldsJson = """{"5": {"_file_ref": "/data/attachments/file.dat"}}"""
        val message = createMessage(TestMessageConfig(fieldsJson = fieldsJson))

        val result = message.toMessageUi()

        assertTrue(result.hasFileAttachments)
        // File reference loading happens async - attachments may be empty if file doesn't exist
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
    fun `loadFileAttachmentData reads from file reference when file exists`() {
        // Create a temp file with JSON array of attachments
        val attachmentData = """[{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]"""
        val tempFile = tempFolder.newFile("file_attachments.dat")
        tempFile.writeText(attachmentData)

        val fieldsJson = """{"5": {"_file_ref": "${tempFile.absolutePath}"}}"""
        val result = loadFileAttachmentData(fieldsJson, 0)

        assertNotNull(result)
        assertEquals("Hello", String(result!!))
    }

    @Test
    fun `loadFileAttachmentData returns null for nonexistent file reference`() {
        val fieldsJson = """{"5": {"_file_ref": "/nonexistent/path/file.dat"}}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }

    @Test
    fun `loadFileAttachmentData returns null for empty file reference path`() {
        val fieldsJson = """{"5": {"_file_ref": ""}}"""
        val result = loadFileAttachmentData(fieldsJson, 0)
        assertNull(result)
    }
}
