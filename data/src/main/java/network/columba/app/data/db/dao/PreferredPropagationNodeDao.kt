package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import kotlinx.coroutines.flow.Flow

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
}
