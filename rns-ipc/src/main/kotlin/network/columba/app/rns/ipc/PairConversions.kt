package network.columba.app.rns.ipc

import network.columba.app.rns.api.model.AnnounceRestoreEntry
import network.columba.app.rns.api.model.PeerIdentityEntry

/**
 * Conversions between the Kotlin `List<Pair<String, ByteArray>>` shape that
 * the [network.columba.app.rns.api.RnsCore] / [network.columba.app.rns.api.RnsLxmf]
 * interfaces speak and the Parcelable wrapper-list shape AIDL requires
 * (`List<PeerIdentityEntry>` / `List<AnnounceRestoreEntry>`). AIDL can carry
 * neither raw `Pair` nor `List<byte[]>`; the wrappers exist solely to satisfy
 * the marshaller.
 *
 * LXMF file attachments used to convert through a `FileAttachment` wrapper here
 * too, but their bytes now cross out-of-band via [AttachmentBlob] (a PFD) to
 * stay under the Binder transaction limit, so no Pair⇄wrapper hop is needed.
 */

internal fun List<Pair<String, ByteArray>>.toPeerIdentityEntries(): List<PeerIdentityEntry> =
    map { (hashHex, publicKey) -> PeerIdentityEntry(hashHex, publicKey) }

internal fun List<PeerIdentityEntry>.toPeerIdentityPairs(): List<Pair<String, ByteArray>> =
    map { it.hashHex to it.publicKey }

internal fun List<Pair<String, ByteArray>>.toAnnounceRestoreEntries(): List<AnnounceRestoreEntry> =
    map { (destHashHex, announceBytes) -> AnnounceRestoreEntry(destHashHex, announceBytes) }

internal fun List<AnnounceRestoreEntry>.toAnnounceRestorePairs(): List<Pair<String, ByteArray>> =
    map { it.destHashHex to it.announceBytes }
