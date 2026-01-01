package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing offline map regions downloaded for offline use.
 *
 * Each region represents a geographic area (center point + radius) with
 * map tiles stored in an MBTiles file on disk.
 */
@Entity(
    tableName = "offline_map_regions",
    indices = [
        Index("createdAt"), // For ordering by date
        Index("status"), // For filtering by download status
    ],
)
data class OfflineMapRegionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** User-provided name for the region (e.g., "Home", "Downtown") */
    val name: String,

    /** Center point latitude */
    val centerLatitude: Double,

    /** Center point longitude */
    val centerLongitude: Double,

    /** Radius in kilometers (5, 10, 25, 50, 100) */
    val radiusKm: Int,

    /** Minimum zoom level stored */
    val minZoom: Int,

    /** Maximum zoom level stored */
    val maxZoom: Int,

    /** Download status: PENDING, DOWNLOADING, COMPLETE, ERROR */
    val status: String,

    /** Path to the .mbtiles file in app storage (null if not yet created) */
    val mbtilesPath: String?,

    /** Number of tiles downloaded */
    val tileCount: Int,

    /** Total file size in bytes */
    val sizeBytes: Long,

    /** Download progress from 0.0 to 1.0 */
    val downloadProgress: Float,

    /** Error message if status == ERROR */
    val errorMessage: String?,

    /** Timestamp when region was created */
    val createdAt: Long,

    /** Timestamp when download completed (null if not complete) */
    val completedAt: Long?,

    /** Source of the tiles: "http" or "rmsp" */
    val source: String = "http",
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_ERROR = "ERROR"

        const val SOURCE_HTTP = "http"
        const val SOURCE_RMSP = "rmsp"
    }
}
