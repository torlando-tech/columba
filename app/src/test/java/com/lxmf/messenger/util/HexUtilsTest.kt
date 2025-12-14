package com.lxmf.messenger.util

import com.lxmf.messenger.util.HexUtils.hexStringToByteArray
import com.lxmf.messenger.util.HexUtils.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for HexUtils extension functions.
 */
class HexUtilsTest {
    // ========== toHexString Tests ==========

    @Test
    fun `toHexString converts bytes correctly`() {
        val bytes = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        assertEquals("1234abcd", bytes.toHexString())
    }

    @Test
    fun `toHexString handles empty array`() {
        val bytes = byteArrayOf()
        assertEquals("", bytes.toHexString())
    }

    @Test
    fun `toHexString handles single byte`() {
        assertEquals("00", byteArrayOf(0x00).toHexString())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHexString())
        assertEquals("0a", byteArrayOf(0x0A).toHexString())
    }

    @Test
    fun `toHexString pads single digit bytes with zero`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x0F)
        assertEquals("01020f", bytes.toHexString())
    }

    @Test
    fun `toHexString produces lowercase output`() {
        val bytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        assertEquals("abcdef", bytes.toHexString())
    }

    // ========== hexStringToByteArray Tests ==========

    @Test
    fun `hexStringToByteArray parses lowercase hex`() {
        val hex = "1234abcd"
        val expected = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        assertArrayEquals(expected, hex.hexStringToByteArray())
    }

    @Test
    fun `hexStringToByteArray parses uppercase hex`() {
        val hex = "1234ABCD"
        val expected = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        assertArrayEquals(expected, hex.hexStringToByteArray())
    }

    @Test
    fun `hexStringToByteArray parses mixed case hex`() {
        val hex = "AbCd12EF"
        val expected = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0xEF.toByte())
        assertArrayEquals(expected, hex.hexStringToByteArray())
    }

    @Test
    fun `hexStringToByteArray handles empty string`() {
        val hex = ""
        assertArrayEquals(byteArrayOf(), hex.hexStringToByteArray())
    }

    @Test
    fun `hexStringToByteArray handles single byte`() {
        assertEquals(0x00.toByte(), "00".hexStringToByteArray()[0])
        assertEquals(0xFF.toByte(), "ff".hexStringToByteArray()[0])
        assertEquals(0x0A.toByte(), "0a".hexStringToByteArray()[0])
    }

    @Test(expected = NumberFormatException::class)
    fun `hexStringToByteArray throws on invalid hex character`() {
        "12gh".hexStringToByteArray()
    }

    // ========== Round-trip Tests ==========

    @Test
    fun `round trip conversion preserves data`() {
        val original = byteArrayOf(0x00, 0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xFF.toByte())
        val hex = original.toHexString()
        val restored = hex.hexStringToByteArray()
        assertArrayEquals(original, restored)
    }

    @Test
    fun `round trip with identity hash length`() {
        // Typical Reticulum identity hash is 32 bytes (64 hex chars)
        val identityHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val bytes = identityHash.hexStringToByteArray()
        assertEquals(32, bytes.size)
        assertEquals(identityHash, bytes.toHexString())
    }
}
