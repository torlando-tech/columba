package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.OfflineMapRegionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineMapRegionDao {
    /**
     * Get all offline map regions, ordered by creation date (newest first).
     */
    @Query("SELECT * FROM offline_map_regions ORDER BY createdAt DESC")
    fun getAllRegions(): Flow<List<OfflineMapRegionEntity>>

    /**
     * Get a specific region by ID.
     */
    @Query("SELECT * FROM offline_map_regions WHERE id = :id")
    suspend fun getRegionById(id: Long): OfflineMapRegionEntity?

    /**
     * Get regions by status (e.g., all DOWNLOADING regions).
     */
    @Query("SELECT * FROM offline_map_regions WHERE status = :status ORDER BY createdAt DESC")
    fun getRegionsByStatus(status: String): Flow<List<OfflineMapRegionEntity>>

    /**
     * Get all completed regions (for tile source lookup).
     */
    @Query("SELECT * FROM offline_map_regions WHERE status = 'COMPLETE' ORDER BY createdAt DESC")
    fun getCompletedRegions(): Flow<List<OfflineMapRegionEntity>>

    /**
     * Insert a new region.
     * @return The auto-generated ID of the inserted region.
     */
    @Insert
    suspend fun insert(region: OfflineMapRegionEntity): Long

    /**
     * Update an existing region.
     */
    @Update
    suspend fun update(region: OfflineMapRegionEntity)

    /**
     * Update download progress for a region.
     */
    @Query(
        """
        UPDATE offline_map_regions
        SET status = :status, downloadProgress = :progress, tileCount = :tileCount
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(id: Long, status: String, progress: Float, tileCount: Int)

    /**
     * Mark a region as complete with final stats.
     */
    @Query(
        """
        UPDATE offline_map_regions
        SET status = 'COMPLETE',
            downloadProgress = 1.0,
            tileCount = :tileCount,
            sizeBytes = :sizeBytes,
            mbtilesPath = :mbtilesPath,
            completedAt = :completedAt
        WHERE id = :id
        """,
    )
    suspend fun markComplete(
        id: Long,
        tileCount: Int,
        sizeBytes: Long,
        mbtilesPath: String,
        completedAt: Long = System.currentTimeMillis(),
    )

    /**
     * Mark a region as failed with an error message.
     */
    @Query(
        """
        UPDATE offline_map_regions
        SET status = 'ERROR', errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun markError(id: Long, errorMessage: String)

    /**
     * Delete a region.
     */
    @Delete
    suspend fun delete(region: OfflineMapRegionEntity)

    /**
     * Delete a region by ID.
     */
    @Query("DELETE FROM offline_map_regions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Get total storage used by completed offline maps.
     */
    @Query("SELECT SUM(sizeBytes) FROM offline_map_regions WHERE status = 'COMPLETE'")
    fun getTotalStorageUsed(): Flow<Long?>

    /**
     * Get count of regions.
     */
    @Query("SELECT COUNT(*) FROM offline_map_regions")
    suspend fun getCount(): Int

    /**
     * Check if a region exists that covers the given location.
     * This is a simplified check - actual coverage depends on radius.
     */
    @Query(
        """
        SELECT * FROM offline_map_regions
        WHERE status = 'COMPLETE'
        AND ABS(centerLatitude - :latitude) < 1.0
        AND ABS(centerLongitude - :longitude) < 1.0
        ORDER BY
            (centerLatitude - :latitude) * (centerLatitude - :latitude) +
            (centerLongitude - :longitude) * (centerLongitude - :longitude) ASC
        LIMIT 1
        """,
    )
    suspend fun findNearestRegion(latitude: Double, longitude: Double): OfflineMapRegionEntity?
}
