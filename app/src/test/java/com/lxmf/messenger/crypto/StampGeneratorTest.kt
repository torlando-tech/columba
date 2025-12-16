package com.lxmf.messenger.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StampGenerator.
 *
 * Test vectors are generated from Python LXMF implementation to ensure
 * byte-for-byte compatibility.
 */
class StampGeneratorTest {

    private lateinit var stampGenerator: StampGenerator

    @Before
    fun setUp() {
        stampGenerator = StampGenerator()
    }

    // ==================== MessagePack Tests ====================

    @Test
    fun `packInt encodes 0 correctly`() {
        val result = stampGenerator.packInt(0)
        assertArrayEquals(hexToBytes("00"), result)
    }

    @Test
    fun `packInt encodes 1 correctly`() {
        val result = stampGenerator.packInt(1)
        assertArrayEquals(hexToBytes("01"), result)
    }

    @Test
    fun `packInt encodes 127 correctly`() {
        val result = stampGenerator.packInt(127)
        assertArrayEquals(hexToBytes("7f"), result)
    }

    @Test
    fun `packInt encodes 128 correctly`() {
        val result = stampGenerator.packInt(128)
        assertArrayEquals(hexToBytes("cc80"), result)
    }

    @Test
    fun `packInt encodes 255 correctly`() {
        val result = stampGenerator.packInt(255)
        assertArrayEquals(hexToBytes("ccff"), result)
    }

    @Test
    fun `packInt encodes 256 correctly`() {
        val result = stampGenerator.packInt(256)
        assertArrayEquals(hexToBytes("cd0100"), result)
    }

    @Test
    fun `packInt encodes 1000 correctly`() {
        val result = stampGenerator.packInt(1000)
        assertArrayEquals(hexToBytes("cd03e8"), result)
    }

    // ==================== SHA256 Tests ====================

    @Test
    fun `sha256 produces correct hash`() {
        val input = "test data".toByteArray()
        val expected = hexToBytes("916f0027a575074ce72a331777c3478d6513f786a591bd892da1a577bf2335f9")
        val result = stampGenerator.sha256(input)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `sha256 produces 32 byte output`() {
        val result = stampGenerator.sha256("any input".toByteArray())
        assertEquals(32, result.size)
    }

    // ==================== HKDF Tests ====================

    @Test
    fun `hkdfExpand matches RFC 5869 style test`() {
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = ByteArray(0)
        val expected = hexToBytes("b2a3d45126d31fb6828ef00d76c6d54e9c2bd4785e49c6ad86e327d89d0de9408eeda1cbef2b03f30e05")

        val result = stampGenerator.hkdfExpand(ikm, salt, info, 42)

        assertArrayEquals(expected, result)
    }

    @Test
    fun `hkdfExpand produces correct length output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { it.toByte() }
        val info = ByteArray(0)

        for (length in listOf(16, 32, 64, 128, 256)) {
            val result = stampGenerator.hkdfExpand(ikm, salt, info, length)
            assertEquals(length, result.size)
        }
    }

    // ==================== Workblock Tests ====================

    @Test
    fun `generateWorkblock produces correct size`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val expandRounds = 2

        val workblock = stampGenerator.generateWorkblock(material, expandRounds)

        // Each round produces 256 bytes
        assertEquals(expandRounds * 256, workblock.size)
    }

    @Test
    fun `generateWorkblock matches Python output`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val expandRounds = 2

        // Expected first 64 bytes from Python test
        val expectedFirst64 = hexToBytes(
            "6b4e93e1358f5b1865f30c2e4c4d3e3e5585bc73c4f3bac53c5418f882791463" +
            "8980973daa9d75be40e2e50adc12987364ee078e492fa424c3980cc51579b83b"
        )

        val workblock = stampGenerator.generateWorkblock(material, expandRounds)

        assertArrayEquals(expectedFirst64, workblock.copyOfRange(0, 64))
    }

    @Test
    fun `generateWorkblock is deterministic`() {
        val material = hexToBytes("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val expandRounds = 3

        val workblock1 = stampGenerator.generateWorkblock(material, expandRounds)
        val workblock2 = stampGenerator.generateWorkblock(material, expandRounds)

        assertArrayEquals(workblock1, workblock2)
    }

    // ==================== Stamp Validation Tests ====================

    @Test
    fun `isStampValid returns true for valid stamp`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val workblock = stampGenerator.generateWorkblock(material, 2)

        // Valid stamp from Python test (cost 8, actually has 11 leading zeros)
        val validStamp = hexToBytes("52c8508b7f8dfdd984e110d489e3c5535c0583005f1ebb08f63ca7c36c6c5882")
        val stampCost = 8

        assertTrue(stampGenerator.isStampValid(validStamp, stampCost, workblock))
    }

    @Test
    fun `isStampValid returns false for invalid stamp`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val workblock = stampGenerator.generateWorkblock(material, 2)

        // Invalid stamp (all 0xff - very unlikely to be valid)
        val invalidStamp = hexToBytes("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        val stampCost = 8

        assertFalse(stampGenerator.isStampValid(invalidStamp, stampCost, workblock))
    }

    @Test
    fun `isStampValid respects stamp cost`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val workblock = stampGenerator.generateWorkblock(material, 2)

        // This stamp has 11 leading zeros
        val stamp = hexToBytes("52c8508b7f8dfdd984e110d489e3c5535c0583005f1ebb08f63ca7c36c6c5882")

        // Should be valid for cost <= 11
        assertTrue(stampGenerator.isStampValid(stamp, 8, workblock))
        assertTrue(stampGenerator.isStampValid(stamp, 10, workblock))
        assertTrue(stampGenerator.isStampValid(stamp, 11, workblock))

        // Should be invalid for cost > 11
        assertFalse(stampGenerator.isStampValid(stamp, 12, workblock))
        assertFalse(stampGenerator.isStampValid(stamp, 16, workblock))
    }

    // ==================== Stamp Value Tests ====================

    @Test
    fun `stampValue returns correct leading zeros count`() {
        val material = hexToBytes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        val workblock = stampGenerator.generateWorkblock(material, 2)

        // This stamp has hash starting with 001c... = 11 leading zeros
        val stamp = hexToBytes("52c8508b7f8dfdd984e110d489e3c5535c0583005f1ebb08f63ca7c36c6c5882")

        val value = stampGenerator.stampValue(workblock, stamp)

        assertEquals(11, value)
    }

    // ==================== Stamp Generation Tests ====================

    @Test
    fun `generateStamp produces valid stamp`() = runTest {
        // Use small workblock for fast test
        val material = hexToBytes("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val workblock = stampGenerator.generateWorkblock(material, 2)
        val stampCost = 8 // Relatively easy to find

        val result = stampGenerator.generateStamp(workblock, stampCost)

        assertNotNull(result.stamp)
        assertTrue(stampGenerator.isStampValid(result.stamp!!, stampCost, workblock))
        assertTrue(result.value >= stampCost)
        assertTrue(result.rounds > 0)
    }

    @Test
    fun `generateStampWithWorkblock produces valid stamp`() = runTest {
        val messageId = hexToBytes("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val stampCost = 8
        val expandRounds = 2 // Small for fast test

        val result = stampGenerator.generateStampWithWorkblock(messageId, stampCost, expandRounds)

        assertNotNull(result.stamp)

        // Verify the stamp is valid against regenerated workblock
        val workblock = stampGenerator.generateWorkblock(messageId, expandRounds)
        assertTrue(stampGenerator.isStampValid(result.stamp!!, stampCost, workblock))
    }

    // ==================== Helper Functions ====================

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
