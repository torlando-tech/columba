package network.columba.app.rns.ipc

import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Out-of-band transfer of a received message's `fieldsJson` across the
 * [network.columba.app.rns.ipc.callback.IRnsMessageCallback] AIDL boundary —
 * the receive-direction mirror of [AttachmentBlob].
 *
 * Inbound LXMF messages carry image/file payloads hex-encoded inside
 * `ReceivedMessage.fieldsJson`; a received photo makes that string several
 * hundred KB (hex doubles the byte size), which overflows the ~1 MB shared
 * Binder transaction buffer when `onMessage` marshals it inline and throws
 * `android.os.TransactionTooLargeException` in the `:reticulum` process. The
 * send direction already solved this for outbound attachments (see
 * [AttachmentBlob]); this is the same trick for the inbound `fieldsJson`.
 *
 * Large `fieldsJson` crosses as a read-only [ParcelFileDescriptor] over a
 * delete-on-close temp file: the server (`:reticulum`) writes it, the client
 * (UI) streams it back. Both processes share the app UID, so the fd and the
 * immediately-unlinked inode are shared safely; the client never resolves the
 * path. Small messages keep riding inline (null PFD) for zero overhead.
 *
 * Wire format (big-endian [DataOutputStream]), versioned so a format bump
 * rejects stale blobs instead of mis-parsing:
 * ```
 *   int MAGIC   = 0x4C584D46 ("LXMF")
 *   int VERSION = 1
 *   int len                       (UTF-8 byte length of fieldsJson)
 *   byte[len] fieldsJson          (UTF-8)
 * ```
 */
internal object FieldsBlob {
    private const val MAGIC = 0x4C584D46 // "LXMF"
    private const val VERSION = 1
    private const val TEMP_SUBDIR = "rns-ipc-rx"

    // Sanity ceiling on the declared payload length. A corrupt/garbage blob that
    // still passes the magic+version check could otherwise carry a length up to
    // Int.MAX (~2 GB) and trigger a huge ByteArray allocation + GC/LMK churn
    // before the caller's runCatching fallback. 128 MB is far above any
    // legitimate hex-encoded fieldsJson — even a maxed ~32 MB attachment doubles
    // to ~64 MB of hex — yet well under Int.MAX.
    private const val MAX_FIELDS_BYTES = 128 * 1024 * 1024

    /**
     * Serialize [fieldsJson] to a delete-on-close temp file under [cacheDir] and
     * return a read-only [ParcelFileDescriptor] over it. The returned PFD is
     * owned by the caller, which must close it once the transaction is
     * delivered — the server's [readFromPfd] reads its own dup.
     */
    @Throws(IOException::class)
    fun writeToPfd(cacheDir: File, fieldsJson: String): ParcelFileDescriptor {
        val dir = File(cacheDir, TEMP_SUBDIR).apply { mkdirs() }
        val tempFile = File.createTempFile("lxmf-fields-", ".bin", dir)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                val bytes = fieldsJson.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            // Unlink immediately (delete-on-close): the open fd keeps the inode
            // alive, so an interrupted delivery leaves nothing on disk.
            tempFile.delete()
        }
    }

    /** Inverse of [writeToPfd]: read the `fieldsJson` back from [pfd] and close it. */
    @Throws(IOException::class)
    fun readFromPfd(pfd: ParcelFileDescriptor): String {
        DataInputStream(BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd))).use { inp ->
            val magic = inp.readInt()
            val version = inp.readInt()
            if (magic != MAGIC || version != VERSION) {
                throw IOException(
                    "Bad fields blob header: magic=0x${Integer.toHexString(magic)} version=$version",
                )
            }
            // Reject a negative or implausibly large length (a corrupt/garbage blob
            // could carry up to Int.MAX) before allocating, so we fail fast instead
            // of churning GC/LMK on a doomed multi-GB ByteArray.
            val len = inp.readInt()
            if (len < 0 || len > MAX_FIELDS_BYTES) {
                throw IOException("Bad fields blob: implausible length ($len)")
            }
            val bytes = ByteArray(len).also { inp.readFully(it) }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
