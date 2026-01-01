package com.lxmf.messenger.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

        val bounds3 = TileDownloadManager.decodeGeohashBounds(
            TileDownloadManager.encodeGeohash(lat, lon, 3),
        )
        val bounds5 = TileDownloadManager.decodeGeohashBounds(
            TileDownloadManager.encodeGeohash(lat, lon, 5),
        )
        val bounds7 = TileDownloadManager.decodeGeohashBounds(
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

    // ========== geohashesForBounds() Tests ==========

    @Test
    fun `geohashesForBounds returns correct number of geohashes for small area`() {
        val bounds = MBTilesWriter.Bounds(
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
        val bounds = MBTilesWriter.Bounds(
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
        val smallBounds = MBTilesWriter.Bounds(
            west = -122.5,
            south = 37.7,
            east = -122.4,
            north = 37.8,
        )

        val largeBounds = MBTilesWriter.Bounds(
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
        val bounds = MBTilesWriter.Bounds(
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
        val params = RegionParams(
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
        val progress = TileDownloadManager.DownloadProgress(
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
        val progress = TileDownloadManager.DownloadProgress(
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
