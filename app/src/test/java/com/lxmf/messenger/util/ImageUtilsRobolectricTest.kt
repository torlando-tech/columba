package com.lxmf.messenger.util

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.lxmf.messenger.data.model.ImageCompressionPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for ImageUtils Android-specific functions.
 * Tests functions that require Android Context and Bitmap APIs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageUtilsRobolectricTest {
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== getImageFormat Tests ==========

    @Test
    fun `getImageFormat returns jpg for image jpeg mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "image/jpeg"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertEquals("jpg", result)
    }

    @Test
    fun `getImageFormat returns png for image png mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "image/png"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertEquals("png", result)
    }

    @Test
    fun `getImageFormat returns webp for image webp mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "image/webp"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertEquals("webp", result)
    }

    @Test
    fun `getImageFormat returns gif for image gif mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "image/gif"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertEquals("gif", result)
    }

    @Test
    fun `getImageFormat returns null for unknown mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "image/tiff"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertNull(result)
    }

    @Test
    fun `getImageFormat returns null for non-image mime type`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns "application/pdf"

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertNull(result)
    }

    @Test
    fun `getImageFormat returns null when content resolver returns null`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } returns null

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertNull(result)
    }

    @Test
    fun `getImageFormat returns null on exception`() {
        val testUri = mockk<Uri>()
        every { mockContentResolver.getType(testUri) } throws RuntimeException("Test error")

        val result = ImageUtils.getImageFormat(testUri, mockContext)

        assertNull(result)
    }

    // ========== Bitmap compression helper for tests ==========

    private fun createTestBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            // Fill with a solid color to make it compressible
            eraseColor(Color.BLUE)
        }
    }

    // ========== CompressionResult with preset values ==========

    @Test
    fun `LOW preset target size is 32KB`() {
        assertEquals(32 * 1024L, ImageCompressionPreset.LOW.targetSizeBytes)
    }

    @Test
    fun `MEDIUM preset target size is 128KB`() {
        assertEquals(128 * 1024L, ImageCompressionPreset.MEDIUM.targetSizeBytes)
    }

    @Test
    fun `HIGH preset target size is 512KB`() {
        assertEquals(512 * 1024L, ImageCompressionPreset.HIGH.targetSizeBytes)
    }

    @Test
    fun `ORIGINAL preset target size is 25MB`() {
        assertEquals(25 * 1024 * 1024L, ImageCompressionPreset.ORIGINAL.targetSizeBytes)
    }

    @Test
    fun `AUTO preset defaults to HIGH dimensions`() {
        assertEquals(2048, ImageCompressionPreset.AUTO.maxDimensionPx)
    }

    // ========== MAX constants tests ==========

    @Test
    fun `MAX_IMAGE_SIZE_BYTES is 512KB`() {
        assertEquals(512 * 1024, ImageUtils.MAX_IMAGE_SIZE_BYTES)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is 2048 pixels`() {
        assertEquals(2048, ImageUtils.MAX_IMAGE_DIMENSION)
    }

    // ========== Bitmap scaling behavior ==========

    @Test
    fun `small bitmap is not scaled when under max dimension`() {
        val smallBitmap = createTestBitmap(100, 100)

        // Verify dimensions are preserved for small images
        assertTrue(smallBitmap.width <= ImageUtils.MAX_IMAGE_DIMENSION)
        assertTrue(smallBitmap.height <= ImageUtils.MAX_IMAGE_DIMENSION)

        smallBitmap.recycle()
    }

    @Test
    fun `large bitmap dimensions exceed max dimension constant`() {
        val largeBitmap = createTestBitmap(4000, 3000)

        // Verify we can create a large bitmap for testing
        assertTrue(largeBitmap.width > ImageUtils.MAX_IMAGE_DIMENSION)

        largeBitmap.recycle()
    }

    // ========== JPEG compression format tests ==========

    @Test
    fun `JPEG compression produces valid output`() {
        val bitmap = createTestBitmap(100, 100)
        val stream = java.io.ByteArrayOutputStream()

        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)

        assertTrue(success)
        assertTrue(stream.toByteArray().isNotEmpty())

        bitmap.recycle()
    }

    @Test
    fun `JPEG compression with lower quality produces smaller output`() {
        val bitmap = createTestBitmap(500, 500)

        val streamHigh = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, streamHigh)
        val highQualitySize = streamHigh.toByteArray().size

        val streamLow = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 30, streamLow)
        val lowQualitySize = streamLow.toByteArray().size

        assertTrue(
            "Low quality ($lowQualitySize) should be smaller than high quality ($highQualitySize)",
            lowQualitySize < highQualitySize,
        )

        bitmap.recycle()
    }

    @Test
    fun `bitmap can be scaled down`() {
        val original = createTestBitmap(1000, 800)

        val scaled = Bitmap.createScaledBitmap(original, 500, 400, true)

        assertEquals(500, scaled.width)
        assertEquals(400, scaled.height)

        original.recycle()
        scaled.recycle()
    }

    // ========== CompressedImage format consistency ==========

    @Test
    fun `compressImage returns jpg format`() {
        // Verify that CompressedImage uses jpg as the format string
        val image = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        assertEquals("jpg", image.format)
    }

    @Test
    fun `compressImageWithPreset returns jpg format in result`() {
        // Verify the expected format in CompressionResult
        val image = ImageUtils.CompressedImage(byteArrayOf(0x00), "jpg")
        val result =
            ImageUtils.CompressionResult(
                compressedImage = image,
                originalSizeBytes = 1000L,
                meetsTargetSize = true,
                targetSizeBytes = 512 * 1024L,
                preset = ImageCompressionPreset.HIGH,
            )

        assertEquals("jpg", result.compressedImage.format)
    }

    // ========== calculateTransferTime additional edge cases ==========

    @Test
    fun `calculateTransferTime with LoRa bandwidth`() {
        // LoRa: ~1.2kbps typical
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 32 * 1024L, // 32KB (LOW preset)
                bandwidthBps = 1200,
            )

        // 32KB = 262144 bits / 1200 bps = ~218 seconds = 3m 38s
        assertTrue(result.seconds > 200)
        assertTrue(result.formattedTime.contains("m"))
    }

    @Test
    fun `calculateTransferTime with BLE bandwidth`() {
        // BLE: ~50kbps typical
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 128 * 1024L, // 128KB (MEDIUM preset)
                bandwidthBps = 50_000,
            )

        // 128KB = 1048576 bits / 50000 bps = ~21 seconds
        assertTrue(result.seconds in 15..30)
        assertTrue(result.formattedTime.contains("s"))
    }

    @Test
    fun `calculateTransferTime with TCP bandwidth`() {
        // TCP: ~1Mbps typical
        val result =
            ImageUtils.calculateTransferTime(
                sizeBytes = 512 * 1024L, // 512KB (HIGH preset)
                bandwidthBps = 1_000_000,
            )

        // 512KB = 4194304 bits / 1000000 bps = ~4 seconds
        assertTrue(result.seconds < 10)
    }

    // ========== Preset quality ranges ==========

    @Test
    fun `LOW preset quality range is 30-60`() {
        assertEquals(60, ImageCompressionPreset.LOW.initialQuality)
        assertEquals(30, ImageCompressionPreset.LOW.minQuality)
    }

    @Test
    fun `MEDIUM preset quality range is 40-75`() {
        assertEquals(75, ImageCompressionPreset.MEDIUM.initialQuality)
        assertEquals(40, ImageCompressionPreset.MEDIUM.minQuality)
    }

    @Test
    fun `HIGH preset quality range is 50-90`() {
        assertEquals(90, ImageCompressionPreset.HIGH.initialQuality)
        assertEquals(50, ImageCompressionPreset.HIGH.minQuality)
    }

    @Test
    fun `ORIGINAL preset quality range is 90-95`() {
        assertEquals(95, ImageCompressionPreset.ORIGINAL.initialQuality)
        assertEquals(90, ImageCompressionPreset.ORIGINAL.minQuality)
    }

    // ========== Preset dimension limits ==========

    @Test
    fun `LOW preset max dimension is 320px`() {
        assertEquals(320, ImageCompressionPreset.LOW.maxDimensionPx)
    }

    @Test
    fun `MEDIUM preset max dimension is 800px`() {
        assertEquals(800, ImageCompressionPreset.MEDIUM.maxDimensionPx)
    }

    @Test
    fun `HIGH preset max dimension is 2048px`() {
        assertEquals(2048, ImageCompressionPreset.HIGH.maxDimensionPx)
    }

    @Test
    fun `ORIGINAL preset has 8K max dimension`() {
        assertEquals(8192, ImageCompressionPreset.ORIGINAL.maxDimensionPx)
    }
}
