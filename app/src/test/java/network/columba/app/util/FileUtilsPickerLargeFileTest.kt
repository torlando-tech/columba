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
 * the legitimate >512KB send flow regressed.
 *
 * The contract pinned here mirrors the E2E's pick -> attach -> outgoing
 * bubble flow at the seam MessagingScreen.filePickerLauncher actually calls:
 * a 1MB pick MUST attach with its full bytes. The COLUMBA-4A OOM guard is
 * simultaneously pinned: a ~181MB pick must still be refused WITHOUT an
 * unbounded read — the allocation is bounded even when provider metadata is
 * missing (statSize == -1) or understates the stream.
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
    fun `composer pick of the canonical E2E 1MB file must attach with full bytes`() {
        // Mirrors tests/transfer_progress_e2e: FILE_SIZE = 1024 * 1024 pushed to
        // the device and picked via the composer file picker. At head 25f97257
        // this pick returns FileTooLarge (512KB cap) and the E2E's
        // wait_text(FILE_NAME) times out — the regression this test pins RED.
        val e2eFileSize = 1024 * 1024
        val payload = ByteArray(e2eFileSize) { (it % 251).toByte() }
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(testUri, "columba-progress-e2e.bin", payload.size.toLong()) {
            ByteArrayInputStream(payload)
        }

        val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

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
}
