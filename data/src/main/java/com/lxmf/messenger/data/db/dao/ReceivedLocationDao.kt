package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.ReceivedLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedLocationDao {
    /**
     * Insert a new received location.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: ReceivedLocationEntity)

    /**
     * Get the latest location for each sender (for map display).
     * Only returns non-expired locations.
     *
     * Cleanup of old/stale locations is handled separately by deleteExpiredLocations().
     */
    @Query(
        """
        SELECT * FROM received_locations r1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM received_locations r2
            WHERE r2.senderHash = r1.senderHash
        )
        AND (expiresAt IS NULL OR expiresAt > :currentTime)
        ORDER BY timestamp DESC
        """,
    )
    fun getLatestLocationsPerSender(currentTime: Long = System.currentTimeMillis()): Flow<List<ReceivedLocationEntity>>

    /**
     * Get the latest location for each sender without expiry filtering.
     * Used for stale/last-known location display where filtering is done in ViewModel.
     */
    @Query(
        """
        SELECT * FROM received_locations r1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM received_locations r2
            WHERE r2.senderHash = r1.senderHash
        )
        ORDER BY timestamp DESC
        """,
    )
    fun getLatestLocationsPerSenderUnfiltered(): Flow<List<ReceivedLocationEntity>>

    /**
     * Get all locations for a specific sender (for trail visualization).
     */
    @Query(
        """
        SELECT * FROM received_locations
        WHERE senderHash = :senderHash
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun getLocationsForSender(senderHash: String, limit: Int = 100): Flow<List<ReceivedLocationEntity>>

    /**
     * Get the most recent location for a specific sender.
     */
    @Query(
        """
        SELECT * FROM received_locations
        WHERE senderHash = :senderHash
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestLocationForSender(senderHash: String): ReceivedLocationEntity?

    /**
     * Delete expired locations past the grace period (cleanup job).
     * Keeps expired locations for 1 hour to display as "last known" location.
     *
     * @param gracePeriodCutoff Locations expired before this time will be deleted
     */
    @Query("DELETE FROM received_locations WHERE expiresAt IS NOT NULL AND expiresAt < :gracePeriodCutoff")
    suspend fun deleteExpiredLocations(gracePeriodCutoff: Long = System.currentTimeMillis() - 3600_000L)

    /**
     * Delete all locations for a sender (when contact is removed).
     */
    @Query("DELETE FROM received_locations WHERE senderHash = :senderHash")
    suspend fun deleteLocationsForSender(senderHash: String)

    /**
     * Delete all locations (for data reset).
     */
    @Query("DELETE FROM received_locations")
    suspend fun deleteAll()

    /**
     * Get count of received locations (for stats/debugging).
     */
    @Query("SELECT COUNT(*) FROM received_locations")
    suspend fun getCount(): Int
}
