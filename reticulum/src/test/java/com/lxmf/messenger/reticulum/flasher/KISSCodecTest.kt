package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for KISS codec implementation.
 */
class KISSCodecTest {
    @Test
    fun `createFrame adds FEND delimiters and command byte`() {
        val frame = KISSCodec.createFrame(0x08, byteArrayOf(0x73))

        assertEquals(0xC0.toByte(), frame.first())
        assertEquals(0xC0.toByte(), frame.last())
        assertEquals(0x08.toByte(), frame[1]) // Command byte
        assertEquals(0x73.toByte(), frame[2]) // Data
    }

    @Test
    fun `createFrame with empty data`() {
        val frame = KISSCodec.createFrame(0x55)

        assertEquals(3, frame.size) // FEND + command + FEND
        assertEquals(0xC0.toByte(), frame[0])
        assertEquals(0x55.toByte(), frame[1])
        assertEquals(0xC0.toByte(), frame[2])
    }

    @Test
    fun `createFrame escapes FEND in data`() {
        val frame = KISSCodec.createFrame(0x01, byteArrayOf(0xC0.toByte()))

        // FEND should be escaped as FESC TFEND (0xDB 0xDC)
        assertEquals(5, frame.size)
        assertEquals(0xC0.toByte(), frame[0]) // Start FEND
        assertEquals(0x01.toByte(), frame[1]) // Command
        assertEquals(0xDB.toByte(), frame[2]) // FESC
        assertEquals(0xDC.toByte(), frame[3]) // TFEND
        assertEquals(0xC0.toByte(), frame[4]) // End FEND
    }

    @Test
    fun `createFrame escapes FESC in command`() {
        val frame = KISSCodec.createFrame(0xDB.toByte())

        // FESC in command should be escaped
        assertEquals(4, frame.size)
        assertEquals(0xDB.toByte(), frame[1]) // FESC
        assertEquals(0xDD.toByte(), frame[2]) // TFESC
    }

    @Test
    fun `decodeFrame extracts command and data`() {
        val frame = byteArrayOf(0xC0.toByte(), 0x08, 0x73, 0xC0.toByte())
        val result = KISSCodec.decodeFrame(frame)

        assertNotNull(result)
        assertEquals(0x08.toByte(), result!!.first)
        assertArrayEquals(byteArrayOf(0x73), result.second)
    }

    @Test
    fun `decodeFrame handles escape sequences`() {
        // Frame with escaped FEND in data
        val frame =
            byteArrayOf(
                0xC0.toByte(),
                0x01,
                0xDB.toByte(),
                0xDC.toByte(), // Escaped FEND
                0xC0.toByte(),
            )
        val result = KISSCodec.decodeFrame(frame)

        assertNotNull(result)
        assertEquals(0x01.toByte(), result!!.first)
        assertArrayEquals(byteArrayOf(0xC0.toByte()), result.second)
    }

    @Test
    fun `decodeFrame returns null for invalid escape`() {
        val frame = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0x01, 0xC0.toByte())
        val result = KISSCodec.decodeFrame(frame)

        assertNull(result)
    }

    @Test
    fun `decodeFrame returns null for empty frame`() {
        val frame = byteArrayOf(0xC0.toByte(), 0xC0.toByte())
        val result = KISSCodec.decodeFrame(frame)

        assertNull(result)
    }

    @Test
    fun `createFrame with list produces same result as byte array`() {
        val dataArray = byteArrayOf(0x01, 0x02, 0x03)
        val dataList = dataArray.toList()

        val frameFromArray = KISSCodec.createFrame(0x10, dataArray)
        val frameFromList = KISSCodec.createFrame(0x10, dataList)

        assertArrayEquals(frameFromArray, frameFromList)
    }

    @Test
    fun `roundtrip encode decode preserves data`() {
        val command: Byte = 0x48
        val data = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0xFF.toByte())

        val frame = KISSCodec.createFrame(command, data)
        val decoded = KISSCodec.decodeFrame(frame)

        assertNotNull(decoded)
        assertEquals(command, decoded!!.first)
        assertArrayEquals(data, decoded.second)
    }

    @Test
    fun `getFrameEnd returns FEND`() {
        assertEquals(0xC0.toByte(), KISSCodec.getFrameEnd())
    }
}

/**
 * Unit tests for KISS frame parser.
 */
class KISSFrameParserTest {
    @Test
    fun `parser extracts command frame`() {
        val parser = KISSFrameParser()
        val frameData = byteArrayOf(0xC0.toByte(), 0x08, 0x73, 0xC0.toByte())

        val frames = parser.processBytes(frameData)

        assertEquals(1, frames.size)
        assertEquals(0x08.toByte(), frames[0].command)
        assertArrayEquals(byteArrayOf(0x73), frames[0].data)
    }

    @Test
    fun `parser handles streaming data`() {
        val parser = KISSFrameParser()

        // First chunk: partial frame
        var frames = parser.processBytes(byteArrayOf(0xC0.toByte(), 0x08))
        assertEquals(0, frames.size)

        // Second chunk: completes frame
        frames = parser.processBytes(byteArrayOf(0x73, 0xC0.toByte()))
        assertEquals(1, frames.size)
        assertEquals(0x08.toByte(), frames[0].command)
    }

    @Test
    fun `parser handles escape sequences in stream`() {
        val parser = KISSFrameParser()

        // Frame with escaped byte split across chunks
        parser.processBytes(byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte()))
        val frames = parser.processBytes(byteArrayOf(0xDC.toByte(), 0xC0.toByte()))

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0xC0.toByte()), frames[0].data)
    }

    @Test
    fun `parser extracts multiple frames`() {
        val parser = KISSFrameParser()
        val data =
            byteArrayOf(
                0xC0.toByte(),
                0x01,
                0x11,
                0xC0.toByte(),
                0xC0.toByte(),
                0x02,
                0x22,
                0xC0.toByte(),
            )

        val frames = parser.processBytes(data)

        assertEquals(2, frames.size)
        assertEquals(0x01.toByte(), frames[0].command)
        assertEquals(0x02.toByte(), frames[1].command)
    }

    @Test
    fun `reset clears parser state`() {
        val parser = KISSFrameParser()

        // Start a frame
        parser.processBytes(byteArrayOf(0xC0.toByte(), 0x01, 0x02))

        // Reset
        parser.reset()

        // New frame should work correctly
        val frames = parser.processBytes(byteArrayOf(0xC0.toByte(), 0x03, 0xC0.toByte()))

        assertEquals(1, frames.size)
        assertEquals(0x03.toByte(), frames[0].command)
    }

    @Test
    fun `KISSFrame equals and hashCode work correctly`() {
        val frame1 = KISSFrameParser.KISSFrame(0x01, byteArrayOf(0x02, 0x03))
        val frame2 = KISSFrameParser.KISSFrame(0x01, byteArrayOf(0x02, 0x03))
        val frame3 = KISSFrameParser.KISSFrame(0x01, byteArrayOf(0x02, 0x04))

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
        assert(frame1 != frame3)
    }
}
