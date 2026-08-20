package network.columba.app.rns.host.persistence

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.PeerActivityType
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.DeliveryStatusUpdate

/** Owns the durable delivery inbox and verified-proof activity projection. */
internal class PendingDeliveryPersistence(
    private val database: ColumbaDatabase,
) {
    private val messageDao = database.messageDao()
    private val peerActivityDao = database.peerActivityDao()
    private val pendingDao = database.pendingDeliveryStatusDao()

    fun startReconciliation(scope: CoroutineScope) {
        scope.launch {
            reconcileWithRetry()
            messageDao.observeOutgoingMessageCount().collect {
                reconcileWithRetry()
            }
        }
    }

    suspend fun persistProof(
        update: DeliveryStatusUpdate,
        receivedAt: Long,
    ): Boolean =
        try {
            val identityHash = update.originatingIdentityHash?.takeIf { it.isNotBlank() } ?: return false
            if (!PeerActivityPolicy.isVerifiedDeliveryProof(update.status)) return false
            val message = messageDao.getOutgoingMessageById(update.messageHash, identityHash) ?: return false
            peerActivityDao.recordActivityOnce(
                eventId = "proof:$identityHash:${update.messageHash}",
                destinationHash = message.conversationHash,
                receivedAt = receivedAt,
                activityType = PeerActivityType.PROOF,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting delivery-proof activity for ${update.messageHash}", e)
            false
        }

    suspend fun persistStatus(update: DeliveryStatusUpdate): Boolean {
        val safeHash = update.messageHash.take(16)
        val now = System.currentTimeMillis()
        val identityHash = update.originatingIdentityHash?.takeIf { it.isNotBlank() }
        if (identityHash == null) {
            Log.w(TAG, "Missing originating identity - rejecting delivery status for $safeHash")
            return false
        }
        try {
            enqueue(update, identityHash, now)
        } catch (e: Exception) {
            Log.e(TAG, "Could not durably enqueue delivery status for $safeHash", e)
            return false
        }
        cleanupInbox(now, safeHash)
        reconcileWithRetry()
        return true
    }

    private suspend fun enqueue(
        update: DeliveryStatusUpdate,
        identityHash: String,
        now: Long,
    ) {
        // Attempt ownership is captured before callbacks can race an identity switch.
        // Never substitute mutable active Room state when provenance is absent.
        pendingDao.reduce(
            identityHash,
            update.messageHash,
            update.status.wireValue,
            update.status.effectiveDeliveryMethod(),
            now,
        )
    }

    private suspend fun cleanupInbox(
        now: Long,
        safeHash: String,
    ) {
        runCatching {
            pendingDao.deleteOlderThan(now - PENDING_DELIVERY_TTL_MS)
            pendingDao.trimToNewest(MAX_PENDING_DELIVERY_EVENTS)
        }.onFailure {
            Log.w(TAG, "Delivery inbox cleanup deferred for $safeHash")
        }
    }

    suspend fun reconcileWithRetry() {
        val delays = listOf(100L, 500L, 2_000L)
        repeat(delays.size + 1) { attempt ->
            if (reconcile()) return
            if (attempt < delays.size) delay(delays[attempt])
        }
    }

    suspend fun reconcile(): Boolean =
        try {
            pendingDao.oldest(RECONCILIATION_BATCH_SIZE).forEach { pending ->
                database.withTransaction {
                    val message = messageDao.getMessageById(pending.messageHash, pending.identityHash)
                    if (message?.isFromMe == true) {
                        // A zero-row update means the event was stale and was atomically rejected.
                        messageDao.applyDeliveryStatus(
                            pending.messageHash,
                            pending.identityHash,
                            pending.status,
                            pending.deliveryMethod,
                        )
                        pendingDao.delete(pending.identityHash, pending.messageHash)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Delivery inbox reconciliation deferred: ${e.javaClass.simpleName}")
            false
        }

    private fun DeliveryStatus.effectiveDeliveryMethod(): String? =
        if (this == DeliveryStatus.RETRYING_PROPAGATED || this == DeliveryStatus.PROPAGATED) {
            "propagated"
        } else {
            null
        }

    private companion object {
        const val TAG = "ServicePersistenceManager"
        const val PENDING_DELIVERY_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_PENDING_DELIVERY_EVENTS = 512
        const val RECONCILIATION_BATCH_SIZE = 128
    }
}
