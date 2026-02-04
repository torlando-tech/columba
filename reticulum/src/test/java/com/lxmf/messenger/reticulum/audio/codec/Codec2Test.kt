package com.lxmf.messenger.reticulum.audio.codec

import org.junit.Assert.*
import org.junit.Test

/**
 * Configuration tests for Codec2 codec.
 *
 * Tests verify mode parameters and header byte mapping match Python LXST specification.
 * Note: Full encode/decode tests require instrumented tests (actual device) due to native JNI.
 * These tests verify the configuration logic and mode constants only.
 */
class Codec2Test {

    @Test
    fun `mode constants match Python LXST values`() {
        assertEquals(700, Codec2.CODEC2_700C)
        assertEquals(1200, Codec2.CODEC2_1200)
        assertEquals(1300, Codec2.CODEC2_1300)
        assertEquals(1400, Codec2.CODEC2_1400)
        assertEquals(1600, Codec2.CODEC2_1600)
        assertEquals(2400, Codec2.CODEC2_2400)
        assertEquals(3200, Codec2.CODEC2_3200)
    }

    @Test
    fun `sample rates match Python LXST`() {
        assertEquals(8000, Codec2.INPUT_RATE)
        assertEquals(8000, Codec2.OUTPUT_RATE)
    }

    @Test
    fun `frame quanta matches Python LXST`() {
        assertEquals(40f, Codec2.FRAME_QUANTA_MS, 0.001f)
    }

    @Test
    fun `mode header mapping is correct`() {
        // Verify all 7 modes have header byte mappings
        assertEquals(0x00.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_700C])
        assertEquals(0x01.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_1200])
        assertEquals(0x02.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_1300])
        assertEquals(0x03.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_1400])
        assertEquals(0x04.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_1600])
        assertEquals(0x05.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_2400])
        assertEquals(0x06.toByte(), Codec2.MODE_HEADERS[Codec2.CODEC2_3200])
    }

    @Test
    fun `header to mode reverse mapping is correct`() {
        // Verify reverse mapping (header byte -> mode)
        assertEquals(Codec2.CODEC2_700C, Codec2.HEADER_MODES[0x00.toByte()])
        assertEquals(Codec2.CODEC2_1200, Codec2.HEADER_MODES[0x01.toByte()])
        assertEquals(Codec2.CODEC2_1300, Codec2.HEADER_MODES[0x02.toByte()])
        assertEquals(Codec2.CODEC2_1400, Codec2.HEADER_MODES[0x03.toByte()])
        assertEquals(Codec2.CODEC2_1600, Codec2.HEADER_MODES[0x04.toByte()])
        assertEquals(Codec2.CODEC2_2400, Codec2.HEADER_MODES[0x05.toByte()])
        assertEquals(Codec2.CODEC2_3200, Codec2.HEADER_MODES[0x06.toByte()])
    }

    @Test
    fun `all mode constants are defined`() {
        // Verify all 7 mode constants exist and have expected values
        // This validates the configuration without requiring JNI
        val modes = listOf(
            Codec2.CODEC2_700C to 700,
            Codec2.CODEC2_1200 to 1200,
            Codec2.CODEC2_1300 to 1300,
            Codec2.CODEC2_1400 to 1400,
            Codec2.CODEC2_1600 to 1600,
            Codec2.CODEC2_2400 to 2400,
            Codec2.CODEC2_3200 to 3200
        )

        modes.forEach { (constant, expectedValue) ->
            assertEquals("Mode constant should have correct value", expectedValue, constant)
            assertNotNull("Mode should have header mapping", Codec2.MODE_HEADERS[constant])
        }
    }

    @Test
    fun `mode header bytes are sequential from 0x00 to 0x06`() {
        // Verify header bytes are 0x00 through 0x06 for the 7 modes
        val headerValues = Codec2.MODE_HEADERS.values.sorted()
        assertEquals(7, headerValues.size)
        assertEquals(0x00.toByte(), headerValues[0])
        assertEquals(0x01.toByte(), headerValues[1])
        assertEquals(0x02.toByte(), headerValues[2])
        assertEquals(0x03.toByte(), headerValues[3])
        assertEquals(0x04.toByte(), headerValues[4])
        assertEquals(0x05.toByte(), headerValues[5])
        assertEquals(0x06.toByte(), headerValues[6])
    }

    @Test
    fun `bidirectional header mode mapping is consistent`() {
        // Verify MODE_HEADERS and HEADER_MODES are bidirectional inverses
        Codec2.MODE_HEADERS.forEach { (mode, header) ->
            assertEquals("Header should map back to original mode", mode, Codec2.HEADER_MODES[header])
        }

        Codec2.HEADER_MODES.forEach { (header, mode) ->
            assertEquals("Mode should map back to original header", header, Codec2.MODE_HEADERS[mode])
        }
    }

    @Test
    fun `all modes have unique header bytes`() {
        // Verify no two modes share the same header byte
        val headerBytes = Codec2.MODE_HEADERS.values
        assertEquals("All header bytes should be unique", headerBytes.size, headerBytes.toSet().size)
    }
}
