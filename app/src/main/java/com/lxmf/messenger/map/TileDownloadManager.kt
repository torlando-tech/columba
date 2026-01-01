package com.lxmf.messenger.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Tile source for downloads.
 */
sealed class TileSource {
    /**
     * Download tiles from HTTP (OpenFreeMap).
     */
    data class Http(val baseUrl: String = TileDownloadManager.DEFAULT_TILE_URL) : TileSource()

    /**
     * Download tiles from RMSP server.
     *
     * @param serverHash The RMSP server's destination hash (hex string)
     * @param fetchTiles Function to fetch tiles from RMSP server
     */
    data class Rmsp(
        val serverHash: String,
        val fetchTiles: suspend (geohash: String, zoomRange: List<Int>) -> ByteArray?,
    ) : TileSource()
}

/**
 * Manages downloading map tiles for offline use.
 *
 * Downloads vector tiles from OpenFreeMap or RMSP and stores them in MBTiles format.
 */
class TileDownloadManager(
    private val context: Context,
    private val tileSource: TileSource = TileSource.Http(),
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

        // Branch based on tile source
        when (tileSource) {
            is TileSource.Http -> downloadRegionHttp(
                centerLat, centerLon, radiusKm, minZoom, maxZoom, name, outputFile,
            )
            is TileSource.Rmsp -> downloadRegionRmsp(
                tileSource, centerLat, centerLon, radiusKm, minZoom, maxZoom, name, outputFile,
            )
        }
    }

    /**
     * Download tiles from HTTP source.
     */
    private suspend fun downloadRegionHttp(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Int,
        minZoom: Int,
        maxZoom: Int,
        name: String,
        outputFile: File,
    ): File? {
        try {
            // Calculate bounds and tiles
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.CALCULATING,
            )

            val bounds = MBTilesWriter.boundsFromCenter(centerLat, centerLon, radiusKm)
            val tiles = calculateTilesForRegion(bounds, minZoom, maxZoom)

            Log.d(TAG, "HTTP Download region: center=($centerLat, $centerLon), radius=$radiusKm km")
            Log.d(TAG, "Calculated ${tiles.size} tiles across zoom levels $minZoom-$maxZoom")

            if (tiles.isEmpty()) {
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.ERROR,
                    errorMessage = "No tiles found for region",
                )
                return null
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
            // Note: We use a mutex to serialize database writes since SQLite connections
            // are not thread-safe across different coroutine dispatcher threads
            val writeMutex = Mutex()

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

                        Log.d(TAG, "Starting zoom level $zoom with ${zoomTiles.size} tiles")
                        _progress.value = _progress.value.copy(currentZoom = zoom)

                        val results = zoomTiles.map { tile ->
                            async {
                                if (isCancelled) return@async null

                                semaphore.withPermit {
                                    downloadTileWithRetry(tile)
                                }
                            }
                        }.awaitAll()

                        // Write successful tiles (serialized with mutex)
                        for ((index, data) in results.withIndex()) {
                            if (data != null) {
                                val tile = zoomTiles[index]
                                writeMutex.withLock {
                                    writer.writeTile(tile.z, tile.x, tile.y, data)
                                }
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
                    writer.close()
                    outputFile.delete()

                    _progress.value = _progress.value.copy(
                        status = DownloadProgress.Status.CANCELLED,
                    )
                    return null
                }

                // Finalize
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.WRITING,
                )

                writer.optimize()
                writer.close()

                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.COMPLETE,
                )

                return outputFile
            } catch (e: Exception) {
                writer.close()
                outputFile.delete()
                throw e
            }
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.ERROR,
                errorMessage = e.message ?: "Download failed",
            )
            return null
        }
    }

    /**
     * Download tiles from RMSP server.
     */
    private suspend fun downloadRegionRmsp(
        source: TileSource.Rmsp,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Int,
        minZoom: Int,
        maxZoom: Int,
        name: String,
        outputFile: File,
    ): File? {
        try {
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.CALCULATING,
            )

            // Calculate bounds and get all geohashes covering the region
            val bounds = MBTilesWriter.boundsFromCenter(centerLat, centerLon, radiusKm)

            // Choose geohash precision based on radius
            // Smaller precision = larger cells = fewer fetches but coarser coverage
            val geohashPrecision = when {
                radiusKm <= 5 -> 5   // ~5km cell
                radiusKm <= 20 -> 4  // ~20km cell
                radiusKm <= 80 -> 3  // ~80km cell
                else -> 2            // ~600km cell
            }

            val geohashes = geohashesForBounds(bounds, geohashPrecision)
            Log.d(TAG, "RMSP Download: ${geohashes.size} geohash cells at precision $geohashPrecision")
            Log.d(TAG, "Geohashes: $geohashes")
            Log.d(TAG, "Server: ${source.serverHash}")
            Log.d(TAG, "Requesting zoom range $minZoom-$maxZoom")

            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.DOWNLOADING,
                totalTiles = geohashes.size, // Update as we get actual tile counts
                downloadedTiles = 0,
            )

            // Fetch tiles for each geohash cell
            val allTiles = mutableListOf<RmspTile>()
            val seenTileCoords = mutableSetOf<Triple<Int, Int, Int>>()
            var geohashesProcessed = 0

            for (geohash in geohashes) {
                if (isCancelled) break

                Log.d(TAG, "Fetching geohash $geohash (${geohashesProcessed + 1}/${geohashes.size})")

                val tileData = source.fetchTiles(geohash, listOf(minZoom, maxZoom))
                if (tileData != null && tileData.isNotEmpty()) {
                    val tiles = unpackRmspTiles(tileData)
                    Log.d(TAG, "Received ${tiles.size} tiles for $geohash (${tileData.size} bytes)")

                    // Add tiles, avoiding duplicates (tiles at cell boundaries may overlap)
                    for (tile in tiles) {
                        val coord = Triple(tile.z, tile.x, tile.y)
                        if (coord !in seenTileCoords) {
                            seenTileCoords.add(coord)
                            allTiles.add(tile)
                        }
                    }
                } else {
                    Log.w(TAG, "No data received for geohash $geohash")
                }

                geohashesProcessed++
                _progress.value = _progress.value.copy(
                    downloadedTiles = geohashesProcessed,
                    totalTiles = geohashes.size,
                )
            }

            if (isCancelled) {
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.CANCELLED,
                )
                return null
            }

            Log.d(TAG, "Total unique tiles collected: ${allTiles.size}")

            if (allTiles.isEmpty()) {
                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.ERROR,
                    errorMessage = "No tiles received from RMSP server",
                )
                return null
            }

            _progress.value = _progress.value.copy(
                totalTiles = allTiles.size,
                downloadedTiles = 0,
                status = DownloadProgress.Status.WRITING,
            )

            // Create MBTiles and write tiles
            val writer = MBTilesWriter(
                file = outputFile,
                name = name,
                description = "Offline map from RMSP: $name",
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = bounds,
                center = MBTilesWriter.Center(centerLon, centerLat, (minZoom + maxZoom) / 2),
            )

            writer.open()
            try {
                var written = 0
                var totalBytes = 0L
                for ((z, x, y, data) in allTiles) {
                    if (isCancelled) break
                    writer.writeTile(z, x, y, data)
                    written++
                    totalBytes += data.size
                    _progress.value = _progress.value.copy(
                        downloadedTiles = written,
                        bytesDownloaded = totalBytes,
                        currentZoom = z,
                    )
                }

                if (isCancelled) {
                    writer.close()
                    outputFile.delete()
                    _progress.value = _progress.value.copy(
                        status = DownloadProgress.Status.CANCELLED,
                    )
                    return null
                }

                writer.optimize()
                writer.close()

                _progress.value = _progress.value.copy(
                    status = DownloadProgress.Status.COMPLETE,
                )

                Log.d(TAG, "RMSP download complete: ${allTiles.size} tiles, $totalBytes bytes")
                return outputFile
            } catch (e: Exception) {
                writer.close()
                outputFile.delete()
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "RMSP download failed: ${e.message}", e)
            _progress.value = _progress.value.copy(
                status = DownloadProgress.Status.ERROR,
                errorMessage = e.message ?: "RMSP download failed",
            )
            return null
        }
    }

    /**
     * Unpack RMSP tile data.
     *
     * Format: [tile_count: u32][tile_entries...]
     * tile_entry: [z: u8][x: u32][y: u32][size: u32][data: bytes]
     */
    private fun unpackRmspTiles(data: ByteArray): List<RmspTile> {
        val tiles = mutableListOf<RmspTile>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        if (data.size < 4) return tiles

        val tileCount = buffer.int
        Log.d(TAG, "RMSP tile count in header: $tileCount")

        repeat(tileCount) {
            if (buffer.remaining() < 13) return tiles // Need at least z(1) + x(4) + y(4) + size(4)

            val z = buffer.get().toInt() and 0xFF
            val x = buffer.int
            val y = buffer.int
            val size = buffer.int

            if (buffer.remaining() < size) return tiles

            val tileData = ByteArray(size)
            buffer.get(tileData)

            tiles.add(RmspTile(z, x, y, tileData))
        }

        return tiles
    }

    private data class RmspTile(
        val z: Int,
        val x: Int,
        val y: Int,
        val data: ByteArray,
    )

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
        val baseUrl = (tileSource as? TileSource.Http)?.baseUrl ?: DEFAULT_TILE_URL
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
                Log.w(TAG, "HTTP $responseCode for tile ${tile.z}/${tile.x}/${tile.y}")
                throw IOException("HTTP $responseCode for $urlString")
            }

            val data = connection.inputStream.use { it.readBytes() }
            Log.v(TAG, "Downloaded tile ${tile.z}/${tile.x}/${tile.y} (${data.size} bytes)")
            return data
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download tile ${tile.z}/${tile.x}/${tile.y}: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "TileDownloadManager"
        // OpenFreeMap requires a version in the URL path
        // This version should be updated periodically or fetched dynamically
        const val DEFAULT_TILE_URL = "https://tiles.openfreemap.org/planet/20251224_001001_pt"
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

        /**
         * Encode latitude/longitude to a geohash string.
         *
         * @param lat Latitude (-90 to 90)
         * @param lon Longitude (-180 to 180)
         * @param precision Number of characters in the geohash (1-12)
         * @return Geohash string
         */
        fun encodeGeohash(lat: Double, lon: Double, precision: Int = 5): String {
            val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
            var minLat = -90.0
            var maxLat = 90.0
            var minLon = -180.0
            var maxLon = 180.0
            var isLon = true
            var bit = 0
            var ch = 0
            val result = StringBuilder()

            while (result.length < precision) {
                if (isLon) {
                    val mid = (minLon + maxLon) / 2
                    if (lon >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        minLon = mid
                    } else {
                        maxLon = mid
                    }
                } else {
                    val mid = (minLat + maxLat) / 2
                    if (lat >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        minLat = mid
                    } else {
                        maxLat = mid
                    }
                }
                isLon = !isLon
                bit++

                if (bit == 5) {
                    result.append(base32[ch])
                    bit = 0
                    ch = 0
                }
            }

            return result.toString()
        }

        /**
         * Decode a geohash to its bounding box.
         *
         * @param geohash The geohash string
         * @return Bounds (south, west, north, east)
         */
        fun decodeGeohashBounds(geohash: String): MBTilesWriter.Bounds {
            val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
            var minLat = -90.0
            var maxLat = 90.0
            var minLon = -180.0
            var maxLon = 180.0
            var isLon = true

            for (c in geohash.lowercase()) {
                val idx = base32.indexOf(c)
                if (idx < 0) continue

                for (bit in 4 downTo 0) {
                    val bitValue = (idx shr bit) and 1
                    if (isLon) {
                        val mid = (minLon + maxLon) / 2
                        if (bitValue == 1) minLon = mid else maxLon = mid
                    } else {
                        val mid = (minLat + maxLat) / 2
                        if (bitValue == 1) minLat = mid else maxLat = mid
                    }
                    isLon = !isLon
                }
            }

            return MBTilesWriter.Bounds(
                west = minLon,
                south = minLat,
                east = maxLon,
                north = maxLat,
            )
        }

        /**
         * Get all geohashes at a given precision that cover a bounding box.
         *
         * @param bounds The bounding box to cover
         * @param precision Geohash precision (1-12)
         * @return Set of geohash strings covering the bounds
         */
        fun geohashesForBounds(bounds: MBTilesWriter.Bounds, precision: Int): Set<String> {
            val geohashes = mutableSetOf<String>()

            // Get the geohash cell size at this precision
            val sampleHash = encodeGeohash(bounds.south, bounds.west, precision)
            val sampleBounds = decodeGeohashBounds(sampleHash)
            val cellWidth = sampleBounds.east - sampleBounds.west
            val cellHeight = sampleBounds.north - sampleBounds.south

            // Iterate over the bounding box with step size slightly smaller than cell size
            // to ensure we don't miss any cells
            val stepLon = cellWidth * 0.9
            val stepLat = cellHeight * 0.9

            var lat = bounds.south
            while (lat <= bounds.north) {
                var lon = bounds.west
                while (lon <= bounds.east) {
                    geohashes.add(encodeGeohash(lat, lon, precision))
                    lon += stepLon
                }
                // Also check the east edge
                geohashes.add(encodeGeohash(lat, bounds.east, precision))
                lat += stepLat
            }

            // Also check the north edge
            var lon = bounds.west
            while (lon <= bounds.east) {
                geohashes.add(encodeGeohash(bounds.north, lon, precision))
                lon += stepLon
            }
            geohashes.add(encodeGeohash(bounds.north, bounds.east, precision))

            return geohashes
        }
    }
}
