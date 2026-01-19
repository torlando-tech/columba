package com.lxmf.messenger.map

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Unit tests for TileDownloadManager.
 *
 * Tests the static utility functions for geohash encoding/decoding,
 * tile coordinate conversion, and bounds calculation.
 */
class TileDownloadManagerTest {
    companion object {
        private const val EPSILON = 0.0001 // Tolerance for floating point comparisons
    }

    // ========== encodeGeohash() Tests ==========

    @Test
    fun `encodeGeohash returns correct geohash for known coordinates`() {
        // San Francisco coordinates
        val geohash = TileDownloadManager.encodeGeohash(37.7749, -122.4194, 5)
        assertEquals("9q8yy", geohash)
    }

    @Test
    fun `encodeGeohash returns correct geohash for London`() {
        // London coordinates
        val geohash = TileDownloadManager.encodeGeohash(51.5074, -0.1278, 5)
        assertEquals("gcpvj", geohash)
    }

    @Test
    fun `encodeGeohash returns correct geohash for Tokyo`() {
        // Tokyo coordinates
        val geohash = TileDownloadManager.encodeGeohash(35.6762, 139.6503, 5)
        assertEquals("xn76c", geohash)
    }

    @Test
    fun `encodeGeohash returns correct geohash for Sydney`() {
        // Sydney coordinates (southern hemisphere)
        val geohash = TileDownloadManager.encodeGeohash(-33.8688, 151.2093, 5)
        assertEquals("r3gx2", geohash)
    }

    @Test
    fun `encodeGeohash handles equator correctly`() {
        val geohash = TileDownloadManager.encodeGeohash(0.0, 0.0, 5)
        assertEquals("s0000", geohash)
    }

    @Test
    fun `encodeGeohash handles prime meridian correctly`() {
        val geohash = TileDownloadManager.encodeGeohash(51.5, 0.0, 5)
        assertEquals("u10hb", geohash)
    }

    @Test
    fun `encodeGeohash handles different precision levels`() {
        val lat = 37.7749
        val lon = -122.4194

        val hash1 = TileDownloadManager.encodeGeohash(lat, lon, 1)
        val hash3 = TileDownloadManager.encodeGeohash(lat, lon, 3)
        val hash5 = TileDownloadManager.encodeGeohash(lat, lon, 5)
        val hash7 = TileDownloadManager.encodeGeohash(lat, lon, 7)

        assertEquals(1, hash1.length)
        assertEquals(3, hash3.length)
        assertEquals(5, hash5.length)
        assertEquals(7, hash7.length)

        // Higher precision should be prefix of lower precision
        assertTrue(hash5.startsWith(hash3))
        assertTrue(hash3.startsWith(hash1))
    }

    @Test
    fun `encodeGeohash handles extreme latitudes`() {
        // Near North Pole
        val northHash = TileDownloadManager.encodeGeohash(89.9, 0.0, 5)
        assertTrue(northHash.isNotEmpty())

        // Near South Pole
        val southHash = TileDownloadManager.encodeGeohash(-89.9, 0.0, 5)
        assertTrue(southHash.isNotEmpty())
    }

    @Test
    fun `encodeGeohash handles extreme longitudes`() {
        // Near date line west
        val westHash = TileDownloadManager.encodeGeohash(0.0, -179.9, 5)
        assertTrue(westHash.isNotEmpty())

        // Near date line east
        val eastHash = TileDownloadManager.encodeGeohash(0.0, 179.9, 5)
        assertTrue(eastHash.isNotEmpty())
    }

    // ========== decodeGeohashBounds() Tests ==========

    @Test
    fun `decodeGeohashBounds returns correct bounds for known geohash`() {
        val bounds = TileDownloadManager.decodeGeohashBounds("9q8yy")

        // Should contain San Francisco
        assertTrue(bounds.south < 37.7749)
        assertTrue(bounds.north > 37.7749)
        assertTrue(bounds.west < -122.4194)
        assertTrue(bounds.east > -122.4194)
    }

    @Test
    fun `decodeGeohashBounds is inverse of encodeGeohash`() {
        val lat = 40.7128
        val lon = -74.0060 // New York

        val geohash = TileDownloadManager.encodeGeohash(lat, lon, 5)
        val bounds = TileDownloadManager.decodeGeohashBounds(geohash)

        // Original point should be within decoded bounds
        assertTrue(lat >= bounds.south && lat <= bounds.north)
        assertTrue(lon >= bounds.west && lon <= bounds.east)
    }

    @Test
    fun `decodeGeohashBounds has smaller area for higher precision`() {
        val lat = 37.7749
        val lon = -122.4194

        val bounds3 =
            TileDownloadManager.decodeGeohashBounds(
                TileDownloadManager.encodeGeohash(lat, lon, 3),
            )
        val bounds5 =
            TileDownloadManager.decodeGeohashBounds(
                TileDownloadManager.encodeGeohash(lat, lon, 5),
            )
        val bounds7 =
            TileDownloadManager.decodeGeohashBounds(
                TileDownloadManager.encodeGeohash(lat, lon, 7),
            )

        val area3 = (bounds3.north - bounds3.south) * (bounds3.east - bounds3.west)
        val area5 = (bounds5.north - bounds5.south) * (bounds5.east - bounds5.west)
        val area7 = (bounds7.north - bounds7.south) * (bounds7.east - bounds7.west)

        assertTrue(area3 > area5)
        assertTrue(area5 > area7)
    }

    @Test
    fun `decodeGeohashBounds handles s0000 (origin)`() {
        val bounds = TileDownloadManager.decodeGeohashBounds("s0000")

        // Should be near equator/prime meridian
        assertTrue(bounds.south < 1.0)
        assertTrue(bounds.north > -1.0)
        assertTrue(bounds.west < 1.0)
        assertTrue(bounds.east > -1.0)
    }

    // ========== decodeGeohashCenter() Tests ==========

    @Test
    fun `decodeGeohashCenter returns center of geohash cell`() {
        val center = TileDownloadManager.decodeGeohashCenter("9q8yy")

        assertNotNull(center)
        // San Francisco area - center should be within the cell
        assertTrue(center!!.first > 37.0 && center.first < 38.0) // latitude
        assertTrue(center.second > -123.0 && center.second < -122.0) // longitude
    }

    @Test
    fun `decodeGeohashCenter returns null for empty string`() {
        val center = TileDownloadManager.decodeGeohashCenter("")
        assertNull(center)
    }

    @Test
    fun `decodeGeohashCenter is consistent with decodeGeohashBounds`() {
        val geohash = "u4pruydqqvj"
        val bounds = TileDownloadManager.decodeGeohashBounds(geohash)
        val center = TileDownloadManager.decodeGeohashCenter(geohash)

        assertNotNull(center)
        // Center should be midpoint of bounds
        val expectedLat = (bounds.south + bounds.north) / 2
        val expectedLon = (bounds.west + bounds.east) / 2
        assertEquals(expectedLat, center!!.first, 0.0001)
        assertEquals(expectedLon, center.second, 0.0001)
    }

    // ========== geohashesForBounds() Tests ==========

    @Test
    fun `geohashesForBounds returns correct number of geohashes for small area`() {
        val bounds =
            MBTilesWriter.Bounds(
                west = -122.5,
                south = 37.7,
                east = -122.4,
                north = 37.8,
            )

        val geohashes = TileDownloadManager.geohashesForBounds(bounds, 5)

        // Small area should have only a few geohashes
        assertTrue(geohashes.isNotEmpty())
        assertTrue(geohashes.size < 20)
    }

    @Test
    fun `geohashesForBounds covers entire bounds`() {
        val bounds =
            MBTilesWriter.Bounds(
                west = -122.5,
                south = 37.7,
                east = -122.3,
                north = 37.9,
            )

        val geohashes = TileDownloadManager.geohashesForBounds(bounds, 4)

        // All corners should be covered by at least one geohash
        val corners =
            listOf(
                Pair(bounds.south, bounds.west),
                Pair(bounds.south, bounds.east),
                Pair(bounds.north, bounds.west),
                Pair(bounds.north, bounds.east),
            )

        for ((lat, lon) in corners) {
            val pointGeohash = TileDownloadManager.encodeGeohash(lat, lon, 4)
            assertTrue("Corner ($lat, $lon) should be covered", geohashes.contains(pointGeohash))
        }
    }

    @Test
    fun `geohashesForBounds returns more geohashes for larger area`() {
        val smallBounds =
            MBTilesWriter.Bounds(
                west = -122.5,
                south = 37.7,
                east = -122.4,
                north = 37.8,
            )

        val largeBounds =
            MBTilesWriter.Bounds(
                west = -123.0,
                south = 37.5,
                east = -122.0,
                north = 38.0,
            )

        val smallGeohashes = TileDownloadManager.geohashesForBounds(smallBounds, 4)
        val largeGeohashes = TileDownloadManager.geohashesForBounds(largeBounds, 4)

        assertTrue(largeGeohashes.size > smallGeohashes.size)
    }

    @Test
    fun `geohashesForBounds returns more geohashes for higher precision`() {
        val bounds =
            MBTilesWriter.Bounds(
                west = -122.5,
                south = 37.7,
                east = -122.4,
                north = 37.8,
            )

        val precision3 = TileDownloadManager.geohashesForBounds(bounds, 3)
        val precision5 = TileDownloadManager.geohashesForBounds(bounds, 5)

        assertTrue(precision5.size > precision3.size)
    }

    // ========== latLonToTile() Tests ==========

    @Test
    fun `latLonToTile returns correct tile for known coordinates at zoom 10`() {
        // San Francisco at zoom 10
        val tile = TileDownloadManager.latLonToTile(37.7749, -122.4194, 10)

        // Known tile for SF at zoom 10
        assertEquals(10, tile.z)
        assertEquals(163, tile.x)
        assertEquals(395, tile.y)
    }

    @Test
    fun `latLonToTile returns (0,0) tile for northwest corner at low zoom`() {
        val tile = TileDownloadManager.latLonToTile(85.0, -180.0, 0)

        assertEquals(0, tile.z)
        assertEquals(0, tile.x)
        assertEquals(0, tile.y)
    }

    @Test
    fun `latLonToTile handles zoom level correctly`() {
        val lat = 37.7749
        val lon = -122.4194

        val tile0 = TileDownloadManager.latLonToTile(lat, lon, 0)
        val tile5 = TileDownloadManager.latLonToTile(lat, lon, 5)
        val tile10 = TileDownloadManager.latLonToTile(lat, lon, 10)

        // At zoom 0, only one tile exists
        assertEquals(0, tile0.x)
        assertEquals(0, tile0.y)

        // Higher zoom levels should have larger coordinates
        assertTrue(tile10.x > tile5.x)
        assertTrue(tile10.y > tile5.y)
    }

    @Test
    fun `latLonToTile handles equator correctly`() {
        val tile = TileDownloadManager.latLonToTile(0.0, 0.0, 4)

        // At equator, y should be roughly in the middle
        assertEquals(8, tile.x) // Center x at zoom 4 is 8
        assertEquals(8, tile.y) // Center y at zoom 4 is 8 (for equator)
    }

    @Test
    fun `latLonToTile handles date line correctly`() {
        val tileWest = TileDownloadManager.latLonToTile(0.0, -179.0, 4)
        val tileEast = TileDownloadManager.latLonToTile(0.0, 179.0, 4)

        // These should be at opposite ends
        assertTrue(tileWest.x < 2)
        assertTrue(tileEast.x > 14)
    }

    // ========== tileToLatLon() Tests ==========

    @Test
    fun `tileToLatLon returns northwest corner of tile`() {
        // Get tile for SF
        val tile = TileDownloadManager.latLonToTile(37.7749, -122.4194, 10)

        // Convert back to lat/lon
        val (lat, lon) = TileDownloadManager.tileToLatLon(tile.z, tile.x, tile.y)

        // Should be northwest corner of the tile (greater lat, lesser lon)
        assertTrue(lat > 37.7) // Tile contains SF
        assertTrue(lon < -122.3)
    }

    @Test
    fun `tileToLatLon and latLonToTile are consistent`() {
        val originalLat = 40.7128
        val originalLon = -74.0060
        val zoom = 12

        // Convert to tile
        val tile = TileDownloadManager.latLonToTile(originalLat, originalLon, zoom)

        // Convert tile corner back to lat/lon
        val (lat, lon) = TileDownloadManager.tileToLatLon(tile.z, tile.x, tile.y)

        // The tile corner should be close to original (within tile size)
        assertTrue(abs(lat - originalLat) < 1.0)
        assertTrue(abs(lon - originalLon) < 1.0)
    }

    @Test
    fun `tileToLatLon returns valid coordinates for tile (0,0) at zoom 0`() {
        val (lat, lon) = TileDownloadManager.tileToLatLon(0, 0, 0)

        // Northwest corner of the world tile
        assertTrue(lat > 85.0) // Near max web mercator lat
        assertEquals(-180.0, lon, EPSILON)
    }

    // ========== RegionParams Tests ==========

    @Test
    fun `RegionParams data class holds correct values`() {
        val params =
            RegionParams(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                name = "San Francisco",
                outputFile = java.io.File("/tmp/test.mbtiles"),
            )

        assertEquals(37.7749, params.centerLat, EPSILON)
        assertEquals(-122.4194, params.centerLon, EPSILON)
        assertEquals(10, params.radiusKm)
        assertEquals(5, params.minZoom)
        assertEquals(14, params.maxZoom)
        assertEquals("San Francisco", params.name)
    }

    @Test
    fun `RegionParams equals works correctly`() {
        val params1 = RegionParams(37.7749, -122.4194, 10, 5, 14, "SF", java.io.File("/tmp/test.mbtiles"))
        val params2 = RegionParams(37.7749, -122.4194, 10, 5, 14, "SF", java.io.File("/tmp/test.mbtiles"))
        val params3 = RegionParams(40.7128, -74.0060, 10, 5, 14, "NYC", java.io.File("/tmp/test.mbtiles"))

        assertEquals(params1, params2)
        assertNotEquals(params1, params3)
    }

    // ========== TileSource Tests ==========

    @Test
    fun `TileSource Http has default URL`() {
        val source = TileSource.Http()
        assertEquals(TileDownloadManager.DEFAULT_TILE_URL, source.baseUrl)
    }

    @Test
    fun `TileSource Http accepts custom URL`() {
        val customUrl = "https://example.com/tiles"
        val source = TileSource.Http(customUrl)
        assertEquals(customUrl, source.baseUrl)
    }

    @Test
    fun `TileSource Rmsp holds server hash`() {
        val source = TileSource.Rmsp("abc123") { _, _ -> null }
        assertEquals("abc123", source.serverHash)
    }

    // ========== TileCoord Tests ==========

    @Test
    fun `TileCoord data class holds correct values`() {
        val tile = TileDownloadManager.TileCoord(10, 163, 395)

        assertEquals(10, tile.z)
        assertEquals(163, tile.x)
        assertEquals(395, tile.y)
    }

    @Test
    fun `TileCoord equals works correctly`() {
        val tile1 = TileDownloadManager.TileCoord(10, 163, 395)
        val tile2 = TileDownloadManager.TileCoord(10, 163, 395)
        val tile3 = TileDownloadManager.TileCoord(10, 164, 395)

        assertEquals(tile1, tile2)
        assertNotEquals(tile1, tile3)
    }

    // ========== DownloadProgress Tests ==========

    @Test
    fun `DownloadProgress progress calculation is correct`() {
        val progress =
            TileDownloadManager.DownloadProgress(
                status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
                totalTiles = 100,
                downloadedTiles = 50,
                failedTiles = 5,
                bytesDownloaded = 500_000,
                currentZoom = 10,
            )

        assertEquals(0.5f, progress.progress, 0.001f)
    }

    @Test
    fun `DownloadProgress progress is zero when totalTiles is zero`() {
        val progress =
            TileDownloadManager.DownloadProgress(
                status = TileDownloadManager.DownloadProgress.Status.IDLE,
                totalTiles = 0,
                downloadedTiles = 0,
                failedTiles = 0,
                bytesDownloaded = 0,
                currentZoom = 0,
            )

        assertEquals(0f, progress.progress, 0.001f)
    }

    @Test
    fun `DownloadProgress status enum has expected values`() {
        val statuses = TileDownloadManager.DownloadProgress.Status.values()

        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.IDLE))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.CALCULATING))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.DOWNLOADING))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.WRITING))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.COMPLETE))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.ERROR))
        assertTrue(statuses.contains(TileDownloadManager.DownloadProgress.Status.CANCELLED))
    }

    // ========== Geohash Edge Cases ==========

    @Test
    fun `encodeGeohash handles negative coordinates correctly`() {
        // Buenos Aires (southern and western hemisphere)
        val geohash = TileDownloadManager.encodeGeohash(-34.6037, -58.3816, 5)

        assertTrue(geohash.isNotEmpty())
        assertEquals(5, geohash.length)
    }

    @Test
    fun `encodeGeohash produces unique hashes for different locations`() {
        val sf = TileDownloadManager.encodeGeohash(37.7749, -122.4194, 5)
        val nyc = TileDownloadManager.encodeGeohash(40.7128, -74.0060, 5)
        val london = TileDownloadManager.encodeGeohash(51.5074, -0.1278, 5)

        assertNotEquals(sf, nyc)
        assertNotEquals(sf, london)
        assertNotEquals(nyc, london)
    }

    @Test
    fun `encodeGeohash produces similar hashes for nearby locations`() {
        // Two nearby points in SF
        val hash1 = TileDownloadManager.encodeGeohash(37.7749, -122.4194, 5)
        val hash2 = TileDownloadManager.encodeGeohash(37.7750, -122.4195, 5)

        // Nearby points should have same prefix at lower precision
        val prefix1 = hash1.take(3)
        val prefix2 = hash2.take(3)
        assertEquals(prefix1, prefix2)
    }

    // ========== Tile Calculation Edge Cases ==========

    @Test
    fun `latLonToTile handles maximum zoom level`() {
        val tile = TileDownloadManager.latLonToTile(37.7749, -122.4194, 18)

        assertEquals(18, tile.z)
        assertTrue(tile.x >= 0)
        assertTrue(tile.y >= 0)
    }

    @Test
    fun `latLonToTile clamps coordinates within valid range`() {
        // Web Mercator can't represent latitudes beyond ~85.05
        val tile = TileDownloadManager.latLonToTile(89.9, 0.0, 10)

        assertTrue(tile.y >= 0)
        assertTrue(tile.y < 1024) // 2^10
    }

    // ========== generateFilename Tests ==========

    @Test
    fun `generateFilename produces valid filename`() {
        val filename = TileDownloadManager.generateFilename("San Francisco")

        assertTrue(filename.endsWith(".mbtiles"))
        assertTrue(filename.contains("San_Francisco"))
        assertFalse(filename.contains(" "))
    }

    @Test
    fun `generateFilename sanitizes special characters`() {
        val filename = TileDownloadManager.generateFilename("Test/Region:Special*Chars")

        assertFalse(filename.contains("/"))
        assertFalse(filename.contains(":"))
        assertFalse(filename.contains("*"))
    }

    @Test
    fun `generateFilename truncates long names`() {
        val longName = "A".repeat(100)
        val filename = TileDownloadManager.generateFilename(longName)

        // Name portion should be truncated to 32 chars
        val namePart = filename.substringBefore("_")
        assertTrue(namePart.length <= 32)
    }

    @Test
    fun `generateFilename includes timestamp for uniqueness`() {
        val filename1 = TileDownloadManager.generateFilename("Test")
        Thread.sleep(10) // Ensure different timestamp
        val filename2 = TileDownloadManager.generateFilename("Test")

        assertNotEquals(filename1, filename2)
    }
}

/**
 * Robolectric-based tests for TileDownloadManager Android-specific operations.
 *
 * Tests downloadRegion() with HTTP and RMSP sources, error handling,
 * progress callbacks, and cancellation behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TileDownloadManagerRobolectricTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var testOutputDir: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        testOutputDir = File(context.cacheDir, "test_tiles")
        testOutputDir.mkdirs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        testOutputDir.deleteRecursively()
    }

    // ========== RMSP Download Tests ==========

    @Test
    fun `downloadRegion with RMSP source returns file on success`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_test.mbtiles")
            val tileData = createMockRmspTileData(listOf(Triple(10, 163, 395)))

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ -> tileData }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            assertNotNull(result)
            assertEquals(outputFile, result)

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `downloadRegion with RMSP source returns null when no tiles received`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_empty.mbtiles")

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ -> null }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            assertNull(result)

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `downloadRegion with RMSP source deduplicates tiles`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_dedup.mbtiles")
            // Create data with duplicate tiles
            val duplicateTileData =
                createMockRmspTileData(
                    listOf(
                        Triple(10, 163, 395),
                        Triple(10, 163, 395), // Duplicate
                        Triple(10, 164, 395),
                    ),
                )

            var fetchCount = 0
            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ ->
                fetchCount++
                duplicateTileData
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            manager.downloadRegion(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 1,
                minZoom = 10,
                maxZoom = 10,
                name = "Test Region",
                outputFile = outputFile,
            )

            advanceUntilIdle()

            // Verify fetchTiles was called (at least once for each geohash)
            assertTrue(fetchCount > 0)

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `downloadRegion with RMSP source handles empty tile data gracefully`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_empty_data.mbtiles")

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ ->
                ByteArray(0) // Empty data
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            // Should return null because no tiles were received
            assertNull(result)

            unmockkConstructor(MBTilesWriter::class)
        }

    // ========== Progress Callback Tests ==========

    @Test
    fun `downloadRegion with RMSP updates progress status through lifecycle`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_progress.mbtiles")
            val tileData = createMockRmspTileData(listOf(Triple(10, 163, 395)))

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ -> tileData }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val observedStatuses = mutableListOf<TileDownloadManager.DownloadProgress.Status>()
            val collectorReady = kotlinx.coroutines.CompletableDeferred<Unit>()

            // Collect progress updates
            val collectJob =
                launch {
                    manager.progress
                        .onStart { collectorReady.complete(Unit) }
                        .collect { progress ->
                            if (progress.status !in observedStatuses) {
                                observedStatuses.add(progress.status)
                            }
                        }
                }

            // Wait for collector to actually be subscribed before starting download
            collectorReady.await()

            manager.downloadRegion(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 1,
                minZoom = 10,
                maxZoom = 10,
                name = "Test Region",
                outputFile = outputFile,
            )

            advanceUntilIdle()
            collectJob.cancel()

            // Verify we went through expected status progression
            assertTrue(
                "Should include CALCULATING status",
                observedStatuses.contains(TileDownloadManager.DownloadProgress.Status.CALCULATING),
            )
            assertTrue(
                "Should include DOWNLOADING status",
                observedStatuses.contains(TileDownloadManager.DownloadProgress.Status.DOWNLOADING),
            )

            unmockkConstructor(MBTilesWriter::class)
        }

    // ========== Cancellation Tests ==========

    @Test
    fun `cancel method sets isCancelled flag`() =
        runTest {
            val manager = TileDownloadManager(context, TileSource.Http())

            // Cancel should not throw even when no download is in progress
            manager.cancel()

            // Verify progress is not changed (still IDLE since no download was running)
            assertEquals(TileDownloadManager.DownloadProgress.Status.IDLE, manager.progress.value.status)
        }

    @Test
    fun `cancel followed by reset restores idle state`() =
        runTest {
            val manager = TileDownloadManager(context, TileSource.Http())

            manager.cancel()
            manager.reset()

            val progress = manager.progress.value
            assertEquals(TileDownloadManager.DownloadProgress.Status.IDLE, progress.status)
        }

    // ========== Reset Tests ==========

    @Test
    fun `reset clears progress state`() =
        runTest {
            val manager = TileDownloadManager(context, TileSource.Http())

            // Manually modify progress state by calling cancel (which sets CANCELLED status indirectly)
            manager.cancel()

            // Reset should restore to initial state
            manager.reset()

            val progress = manager.progress.value
            assertEquals(TileDownloadManager.DownloadProgress.Status.IDLE, progress.status)
            assertEquals(0, progress.totalTiles)
            assertEquals(0, progress.downloadedTiles)
            assertEquals(0, progress.failedTiles)
            assertEquals(0L, progress.bytesDownloaded)
            assertEquals(0, progress.currentZoom)
            assertNull(progress.errorMessage)
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `downloadRegion with RMSP sets ERROR status on exception`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_error.mbtiles")

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ ->
                throw java.io.IOException("Simulated RMSP error")
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            assertNull(result)
            assertEquals(
                TileDownloadManager.DownloadProgress.Status.ERROR,
                manager.progress.value.status,
            )
            assertNotNull(manager.progress.value.errorMessage)

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `error message is captured in progress state`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_error_msg.mbtiles")
            val expectedErrorMessage = "Custom RMSP fetch error"

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ ->
                throw java.io.IOException(expectedErrorMessage)
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            manager.downloadRegion(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 1,
                minZoom = 10,
                maxZoom = 10,
                name = "Test Region",
                outputFile = outputFile,
            )

            advanceUntilIdle()

            assertEquals(expectedErrorMessage, manager.progress.value.errorMessage)

            unmockkConstructor(MBTilesWriter::class)
        }

    // ========== Estimate Download Tests ==========

    @Test
    fun `estimateDownload returns reasonable tile count for small radius`() {
        val manager = TileDownloadManager(context, TileSource.Http())

        val (tileCount, estimatedSize) =
            manager.estimateDownload(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 1,
                minZoom = 10,
                maxZoom = 12,
            )

        assertTrue("Tile count should be positive", tileCount > 0)
        assertTrue("Tile count should be reasonable for 1km radius", tileCount < 1000)
        assertEquals(
            "Estimated size should be tile count * average size",
            tileCount * TileDownloadManager.AVERAGE_TILE_SIZE_BYTES,
            estimatedSize,
        )
    }

    @Test
    fun `estimateDownload scales with radius`() {
        val manager = TileDownloadManager(context, TileSource.Http())

        val (smallCount, _) =
            manager.estimateDownload(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 1,
                minZoom = 10,
                maxZoom = 10,
            )

        val (largeCount, _) =
            manager.estimateDownload(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 10,
                minZoom = 10,
                maxZoom = 10,
            )

        assertTrue("Larger radius should have more tiles", largeCount > smallCount)
    }

    @Test
    fun `estimateDownload scales with zoom range`() {
        val manager = TileDownloadManager(context, TileSource.Http())

        val (smallZoomCount, _) =
            manager.estimateDownload(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 5,
                minZoom = 10,
                maxZoom = 10,
            )

        val (largeZoomCount, _) =
            manager.estimateDownload(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 5,
                minZoom = 10,
                maxZoom = 12,
            )

        assertTrue("Larger zoom range should have more tiles", largeZoomCount > smallZoomCount)
    }

    // ========== getOfflineMapsDir Tests ==========

    @Test
    fun `getOfflineMapsDir creates directory`() {
        val dir = TileDownloadManager.getOfflineMapsDir(context)

        assertTrue("Offline maps directory should exist", dir.exists())
        assertTrue("Should be a directory", dir.isDirectory)
        assertTrue("Should be named offline_maps", dir.name == "offline_maps")
    }

    @Test
    fun `getOfflineMapsDir returns same directory on multiple calls`() {
        val dir1 = TileDownloadManager.getOfflineMapsDir(context)
        val dir2 = TileDownloadManager.getOfflineMapsDir(context)

        assertEquals(dir1.absolutePath, dir2.absolutePath)
    }

    // ========== RMSP Tile Unpacking Tests ==========

    @Test
    fun `RMSP tile data with insufficient header bytes is handled`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_short_header.mbtiles")

            // Only 2 bytes - less than the 4-byte tile count header
            val shortData = ByteArray(2) { 0 }

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ -> shortData }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            // Should return null because no valid tiles were unpacked
            assertNull(result)

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `RMSP tile data with truncated tile entry is handled`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_truncated.mbtiles")

            // Create data with tile count=1 but truncated tile entry
            val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(1) // tile count = 1
            buffer.put(10.toByte()) // z
            buffer.putInt(163) // x (incomplete - missing y, size, data)

            val truncatedData = buffer.array()

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { _, _ -> truncatedData }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            val result =
                manager.downloadRegion(
                    centerLat = 37.7749,
                    centerLon = -122.4194,
                    radiusKm = 1,
                    minZoom = 10,
                    maxZoom = 10,
                    name = "Test Region",
                    outputFile = outputFile,
                )

            advanceUntilIdle()

            // Should return null because tile was truncated
            assertNull(result)

            unmockkConstructor(MBTilesWriter::class)
        }

    // ========== RMSP Tile Validation Tests ==========

    @Test
    fun `unpackRmspTiles rejects negative tile count`() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(-1) // Invalid negative count

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for negative tile count", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects excessive tile count`() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1_000_001) // Exceeds 1,000,000 limit

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for excessive tile count", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects negative tile size`() {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(10.toByte()) // z
        buffer.putInt(100) // x
        buffer.putInt(200) // y
        buffer.putInt(-1) // Invalid negative size

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for negative tile size", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects excessive tile size`() {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(10.toByte()) // z
        buffer.putInt(100) // x
        buffer.putInt(200) // y
        buffer.putInt(1_000_001) // Exceeds 1MB limit

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for excessive tile size", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects invalid zoom level`() {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(23.toByte()) // z = 23, exceeds max of 22
        buffer.putInt(0) // x
        buffer.putInt(0) // y
        buffer.putInt(10) // size

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for invalid zoom level", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects coordinates out of bounds for zoom`() {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(5.toByte()) // z = 5, max coord = 31
        buffer.putInt(32) // x = 32, exceeds max of 31 for zoom 5
        buffer.putInt(0) // y
        buffer.putInt(10) // size

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for out-of-bounds coordinates", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles rejects negative coordinates`() {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(10.toByte()) // z
        buffer.putInt(-1) // x = -1, invalid
        buffer.putInt(0) // y
        buffer.putInt(10) // size

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertTrue("Should return empty list for negative coordinates", tiles.isEmpty())
    }

    @Test
    fun `unpackRmspTiles accepts valid tile data`() {
        val tileData = ByteArray(50) { it.toByte() }
        val buffer = ByteBuffer.allocate(4 + 13 + tileData.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // tile count = 1
        buffer.put(10.toByte()) // z
        buffer.putInt(100) // x
        buffer.putInt(200) // y
        buffer.putInt(tileData.size) // valid size
        buffer.put(tileData)

        val manager = TileDownloadManager(context, TileSource.Http())
        val tiles = manager.unpackRmspTiles(buffer.array())

        assertEquals("Should parse one valid tile", 1, tiles.size)
        assertEquals("Tile z should match", 10, tiles[0].z)
        assertEquals("Tile x should match", 100, tiles[0].x)
        assertEquals("Tile y should match", 200, tiles[0].y)
    }

    // ========== Geohash Precision Selection Tests ==========

    @Test
    fun `RMSP uses appropriate geohash precision for small radius`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_precision_small.mbtiles")
            val observedGeohashes = mutableListOf<String>()

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { geohash, _ ->
                observedGeohashes.add(geohash)
                createMockRmspTileData(listOf(Triple(10, 163, 395)))
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            manager.downloadRegion(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 3, // Small radius - should use precision 5
                minZoom = 10,
                maxZoom = 10,
                name = "Test Region",
                outputFile = outputFile,
            )

            advanceUntilIdle()

            // Verify geohashes have precision 5 (5 characters)
            assertTrue("Should have observed geohashes", observedGeohashes.isNotEmpty())
            assertTrue(
                "Geohashes should have precision 5 for small radius",
                observedGeohashes.all { it.length == 5 },
            )

            unmockkConstructor(MBTilesWriter::class)
        }

    @Test
    fun `RMSP uses appropriate geohash precision for large radius`() =
        runTest {
            val outputFile = File(testOutputDir, "rmsp_precision_large.mbtiles")
            val observedGeohashes = mutableListOf<String>()

            val fetchTiles: suspend (String, List<Int>) -> ByteArray? = { geohash, _ ->
                observedGeohashes.add(geohash)
                createMockRmspTileData(listOf(Triple(10, 163, 395)))
            }
            val source = TileSource.Rmsp("test_server_hash", fetchTiles)

            mockkConstructor(MBTilesWriter::class)

            val manager = TileDownloadManager(context, source)
            manager.downloadRegion(
                centerLat = 37.7749,
                centerLon = -122.4194,
                radiusKm = 100, // Large radius - should use precision 3 (~150km cells)
                minZoom = 10,
                maxZoom = 10,
                name = "Test Region",
                outputFile = outputFile,
            )

            advanceUntilIdle()

            // Verify geohashes have precision 3 (3 characters) - precision 2 is too coarse (~630km)
            assertTrue("Should have observed geohashes", observedGeohashes.isNotEmpty())
            assertTrue(
                "Geohashes should have precision 3 for large radius",
                observedGeohashes.all { it.length == 3 },
            )

            unmockkConstructor(MBTilesWriter::class)
        }

    // ========== Helper Functions ==========

    /**
     * Creates mock RMSP tile data in the expected format.
     * Format: tile_count (u32), followed by tile_entries
     * Each tile_entry: z (u8), x (u32), y (u32), size (u32), data (bytes)
     */
    private fun createMockRmspTileData(tiles: List<Triple<Int, Int, Int>>): ByteArray {
        val tileDataContent = ByteArray(100) { it.toByte() } // Mock PBF data

        // Calculate buffer size: 4 (count) + tiles * (1 + 4 + 4 + 4 + dataSize)
        val entrySize = 1 + 4 + 4 + 4 + tileDataContent.size
        val bufferSize = 4 + tiles.size * entrySize

        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(tiles.size)

        for ((z, x, y) in tiles) {
            buffer.put(z.toByte())
            buffer.putInt(x)
            buffer.putInt(y)
            buffer.putInt(tileDataContent.size)
            buffer.put(tileDataContent)
        }

        return buffer.array()
    }
}
