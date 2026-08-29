package network.columba.app.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Unit tests for FileUtils Android-specific functions using MockK.
 * Tests readFileFromUri and getFilename which require Android Context.
 */
// Suppress NoRelaxedMocks for Android framework classes (Context, ContentResolver, Cursor, Uri)
// which have many methods that are not relevant to these tests
@Suppress("NoRelaxedMocks")
class FileUtilsRobolectricTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun `cleanupAllTempFiles removes only stale voice cache artifacts`() {
        val cacheDir = temporaryFolder.newFolder("cache")
        every { mockContext.cacheDir } returns cacheDir
        val voiceNotes = File(cacheDir, "voice-notes").apply { mkdirs() }
        val staleFiles =
            listOf(
                File(voiceNotes, "recording.ogg"),
                File(cacheDir, "voice_message_stale.ogg"),
                File(cacheDir, "voice_preview_stale.wav"),
                File(cacheDir, "voice_waveform_stale.ogg"),
            ).onEach { file ->
                file.writeText("stale")
                assertTrue(file.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
            }
        val recentFiles =
            listOf(
                File(voiceNotes, "recent.ogg"),
                File(cacheDir, "voice_message_recent.ogg"),
                File(cacheDir, "voice_preview_recent.wav"),
                File(cacheDir, "voice_waveform_recent.ogg"),
            ).onEach { it.writeText("recent") }
        val unrelated =
            File(cacheDir, "unrelated_stale.ogg").apply {
                writeText("keep")
                assertTrue(setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
            }

        assertEquals(4, FileUtils.cleanupAllTempFiles(mockContext, maxAgeMs = 60 * 60 * 1000))

        staleFiles.forEach { assertFalse(it.exists()) }
        recentFiles.forEach { assertTrue(it.exists()) }
        assertTrue(unrelated.exists())
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

    // ========== readFileFromUriWithResult Tests ==========

    @Test
    fun `readFileFromUriWithResult returns FileTooLarge for oversized file before reading bytes`() {
        // Sentry COLUMBA-4A: picking a ~181MB file made readFileFromUriWithResult
        // allocate the ENTIRE file in memory via readBytes() because both size
        // guards were disabled at Int.MAX_VALUE (regression 30075665). The
        // pre-check (getFileSize via openFileDescriptor().statSize) must
        // short-circuit BEFORE any bytes are read.
        //
        // Honesty: a literal Android heap OOM cannot be deterministically forced
        // in a JVM unit test. This deterministic seam reproduces the Sentry
        // SYMPTOM — the guard trips before the unbounded allocation — not the
        // literal OOM.
        val oversize = 181_374_640L // mirrors the Sentry allocation size
        val testUri = mockk<Uri>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        every { mockPfd.statSize } returns oversize
        every { mockContentResolver.openFileDescriptor(testUri, "r") } returns mockPfd
        // Deterministic mirror of the Sentry event: the mocked resolver hands
        // over the full 181MB file as a LAZY stream — zero allocation until a
        // read() actually happens. On the buggy base (guard disabled at
        // Int.MAX_VALUE) readFileFromUriWithResult reads all of it via
        // readBytes() and returns Success with 181MB of data — the unbounded
        // allocation that OOMed the device. The fix must return FileTooLarge
        // BEFORE this stream is touched at all (lazy stream stays unallocated,
        // proving no unbounded read happened).
        every { mockContentResolver.openInputStream(testUri) } returns
            lazyOversizeStream(oversize.toInt())

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        // Bounded message: on the buggy base `result` is a Success whose
        // toString() embeds the full 181MB payload — printing it would blow
        // the test JVM / report writer. Only the class name is needed as
        // failure evidence.
        assertTrue(
            "Expected FileTooLarge for ${oversize}B file, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        result as FileUtils.FileReadResult.FileTooLarge
        assertEquals(oversize, result.actualSize)
        assertEquals(FileUtils.MAX_SINGLE_FILE_SIZE, result.maxSize)
        // The guard must short-circuit before readBytes(): the full-byte read
        // (and even filename lookup) must never start.
        verify(exactly = 0) { mockContentResolver.openInputStream(testUri) }
        verify(exactly = 0) { mockContentResolver.query(testUri, null, null, null, null) }
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

    // ========== Test helpers ==========

    /**
     * Build an InputStream that serves [size] bytes with zero upfront
     * allocation — memory is only consumed as read() is actually called.
     * Used to model an oversized picked file: a correct size-cap guard
     * returns before any read() call (nothing allocated); the unbounded base
     * code triggers the full read and returns the entire 181MB payload —
     * making the failure evidence "read 181MB into memory" instead of a JVM OOM.
     */
    private fun lazyOversizeStream(size: Int): InputStream =
        object : InputStream() {
            private var remaining = size
            override fun read(): Int {
                if (remaining <= 0) return -1
                remaining--
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len, remaining)
                b.fill(0, off, off + toRead)
                remaining -= toRead
                return toRead
            }
        }
}
