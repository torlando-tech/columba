package com.lxmf.messenger.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Base32 encoder/decoder.
 *
 * Tests cover RFC 4648 compliance, round-trip encoding, identity key detection,
 * and edge cases relevant to Sideband interoperability.
 */
class Base32Test {
    // ==================== Encode Tests ====================

    @Test
    fun `encode empty byte array returns empty string`() {
        assertEquals("", Base32.encode(ByteArray(0)))
    }

    @Test
    fun `encode RFC 4648 test vectors`() {
        // RFC 4648 Section 10 test vectors
        assertEquals("MY======", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ====", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6===", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ=", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI======", Base32.encode("foobar".toByteArray()))
    }

    // ==================== Decode Tests ====================

    @Test
    fun `decode empty string returns empty byte array`() {
        assertArrayEquals(ByteArray(0), Base32.decode(""))
    }

    @Test
    fun `decode RFC 4648 test vectors`() {
        assertArrayEquals("f".toByteArray(), Base32.decode("MY======"))
        assertArrayEquals("fo".toByteArray(), Base32.decode("MZXQ===="))
        assertArrayEquals("foo".toByteArray(), Base32.decode("MZXW6==="))
        assertArrayEquals("foob".toByteArray(), Base32.decode("MZXW6YQ="))
        assertArrayEquals("fooba".toByteArray(), Base32.decode("MZXW6YTB"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
    }

    @Test
    fun `decode handles lowercase input`() {
        assertArrayEquals("foo".toByteArray(), Base32.decode("mzxw6==="))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw6ytboi======"))
    }

    @Test
    fun `decode handles input without padding`() {
        assertArrayEquals("f".toByteArray(), Base32.decode("MY"))
        assertArrayEquals("foo".toByteArray(), Base32.decode("MZXW6"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode throws on invalid characters`() {
        Base32.decode("INVALID!")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode throws on digit 0`() {
        // 0 and 1 are not valid Base32 characters (only 2-7 are used)
        Base32.decode("MY0=====")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode throws on digit 1`() {
        Base32.decode("MY1=====")
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `encode then decode is identity for various lengths`() {
        for (len in 0..130) {
            val original = ByteArray(len) { (it % 256).toByte() }
            val encoded = Base32.encode(original)
            val decoded = Base32.decode(encoded)
            assertArrayEquals("Round-trip failed for length $len", original, decoded)
        }
    }

    @Test
    fun `encode then decode 64-byte identity key`() {
        // Simulate a Reticulum identity key (32-byte signing + 32-byte encryption)
        val identityKey = ByteArray(64) { (it * 3 + 17).toByte() }
        val encoded = Base32.encode(identityKey)
        val decoded = Base32.decode(encoded)
        assertArrayEquals(identityKey, decoded)
    }

    @Test
    fun `encode 64-byte key produces expected length`() {
        // 64 bytes * 8 bits / 5 bits per char = 102.4 chars, rounded to 104 with padding
        val identityKey = ByteArray(64) { 0 }
        val encoded = Base32.encode(identityKey)
        // 104 chars padded to next multiple of 8 = 104
        assertEquals(104, encoded.length)
    }

    // ==================== isIdentityKey Tests ====================

    @Test
    fun `isIdentityKey returns true for valid 64-byte key`() {
        val key = ByteArray(64) { (it * 7).toByte() }
        val encoded = Base32.encode(key)
        assertTrue(Base32.isIdentityKey(encoded))
    }

    @Test
    fun `isIdentityKey returns true with leading and trailing whitespace`() {
        val key = ByteArray(64) { (it * 7).toByte() }
        val encoded = Base32.encode(key)
        assertTrue(Base32.isIdentityKey("  $encoded  "))
        assertTrue(Base32.isIdentityKey("\n$encoded\n"))
    }

    @Test
    fun `isIdentityKey returns false for shorter key`() {
        val shortKey = ByteArray(32) { it.toByte() }
        val encoded = Base32.encode(shortKey)
        assertFalse(Base32.isIdentityKey(encoded))
    }

    @Test
    fun `isIdentityKey returns false for longer key`() {
        val longKey = ByteArray(128) { it.toByte() }
        val encoded = Base32.encode(longKey)
        assertFalse(Base32.isIdentityKey(encoded))
    }

    @Test
    fun `isIdentityKey returns false for regular text`() {
        assertFalse(Base32.isIdentityKey("Hello, World!"))
        assertFalse(Base32.isIdentityKey("This is just a normal message"))
        assertFalse(Base32.isIdentityKey(""))
    }

    @Test
    fun `isIdentityKey returns false for invalid Base32`() {
        assertFalse(Base32.isIdentityKey("not-base32!@#\$%"))
    }

    @Test
    fun `isIdentityKey handles lowercase Base32`() {
        val key = ByteArray(64) { (it * 11).toByte() }
        val encoded = Base32.encode(key).lowercase()
        assertTrue(Base32.isIdentityKey(encoded))
    }
}
