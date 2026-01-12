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
 * Parameters for downloading a map region.
 */
data class RegionParams(
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val name: String,
    val outputFile: File,
)

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

    private val _progress =
        MutableStateFlow(
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
    ): File? =
        withContext(Dispatchers.IO) {
            isCancelled = false

            val params = RegionParams(centerLat, centerLon, radiusKm, minZoom, maxZoom, name, outputFile)

            // Branch based on tile source
            when (tileSource) {
                is TileSource.Http -> downloadRegionHttp(params)
                is TileSource.Rmsp -> downloadRegionRmsp(tileSource, params)
            }
        }

    /**
     * Download tiles from HTTP source.
     */
    private suspend fun downloadRegionHttp(params: RegionParams): File? {
        return try {
            // Calculate bounds and tiles
            _progress.value = _progress.value.copy(status = DownloadProgress.Status.CALCULATING)
            val bounds = MBTilesWriter.boundsFromCenter(params.centerLat, params.centerLon, params.radiusKm)
            val tiles = calculateTilesForRegion(bounds, params.minZoom, params.maxZoom)

            Log.d(TAG, "HTTP Download: center=(${params.centerLat}, ${params.centerLon}), radius=${params.radiusKm} km")
            Log.d(TAG, "Calculated ${tiles.size} tiles across zoom levels ${params.minZoom}-${params.maxZoom}")

            if (tiles.isEmpty()) {
                updateErrorStatus("No tiles found for region")
                return null
            }

            _progress.value =
                _progress.value.copy(
                    status = DownloadProgress.Status.DOWNLOADING,
                    totalTiles = tiles.size,
                    downloadedTiles = 0,
                    failedTiles = 0,
                    bytesDownloaded = 0,
                )

            val writer =
                MBTilesWriter(
                    file = params.outputFile,
                    name = params.name,
                    description = "Offline map for ${params.name}",
                    minZoom = params.minZoom,
                    maxZoom = params.maxZoom,
                    bounds = bounds,
                    center = MBTilesWriter.Center(params.centerLon, params.centerLat, (params.minZoom + params.maxZoom) / 2),
                )
            writer.open()

            var success = false
            try {
                success = executeHttpDownload(writer, tiles, params)
                if (success) {
                    _progress.value = _progress.value.copy(status = DownloadProgress.Status.WRITING)
                    writer.optimize()
                    _progress.value = _progress.value.copy(status = DownloadProgress.Status.COMPLETE)
                }
            } finally {
                writer.close()
                if (!success) {
                    params.outputFile.delete()
                }
            }
            if (success) params.outputFile else null
        } catch (e: Exception) {
            updateErrorStatus(e.message ?: "Download failed")
            null
        }
    }

    private suspend fun executeHttpDownload(
        writer: MBTilesWriter,
        tiles: List<TileCoord>,
        params: RegionParams,
    ): Boolean {
        val writeMutex = Mutex()
        val semaphore = Semaphore(CONCURRENT_DOWNLOADS)
        var downloadedCount = 0
        var failedCount = 0
        var totalBytes = 0L

        coroutineScope {
            val tilesByZoom = tiles.groupBy { it.z }

            for (zoom in params.minZoom..params.maxZoom) {
                if (isCancelled) break

                tilesByZoom[zoom]?.let { zoomTiles ->
                    Log.d(TAG, "Starting zoom level $zoom with ${zoomTiles.size} tiles")
                    _progress.value = _progress.value.copy(currentZoom = zoom)

                    // Process tiles in batches to reduce memory usage
                    zoomTiles.chunked(BATCH_SIZE).forEach { batch ->
                        if (isCancelled) return@forEach

                        // Download batch concurrently
                        val results =
                            batch.map { tile ->
                                async {
                                    if (isCancelled) return@async TileResult.Failed
                                    semaphore.withPermit { downloadTileWithRetry(tile) }
                                }
                            }.awaitAll()

                        // Write batch immediately to reduce memory footprint
                        for ((index, result) in results.withIndex()) {
                            when (result) {
                                is TileResult.Success -> {
                                    val tile = batch[index]
                                    writeMutex.withLock { writer.writeTile(tile.z, tile.x, tile.y, result.data) }
                                    downloadedCount++
                                    totalBytes += result.data.size
                                }
                                is TileResult.NotAvailable -> { /* Skip - no data at this location */ }
                                is TileResult.Failed -> failedCount++
                            }
                        }
                        _progress.value =
                            _progress.value.copy(
                                downloadedTiles = downloadedCount,
                                failedTiles = failedCount,
                                bytesDownloaded = totalBytes,
                            )
                    }
                }
            }
        }

        if (isCancelled) {
            // Note: writer.close() called by finally block in downloadRegionHttp()
            // Retry deletion with backoff - file handles may not release immediately
            repeat(5) { attempt ->
                delay(100L * (attempt + 1))
                if (params.outputFile.delete() || !params.outputFile.exists()) {
                    return@repeat
                }
            }
            if (params.outputFile.exists()) {
                Log.e(TAG, "Failed to delete cancelled download after retries: ${params.outputFile.absolutePath}")
            }
            _progress.value = _progress.value.copy(status = DownloadProgress.Status.CANCELLED)
            return false
        }

        return true
    }

    private fun updateErrorStatus(message: String) {
        _progress.value = _progress.value.copy(status = DownloadProgress.Status.ERROR, errorMessage = message)
    }

    /**
     * Download tiles from RMSP server.
     */
    private suspend fun downloadRegionRmsp(
        source: TileSource.Rmsp,
        params: RegionParams,
    ): File? {
        return try {
            val allTiles = fetchAllRmspTiles(source, params) ?: return null

            if (allTiles.isEmpty()) {
                updateErrorStatus("No tiles received from RMSP server")
                return null
            }

            writeRmspTilesToFile(allTiles, params)
        } catch (e: Exception) {
            Log.e(TAG, "RMSP download failed: ${e.message}", e)
            updateErrorStatus(e.message ?: "RMSP download failed")
            null
        }
    }

    private suspend fun fetchAllRmspTiles(
        source: TileSource.Rmsp,
        params: RegionParams,
    ): List<RmspTile>? {
        _progress.value = _progress.value.copy(status = DownloadProgress.Status.CALCULATING)

        val bounds = MBTilesWriter.boundsFromCenter(params.centerLat, params.centerLon, params.radiusKm)
        // Adjust precision based on radius (precision 2 cells are ~630km, too coarse for most requests)
        val geohashPrecision =
            when {
                params.radiusKm <= 5 -> 5   // ~5km cells
                params.radiusKm <= 20 -> 4  // ~40km cells
                params.radiusKm <= 50 -> 4  // Still fits in ~40km cells
                else -> 3                   // ~150km cells for 50-100km radius
            }
        val geohashes = geohashesForBounds(bounds, geohashPrecision)

        Log.d(TAG, "RMSP Download: ${geohashes.size} geohash cells at precision $geohashPrecision")
        Log.d(TAG, "Server: ${source.serverHash}, zoom range ${params.minZoom}-${params.maxZoom}")

        _progress.value =
            _progress.value.copy(
                status = DownloadProgress.Status.DOWNLOADING,
                totalTiles = geohashes.size,
                downloadedTiles = 0,
            )

        val allTiles = mutableListOf<RmspTile>()
        val seenCoords = mutableSetOf<Triple<Int, Int, Int>>()
        var processed = 0

        for (geohash in geohashes) {
            if (isCancelled) break

            val tileData = source.fetchTiles(geohash, listOf(params.minZoom, params.maxZoom))
            tileData?.takeIf { it.isNotEmpty() }?.let { data ->
                // Filter duplicates to reduce memory and database overhead
                for (tile in unpackRmspTiles(data)) {
                    val coord = Triple(tile.z, tile.x, tile.y)
                    if (seenCoords.add(coord)) {
                        allTiles.add(tile)
                    }
                }
            }
            processed++
            _progress.value = _progress.value.copy(downloadedTiles = processed, totalTiles = geohashes.size)
        }

        if (isCancelled) {
            _progress.value = _progress.value.copy(status = DownloadProgress.Status.CANCELLED)
            return null
        }

        Log.d(TAG, "Total unique tiles collected: ${allTiles.size}")
        return allTiles
    }

    private fun writeRmspTilesToFile(
        allTiles: List<RmspTile>,
        params: RegionParams,
    ): File? {
        _progress.value = _progress.value.copy(totalTiles = allTiles.size, downloadedTiles = 0, status = DownloadProgress.Status.WRITING)

        val bounds = MBTilesWriter.boundsFromCenter(params.centerLat, params.centerLon, params.radiusKm)
        val writer =
            MBTilesWriter(
                file = params.outputFile,
                name = params.name,
                description = "Offline map from RMSP: ${params.name}",
                minZoom = params.minZoom,
                maxZoom = params.maxZoom,
                bounds = bounds,
                center = MBTilesWriter.Center(params.centerLon, params.centerLat, (params.minZoom + params.maxZoom) / 2),
            )
        writer.open()

        try {
            var written = 0
            var totalBytes = 0L
            for (tile in allTiles) {
                if (isCancelled) {
                    writer.close()
                    // Retry deletion with backoff - file handles may not release immediately
                    repeat(5) { attempt ->
                        Thread.sleep(100L * (attempt + 1))
                        if (params.outputFile.delete() || !params.outputFile.exists()) {
                            return@repeat
                        }
                    }
                    if (params.outputFile.exists()) {
                        Log.e(TAG, "Failed to delete cancelled RMSP download: ${params.outputFile.absolutePath}")
                    }
                    _progress.value = _progress.value.copy(status = DownloadProgress.Status.CANCELLED)
                    return null
                }
                writer.writeTile(tile.z, tile.x, tile.y, tile.data)
                written++
                totalBytes += tile.data.size
                _progress.value = _progress.value.copy(downloadedTiles = written, bytesDownloaded = totalBytes, currentZoom = tile.z)
            }

            writer.optimize()
            writer.close()
            _progress.value = _progress.value.copy(status = DownloadProgress.Status.COMPLETE)
            Log.d(TAG, "RMSP download complete: ${allTiles.size} tiles, $totalBytes bytes")
            return params.outputFile
        } catch (e: Exception) {
            writer.close()
            params.outputFile.delete()
            throw e
        }
    }

    /**
     * Unpack RMSP tile data.
     *
     * Format: tile_count (u32), followed by tile_entries
     * Each tile_entry: z (u8), x (u32), y (u32), size (u32), data (bytes)
     */
    @Suppress("MemberVisibilityCanBePrivate", "ReturnCount") // Internal for testing; multiple returns for security validation
    internal fun unpackRmspTiles(data: ByteArray): List<RmspTile> {
        val tiles = mutableListOf<RmspTile>()
        var cumulativeSize = 0L
        if (data.size < 4) return tiles

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val tileCount = buffer.int
        Log.d(TAG, "RMSP tile count in header: $tileCount")

        // Validate tile count to prevent DoS attacks
        if (tileCount < 0 || tileCount > 100_000) {
            Log.w(TAG, "Invalid tile count: $tileCount (expected 0-100000)")
            return tiles
        }

        repeat(tileCount) {
            // Need at least z(1) + x(4) + y(4) + size(4) = 13 bytes, then tile data
            val headerAvailable = buffer.remaining() >= 13
            if (!headerAvailable) return@repeat

            val z = buffer.get().toInt() and 0xFF
            val x = buffer.int
            val y = buffer.int
            val size = buffer.int

            // Validate zoom level (0-22 covers all practical tile systems)
            if (z > 22) {
                Log.w(TAG, "Invalid zoom level: $z (expected 0-22, aborting)")
                return tiles // Data is corrupted
            }

            // Validate tile coordinates are within bounds for this zoom level
            // Use Long to prevent overflow if zoom validation ever changes
            val maxCoord = ((1L shl z) - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val xInBounds = x in 0..maxCoord
            val yInBounds = y in 0..maxCoord
            if (!xInBounds || !yInBounds) {
                Log.w(TAG, "Invalid coordinates for zoom $z: x=$x, y=$y (expected 0-$maxCoord, aborting)")
                return tiles // Data is corrupted
            }

            // Validate tile size to prevent OOM attacks (vector tiles are typically 5-50KB)
            if (size < 0 || size > 1_000_000) { // 1MB max per tile
                Log.w(TAG, "Invalid tile size: $size bytes (aborting)")
                return tiles // Data is likely corrupted
            }

            // Check cumulative size to prevent memory exhaustion (tracked incrementally to avoid O(n²))
            if (cumulativeSize + size > 100_000_000) { // 100MB max total
                Log.w(TAG, "Total tile data exceeds 100MB limit (aborting)")
                return tiles
            }

            val dataAvailable = buffer.remaining() >= size
            if (!dataAvailable) return@repeat

            val tileData = ByteArray(size)
            buffer.get(tileData)
            tiles.add(RmspTile(z, x, y, tileData))
            cumulativeSize += size
        }

        return tiles
    }

    @Suppress("MemberVisibilityCanBePrivate") // Internal for testing
    internal data class RmspTile(
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
        if (_progress.value.status == DownloadProgress.Status.DOWNLOADING) {
            _progress.value = _progress.value.copy(status = DownloadProgress.Status.CANCELLED)
        }
    }

    /**
     * Reset the progress state.
     */
    fun reset() {
        isCancelled = false
        _progress.value =
            DownloadProgress(
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

    /** Result of attempting to download a tile */
    private sealed class TileResult {
        data class Success(val data: ByteArray) : TileResult()
        object NotAvailable : TileResult() // 204 - tile doesn't exist at this location
        object Failed : TileResult() // Failed after retries
    }

    private suspend fun downloadTileWithRetry(tile: TileCoord): TileResult {
        repeat(MAX_RETRIES) { attempt ->
            try {
                val data = downloadTile(tile)
                return if (data != null) TileResult.Success(data) else TileResult.NotAvailable
            } catch (e: IOException) {
                Log.w(TAG, "Download attempt ${attempt + 1} failed for tile ${tile.z}/${tile.x}/${tile.y}: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        return TileResult.Failed
    }

    private fun downloadTile(tile: TileCoord): ByteArray? {
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
            if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                // Tile not available at this location/zoom - skip storing
                Log.v(TAG, "Tile ${tile.z}/${tile.x}/${tile.y} not available (204)")
                return null
            }
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

        // OpenFreeMap tiles - use versionless URL that redirects to latest
        const val DEFAULT_TILE_URL = "https://tiles.openfreemap.org/planet"

        // Network I/O bound - higher concurrency than CPU count is optimal
        const val CONCURRENT_DOWNLOADS = 10
        const val BATCH_SIZE = 100 // Process tiles in batches to reduce memory usage
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L
        const val AVERAGE_TILE_SIZE_BYTES = 15_000L
        const val USER_AGENT = "Columba/1.0 (Android; Offline Maps)"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000

        /**
         * Convert latitude/longitude to tile coordinates.
         */
        fun latLonToTile(
            lat: Double,
            lon: Double,
            zoom: Int,
        ): TileCoord {
            val n = 2.0.pow(zoom)
            val x = floor((lon + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
            return TileCoord(zoom, x.coerceIn(0, n.toInt() - 1), y.coerceIn(0, n.toInt() - 1))
        }

        /**
         * Convert tile coordinates to latitude/longitude (top-left corner).
         */
        fun tileToLatLon(
            z: Int,
            x: Int,
            y: Int,
        ): Pair<Double, Double> {
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
        fun encodeGeohash(
            lat: Double,
            lon: Double,
            precision: Int = 5,
        ): String {
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
        fun geohashesForBounds(
            bounds: MBTilesWriter.Bounds,
            precision: Int,
        ): Set<String> {
            val geohashes = mutableSetOf<String>()

            // Get cell dimensions from a sample
            val sampleHash = encodeGeohash(bounds.south, bounds.west, precision)
            val sampleBounds = decodeGeohashBounds(sampleHash)
            val cellWidth = sampleBounds.east - sampleBounds.west
            val cellHeight = sampleBounds.north - sampleBounds.south

            // Calculate grid with 10% overlap to ensure no gaps
            val stepLat = cellHeight * 0.9
            val stepLon = cellWidth * 0.9

            // Handle date line crossing: normalize longitude bounds
            val westLon = bounds.west
            val eastLon = if (bounds.east < bounds.west) bounds.east + 360.0 else bounds.east

            var lat = bounds.south
            while (lat <= bounds.north) {
                var lon = westLon
                while (lon <= eastLon) {
                    // Normalize longitude back to [-180, 180] for encoding
                    val normLon = if (lon > 180.0) lon - 360.0 else lon
                    geohashes.add(encodeGeohash(lat, normLon, precision))
                    lon += stepLon
                }
                // Ensure east edge is covered
                geohashes.add(encodeGeohash(lat, bounds.east, precision))
                lat += stepLat
            }

            // Ensure north edge is covered
            var lon = westLon
            while (lon <= eastLon) {
                val normLon = if (lon > 180.0) lon - 360.0 else lon
                geohashes.add(encodeGeohash(bounds.north, normLon, precision))
                lon += stepLon
            }
            // Ensure northeast corner is covered
            geohashes.add(encodeGeohash(bounds.north, bounds.east, precision))

            // Safety limit to prevent memory exhaustion
            if (geohashes.size > MAX_GEOHASHES) {
                Log.w(TAG, "Geohash count ${geohashes.size} exceeds limit $MAX_GEOHASHES, truncating")
                return geohashes.take(MAX_GEOHASHES).toSet()
            }

            return geohashes
        }

        private const val MAX_GEOHASHES = 500
    }
}
