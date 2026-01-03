package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageUtils data classes and utility functions.
 * Tests cover:
 * - CompressedImage data class (equals/hashCode)
 * - CompressionResult data class (computed properties, equals/hashCode)
 * - isImageFormatSupported function
 */
class ImageUtilsTest {

    // ========== CompressedImage Tests ==========

    @Test
    fun `CompressedImage equals returns true for identical data`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val image1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val image2 = ImageUtils.CompressedImage(data.copyOf(), "webp")

        assertEquals(image1, image2)
    }

    @Test
    fun `CompressedImage equals returns false for different data`() {
        val image1 = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")
        val image2 = ImageUtils.CompressedImage(byteArrayOf(4, 5, 6), "webp")

        assertNotEquals(image1, image2)
    }

    @Test
    fun `CompressedImage equals returns false for different format`() {
        val data = byteArrayOf(1, 2, 3)
        val image1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val image2 = ImageUtils.CompressedImage(data.copyOf(), "png")

        assertNotEquals(image1, image2)
    }

    @Test
    @Suppress("EqualsNullCall") // Testing equals(null) behavior explicitly
    fun `CompressedImage equals returns false for null`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")

        assertFalse(image.equals(null))
    }

    @Test
    fun `CompressedImage equals returns false for different type`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")

        @Suppress("ReplaceCallWithBinaryOperator")
        assertFalse(image.equals("not an image"))
    }

    @Test
    fun `CompressedImage equals returns true for same instance`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")

        assertEquals(image, image)
    }

    @Test
    fun `CompressedImage hashCode is same for equal objects`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val image1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val image2 = ImageUtils.CompressedImage(data.copyOf(), "webp")

        assertEquals(image1.hashCode(), image2.hashCode())
    }

    @Test
    fun `CompressedImage hashCode differs for different data`() {
        val image1 = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")
        val image2 = ImageUtils.CompressedImage(byteArrayOf(4, 5, 6), "webp")

        // Note: hashCode collision is theoretically possible but extremely unlikely for different data
        assertNotEquals(image1.hashCode(), image2.hashCode())
    }

    // ========== CompressionResult.needsUserConfirmation Tests ==========

    @Test
    fun `needsUserConfirmation returns true when quality below threshold`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 100,
            qualityUsed = 40, // Below HEAVY_COMPRESSION_THRESHOLD (50)
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `needsUserConfirmation returns true when size limit exceeded`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 600,
            qualityUsed = 80, // Above threshold
            wasScaledDown = false,
            exceedsSizeLimit = true,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `needsUserConfirmation returns true when both conditions met`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 600,
            qualityUsed = 30, // Below threshold
            wasScaledDown = true,
            exceedsSizeLimit = true,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `needsUserConfirmation returns false when quality above threshold and within size limit`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 80, // Above threshold
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.needsUserConfirmation)
    }

    @Test
    fun `needsUserConfirmation returns true at exactly threshold quality`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 49, // Just below threshold (50)
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `needsUserConfirmation returns false at threshold quality`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 50, // Exactly at threshold
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.needsUserConfirmation)
    }

    // ========== CompressionResult.compressionRatioText Tests ==========

    @Test
    fun `compressionRatioText shows 0 percent for no reduction`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 1000,
            qualityUsed = 90,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("0% smaller", result.compressionRatioText)
    }

    @Test
    fun `compressionRatioText shows 50 percent for half size`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("50% smaller", result.compressionRatioText)
    }

    @Test
    fun `compressionRatioText shows 90 percent for 90 percent reduction`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 100,
            qualityUsed = 50,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )

        assertEquals("90% smaller", result.compressionRatioText)
    }

    @Test
    fun `compressionRatioText returns NA for zero original size`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 0,
            compressedSizeBytes = 100,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("N/A", result.compressionRatioText)
    }

    @Test
    fun `compressionRatioText handles rounding correctly`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 333,
            qualityUsed = 70,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        // (1 - 333/1000) * 100 = 66.7%, rounds to 66
        assertEquals("66% smaller", result.compressionRatioText)
    }

    // ========== CompressionResult equals/hashCode Tests ==========

    @Test
    fun `CompressionResult equals returns true for identical values`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )

        assertEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different data`() {
        val result1 = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = byteArrayOf(4, 5, 6),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different format`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "png",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different originalSizeBytes`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 2000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different compressedSizeBytes`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 300,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different qualityUsed`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 60,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different wasScaledDown`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different exceedsSizeLimit`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = true,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertNotEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns true for same instance`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals(result, result)
    }

    @Test
    @Suppress("EqualsNullCall") // Testing equals(null) behavior explicitly
    fun `CompressionResult equals returns false for null`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.equals(null))
    }

    @Test
    fun `CompressionResult equals returns false for different type`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(1, 2, 3),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        @Suppress("ReplaceCallWithBinaryOperator")
        assertFalse(result.equals("not a compression result"))
    }

    @Test
    fun `CompressionResult hashCode is same for equal objects`() {
        val data = byteArrayOf(1, 2, 3)
        val result1 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = data.copyOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )

        assertEquals(result1.hashCode(), result2.hashCode())
    }

    // ========== isImageFormatSupported Tests ==========

    @Test
    fun `isImageFormatSupported returns true for jpg`() {
        assertTrue(ImageUtils.isImageFormatSupported("jpg"))
    }

    @Test
    fun `isImageFormatSupported returns true for jpeg`() {
        assertTrue(ImageUtils.isImageFormatSupported("jpeg"))
    }

    @Test
    fun `isImageFormatSupported returns true for png`() {
        assertTrue(ImageUtils.isImageFormatSupported("png"))
    }

    @Test
    fun `isImageFormatSupported returns true for webp`() {
        assertTrue(ImageUtils.isImageFormatSupported("webp"))
    }

    @Test
    fun `isImageFormatSupported returns true for uppercase JPG`() {
        assertTrue(ImageUtils.isImageFormatSupported("JPG"))
    }

    @Test
    fun `isImageFormatSupported returns true for uppercase JPEG`() {
        assertTrue(ImageUtils.isImageFormatSupported("JPEG"))
    }

    @Test
    fun `isImageFormatSupported returns true for uppercase PNG`() {
        assertTrue(ImageUtils.isImageFormatSupported("PNG"))
    }

    @Test
    fun `isImageFormatSupported returns true for uppercase WEBP`() {
        assertTrue(ImageUtils.isImageFormatSupported("WEBP"))
    }

    @Test
    fun `isImageFormatSupported returns true for mixed case JpG`() {
        assertTrue(ImageUtils.isImageFormatSupported("JpG"))
    }

    @Test
    fun `isImageFormatSupported returns true for gif`() {
        // GIF is now supported for animated images
        assertTrue(ImageUtils.isImageFormatSupported("gif"))
    }

    @Test
    fun `isImageFormatSupported returns false for bmp`() {
        assertFalse(ImageUtils.isImageFormatSupported("bmp"))
    }

    @Test
    fun `isImageFormatSupported returns false for tiff`() {
        assertFalse(ImageUtils.isImageFormatSupported("tiff"))
    }

    @Test
    fun `isImageFormatSupported returns false for unknown format`() {
        assertFalse(ImageUtils.isImageFormatSupported("xyz"))
    }

    @Test
    fun `isImageFormatSupported returns false for null`() {
        assertFalse(ImageUtils.isImageFormatSupported(null))
    }

    @Test
    fun `isImageFormatSupported returns false for empty string`() {
        assertFalse(ImageUtils.isImageFormatSupported(""))
    }

    // ========== Constants Tests ==========

    @Test
    fun `MAX_IMAGE_SIZE_BYTES is 512KB`() {
        assertEquals(512 * 1024, ImageUtils.MAX_IMAGE_SIZE_BYTES)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is 2048`() {
        assertEquals(2048, ImageUtils.MAX_IMAGE_DIMENSION)
    }

    @Test
    fun `HEAVY_COMPRESSION_THRESHOLD is 50`() {
        assertEquals(50, ImageUtils.HEAVY_COMPRESSION_THRESHOLD)
    }

    @Test
    fun `SUPPORTED_IMAGE_FORMATS contains expected formats`() {
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("jpg"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("jpeg"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("png"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("webp"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("gif"))
        assertEquals(5, ImageUtils.SUPPORTED_IMAGE_FORMATS.size)
    }
}
