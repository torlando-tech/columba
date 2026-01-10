package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageCompressionPreset enum.
 */
class ImageCompressionPresetTest {
    @Test
    fun `all presets have valid maxDimension values`() {
        ImageCompressionPreset.entries.forEach { preset ->
            assertTrue(
                "Preset ${preset.name} should have positive maxDimension",
                preset.maxDimensionPx > 0,
            )
        }
    }

    @Test
    fun `all presets have valid targetSize values`() {
        ImageCompressionPreset.entries.forEach { preset ->
            assertTrue(
                "Preset ${preset.name} should have positive targetSize",
                preset.targetSizeBytes > 0,
            )
        }
    }

    @Test
    fun `all presets have quality values in valid range`() {
        ImageCompressionPreset.entries.forEach { preset ->
            assertTrue(
                "Preset ${preset.name} initialQuality should be 0-100",
                preset.initialQuality in 0..100,
            )
            assertTrue(
                "Preset ${preset.name} minQuality should be 0-100",
                preset.minQuality in 0..100,
            )
            assertTrue(
                "Preset ${preset.name} minQuality should be <= initialQuality",
                preset.minQuality <= preset.initialQuality,
            )
        }
    }

    @Test
    fun `AUTO preset is default`() {
        assertEquals(ImageCompressionPreset.AUTO, ImageCompressionPreset.DEFAULT)
    }

    @Test
    fun `fromName returns correct preset`() {
        assertEquals(ImageCompressionPreset.LOW, ImageCompressionPreset.fromName("LOW"))
        assertEquals(ImageCompressionPreset.MEDIUM, ImageCompressionPreset.fromName("MEDIUM"))
        assertEquals(ImageCompressionPreset.HIGH, ImageCompressionPreset.fromName("HIGH"))
        assertEquals(ImageCompressionPreset.ORIGINAL, ImageCompressionPreset.fromName("ORIGINAL"))
        assertEquals(ImageCompressionPreset.AUTO, ImageCompressionPreset.fromName("AUTO"))
    }

    @Test
    fun `fromName returns DEFAULT for unknown name`() {
        assertEquals(ImageCompressionPreset.DEFAULT, ImageCompressionPreset.fromName("UNKNOWN"))
        assertEquals(ImageCompressionPreset.DEFAULT, ImageCompressionPreset.fromName(""))
    }

    @Test
    fun `presets are ordered by target size`() {
        assertTrue(
            "LOW should have smaller target than MEDIUM",
            ImageCompressionPreset.LOW.targetSizeBytes < ImageCompressionPreset.MEDIUM.targetSizeBytes,
        )
        assertTrue(
            "MEDIUM should have smaller target than HIGH",
            ImageCompressionPreset.MEDIUM.targetSizeBytes < ImageCompressionPreset.HIGH.targetSizeBytes,
        )
        assertTrue(
            "HIGH should have smaller target than ORIGINAL",
            ImageCompressionPreset.HIGH.targetSizeBytes < ImageCompressionPreset.ORIGINAL.targetSizeBytes,
        )
    }

    @Test
    fun `LOW preset has expected values`() {
        val preset = ImageCompressionPreset.LOW
        assertEquals(320, preset.maxDimensionPx)
        assertEquals(32 * 1024L, preset.targetSizeBytes)
    }

    @Test
    fun `MEDIUM preset has expected values`() {
        val preset = ImageCompressionPreset.MEDIUM
        assertEquals(800, preset.maxDimensionPx)
        assertEquals(128 * 1024L, preset.targetSizeBytes)
    }

    @Test
    fun `HIGH preset has expected values`() {
        val preset = ImageCompressionPreset.HIGH
        assertEquals(2048, preset.maxDimensionPx)
        assertEquals(512 * 1024L, preset.targetSizeBytes)
    }

    @Test
    fun `ORIGINAL preset has 25MB target`() {
        val preset = ImageCompressionPreset.ORIGINAL
        assertEquals(25 * 1024 * 1024L, preset.targetSizeBytes)
    }

    @Test
    fun `all presets have non-empty displayName and description`() {
        ImageCompressionPreset.entries.forEach { preset ->
            assertNotNull("Preset ${preset.name} should have displayName", preset.displayName)
            assertTrue(
                "Preset ${preset.name} displayName should not be empty",
                preset.displayName.isNotEmpty(),
            )
            assertNotNull("Preset ${preset.name} should have description", preset.description)
            assertTrue(
                "Preset ${preset.name} description should not be empty",
                preset.description.isNotEmpty(),
            )
        }
    }
}
