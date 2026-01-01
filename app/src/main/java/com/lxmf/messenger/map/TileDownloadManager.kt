package com.lxmf.messenger.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Manages downloading map tiles for offline use.
 *
 * Downloads vector tiles from OpenFreeMap and stores them in MBTiles format.
 */
class TileDownloadManager(
    private val context: Context,
    private val baseUrl: String = DEFAULT_TILE_URL,
) {
    /**
     * Download progress state.
     */
    data class DownloadProgress(
        val status: Status,
        val totalTiles: Int,
        val downloadedTiles: Int,
        val failedTiles: Int,
        val bytesDownloaded: Long,
        val currentZoom: Int,
        val errorMessage: String? = null,
    ) {
        val progress: Float
            get() = if (totalTiles > 0) downloadedTiles.toFloat() / totalTiles else 0f

        enum class Status {
            IDLE,
            CALCULATING,
            DOWNLOADING,
            WRITING,
            COMPLETE,
            ERROR,
            CANCELLED,
        }
    }

    /**
     * Tile coordinate.
     */
    data class TileCoord(
        val z: Int,
        val x: Int,
        val y: Int,
    )

    private val _progress = MutableStateFlow(
        DownloadProgress(
            status = DownloadProgress.Status.IDLE,
            totalTiles = 0,
            downloadedTiles = 0,
            failedTiles = 0,
            bytesDownloaded = 0,
            currentZoom = 0,
        ),
    )
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    @Volatile
    private var isCancelled = false

    /**
     * Download tiles for a circular region and save to MBTiles.
     *
     * @param centerLat Center latitude
     * @param centerLon Center longitude
     * @param radiusKm Radius in kilometers
     * @param minZoom Minimum zoom level
     * @param maxZoom Maximum zoom level
     * @param name Name for the MBTiles file
     * @param outputFile Output MBTiles file
     * @return The output file on success, null on failure or cancellation
     */
    suspend fun downloadRegion(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Int,
        minZoom: Int,
        maxZoom: Int,
        name: String,
        outputFile: File,
    ): File? = withContext(Dispatchers.IO) {
        isCancelled = false

        try {
            // Calculate bounds and tiles
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.CALCULATING,
            )

            val bounds = MBTilesWriter.boundsFromCenter(centerLat, centerLon, radiusKm)
            val tiles = calculateTilesForRegion(bounds, minZoom, maxZoom)

            if (tiles.isEmpty()) {
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.ERROR,
                    errorMessage = "No tiles found for region",
                )
                return@withContext null
            }

            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.DOWNLOADING,
                totalTiles = tiles.size,
                downloadedTiles = 0,
                failedTiles = 0,
                bytesDownloaded = 0,
            )

            // Create MBTiles writer
            val writer = MBTilesWriter(
                file = outputFile,
                name = name,
                description = "Offline map for $name",
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = bounds,
                center = MBTilesWriter.Center(centerLon, centerLat, (minZoom + maxZoom) / 2),
            )

            writer.open()
            writer.beginTransaction()

            try {
                // Download tiles with concurrency limit
                val semaphore = Semaphore(CONCURRENT_DOWNLOADS)
                var downloadedCount = 0
                var failedCount = 0
                var totalBytes = 0L

                coroutineScope {
                    // Group tiles by zoom level for progress reporting
                    val tilesByZoom = tiles.groupBy { it.z }

                    for (zoom in minZoom..maxZoom) {
                        if (isCancelled) break

                        val zoomTiles = tilesByZoom[zoom] ?: continue

                        _progress.value = _progress.value.copy(currentZoom = zoom)

                        val results = zoomTiles.map { tile ->
                            async {
                                if (isCancelled) return@async null

                                semaphore.withPermit {
                                    downloadTileWithRetry(tile)
                                }
                            }
                        }.awaitAll()

                        // Write successful tiles
                        for ((index, data) in results.withIndex()) {
                            if (data != null) {
                                val tile = zoomTiles[index]
                                writer.writeTile(tile.z, tile.x, tile.y, data)
                                downloadedCount++
                                totalBytes += data.size
                            } else {
                                failedCount++
                            }

                            _progress.value = _progress.value.copy(
                                downloadedTiles = downloadedCount,
                                failedTiles = failedCount,
                                bytesDownloaded = totalBytes,
                            )
                        }
                    }
                }

                if (isCancelled) {
                    writer.endTransaction(success = false)
                    writer.close()
                    outputFile.delete()

                    _progress.value = _progress.value.copy(
                        status = DownloadProgress.Status.CANCELLED,
                    )
                    return@withContext null
                }

                // Finalize
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.WRITING,
                )

                writer.endTransaction(success = true)
                writer.optimize()
                writer.close()

                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.COMPLETE,
                )

                outputFile
            } catch (e: Exception) {
                writer.endTransaction(success = false)
                writer.close()
                outputFile.delete()
                throw e
            }
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.ERROR,
                errorMessage = e.message ?: "Download failed",
            )
            null
        }
    }

    /**
     * Cancel an ongoing download.
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Reset the progress state.
     */
    fun reset() {
        isCancelled = false
        _progress.value = DownloadProgress(
            status = DownloadProgress.Status.IDLE,
            totalTiles = 0,
            downloadedTiles = 0,
            failedTiles = 0,
            bytesDownloaded = 0,
            currentZoom = 0,
        )
    }

    /**
     * Calculate the estimated number of tiles and size for a region.
     *
     * @return Pair of (tile count, estimated size in bytes)
     */
    fun estimateDownload(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Int,
        minZoom: Int,
        maxZoom: Int,
    ): Pair<Int, Long> {
        val bounds = MBTilesWriter.boundsFromCenter(centerLat, centerLon, radiusKm)
        val tiles = calculateTilesForRegion(bounds, minZoom, maxZoom)
        // Estimate ~15KB average per vector tile
        val estimatedSize = tiles.size * AVERAGE_TILE_SIZE_BYTES
        return Pair(tiles.size, estimatedSize)
    }

    private fun calculateTilesForRegion(
        bounds: MBTilesWriter.Bounds,
        minZoom: Int,
        maxZoom: Int,
    ): List<TileCoord> {
        val tiles = mutableListOf<TileCoord>()

        for (z in minZoom..maxZoom) {
            val minTile = latLonToTile(bounds.north, bounds.west, z)
            val maxTile = latLonToTile(bounds.south, bounds.east, z)

            for (x in minTile.x..maxTile.x) {
                for (y in minTile.y..maxTile.y) {
                    tiles.add(TileCoord(z, x, y))
                }
            }
        }

        return tiles
    }

    private suspend fun downloadTileWithRetry(tile: TileCoord): ByteArray? {
        repeat(MAX_RETRIES) { attempt ->
            try {
                return downloadTile(tile)
            } catch (e: IOException) {
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        return null
    }

    private fun downloadTile(tile: TileCoord): ByteArray {
        val urlString = "$baseUrl/${tile.z}/${tile.x}/${tile.y}.pbf"
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode for $urlString")
            }

            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_TILE_URL = "https://tiles.openfreemap.org/planet"
        const val CONCURRENT_DOWNLOADS = 4
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L
        const val AVERAGE_TILE_SIZE_BYTES = 15_000L
        const val USER_AGENT = "Columba/1.0 (Android; Offline Maps)"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000

        /**
         * Convert latitude/longitude to tile coordinates.
         */
        fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
            val n = 2.0.pow(zoom)
            val x = floor((lon + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
            return TileCoord(zoom, x.coerceIn(0, n.toInt() - 1), y.coerceIn(0, n.toInt() - 1))
        }

        /**
         * Convert tile coordinates to latitude/longitude (top-left corner).
         */
        fun tileToLatLon(z: Int, x: Int, y: Int): Pair<Double, Double> {
            val n = 2.0.pow(z)
            val lon = x / n * 360.0 - 180.0
            val latRad = atan(sinh(PI * (1 - 2 * y / n)))
            val lat = Math.toDegrees(latRad)
            return Pair(lat, lon)
        }

        /**
         * Get the output directory for offline maps.
         */
        fun getOfflineMapsDir(context: Context): File {
            return File(context.filesDir, "offline_maps").also { it.mkdirs() }
        }

        /**
         * Generate a unique filename for an offline map.
         */
        fun generateFilename(name: String): String {
            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
            val timestamp = System.currentTimeMillis()
            return "${sanitized}_$timestamp.mbtiles"
        }
    }
}
