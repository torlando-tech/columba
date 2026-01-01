package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.RmspServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RmspServerDao {
    /**
     * Insert or update an RMSP server.
     * Uses REPLACE strategy so re-announces update the existing entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: RmspServerEntity)

    /**
     * Get all known RMSP servers, ordered by hops (nearest first), then recency.
     */
    @Query("SELECT * FROM rmsp_servers ORDER BY hops ASC, lastSeenTimestamp DESC")
    fun getAllServers(): Flow<List<RmspServerEntity>>

    /**
     * Get a specific server by destination hash.
     */
    @Query("SELECT * FROM rmsp_servers WHERE destinationHash = :hash")
    suspend fun getServer(hash: String): RmspServerEntity?

    /**
     * Find servers that might cover a given geohash.
     * Note: This uses LIKE for prefix matching, but the entity's coversGeohash()
     * method should be used for accurate coverage checking.
     */
    @Query(
        """
        SELECT * FROM rmsp_servers
        WHERE coverageGeohashes = ''
           OR coverageGeohashes LIKE '%' || :geohashPrefix || '%'
           OR :geohashPrefix LIKE coverageGeohashes || '%'
        ORDER BY hops ASC, lastSeenTimestamp DESC
        """,
    )
    fun getServersByGeohashPrefix(geohashPrefix: String): Flow<List<RmspServerEntity>>

    /**
     * Get the nearest servers (by hop count).
     */
    @Query("SELECT * FROM rmsp_servers ORDER BY hops ASC LIMIT :limit")
    fun getNearestServers(limit: Int = 5): Flow<List<RmspServerEntity>>

    /**
     * Delete a server by destination hash.
     */
    @Query("DELETE FROM rmsp_servers WHERE destinationHash = :hash")
    suspend fun deleteServer(hash: String)

    /**
     * Delete servers that haven't been seen recently.
     * @param cutoffTime Servers with lastSeenTimestamp before this will be deleted.
     */
    @Query("DELETE FROM rmsp_servers WHERE lastSeenTimestamp < :cutoffTime")
    suspend fun deleteStaleServers(cutoffTime: Long)

    /**
     * Delete all servers (for data reset).
     */
    @Query("DELETE FROM rmsp_servers")
    suspend fun deleteAll()

    /**
     * Get count of known servers.
     */
    @Query("SELECT COUNT(*) FROM rmsp_servers")
    suspend fun getCount(): Int

    /**
     * Check if any servers are available.
     */
    @Query("SELECT COUNT(*) > 0 FROM rmsp_servers")
    fun hasServers(): Flow<Boolean>
}
