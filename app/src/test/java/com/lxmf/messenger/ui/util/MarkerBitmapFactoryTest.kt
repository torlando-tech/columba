package com.lxmf.messenger.ui.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MarkerBitmapFactory.
 *
 * Tests all pure functions for creating map marker bitmaps:
 * - hashToColor: deterministic hash-to-color conversion
 * - createInitialMarker: circle with initial and display name
 * - createDashedCircle: dashed circle ring
 * - createFilledCircleWithDashedOutline: filled circle with dashed outline
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkerBitmapFactoryTest {

    companion object {
        private const val TEST_DENSITY = 2.0f // Standard xhdpi density
    }

    // ========== hashToColor Tests ==========

    @Test
    fun `hashToColor returns same color for same hash`() {
        val hash = "abcdef0123456789"
        val color1 = MarkerBitmapFactory.hashToColor(hash)
        val color2 = MarkerBitmapFactory.hashToColor(hash)

        assertEquals(color1, color2)
    }

    @Test
    fun `hashToColor returns different colors for different hashes`() {
        val color1 = MarkerBitmapFactory.hashToColor("hash1")
        val color2 = MarkerBitmapFactory.hashToColor("hash2")
        val color3 = MarkerBitmapFactory.hashToColor("completely_different_string")

        // Different hashes should produce different colors (with very high probability)
        assertNotEquals(color1, color2)
        assertNotEquals(color1, color3)
    }

    @Test
    fun `hashToColor handles empty string`() {
        val color = MarkerBitmapFactory.hashToColor("")

        // Should not crash and return a valid color
        assertNotEquals(0, color)
        assertTrue(Color.alpha(color) == 255) // Full opacity from HSVToColor
    }

    @Test
    fun `hashToColor handles special characters`() {
        val color = MarkerBitmapFactory.hashToColor("!@#\$%^&*()")

        assertNotEquals(0, color)
        assertTrue(Color.alpha(color) == 255)
    }

    @Test
    fun `hashToColor produces colors with correct saturation and brightness`() {
        // Test several hashes and verify the colors have expected HSV properties
        val testHashes = listOf("test1", "test2", "test3", "abc", "xyz")

        for (hash in testHashes) {
            val color = MarkerBitmapFactory.hashToColor(hash)
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)

            // Saturation should be 0.7 (with some float tolerance)
            assertEquals(0.7f, hsv[1], 0.01f)

            // Brightness should be 0.8
            assertEquals(0.8f, hsv[2], 0.01f)

            // Hue should be in valid range 0-360
            assertTrue(hsv[0] >= 0f && hsv[0] < 360f)
        }
    }

    @Test
    fun `hashToColor is deterministic across multiple calls`() {
        val hash = "destination_hash_123456789abcdef"
        val colors = (1..100).map { MarkerBitmapFactory.hashToColor(hash) }

        // All 100 calls should return the same color
        assertTrue(colors.all { it == colors[0] })
    }

    // ========== createInitialMarker Tests ==========

    @Test
    fun `createInitialMarker returns non-null bitmap`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'A',
            displayName = "Alice",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        assertNotNull(bitmap)
    }

    @Test
    fun `createInitialMarker creates bitmap with correct minimum dimensions`() {
        val sizeDp = 40f
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'A',
            displayName = "Al", // Short name
            backgroundColor = Color.BLUE,
            sizeDp = sizeDp,
            density = TEST_DENSITY,
        )

        val expectedMinWidth = (sizeDp * TEST_DENSITY).toInt()
        // Width should be at least as wide as the circle
        assertTrue(bitmap.width >= expectedMinWidth)

        // Height should include circle + padding + label
        assertTrue(bitmap.height > expectedMinWidth)
    }

    @Test
    fun `createInitialMarker expands width for long display names`() {
        val shortNameBitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'A',
            displayName = "Al",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        val longNameBitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'A',
            displayName = "Alexander the Great of Macedonia",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        // Long name should create a wider bitmap (or at least equal for very short names)
        assertTrue(
            "Long name bitmap should be at least as wide as short name bitmap",
            longNameBitmap.width >= shortNameBitmap.width,
        )

        // Height should be the same (both have same circle size + label height)
        assertEquals(shortNameBitmap.height, longNameBitmap.height)
    }

    @Test
    fun `createInitialMarker creates valid bitmap`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'T',
            displayName = "Test",
            backgroundColor = Color.RED,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        // Verify bitmap is created with valid dimensions
        // Note: Robolectric doesn't render actual pixels, so we verify structure only
        assertTrue("Bitmap should have positive width", bitmap.width > 0)
        assertTrue("Bitmap should have positive height", bitmap.height > 0)
        assertFalse("Bitmap should not be recycled", bitmap.isRecycled)
    }

    @Test
    fun `createInitialMarker uses correct size based on density`() {
        val sizeDp = 50f
        val lowDensity = 1.0f
        val highDensity = 3.0f

        val lowDensityBitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'X',
            displayName = "Test",
            backgroundColor = Color.GREEN,
            sizeDp = sizeDp,
            density = lowDensity,
        )

        val highDensityBitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'X',
            displayName = "Test",
            backgroundColor = Color.GREEN,
            sizeDp = sizeDp,
            density = highDensity,
        )

        // Higher density should produce larger bitmap
        assertTrue(highDensityBitmap.width > lowDensityBitmap.width)
        assertTrue(highDensityBitmap.height > lowDensityBitmap.height)
    }

    @Test
    fun `createInitialMarker handles uppercase initial`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'a', // lowercase
            displayName = "alice",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        // Should not crash and produce valid bitmap
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun `createInitialMarker handles non-letter initial`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = '?',
            displayName = "Unknown",
            backgroundColor = Color.GRAY,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
    }

    @Test
    fun `createInitialMarker handles empty display name`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'X',
            displayName = "",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        // Should not crash
        assertNotNull(bitmap)
    }

    @Test
    fun `createInitialMarker has ARGB_8888 config`() {
        val bitmap = MarkerBitmapFactory.createInitialMarker(
            initial = 'T',
            displayName = "Test",
            backgroundColor = Color.BLUE,
            sizeDp = 40f,
            density = TEST_DENSITY,
        )

        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    // ========== createDashedCircle Tests ==========

    @Test
    fun `createDashedCircle returns non-null bitmap`() {
        val bitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = 28f,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        assertNotNull(bitmap)
    }

    @Test
    fun `createDashedCircle creates square bitmap`() {
        val sizeDp = 28f
        val bitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = sizeDp,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        val expectedSize = (sizeDp * TEST_DENSITY).toInt()
        assertEquals(expectedSize, bitmap.width)
        assertEquals(expectedSize, bitmap.height)
    }

    @Test
    fun `createDashedCircle creates valid bitmap`() {
        val bitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = 40f,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        // Verify bitmap structure (Robolectric doesn't render actual pixels)
        assertTrue("Bitmap should have positive width", bitmap.width > 0)
        assertTrue("Bitmap should have positive height", bitmap.height > 0)
        assertFalse("Bitmap should not be recycled", bitmap.isRecycled)
    }

    @Test
    fun `createDashedCircle scales with density`() {
        val sizeDp = 28f
        val lowDensity = 1.0f
        val highDensity = 3.0f

        val lowDensityBitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = sizeDp,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = lowDensity,
        )

        val highDensityBitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = sizeDp,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = highDensity,
        )

        assertEquals((sizeDp * lowDensity).toInt(), lowDensityBitmap.width)
        assertEquals((sizeDp * highDensity).toInt(), highDensityBitmap.width)
    }

    @Test
    fun `createDashedCircle has ARGB_8888 config`() {
        val bitmap = MarkerBitmapFactory.createDashedCircle(
            sizeDp = 28f,
            strokeWidthDp = 3f,
            color = Color.RED,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    // ========== createFilledCircleWithDashedOutline Tests ==========

    @Test
    fun `createFilledCircleWithDashedOutline returns non-null bitmap`() {
        val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = 28f,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.6f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        assertNotNull(bitmap)
    }

    @Test
    fun `createFilledCircleWithDashedOutline creates square bitmap`() {
        val sizeDp = 32f
        val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = sizeDp,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.6f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        val expectedSize = (sizeDp * TEST_DENSITY).toInt()
        assertEquals(expectedSize, bitmap.width)
        assertEquals(expectedSize, bitmap.height)
    }

    @Test
    fun `createFilledCircleWithDashedOutline creates valid bitmap`() {
        val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = 40f,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 1.0f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        // Verify bitmap structure (Robolectric doesn't render actual pixels)
        assertTrue("Bitmap should have positive width", bitmap.width > 0)
        assertTrue("Bitmap should have positive height", bitmap.height > 0)
        assertFalse("Bitmap should not be recycled", bitmap.isRecycled)
    }

    @Test
    fun `createFilledCircleWithDashedOutline accepts various opacity values`() {
        // Test that various opacity values don't crash
        val opacityValues = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        for (opacity in opacityValues) {
            val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
                sizeDp = 40f,
                fillColor = Color.BLUE,
                strokeColor = Color.WHITE,
                fillOpacity = opacity,
                strokeWidthDp = 3f,
                dashLengthDp = 4f,
                gapLengthDp = 4f,
                density = TEST_DENSITY,
            )

            assertNotNull("Bitmap should not be null for opacity $opacity", bitmap)
            assertTrue("Bitmap should have positive dimensions", bitmap.width > 0)
        }
    }

    @Test
    fun `createFilledCircleWithDashedOutline scales with density`() {
        val sizeDp = 32f
        val lowDensity = 1.0f
        val highDensity = 3.0f

        val lowDensityBitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = sizeDp,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.6f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = lowDensity,
        )

        val highDensityBitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = sizeDp,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.6f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = highDensity,
        )

        assertEquals((sizeDp * lowDensity).toInt(), lowDensityBitmap.width)
        assertEquals((sizeDp * highDensity).toInt(), highDensityBitmap.width)
    }

    @Test
    fun `createFilledCircleWithDashedOutline has ARGB_8888 config`() {
        val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = 28f,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.6f,
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test
    fun `createFilledCircleWithDashedOutline handles zero opacity without crash`() {
        val bitmap = MarkerBitmapFactory.createFilledCircleWithDashedOutline(
            sizeDp = 40f,
            fillColor = Color.BLUE,
            strokeColor = Color.WHITE,
            fillOpacity = 0.0f, // Zero opacity
            strokeWidthDp = 3f,
            dashLengthDp = 4f,
            gapLengthDp = 4f,
            density = TEST_DENSITY,
        )

        // Verify bitmap is created successfully
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }
}
