package com.lxmf.messenger.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the marker declutter algorithm.
 *
 * Uses an identity ScreenToLatLng converter (screen coords = lat/lng) to test
 * the pure algorithmic logic without MapLibre dependency.
 */
class MarkerDeclutterTest {
    // Identity converter: screen coords map directly to lat/lng
    // This lets us reason about offsets in pixel space directly
    private val identityConverter = ScreenToLatLng { x, y -> Pair(y.toDouble(), x.toDouble()) }

    // ========== Empty / Single marker ==========

    @Test
    fun `empty list returns empty`() {
        val result = calculateDeclutteredPositions(emptyList(), identityConverter)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single marker is not offset`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 200f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(1, result.size)
        assertFalse(result[0].isOffset)
        assertEquals(10.0, result[0].displayLat, 0.001)
        assertEquals(20.0, result[0].displayLng, 0.001)
    }

    // ========== Isolated markers (no overlap) ==========

    @Test
    fun `two distant markers are not offset`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 0f, 0f),
            ScreenMarker("b", 11.0, 21.0, 200f, 200f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(2, result.size)
        assertFalse(result[0].isOffset)
        assertFalse(result[1].isOffset)
    }

    @Test
    fun `markers just outside threshold are not grouped`() {
        // Default threshold is 60px, place markers 61px apart
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 0f, 0f),
            ScreenMarker("b", 11.0, 21.0, 61f, 0f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(2, result.size)
        assertFalse(result[0].isOffset)
        assertFalse(result[1].isOffset)
    }

    // ========== Two overlapping markers ==========

    @Test
    fun `two overlapping markers are both offset`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 110f, 105f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(2, result.size)
        assertTrue(result[0].isOffset)
        assertTrue(result[1].isOffset)
    }

    @Test
    fun `offset markers preserve original positions`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 110f, 105f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(10.0, result[0].originalLat, 0.001)
        assertEquals(20.0, result[0].originalLng, 0.001)
        assertEquals(10.001, result[1].originalLat, 0.001)
        assertEquals(20.001, result[1].originalLng, 0.001)
    }

    @Test
    fun `offset markers have display positions different from original`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 110f, 105f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        // Display positions should differ from original GPS positions
        val a = result.find { it.hash == "a" }!!
        val b = result.find { it.hash == "b" }!!
        assertTrue(a.displayLat != a.originalLat || a.displayLng != a.originalLng)
        assertTrue(b.displayLat != b.originalLat || b.displayLng != b.originalLng)
    }

    @Test
    fun `two offset markers are equidistant from centroid`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 100f, 110f),
        )
        // Use converter that preserves screen distances (identity mapping)
        val pixelConverter = ScreenToLatLng { x, y -> Pair(y.toDouble(), x.toDouble()) }
        val result = calculateDeclutteredPositions(markers, pixelConverter)

        val cx = 100.0 // centroid X
        val cy = 105.0 // centroid Y

        val distA = kotlin.math.sqrt(
            (result[0].displayLng - cx) * (result[0].displayLng - cx) +
                (result[0].displayLat - cy) * (result[0].displayLat - cy),
        )
        val distB = kotlin.math.sqrt(
            (result[1].displayLng - cx) * (result[1].displayLng - cx) +
                (result[1].displayLat - cy) * (result[1].displayLat - cy),
        )
        assertEquals(distA, distB, 0.1)
    }

    // ========== Multiple groups ==========

    @Test
    fun `two separate clusters are handled independently`() {
        val markers = listOf(
            // Cluster 1 (near 0,0)
            ScreenMarker("a1", 10.0, 20.0, 0f, 0f),
            ScreenMarker("a2", 10.001, 20.001, 10f, 5f),
            // Cluster 2 (near 500,500)
            ScreenMarker("b1", 50.0, 60.0, 500f, 500f),
            ScreenMarker("b2", 50.001, 60.001, 510f, 505f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(4, result.size)
        // All should be offset (both clusters have 2 overlapping markers)
        assertTrue(result.all { it.isOffset })
    }

    @Test
    fun `mixed isolated and clustered markers`() {
        val markers = listOf(
            // Cluster
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 110f, 105f),
            // Isolated
            ScreenMarker("c", 50.0, 60.0, 500f, 500f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(3, result.size)
        val a = result.find { it.hash == "a" }!!
        val b = result.find { it.hash == "b" }!!
        val c = result.find { it.hash == "c" }!!
        assertTrue(a.isOffset)
        assertTrue(b.isOffset)
        assertFalse(c.isOffset)
    }

    // ========== Large group — radius scaling ==========

    @Test
    fun `large group uses scaled radius`() {
        // 8 markers at the same pixel position
        val markers = (0 until 8).map { i ->
            ScreenMarker("m$i", 10.0 + i * 0.0001, 20.0, 100f + i, 100f + i)
        }
        val pixelConverter = ScreenToLatLng { x, y -> Pair(y.toDouble(), x.toDouble()) }
        val result = calculateDeclutteredPositions(markers, pixelConverter)

        assertEquals(8, result.size)
        assertTrue(result.all { it.isOffset })

        // Check that all markers are offset to different positions
        val displayPositions = result.map { Pair(it.displayLat, it.displayLng) }.toSet()
        assertEquals(8, displayPositions.size)
    }

    @Test
    fun `radius scales with group size`() {
        // For a group of 8, minRadius = 8 * 75 / (2*PI) ≈ 95.5, which is > DECLUTTER_OFFSET_PX (70)
        // For a group of 2, minRadius = 2 * 75 / (2*PI) ≈ 23.9, so DECLUTTER_OFFSET_PX (70) is used
        val smallGroup = listOf(
            ScreenMarker("a", 10.0, 20.0, 100f, 100f),
            ScreenMarker("b", 10.001, 20.001, 100f, 110f),
        )
        val largeGroup = (0 until 8).map { i ->
            ScreenMarker("m$i", 10.0, 20.0, 100f, 100f + i)
        }

        val pixelConverter = ScreenToLatLng { x, y -> Pair(y.toDouble(), x.toDouble()) }
        val smallResult = calculateDeclutteredPositions(smallGroup, pixelConverter)
        val largeResult = calculateDeclutteredPositions(largeGroup, pixelConverter)

        // Compute max offset distance from centroid for each group
        val smallCy = 105.0
        val smallMaxDist = smallResult.maxOf { dm ->
            kotlin.math.sqrt((dm.displayLat - smallCy) * (dm.displayLat - smallCy) +
                (dm.displayLng - 100.0) * (dm.displayLng - 100.0))
        }

        val largeCy = largeGroup.map { it.screenY.toDouble() }.average()
        val largeCx = 100.0
        val largeMaxDist = largeResult.maxOf { dm ->
            kotlin.math.sqrt((dm.displayLat - largeCy) * (dm.displayLat - largeCy) +
                (dm.displayLng - largeCx) * (dm.displayLng - largeCx))
        }

        // Large group should have bigger radius
        assertTrue("Large group radius ($largeMaxDist) should be > small group radius ($smallMaxDist)",
            largeMaxDist > smallMaxDist)
    }

    // ========== Custom thresholds ==========

    @Test
    fun `custom overlap threshold groups markers differently`() {
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 0f, 0f),
            ScreenMarker("b", 11.0, 21.0, 50f, 0f),
        )
        // With default threshold (60px), they overlap
        val resultOverlap = calculateDeclutteredPositions(markers, identityConverter, overlapThresholdPx = 60f)
        assertTrue(resultOverlap.all { it.isOffset })

        // With smaller threshold (30px), they don't overlap
        val resultNoOverlap = calculateDeclutteredPositions(markers, identityConverter, overlapThresholdPx = 30f)
        assertFalse(resultNoOverlap.any { it.isOffset })
    }

    // ========== Hash preservation ==========

    @Test
    fun `all marker hashes are preserved in output`() {
        val markers = listOf(
            ScreenMarker("alpha", 10.0, 20.0, 100f, 100f),
            ScreenMarker("beta", 10.001, 20.001, 110f, 105f),
            ScreenMarker("gamma", 50.0, 60.0, 500f, 500f),
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        val hashes = result.map { it.hash }.toSet()
        assertEquals(setOf("alpha", "beta", "gamma"), hashes)
    }

    // ========== Transitive grouping ==========

    @Test
    fun `transitive overlap groups markers in chain`() {
        // A is near B, B is near C, but A is far from C
        // All three should end up in the same group via Union-Find
        val markers = listOf(
            ScreenMarker("a", 10.0, 20.0, 0f, 0f),
            ScreenMarker("b", 10.001, 20.001, 50f, 0f), // 50px from A (< 60)
            ScreenMarker("c", 10.002, 20.002, 100f, 0f), // 50px from B, 100px from A
        )
        val result = calculateDeclutteredPositions(markers, identityConverter)
        assertEquals(3, result.size)
        // All should be in one group and offset
        assertTrue(result.all { it.isOffset })
    }
}
