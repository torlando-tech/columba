package network.columba.app.rns.host.persistence

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.dao.PendingSignatureMessage

/**
 * Resolves unknown-key messages below the UI/IPC seam. Room's initial emission
 * handles restart; message, announce and peer-key invalidations close arrival races.
 * There is deliberately no navigation state, polling, or in-memory pending queue.
 */
internal class PendingSignatureVerification(
    private val database: ColumbaDatabase,
) {
    private val messages = database.messageDao()
    private val announces = database.announceDao()

    fun startReconciliation(scope: CoroutineScope) =
        scope.launch {
            // Do not distinctUntilChanged these counts: replacing an announce or
            // peer key, or inserting while another row resolves, can leave counts
            // unchanged but still requires reconciliation.
            combine(
                messages.observePendingSignatureCount(),
                announces.getAnnounceCountFlow(),
                database.peerIdentityDao().observePeerIdentityCount(),
            ) { _, _, _ -> Unit }.collect { reconcile() }
        }

    suspend fun reconcile() {
        try {
            var afterSender = ""
            while (true) {
                val senders = messages.getPendingSignatureSenders(afterSender, BATCH_SIZE)
                if (senders.isEmpty()) break
                for (sender in senders) {
                    val key = findDirectKey(sender) ?: continue
                    resolveSender(sender, key)
                }
                afterSender = senders.last()
            }
            if (messages.getPendingSignatureSenders("", 1).isNotEmpty()) resolvePeerKeys()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pending signature verification deferred", e)
        }
    }

    /**
     * Scan persisted keys once after direct lookups, including keys learned from
     * other-aspect announces. Each page queries only its candidate LXMF senders.
     * For S pending senders and P cached peers, key hashing is O(S + P), with
     * indexed sender lookups and only one B-row peer page/candidate map in memory.
     */
    private suspend fun resolvePeerKeys() {
        val peers = database.peerIdentityDao()
        var afterPeerHash = ""
        while (true) {
            val page = peers.getPeerIdentitiesAfter(afterPeerHash, BATCH_SIZE)
            if (page.isEmpty()) return
            val keys = mutableMapOf<String, ByteArray>()
            for (peer in page) {
                val sender = LxmfSignatureVerifier.senderHash(peer.publicKey) ?: continue
                keys[sender] = peer.publicKey
            }
            if (keys.isNotEmpty()) {
                for (sender in messages.getPendingSignatureSendersMatching(keys.keys.toList())) {
                    resolveSender(sender, keys.getValue(sender))
                }
            }
            afterPeerHash = page.last().peerHash
        }
    }

    private suspend fun findDirectKey(sender: String): ByteArray? {
        val announced = announces.getAnnounce(sender)?.publicKey
        if (announced != null && LxmfSignatureVerifier.senderHash(announced) == sender) return announced
        val cached = database.peerIdentityDao().getPeerIdentity(sender)?.publicKey
        return cached?.takeIf { LxmfSignatureVerifier.senderHash(it) == sender }
    }

    private suspend fun resolveSender(
        sender: String,
        publicKey: ByteArray,
    ) {
        var afterId = ""
        var afterIdentity = ""
        while (true) {
            val page = messages.getPendingSignatureMessages(sender, afterId, afterIdentity, BATCH_SIZE)
            if (page.isEmpty()) return
            for (pending in page) {
                resolveMessage(sender, publicKey, pending)
            }
            afterId = page.last().id
            afterIdentity = page.last().identityHash
        }
    }

    private suspend fun resolveMessage(
        sender: String,
        publicKey: ByteArray,
        pending: PendingSignatureMessage,
    ) {
        val localIdentity = database.localIdentityDao().getIdentity(pending.identityHash) ?: return
        val evidence = messages.readSignatureEvidence(pending.id, pending.identityHash) ?: return
        val status =
            LxmfSignatureVerifier.verify(
                evidence = evidence,
                messageHash = pending.id,
                senderHash = sender,
                destinationHash = localIdentity.destinationHash,
                publicKey = publicKey,
            ) ?: return
        // Conditional update scoped to the original local identity;
        // another worker or identity switch cannot overwrite a result.
        messages.resolvePendingSignature(pending.id, pending.identityHash, status)
    }

    private companion object {
        const val TAG = "SignatureVerification"
        const val BATCH_SIZE = 32
    }
}
