package com.lxmf.messenger.ui.model

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
class ImageCacheTest {
    @Before
    fun setup() {
        // Clear cache before each test to ensure isolation
        ImageCache.clear()
    }

    @After
    fun tearDown() {
        // Clear cache after each test
        ImageCache.clear()
    }

    @Test
    fun `get returns null for uncached message`() {
        val result = ImageCache.get("nonexistent-id")
        assertNull(result)
    }

    @Test
    fun `put and get returns cached image`() {
        val bitmap = createTestBitmap()
        val messageId = "test-message-123"

        ImageCache.put(messageId, bitmap)
        val result = ImageCache.get(messageId)

        assertNotNull(result)
        assertEquals(bitmap, result)
    }

    @Test
    fun `contains returns false for uncached message`() {
        val result = ImageCache.contains("nonexistent-id")
        assertFalse(result)
    }

    @Test
    fun `contains returns true for cached message`() {
        val bitmap = createTestBitmap()
        val messageId = "test-message-456"

        ImageCache.put(messageId, bitmap)
        val result = ImageCache.contains(messageId)

        assertTrue(result)
    }

    @Test
    fun `clear removes all cached images`() {
        val bitmap1 = createTestBitmap()
        val bitmap2 = createTestBitmap()

        ImageCache.put("id1", bitmap1)
        ImageCache.put("id2", bitmap2)
        assertEquals(2, ImageCache.size())

        ImageCache.clear()

        assertEquals(0, ImageCache.size())
        assertNull(ImageCache.get("id1"))
        assertNull(ImageCache.get("id2"))
    }

    @Test
    fun `size returns correct count`() {
        assertEquals(0, ImageCache.size())

        ImageCache.put("id1", createTestBitmap())
        assertEquals(1, ImageCache.size())

        ImageCache.put("id2", createTestBitmap())
        assertEquals(2, ImageCache.size())

        ImageCache.put("id3", createTestBitmap())
        assertEquals(3, ImageCache.size())
    }

    @Test
    fun `put overwrites existing entry with same key`() {
        val bitmap1 = createTestBitmap()
        val bitmap2 = createTestBitmap()
        val messageId = "same-id"

        ImageCache.put(messageId, bitmap1)
        ImageCache.put(messageId, bitmap2)

        // Size should still be 1
        assertEquals(1, ImageCache.size())
        // Should return the second bitmap
        assertEquals(bitmap2, ImageCache.get(messageId))
    }

    @Test
    fun `getStats returns hit and miss counts`() {
        val bitmap = createTestBitmap()
        ImageCache.put("cached-id", bitmap)

        // Miss
        ImageCache.get("nonexistent")
        // Hit
        ImageCache.get("cached-id")
        // Another hit
        ImageCache.get("cached-id")
        // Another miss
        ImageCache.get("also-nonexistent")

        val (hits, misses) = ImageCache.getStats()
        assertEquals(2, hits)
        assertEquals(2, misses)
    }

    @Test
    fun `cache handles many items`() {
        // Add 30 items (less than max cache size of 50)
        repeat(30) { i ->
            ImageCache.put("message-$i", createTestBitmap())
        }

        assertEquals(30, ImageCache.size())

        // Verify all items are accessible
        repeat(30) { i ->
            assertNotNull(ImageCache.get("message-$i"))
        }
    }

    private fun createTestBitmap(): ImageBitmap {
        // Create a minimal test bitmap (1x1 pixel)
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
