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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Regression tests for the COLUMBA-4A canonical-CI regression (Greploop
 * iteration 2 on PR #1113): the restored 512KB single-file cap made the Real
 * LXMF Resource Progress E2E red, because that test picks a 1MB file in the
 * composer (tests/transfer_progress_e2e FILE_SIZE = 1024 * 1024) and asserts
 * it reaches the outgoing bubble with verified progress. With the cap at
 * 512KB the pick was rejected (FileTooLarge -> toast -> never attached), so
 * the legitimate >512KB send flow regressed. RED at 25f97257 as
 * 'Expected Success for the canonical 1MB E2E pick, got: FileTooLarge'.
 *
 * The fix routes composer SEND picks through FileUtils.readFileForSendWithResult
 * bounded to MAX_TOTAL_ATTACHMENT_SIZE (32MB) — the same per-file ceiling
 * MessagingViewModel already enforces before handing bytes to Reticulum —
 * while the generic readFileFromUriWithResult keeps the 512KB fast-path cap.
 *
 * These tests mirror the E2E's pick -> attach -> outgoing bubble contract at
 * the seam MessagingScreen.filePickerLauncher calls, and pin that the
 * COLUMBA-4A OOM guard is NOT relaxed: a ~181MB pick must be refused WITHOUT
 * an unbounded read, including when provider metadata is missing
 * (statSize == -1) or understates the stream.
 */
// Suppress NoRelaxedMocks for Android framework classes (Context, ContentResolver, Cursor, Uri)
// which have many methods that are not relevant to these tests
@Suppress("NoRelaxedMocks")
class FileUtilsPickerLargeFileTest {
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

    private fun stubPick(
        uri: Uri,
        filename: String,
        statSize: Long,
        streamProvider: () -> InputStream,
    ) {
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockContentResolver.query(uri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns filename
        every { mockContentResolver.getType(uri) } returns "application/octet-stream"
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        every { mockPfd.statSize } returns statSize
        every { mockContentResolver.openFileDescriptor(uri, "r") } returns mockPfd
        every { mockContentResolver.openInputStream(uri) } answers { streamProvider() }
    }

    @Test
    fun `composer send pick of the canonical E2E 1MB file must attach with full bytes`() {
        // Mirrors tests/transfer_progress_e2e: FILE_SIZE = 1024 * 1024 pushed
        // to the device and picked via the composer file picker. At head
        // 25f97257 this pick returned FileTooLarge (512KB cap) and the E2E's
        // wait_text(FILE_NAME) timed out — the regression this test pins RED.
        val e2eFileSize = 1024 * 1024
        val payload = ByteArray(e2eFileSize) { (it % 251).toByte() }
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(testUri, "columba-progress-e2e.bin", payload.size.toLong()) {
            ByteArrayInputStream(payload)
        }

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected Success for the canonical 1MB E2E pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.Success,
        )
        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals("columba-progress-e2e.bin", attachment.filename)
        assertEquals(e2eFileSize, attachment.sizeBytes)
        assertTrue("Attached bytes must match the picked file", payload.contentEquals(attachment.data))
    }

    @Test
    fun `composer send pick rejects the Sentry 181MB file via pre-check before any bytes are read`() {
        // The COLUMBA-4A OOM scenario (Sentry fatal on a ~181MB pick) must
        // stay refused after the send-path cap is raised to 32MB: the size
        // pre-check must short-circuit BEFORE openInputStream is touched.
        val oversize = 181_374_640L
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(oversizeUri = testUri, oversize = oversize)

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for ${oversize}B send pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        result as FileUtils.FileReadResult.FileTooLarge
        assertEquals(oversize, result.actualSize)
        assertEquals(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE, result.maxSize)
        verify(exactly = 0) { mockContentResolver.openInputStream(testUri) }
    }

    @Test
    fun `composer send pick rejects unknown statSize oversize stream with a bounded read`() {
        // Even on the send path, metadata alone cannot be trusted: statSize
        // -1 over a ~181MB lazy stream must abort near the 32MB cap, never
        // allocating the full stream (the original OOM hole, Greptile P1).
        val streamSize = 181_374_640
        val testUri = mockk<Uri>(relaxed = true)
        val counting = CountingLazyStream(streamSize)
        stubPick(testUri, "mystery.bin", -1L) { counting }

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for unknown-size ${streamSize}B send pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        assertTrue(
            "Bounded send read must abort near the cap, but consumed ${counting.totalRead}B " +
                "of a ${streamSize}B stream (cap ${FileUtils.MAX_TOTAL_ATTACHMENT_SIZE})",
            counting.totalRead <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE + MAX_SLACK_BYTES,
        )
    }

    @Test
    fun `generic readFileFromUriWithResult keeps the 512KB fast-path cap`() {
        // The larger bound is scoped to the composer send seam only. The
        // generic reader used elsewhere must keep rejecting files above
        // MAX_SINGLE_FILE_SIZE so its accepted 181MB pre-check test and all
        // existing callers stay byte-for-byte in contract.
        val oneMB = 1024 * 1024
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(testUri, "one-mb.bin", oneMB.toLong()) {
            ByteArrayInputStream(ByteArray(oneMB))
        }

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for 1MB on the generic 512KB-capped reader, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        result as FileUtils.FileReadResult.FileTooLarge
        assertEquals(FileUtils.MAX_SINGLE_FILE_SIZE, result.maxSize)
    }

    private fun stubPick(
        oversizeUri: Uri,
        oversize: Long,
    ) = stubPick(oversizeUri, "huge.bin", oversize) {
        // Must never be reached when the pre-check works; hand back a lazy
        // zero-allocation stream so an accidental read cannot OOM the JVM.
        CountingLazyStream(oversize.toInt())
    }

    /** Allowed slack beyond the cap for one read buffer before aborting. */
    private companion object {
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
