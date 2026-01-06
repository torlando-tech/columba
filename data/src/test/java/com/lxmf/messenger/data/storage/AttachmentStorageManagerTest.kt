package com.lxmf.messenger.data.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for AttachmentStorageManager.
 *
 * Tests cover:
 * - saveAttachment: creating files with correct content
 * - loadAttachment: reading files, handling missing files
 * - deleteAttachments: removing message directories
 * - cleanupOldAttachments: removing old directories, preserving recent ones
 * - Constants: SIZE_THRESHOLD, FILE_REF_KEY
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentStorageManagerTest {
    private lateinit var context: Context
    private lateinit var storageManager: AttachmentStorageManager
    private lateinit var attachmentsDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = AttachmentStorageManager(context)
        attachmentsDir = File(context.filesDir, "attachments")
        // Clean up any leftover files from previous tests
        attachmentsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        // Clean up test files
        attachmentsDir.deleteRecursively()
    }

    // ========== Constants Tests ==========

    @Test
    fun `SIZE_THRESHOLD is 500KB`() {
        assertEquals(500 * 1024, AttachmentStorageManager.SIZE_THRESHOLD)
    }

    @Test
    fun `FILE_REF_KEY is _file_ref`() {
        assertEquals("_file_ref", AttachmentStorageManager.FILE_REF_KEY)
    }

    // ========== saveAttachment Tests ==========

    @Test
    fun `saveAttachment creates file with correct content`() {
        val messageHash = "abc123def456"
        val fieldKey = "6"
        val data = "hex-encoded-image-data-here"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)

        assertNotNull("Path should not be null", path)
        val file = File(path!!)
        assertTrue("File should exist", file.exists())
        assertEquals("Content should match", data, file.readText())
    }

    @Test
    fun `saveAttachment creates nested directories`() {
        val messageHash = "new_message_hash"
        val fieldKey = "5"
        val data = "file-attachment-data"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)

        assertNotNull("Path should not be null", path)
        val file = File(path!!)
        assertTrue("File should exist", file.exists())
        assertTrue(
            "Path should contain message hash",
            path.contains(messageHash),
        )
        assertTrue(
            "Path should contain field key",
            path.endsWith(fieldKey),
        )
    }

    @Test
    fun `saveAttachment returns path on success`() {
        val messageHash = "test_hash"
        val fieldKey = "6"
        val data = "test data"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)

        assertNotNull("Should return non-null path", path)
        assertTrue("Path should be absolute", path!!.startsWith("/"))
        assertTrue("Path should contain attachments dir", path.contains("attachments"))
    }

    @Test
    fun `saveAttachment handles special characters in message hash`() {
        // Message hashes are typically hex strings, but test edge cases
        val messageHash = "abc-123_def"
        val fieldKey = "6"
        val data = "test data"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)

        assertNotNull("Path should not be null", path)
        val file = File(path!!)
        assertTrue("File should exist", file.exists())
    }

    @Test
    fun `saveAttachment handles large data`() {
        val messageHash = "large_data_test"
        val fieldKey = "5"
        val data = "x".repeat(1024 * 1024) // 1MB of data

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)

        assertNotNull("Path should not be null", path)
        val file = File(path!!)
        assertEquals("File size should match data length", data.length.toLong(), file.length())
    }

    @Test
    fun `saveAttachment overwrites existing file`() {
        val messageHash = "overwrite_test"
        val fieldKey = "6"
        val originalData = "original"
        val newData = "updated"

        // Save original
        storageManager.saveAttachment(messageHash, fieldKey, originalData)

        // Overwrite with new data
        val path = storageManager.saveAttachment(messageHash, fieldKey, newData)

        val file = File(path!!)
        assertEquals("Content should be updated", newData, file.readText())
    }

    // ========== loadAttachment Tests ==========

    @Test
    fun `loadAttachment reads existing file`() {
        val messageHash = "load_test"
        val fieldKey = "6"
        val data = "test attachment data"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)
        val loaded = storageManager.loadAttachment(path!!)

        assertEquals("Loaded data should match saved data", data, loaded)
    }

    @Test
    fun `loadAttachment returns null for non-existent file`() {
        val fakePath = "/non/existent/path/attachment"

        val loaded = storageManager.loadAttachment(fakePath)

        assertNull("Should return null for missing file", loaded)
    }

    @Test
    fun `loadAttachment returns null for invalid path`() {
        val invalidPath = ""

        val loaded = storageManager.loadAttachment(invalidPath)

        assertNull("Should return null for invalid path", loaded)
    }

    // ========== deleteAttachments Tests ==========

    @Test
    fun `deleteAttachments removes message directory`() {
        val messageHash = "delete_test"
        val fieldKey = "6"
        val data = "test data"

        val path = storageManager.saveAttachment(messageHash, fieldKey, data)
        val file = File(path!!)
        assertTrue("File should exist before delete", file.exists())

        storageManager.deleteAttachments(messageHash)

        assertTrue("File should be deleted", !file.exists())
        assertTrue("Directory should be deleted", !file.parentFile!!.exists())
    }

    @Test
    fun `deleteAttachments handles non-existent directory gracefully`() {
        val nonExistentHash = "does_not_exist"

        // Should not throw exception
        storageManager.deleteAttachments(nonExistentHash)
    }

    @Test
    fun `deleteAttachments removes all files in directory`() {
        val messageHash = "multi_file_test"

        // Create multiple attachments for same message
        storageManager.saveAttachment(messageHash, "5", "file data")
        storageManager.saveAttachment(messageHash, "6", "image data")
        storageManager.saveAttachment(messageHash, "7", "other data")

        val messageDir = File(attachmentsDir, messageHash)
        assertEquals("Should have 3 files", 3, messageDir.listFiles()?.size)

        storageManager.deleteAttachments(messageHash)

        assertTrue("Directory should be deleted", !messageDir.exists())
    }

    // ========== cleanupOldAttachments Tests ==========

    @Test
    fun `cleanupOldAttachments removes directories older than maxAge`() {
        val messageHash = "old_message"
        val path = storageManager.saveAttachment(messageHash, "6", "data")

        // Set directory modification time to 2 hours ago
        val messageDir = File(path!!).parentFile!!
        val oldTime = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        messageDir.setLastModified(oldTime)

        // Cleanup with 1 hour max age
        storageManager.cleanupOldAttachments(60 * 60 * 1000L)

        assertTrue("Old directory should be deleted", !messageDir.exists())
    }

    @Test
    fun `cleanupOldAttachments preserves recent directories`() {
        val messageHash = "recent_message"
        val path = storageManager.saveAttachment(messageHash, "6", "data")

        val messageDir = File(path!!).parentFile!!

        // Cleanup with 1 hour max age (directory is recent, should be preserved)
        storageManager.cleanupOldAttachments(60 * 60 * 1000L)

        assertTrue("Recent directory should be preserved", messageDir.exists())
    }

    @Test
    fun `cleanupOldAttachments handles empty attachments directory`() {
        // Ensure attachments directory exists but is empty
        attachmentsDir.mkdirs()

        // Should not throw exception
        storageManager.cleanupOldAttachments(60 * 60 * 1000L)
    }

    @Test
    fun `cleanupOldAttachments uses default 7 day max age`() {
        val messageHash = "week_old_message"
        val path = storageManager.saveAttachment(messageHash, "6", "data")

        // Set directory modification time to 8 days ago
        val messageDir = File(path!!).parentFile!!
        val eightDaysAgo = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L
        messageDir.setLastModified(eightDaysAgo)

        // Cleanup with default max age (7 days)
        storageManager.cleanupOldAttachments()

        assertTrue("8-day-old directory should be deleted", !messageDir.exists())
    }

    @Test
    fun `cleanupOldAttachments preserves 6 day old directories by default`() {
        val messageHash = "six_day_old"
        val path = storageManager.saveAttachment(messageHash, "6", "data")

        // Set directory modification time to 6 days ago
        val messageDir = File(path!!).parentFile!!
        val sixDaysAgo = System.currentTimeMillis() - 6 * 24 * 60 * 60 * 1000L
        messageDir.setLastModified(sixDaysAgo)

        // Cleanup with default max age (7 days)
        storageManager.cleanupOldAttachments()

        assertTrue("6-day-old directory should be preserved", messageDir.exists())
    }
}
