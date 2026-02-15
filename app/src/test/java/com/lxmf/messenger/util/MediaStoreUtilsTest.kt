package com.lxmf.messenger.util

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaStoreUtilsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `getRecentPhotos returns empty list when no images exist`() {
        val photos = MediaStoreUtils.getRecentPhotos(context)
        assertTrue(photos.isEmpty())
    }

    @Test
    fun `getRecentPhotos respects limit parameter`() {
        // Insert some images into the MediaStore
        val resolver = context.contentResolver
        repeat(5) { i ->
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "test_image_$i.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000 - i)
                }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }

        val photos = MediaStoreUtils.getRecentPhotos(context, limit = 3)
        assertEquals(3, photos.size)
    }

    @Test
    fun `getRecentPhotos returns all images when fewer than limit`() {
        val resolver = context.contentResolver
        repeat(2) { i ->
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "photo_$i.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000 - i)
                }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }

        val photos = MediaStoreUtils.getRecentPhotos(context, limit = 30)
        assertEquals(2, photos.size)
    }

    @Test
    fun `getRecentPhotos returns content URIs`() {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "uri_test.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        val photos = MediaStoreUtils.getRecentPhotos(context, limit = 1)
        assertEquals(1, photos.size)
        assertTrue(photos[0].toString().startsWith("content://"))
    }

    @Test
    fun `getRecentPhotos default limit is 30`() {
        // Verify that the default parameter exists and doesn't crash
        val photos = MediaStoreUtils.getRecentPhotos(context)
        assertTrue(photos.size <= 30)
    }
}
