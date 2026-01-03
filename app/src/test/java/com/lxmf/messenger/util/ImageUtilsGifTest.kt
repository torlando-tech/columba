package com.lxmf.messenger.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageUtils GIF detection functionality.
 */
class ImageUtilsGifTest {
    // ========== isAnimatedGif() Tests ==========

    @Test
    fun `isAnimatedGif returns false for empty byte array`() {
        val result = ImageUtils.isAnimatedGif(byteArrayOf())
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns false for array smaller than 6 bytes`() {
        val result = ImageUtils.isAnimatedGif(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39))
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns false for JPEG header`() {
        // JPEG magic bytes: FF D8 FF
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val result = ImageUtils.isAnimatedGif(jpeg)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns false for PNG header`() {
        // PNG magic bytes: 89 50 4E 47 0D 0A
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val result = ImageUtils.isAnimatedGif(png)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns false for GIF87a without animation`() {
        // GIF87a header without NETSCAPE extension or multiple frames
        val gif87a =
            "GIF87a".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00) // minimal non-animated gif data
        val result = ImageUtils.isAnimatedGif(gif87a)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns false for GIF89a without animation`() {
        // GIF89a header without NETSCAPE extension or multiple frames
        val gif89a =
            "GIF89a".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00) // minimal non-animated gif data
        val result = ImageUtils.isAnimatedGif(gif89a)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns true for GIF89a with NETSCAPE extension`() {
        // GIF89a with NETSCAPE2.0 application extension
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        // Some padding before the extension
        val padding = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        // NETSCAPE2.0 application extension: 0x21 0xFF 0x0B followed by "NETSCAPE2.0"
        val netscapeExtension =
            byteArrayOf(0x21, 0xFF.toByte(), 0x0B) +
                "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        val trailer = byteArrayOf(0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x3B)

        val animatedGif = header + padding + netscapeExtension + trailer
        val result = ImageUtils.isAnimatedGif(animatedGif)
        assertTrue(result)
    }

    @Test
    fun `isAnimatedGif returns true for GIF with multiple graphic control extensions`() {
        // GIF89a with multiple graphic control extensions (indicates multiple frames)
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val padding = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        // First graphic control extension: 0x21 0xF9
        val gce1 = byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        // Second graphic control extension
        val gce2 = byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        val trailer = byteArrayOf(0x3B)

        val multiFrameGif = header + padding + gce1 + gce2 + trailer
        val result = ImageUtils.isAnimatedGif(multiFrameGif)
        assertTrue(result)
    }

    @Test
    fun `isAnimatedGif returns false for single frame GIF with one graphic control extension`() {
        // GIF89a with only one graphic control extension (single frame)
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val padding = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        // Only one graphic control extension
        val gce = byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        val trailer = byteArrayOf(0x3B)

        val singleFrameGif = header + padding + gce + trailer
        val result = ImageUtils.isAnimatedGif(singleFrameGif)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif handles random binary data without crashing`() {
        // Random bytes that aren't a valid image
        val randomData =
            byteArrayOf(
                0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(),
            )
        val result = ImageUtils.isAnimatedGif(randomData)
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif handles truncated NETSCAPE extension`() {
        // GIF header followed by partial NETSCAPE extension (not enough bytes)
        val header = "GIF89a".toByteArray(Charsets.US_ASCII)
        val partialExtension =
            byteArrayOf(0x21, 0xFF.toByte(), 0x0B) +
                "NETSCAPE".toByteArray(Charsets.US_ASCII) // Missing "2.0"

        val truncatedGif = header + partialExtension
        val result = ImageUtils.isAnimatedGif(truncatedGif)
        // Should not crash, and shouldn't detect as animated since extension is incomplete
        assertFalse(result)
    }

    @Test
    fun `isAnimatedGif returns true for real animated GIF bytes`() {
        // This is a minimal valid animated GIF (1x1, 2 frames)
        // Created programmatically with proper GIF structure
        val animatedGifBytes = createMinimalAnimatedGif()
        val result = ImageUtils.isAnimatedGif(animatedGifBytes)
        assertTrue(result)
    }

    @Test
    fun `isAnimatedGif returns false for non-GIF with similar byte pattern`() {
        // Data that has 0x21 0xFF 0x0B pattern but isn't a GIF
        val fakeData =
            byteArrayOf(
                0x50, 0x4B, 0x03, 0x04, // ZIP header
                0x21, 0xFF.toByte(), 0x0B, // Matches NETSCAPE pattern prefix
            ) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)

        val result = ImageUtils.isAnimatedGif(fakeData)
        assertFalse(result)
    }

    // ========== CompressedImage Tests ==========

    @Test
    fun `CompressedImage equals returns true for identical data`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val img1 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        val img2 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        assertTrue(img1 == img2)
    }

    @Test
    fun `CompressedImage equals returns false for different data`() {
        val img1 = ImageUtils.CompressedImage(byteArrayOf(0x01), "gif", true)
        val img2 = ImageUtils.CompressedImage(byteArrayOf(0x02), "gif", true)
        assertFalse(img1 == img2)
    }

    @Test
    fun `CompressedImage equals returns false for different format`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val img1 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        val img2 = ImageUtils.CompressedImage(data.copyOf(), "jpg", true)
        assertFalse(img1 == img2)
    }

    @Test
    fun `CompressedImage equals returns false for different isAnimated`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val img1 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        val img2 = ImageUtils.CompressedImage(data.copyOf(), "gif", false)
        assertFalse(img1 == img2)
    }

    @Test
    fun `CompressedImage hashCode is consistent for equal objects`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val img1 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        val img2 = ImageUtils.CompressedImage(data.copyOf(), "gif", true)
        assertTrue(img1.hashCode() == img2.hashCode())
    }

    // ========== Constants Tests ==========

    @Test
    fun `MAX_IMAGE_SIZE_BYTES is 512KB`() {
        assertTrue(ImageUtils.MAX_IMAGE_SIZE_BYTES == 512 * 1024)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is 2048 pixels`() {
        assertTrue(ImageUtils.MAX_IMAGE_DIMENSION == 2048)
    }

    @Test
    fun `SUPPORTED_IMAGE_FORMATS contains expected formats`() {
        val formats = ImageUtils.SUPPORTED_IMAGE_FORMATS
        assertTrue(formats.contains("jpg"))
        assertTrue(formats.contains("jpeg"))
        assertTrue(formats.contains("png"))
        assertTrue(formats.contains("webp"))
        assertTrue(formats.contains("gif"))
    }

    @Test
    fun `isImageFormatSupported returns true for supported formats`() {
        assertTrue(ImageUtils.isImageFormatSupported("jpg"))
        assertTrue(ImageUtils.isImageFormatSupported("jpeg"))
        assertTrue(ImageUtils.isImageFormatSupported("png"))
        assertTrue(ImageUtils.isImageFormatSupported("gif"))
        assertTrue(ImageUtils.isImageFormatSupported("webp"))
    }

    @Test
    fun `isImageFormatSupported returns true for uppercase formats`() {
        assertTrue(ImageUtils.isImageFormatSupported("JPG"))
        assertTrue(ImageUtils.isImageFormatSupported("PNG"))
        assertTrue(ImageUtils.isImageFormatSupported("GIF"))
    }

    @Test
    fun `isImageFormatSupported returns false for unsupported formats`() {
        assertFalse(ImageUtils.isImageFormatSupported("bmp"))
        assertFalse(ImageUtils.isImageFormatSupported("tiff"))
        assertFalse(ImageUtils.isImageFormatSupported("svg"))
        assertFalse(ImageUtils.isImageFormatSupported("ico"))
    }

    @Test
    fun `isImageFormatSupported returns false for null`() {
        assertFalse(ImageUtils.isImageFormatSupported(null))
    }

    @Test
    fun `isImageFormatSupported returns false for empty string`() {
        assertFalse(ImageUtils.isImageFormatSupported(""))
    }

    // ========== Helper Functions ==========

    /**
     * Creates a minimal valid animated GIF with 2 frames.
     * This is a properly structured GIF89a with NETSCAPE2.0 extension.
     */
    private fun createMinimalAnimatedGif(): ByteArray {
        val output = mutableListOf<Byte>()

        // GIF89a header
        output.addAll("GIF89a".toByteArray(Charsets.US_ASCII).toList())

        // Logical Screen Descriptor (7 bytes)
        output.add(0x01) // Width low byte
        output.add(0x00) // Width high byte
        output.add(0x01) // Height low byte
        output.add(0x00) // Height high byte
        output.add(0x00) // Packed field (no global color table)
        output.add(0x00) // Background color index
        output.add(0x00) // Pixel aspect ratio

        // NETSCAPE2.0 Application Extension (for looping)
        output.add(0x21) // Extension introducer
        output.add(0xFF.toByte()) // Application extension label
        output.add(0x0B) // Block size
        output.addAll("NETSCAPE2.0".toByteArray(Charsets.US_ASCII).toList())
        output.add(0x03) // Sub-block size
        output.add(0x01) // Sub-block ID
        output.add(0x00) // Loop count low byte (0 = infinite)
        output.add(0x00) // Loop count high byte
        output.add(0x00) // Block terminator

        // Frame 1 - Graphic Control Extension
        output.add(0x21) // Extension introducer
        output.add(0xF9.toByte()) // Graphic control label
        output.add(0x04) // Block size
        output.add(0x00) // Packed field
        output.add(0x0A) // Delay low byte (10 * 10ms = 100ms)
        output.add(0x00) // Delay high byte
        output.add(0x00) // Transparent color index
        output.add(0x00) // Block terminator

        // Frame 1 - Image Descriptor
        output.add(0x2C) // Image separator
        output.add(0x00) // Left position low
        output.add(0x00) // Left position high
        output.add(0x00) // Top position low
        output.add(0x00) // Top position high
        output.add(0x01) // Width low
        output.add(0x00) // Width high
        output.add(0x01) // Height low
        output.add(0x00) // Height high
        output.add(0x00) // Packed field (no local color table)

        // Frame 1 - Image Data (minimal LZW)
        output.add(0x02) // LZW minimum code size
        output.add(0x02) // Sub-block size
        output.add(0x44) // LZW data
        output.add(0x01) // LZW data
        output.add(0x00) // Block terminator

        // Frame 2 - Graphic Control Extension
        output.add(0x21) // Extension introducer
        output.add(0xF9.toByte()) // Graphic control label
        output.add(0x04) // Block size
        output.add(0x00) // Packed field
        output.add(0x0A) // Delay low byte
        output.add(0x00) // Delay high byte
        output.add(0x00) // Transparent color index
        output.add(0x00) // Block terminator

        // Frame 2 - Image Descriptor
        output.add(0x2C) // Image separator
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x00)

        // Frame 2 - Image Data
        output.add(0x02)
        output.add(0x02)
        output.add(0x44)
        output.add(0x01)
        output.add(0x00)

        // Trailer
        output.add(0x3B)

        return output.toByteArray()
    }
}
