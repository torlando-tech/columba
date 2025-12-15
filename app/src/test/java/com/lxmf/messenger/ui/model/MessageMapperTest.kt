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
