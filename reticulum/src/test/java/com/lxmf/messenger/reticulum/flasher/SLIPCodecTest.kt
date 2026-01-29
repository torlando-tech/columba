package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SLIP codec implementation.
 */
class SLIPCodecTest {
    @Test
    fun `encodeEscapeChars returns unchanged data for no special characters`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encoded = SLIPCodec.encodeEscapeChars(data)

        assertEquals(4, encoded.size)
        assertArrayEquals(data, encoded.toByteArray())
    }

    @Test
    fun `encodeEscapeChars escapes FEND correctly`() {
        // 0xC0 should become 0xDB 0xDC
        val data = byteArrayOf(0x01, 0xC0.toByte(), 0x02)
        val encoded = SLIPCodec.encodeEscapeChars(data)

        assertEquals(4, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])
        assertEquals(0xDB.toByte(), encoded[1])
        assertEquals(0xDC.toByte(), encoded[2])
        assertEquals(0x02.toByte(), encoded[3])
    }

    @Test
    fun `encodeEscapeChars escapes FESC correctly`() {
        // 0xDB should become 0xDB 0xDD
        val data = byteArrayOf(0x01, 0xDB.toByte(), 0x02)
        val encoded = SLIPCodec.encodeEscapeChars(data)

        assertEquals(4, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])
        assertEquals(0xDB.toByte(), encoded[1])
        assertEquals(0xDD.toByte(), encoded[2])
        assertEquals(0x02.toByte(), encoded[3])
    }

    @Test
    fun `encodeEscapeChars handles multiple special characters`() {
        val data = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte())
        val encoded = SLIPCodec.encodeEscapeChars(data)

        // Each special char becomes 2 bytes
        assertEquals(6, encoded.size)
    }

    @Test
    fun `encodeFrame adds FEND delimiters`() {
        val data = byteArrayOf(0x01, 0x02)
        val frame = SLIPCodec.encodeFrame(data)

        // Should start and end with 0xC0
        assertEquals(0xC0.toByte(), frame.first())
        assertEquals(0xC0.toByte(), frame.last())
        assertEquals(4, frame.size)
    }

    @Test
    fun `decodeFrame removes escaping`() {
        // Frame with escaped FEND: 0xDB 0xDC -> 0xC0
        val frame = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDC.toByte(), 0x02, 0xC0.toByte())
        val decoded = SLIPCodec.decodeFrame(frame)

        assertArrayEquals(byteArrayOf(0x01, 0xC0.toByte(), 0x02), decoded)
    }

    @Test
    fun `decodeFrame removes escaped FESC`() {
        // Frame with escaped FESC: 0xDB 0xDD -> 0xDB
        val frame = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDD.toByte(), 0x02, 0xC0.toByte())
        val decoded = SLIPCodec.decodeFrame(frame)

        assertArrayEquals(byteArrayOf(0x01, 0xDB.toByte(), 0x02), decoded)
    }

    @Test
    fun `decodeFrame returns null for invalid escape sequence`() {
        // Invalid escape: 0xDB followed by non-escape byte
        val frame = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0x01, 0xC0.toByte())
        val decoded = SLIPCodec.decodeFrame(frame)

        assertNull(decoded)
    }

    @Test
    fun `decodeFrame returns null for incomplete escape at end`() {
        val frame = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xC0.toByte())
        val decoded = SLIPCodec.decodeFrame(frame)

        assertNull(decoded)
    }

    @Test
    fun `encode and decode roundtrip preserves data`() {
        val original = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0xFF.toByte(), 0x55)
        val encoded = SLIPCodec.encodeFrame(original)
        val decoded = SLIPCodec.decodeFrame(encoded)

        assertArrayEquals(original, decoded)
    }

    @Test
    fun `getFrameEnd returns correct delimiter`() {
        assertEquals(0xC0.toByte(), SLIPCodec.getFrameEnd())
    }

    @Test
    fun `isFrameEnd correctly identifies delimiter`() {
        assertTrue(SLIPCodec.isFrameEnd(0xC0.toByte()))
    }
}

/**
 * Unit tests for SLIP frame parser.
 */
class SLIPFrameParserTest {
    @Test
    fun `parser extracts single frame`() {
        val parser = SLIPFrameParser()
        val frameData = byteArrayOf(0xC0.toByte(), 0x01, 0x02, 0x03, 0xC0.toByte())

        val frames = parser.processBytes(frameData)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), frames[0])
    }

    @Test
    fun `parser extracts multiple frames`() {
        val parser = SLIPFrameParser()
        val data =
            byteArrayOf(
                0xC0.toByte(),
                0x01,
                0x02,
                0xC0.toByte(),
                0xC0.toByte(),
                0x03,
                0x04,
                0xC0.toByte(),
            )

        val frames = parser.processBytes(data)

        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02), frames[0])
        assertArrayEquals(byteArrayOf(0x03, 0x04), frames[1])
    }

    @Test
    fun `parser handles streaming data across multiple calls`() {
        val parser = SLIPFrameParser()

        // First chunk: partial frame
        var frames = parser.processBytes(byteArrayOf(0xC0.toByte(), 0x01, 0x02))
        assertEquals(0, frames.size)

        // Second chunk: completes frame
        frames = parser.processBytes(byteArrayOf(0x03, 0xC0.toByte()))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), frames[0])
    }

    @Test
    fun `parser handles escape sequences`() {
        val parser = SLIPFrameParser()
        // Frame with escaped FEND
        val data = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDC.toByte(), 0x02, 0xC0.toByte())

        val frames = parser.processBytes(data)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01, 0xC0.toByte(), 0x02), frames[0])
    }

    @Test
    fun `parser ignores bytes outside frame`() {
        val parser = SLIPFrameParser()
        val data = byteArrayOf(0x99.toByte(), 0x88.toByte(), 0xC0.toByte(), 0x01, 0xC0.toByte())

        val frames = parser.processBytes(data)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01), frames[0])
    }

    @Test
    fun `reset clears parser state`() {
        val parser = SLIPFrameParser()

        // Start a frame
        parser.processBytes(byteArrayOf(0xC0.toByte(), 0x01, 0x02))

        // Reset
        parser.reset()

        // Start a new frame
        val frames = parser.processBytes(byteArrayOf(0xC0.toByte(), 0x03, 0xC0.toByte()))

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x03), frames[0])
    }

    @Test
    fun `parser skips empty frames`() {
        val parser = SLIPFrameParser()
        // Two consecutive FENDs create an empty frame (should be skipped)
        val data = byteArrayOf(0xC0.toByte(), 0xC0.toByte(), 0x01, 0xC0.toByte())

        val frames = parser.processBytes(data)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x01), frames[0])
    }
}
