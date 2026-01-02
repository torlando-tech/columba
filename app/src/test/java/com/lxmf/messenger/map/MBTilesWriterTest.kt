package com.lxmf.messenger.map

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.pow

/**
 * Unit tests for MBTilesWriter.
 *
 * Tests the static utility functions and data classes.
 * Note: Database operations require Android context and are tested separately.
 */
class MBTilesWriterTest {
    companion object {
        private const val EPSILON = 0.0001 // Tolerance for floating point comparisons
    }

    // ========== flipY() Tests ==========

    @Test
    fun `flipY converts XYZ to TMS at zoom 0`() {
        // At zoom 0, there's only one tile (0,0)
        // TMS y = 2^0 - 1 - 0 = 0
        assertEquals(0, MBTilesWriter.flipY(0, 0))
    }

    @Test
    fun `flipY converts XYZ to TMS at zoom 1`() {
        // At zoom 1, tiles are (0,0), (1,0), (0,1), (1,1)
        // TMS y = 2^1 - 1 - xyz_y = 1 - xyz_y
        assertEquals(1, MBTilesWriter.flipY(1, 0)) // Top row becomes bottom
        assertEquals(0, MBTilesWriter.flipY(1, 1)) // Bottom row becomes top
    }

    @Test
    fun `flipY converts XYZ to TMS at zoom 10`() {
        // At zoom 10, 2^10 = 1024 tiles in each direction
        // TMS y = 1023 - xyz_y
        assertEquals(1023, MBTilesWriter.flipY(10, 0))
        assertEquals(0, MBTilesWriter.flipY(10, 1023))
        assertEquals(511, MBTilesWriter.flipY(10, 512))
    }

    @Test
    fun `flipY is its own inverse`() {
        // flipY(flipY(y)) should equal y
        val zoom = 12
        val xyzY = 1500

        val tmsY = MBTilesWriter.flipY(zoom, xyzY)
        val backToXyz = MBTilesWriter.flipY(zoom, tmsY)

        assertEquals(xyzY, backToXyz)
    }

    @Test
    fun `flipY handles high zoom levels`() {
        // At zoom 18, 2^18 = 262144 tiles
        val zoom = 18
        val xyzY = 100000

        val tmsY = MBTilesWriter.flipY(zoom, xyzY)

        assertTrue(tmsY >= 0)
        assertTrue(tmsY < 262144)
        assertEquals(262143 - xyzY, tmsY)
    }

    // ========== tmsToXyzY() Tests ==========

    @Test
    fun `tmsToXyzY converts TMS to XYZ at zoom 0`() {
        assertEquals(0, MBTilesWriter.tmsToXyzY(0, 0))
    }

    @Test
    fun `tmsToXyzY converts TMS to XYZ at zoom 1`() {
        assertEquals(0, MBTilesWriter.tmsToXyzY(1, 1)) // TMS 1 -> XYZ 0
        assertEquals(1, MBTilesWriter.tmsToXyzY(1, 0)) // TMS 0 -> XYZ 1
    }

    @Test
    fun `tmsToXyzY is inverse of flipY`() {
        val zoom = 12
        val xyzY = 2048

        val tmsY = MBTilesWriter.flipY(zoom, xyzY)
        val backToXyz = MBTilesWriter.tmsToXyzY(zoom, tmsY)

        assertEquals(xyzY, backToXyz)
    }

    @Test
    fun `tmsToXyzY and flipY are identical functions`() {
        // The math is the same in both directions
        val zoom = 10
        for (y in listOf(0, 100, 500, 1000, 1023)) {
            assertEquals(MBTilesWriter.flipY(zoom, y), MBTilesWriter.tmsToXyzY(zoom, y))
        }
    }

    // ========== boundsFromCenter() Tests ==========

    @Test
    fun `boundsFromCenter calculates correct bounds for San Francisco`() {
        val bounds = MBTilesWriter.boundsFromCenter(37.7749, -122.4194, 10)

        // 10 km radius should give ~20 km total span
        val latSpan = bounds.north - bounds.south
        val lonSpan = bounds.east - bounds.west

        // ~0.09 degrees per km at this latitude
        assertTrue(latSpan > 0.15) // At least 15 km
        assertTrue(latSpan < 0.25) // At most 25 km
        assertTrue(lonSpan > 0.15)
        assertTrue(lonSpan < 0.30) // Longitude spans more due to latitude
    }

    @Test
    fun `boundsFromCenter contains center point`() {
        val lat = 37.7749
        val lon = -122.4194
        val bounds = MBTilesWriter.boundsFromCenter(lat, lon, 5)

        assertTrue(bounds.south < lat)
        assertTrue(bounds.north > lat)
        assertTrue(bounds.west < lon)
        assertTrue(bounds.east > lon)
    }

    @Test
    fun `boundsFromCenter handles equator correctly`() {
        val bounds = MBTilesWriter.boundsFromCenter(0.0, 0.0, 100)

        // At equator, lat and lon spans should be roughly equal
        val latSpan = bounds.north - bounds.south
        val lonSpan = bounds.east - bounds.west

        // Should be within 10% of each other
        assertTrue(abs(latSpan - lonSpan) < latSpan * 0.1)
    }

    @Test
    fun `boundsFromCenter handles high latitudes correctly`() {
        // At 60 degrees latitude, longitude spans more
        val bounds = MBTilesWriter.boundsFromCenter(60.0, 0.0, 100)

        val latSpan = bounds.north - bounds.south
        val lonSpan = bounds.east - bounds.west

        // Longitude span should be larger at higher latitudes
        assertTrue(lonSpan > latSpan)
    }

    @Test
    fun `boundsFromCenter scales with radius`() {
        val smallBounds = MBTilesWriter.boundsFromCenter(37.7749, -122.4194, 5)
        val largeBounds = MBTilesWriter.boundsFromCenter(37.7749, -122.4194, 50)

        val smallLatSpan = smallBounds.north - smallBounds.south
        val largeLatSpan = largeBounds.north - largeBounds.south

        // 10x radius should give ~10x span
        assertTrue(largeLatSpan / smallLatSpan > 8)
        assertTrue(largeLatSpan / smallLatSpan < 12)
    }

    @Test
    fun `boundsFromCenter handles southern hemisphere`() {
        // Sydney
        val bounds = MBTilesWriter.boundsFromCenter(-33.8688, 151.2093, 10)

        assertTrue(bounds.south < -33.8688)
        assertTrue(bounds.north > -33.8688)
        assertTrue(bounds.south < bounds.north)
    }

    // ========== Bounds Data Class Tests ==========

    @Test
    fun `Bounds data class holds correct values`() {
        val bounds = MBTilesWriter.Bounds(
            west = -122.5,
            south = 37.5,
            east = -122.0,
            north = 38.0,
        )

        assertEquals(-122.5, bounds.west, EPSILON)
        assertEquals(37.5, bounds.south, EPSILON)
        assertEquals(-122.0, bounds.east, EPSILON)
        assertEquals(38.0, bounds.north, EPSILON)
    }

    @Test
    fun `Bounds toString produces correct format`() {
        val bounds = MBTilesWriter.Bounds(
            west = -122.5,
            south = 37.5,
            east = -122.0,
            north = 38.0,
        )

        val str = bounds.toString()
        assertEquals("-122.5,37.5,-122.0,38.0", str)
    }

    @Test
    fun `Bounds equals works correctly`() {
        val bounds1 = MBTilesWriter.Bounds(-122.5, 37.5, -122.0, 38.0)
        val bounds2 = MBTilesWriter.Bounds(-122.5, 37.5, -122.0, 38.0)
        val bounds3 = MBTilesWriter.Bounds(-122.5, 37.5, -122.0, 38.5)

        assertEquals(bounds1, bounds2)
        assertNotEquals(bounds1, bounds3)
    }

    // ========== Center Data Class Tests ==========

    @Test
    fun `Center data class holds correct values`() {
        val center = MBTilesWriter.Center(
            longitude = -122.4194,
            latitude = 37.7749,
            zoom = 10,
        )

        assertEquals(-122.4194, center.longitude, EPSILON)
        assertEquals(37.7749, center.latitude, EPSILON)
        assertEquals(10, center.zoom)
    }

    @Test
    fun `Center toString produces correct format`() {
        val center = MBTilesWriter.Center(
            longitude = -122.4194,
            latitude = 37.7749,
            zoom = 10,
        )

        val str = center.toString()
        assertEquals("-122.4194,37.7749,10", str)
    }

    @Test
    fun `Center equals works correctly`() {
        val center1 = MBTilesWriter.Center(-122.4194, 37.7749, 10)
        val center2 = MBTilesWriter.Center(-122.4194, 37.7749, 10)
        val center3 = MBTilesWriter.Center(-122.4194, 37.7749, 12)

        assertEquals(center1, center2)
        assertNotEquals(center1, center3)
    }

    // ========== Coordinate System Tests ==========

    @Test
    fun `TMS and XYZ have consistent relationship across zoom levels`() {
        for (zoom in 0..16) {
            val maxTile = (1 shl zoom) - 1 // 2^zoom - 1

            // Top XYZ row (y=0) should map to bottom TMS row
            assertEquals(maxTile, MBTilesWriter.flipY(zoom, 0))

            // Bottom XYZ row should map to top TMS row
            assertEquals(0, MBTilesWriter.flipY(zoom, maxTile))
        }
    }

    @Test
    fun `bounds calculation is symmetric around center`() {
        val lat = 45.0
        val lon = -100.0
        val radius = 20

        val bounds = MBTilesWriter.boundsFromCenter(lat, lon, radius)

        val southDist = lat - bounds.south
        val northDist = bounds.north - lat

        // Should be roughly symmetric
        assertTrue(abs(southDist - northDist) < 0.01)
    }
}

/**
 * Robolectric-based integration tests for MBTilesWriter SQLite operations.
 *
 * These tests use real SQLite through Robolectric to verify:
 * - Full lifecycle: create, write, close
 * - Tile data persistence
 * - Metadata writing
 * - Y coordinate flipping in database
 * - Transaction handling
 * - Resource cleanup
 * - Error handling
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MBTilesWriterRobolectricTest {

    private lateinit var tempDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        tempDir = createTempDirectory("mbtiles_test").toFile()
        testFile = File(tempDir, "test.mbtiles")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== Full Lifecycle Tests ==========

    @Test
    fun `open creates database file and schema`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            description = "Test description",
            minZoom = 0,
            maxZoom = 14,
        )

        writer.open()

        assertTrue("Database file should exist", testFile.exists())
        assertTrue("Database file should have content", testFile.length() > 0)

        writer.close()
    }

    @Test
    fun `open creates tiles table with correct schema`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='tiles'",
            null,
        )
        assertTrue("tiles table should exist", cursor.moveToFirst())
        cursor.close()
        db.close()
        writer.close()
    }

    @Test
    fun `open creates metadata table with correct schema`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='metadata'",
            null,
        )
        assertTrue("metadata table should exist", cursor.moveToFirst())
        cursor.close()
        db.close()
        writer.close()
    }

    @Test
    fun `open creates tiles index for performance`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='tiles_index'",
            null,
        )
        assertTrue("tiles_index should exist", cursor.moveToFirst())
        cursor.close()
        db.close()
        writer.close()
    }

    @Test
    fun `full lifecycle - create write close reopen read`() {
        val tileData = byteArrayOf(1, 2, 3, 4, 5)

        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.writeTile(5, 10, 15, tileData)
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 5 AND tile_column = 10",
            null,
        )
        assertTrue("Should find the written tile", cursor.moveToFirst())
        val readData = cursor.getBlob(0)
        assertArrayEquals("Tile data should match", tileData, readData)
        cursor.close()
        db.close()
    }

    // ========== writeTile() Tests ==========

    @Test
    fun `writeTile stores tile data correctly`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val tileData = byteArrayOf(10, 20, 30, 40, 50, 60)
        writer.writeTile(8, 100, 200, tileData)
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 8 AND tile_column = 100",
            null,
        )
        assertTrue(cursor.moveToFirst())
        assertArrayEquals(tileData, cursor.getBlob(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `writeTile increments tile count`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        assertEquals(0, writer.getTileCount())

        writer.writeTile(0, 0, 0, byteArrayOf(1))
        assertEquals(1, writer.getTileCount())

        writer.writeTile(1, 0, 0, byteArrayOf(2))
        assertEquals(2, writer.getTileCount())

        writer.writeTile(1, 1, 1, byteArrayOf(3))
        assertEquals(3, writer.getTileCount())

        writer.close()
    }

    @Test
    fun `writeTile accumulates total bytes`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        assertEquals(0L, writer.getTotalBytes())

        writer.writeTile(0, 0, 0, byteArrayOf(1, 2, 3))
        assertEquals(3L, writer.getTotalBytes())

        writer.writeTile(1, 0, 0, byteArrayOf(4, 5, 6, 7, 8))
        assertEquals(8L, writer.getTotalBytes())

        writer.close()
    }

    @Test
    fun `writeTile handles empty tile data`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val emptyData = byteArrayOf()
        writer.writeTile(0, 0, 0, emptyData)

        assertEquals(1, writer.getTileCount())
        assertEquals(0L, writer.getTotalBytes())

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT tile_data FROM tiles", null)
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getBlob(0).size)
        cursor.close()
        db.close()
    }

    @Test
    fun `writeTile handles large tile data`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val largeData = ByteArray(100_000) { it.toByte() }
        writer.writeTile(10, 500, 500, largeData)

        assertEquals(1, writer.getTileCount())
        assertEquals(100_000L, writer.getTotalBytes())

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT tile_data FROM tiles", null)
        assertTrue(cursor.moveToFirst())
        assertArrayEquals(largeData, cursor.getBlob(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `writeTile replaces existing tile at same coordinates`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val firstData = byteArrayOf(1, 1, 1)
        val secondData = byteArrayOf(2, 2, 2, 2, 2)

        writer.writeTile(5, 10, 20, firstData)
        writer.writeTile(5, 10, 20, secondData)

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val countCursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        countCursor.moveToFirst()
        assertEquals(1, countCursor.getInt(0))
        countCursor.close()

        val dataCursor = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 5 AND tile_column = 10",
            null,
        )
        dataCursor.moveToFirst()
        assertArrayEquals(secondData, dataCursor.getBlob(0))
        dataCursor.close()
        db.close()
    }

    // ========== Y Coordinate Flipping Tests ==========

    @Test
    fun `writeTile flips Y coordinate from XYZ to TMS at zoom 0`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.writeTile(0, 0, 0, byteArrayOf(1))
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT tile_row FROM tiles WHERE zoom_level = 0",
            null,
        )
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `writeTile flips Y coordinate from XYZ to TMS at zoom 1`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.writeTile(1, 0, 0, byteArrayOf(1))
        writer.writeTile(1, 0, 1, byteArrayOf(2))
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        val cursor1 = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 1 AND tile_row = 1",
            null,
        )
        assertTrue("XYZ y=0 should be at TMS row 1", cursor1.moveToFirst())
        assertArrayEquals(byteArrayOf(1), cursor1.getBlob(0))
        cursor1.close()

        val cursor2 = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 1 AND tile_row = 0",
            null,
        )
        assertTrue("XYZ y=1 should be at TMS row 0", cursor2.moveToFirst())
        assertArrayEquals(byteArrayOf(2), cursor2.getBlob(0))
        cursor2.close()

        db.close()
    }

    @Test
    fun `writeTile flips Y coordinate correctly at higher zoom levels`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.writeTile(10, 0, 0, byteArrayOf(1))
        writer.writeTile(10, 0, 1023, byteArrayOf(2))
        writer.writeTile(10, 0, 512, byteArrayOf(3))
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        val cursor1 = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 10 AND tile_row = 1023",
            null,
        )
        assertTrue(cursor1.moveToFirst())
        assertArrayEquals(byteArrayOf(1), cursor1.getBlob(0))
        cursor1.close()

        val cursor2 = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 10 AND tile_row = 0",
            null,
        )
        assertTrue(cursor2.moveToFirst())
        assertArrayEquals(byteArrayOf(2), cursor2.getBlob(0))
        cursor2.close()

        val cursor3 = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = 10 AND tile_row = 511",
            null,
        )
        assertTrue(cursor3.moveToFirst())
        assertArrayEquals(byteArrayOf(3), cursor3.getBlob(0))
        cursor3.close()

        db.close()
    }

    @Test
    fun `Y coordinate flipping is consistent across all stored tiles`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val testCases = listOf(
            Triple(5, 10, 15),
            Triple(8, 100, 200),
            Triple(12, 2000, 1500),
        )

        for ((z, x, y) in testCases) {
            writer.writeTile(z, x, y, byteArrayOf(z.toByte()))
        }
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        for ((z, x, y) in testCases) {
            val expectedTmsY = (2.0.pow(z) - 1 - y).toInt()
            val cursor = db.rawQuery(
                "SELECT tile_row FROM tiles WHERE zoom_level = ? AND tile_column = ?",
                arrayOf(z.toString(), x.toString()),
            )
            assertTrue("Should find tile at zoom $z", cursor.moveToFirst())
            assertEquals(
                "TMS Y should be $expectedTmsY for XYZ y=$y at zoom $z",
                expectedTmsY,
                cursor.getInt(0),
            )
            cursor.close()
        }

        db.close()
    }

    // ========== Metadata Tests ==========

    @Test
    fun `metadata contains required MBTiles fields`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            minZoom = 2,
            maxZoom = 16,
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        assertMetadataValue(db, "name", "Test Map")
        assertMetadataValue(db, "format", "pbf")
        assertMetadataValue(db, "type", "baselayer")
        assertMetadataValue(db, "minzoom", "2")
        assertMetadataValue(db, "maxzoom", "16")
        assertMetadataValue(db, "scheme", "tms")

        db.close()
    }

    @Test
    fun `metadata includes description when provided`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            description = "A test map description",
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "description", "A test map description")
        db.close()
    }

    @Test
    fun `metadata excludes description when empty`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            description = "",
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery(
            "SELECT value FROM metadata WHERE name = 'description'",
            null,
        )
        assertFalse("Description should not be in metadata", cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun `metadata includes bounds when provided`() {
        val bounds = MBTilesWriter.Bounds(
            west = -122.5,
            south = 37.5,
            east = -122.0,
            north = 38.0,
        )
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            bounds = bounds,
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "bounds", "-122.5,37.5,-122.0,38.0")
        db.close()
    }

    @Test
    fun `metadata includes center when provided`() {
        val center = MBTilesWriter.Center(
            longitude = -122.4,
            latitude = 37.8,
            zoom = 10,
        )
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            center = center,
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "center", "-122.4,37.8,10")
        db.close()
    }

    @Test
    fun `metadata includes version field`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "version", "1.0.0")
        db.close()
    }

    private fun assertMetadataValue(db: SQLiteDatabase, name: String, expectedValue: String) {
        val cursor = db.rawQuery(
            "SELECT value FROM metadata WHERE name = ?",
            arrayOf(name),
        )
        assertTrue("Metadata '$name' should exist", cursor.moveToFirst())
        assertEquals("Metadata '$name' value", expectedValue, cursor.getString(0))
        cursor.close()
    }

    // ========== Transaction Tests ==========

    @Test
    fun `beginTransaction and endTransaction work correctly`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.beginTransaction()
        writer.writeTile(0, 0, 0, byteArrayOf(1))
        writer.writeTile(1, 0, 0, byteArrayOf(2))
        writer.writeTile(1, 1, 0, byteArrayOf(3))
        writer.endTransaction(success = true)

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        cursor.moveToFirst()
        assertEquals(3, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `endTransaction with success false does not commit`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.writeTile(0, 0, 0, byteArrayOf(1))

        writer.beginTransaction()
        writer.writeTile(1, 0, 0, byteArrayOf(2))
        writer.writeTile(1, 1, 0, byteArrayOf(3))
        writer.endTransaction(success = false)

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `bulk insert with transaction is more efficient`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.beginTransaction()
        repeat(100) { i ->
            writer.writeTile(5, i, 0, byteArrayOf(i.toByte()))
        }
        writer.endTransaction(success = true)

        assertEquals(100, writer.getTileCount())

        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        cursor.moveToFirst()
        assertEquals(100, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    // ========== close() Tests ==========

    @Test
    fun `close releases database resources`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.writeTile(0, 0, 0, byteArrayOf(1))
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertNotNull(db)
        db.close()
    }

    @Test
    fun `close can be called multiple times safely`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.close()
        writer.close()
        writer.close()
    }

    @Test
    fun `writeTile after close is safe and does nothing`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.writeTile(0, 0, 0, byteArrayOf(1))
        writer.close()

        writer.writeTile(1, 0, 0, byteArrayOf(2))

        assertEquals(1, writer.getTileCount())

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    @Test
    fun `optimize compacts the database`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        repeat(10) { i ->
            writer.writeTile(5, i, 0, ByteArray(1000) { it.toByte() })
        }

        val sizeBeforeOptimize = testFile.length()
        writer.optimize()
        val sizeAfterOptimize = testFile.length()

        assertTrue(
            "Size after optimize should not be larger",
            sizeAfterOptimize <= sizeBeforeOptimize + 4096,
        )

        writer.close()
    }

    @Test
    fun `getFileSize returns actual file size`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        val initialSize = writer.getFileSize()
        assertTrue("Initial file should have content", initialSize > 0)

        repeat(5) { i ->
            writer.writeTile(5, i, 0, ByteArray(10000))
        }

        val finalSize = writer.getFileSize()
        assertTrue("File should grow after writes", finalSize > initialSize)

        writer.close()
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `open with nested directory creates parent dirs`() {
        val nestedFile = File(tempDir, "deeply/nested/path/test.mbtiles")
        val writer = MBTilesWriter(file = nestedFile, name = "Test Map")

        writer.open()

        assertTrue("Nested file should exist", nestedFile.exists())
        assertTrue("Parent directories should exist", nestedFile.parentFile?.exists() == true)

        writer.close()
    }

    @Test
    fun `open overwrites existing file`() {
        testFile.writeText("existing content")
        val originalSize = testFile.length()

        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()
        writer.close()

        assertTrue(testFile.exists())
        assertNotEquals(originalSize, testFile.length())

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertNotNull(db)
        db.close()
    }

    @Test
    fun `writer handles special characters in name and description`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test's \"Map\" with <special> & chars",
            description = "Description with unicode: cafe",
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        val nameCursor = db.rawQuery(
            "SELECT value FROM metadata WHERE name = 'name'",
            null,
        )
        assertTrue(nameCursor.moveToFirst())
        assertEquals("Test's \"Map\" with <special> & chars", nameCursor.getString(0))
        nameCursor.close()

        val descCursor = db.rawQuery(
            "SELECT value FROM metadata WHERE name = 'description'",
            null,
        )
        assertTrue(descCursor.moveToFirst())
        assertEquals("Description with unicode: cafe", descCursor.getString(0))
        descCursor.close()

        db.close()
    }

    @Test
    fun `writer handles boundary zoom levels`() {
        val writer = MBTilesWriter(
            file = testFile,
            name = "Test Map",
            minZoom = 0,
            maxZoom = 22,
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "minzoom", "0")
        assertMetadataValue(db, "maxzoom", "22")
        db.close()
    }

    @Test
    fun `writer handles negative coordinates in bounds`() {
        val bounds = MBTilesWriter.Bounds(
            west = -180.0,
            south = -85.0,
            east = 180.0,
            north = 85.0,
        )
        val writer = MBTilesWriter(
            file = testFile,
            name = "World Map",
            bounds = bounds,
        )
        writer.open()
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)
        assertMetadataValue(db, "bounds", "-180.0,-85.0,180.0,85.0")
        db.close()
    }

    // ========== Read-Only Access After Write Tests ==========

    @Test
    fun `database is readable after close`() {
        val writer = MBTilesWriter(file = testFile, name = "Test Map")
        writer.open()

        writer.beginTransaction()
        repeat(50) { i ->
            writer.writeTile(8, i % 10, i / 10, byteArrayOf(i.toByte()))
        }
        writer.endTransaction(success = true)
        writer.close()

        val db = SQLiteDatabase.openDatabase(testFile.path, null, SQLiteDatabase.OPEN_READONLY)

        val tileCursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
        tileCursor.moveToFirst()
        assertEquals(50, tileCursor.getInt(0))
        tileCursor.close()

        val metaCursor = db.rawQuery("SELECT COUNT(*) FROM metadata", null)
        metaCursor.moveToFirst()
        assertTrue("Should have metadata entries", metaCursor.getInt(0) >= 5)
        metaCursor.close()

        db.close()
    }
}
