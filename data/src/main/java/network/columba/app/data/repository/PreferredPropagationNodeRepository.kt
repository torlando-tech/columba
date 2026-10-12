package network.columba.app.data.repository

import network.columba.app.data.db.dao.PreferredPropagationNodeDao
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin repository over [PreferredPropagationNodeDao] for the prioritized
 * preferred-propagation-node list.
 *
 * The multi-write operations ([reorder] and [remove]) delegate to the DAO's
 * `@Transaction` methods so they are atomic: a process death or crash mid-operation can
 * never leave the `priority` column in a half-applied state (e.g. two rows sharing a
 * priority, or a reorder visible to the relay writer part-way through).
 *
 * `:data` cannot reference `:rns-api`'s `NetworkRestriction`, so this repository works
 * with the raw restriction string; the `:app`-side mapping to the enum lives with the
 * selection policy (which also sees `NetworkRestriction`).
 */
@Singleton
class PreferredPropagationNodeRepository
    @Inject
    constructor(
        private val dao: PreferredPropagationNodeDao,
    ) {
        /** Live, priority-ordered view of the whole list (including disabled nodes). */
        fun observeOrdered(): Flow<List<PreferredPropagationNodeEntity>> = dao.observeOrdered()

        /** Live, priority-ordered view of the enabled candidate pool. */
        fun observeEnabled(): Flow<List<PreferredPropagationNodeEntity>> = dao.observeEnabled()

        suspend fun getEnabled(): List<PreferredPropagationNodeEntity> = dao.getEnabled()

        suspend fun findByHash(destinationHash: String): PreferredPropagationNodeEntity? =
            dao.findByHash(destinationHash)

        suspend fun contains(destinationHash: String): Boolean = dao.contains(destinationHash)

        suspend fun count(): Int = dao.count()

        /**
         * Add a node to the list, appending after any existing entries.
         * Idempotent on destination hash: if the node is already present it is a no-op
         * (preserving its existing priority/restriction), so re-adding never duplicates.
         */
        suspend fun add(
            destinationHash: String,
            networkRestriction: String,
            displayName: String? = null,
            publicKey: String? = null,
        ) {
            if (dao.contains(destinationHash)) return
            val nextPriority = dao.count()
            dao.upsert(
                PreferredPropagationNodeEntity(
                    destinationHash = destinationHash,
                    priority = nextPriority,
                    networkRestriction = networkRestriction,
                    enabled = true,
                    displayName = displayName,
                    publicKey = publicKey,
                ),
            )
        }

        /**
         * Remove a node from the list by destination hash, then re-pack the remaining
         * `priority` values (across enabled + disabled rows) so the list stays contiguous
         * and no gap is left in the ordering. Atomic (single DAO transaction).
         */
        suspend fun remove(destinationHash: String) {
            dao.removeAndRepackPriorities(destinationHash)
        }

        /**
         * Persist a new priority order. [destinationHashes] is the full new list order
         * (index = new priority). Atomic (single DAO transaction): observers see either the
         * complete old order or the complete new order, never a partial reorder.
         */
        suspend fun reorder(destinationHashes: List<String>) {
            dao.reorderPriorities(destinationHashes)
        }

        /** Update a node's transport restriction (raw `NetworkRestriction` name). */
        suspend fun setRestriction(destinationHash: String, networkRestriction: String) {
            val node = dao.findByHash(destinationHash) ?: return
            dao.upsert(node.copy(networkRestriction = networkRestriction))
        }

        /** Toggle a node's enabled flag. */
        suspend fun setEnabled(destinationHash: String, enabled: Boolean) {
            dao.setEnabled(destinationHash, enabled)
        }

        /**
         * Record that a sync to [destinationHash] (as the active relay) failed. Stores the raw
         * LXMF state [code], a human-readable [explanation], and [timestamp] so the UI can show
         * *why* the node went unreachable and the selection policy can exclude it for the
         * cooldown window. No-op for a hash that is not in the list.
         */
        suspend fun recordFailure(
            destinationHash: String,
            code: Int,
            explanation: String,
            timestamp: Long,
        ) {
            if (!dao.contains(destinationHash)) return
            dao.recordLastFailure(destinationHash, code, explanation, timestamp)
        }

        /**
         * Clear a node's recorded failure after a successful sync, so it is eligible for
         * selection again. No-op for a hash that is not in the list.
         */
        suspend fun clearFailure(destinationHash: String) {
            if (!dao.contains(destinationHash)) return
            dao.clearLastFailure(destinationHash)
        }

        /**
         * Whether a node's *most recent* recorded failure is still inside the unreachable
         * cooldown window relative to [nowMillis]. This is the single source of truth for
         * whether the selection policy should skip the node: a node is excluded only while a
         * recent failure is on record, so a recovered relay (or a stale pre-cooldown failure)
         * is never excluded forever. Null failure (never seen unreachable) → false.
         */
        fun isCurrentlyUnreachable(
            node: PreferredPropagationNodeEntity,
            nowMillis: Long,
            cooldownMillis: Long,
        ): Boolean {
            val failedAt = node.lastFailureAt ?: return false
            return (nowMillis - failedAt) in 0..cooldownMillis
        }
    }
