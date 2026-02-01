package com.lxmf.messenger.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for FileUtils Android-specific functions using MockK.
 * Tests readFileFromUri and getFilename which require Android Context.
 */
// Suppress NoRelaxedMocks for Android framework classes (Context, ContentResolver, Cursor, Uri)
// which have many methods that are not relevant to these tests
@Suppress("NoRelaxedMocks")
class FileUtilsRobolectricTest {
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== readFileFromUri Tests ==========

    @Test
    fun `readFileFromUri returns FileAttachment for valid file`() {
        val testData = "Hello, World!".toByteArray()
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        // Setup cursor for filename
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "test_file.txt"

        // Setup input stream
        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(testData)
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNotNull(result)
        assertEquals("test_file.txt", result!!.filename)
        assertEquals(testData.size, result.sizeBytes)
        assertEquals("text/plain", result.mimeType)
    }

    @Test
    fun `readFileFromUri handles empty file`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "empty.txt"

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNotNull(result)
        assertEquals("empty.txt", result!!.filename)
        assertEquals(0, result.sizeBytes)
    }

    @Test
    fun `readFileFromUri uses unknown filename when cursor returns no name`() {
        val testData = "test".toByteArray()
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        // Cursor returns false for moveToFirst (empty)
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns false
        every { testUri.lastPathSegment } returns null

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(testData)
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNotNull(result)
        assertEquals("unknown", result!!.filename)
    }

    @Test
    fun `readFileFromUri returns correct size for binary data`() {
        val binaryData = ByteArray(1024) { it.toByte() }
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "binary.bin"

        every { mockContentResolver.openInputStream(testUri) } returns ByteArrayInputStream(binaryData)
        every { mockContentResolver.getType(testUri) } returns "application/octet-stream"

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNotNull(result)
        assertEquals(1024, result!!.sizeBytes)
        assertEquals(1024, result.data.size)
    }

    @Test
    fun `readFileFromUri returns null when openInputStream fails`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "file.txt"

        // Return null for input stream
        every { mockContentResolver.openInputStream(testUri) } returns null
        every { mockContentResolver.getType(testUri) } returns "text/plain"

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNull(result)
    }

    @Test
    fun `readFileFromUri returns null on exception`() {
        val testUri = mockk<Uri>()

        // Throw exception on query
        every { mockContentResolver.query(testUri, null, null, null, null) } throws RuntimeException("Test error")

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNull(result)
    }

    // ========== getFilename Tests ==========

    @Test
    fun `getFilename returns display name from cursor`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "my_document.pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("my_document.pdf", result)
    }

    @Test
    fun `getFilename returns last path segment when cursor is empty`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns false
        every { testUri.lastPathSegment } returns "file.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("file.txt", result)
    }

    @Test
    fun `getFilename handles cursor without display name column`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns -1 // Column not found
        every { testUri.lastPathSegment } returns "fallback.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        // When column index is -1, the inner block returns null, which triggers
        // the ?: operator to use lastPathSegment as fallback
        assertEquals("fallback.txt", result)
    }

    @Test
    fun `getFilename handles special characters in filename`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "file (1) [copy].pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("file (1) [copy].pdf", result)
    }

    @Test
    fun `getFilename handles unicode filename`() {
        val testUri = mockk<Uri>()
        val mockCursor = mockk<Cursor>(relaxed = true)

        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "文档.pdf"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("文档.pdf", result)
    }

    @Test
    fun `getFilename returns last path segment when query returns null`() {
        val testUri = mockk<Uri>()

        every { mockContentResolver.query(testUri, null, null, null, null) } returns null
        every { testUri.lastPathSegment } returns "segment.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("segment.txt", result)
    }

    @Test
    fun `getFilename returns last path segment on exception`() {
        val testUri = mockk<Uri>()

        every { mockContentResolver.query(testUri, null, null, null, null) } throws RuntimeException("Query failed")
        every { testUri.lastPathSegment } returns "exception_fallback.txt"

        val result = FileUtils.getFilename(mockContext, testUri)

        assertEquals("exception_fallback.txt", result)
    }
}
