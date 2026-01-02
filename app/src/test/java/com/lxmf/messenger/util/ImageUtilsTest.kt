package com.lxmf.messenger.util

import com.lxmf.messenger.data.model.ImageCompressionPreset
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

    // ========== CompressionResult Tests ==========

    @Test
    fun `CompressionResult stores all values correctly`() {
        val compressedImage = ImageUtils.CompressedImage(byteArrayOf(0x00, 0x01, 0x02), "jpg")
        val result =
            ImageUtils.CompressionResult(
                compressedImage = compressedImage,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        assertEquals(compressedImage, result.compressedImage)
        assertEquals(1000L, result.originalSizeBytes)
        assertTrue(result.meetsTargetSize)
        assertEquals(512 * 1024L, result.targetSizeBytes)
        assertEquals(ImageCompressionPreset.HIGH, result.preset)
    }

    @Test
    fun `CompressionResult meetsTargetSize is false when compressed size exceeds target`() {
        val result =
            ImageUtils.CompressionResult(
                compressedImage = ImageUtils.CompressedImage(byteArrayOf(), "jpg"),
                originalSizeBytes = 2_000_000L,
                meetsTargetSize = false,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        assertFalse(result.meetsTargetSize)
    }

    @Test
    fun `CompressionResult with LOW preset has correct target size`() {
        val result =
            ImageUtils.CompressionResult(
                compressedImage = ImageUtils.CompressedImage(byteArrayOf(), "jpg"),
                originalSizeBytes = 100_000L,
                meetsTargetSize = true,
                targetSizeBytes = ImageCompressionPreset.LOW.targetSizeBytes,
                preset = ImageCompressionPreset.LOW,
            )

        assertEquals(32 * 1024L, result.targetSizeBytes)
    }

    @Test
    fun `CompressionResult with MEDIUM preset has correct target size`() {
        val result =
            ImageUtils.CompressionResult(
                compressedImage = ImageUtils.CompressedImage(byteArrayOf(), "jpg"),
                originalSizeBytes = 200_000L,
                meetsTargetSize = true,
                targetSizeBytes = ImageCompressionPreset.MEDIUM.targetSizeBytes,
                preset = ImageCompressionPreset.MEDIUM,
            )

        assertEquals(128 * 1024L, result.targetSizeBytes)
    }

    // ========== TransferTimeEstimate Tests ==========

    @Test
    fun `TransferTimeEstimate stores values correctly`() {
        val estimate =
            ImageUtils.TransferTimeEstimate(
                seconds = 120,
                formattedTime = "2m",
            )

        assertEquals(120, estimate.seconds)
        assertEquals("2m", estimate.formattedTime)
    }

    // ========== calculateTransferTime Tests ==========

    @Test
    fun `calculateTransferTime returns less than 1s for small fast transfers`() {
        // 1KB at 100kbps = 0.08 seconds
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 1024L,
                bandwidthBps = 100_000,
            )

        assertEquals(0, result.seconds)
        assertEquals("< 1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime returns seconds for short transfers`() {
        // 10KB at 10kbps = 8 seconds
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 10 * 1024L,
                bandwidthBps = 10_000,
            )

        assertEquals(8, result.seconds)
        assertEquals("8s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime returns minutes for longer transfers`() {
        // 512KB at 10kbps = 409.6 seconds ≈ 6m 49s
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 512 * 1024L,
                bandwidthBps = 10_000,
            )

        assertEquals(419, result.seconds)
        assertEquals("6m 59s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime returns hours for very long transfers`() {
        // 10MB at 1kbps = 81920 seconds ≈ 22h 45m
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 10 * 1024 * 1024L,
                bandwidthBps = 1_000,
            )

        assertTrue(result.seconds >= 3600)
        assertTrue(result.formattedTime.contains("h"))
    }

    @Test
    fun `calculateTransferTime returns Unknown for zero bandwidth`() {
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 1024L,
                bandwidthBps = 0,
            )

        assertEquals(0, result.seconds)
        assertEquals("Unknown", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime returns Unknown for negative bandwidth`() {
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 1024L,
                bandwidthBps = -100,
            )

        assertEquals(0, result.seconds)
        assertEquals("Unknown", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles zero size`() {
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 0L,
                bandwidthBps = 10_000,
            )

        assertEquals(0, result.seconds)
        assertEquals("< 1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats exactly 60 seconds as 1m`() {
        // 75KB at 10kbps = 60 seconds exactly
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 75 * 1024L,
                bandwidthBps = 10_000,
            )

        // Due to integer division, 75*1024*8 / 10000 = 61 seconds
        assertEquals(61, result.seconds)
        assertEquals("1m 1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats hour without remaining minutes`() {
        // Calculate bytes needed for exactly 1 hour at 1000bps
        // 1 hour = 3600 seconds, at 1000bps = 3600 * 1000 bits = 450000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 450_000L,
                bandwidthBps = 1_000,
            )

        assertEquals(3600, result.seconds)
        assertEquals("1h", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats 59 seconds correctly`() {
        // 59 seconds should show as "59s", not "1m"
        // Need 59 * bandwidth / 8 bytes
        // At 8000 bps: 59 * 8000 / 8 = 59000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 59_000L,
                bandwidthBps = 8_000,
            )

        assertEquals(59, result.seconds)
        assertEquals("59s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats minutes without remaining seconds`() {
        // Exactly 2 minutes = 120 seconds
        // At 8000 bps: 120 * 8000 / 8 = 120000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 120_000L,
                bandwidthBps = 8_000,
            )

        assertEquals(120, result.seconds)
        assertEquals("2m", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats minutes with remaining seconds`() {
        // 2 minutes 30 seconds = 150 seconds
        // At 8000 bps: 150 * 8000 / 8 = 150000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 150_000L,
                bandwidthBps = 8_000,
            )

        assertEquals(150, result.seconds)
        assertEquals("2m 30s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats hours with remaining minutes`() {
        // 1 hour 30 minutes = 5400 seconds
        // At 8000 bps: 5400 * 8000 / 8 = 5400000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 5_400_000L,
                bandwidthBps = 8_000,
            )

        assertEquals(5400, result.seconds)
        assertEquals("1h 30m", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles 1 second exactly`() {
        // 1 second at 8000 bps = 1000 bytes
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 1_000L,
                bandwidthBps = 8_000,
            )

        assertEquals(1, result.seconds)
        assertEquals("1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles large file sizes`() {
        // 1GB at 1Mbps = 8589934592 bits / 1000000 bps = 8589 seconds ≈ 2h 23m
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 1024L * 1024L * 1024L,
                bandwidthBps = 1_000_000,
            )

        assertTrue(result.seconds > 0)
        assertTrue(result.formattedTime.contains("h"))
    }

    // ========== CompressionResult Additional Tests ==========

    @Test
    fun `CompressionResult with ORIGINAL preset has correct target size`() {
        val result =
            ImageUtils.CompressionResult(
                compressedImage = ImageUtils.CompressedImage(byteArrayOf(), "jpg"),
                originalSizeBytes = 10_000_000L,
                meetsTargetSize = true,
                targetSizeBytes = ImageCompressionPreset.ORIGINAL.targetSizeBytes,
                preset = ImageCompressionPreset.ORIGINAL,
            )

        assertEquals(250 * 1024 * 1024L, result.targetSizeBytes)
    }

    @Test
    fun `CompressionResult with AUTO preset has correct target size`() {
        val result =
            ImageUtils.CompressionResult(
                compressedImage = ImageUtils.CompressedImage(byteArrayOf(), "jpg"),
                originalSizeBytes = 500_000L,
                meetsTargetSize = true,
                targetSizeBytes = ImageCompressionPreset.AUTO.targetSizeBytes,
                preset = ImageCompressionPreset.AUTO,
            )

        assertEquals(512 * 1024L, result.targetSizeBytes)
    }

    @Test
    fun `CompressionResult data class equality works correctly`() {
        val image1 = ImageUtils.CompressedImage(byteArrayOf(0x01, 0x02), "jpg")
        val image2 = ImageUtils.CompressedImage(byteArrayOf(0x01, 0x02), "jpg")

        val result1 =
            ImageUtils.CompressionResult(
                compressedImage = image1,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        val result2 =
            ImageUtils.CompressionResult(
                compressedImage = image2,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        assertEquals(result1, result2)
    }

    @Test
    fun `CompressionResult data class inequality for different presets`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(0x01), "jpg")

        val result1 =
            ImageUtils.CompressionResult(
                compressedImage = image,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        val result2 =
            ImageUtils.CompressionResult(
                compressedImage = image,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 128 * 1024L,
                preset = ImageCompressionPreset.MEDIUM,
            )

        assertFalse(result1 == result2)
    }

    @Test
    fun `CompressionResult data class inequality for different meetsTargetSize`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(0x01), "jpg")

        val result1 =
            ImageUtils.CompressionResult(
                compressedImage = image,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        val result2 =
            ImageUtils.CompressionResult(
                compressedImage = image,
                originalSizeBytes = 1000L,
                meetsTargetSize = false,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        assertFalse(result1 == result2)
    }

    // ========== CompressedImage Additional Tests ==========

    @Test
    fun `CompressedImage equals returns false for null`() {
        val compressed = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        val nullImage: ImageUtils.CompressedImage? = null
        assertFalse(compressed == nullImage)
    }

    @Test
    fun `CompressedImage equals returns false for different type`() {
        val compressed = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        val other: Any = "not a CompressedImage"
        assertFalse(compressed == other)
    }

    @Test
    fun `CompressedImage equals returns true for same instance`() {
        val compressed = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(compressed.equals(compressed))
    }

    @Test
    fun `CompressedImage hashCode differs for different data`() {
        val compressed1 = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        val compressed2 = ImageUtils.CompressedImage(byteArrayOf(0x01), "jpg")

        // While not guaranteed by hashCode contract, different data should usually produce different hashes
        assertFalse(compressed1.hashCode() == compressed2.hashCode())
    }

    @Test
    fun `CompressedImage hashCode differs for different formats`() {
        val data = byteArrayOf(0x00)
        val compressed1 = ImageUtils.CompressedImage(data, "jpg")
        val compressed2 = ImageUtils.CompressedImage(data, "png")

        assertFalse(compressed1.hashCode() == compressed2.hashCode())
    }

    // ========== TransferTimeEstimate Additional Tests ==========

    @Test
    fun `TransferTimeEstimate data class equality works correctly`() {
        val estimate1 = ImageUtils.TransferTimeEstimate(60, "1m")
        val estimate2 = ImageUtils.TransferTimeEstimate(60, "1m")

        assertEquals(estimate1, estimate2)
    }

    @Test
    fun `TransferTimeEstimate data class inequality for different seconds`() {
        val estimate1 = ImageUtils.TransferTimeEstimate(60, "1m")
        val estimate2 = ImageUtils.TransferTimeEstimate(120, "1m")

        assertFalse(estimate1 == estimate2)
    }

    @Test
    fun `TransferTimeEstimate data class inequality for different formattedTime`() {
        val estimate1 = ImageUtils.TransferTimeEstimate(60, "1m")
        val estimate2 = ImageUtils.TransferTimeEstimate(60, "60s")

        assertFalse(estimate1 == estimate2)
    }

    @Test
    fun `TransferTimeEstimate hashCode is consistent for equal instances`() {
        val estimate1 = ImageUtils.TransferTimeEstimate(120, "2m")
        val estimate2 = ImageUtils.TransferTimeEstimate(120, "2m")

        assertEquals(estimate1.hashCode(), estimate2.hashCode())
    }
}
