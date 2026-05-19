package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * AIDL-friendly Parcelable form of a single file attachment.
 *
 * The Kotlin RnsLxmf surface accepts `List<Pair<String, ByteArray>>`; the AIDL
 * boundary can't carry `List<byte[]>` or `Pair`, so :rns-ipc converts between
 * the Pair form and this wrapper at the seam. Wrapper-only type — no domain
 * code outside the IPC layer should construct or unpack these directly.
 */
@Parcelize
data class FileAttachment(
    val name: String,
    val data: ByteArray,
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FileAttachment
        if (name != other.name) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
