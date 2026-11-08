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
 * handles restart; invalidations from either table close announce/insert races.
 * There is deliberately no navigation state, polling, or in-memory pending queue.
 */
internal class PendingSignatureVerification(
    private val database: ColumbaDatabase,
) {
    private val messages = database.messageDao()
    private val announces = database.announceDao()

    fun startReconciliation(scope: CoroutineScope) =
        scope.launch {
            // Do not distinctUntilChanged these counts: replacing an announce
            // key or inserting a pending row while another resolves can leave
            // the count unchanged, but still requires another reconciliation.
            combine(messages.observePendingSignatureCount(), announces.getAnnounceCountFlow()) { _, _ -> Unit }
                .collect { reconcile() }
        }

    suspend fun reconcile() {
        try {
            var afterSender = ""
            while (true) {
                val senders = messages.getPendingSignatureSenders(afterSender, BATCH_SIZE)
                if (senders.isEmpty()) break
                for (sender in senders) {
                    val key = findKey(sender) ?: continue
                    resolveSender(sender, key)
                }
                afterSender = senders.last()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pending signature verification deferred", e)
        }
    }

    @Suppress("ReturnCount") // Prefer matching-key exits over nesting cache and paged lookups.
    private suspend fun findKey(sender: String): ByteArray? {
        val announced = announces.getAnnounce(sender)?.publicKey
        if (announced != null && LxmfSignatureVerifier.senderHash(announced) == sender) return announced
        val peers = database.peerIdentityDao()
        val cached = peers.getPeerIdentity(sender)?.publicKey
        if (cached != null && LxmfSignatureVerifier.senderHash(cached) == sender) return cached
        // An announce for another aspect (e.g. lxst.telephony) carries the
        // same identity key. Scan persisted keys in bounded pages only when
        // there is no direct delivery-announce lookup for this pending sender.
        var offset = 0
        while (true) {
            val page = peers.getPeerIdentitiesBatch(BATCH_SIZE, offset)
            if (page.isEmpty()) return null
            page.firstOrNull { LxmfSignatureVerifier.senderHash(it.publicKey) == sender }?.let { return it.publicKey }
            offset += page.size
        }
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
