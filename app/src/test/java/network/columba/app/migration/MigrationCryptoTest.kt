package network.columba.app.migration

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

    /**
     * Build a legacy 0x02 (whole-file GCM) export using raw JCE, independent
     * of MigrationCrypto, to prove the importer still reads pre-0x03 files.
     *
     * Layout: [0x02][16-byte salt][12-byte IV][GCM ciphertext + 16-byte tag]
     */
    private fun buildLegacy0x02(plaintext: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val keySpec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt,
            600_000,
            256,
        )
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.encoded, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(MigrationCrypto.LEGACY_ENCRYPTED_VERSION) +
            salt +
            iv +
            ciphertext
    }

    @Test
    fun `legacy 0x02 file decrypts and matches plaintext`() {
        val plaintext = "legacy payload from an older app version".toByteArray()
        val legacy = buildLegacy0x02(plaintext, testPassword)
        assertEquals(MigrationCrypto.LEGACY_ENCRYPTED_VERSION, legacy[0])
        val decrypted = MigrationCrypto.decrypt(legacy, testPassword)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `legacy 0x02 file is detected as encrypted`() {
        val legacy = buildLegacy0x02("x".toByteArray(), testPassword)
        assertTrue(MigrationCrypto.isEncrypted(legacy))
    }

    @Test(expected = WrongPasswordException::class)
    fun `legacy 0x02 file with wrong password throws WrongPasswordException`() {
        val legacy = buildLegacy0x02("x".toByteArray(), testPassword)
        MigrationCrypto.decrypt(legacy, "wrong-password")
    }

    // region 0x03 authenticated-framing regression tests
    //
    // The 0x03 format derives each chunk IV from (base IV, index) and binds
    // the fixed header + chunk index into every chunk's GCM AAD. These tests
    // prove the framing cannot be tampered with, reordered, truncated, or
    // fed a malformed chunk size.

    private fun assertDecryptFails(encrypted: ByteArray) {
        // Any decryption failure (WrongPasswordException or
        // InvalidExportFileException) is acceptable; only a *successful*
        // decrypt means the assertion has failed.
        val result = runCatching { MigrationCrypto.decrypt(encrypted, testPassword) }
        assertTrue("Expected decryption to fail, but it succeeded", result.isFailure)
    }

    @Test
    fun `round-trip spans multiple 8 MiB chunks`() {
        // Force two chunks: 8 MiB + 1 MiB of plaintext.
        val size = MigrationCrypto.CHUNK_SIZE_BYTES + 1024 * 1024
        val plaintext = ByteArray(size) { (it % 251).toByte() }
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword)
        val decrypted = MigrationCrypto.decrypt(encrypted, testPassword)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `tampering the base IV in the header is rejected`() {
        val plaintext = "framing integrity payload 0123456789".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword).copyOf()
        // Flip a byte of the 12-byte base IV (header offset 17..28).
        encrypted[17] = (encrypted[17].toInt() xor 0x01).toByte()
        assertDecryptFails(encrypted)
    }

    @Test
    fun `tampering a ciphertext byte is rejected`() {
        val plaintext = "tamper detection payload 0123456789".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword).copyOf()
        // The single chunk's ciphertext starts right after the 45-byte header
        // and the 8-byte chunk-index record: offset 45 + 8.
        encrypted[45 + 8 + 1] = (encrypted[45 + 8 + 1].toInt() xor 0x01).toByte()
        assertDecryptFails(encrypted)
    }

    @Test
    fun `appending trailing data after the final chunk is rejected`() {
        val plaintext = "trailing data check payload".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword).copyOf()
        // The declared total length is unchanged, so every chunk authenticates;
        // the extra trailing byte must trip the exhaustion check.
        val withTrailing = encrypted + byteArrayOf(0x00, 0x01, 0x02)
        assertDecryptFails(withTrailing)
    }

    @Test
    fun `negative total length in the header is rejected`() {
        val plaintext = "length sanity payload".toByteArray()
        val encrypted = MigrationCrypto.encrypt(plaintext, testPassword).copyOf()
        // totalLength is the last 8 bytes of the 45-byte header; set it to
        // -1 (all 0xFF) which is out of range.
        for (i in 45 - 8 until 45) encrypted[i] = 0xFF.toByte()
        assertDecryptFails(encrypted)
    }

    @Test
    fun `zero chunk size with non-empty total length is rejected`() {
        // Craft a 45-byte 0x03 header: chunkSize = 0, totalLength = 100.
        val header = ByteArray(45)
        header[0] = MigrationCrypto.ENCRYPTED_VERSION
        // salt (16) and base IV (12) can be zero for this structural check.
        writeLong(header, 29, 0L) // chunkSize = 0
        writeLong(header, 37, 100L) // totalLength = 100 (non-zero)
        assertDecryptFails(header)
    }

    /** Big-endian long writer used only to craft malformed test headers. */
    private fun writeLong(buf: ByteArray, offset: Int, value: Long) {
        for (i in 7 downTo 0) {
            buf[offset + (7 - i)] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    // endregion
}
