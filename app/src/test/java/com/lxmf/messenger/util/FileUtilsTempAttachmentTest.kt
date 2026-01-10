package com.lxmf.messenger.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for FileUtils temp attachment functionality.
 *
 * Uses Robolectric to provide Android Context for file operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileUtilsTempAttachmentTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean up any leftover temp files
        FileUtils.cleanupTempAttachments(context, 0)
    }

    // ========== FILE_TRANSFER_THRESHOLD Tests ==========

    @Test
    fun `FILE_TRANSFER_THRESHOLD is 500KB`() {
        assertEquals(500 * 1024, FileUtils.FILE_TRANSFER_THRESHOLD)
    }

    // ========== writeTempAttachment Tests ==========

    @Test
    fun `writeTempAttachment creates file with correct content`() {
        val filename = "test.pdf"
        val data = "Hello, World!".toByteArray()

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)

        assertTrue("File should exist", tempFile.exists())
        assertArrayEquals("File content should match", data, tempFile.readBytes())
    }

    @Test
    fun `writeTempAttachment creates file in attachments subdirectory`() {
        val filename = "document.pdf"
        val data = ByteArray(1024) { it.toByte() }

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)

        assertTrue(
            "File path should contain attachments",
            tempFile.absolutePath.contains("attachments"),
        )
    }

    @Test
    fun `writeTempAttachment includes timestamp in filename`() {
        val filename = "report.pdf"
        val data = ByteArray(100)

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)

        // Filename should start with a timestamp (13+ digit number)
        val timestampPattern = Regex("\\d{13,}_")
        assertTrue(
            "Filename should include timestamp",
            timestampPattern.containsMatchIn(tempFile.name),
        )
    }

    @Test
    fun `writeTempAttachment sanitizes unsafe characters in filename`() {
        val unsafeFilename = "test<file>with:unsafe*chars?.pdf"
        val data = ByteArray(50)

        val tempFile = FileUtils.writeTempAttachment(context, unsafeFilename, data)

        // Unsafe characters should be replaced with underscores
        assertTrue(
            "Filename should not contain unsafe characters",
            !tempFile.name.contains("<") &&
                !tempFile.name.contains(">") &&
                !tempFile.name.contains(":") &&
                !tempFile.name.contains("*") &&
                !tempFile.name.contains("?"),
        )
    }

    @Test
    fun `writeTempAttachment preserves safe characters in filename`() {
        val safeFilename = "my-document_v2.0.pdf"
        val data = ByteArray(50)

        val tempFile = FileUtils.writeTempAttachment(context, safeFilename, data)

        assertTrue(
            "Filename should contain the safe original name",
            tempFile.name.contains("my-document_v2.0.pdf"),
        )
    }

    @Test
    fun `writeTempAttachment handles empty data`() {
        val filename = "empty.txt"
        val data = ByteArray(0)

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)

        assertTrue("File should exist", tempFile.exists())
        assertEquals("File should be empty", 0, tempFile.length())
    }

    @Test
    fun `writeTempAttachment handles large data`() {
        val filename = "large.bin"
        val data = ByteArray(1024 * 1024) { it.toByte() } // 1MB

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)

        assertTrue("File should exist", tempFile.exists())
        assertEquals("File size should match", data.size.toLong(), tempFile.length())
    }

    @Test
    fun `writeTempAttachment creates unique files for same filename`() {
        val filename = "duplicate.pdf"
        val data1 = "Content 1".toByteArray()
        val data2 = "Content 2".toByteArray()

        val tempFile1 = FileUtils.writeTempAttachment(context, filename, data1)
        // Small delay to ensure different timestamps
        Thread.sleep(2)
        val tempFile2 = FileUtils.writeTempAttachment(context, filename, data2)

        assertTrue("File 1 should exist", tempFile1.exists())
        assertTrue("File 2 should exist", tempFile2.exists())
        assertTrue("Files should have different paths", tempFile1.absolutePath != tempFile2.absolutePath)
    }

    // ========== cleanupTempAttachments Tests ==========

    @Test
    fun `cleanupTempAttachments removes old files`() {
        // Create some temp files
        val filename = "old_file.pdf"
        val data = ByteArray(100)

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)
        assertTrue("File should exist before cleanup", tempFile.exists())

        // Set the file's last modified time to the past (older than the cutoff)
        val oldTime = System.currentTimeMillis() - 2 * 60 * 60 * 1000L // 2 hours ago
        tempFile.setLastModified(oldTime)

        // Cleanup with 1 hour max age (files older than 1 hour should be removed)
        FileUtils.cleanupTempAttachments(context, 60 * 60 * 1000L)

        assertTrue(
            "File should be deleted",
            !tempFile.exists(),
        )
    }

    @Test
    fun `cleanupTempAttachments preserves recent files`() {
        val filename = "recent_file.pdf"
        val data = ByteArray(100)

        val tempFile = FileUtils.writeTempAttachment(context, filename, data)
        assertTrue("File should exist before cleanup", tempFile.exists())

        // Cleanup with 1 hour max age (file should NOT be removed)
        FileUtils.cleanupTempAttachments(context, 60 * 60 * 1000L)

        assertTrue("Recent file should be preserved", tempFile.exists())
    }

    // ========== wouldExceedSizeLimit Tests ==========
    // Note: With MAX_TOTAL_ATTACHMENT_SIZE = Int.MAX_VALUE, the limit is effectively unlimited.
    // Testing "exceeding" the limit would require integer overflow, so we only test normal cases.

    @Test
    fun `wouldExceedSizeLimit returns false for typical file sizes`() {
        // With Int.MAX_VALUE limit, normal file sizes never exceed
        assertTrue(!FileUtils.wouldExceedSizeLimit(0, 100 * 1024 * 1024)) // 100MB
        assertTrue(!FileUtils.wouldExceedSizeLimit(500 * 1024 * 1024, 500 * 1024 * 1024)) // 1GB total
    }

    // ========== Size Constants Tests ==========

    @Test
    fun `MAX_TOTAL_ATTACHMENT_SIZE is Int MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)
    }

    @Test
    fun `MAX_SINGLE_FILE_SIZE is Int MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, FileUtils.MAX_SINGLE_FILE_SIZE)
    }
}
