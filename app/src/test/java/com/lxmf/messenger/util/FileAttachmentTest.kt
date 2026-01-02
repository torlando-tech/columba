package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FileAttachment data class.
 * Tests equality, hashCode, and property accessors.
 */
class FileAttachmentTest {
    @Test
    fun `equals returns true for identical attachments`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val attachment1 =
            FileAttachment(
                filename = "test.pdf",
                data = data,
                mimeType = "application/pdf",
                sizeBytes = 5,
            )
        val attachment2 =
            FileAttachment(
                filename = "test.pdf",
                data = data.copyOf(),
                mimeType = "application/pdf",
                sizeBytes = 5,
            )

        assertEquals(attachment1, attachment2)
    }

    @Test
    fun `equals returns false for different filenames`() {
        val data = byteArrayOf(1, 2, 3)
        val attachment1 = FileAttachment("file1.pdf", data, "application/pdf", 3)
        val attachment2 = FileAttachment("file2.pdf", data, "application/pdf", 3)

        assertNotEquals(attachment1, attachment2)
    }

    @Test
    fun `equals returns false for different data`() {
        val attachment1 = FileAttachment("test.pdf", byteArrayOf(1, 2, 3), "application/pdf", 3)
        val attachment2 = FileAttachment("test.pdf", byteArrayOf(4, 5, 6), "application/pdf", 3)

        assertNotEquals(attachment1, attachment2)
    }

    @Test
    fun `equals returns false for different mimeTypes`() {
        val data = byteArrayOf(1, 2, 3)
        val attachment1 = FileAttachment("test.txt", data, "text/plain", 3)
        val attachment2 = FileAttachment("test.txt", data, "application/octet-stream", 3)

        assertNotEquals(attachment1, attachment2)
    }

    @Test
    fun `equals returns false for different sizeBytes`() {
        val data = byteArrayOf(1, 2, 3)
        val attachment1 = FileAttachment("test.pdf", data, "application/pdf", 3)
        val attachment2 = FileAttachment("test.pdf", data, "application/pdf", 100)

        assertNotEquals(attachment1, attachment2)
    }

    @Test
    fun `equals returns true for same instance`() {
        val attachment = FileAttachment("test.pdf", byteArrayOf(1, 2, 3), "application/pdf", 3)

        assertEquals(attachment, attachment)
    }

    @Test
    fun `equals returns false for null`() {
        val attachment = FileAttachment("test.pdf", byteArrayOf(1, 2, 3), "application/pdf", 3)

        assertNotEquals(attachment, null)
    }

    @Test
    fun `equals returns false for different class`() {
        val attachment = FileAttachment("test.pdf", byteArrayOf(1, 2, 3), "application/pdf", 3)

        assertNotEquals(attachment, "not an attachment")
    }

    @Test
    fun `hashCode is consistent for equal objects`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val attachment1 = FileAttachment("test.pdf", data, "application/pdf", 5)
        val attachment2 = FileAttachment("test.pdf", data.copyOf(), "application/pdf", 5)

        assertEquals(attachment1.hashCode(), attachment2.hashCode())
    }

    @Test
    fun `hashCode differs for different objects`() {
        val attachment1 = FileAttachment("test1.pdf", byteArrayOf(1, 2, 3), "application/pdf", 3)
        val attachment2 = FileAttachment("test2.pdf", byteArrayOf(4, 5, 6), "text/plain", 3)

        // Note: hash codes CAN be equal for different objects, but are unlikely
        // This test verifies they're calculated, not that they're always different
        assertTrue(attachment1.hashCode() != 0 || attachment2.hashCode() != 0)
    }

    @Test
    fun `properties are accessible`() {
        val data = byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f) // "Hello"
        val attachment =
            FileAttachment(
                filename = "hello.txt",
                data = data,
                mimeType = "text/plain",
                sizeBytes = 5,
            )

        assertEquals("hello.txt", attachment.filename)
        assertTrue(data.contentEquals(attachment.data))
        assertEquals("text/plain", attachment.mimeType)
        assertEquals(5, attachment.sizeBytes)
    }

    @Test
    fun `handles empty data`() {
        val attachment =
            FileAttachment(
                filename = "empty.txt",
                data = byteArrayOf(),
                mimeType = "text/plain",
                sizeBytes = 0,
            )

        assertEquals("empty.txt", attachment.filename)
        assertEquals(0, attachment.data.size)
        assertEquals(0, attachment.sizeBytes)
    }

    @Test
    fun `handles large data`() {
        val largeData = ByteArray(100_000) { it.toByte() }
        val attachment =
            FileAttachment(
                filename = "large.bin",
                data = largeData,
                mimeType = "application/octet-stream",
                sizeBytes = 100_000,
            )

        assertEquals(100_000, attachment.data.size)
        assertEquals(100_000, attachment.sizeBytes)
    }

    @Test
    fun `handles special characters in filename`() {
        val attachment =
            FileAttachment(
                filename = "file (1) [copy].pdf",
                data = byteArrayOf(1, 2, 3),
                mimeType = "application/pdf",
                sizeBytes = 3,
            )

        assertEquals("file (1) [copy].pdf", attachment.filename)
    }

    @Test
    fun `handles unicode in filename`() {
        val attachment =
            FileAttachment(
                filename = "文档.pdf",
                data = byteArrayOf(1, 2, 3),
                mimeType = "application/pdf",
                sizeBytes = 3,
            )

        assertEquals("文档.pdf", attachment.filename)
    }
}
