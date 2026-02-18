package com.lxmf.messenger.data.crypto

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides decrypted identity key data at runtime.
 *
 * This class manages:
 * - Decryption of identity keys on demand
 * - In-memory caching of decrypted keys (cleared on app background)
 * - Temporary file handling for Python/Reticulum compatibility
 * - Password verification and protection management
 *
 * Usage:
 * ```
 * val keyData = keyProvider.getDecryptedKeyData(identityHash).getOrThrow()
 * // Use keyData...
 * // The key is automatically cached and wiped when app goes to background
 * ```
 */
@Suppress("TooManyFunctions") // Key management API requires many related functions
@Singleton
class IdentityKeyProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityDao: LocalIdentityDao,
        private val encryptor: IdentityKeyEncryptor,
    ) : DefaultLifecycleObserver {
        companion object {
            private const val TAG = "IdentityKeyProvider"
            private const val TEMP_KEY_DIR = "temp_keys"
        }

        // In-memory cache of decrypted keys (identityHash -> keyData)
        private val keyCache = ConcurrentHashMap<String, ByteArray>()

        // Lock for thread-safe cache operations (ReentrantLock works from both
        // suspend and non-suspend contexts, unlike coroutine Mutex)
        private val cacheLock = ReentrantLock()

        // Track active temp files for cleanup
        private val activeTempFiles = ConcurrentHashMap<String, File>()

        private val secureRandom = SecureRandom()

        init {
            // Register for lifecycle events to clear cache when app goes to background
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        /**
         * Get decrypted key data for an identity.
         *
         * @param identityHash The identity to get key data for
         * @param password Optional password if the identity is password-protected
         * @return Result containing the 64-byte decrypted key data
         */
        suspend fun getDecryptedKeyData(
            identityHash: String,
            password: CharArray? = null,
        ): Result<ByteArray> =
            withContext(Dispatchers.IO) {
                // Check cache first (under lock to prevent onStop from wiping mid-copy)
                cacheLock.withLock {
                    keyCache[identityHash]?.let { cachedKey ->
                        return@withContext Result.success(cachedKey.copyOf())
                    }
                }

                try {
                    val identity =
                        identityDao.getIdentity(identityHash)
                            ?: return@withContext Result.failure(
                                KeyNotFoundException("Identity not found: ${identityHash.take(8)}..."),
                            )

                    val keyData = decryptIdentityKey(identity, password)
                    if (keyData != null) {
                        // Cache the key (a copy, so we can wipe the cache independently)
                        cacheLock.withLock {
                            keyCache[identityHash] = keyData.copyOf()
                        }
                        Result.success(keyData)
                    } else {
                        Result.failure(KeyNotFoundException("No key data available for identity"))
                    }
                } catch (e: WrongPasswordException) {
                    Result.failure(e)
                } catch (e: CorruptedKeyException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get key data for ${identityHash.take(8)}...", e)
                    Result.failure(e)
                }
            }

        /**
         * Check if an identity requires a password to unlock.
         */
        suspend fun requiresPassword(identityHash: String): Boolean =
            withContext(Dispatchers.IO) {
                val identity = identityDao.getIdentity(identityHash) ?: return@withContext false
                identity.keyEncryptionVersion == IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt()
            }

        /**
         * Check if a password is correct for a password-protected identity.
         */
        suspend fun verifyPassword(
            identityHash: String,
            password: CharArray,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val identity = identityDao.getIdentity(identityHash) ?: return@withContext false

                if (identity.keyEncryptionVersion != IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt()) {
                    return@withContext true // Not password protected
                }

                val salt = identity.passwordSalt ?: return@withContext false
                val expectedHash = identity.passwordVerificationHash ?: return@withContext false

                encryptor.verifyPassword(password, salt, expectedHash)
            }

        /**
         * Enable password protection on an identity.
         *
         * @param identityHash The identity to protect
         * @param password The password to use
         * @return Result indicating success or failure
         */
        suspend fun enablePasswordProtection(
            identityHash: String,
            password: CharArray,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val identity =
                        identityDao.getIdentity(identityHash)
                            ?: return@withContext Result.failure(KeyNotFoundException("Identity not found"))

                    // Get current encrypted data
                    val currentEncryptedData =
                        identity.encryptedKeyData
                            ?: return@withContext Result.failure(
                                IllegalStateException("Identity not yet encrypted with device key"),
                            )

                    if (identity.keyEncryptionVersion == IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt()) {
                        return@withContext Result.failure(
                            IllegalStateException("Identity is already password protected"),
                        )
                    }

                    // Get salt for password verification (reuse from encryption or generate new)
                    val salt = ByteArray(32)
                    secureRandom.nextBytes(salt)

                    // Create password verification hash
                    val verificationHash = encryptor.createPasswordVerificationHash(password, salt)

                    // Add password protection layer
                    val passwordEncrypted = encryptor.addPasswordProtection(currentEncryptedData, password)

                    // Update database
                    identityDao.updatePasswordProtection(
                        identityHash = identityHash,
                        encryptedKeyData = passwordEncrypted,
                        version = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                        passwordSalt = salt,
                        passwordVerificationHash = verificationHash,
                    )

                    // Clear cache for this identity (will need password next time)
                    clearCachedKey(identityHash)

                    Log.i(TAG, "Enabled password protection for ${identityHash.take(8)}...")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable password protection", e)
                    Result.failure(e)
                }
            }

        /**
         * Disable password protection on an identity.
         *
         * @param identityHash The identity to unprotect
         * @param currentPassword The current password
         * @return Result indicating success or failure
         */
        suspend fun disablePasswordProtection(
            identityHash: String,
            currentPassword: CharArray,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val identity =
                        identityDao.getIdentity(identityHash)
                            ?: return@withContext Result.failure(KeyNotFoundException("Identity not found"))

                    if (identity.keyEncryptionVersion != IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt()) {
                        return@withContext Result.failure(
                            IllegalStateException("Identity is not password protected"),
                        )
                    }

                    val encryptedData =
                        identity.encryptedKeyData
                            ?: return@withContext Result.failure(IllegalStateException("No encrypted data"))

                    // Remove password protection
                    val deviceOnlyEncrypted = encryptor.removePasswordProtection(encryptedData, currentPassword)

                    // Update database: set device-only key data and clear password metadata
                    identityDao.clearPasswordProtection(
                        identityHash = identityHash,
                        encryptedKeyData = deviceOnlyEncrypted,
                        version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    )

                    Log.i(TAG, "Disabled password protection for ${identityHash.take(8)}...")
                    Result.success(Unit)
                } catch (e: WrongPasswordException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable password protection", e)
                    Result.failure(e)
                }
            }

        /**
         * Change the password on a password-protected identity.
         */
        suspend fun changePassword(
            identityHash: String,
            oldPassword: CharArray,
            newPassword: CharArray,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val identity =
                        identityDao.getIdentity(identityHash)
                            ?: return@withContext Result.failure(KeyNotFoundException("Identity not found"))

                    if (identity.keyEncryptionVersion != IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt()) {
                        return@withContext Result.failure(
                            IllegalStateException("Identity is not password protected"),
                        )
                    }

                    val encryptedData =
                        identity.encryptedKeyData
                            ?: return@withContext Result.failure(IllegalStateException("No encrypted data"))

                    // Change password
                    val newEncrypted = encryptor.changePassword(encryptedData, oldPassword, newPassword)

                    // Generate new salt and verification hash
                    val newSalt = ByteArray(32)
                    secureRandom.nextBytes(newSalt)
                    val newVerificationHash = encryptor.createPasswordVerificationHash(newPassword, newSalt)

                    // Update database
                    identityDao.updatePasswordProtection(
                        identityHash = identityHash,
                        encryptedKeyData = newEncrypted,
                        version = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                        passwordSalt = newSalt,
                        passwordVerificationHash = newVerificationHash,
                    )

                    // Clear cache
                    clearCachedKey(identityHash)

                    Log.i(TAG, "Changed password for ${identityHash.take(8)}...")
                    Result.success(Unit)
                } catch (e: WrongPasswordException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to change password", e)
                    Result.failure(e)
                }
            }

        /**
         * Create a temporary decrypted key file for Python/Reticulum.
         *
         * The file is automatically tracked and deleted when cleanupTempFile is called
         * or when the app goes to background.
         *
         * @param identityHash The identity to create temp file for
         * @param password Optional password if password-protected
         * @return Path to the temporary file
         */
        suspend fun createTempKeyFile(
            identityHash: String,
            password: CharArray? = null,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val keyData = getDecryptedKeyData(identityHash, password).getOrThrow()

                    val tempDir = File(context.cacheDir, TEMP_KEY_DIR).apply { mkdirs() }
                    val tempFile = File(tempDir, "temp_${identityHash}_${System.currentTimeMillis()}")

                    tempFile.writeBytes(keyData)
                    tempFile.setReadable(true, true) // Only readable by owner

                    // Track for cleanup
                    activeTempFiles[identityHash] = tempFile

                    Log.d(TAG, "Created temp key file for ${identityHash.take(8)}...")
                    Result.success(tempFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create temp key file", e)
                    Result.failure(e)
                }
            }

        /**
         * Clean up a temporary key file.
         */
        fun cleanupTempFile(identityHash: String) {
            activeTempFiles.remove(identityHash)?.let { file ->
                secureDeleteFile(file)
                Log.d(TAG, "Cleaned up temp key file for ${identityHash.take(8)}...")
            }
        }

        /**
         * Clear a specific key from the cache.
         */
        suspend fun clearCachedKey(identityHash: String) {
            cacheLock.withLock {
                keyCache.remove(identityHash)?.let { keyData ->
                    encryptor.secureWipe(keyData)
                }
            }
        }

        /**
         * Clear all cached keys (called when app goes to background).
         */
        suspend fun clearAllCachedKeys() {
            cacheLock.withLock {
                wipeCacheUnsafe()
            }
        }

        /**
         * Clear all cached keys under the lock.
         * Callable from non-suspend contexts (e.g., lifecycle callbacks)
         * since ReentrantLock works from any thread.
         */
        private fun clearCacheInternal() {
            cacheLock.withLock {
                wipeCacheUnsafe()
            }
            Log.d(TAG, "Cleared all cached keys")
        }

        /** Wipe and clear cache entries. Caller must hold [cacheLock]. */
        private fun wipeCacheUnsafe() {
            keyCache.values.forEach { keyData ->
                encryptor.secureWipe(keyData)
            }
            keyCache.clear()
        }

        /**
         * Clean up all temporary key files.
         */
        private fun cleanupAllTempFiles() {
            activeTempFiles.values.forEach { file ->
                secureDeleteFile(file)
            }
            activeTempFiles.clear()

            // Also clean up any orphaned temp files
            val tempDir = File(context.cacheDir, TEMP_KEY_DIR)
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    secureDeleteFile(file)
                }
            }
            Log.d(TAG, "Cleaned up all temp key files")
        }

        /**
         * Securely delete a file by overwriting with random data.
         */
        private fun secureDeleteFile(file: File) {
            try {
                if (file.exists()) {
                    val randomData = ByteArray(file.length().toInt())
                    secureRandom.nextBytes(randomData)
                    file.writeBytes(randomData)
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to securely delete file: ${file.name}", e)
                file.delete() // Try normal delete as fallback
            }
        }

        /**
         * Decrypt identity key based on encryption version.
         */
        @Suppress("DEPRECATION", "ThrowsCount") // Different encryption versions require different handling
        private fun decryptIdentityKey(identity: LocalIdentityEntity, password: CharArray?): ByteArray? {
            return when (identity.keyEncryptionVersion) {
                0 -> {
                    // Unencrypted (legacy/not yet migrated)
                    identity.keyData ?: readKeyFromFile(identity)
                }
                IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt() -> {
                    // Device-only encryption
                    val encryptedData =
                        identity.encryptedKeyData
                            ?: throw CorruptedKeyException("No encrypted key data for device-encrypted identity")
                    encryptor.decryptWithDeviceKey(encryptedData)
                }
                IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt() -> {
                    // Device + password encryption
                    if (password == null) {
                        throw WrongPasswordException("Password required for this identity")
                    }
                    val encryptedData =
                        identity.encryptedKeyData
                            ?: throw CorruptedKeyException("No encrypted key data for password-protected identity")
                    encryptor.decryptWithPassword(encryptedData, password)
                }
                else -> {
                    throw CorruptedKeyException("Unknown encryption version: ${identity.keyEncryptionVersion}")
                }
            }
        }

        /**
         * Read key data from the identity file.
         */
        private fun readKeyFromFile(identity: LocalIdentityEntity): ByteArray? {
            val file = File(identity.filePath)
            return if (file.exists() && file.length() == IdentityKeyEncryptor.IDENTITY_KEY_SIZE.toLong()) {
                try {
                    file.readBytes()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read key from file: ${identity.filePath}", e)
                    null
                }
            } else {
                null
            }
        }

        // Lifecycle callbacks

        override fun onStop(owner: LifecycleOwner) {
            // App going to background - clear sensitive data
            clearCacheInternal()
            cleanupAllTempFiles()
        }
    }

/**
 * Exception thrown when an identity key is not found.
 */
class KeyNotFoundException(message: String) : Exception(message)
