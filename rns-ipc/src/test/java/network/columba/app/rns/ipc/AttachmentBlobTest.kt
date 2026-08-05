package network.columba.app.rns.ipc

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AttachmentBlobTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `round trips resource-sized structured audio outside Binder`() {
        val audioBytes = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val pfd =
            AttachmentBlob.writeToPfd(
                cacheDir = tmp.root,
                imageData = null,
                imageFormat = null,
                fileAttachments = null,
                extraFields = mapOf(0x07 to listOf(0x10, audioBytes)),
            )

        val payload = AttachmentBlob.readFromPfd(pfd)
        val audio = payload.extraFields[0x07] as List<*>
        assertEquals(0x10, audio[0])
        assertArrayEquals(audioBytes, audio[1] as ByteArray)
    }

    @Test
    fun `round trips attachments and primitive extra fields together`() {
        val pfd =
            AttachmentBlob.writeToPfd(
                cacheDir = tmp.root,
                imageData = byteArrayOf(1, 2),
                imageFormat = "png",
                fileAttachments = listOf("a.bin" to byteArrayOf(3, 4)),
                extraFields = mapOf(1 to true, 2 to "value"),
            )

        val payload = AttachmentBlob.readFromPfd(pfd)
        assertArrayEquals(byteArrayOf(1, 2), payload.imageData)
        assertEquals("png", payload.imageFormat)
        assertEquals("a.bin", payload.fileAttachments.single().first)
        assertArrayEquals(byteArrayOf(3, 4), payload.fileAttachments.single().second)
        assertEquals(true, payload.extraFields[1])
        assertEquals("value", payload.extraFields[2])
    }

    @Test
    fun `rejects implausible extra field collection size before allocation`() {
        val file = File(tmp.root, "corrupt.bin")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(0x4C584D42)
            out.writeInt(2)
            out.writeInt(-1)
            out.writeInt(0)
            out.writeInt(Int.MAX_VALUE)
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        assertThrows(IOException::class.java) { AttachmentBlob.readFromPfd(pfd) }
    }
}
