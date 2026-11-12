package network.columba.app.service

import android.util.Log
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.rns.host.util.PeerNameResolver

/**
 * Resolve a call peer's display name from the identity hash carried by
 * [network.columba.app.rns.api.model.CallState].
 *
 * Lookup chain: contact custom nickname (via the announce's destination hash),
 * then the announce's advertised peer name; null when neither is usable, letting
 * the notification fall back to a formatted hash. Used by the incoming-call
 * notification; the `:reticulum` service resolves its own ongoing-call peer name
 * in-process, since it cannot reach these app repositories.
 */
internal object CallPeerNameResolver {
    suspend fun resolve(
        identityHash: String,
        announceRepository: AnnounceRepository,
        contactRepository: ContactRepository,
    ): String? =
        runCatching {
            val announce = announceRepository.findByIdentityHash(identityHash) ?: return@runCatching null
            val nickname = contactRepository.getContact(announce.destinationHash)?.customNickname
            when {
                PeerNameResolver.isValidPeerName(nickname) -> nickname
                PeerNameResolver.isValidPeerName(announce.peerName) -> announce.peerName
                else -> null
            }
        }
            .onFailure { Log.w("CallPeerNameResolver", "Call peer name lookup failed: ${it.message}") }
            .getOrNull()
}
