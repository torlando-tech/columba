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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream

/**
 * Regression tests for the COLUMBA-4A correction (Greptile P1 on PR #1113):
 * "Unknown sizes bypass read cap".
 *
 * The approved fix relies on a metadata pre-check (statSize from
 * openFileDescriptor) to reject oversized picks BEFORE reading. A content
 * provider may report statSize == -1 (unknown) or understate the stream size,
 * which passes the pre-check and lands in readBytes() — the exact unbounded
 * in-memory allocation this ticket exists to eliminate.
 *
 * These tests pin the deterministic seam: a provider with unknown/understated
 * metadata over a stream larger than MAX_SINGLE_FILE_SIZE. The read must be
 * REJECTED (FileTooLarge / null) and the stream read itself must be BOUNDED —
 * the implementation may never consume more than a constant slack beyond the
 * cap, so no unbounded allocation can happen regardless of provider metadata.
 */
// Suppress NoRelaxedMocks for Android framework classes (Context, ContentResolver, Cursor, Uri)
// which have many methods that are not relevant to these tests
@Suppress("NoRelaxedMocks")
class FileUtilsUnknownSizeReadTest {
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

    private fun stubMetadataOnly(uri: Uri) {
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockContentResolver.query(uri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "mystery.bin"
        every { mockContentResolver.getType(uri) } returns "application/octet-stream"
    }

    private fun stubStatSize(
        uri: Uri,
        statSize: Long,
    ) {
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        every { mockPfd.statSize } returns statSize
        every { mockContentResolver.openFileDescriptor(uri, "r") } returns mockPfd
    }

    @Test
    fun `readFileFromUriWithResult rejects unknown statSize stream and bounds the read`() {
        // Provider reports statSize = -1 (unknown) over a ~8MB lazy stream.
        // The pre-check cannot fire; the stream read itself MUST be bounded
        // and the result MUST be FileTooLarge, not Success with 8MB in RAM.
        val streamSize = 8 * 1024 * 1024
        val testUri = mockk<Uri>(relaxed = true)
        stubMetadataOnly(testUri)
        stubStatSize(testUri, -1L)
        val counting = CountingLazyStream(streamSize)
        every { mockContentResolver.openInputStream(testUri) } returns counting

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for unknown-size ${streamSize}B stream, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        result as FileUtils.FileReadResult.FileTooLarge
        assertEquals(FileUtils.MAX_SINGLE_FILE_SIZE, result.maxSize)
        // Rejecting AFTER reading everything is still an OOM: the read itself
        // must stop shortly after the cap (one buffer of slack allowed).
        assertTrue(
            "Bounded read must abort near the cap, but consumed ${counting.totalRead}B " +
                "of a ${streamSize}B stream (cap ${FileUtils.MAX_SINGLE_FILE_SIZE})",
            counting.totalRead <= FileUtils.MAX_SINGLE_FILE_SIZE + BoundedReadAssertion.MAX_SLACK_BYTES,
        )
    }

    @Test
    fun `readFileFromUriWithResult rejects understated statSize stream and bounds the read`() {
        // Provider lies (or is simply wrong): statSize = 100, stream is ~8MB.
        val streamSize = 8 * 1024 * 1024
        val testUri = mockk<Uri>(relaxed = true)
        stubMetadataOnly(testUri)
        stubStatSize(testUri, 100L)
        val counting = CountingLazyStream(streamSize)
        every { mockContentResolver.openInputStream(testUri) } returns counting

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for understated-size stream, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        assertTrue(
            "Bounded read must abort near the cap, but consumed ${counting.totalRead}B " +
                "of a ${streamSize}B stream (cap ${FileUtils.MAX_SINGLE_FILE_SIZE})",
            counting.totalRead <= FileUtils.MAX_SINGLE_FILE_SIZE + BoundedReadAssertion.MAX_SLACK_BYTES,
        )
    }

    @Test
    fun `readFileFromUri returns null for unknown statSize stream and bounds the read`() {
        // Sibling parity: readFileFromUri has no post-read cap at all today.
        val streamSize = 8 * 1024 * 1024
        val testUri = mockk<Uri>(relaxed = true)
        stubMetadataOnly(testUri)
        stubStatSize(testUri, -1L)
        val counting = CountingLazyStream(streamSize)
        every { mockContentResolver.openInputStream(testUri) } returns counting

        val result = FileUtils.readFileFromUri(mockContext, testUri)

        assertNull(result)
        assertTrue(
            "Bounded read must abort near the cap, but consumed ${counting.totalRead}B " +
                "of a ${streamSize}B stream (cap ${FileUtils.MAX_SINGLE_FILE_SIZE})",
            counting.totalRead <= FileUtils.MAX_SINGLE_FILE_SIZE + BoundedReadAssertion.MAX_SLACK_BYTES,
        )
    }

    @Test
    fun `readFileFromUriWithResult still succeeds for unknown statSize within the cap`() {
        // The bounded reader must not regress small unknown-size streams
        // (e.g. pipes / streamed providers that report -1 but fit in memory).
        val data = ByteArray(64 * 1024) { (it % 251).toByte() }
        val testUri = mockk<Uri>(relaxed = true)
        stubMetadataOnly(testUri)
        stubStatSize(testUri, -1L)
        every { mockContentResolver.openInputStream(testUri) } returns java.io.ByteArrayInputStream(data)

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(
            "Expected Success for in-cap unknown-size stream, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.Success,
        )
        result as FileUtils.FileReadResult.Success
        assertEquals(data.size, result.attachment.sizeBytes)
    }

    @Test
    fun `readFileFromUriWithResult accepts a stream exactly at the cap`() {
        // Boundary: exactly MAX_SINGLE_FILE_SIZE bytes must still be accepted
        // (the cap rejects strictly-larger files, matching the pre-check '>').
        val data = ByteArray(FileUtils.MAX_SINGLE_FILE_SIZE)
        val testUri = mockk<Uri>(relaxed = true)
        stubMetadataOnly(testUri)
        stubStatSize(testUri, -1L)
        every { mockContentResolver.openInputStream(testUri) } returns java.io.ByteArrayInputStream(data)

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(
            "Expected Success for exactly-cap-sized stream, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.Success,
        )
    }

    /** Allowed slack beyond the cap for one read buffer before aborting. */
    private object BoundedReadAssertion {
        const val MAX_SLACK_BYTES = 64 * 1024
    }

    /**
     * InputStream that serves [size] bytes with zero upfront allocation and
     * counts every byte actually consumed, so tests can assert the read was
     * aborted near the cap instead of pulling the whole stream into memory.
     */
    private class CountingLazyStream(size: Int) : InputStream() {
        @Volatile
        var totalRead: Int = 0
            private set

        private var remaining = size

        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            totalRead++
            return 0
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len, remaining)
            b.fill(0, off, off + toRead)
            remaining -= toRead
            totalRead += toRead
            return toRead
        }
    }
}
