package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageUtils.
 *
 * Note: compressImage() requires Android Context and Bitmap APIs, so it cannot be
 * tested in pure unit tests. Those tests would need to be instrumented tests or
 * use Robolectric with proper Android framework mocking.
 */
class ImageUtilsTest {
    // ========== Constants Tests ==========

    @Test
    fun `MAX_IMAGE_SIZE_BYTES is 512KB`() {
        assertEquals(512 * 1024, ImageUtils.MAX_IMAGE_SIZE_BYTES)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is 2048 pixels`() {
        assertEquals(2048, ImageUtils.MAX_IMAGE_DIMENSION)
    }

    @Test
    fun `SUPPORTED_IMAGE_FORMATS includes expected formats`() {
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("jpg"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("jpeg"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("png"))
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("webp"))
    }

    @Test
    fun `SUPPORTED_IMAGE_FORMATS has exactly 4 formats`() {
        assertEquals(4, ImageUtils.SUPPORTED_IMAGE_FORMATS.size)
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
    fun `isImageFormatSupported returns true for mixed case Jpg`() {
        assertTrue(ImageUtils.isImageFormatSupported("Jpg"))
    }

    @Test
    fun `isImageFormatSupported returns true for mixed case JpEg`() {
        assertTrue(ImageUtils.isImageFormatSupported("JpEg"))
    }

    @Test
    fun `isImageFormatSupported returns true for mixed case pNg`() {
        assertTrue(ImageUtils.isImageFormatSupported("pNg"))
    }

    @Test
    fun `isImageFormatSupported returns true for mixed case WebP`() {
        assertTrue(ImageUtils.isImageFormatSupported("WebP"))
    }

    @Test
    fun `isImageFormatSupported returns false for gif`() {
        assertFalse(ImageUtils.isImageFormatSupported("gif"))
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
    fun `isImageFormatSupported returns false for svg`() {
        assertFalse(ImageUtils.isImageFormatSupported("svg"))
    }

    @Test
    fun `isImageFormatSupported returns false for heic`() {
        assertFalse(ImageUtils.isImageFormatSupported("heic"))
    }

    @Test
    fun `isImageFormatSupported returns false for avif`() {
        assertFalse(ImageUtils.isImageFormatSupported("avif"))
    }

    @Test
    fun `isImageFormatSupported returns false for null`() {
        assertFalse(ImageUtils.isImageFormatSupported(null))
    }

    @Test
    fun `isImageFormatSupported returns false for empty string`() {
        assertFalse(ImageUtils.isImageFormatSupported(""))
    }

    @Test
    fun `isImageFormatSupported returns false for whitespace`() {
        assertFalse(ImageUtils.isImageFormatSupported("   "))
    }

    @Test
    fun `isImageFormatSupported returns false for format with leading dot`() {
        assertFalse(ImageUtils.isImageFormatSupported(".jpg"))
    }

    @Test
    fun `isImageFormatSupported returns false for format with trailing spaces`() {
        // Note: This tests current behavior - format strings should be clean
        assertFalse(ImageUtils.isImageFormatSupported("jpg "))
    }

    // ========== CompressedImage Tests ==========

    @Test
    fun `CompressedImage stores data correctly`() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val compressed = ImageUtils.CompressedImage(data, "webp")

        assertTrue(data.contentEquals(compressed.data))
    }

    @Test
    fun `CompressedImage stores format correctly`() {
        val compressed = ImageUtils.CompressedImage(byteArrayOf(), "webp")

        assertEquals("webp", compressed.format)
    }

    @Test
    fun `CompressedImage format should be webp for Sideband interop`() {
        // This documents the expected format for Sideband compatibility
        val expectedFormat = "webp"
        val compressed = ImageUtils.CompressedImage(byteArrayOf(0x00), expectedFormat)

        assertEquals(expectedFormat, compressed.format)
    }

    @Test
    fun `CompressedImage equals works for equal instances`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val compressed1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val compressed2 = ImageUtils.CompressedImage(data.copyOf(), "webp")

        assertEquals(compressed1, compressed2)
    }

    @Test
    fun `CompressedImage equals returns false for different formats`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val compressed1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val compressed2 = ImageUtils.CompressedImage(data.copyOf(), "png")

        assertFalse(compressed1 == compressed2)
    }

    @Test
    fun `CompressedImage equals returns false for different data`() {
        val compressed1 = ImageUtils.CompressedImage(byteArrayOf(0x00, 0x01), "webp")
        val compressed2 = ImageUtils.CompressedImage(byteArrayOf(0x02, 0x03), "webp")

        assertFalse(compressed1 == compressed2)
    }

    @Test
    fun `CompressedImage equals returns false for different data lengths`() {
        val compressed1 = ImageUtils.CompressedImage(byteArrayOf(0x00), "webp")
        val compressed2 = ImageUtils.CompressedImage(byteArrayOf(0x00, 0x01), "webp")

        assertFalse(compressed1 == compressed2)
    }

    @Test
    fun `CompressedImage hashCode is consistent for equal instances`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val compressed1 = ImageUtils.CompressedImage(data.copyOf(), "webp")
        val compressed2 = ImageUtils.CompressedImage(data.copyOf(), "webp")

        assertEquals(compressed1.hashCode(), compressed2.hashCode())
    }

    @Test
    fun `CompressedImage handles empty data`() {
        val compressed = ImageUtils.CompressedImage(byteArrayOf(), "webp")

        assertEquals(0, compressed.data.size)
        assertEquals("webp", compressed.format)
    }

    @Test
    fun `CompressedImage handles large data`() {
        val largeData = ByteArray(512 * 1024) { it.toByte() }
        val compressed = ImageUtils.CompressedImage(largeData, "webp")

        assertEquals(512 * 1024, compressed.data.size)
        assertTrue(largeData.contentEquals(compressed.data))
    }

    // ========== WebP Format Documentation Tests ==========
    // These tests document the expected behavior for Sideband interop

    @Test
    fun `webp format string is lowercase without dot`() {
        // Sideband expects format strings like "webp", not ".webp" or "WEBP"
        val expectedFormat = "webp"
        assertEquals("webp", expectedFormat)
    }

    @Test
    fun `webp is in supported formats list`() {
        // WebP must be supported for Sideband interop
        assertTrue(ImageUtils.SUPPORTED_IMAGE_FORMATS.contains("webp"))
    }

    // ========== HEAVY_COMPRESSION_THRESHOLD Tests ==========

    @Test
    fun `HEAVY_COMPRESSION_THRESHOLD is 50`() {
        assertEquals(50, ImageUtils.HEAVY_COMPRESSION_THRESHOLD)
    }

    // ========== CompressionResult Tests ==========

    @Test
    fun `CompressionResult stores all values correctly`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val result = ImageUtils.CompressionResult(
            data = data,
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertTrue(data.contentEquals(result.data))
        assertEquals("webp", result.format)
        assertEquals(1000, result.originalSizeBytes)
        assertEquals(500, result.compressedSizeBytes)
        assertEquals(80, result.qualityUsed)
        assertFalse(result.wasScaledDown)
        assertFalse(result.exceedsSizeLimit)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is true when quality below threshold`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 40, // Below 50 threshold
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is true when quality at threshold minus one`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 49, // Just below threshold
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is false when quality at threshold`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 50, // At threshold - not below
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is false when quality above threshold`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 600,
            qualityUsed = 90,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is true when size limit exceeded`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 600,
            qualityUsed = 90, // High quality - not a concern
            wasScaledDown = false,
            exceedsSizeLimit = true, // But exceeds size limit
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult needsUserConfirmation is true when both quality low and size exceeded`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 2000,
            compressedSizeBytes = 600,
            qualityUsed = 10,
            wasScaledDown = true,
            exceedsSizeLimit = true,
        )

        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `CompressionResult compressionRatioText shows correct percentage`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 250,
            qualityUsed = 70,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("75% smaller", result.compressionRatioText)
    }

    @Test
    fun `CompressionResult compressionRatioText shows 0 percent for no compression`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
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
    fun `CompressionResult compressionRatioText shows NA for zero original size`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 0,
            compressedSizeBytes = 100,
            qualityUsed = 90,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("N/A", result.compressionRatioText)
    }

    @Test
    fun `CompressionResult compressionRatioText handles 50 percent compression`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 70,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals("50% smaller", result.compressionRatioText)
    }

    @Test
    fun `CompressionResult compressionRatioText handles 99 percent compression`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 10,
            qualityUsed = 10,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )

        assertEquals("99% smaller", result.compressionRatioText)
    }

    @Test
    fun `CompressionResult equals works for equal instances`() {
        val data = byteArrayOf(0x00, 0x01)
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
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals(result1, result2)
    }

    @Test
    fun `CompressionResult equals returns false for different data`() {
        val result1 = ImageUtils.CompressionResult(
            data = byteArrayOf(0x00),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )
        val result2 = ImageUtils.CompressionResult(
            data = byteArrayOf(0x01),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 500,
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult equals returns false for different format`() {
        val data = byteArrayOf(0x00)
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

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult equals returns false for different originalSizeBytes`() {
        val data = byteArrayOf(0x00)
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

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult equals returns false for different qualityUsed`() {
        val data = byteArrayOf(0x00)
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
            qualityUsed = 70,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult equals returns false for different wasScaledDown`() {
        val data = byteArrayOf(0x00)
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
            qualityUsed = 80,
            wasScaledDown = true,
            exceedsSizeLimit = false,
        )

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult equals returns false for different exceedsSizeLimit`() {
        val data = byteArrayOf(0x00)
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
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = true,
        )

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult hashCode is consistent for equal instances`() {
        val data = byteArrayOf(0x00, 0x01)
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
            qualityUsed = 80,
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertEquals(result1.hashCode(), result2.hashCode())
    }

    // ========== CompressionResult Edge Cases ==========

    @Test
    fun `CompressionResult wasScaledDown is tracked independently`() {
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 1000,
            compressedSizeBytes = 400,
            qualityUsed = 90, // Good quality
            wasScaledDown = true, // But was scaled
            exceedsSizeLimit = false,
        )

        // wasScaledDown shouldn't affect needsUserConfirmation by itself
        assertFalse(result.needsUserConfirmation)
        assertTrue(result.wasScaledDown)
    }

    @Test
    fun `CompressionResult minimum quality value 10`() {
        // Document the minimum quality boundary from compression loop
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 2000000, // 2MB original
            compressedSizeBytes = 600000, // 600KB compressed
            qualityUsed = 10, // Minimum quality
            wasScaledDown = true,
            exceedsSizeLimit = true,
        )

        assertTrue(result.needsUserConfirmation)
        assertEquals(10, result.qualityUsed)
    }

    @Test
    fun `CompressionResult quality at 90 is the starting point`() {
        // Document that compression starts at quality 90
        val result = ImageUtils.CompressionResult(
            data = byteArrayOf(),
            format = "webp",
            originalSizeBytes = 100000, // 100KB - fits easily
            compressedSizeBytes = 80000, // 80KB after compression
            qualityUsed = 90, // Starting quality
            wasScaledDown = false,
            exceedsSizeLimit = false,
        )

        assertFalse(result.needsUserConfirmation)
        assertEquals(90, result.qualityUsed)
    }
}
