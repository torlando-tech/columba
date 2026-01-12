package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.OfflineMapRegionDao
import com.lxmf.messenger.data.db.entity.OfflineMapRegionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data model for offline map regions used in the app layer.
 */
data class OfflineMapRegion(
    val id: Long,
    val name: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusKm: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val status: Status,
    val mbtilesPath: String?,
    val tileCount: Int,
    val sizeBytes: Long,
    val downloadProgress: Float,
    val errorMessage: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val source: Source,
    val tileVersion: String?,
) {
    enum class Status {
        PENDING,
        DOWNLOADING,
        COMPLETE,
        ERROR,
    }

    enum class Source {
        HTTP,
        RMSP,
    }

    /**
     * Get a human-readable size string.
     */
    fun getSizeString(): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * Repository for managing offline map regions.
 */
@Suppress("TooManyFunctions")
@Singleton
class OfflineMapRegionRepository
    @Inject
    constructor(
        private val offlineMapRegionDao: OfflineMapRegionDao,
    ) {
        /**
         * Get all offline map regions as a Flow.
         */
        fun getAllRegions(): Flow<List<OfflineMapRegion>> {
            return offlineMapRegionDao.getAllRegions().map { entities ->
                entities.map { it.toOfflineMapRegion() }
            }
        }

        /**
         * Get all completed regions.
         */
        fun getCompletedRegions(): Flow<List<OfflineMapRegion>> {
            return offlineMapRegionDao.getCompletedRegions().map { entities ->
                entities.map { it.toOfflineMapRegion() }
            }
        }

        /**
         * Get a specific region by ID.
         */
        suspend fun getRegionById(id: Long): OfflineMapRegion? {
            return offlineMapRegionDao.getRegionById(id)?.toOfflineMapRegion()
        }

        /**
         * Create a new pending region.
         * @return The ID of the created region
         */
        suspend fun createRegion(
            name: String,
            centerLatitude: Double,
            centerLongitude: Double,
            radiusKm: Int,
            minZoom: Int,
            maxZoom: Int,
            source: OfflineMapRegion.Source = OfflineMapRegion.Source.HTTP,
        ): Long {
            val entity =
                OfflineMapRegionEntity(
                    name = name,
                    centerLatitude = centerLatitude,
                    centerLongitude = centerLongitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    status = OfflineMapRegionEntity.STATUS_PENDING,
                    mbtilesPath = null,
                    tileCount = 0,
                    sizeBytes = 0,
                    downloadProgress = 0f,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                    completedAt = null,
                    source =
                        if (source == OfflineMapRegion.Source.RMSP) {
                            OfflineMapRegionEntity.SOURCE_RMSP
                        } else {
                            OfflineMapRegionEntity.SOURCE_HTTP
                        },
                )
            return offlineMapRegionDao.insert(entity)
        }

        /**
         * Update download progress for a region.
         */
        suspend fun updateProgress(
            id: Long,
            status: OfflineMapRegion.Status,
            progress: Float,
            tileCount: Int,
        ) {
            offlineMapRegionDao.updateProgress(
                id = id,
                status = status.toEntityStatus(),
                progress = progress,
                tileCount = tileCount,
            )
        }

        /**
         * Mark a region as complete.
         */
        suspend fun markComplete(
            id: Long,
            tileCount: Int,
            sizeBytes: Long,
            mbtilesPath: String,
            tileVersion: String? = null,
        ) {
            offlineMapRegionDao.markComplete(
                id = id,
                tileCount = tileCount,
                sizeBytes = sizeBytes,
                mbtilesPath = mbtilesPath,
                tileVersion = tileVersion,
            )
        }

        /**
         * Update the tile version for a region.
         */
        suspend fun updateTileVersion(id: Long, version: String) {
            offlineMapRegionDao.updateTileVersion(id, version)
        }

        /**
         * Mark a region as failed.
         */
        suspend fun markError(
            id: Long,
            errorMessage: String,
        ) {
            offlineMapRegionDao.markError(id, errorMessage)
        }

        /**
         * Delete a region by ID.
         */
        suspend fun deleteRegion(id: Long) {
            offlineMapRegionDao.deleteById(id)
        }

        /**
         * Get total storage used by completed offline maps.
         */
        fun getTotalStorageUsed(): Flow<Long?> {
            return offlineMapRegionDao.getTotalStorageUsed()
        }

        /**
         * Get count of regions.
         */
        suspend fun getCount(): Int {
            return offlineMapRegionDao.getCount()
        }

        /**
         * Find the nearest completed region to a location.
         */
        suspend fun findNearestRegion(
            latitude: Double,
            longitude: Double,
        ): OfflineMapRegion? {
            return offlineMapRegionDao.findNearestRegion(latitude, longitude)?.toOfflineMapRegion()
        }

        /**
         * Find orphaned MBTiles files not tracked in the database.
         * @param offlineMapsDir The directory containing MBTiles files
         * @return List of orphaned file paths
         */
        suspend fun findOrphanedFiles(offlineMapsDir: java.io.File): List<java.io.File> {
            val trackedPaths = offlineMapRegionDao.getAllMbtilesPaths().toSet()
            return offlineMapsDir.listFiles { file ->
                file.extension == "mbtiles" && file.absolutePath !in trackedPaths
            }?.toList() ?: emptyList()
        }

        /**
         * Import an orphaned MBTiles file into the database.
         * Attempts to extract center/bounds from MBTiles metadata.
         * @return The ID of the imported region
         */
        suspend fun importOrphanedFile(
            file: java.io.File,
            name: String = file.nameWithoutExtension,
        ): Long {
            // Try to extract metadata from MBTiles
            val metadata = extractMbtilesMetadata(file)

            val entity = OfflineMapRegionEntity(
                name = metadata?.name ?: name,
                centerLatitude = metadata?.centerLat ?: 0.0,
                centerLongitude = metadata?.centerLon ?: 0.0,
                radiusKm = metadata?.radiusKm ?: 100, // Default to 100km to ensure it's usable
                minZoom = metadata?.minZoom ?: 0,
                maxZoom = metadata?.maxZoom ?: 14,
                status = OfflineMapRegionEntity.STATUS_COMPLETE,
                mbtilesPath = file.absolutePath,
                tileCount = 0,
                sizeBytes = file.length(),
                downloadProgress = 1f,
                errorMessage = null,
                createdAt = file.lastModified(),
                completedAt = file.lastModified(),
                source = OfflineMapRegionEntity.SOURCE_HTTP,
            )
            return offlineMapRegionDao.insert(entity)
        }

        private data class MbtilesMetadata(
            val name: String?,
            val centerLat: Double,
            val centerLon: Double,
            val radiusKm: Int,
            val minZoom: Int,
            val maxZoom: Int,
        )

        private fun extractMbtilesMetadata(file: java.io.File): MbtilesMetadata? {
            var db: android.database.sqlite.SQLiteDatabase? = null
            return try {
                db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
                val metadata = mutableMapOf<String, String>()
                db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        metadata[cursor.getString(0)] = cursor.getString(1)
                    }
                }

                // Parse center (format: "lon,lat,zoom")
                val center = metadata["center"]?.split(",")
                val centerLon = center?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val centerLat = center?.getOrNull(1)?.toDoubleOrNull() ?: 0.0

                // Parse bounds (format: "west,south,east,north")
                val bounds = metadata["bounds"]?.split(",")
                val radiusKm = if (bounds != null && bounds.size == 4) {
                    val west = bounds[0].toDoubleOrNull() ?: 0.0
                    val east = bounds[2].toDoubleOrNull() ?: 0.0
                    // Approximate radius from bounds width with latitude correction
                    // (111km per degree at equator, less at higher latitudes)
                    val lonDegToKm = 111.0 * kotlin.math.cos(Math.toRadians(centerLat))
                    ((east - west) * lonDegToKm / 2).toInt().coerceIn(10, 200)
                } else {
                    100 // Default
                }

                MbtilesMetadata(
                    name = metadata["name"],
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusKm = radiusKm,
                    minZoom = metadata["minzoom"]?.toIntOrNull() ?: 0,
                    maxZoom = metadata["maxzoom"]?.toIntOrNull() ?: 14,
                )
            } catch (@Suppress("SwallowedException") e: Exception) {
                null // Return null if metadata extraction fails - file may be corrupted
            } finally {
                db?.close()
            }
        }
    }

/**
 * Extension function to convert entity to domain model.
 */
private fun OfflineMapRegionEntity.toOfflineMapRegion(): OfflineMapRegion {
    return OfflineMapRegion(
        id = id,
        name = name,
        centerLatitude = centerLatitude,
        centerLongitude = centerLongitude,
        radiusKm = radiusKm,
        minZoom = minZoom,
        maxZoom = maxZoom,
        status =
            when (status) {
                OfflineMapRegionEntity.STATUS_PENDING -> OfflineMapRegion.Status.PENDING
                OfflineMapRegionEntity.STATUS_DOWNLOADING -> OfflineMapRegion.Status.DOWNLOADING
                OfflineMapRegionEntity.STATUS_COMPLETE -> OfflineMapRegion.Status.COMPLETE
                else -> OfflineMapRegion.Status.ERROR
            },
        mbtilesPath = mbtilesPath,
        tileCount = tileCount,
        sizeBytes = sizeBytes,
        downloadProgress = downloadProgress,
        errorMessage = errorMessage,
        createdAt = createdAt,
        completedAt = completedAt,
        source =
            if (source == OfflineMapRegionEntity.SOURCE_RMSP) {
                OfflineMapRegion.Source.RMSP
            } else {
                OfflineMapRegion.Source.HTTP
            },
        tileVersion = tileVersion,
    )
}

/**
 * Extension function to convert domain status to entity status.
 */
private fun OfflineMapRegion.Status.toEntityStatus(): String {
    return when (this) {
        OfflineMapRegion.Status.PENDING -> OfflineMapRegionEntity.STATUS_PENDING
        OfflineMapRegion.Status.DOWNLOADING -> OfflineMapRegionEntity.STATUS_DOWNLOADING
        OfflineMapRegion.Status.COMPLETE -> OfflineMapRegionEntity.STATUS_COMPLETE
        OfflineMapRegion.Status.ERROR -> OfflineMapRegionEntity.STATUS_ERROR
    }
}
