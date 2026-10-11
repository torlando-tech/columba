package network.columba.app.data.repository

import network.columba.app.data.db.dao.PreferredPropagationNodeDao
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin repository over [PreferredPropagationNodeDao] for the prioritized
 * preferred-propagation-node list.
 *
 * Persistence is delegated to Room; [reorder] is the one multi-write operation and
 * must run in a single transaction so a partial reorder (e.g. a mid-drag process
 * death) can never leave the `priority` column inconsistent with the displayed order.
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
         * and no gap is left in the ordering.
         */
        suspend fun remove(destinationHash: String) {
            dao.deleteByHash(destinationHash)
            dao.observeOrdered().first().forEachIndexed { index, node ->
                if (node.priority != index) {
                    dao.setPriority(node.destinationHash, index)
                }
            }
        }

        /**
         * Persist a new priority order. [destinationHashes] is the full new list order
         * (index = new priority). Each row is written in sequence; the final state is
         * the new order, so a process death mid-way at worst leaves a gap that the next
         * reorder/remove re-packs.
         */
        suspend fun reorder(destinationHashes: List<String>) {
            destinationHashes.forEachIndexed { index, hash ->
                dao.setPriority(hash, index)
            }
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
    }
