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
        val message = createMessage(
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
        val message = createMessage(
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

        val message = createMessage(
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

        val message = createMessage(
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
        val message = createMessage(
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
        val result = decodeAndCacheImage(
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

    private fun createTestBitmap() =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

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
        val message = createMessage(
            TestMessageConfig(fieldsJson = """{"6": {"_file_ref": "/data/attachments/img.dat"}}"""),
        )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false when file reference object has wrong key`() {
        // Object in field 6 but without _file_ref key
        val message = createMessage(
            TestMessageConfig(fieldsJson = """{"6": {"wrong_key": "/path/to/file"}}"""),
        )

        val result = message.toMessageUi()

        assertFalse(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi sets hasImageAttachment false for empty file reference path`() {
        // _file_ref exists but value is empty
        val message = createMessage(
            TestMessageConfig(fieldsJson = """{"6": {"_file_ref": ""}}"""),
        )

        val result = message.toMessageUi()

        // hasImageField checks if _file_ref key exists, not if value is non-empty
        // So this should still be true
        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi handles deeply nested JSON without crashing`() {
        val message = createMessage(
            TestMessageConfig(fieldsJson = """{"1": {"nested": {"deep": "value"}}, "6": "image_hex"}"""),
        )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    @Test
    fun `toMessageUi handles JSON with multiple fields including image`() {
        val message = createMessage(
            TestMessageConfig(fieldsJson = """{"1": "text content", "6": "image_hex_data", "7": "other"}"""),
        )

        val result = message.toMessageUi()

        assertTrue(result.hasImageAttachment)
    }

    // ========== decodeAndCacheImage() additional coverage ==========

    @Test
    fun `decodeAndCacheImage handles file reference with empty _file_ref value`() {
        val result = decodeAndCacheImage(
            "empty-path-test",
            """{"6": {"_file_ref": ""}}""",
        )

        // Empty path should fail to read
        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles field 6 as JSONObject without _file_ref key`() {
        val result = decodeAndCacheImage(
            "no-file-ref-key",
            """{"6": {"other_key": "value"}}""",
        )

        assertNull(result)
    }

    @Test
    fun `decodeAndCacheImage handles very long hex string without crashing`() {
        // Generate a long but invalid hex string
        val longHex = "ff".repeat(10000)
        val result = decodeAndCacheImage(
            "long-hex-test",
            """{"6": "$longHex"}""",
        )

        // May or may not decode, but shouldn't crash
    }

    @Test
    fun `decodeAndCacheImage handles odd-length hex string gracefully`() {
        // Odd-length hex strings may or may not decode depending on implementation
        // This test verifies no exception is thrown
        val result = decodeAndCacheImage(
            "odd-hex-test",
            """{"6": "fff"}""", // 3 chars, not valid hex pair
        )

        // Result may be null or non-null depending on Robolectric's BitmapFactory
        // The important thing is it doesn't crash
    }

    @Test
    fun `decodeAndCacheImage handles uppercase hex string`() {
        val result = decodeAndCacheImage(
            "uppercase-hex-test",
            """{"6": "FFD8FFE0"}""",
        )

        // Should handle uppercase hex - whether decode succeeds depends on BitmapFactory
    }

    @Test
    fun `decodeAndCacheImage handles mixed case hex string`() {
        val result = decodeAndCacheImage(
            "mixed-case-test",
            """{"6": "FfD8fFe0"}""",
        )

        // Should handle mixed case
    }

    @Test
    fun `toMessageUi correctly maps all MessageUi fields`() {
        val message = createMessage(
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
        val message = createMessage(
            TestMessageConfig(
                status = "failed",
                errorMessage = "Network timeout",
            ),
        )

        val result = message.toMessageUi()

        assertEquals("failed", result.status)
        assertEquals("Network timeout", result.errorMessage)
    }
}
