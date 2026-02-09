package com.lxmf.messenger.map

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper for MapLibre's native OfflineManager API.
 *
 * This class provides a simplified interface for downloading, managing, and deleting
 * offline map regions using MapLibre's internal tile caching system.
 *
 * Key benefits over MBTiles approach:
 * - Tiles are stored in MapLibre's internal database (mbgl-offline.db)
 * - Automatic tile usage when offline - no explicit style switching needed
 * - Built-in progress tracking via OfflineRegionObserver
 * - Native support without custom protocol handlers
 */
@Singleton
class MapLibreOfflineManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "MapLibreOfflineManager"
            const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

            // MapLibre default limit is 6000 tiles per region
            // Can be increased with setOfflineMapboxTileCountLimit()
            const val DEFAULT_TILE_LIMIT = 6000L
            const val EXTENDED_TILE_LIMIT = 50000L

            // Pixel ratio for tile downloads (1.0 for standard, 2.0 for retina)
            const val DEFAULT_PIXEL_RATIO = 1.0f
        }

        private val offlineManager: OfflineManager by lazy {
            OfflineManager.getInstance(context).also {
                // Increase tile limit for larger regions
                it.setOfflineMapboxTileCountLimit(EXTENDED_TILE_LIMIT)
            }
        }

        /**
         * Download a region for offline use.
         *
         * @param name Display name for the region (stored as metadata)
         * @param bounds Geographic bounds of the region
         * @param minZoom Minimum zoom level to download
         * @param maxZoom Maximum zoom level to download
         * @param styleUrl URL of the map style (default: OpenFreeMap Liberty)
         * @param onCreated Callback when MapLibre region is created (before download starts)
         * @param onProgress Callback for download progress (0.0 to 1.0)
         * @param onComplete Callback when download completes with MapLibre region ID
         * @param onError Callback for download errors
         */
        @Suppress("LongParameterList") // Callback-based API requires these parameters
        fun downloadRegion(
            name: String,
            bounds: LatLngBounds,
            minZoom: Double,
            maxZoom: Double,
            styleUrl: String = DEFAULT_STYLE_URL,
            // MapLibre region ID - called immediately when region is created
            onCreated: (Long) -> Unit = {},
            // progress, completedResources, requiredResources
            onProgress: (Float, Long, Long) -> Unit,
            // regionId, sizeBytes
            onComplete: (Long, Long) -> Unit,
            onError: (String) -> Unit,
        ) {
            Log.d(TAG, "Starting download for region '$name': bounds=$bounds, zoom=$minZoom-$maxZoom")

            // Create region definition
            val definition =
                OfflineTilePyramidRegionDefinition(
                    styleUrl,
                    bounds,
                    minZoom,
                    maxZoom,
                    DEFAULT_PIXEL_RATIO,
                )

            // Encode region name as metadata
            val metadata = name.toByteArray(Charsets.UTF_8)

            // Create the offline region
            offlineManager.createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        Log.d(TAG, "Region created with ID: ${offlineRegion.id}")

                        // Notify caller of region ID immediately (for cancellation support)
                        onCreated(offlineRegion.id)

                        // Set up download observer
                        offlineRegion.setObserver(
                            object : OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status: OfflineRegionStatus) {
                                    val progress =
                                        if (status.requiredResourceCount > 0) {
                                            status.completedResourceCount.toFloat() / status.requiredResourceCount
                                        } else {
                                            0f
                                        }

                                    Log.d(
                                        TAG,
                                        "Download progress: ${(progress * 100).toInt()}% " +
                                            "(${status.completedResourceCount}/${status.requiredResourceCount})",
                                    )

                                    onProgress(progress, status.completedResourceCount, status.requiredResourceCount)

                                    if (status.isComplete) {
                                        Log.d(TAG, "Download complete! Size: ${status.completedResourceSize} bytes")
                                        offlineRegion.setObserver(null)
                                        onComplete(offlineRegion.id, status.completedResourceSize)
                                    }
                                }

                                override fun onError(error: OfflineRegionError) {
                                    Log.e(TAG, "Download error: ${error.reason} - ${error.message}")
                                    offlineRegion.setObserver(null)
                                    onError("${error.reason}: ${error.message}")
                                }

                                override fun mapboxTileCountLimitExceeded(limit: Long) {
                                    Log.e(TAG, "Tile count limit exceeded: $limit")
                                    offlineRegion.setObserver(null)
                                    onError("Tile count limit exceeded ($limit tiles). Try a smaller region or fewer zoom levels.")
                                }
                            },
                        )

                        // Start the download
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to create region: $error")
                        onError(error)
                    }
                },
            )
        }

        /**
         * List all downloaded offline regions.
         *
         * @param callback Callback with list of regions and their metadata
         */
        fun listRegions(callback: (List<OfflineRegionInfo>) -> Unit) {
            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val regions =
                            offlineRegions?.map { region ->
                                val name = region.metadata?.toString(Charsets.UTF_8) ?: "Unknown"
                                OfflineRegionInfo(
                                    id = region.id,
                                    name = name,
                                    definition = region.definition,
                                )
                            } ?: emptyList()
                        callback(regions)
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to list regions: $error")
                        callback(emptyList())
                    }
                },
            )
        }

        /**
         * Get the status of a specific region.
         *
         * @param regionId MapLibre region ID
         * @param callback Callback with region status
         */
        fun getRegionStatus(
            regionId: Long,
            callback: (OfflineRegionStatus?) -> Unit,
        ) {
            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val region = offlineRegions?.find { it.id == regionId }
                        if (region != null) {
                            region.getStatus(
                                object : OfflineRegion.OfflineRegionStatusCallback {
                                    override fun onStatus(status: OfflineRegionStatus?) {
                                        callback(status)
                                    }

                                    override fun onError(error: String?) {
                                        Log.e(TAG, "Failed to get region status: $error")
                                        callback(null)
                                    }
                                },
                            )
                        } else {
                            callback(null)
                        }
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to list regions for status: $error")
                        callback(null)
                    }
                },
            )
        }

        /**
         * Delete an offline region.
         *
         * @param regionId MapLibre region ID
         * @param callback Callback with success status
         */
        fun deleteRegion(
            regionId: Long,
            callback: (Boolean) -> Unit,
        ) {
            Log.d(TAG, "Deleting region: $regionId")

            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val region = offlineRegions?.find { it.id == regionId }
                        if (region != null) {
                            region.delete(
                                object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() {
                                        Log.d(TAG, "Region deleted successfully: $regionId")
                                        callback(true)
                                    }

                                    override fun onError(error: String) {
                                        Log.e(TAG, "Failed to delete region: $error")
                                        callback(false)
                                    }
                                },
                            )
                        } else {
                            Log.w(TAG, "Region not found for deletion: $regionId")
                            callback(false)
                        }
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to list regions for deletion: $error")
                        callback(false)
                    }
                },
            )
        }

        /**
         * Pause download for a region.
         */
        fun pauseDownload(
            regionId: Long,
            callback: (Boolean) -> Unit,
        ) {
            findRegion(regionId) { region ->
                if (region != null) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    callback(true)
                } else {
                    callback(false)
                }
            }
        }

        /**
         * Resume download for a region.
         */
        fun resumeDownload(
            regionId: Long,
            callback: (Boolean) -> Unit,
        ) {
            findRegion(regionId) { region ->
                if (region != null) {
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    callback(true)
                } else {
                    callback(false)
                }
            }
        }

        /**
         * Invalidate a region to refresh tiles on next download.
         */
        fun invalidateRegion(
            regionId: Long,
            callback: (Boolean) -> Unit,
        ) {
            findRegion(regionId) { region ->
                if (region != null) {
                    region.invalidate(
                        object : OfflineRegion.OfflineRegionInvalidateCallback {
                            override fun onInvalidate() {
                                Log.d(TAG, "Region invalidated: $regionId")
                                callback(true)
                            }

                            override fun onError(error: String) {
                                Log.e(TAG, "Failed to invalidate region: $error")
                                callback(false)
                            }
                        },
                    )
                } else {
                    callback(false)
                }
            }
        }

        /**
         * Clear MapLibre's ambient tile cache.
         *
         * This removes tiles that were cached during regular map browsing (not part of
         * downloaded offline regions). Useful when the ambient cache becomes corrupted,
         * causing map rendering issues after extended offline periods (#354).
         *
         * Downloaded offline regions are NOT affected.
         *
         * @param callback Success/failure callback
         */
        fun clearAmbientCache(callback: (Boolean) -> Unit) {
            Log.d(TAG, "Clearing ambient tile cache")
            offlineManager.clearAmbientCache(
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "Ambient cache cleared successfully")
                        callback(true)
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "Failed to clear ambient cache: $message")
                        callback(false)
                    }
                },
            )
        }

        /**
         * Reset MapLibre's entire offline database.
         *
         * WARNING: This deletes ALL data including downloaded offline regions.
         * Use as a last resort when the database is corrupted and clearAmbientCache
         * doesn't resolve the issue.
         *
         * After calling this, all offline regions will need to be re-downloaded.
         *
         * @param callback Success/failure callback
         */
        fun resetDatabase(callback: (Boolean) -> Unit) {
            Log.w(TAG, "Resetting entire offline database - all offline regions will be deleted")
            offlineManager.resetDatabase(
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "Offline database reset successfully")
                        callback(true)
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "Failed to reset offline database: $message")
                        callback(false)
                    }
                },
            )
        }

        private fun findRegion(
            regionId: Long,
            callback: (OfflineRegion?) -> Unit,
        ) {
            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        callback(offlineRegions?.find { it.id == regionId })
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to find region: $error")
                        callback(null)
                    }
                },
            )
        }

        /**
         * Calculate the estimated number of tiles for a region.
         * Useful for showing users an estimate before downloading.
         *
         * Formula: Sum of 4^zoom for each zoom level, multiplied by the fraction of the world covered.
         */
        fun estimateTileCount(
            bounds: LatLngBounds,
            minZoom: Int,
            maxZoom: Int,
        ): Long {
            // World bounds
            val worldLatSpan = 180.0
            val worldLonSpan = 360.0

            // Region bounds
            val latSpan = bounds.latitudeSpan
            val lonSpan = bounds.longitudeSpan

            // Fraction of world covered
            val fractionLat = latSpan / worldLatSpan
            val fractionLon = lonSpan / worldLonSpan
            val fraction = fractionLat * fractionLon

            // Calculate tiles at each zoom level
            var totalTiles = 0L
            for (zoom in minZoom..maxZoom) {
                val tilesAtZoom = 1L shl (zoom * 2) // 4^zoom = 2^(2*zoom)
                totalTiles += (tilesAtZoom * fraction).toLong()
            }

            return totalTiles
        }
    }

/**
 * Information about an offline region.
 */
data class OfflineRegionInfo(
    val id: Long,
    val name: String,
    val definition: OfflineRegionDefinition,
)
