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

    // ========== isAnimatedGif Tests ==========

    @Test
    fun `isAnimatedGif returns false for empty bytes`() {
        assertFalse(ImageUtils.isAnimatedGif(byteArrayOf()))
    }

    @Test
    fun `isAnimatedGif returns false for bytes smaller than 6`() {
        assertFalse(ImageUtils.isAnimatedGif(byteArrayOf(0x47, 0x49, 0x46)))
        assertFalse(ImageUtils.isAnimatedGif(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39)))
    }

    @Test
    fun `isAnimatedGif returns false for non-GIF header`() {
        // PNG header
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertFalse(ImageUtils.isAnimatedGif(pngHeader))
    }

    @Test
    fun `isAnimatedGif returns false for JPEG header`() {
        // JPEG header
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertFalse(ImageUtils.isAnimatedGif(jpegHeader))
    }

    @Test
    fun `isAnimatedGif returns false for random bytes`() {
        val randomBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertFalse(ImageUtils.isAnimatedGif(randomBytes))
    }

    @Test
    fun `isAnimatedGif returns false for GIF87a without animation`() {
        // GIF87a header only (minimal static GIF)
        val gif87aHeader = "GIF87a".toByteArray(Charsets.US_ASCII)
        assertFalse(ImageUtils.isAnimatedGif(gif87aHeader))
    }

    @Test
    fun `isAnimatedGif returns false for GIF89a without animation markers`() {
        // GIF89a header with minimal screen descriptor but no animation extensions
        val gif89a = mutableListOf<Byte>()
        gif89a.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())
        // Add minimal screen descriptor (7 bytes)
        gif89a.addAll(listOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
        // Trailer
        gif89a.add(0x3B)

        assertFalse(ImageUtils.isAnimatedGif(gif89a.toByteArray()))
    }

    @Test
    fun `isAnimatedGif returns true for GIF with NETSCAPE extension`() {
        val animatedGif = createAnimatedGifWithNetscapeExtension()
        assertTrue(ImageUtils.isAnimatedGif(animatedGif))
    }

    @Test
    fun `isAnimatedGif returns true for GIF with multiple graphic control extensions`() {
        val animatedGif = createAnimatedGifWithMultipleFrames()
        assertTrue(ImageUtils.isAnimatedGif(animatedGif))
    }

    @Test
    fun `isAnimatedGif returns false for GIF with single frame`() {
        val staticGif = createStaticGifWithSingleFrame()
        assertFalse(ImageUtils.isAnimatedGif(staticGif))
    }

    @Test
    fun `isAnimatedGif handles GIF with NETSCAPE extension but partial match`() {
        // GIF89a header with partial NETSCAPE signature (missing last chars)
        val gif = mutableListOf<Byte>()
        gif.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())
        // Screen descriptor
        gif.addAll(listOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
        // Application extension header without full NETSCAPE2.0
        gif.add(0x21)
        gif.add(0xFF.toByte())
        gif.add(0x0B)
        gif.addAll("NETSCAPE2".toByteArray(Charsets.US_ASCII).toList()) // Missing ".0"
        gif.add(0x3B)

        // Should not match since NETSCAPE2.0 is incomplete
        assertFalse(ImageUtils.isAnimatedGif(gif.toByteArray()))
    }

    @Test
    fun `isAnimatedGif handles truncated GIF gracefully`() {
        // GIF89a with only partial NETSCAPE extension marker
        val gif = mutableListOf<Byte>()
        gif.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())
        gif.add(0x21)
        gif.add(0xFF.toByte())
        // Truncated - missing rest of extension

        // Should not crash, should return false
        assertFalse(ImageUtils.isAnimatedGif(gif.toByteArray()))
    }

    // ========== CompressedImage isAnimated Tests ==========

    @Test
    fun `CompressedImage isAnimated defaults to false`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "webp")
        assertFalse(image.isAnimated)
    }

    @Test
    fun `CompressedImage isAnimated can be set to true`() {
        val image = ImageUtils.CompressedImage(byteArrayOf(1, 2, 3), "gif", isAnimated = true)
        assertTrue(image.isAnimated)
    }

    @Test
    fun `CompressedImage equals considers isAnimated flag`() {
        val data = byteArrayOf(1, 2, 3)
        val image1 = ImageUtils.CompressedImage(data.copyOf(), "gif", isAnimated = true)
        val image2 = ImageUtils.CompressedImage(data.copyOf(), "gif", isAnimated = false)

        assertNotEquals(image1, image2)
    }

    @Test
    fun `CompressedImage hashCode differs for different isAnimated`() {
        val data = byteArrayOf(1, 2, 3)
        val image1 = ImageUtils.CompressedImage(data.copyOf(), "gif", isAnimated = true)
        val image2 = ImageUtils.CompressedImage(data.copyOf(), "gif", isAnimated = false)

        assertNotEquals(image1.hashCode(), image2.hashCode())
    }

    // ========== Helper Functions for GIF Tests ==========

    /**
     * Creates a minimal valid animated GIF with NETSCAPE2.0 extension.
     */
    private fun createAnimatedGifWithNetscapeExtension(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor (7 bytes)
        output.addAll(listOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))

        // NETSCAPE2.0 Application Extension (for looping)
        output.add(0x21) // Extension introducer
        output.add(0xFF.toByte()) // Application extension label
        output.add(0x0B) // Block size (11)
        output.addAll("NETSCAPE2.0".toByteArray(Charsets.US_ASCII).toList())
        output.add(0x03) // Sub-block size
        output.add(0x01) // Loop indicator
        output.add(0x00)
        output.add(0x00) // Loop count (0 = infinite)
        output.add(0x00) // Block terminator

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }

    /**
     * Creates a minimal valid animated GIF with multiple graphic control extensions (frames).
     */
    private fun createAnimatedGifWithMultipleFrames(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor (7 bytes)
        output.addAll(listOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))

        // First frame - Graphic Control Extension
        output.add(0x21) // Extension introducer
        output.add(0xF9.toByte()) // Graphic control label
        output.add(0x04) // Block size
        output.add(0x00) // Packed byte
        output.add(0x0A) // Delay (low)
        output.add(0x00) // Delay (high)
        output.add(0x00) // Transparent color
        output.add(0x00) // Block terminator

        // First frame - Image Descriptor
        output.add(0x2C)
        output.addAll(listOf(0x00, 0x00, 0x00, 0x00)) // Position
        output.addAll(listOf(0x01, 0x00, 0x01, 0x00)) // Size
        output.add(0x00) // Packed

        // Second frame - Graphic Control Extension
        output.add(0x21) // Extension introducer
        output.add(0xF9.toByte()) // Graphic control label
        output.add(0x04) // Block size
        output.add(0x00) // Packed byte
        output.add(0x0A) // Delay (low)
        output.add(0x00) // Delay (high)
        output.add(0x00) // Transparent color
        output.add(0x00) // Block terminator

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }

    /**
     * Creates a minimal valid static GIF with a single frame (no animation).
     */
    private fun createStaticGifWithSingleFrame(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor (7 bytes)
        output.addAll(listOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))

        // Single frame - Image Descriptor (no Graphic Control Extension)
        output.add(0x2C)
        output.addAll(listOf(0x00, 0x00, 0x00, 0x00)) // Position
        output.addAll(listOf(0x01, 0x00, 0x01, 0x00)) // Size
        output.add(0x00) // Packed

        // Image data (minimal LZW)
        output.add(0x02) // LZW minimum code size
        output.add(0x02) // Sub-block size
        output.add(0x44)
        output.add(0x01)
        output.add(0x00) // Block terminator

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }
}
