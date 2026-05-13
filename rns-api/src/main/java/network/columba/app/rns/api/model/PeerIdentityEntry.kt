package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * AIDL-friendly Parcelable form of a `Pair<String, ByteArray>` entry passed to
 * [RnsCore.restorePeerIdentities] — `hashHex` is the peer's hex identity hash,
 * `publicKey` is the cached public-key bytes recovered from app storage at
 * startup.
 *
 * Wrapper-only type — domain code holds `List<Pair<String, ByteArray>>`;
 * :rns-ipc converts at the seam.
 */
@Parcelize
data class PeerIdentityEntry(
    val hashHex: String,
    val publicKey: ByteArray,
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerIdentityEntry
        if (hashHex != other.hashHex) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = hashHex.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
