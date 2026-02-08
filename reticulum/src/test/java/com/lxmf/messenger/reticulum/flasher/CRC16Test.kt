package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for CRC16 implementation.
 *
 * These tests verify the CRC16-CCITT algorithm matches the Nordic DFU bootloader's
 * expected values.
 */
class CRC16Test {
    @Test
    fun `calculate CRC16 for empty data returns initial value`() {
        val result = CRC16.calculate(ByteArray(0))
        assertEquals(0xFFFF, result)
    }

    @Test
    fun `calculate CRC16 for single byte`() {
        // Known test vector: single byte 0x00 with initial 0xFFFF
        val result = CRC16.calculate(byteArrayOf(0x00))
        // The CRC algorithm used is the Nordic variant
        assertEquals(0xE1F0, result)
    }

    @Test
    fun `calculate CRC16 for test string`() {
        // Test with "123456789" which is a standard CRC test vector
        val testData = "123456789".toByteArray(Charsets.US_ASCII)
        val result = CRC16.calculate(testData)
        // Nordic's CRC16 variant produces this value for "123456789"
        assertEquals(0x29B1, result)
    }

    @Test
    fun `calculate CRC16 with incremental updates`() {
        val fullData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        // Calculate in one shot
        val oneShot = CRC16.calculate(fullData)

        // Calculate incrementally
        val part1 = CRC16.calculate(byteArrayOf(0x01, 0x02))
        val incremental = CRC16.calculate(byteArrayOf(0x03, 0x04, 0x05), part1)

        assertEquals(oneShot, incremental)
    }

    @Test
    fun `calculate CRC16 from list produces same result as byte array`() {
        val data = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val dataList = data.toList()

        val crcArray = CRC16.calculate(data)
        val crcList = CRC16.calculate(dataList)

        assertEquals(crcArray, crcList)
    }

    @Test
    fun `appendCrc adds correct CRC bytes`() {
        val data = listOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte())
        val withCrc = CRC16.appendCrc(data)

        assertEquals(5, withCrc.size)

        // Verify CRC bytes are correct (little-endian)
        val expectedCrc = CRC16.calculate(data)
        assertEquals((expectedCrc and 0xFF).toByte(), withCrc[3])
        assertEquals(((expectedCrc shr 8) and 0xFF).toByte(), withCrc[4])
    }

    @Test
    fun `calculate CRC16 for DFU packet header`() {
        // Test with a typical DFU packet header structure
        val header =
            byteArrayOf(
                0x09,
                0xEE.toByte(),
                0x00,
                0xF0.toByte(), // SLIP header
                0x03,
                0x00,
                0x00,
                0x00, // DFU_START_PACKET
            )
        val result = CRC16.calculate(header)
        // Just verify it produces a valid 16-bit value
        assert(result in 0..0xFFFF)
    }

    @Test
    fun `CRC16 is deterministic`() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

        val result1 = CRC16.calculate(data)
        val result2 = CRC16.calculate(data)
        val result3 = CRC16.calculate(data)

        assertEquals(result1, result2)
        assertEquals(result2, result3)
    }
}
