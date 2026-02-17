package com.lxmf.messenger.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles encryption and decryption of Reticulum identity keys using Android Keystore.
 *
 * Provides two layers of protection:
 * - Layer 1 (Device Key): AES-256-GCM using hardware-backed Android Keystore
 * - Layer 2 (User Password): Optional PBKDF2-derived key + AES-256-GCM
 *
 * Encrypted format:
 * - Version 1 (Device-only): [0x01][12-byte IV][encrypted data][16-byte auth tag]
 * - Version 2 (Device+Password): [0x02][32-byte salt][12-byte IV][encrypted v1 data][16-byte auth tag]
 */
@Suppress("TooManyFunctions") // Encryption API requires many related functions
@Singleton
class IdentityKeyEncryptor
    @Inject
    constructor() {
        companion object {
            private const val TAG = "IdentityKeyEncryptor"

            // Android Keystore configuration
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val KEY_ALIAS = "columba_identity_master_key"

            // Encryption parameters
            private const val AES_KEY_SIZE = 256
            private const val GCM_IV_LENGTH = 12
            private const val GCM_TAG_LENGTH = 128
            private const val SALT_LENGTH = 32

            // PBKDF2 parameters (600K iterations as per OWASP recommendation)
            private const val PBKDF2_ITERATIONS = 600_000
            private const val PBKDF2_KEY_LENGTH = 256

            // Version bytes
            const val VERSION_DEVICE_ONLY: Byte = 0x01
            const val VERSION_DEVICE_AND_PASSWORD: Byte = 0x02

            // Expected key size
            const val IDENTITY_KEY_SIZE = 64
        }

        private val secureRandom = SecureRandom()

        /**
         * Get or create the master encryption key from Android Keystore.
         * This key is hardware-backed (TEE/StrongBox) when available.
         */
        private fun getOrCreateMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            // Check if key already exists
            keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

            // Generate new key with hardware backing
            val keyGenerator =
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE,
                )

            val keySpec =
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(AES_KEY_SIZE)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    // Key is bound to the device and cannot be exported
                    .setUserAuthenticationRequired(false) // We handle auth at app level
                    .build()

            keyGenerator.init(keySpec)
            return keyGenerator.generateKey().also {
                Log.i(TAG, "Generated new master key in Android Keystore")
            }
        }

        /**
         * Check if the master key exists in the Keystore.
         */
        fun hasMasterKey(): Boolean {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                keyStore.containsAlias(KEY_ALIAS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check master key existence", e)
                false
            }
        }

        /**
         * Encrypt identity key data with device-only protection (Layer 1).
         *
         * Format: [0x01][12-byte IV][encrypted data][16-byte auth tag]
         *
         * @param plainKeyData The 64-byte identity key to encrypt
         * @return Encrypted data with version prefix and IV
         */
        fun encryptWithDeviceKey(plainKeyData: ByteArray): ByteArray {
            require(plainKeyData.size == IDENTITY_KEY_SIZE) {
                "Identity key must be $IDENTITY_KEY_SIZE bytes, got ${plainKeyData.size}"
            }

            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val iv = cipher.iv
            val encryptedData = cipher.doFinal(plainKeyData)

            // Combine: version + IV + encrypted data (includes auth tag)
            return ByteBuffer.allocate(1 + GCM_IV_LENGTH + encryptedData.size)
                .put(VERSION_DEVICE_ONLY)
                .put(iv)
                .put(encryptedData)
                .array()
        }

        /**
         * Decrypt identity key data encrypted with device-only protection.
         *
         * @param encryptedData The encrypted data with version prefix and IV
         * @return Decrypted 64-byte identity key
         * @throws CorruptedKeyException if decryption fails or data is tampered
         */
        @Suppress("ThrowsCount") // Input validation requires multiple checks
        fun decryptWithDeviceKey(encryptedData: ByteArray): ByteArray {
            validateDeviceEncryptedData(encryptedData)

            val buffer = ByteBuffer.wrap(encryptedData)
            buffer.get() // Skip version byte (already validated)

            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)

            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            return try {
                val masterKey = getOrCreateMasterKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw CorruptedKeyException("Decryption failed - key may be corrupted or tampered", e)
            }
        }

        @Suppress("ThrowsCount") // Validation needs multiple throw statements for different error cases
        private fun validateDeviceEncryptedData(encryptedData: ByteArray) {
            if (encryptedData.isEmpty()) {
                throw CorruptedKeyException("Encrypted data is empty")
            }
            if (encryptedData[0] != VERSION_DEVICE_ONLY) {
                throw CorruptedKeyException("Unsupported encryption version: ${encryptedData[0]} (expected device-only)")
            }
            if (encryptedData.size < 1 + GCM_IV_LENGTH + IDENTITY_KEY_SIZE) {
                throw CorruptedKeyException("Encrypted data too short")
            }
        }

        /**
         * Encrypt identity key data with device + password protection (Layer 1 + Layer 2).
         *
         * Format: [0x02][32-byte salt][12-byte IV][encrypted v1 data][16-byte auth tag]
         *
         * @param plainKeyData The 64-byte identity key to encrypt
         * @param password The user's password for additional protection
         * @return Double-encrypted data with version, salt, and IV
         */
        fun encryptWithPassword(
            plainKeyData: ByteArray,
            password: CharArray,
        ): ByteArray {
            require(plainKeyData.size == IDENTITY_KEY_SIZE) {
                "Identity key must be $IDENTITY_KEY_SIZE bytes, got ${plainKeyData.size}"
            }

            // First, encrypt with device key (Layer 1)
            val deviceEncrypted = encryptWithDeviceKey(plainKeyData)

            // Generate salt for password derivation
            val salt = ByteArray(SALT_LENGTH)
            secureRandom.nextBytes(salt)

            // Derive key from password
            val passwordKey = deriveKeyFromPassword(password, salt)

            // Encrypt the device-encrypted data with password key (Layer 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, passwordKey)
            val iv = cipher.iv
            val doubleEncrypted = cipher.doFinal(deviceEncrypted)

            // Securely clear the password key from memory
            secureWipe(passwordKey.encoded)

            // Combine: version + salt + IV + encrypted data (includes auth tag)
            return ByteBuffer.allocate(1 + SALT_LENGTH + GCM_IV_LENGTH + doubleEncrypted.size)
                .put(VERSION_DEVICE_AND_PASSWORD)
                .put(salt)
                .put(iv)
                .put(doubleEncrypted)
                .array()
        }

        /**
         * Decrypt identity key data encrypted with device + password protection.
         *
         * @param encryptedData The double-encrypted data
         * @param password The user's password
         * @return Decrypted 64-byte identity key
         * @throws WrongPasswordException if the password is incorrect
         * @throws CorruptedKeyException if data is corrupted or tampered
         */
        @Suppress("ThrowsCount") // Input validation requires multiple checks
        fun decryptWithPassword(
            encryptedData: ByteArray,
            password: CharArray,
        ): ByteArray {
            validatePasswordEncryptedData(encryptedData)

            val buffer = ByteBuffer.wrap(encryptedData)
            buffer.get() // Skip version byte (already validated)

            val salt = ByteArray(SALT_LENGTH)
            buffer.get(salt)

            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)

            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            // Derive key from password
            val passwordKey = deriveKeyFromPassword(password, salt)

            val deviceEncrypted =
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, passwordKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    cipher.doFinal(ciphertext)
                } catch (e: Exception) {
                    secureWipe(passwordKey.encoded)
                    throw WrongPasswordException("Password incorrect or data corrupted", e)
                } finally {
                    secureWipe(passwordKey.encoded)
                }

            // Now decrypt with device key (Layer 1)
            return decryptWithDeviceKey(deviceEncrypted)
        }

        @Suppress("ThrowsCount") // Validation needs multiple throw statements for different error cases
        private fun validatePasswordEncryptedData(encryptedData: ByteArray) {
            if (encryptedData.isEmpty()) {
                throw CorruptedKeyException("Encrypted data is empty")
            }
            if (encryptedData[0] != VERSION_DEVICE_AND_PASSWORD) {
                throw CorruptedKeyException("Unsupported encryption version: ${encryptedData[0]} (expected device+password)")
            }
            val minSize = 1 + SALT_LENGTH + GCM_IV_LENGTH + 1 + GCM_IV_LENGTH + IDENTITY_KEY_SIZE
            if (encryptedData.size < minSize) {
                throw CorruptedKeyException("Encrypted data too short")
            }
        }

        /**
         * Derive an AES key from a password using PBKDF2.
         */
        private fun deriveKeyFromPassword(
            password: CharArray,
            salt: ByteArray,
        ): SecretKey {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
            val tmp = factory.generateSecret(spec)
            spec.clearPassword()
            return SecretKeySpec(tmp.encoded, "AES").also {
                secureWipe(tmp.encoded)
            }
        }

        /**
         * Create a password verification hash for storage.
         * This allows checking if the correct password is provided without storing it.
         *
         * @param password The password to create verification hash for
         * @param salt The salt to use (should match the encryption salt)
         * @return 32-byte verification hash
         */
        fun createPasswordVerificationHash(
            password: CharArray,
            salt: ByteArray,
        ): ByteArray {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            // Use half the iterations for verification hash (still secure, but faster for check)
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS / 2, AES_KEY_SIZE)
            val hash = factory.generateSecret(spec).encoded
            spec.clearPassword()
            return hash
        }

        /**
         * Verify a password against a stored verification hash.
         */
        fun verifyPassword(
            password: CharArray,
            salt: ByteArray,
            expectedHash: ByteArray,
        ): Boolean {
            val computedHash = createPasswordVerificationHash(password, salt)
            return computedHash.contentEquals(expectedHash)
        }

        /**
         * Add password protection to device-only encrypted data.
         * Upgrades from Version 1 to Version 2 encryption.
         *
         * @param deviceEncryptedData Data currently encrypted with device key only
         * @param password New password to add
         * @return Password-protected encrypted data
         */
        fun addPasswordProtection(
            deviceEncryptedData: ByteArray,
            password: CharArray,
        ): ByteArray {
            // Decrypt with device key
            val plainKeyData = decryptWithDeviceKey(deviceEncryptedData)

            // Re-encrypt with password
            val result = encryptWithPassword(plainKeyData, password)

            // Securely wipe the plaintext
            secureWipe(plainKeyData)

            return result
        }

        /**
         * Remove password protection, returning to device-only encryption.
         * Downgrades from Version 2 to Version 1 encryption.
         *
         * @param passwordEncryptedData Data currently encrypted with device + password
         * @param currentPassword Current password
         * @return Device-only encrypted data
         */
        fun removePasswordProtection(
            passwordEncryptedData: ByteArray,
            currentPassword: CharArray,
        ): ByteArray {
            // Decrypt with password
            val plainKeyData = decryptWithPassword(passwordEncryptedData, currentPassword)

            // Re-encrypt with device key only
            val result = encryptWithDeviceKey(plainKeyData)

            // Securely wipe the plaintext
            secureWipe(plainKeyData)

            return result
        }

        /**
         * Change the password on password-protected data.
         *
         * @param encryptedData Currently password-protected data
         * @param oldPassword Current password
         * @param newPassword New password
         * @return Data encrypted with new password
         */
        fun changePassword(
            encryptedData: ByteArray,
            oldPassword: CharArray,
            newPassword: CharArray,
        ): ByteArray {
            val plainKeyData = decryptWithPassword(encryptedData, oldPassword)
            val result = encryptWithPassword(plainKeyData, newPassword)
            secureWipe(plainKeyData)
            return result
        }

        /**
         * Get the encryption version from encrypted data.
         *
         * @return VERSION_DEVICE_ONLY, VERSION_DEVICE_AND_PASSWORD, or -1 if invalid
         */
        fun getEncryptionVersion(encryptedData: ByteArray): Int {
            return if (encryptedData.isNotEmpty()) {
                encryptedData[0].toInt()
            } else {
                -1
            }
        }

        /**
         * Check if the encrypted data requires a password.
         */
        fun requiresPassword(encryptedData: ByteArray): Boolean {
            return getEncryptionVersion(encryptedData) == VERSION_DEVICE_AND_PASSWORD.toInt()
        }

        /**
         * Extract the salt from password-protected encrypted data.
         * Used for password verification before decryption.
         */
        fun extractSalt(encryptedData: ByteArray): ByteArray? {
            if (encryptedData.size < 1 + SALT_LENGTH) return null
            if (encryptedData[0] != VERSION_DEVICE_AND_PASSWORD) return null

            return encryptedData.copyOfRange(1, 1 + SALT_LENGTH)
        }

        /**
         * Encrypt key data for migration export (password-based, not device-bound).
         * Uses a fresh PBKDF2-derived key for portability between devices.
         *
         * Format: [32-byte salt][12-byte IV][encrypted data][16-byte auth tag]
         *
         * @param plainKeyData The 64-byte identity key to encrypt
         * @param password Export password
         * @return Encrypted data that can be decrypted on any device with the password
         */
        fun encryptForExport(
            plainKeyData: ByteArray,
            password: CharArray,
        ): ByteArray {
            require(plainKeyData.size == IDENTITY_KEY_SIZE) {
                "Identity key must be $IDENTITY_KEY_SIZE bytes, got ${plainKeyData.size}"
            }

            val salt = ByteArray(SALT_LENGTH)
            secureRandom.nextBytes(salt)

            val exportKey = deriveKeyFromPassword(password, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, exportKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainKeyData)

            secureWipe(exportKey.encoded)

            return ByteBuffer.allocate(SALT_LENGTH + GCM_IV_LENGTH + encrypted.size)
                .put(salt)
                .put(iv)
                .put(encrypted)
                .array()
        }

        /**
         * Decrypt key data from migration export.
         *
         * @param encryptedData Encrypted export data
         * @param password Export password
         * @return Decrypted 64-byte identity key
         */
        fun decryptFromExport(
            encryptedData: ByteArray,
            password: CharArray,
        ): ByteArray {
            val minSize = SALT_LENGTH + GCM_IV_LENGTH + IDENTITY_KEY_SIZE
            if (encryptedData.size < minSize) {
                throw CorruptedKeyException("Export data too short")
            }

            val buffer = ByteBuffer.wrap(encryptedData)

            val salt = ByteArray(SALT_LENGTH)
            buffer.get(salt)

            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)

            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            val exportKey = deriveKeyFromPassword(password, salt)

            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, exportKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw WrongPasswordException("Export password incorrect or data corrupted", e)
            } finally {
                secureWipe(exportKey.encoded)
            }
        }

        /**
         * Securely wipe a byte array by overwriting with random data then zeroing.
         *
         * Note: When called on `SecretKeySpec.getEncoded()`, JCA returns a clone of the
         * internal key material, so this only wipes the copy. The original key bytes inside
         * the SecretKeySpec remain until GC. This is a known JVM/JCA limitation — there is
         * no reliable way to wipe SecretKey internals on the JVM.
         */
        fun secureWipe(data: ByteArray) {
            secureRandom.nextBytes(data)
            data.fill(0)
        }
    }

/**
 * Exception thrown when encrypted key data is corrupted or tampered.
 */
class CorruptedKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when the wrong password is provided.
 */
class WrongPasswordException(message: String, cause: Throwable? = null) : Exception(message, cause)
