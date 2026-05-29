package network.columba.app.rns.ipc

import android.os.ParcelFileDescriptor
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

/**
 * Out-of-band inbound `fieldsJson` transfer (the receive-side mirror of
 * [AttachmentBlob]). Pins the round-trip and the corrupt-length guard.
 */
@RunWith(RobolectricTestRunner::class)
class FieldsBlobTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `round-trips a large fieldsJson through a PFD unchanged`() {
        // ~400 KB — comfortably over the inline threshold, the case that used to
        // overflow the Binder transaction inline.
        val json = """{"6":"${"ab".repeat(200_000)}"}"""
        val pfd = FieldsBlob.writeToPfd(tmp.root, json)
        assertEquals(json, FieldsBlob.readFromPfd(pfd))
    }

    @Test
    fun `rejects an implausible declared length instead of allocating it`() {
        // Hand-craft a blob with a valid magic+version header but an absurd
        // length, as a corrupt/truncated stream might. readFromPfd must throw
        // before attempting a multi-GB ByteArray allocation.
        val f = File(tmp.root, "corrupt.bin")
        DataOutputStream(FileOutputStream(f)).use { out ->
            out.writeInt(0x4C584D46) // MAGIC ("LXMF")
            out.writeInt(1) // VERSION
            out.writeInt(Int.MAX_VALUE) // implausible length
        }
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        assertThrows(IOException::class.java) { FieldsBlob.readFromPfd(pfd) }
    }

    @Test
    fun `rejects a blob with the wrong magic`() {
        val f = File(tmp.root, "wrongmagic.bin")
        DataOutputStream(FileOutputStream(f)).use { out ->
            out.writeInt(0xDEADBEEF.toInt())
            out.writeInt(1)
            out.writeInt(0)
        }
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        assertThrows(IOException::class.java) { FieldsBlob.readFromPfd(pfd) }
    }
}
