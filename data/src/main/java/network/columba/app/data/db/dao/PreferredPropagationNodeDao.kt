package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * DAO for the prioritized preferred-propagation-node list.
 *
 * The list is ordered by [PreferredPropagationNodeEntity.priority] (lower = higher
 * priority). All reads that feed the selection policy filter on `enabled = 1`.
 */
@Dao
interface PreferredPropagationNodeDao {
    /** All preferred nodes in priority order (including disabled), as a live Flow. */
    @Query("SELECT * FROM preferred_propagation_nodes ORDER BY priority ASC, id ASC")
    fun observeOrdered(): Flow<List<PreferredPropagationNodeEntity>>

    /** Only the enabled nodes in priority order — the selection candidate pool. */
    @Query("SELECT * FROM preferred_propagation_nodes WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    fun observeEnabled(): Flow<List<PreferredPropagationNodeEntity>>

    /** Snapshot of the ordered enabled list (for one-shot reads). */
    @Query("SELECT * FROM preferred_propagation_nodes WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun getEnabled(): List<PreferredPropagationNodeEntity>

    /** Insert or replace by primary key (upsert). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: PreferredPropagationNodeEntity): Long

    /** Look up a single node by its destination hash. */
    @Query("SELECT * FROM preferred_propagation_nodes WHERE destinationHash = :destinationHash LIMIT 1")
    suspend fun findByHash(destinationHash: String): PreferredPropagationNodeEntity?

    /** Remove a node from the list by destination hash. */
    @Query("DELETE FROM preferred_propagation_nodes WHERE destinationHash = :destinationHash")
    suspend fun deleteByHash(destinationHash: String)

    /** Update a node's priority position (used during reorder). */
    @Query("UPDATE preferred_propagation_nodes SET priority = :priority WHERE destinationHash = :destinationHash")
    suspend fun setPriority(destinationHash: String, priority: Int)

    /** Toggle a node's enabled flag. */
    @Query("UPDATE preferred_propagation_nodes SET enabled = :enabled WHERE destinationHash = :destinationHash")
    suspend fun setEnabled(destinationHash: String, enabled: Boolean)

    /** Update a node's denormalized display name. */
    @Query("UPDATE preferred_propagation_nodes SET displayName = :displayName WHERE destinationHash = :destinationHash")
    suspend fun setDisplayName(destinationHash: String, displayName: String?)

    /** Whether the list currently contains the given destination hash. */
    @Query("SELECT EXISTS(SELECT 1 FROM preferred_propagation_nodes WHERE destinationHash = :destinationHash)")
    suspend fun contains(destinationHash: String): Boolean

    /** Number of rows in the list. */
    @Query("SELECT COUNT(*) FROM preferred_propagation_nodes")
    suspend fun count(): Int

    /**
     * Record the most recent sync failure for a node while it was the active relay.
     * Overwrites any prior failure record (a node is only ever excluded while its *most
     * recent* failure is inside the cooldown window, so the latest event wins).
     */
    @Query(
        "UPDATE preferred_propagation_nodes SET lastFailureCode = :lastFailureCode, " +
            "lastFailureExplanation = :lastFailureExplanation, lastFailureAt = :lastFailureAt " +
            "WHERE destinationHash = :destinationHash",
    )
    suspend fun recordLastFailure(
        destinationHash: String,
        lastFailureCode: Int,
        lastFailureExplanation: String,
        lastFailureAt: Long,
    )

    /**
     * Clear a node's recorded failure (all three columns → null). Called after a successful
     * sync to that relay so a recovered node is no longer excluded by the policy.
     */
    @Query(
        "UPDATE preferred_propagation_nodes SET lastFailureCode = NULL, " +
            "lastFailureExplanation = NULL, lastFailureAt = NULL " +
            "WHERE destinationHash = :destinationHash",
    )
    suspend fun clearLastFailure(destinationHash: String)

    /**
     * Atomically persist a new priority order. [destinationHashes] is the full new list
     * order (index = new priority). Wrapping all the priority writes in one transaction
     * means observers (the relay writer, the screen) only ever see the complete old order
     * or the complete new order — never a half-reordered state where two rows share a
     * priority.
     */
    @Transaction
    suspend fun reorderPriorities(destinationHashes: List<String>) {
        destinationHashes.forEachIndexed { index, hash ->
            setPriority(hash, index)
        }
    }

    /**
     * Atomically remove a node and re-pack the remaining `priority` values so the list
     * stays contiguous (no gap) with a single commit.
     */
    @Transaction
    suspend fun removeAndRepackPriorities(destinationHash: String) {
        deleteByHash(destinationHash)
        observeOrdered().first().forEachIndexed { index, node ->
            if (node.priority != index) {
                setPriority(node.destinationHash, index)
            }
        }
    }
}
