package com.lxmf.messenger.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
