package com.lxmf.messenger.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MigrationCryptoTest {
    private val testPassword = "test-password-12345"

    @Test
    fun `encrypt and decrypt round-trip preserves data`() {
        val plaintext = "Hello, World! This is a test payload.".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        val decrypted = MigrationCrypto.decrypt(encrypted, testPassword)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt and decrypt round-trip with large data`() {
        val plaintext = ByteArray(1_000_000) { (it % 256).toByte() }
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        val decrypted = MigrationCrypto.decrypt(encrypted, testPassword)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt and decrypt round-trip with empty data`() {
        val plaintext = ByteArray(0)
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        val decrypted = MigrationCrypto.decrypt(encrypted, testPassword)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = WrongPasswordException::class)
    fun `decrypt with wrong password throws WrongPasswordException`() {
        val plaintext = "Secret data".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        MigrationCrypto.decrypt(encrypted, "wrong-password")
    }

    @Test
    fun `encrypted output starts with version byte`() {
        val encrypted = MigrationCrypto.encrypt("test".toByteArray(), testPassword)
        assertEquals(MigrationCrypto.ENCRYPTED_VERSION, encrypted[0])
    }

    @Test
    fun `encrypted output is larger than plaintext`() {
        val plaintext = "test".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        // Header: 1 (version) + 16 (salt) + 12 (IV) = 29 bytes + GCM tag (16 bytes)
        assertTrue(encrypted.size > plaintext.size + 29)
    }

    @Test
    fun `two encryptions of same data produce different ciphertext`() {
        val plaintext = "same data".toByteArray()
        val encrypted1 = MigrationCrypto.encrypt(plaintext, testPassword)
        val encrypted2 = MigrationCrypto.encrypt(plaintext, testPassword)
        // Different salt and IV each time
        assertFalse(encrypted1.contentEquals(encrypted2))
    }

    @Test
    fun `isEncrypted returns true for encrypted data`() {
        val encrypted = MigrationCrypto.encrypt("test".toByteArray(), testPassword)
        assertTrue(MigrationCrypto.isEncrypted(encrypted))
    }

    @Test
    fun `isEncrypted returns false for ZIP data`() {
        // ZIP magic bytes: PK (0x50 0x4B)
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertFalse(MigrationCrypto.isEncrypted(zipHeader))
    }

    @Test(expected = InvalidExportFileException::class)
    fun `isEncrypted throws for unrecognized format`() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02)
        MigrationCrypto.isEncrypted(garbage)
    }

    @Test(expected = InvalidExportFileException::class)
    fun `isEncrypted throws for empty data`() {
        MigrationCrypto.isEncrypted(ByteArray(0))
    }

    @Test(expected = InvalidExportFileException::class)
    fun `decrypt throws for empty data`() {
        MigrationCrypto.decrypt(ByteArray(0), testPassword)
    }

    @Test(expected = InvalidExportFileException::class)
    fun `decrypt throws for truncated data`() {
        // Version byte + partial salt (too short to be valid)
        val truncated = byteArrayOf(MigrationCrypto.ENCRYPTED_VERSION, 0x01, 0x02)
        MigrationCrypto.decrypt(truncated, testPassword)
    }

    @Test
    fun `encryptFile encrypts file in-place`() {
        val tempFile = File.createTempFile("migration_test", ".columba")
        try {
            val originalContent = "ZIP file content simulation".toByteArray()
            tempFile.writeBytes(originalContent)

            MigrationCrypto.encryptFile(tempFile, testPassword)

            // File should now start with version byte, not original content
            val encryptedContent = tempFile.readBytes()
            assertEquals(MigrationCrypto.ENCRYPTED_VERSION, encryptedContent[0])

            // Decrypt and verify
            val decrypted = MigrationCrypto.decrypt(encryptedContent, testPassword)
            assertArrayEquals(originalContent, decrypted)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `decryptStream returns valid input stream`() {
        val plaintext = "stream test data".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)

        val decryptedStream = MigrationCrypto.decryptStream(
            encrypted.inputStream(),
            testPassword,
        )
        val result = decryptedStream.readBytes()
        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `decrypt works with unicode password`() {
        val plaintext = "data".toByteArray()
        val unicodePassword = "пароль密码パスワード"
        val encrypted = MigrationCrypto.encrypt(plaintext, unicodePassword)
        val decrypted = MigrationCrypto.decrypt(encrypted, unicodePassword)
        assertArrayEquals(plaintext, decrypted)
    }
}
