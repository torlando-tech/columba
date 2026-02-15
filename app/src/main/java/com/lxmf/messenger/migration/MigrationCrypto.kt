package com.lxmf.messenger.migration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles encryption and decryption of migration export files.
 *
 * File format (encrypted):
 * ```
 * [1 byte:  version (0x02)]
 * [16 bytes: PBKDF2 salt]
 * [12 bytes: AES-GCM IV/nonce]
 * [N bytes:  AES-256-GCM ciphertext (includes 16-byte auth tag)]
 * ```
 *
 * Unencrypted (legacy) files start with the ZIP magic bytes (0x50 0x4B)
 * and are detected automatically during import.
 */
object MigrationCrypto {
    /** Version byte written at the start of encrypted export files. */
    const val ENCRYPTED_VERSION: Byte = 0x02

    /** First two bytes of a ZIP file (PK). */
    private const val ZIP_MAGIC_BYTE_1: Byte = 0x50 // 'P'
    private const val ZIP_MAGIC_BYTE_2: Byte = 0x4B // 'K'

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /** Minimum password length enforced at the UI layer. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * Encrypt a plaintext ZIP file in-place, replacing it with the encrypted format.
     *
     * @param plaintextZip the ZIP file to encrypt
     * @param password the user-chosen password
     * @return the same [File] reference, now containing encrypted data
     */
    fun encryptFile(plaintextZip: File, password: String): File {
        val plaintext = plaintextZip.readBytes()
        val encrypted = encrypt(plaintext, password)
        plaintextZip.writeBytes(encrypted)
        return plaintextZip
    }

    /**
     * Encrypt raw bytes with AES-256-GCM using a password-derived key.
     *
     * @return the full encrypted payload including header, salt, IV, and ciphertext
     */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val random = SecureRandom()

        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArrayOutputStream().use { out ->
            out.write(ENCRYPTED_VERSION.toInt())
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
            out.toByteArray()
        }
    }

    /**
     * Decrypt an encrypted export payload.
     *
     * @param encrypted the full encrypted payload (header + salt + IV + ciphertext)
     * @param password the user-provided password
     * @return the decrypted ZIP bytes
     * @throws WrongPasswordException if the password is incorrect (GCM auth tag mismatch)
     * @throws InvalidExportFileException if the file format is not recognized
     */
    @Suppress("ThrowsCount")
    fun decrypt(encrypted: ByteArray, password: String): ByteArray {
        if (encrypted.isEmpty()) {
            throw InvalidExportFileException("Export file is empty")
        }

        if (encrypted[0] != ENCRYPTED_VERSION) {
            throw InvalidExportFileException(
                "Unrecognized export format (version byte: 0x${
                    String.format(java.util.Locale.ROOT, "%02X", encrypted[0])
                })",
            )
        }

        val headerSize = 1 + SALT_LENGTH + IV_LENGTH
        if (encrypted.size < headerSize + GCM_TAG_BITS / 8) {
            throw InvalidExportFileException("Export file is too small to be valid")
        }

        var offset = 1
        val salt = encrypted.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = encrypted.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = encrypted.copyOfRange(offset, encrypted.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        return try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException("Incorrect password", e)
        }
    }

    /**
     * Decrypt an encrypted export file and return an [InputStream] to the plaintext ZIP.
     *
     * @param encryptedStream input stream of the encrypted file
     * @param password the user-provided password
     * @return an [InputStream] containing the decrypted ZIP data
     */
    fun decryptStream(encryptedStream: InputStream, password: String): InputStream {
        val encrypted = encryptedStream.readBytes()
        val decrypted = decrypt(encrypted, password)
        return ByteArrayInputStream(decrypted)
    }

    /**
     * Check whether raw file bytes represent an encrypted export (vs. a legacy plaintext ZIP).
     *
     * @return `true` if the file starts with the encrypted version byte,
     *         `false` if it starts with ZIP magic bytes (legacy format)
     * @throws InvalidExportFileException if the format is not recognized at all
     */
    fun isEncrypted(header: ByteArray): Boolean {
        if (header.isEmpty()) {
            throw InvalidExportFileException("Export file is empty")
        }
        if (header[0] == ENCRYPTED_VERSION) return true
        if (header.size >= 2 && header[0] == ZIP_MAGIC_BYTE_1 && header[1] == ZIP_MAGIC_BYTE_2) {
            return false
        }
        throw InvalidExportFileException(
            "Unrecognized export file format (starts with 0x${
                String.format(java.util.Locale.ROOT, "%02X", header[0])
            })",
        )
    }

    /**
     * Derive an AES-256 key from a password and salt using PBKDF2.
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}

/**
 * Thrown when the user provides an incorrect password during decryption.
 */
class WrongPasswordException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when the export file format is not recognized.
 */
class InvalidExportFileException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when an encrypted export file is opened without providing a password.
 */
class PasswordRequiredException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
