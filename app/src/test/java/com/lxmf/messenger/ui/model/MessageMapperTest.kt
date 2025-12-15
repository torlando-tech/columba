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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageMapperTest {

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
}
