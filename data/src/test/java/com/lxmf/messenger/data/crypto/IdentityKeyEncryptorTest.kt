package com.lxmf.messenger.data.crypto

import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom

/**
 * Unit tests for IdentityKeyEncryptor.
 *
 * Note: Tests that require Android Keystore (device key encryption/decryption)
 * are skipped in Robolectric and should be run as instrumented tests.
 * This test file focuses on password-based operations and utility methods
 * that don't require the Keystore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class IdentityKeyEncryptorTest {
    private lateinit var encryptor: IdentityKeyEncryptor

    companion object {
        // Standard 64-byte identity key for testing
        private val TEST_KEY_DATA = ByteArray(64) { it.toByte() }
        private val TEST_PASSWORD = "testPassword123!".toCharArray()
    }

    @Before
    fun setUp() {
        encryptor = IdentityKeyEncryptor()
    }

    // ==================== Password Verification Tests ====================
    // These tests don't require Android Keystore

    @Test
    fun `password verification hash is deterministic with same salt`() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val hash1 = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt)
        val hash2 = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt)

        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun `password verification hash differs with different salt`() {
        val salt1 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val salt2 = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val hash1 = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt1)
        val hash2 = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt2)

        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `verifyPassword returns true for correct password`() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt)

        assertTrue(encryptor.verifyPassword(TEST_PASSWORD, salt, hash))
    }

    @Test
    fun `verifyPassword returns false for wrong password`() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt)
        val wrongPassword = "wrongPassword!".toCharArray()

        assertFalse(encryptor.verifyPassword(wrongPassword, salt, hash))
    }

    @Test
    fun `password verification hash has expected size`() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = encryptor.createPasswordVerificationHash(TEST_PASSWORD, salt)

        assertEquals(32, hash.size)
    }

    // ==================== Export Encryption Tests ====================
    // These use password-based encryption without device key

    @Test
    fun `encryptForExport produces portable encrypted data`() {
        val encrypted = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)

        assertNotNull(encrypted)
        assertTrue(encrypted.isNotEmpty())
    }

    @Test
    fun `encryptForExport and decryptFromExport round trip`() {
        val encrypted = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)
        val decrypted = encryptor.decryptFromExport(encrypted, TEST_PASSWORD)

        assertArrayEquals(TEST_KEY_DATA, decrypted)
    }

    @Test
    fun `encryptForExport produces different ciphertext each time due to random IV`() {
        val encrypted1 = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)
        val encrypted2 = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)

        // Due to random IV and salt, encrypted data should be different
        assertFalse(encrypted1.contentEquals(encrypted2))
    }

    @Test
    fun `export encryption is not device-bound`() {
        // This test verifies the export format doesn't include device key version prefix
        val encrypted = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)

        // Export format: [32-byte salt][12-byte IV][encrypted data][16-byte auth tag]
        // It should NOT start with 0x01 or 0x02 version bytes
        assertTrue(
            encrypted[0] != IdentityKeyEncryptor.VERSION_DEVICE_ONLY &&
                encrypted[0] != IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD,
        )
    }

    @Test(expected = WrongPasswordException::class)
    fun `decryptFromExport throws on wrong password`() {
        val encrypted = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)
        val wrongPassword = "wrongPassword!".toCharArray()
        encryptor.decryptFromExport(encrypted, wrongPassword)
    }

    @Test(expected = CorruptedKeyException::class)
    fun `decryptFromExport throws on truncated data`() {
        encryptor.decryptFromExport(ByteArray(10), TEST_PASSWORD)
    }

    @Test(expected = CorruptedKeyException::class)
    fun `decryptFromExport throws on empty data`() {
        encryptor.decryptFromExport(ByteArray(0), TEST_PASSWORD)
    }

    @Test(expected = WrongPasswordException::class)
    fun `decryptFromExport throws on tampered data`() {
        val encrypted = encryptor.encryptForExport(TEST_KEY_DATA, TEST_PASSWORD)
        // Tamper with the last byte (part of auth tag)
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()
        encryptor.decryptFromExport(encrypted, TEST_PASSWORD)
    }

    // ==================== Version Detection Tests ====================

    @Test
    fun `getEncryptionVersion returns -1 for empty data`() {
        assertEquals(-1, encryptor.getEncryptionVersion(ByteArray(0)))
    }

    @Test
    fun `getEncryptionVersion returns version for device-only prefix`() {
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_ONLY) + ByteArray(100)
        assertEquals(IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(), encryptor.getEncryptionVersion(data))
    }

    @Test
    fun `getEncryptionVersion returns version for password prefix`() {
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD) + ByteArray(100)
        assertEquals(IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(), encryptor.getEncryptionVersion(data))
    }

    @Test
    fun `requiresPassword returns false for device-only prefix`() {
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_ONLY) + ByteArray(100)
        assertFalse(encryptor.requiresPassword(data))
    }

    @Test
    fun `requiresPassword returns true for password prefix`() {
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD) + ByteArray(100)
        assertTrue(encryptor.requiresPassword(data))
    }

    @Test
    fun `requiresPassword returns false for empty data`() {
        assertFalse(encryptor.requiresPassword(ByteArray(0)))
    }

    // ==================== Salt Extraction Tests ====================

    @Test
    fun `extractSalt returns null for device-only prefix`() {
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_ONLY) + ByteArray(100)
        assertEquals(null, encryptor.extractSalt(data))
    }

    @Test
    fun `extractSalt returns null for empty data`() {
        val salt = encryptor.extractSalt(ByteArray(0))
        assertEquals(null, salt)
    }

    @Test
    fun `extractSalt returns null for data too short for salt`() {
        // Password version needs at least 1 (version) + 32 (salt) = 33 bytes
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD) + ByteArray(10)
        assertEquals(null, encryptor.extractSalt(data))
    }

    @Test
    fun `extractSalt returns salt from password protected data format`() {
        // Create data with password prefix and salt
        val salt = ByteArray(32) { it.toByte() }
        val data = byteArrayOf(IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD) + salt + ByteArray(50)

        val extractedSalt = encryptor.extractSalt(data)

        assertNotNull(extractedSalt)
        assertArrayEquals(salt, extractedSalt)
    }

    // ==================== Secure Wipe Tests ====================

    @Test
    fun `secureWipe zeroes out byte array`() {
        val data = ByteArray(64) { 0xFF.toByte() }

        encryptor.secureWipe(data)

        // After wiping, array should be all zeros
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `secureWipe handles empty array`() {
        val data = ByteArray(0)
        encryptor.secureWipe(data) // Should not throw
        assertTrue(data.isEmpty())
    }

    // ==================== Input Validation Tests ====================

    @Test(expected = IllegalArgumentException::class)
    fun `encryptForExport rejects wrong key size`() {
        val wrongSizeKey = ByteArray(32) // Should be 64 bytes
        encryptor.encryptForExport(wrongSizeKey, TEST_PASSWORD)
    }
}
