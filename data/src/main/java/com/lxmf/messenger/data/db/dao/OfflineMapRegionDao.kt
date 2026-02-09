package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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
    suspend fun updateProgress(
        id: Long,
        status: String,
        progress: Float,
        tileCount: Int,
    )

    /**
     * Mark a region as complete with final stats (legacy MBTiles).
     */
    @Query(
        """
        UPDATE offline_map_regions
        SET status = 'COMPLETE',
            downloadProgress = 1.0,
            tileCount = :tileCount,
            sizeBytes = :sizeBytes,
            mbtilesPath = :mbtilesPath,
            completedAt = :completedAt,
            tileVersion = :tileVersion
        WHERE id = :id
        """,
    )
    suspend fun markComplete(
        id: Long,
        tileCount: Int,
        sizeBytes: Long,
        mbtilesPath: String,
        completedAt: Long = System.currentTimeMillis(),
        tileVersion: String? = null,
    )

    /**
     * Mark a region as complete with MapLibre region ID (new OfflineManager API).
     */
    @Query(
        """
        UPDATE offline_map_regions
        SET status = 'COMPLETE',
            downloadProgress = 1.0,
            tileCount = :tileCount,
            sizeBytes = :sizeBytes,
            maplibreRegionId = :maplibreRegionId,
            completedAt = :completedAt
        WHERE id = :id
        """,
    )
    suspend fun markCompleteWithMaplibreId(
        id: Long,
        tileCount: Int,
        sizeBytes: Long,
        maplibreRegionId: Long,
        completedAt: Long = System.currentTimeMillis(),
    )

    /**
     * Update the tile version for a region.
     */
    @Query("UPDATE offline_map_regions SET tileVersion = :version WHERE id = :id")
    suspend fun updateTileVersion(
        id: Long,
        version: String,
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
    suspend fun markError(
        id: Long,
        errorMessage: String,
    )

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
    suspend fun findNearestRegion(
        latitude: Double,
        longitude: Double,
    ): OfflineMapRegionEntity?

    /**
     * Get all tracked MBTiles file paths for orphan detection.
     */
    @Query("SELECT mbtilesPath FROM offline_map_regions WHERE mbtilesPath IS NOT NULL")
    suspend fun getAllMbtilesPaths(): List<String>

    /**
     * Get a region by its MapLibre region ID.
     */
    @Query("SELECT * FROM offline_map_regions WHERE maplibreRegionId = :maplibreRegionId")
    suspend fun getRegionByMaplibreId(maplibreRegionId: Long): OfflineMapRegionEntity?

    /**
     * Get all tracked MapLibre region IDs for orphan detection.
     */
    @Query("SELECT maplibreRegionId FROM offline_map_regions WHERE maplibreRegionId IS NOT NULL")
    suspend fun getAllMaplibreRegionIds(): List<Long>

    /**
     * Update the MapLibre region ID for a region.
     */
    @Query("UPDATE offline_map_regions SET maplibreRegionId = :maplibreRegionId WHERE id = :id")
    suspend fun updateMaplibreRegionId(
        id: Long,
        maplibreRegionId: Long,
    )

    /**
     * Update the local style JSON file path for a region.
     */
    @Query("UPDATE offline_map_regions SET localStylePath = :localStylePath WHERE id = :id")
    suspend fun updateLocalStylePath(
        id: Long,
        localStylePath: String,
    )

    /**
     * Get the first completed region with a locally cached style JSON file.
     * Used by Plan 02 to load the cached style for offline rendering.
     */
    @Query("SELECT * FROM offline_map_regions WHERE status = 'COMPLETE' AND localStylePath IS NOT NULL LIMIT 1")
    suspend fun getFirstCompletedRegionWithLocalStyle(): OfflineMapRegionEntity?

    /**
     * Get the region marked as default map center.
     * Returns null if no region is marked as default.
     */
    @Query("SELECT * FROM offline_map_regions WHERE isDefault = 1 AND status = 'COMPLETE' LIMIT 1")
    suspend fun getDefaultRegion(): OfflineMapRegionEntity?

    /**
     * Clear the default flag from all regions.
     */
    @Query("UPDATE offline_map_regions SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefaultRegion()

    /**
     * Set a specific region as the default map center.
     */
    @Query("UPDATE offline_map_regions SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultRegionById(id: Long)

    /**
     * Atomically clear any existing default and set the given region as default.
     * Wrapped in a transaction to prevent race conditions where multiple regions
     * could end up marked as default simultaneously.
     */
    @Transaction
    suspend fun setDefaultRegion(id: Long) {
        clearDefaultRegion()
        setDefaultRegionById(id)
    }
}
