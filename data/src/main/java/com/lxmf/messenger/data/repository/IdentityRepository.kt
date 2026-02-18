package com.lxmf.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.lxmf.messenger.data.crypto.IdentityKeyEncryptor
import com.lxmf.messenger.data.crypto.IdentityKeyMigrator
import com.lxmf.messenger.data.crypto.IdentityKeyProvider
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IdentityRepository"

/**
 * Repository for managing local Reticulum identities.
 */
@Suppress("TooManyFunctions") // Identity management requires comprehensive API
@Singleton
class IdentityRepository
    @Inject
    constructor(
        private val identityDao: LocalIdentityDao,
        private val database: ColumbaDatabase,
        @ApplicationContext private val context: Context,
        private val ioDispatcher: CoroutineDispatcher,
        private val keyEncryptor: IdentityKeyEncryptor,
        private val keyMigrator: IdentityKeyMigrator,
        private val keyProvider: IdentityKeyProvider,
    ) {
        /**
         * Flow of all identities, ordered by last used timestamp.
         */
        val allIdentities: Flow<List<LocalIdentityEntity>> = identityDao.getAllIdentities()

        /**
         * Flow of the currently active identity.
         */
        val activeIdentity: Flow<LocalIdentityEntity?> = identityDao.getActiveIdentity()

        /**
         * Get the currently active identity synchronously.
         * Used during app initialization when Flow observation isn't available.
         */
        suspend fun getActiveIdentitySync(): LocalIdentityEntity? =
            withContext(ioDispatcher) {
                identityDao.getActiveIdentitySync()
            }

        /**
         * Get a specific identity by its hash.
         */
        suspend fun getIdentity(identityHash: String): LocalIdentityEntity? =
            withContext(ioDispatcher) {
                identityDao.getIdentity(identityHash)
            }

        /**
         * Create a new identity using the Python Reticulum service.
         * The key data is encrypted using AES-256-GCM before storage.
         *
         * For now, this is a placeholder that creates a database entry.
         * The actual identity file creation will be handled by ReticulumProtocol.
         */
        suspend fun createIdentity(
            identityHash: String,
            displayName: String,
            destinationHash: String,
            filePath: String,
            keyData: ByteArray? = null,
        ): Result<LocalIdentityEntity> =
            withContext(ioDispatcher) {
                try {
                    Log.d(TAG, "createIdentity: Creating entity for '$displayName' with hash ${identityHash.take(8)}...")

                    // Encrypt the key data if provided
                    val (encryptedKeyData, keyVersion) =
                        if (keyData != null && keyData.size == 64) {
                            val encrypted = keyEncryptor.encryptWithDeviceKey(keyData)
                            encrypted to IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt()
                        } else {
                            null to 0
                        }

                    @Suppress("DEPRECATION")
                    val entity =
                        LocalIdentityEntity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = filePath,
                            keyData = null, // No longer store unencrypted key data
                            encryptedKeyData = encryptedKeyData,
                            keyEncryptionVersion = keyVersion,
                            createdTimestamp = System.currentTimeMillis(),
                            lastUsedTimestamp = 0L,
                            isActive = false,
                        )
                    Log.d(TAG, "createIdentity: Calling DAO insert...")
                    identityDao.insert(entity)
                    Log.d(TAG, "createIdentity: DAO insert completed successfully (key encrypted: ${encryptedKeyData != null})")
                    Result.success(entity)
                } catch (e: Exception) {
                    Log.e(TAG, "createIdentity: Failed to insert identity", e)
                    Result.failure(e)
                }
            }

        /**
         * Set an identity as the active identity.
         * This will deactivate all other identities and activate the specified one.
         */
        suspend fun switchActiveIdentity(identityHash: String): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    identityDao.setActive(identityHash)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Update the display name for a specific identity.
         */
        suspend fun updateDisplayName(
            identityHash: String,
            displayName: String,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    Log.d(TAG, "updateDisplayName: Updating display name for ${identityHash.take(8)}... to '$displayName'")
                    identityDao.updateDisplayName(identityHash, displayName)
                    Log.d(TAG, "updateDisplayName: Display name updated successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "updateDisplayName: Failed to update display name", e)
                    Result.failure(e)
                }
            }

        /**
         * Delete an identity from both database and file system.
         * The identity file deletion will be handled by ReticulumProtocol.
         * Database cascade delete will remove all associated data (conversations, contacts, etc.).
         */
        suspend fun deleteIdentity(identityHash: String): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    // Delete from database (cascade will delete associated data)
                    identityDao.delete(identityHash)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Import an identity from a file URI.
         * Reads the file, validates it, encrypts the key, and adds it to the database.
         */
        suspend fun importIdentity(
            identityHash: String,
            displayName: String,
            destinationHash: String,
            filePath: String,
            keyData: ByteArray? = null,
        ): Result<LocalIdentityEntity> =
            withContext(ioDispatcher) {
                try {
                    // Encrypt the key data if provided
                    val (encryptedKeyData, keyVersion) =
                        if (keyData != null && keyData.size == 64) {
                            val encrypted = keyEncryptor.encryptWithDeviceKey(keyData)
                            encrypted to IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt()
                        } else {
                            null to 0
                        }

                    @Suppress("DEPRECATION")
                    val entity =
                        LocalIdentityEntity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = filePath,
                            keyData = null, // No longer store unencrypted
                            encryptedKeyData = encryptedKeyData,
                            keyEncryptionVersion = keyVersion,
                            createdTimestamp = System.currentTimeMillis(),
                            lastUsedTimestamp = 0L,
                            isActive = false,
                        )

                    identityDao.insert(entity)
                    Result.success(entity)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Export an identity to a shareable URI.
         * Copies the identity file to cache directory and returns a FileProvider URI.
         */
        suspend fun exportIdentity(
            identityHash: String,
            fileData: ByteArray,
        ): Result<Uri> =
            withContext(ioDispatcher) {
                try {
                    // Create cache file for sharing
                    val cacheFile = File(context.cacheDir, "identity_$identityHash.rnsidentity")
                    cacheFile.writeBytes(fileData)

                    // Get FileProvider URI for sharing
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile,
                        )

                    Result.success(uri)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Get the count of all identities.
         */
        suspend fun getIdentityCount(): Int =
            withContext(ioDispatcher) {
                identityDao.getIdentityCount()
            }

        /**
         * Check if an identity with the given hash exists.
         */
        suspend fun identityExists(identityHash: String): Boolean =
            withContext(ioDispatcher) {
                identityDao.identityExists(identityHash)
            }

        /**
         * Update the last used timestamp of an identity.
         */
        suspend fun updateLastUsedTimestamp(identityHash: String): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    identityDao.updateLastUsedTimestamp(identityHash, System.currentTimeMillis())
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        /**
         * Update the icon appearance (icon, foreground color, background color) for an identity.
         *
         * @param identityHash The identity to update
         * @param iconName Material Design Icon name (e.g., "account", "star"), or null to clear
         * @param foregroundColor Hex RGB color for icon foreground (e.g., "FFFFFF"), or null to clear
         * @param backgroundColor Hex RGB color for icon background (e.g., "1E88E5"), or null to clear
         */
        suspend fun updateIconAppearance(
            identityHash: String,
            iconName: String?,
            foregroundColor: String?,
            backgroundColor: String?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    Log.d(
                        TAG,
                        "updateIconAppearance: Updating icon for ${identityHash.take(8)}... " +
                            "to icon=$iconName, fg=$foregroundColor, bg=$backgroundColor",
                    )
                    identityDao.updateIconAppearance(identityHash, iconName, foregroundColor, backgroundColor)
                    Log.d(TAG, "updateIconAppearance: Icon appearance updated successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "updateIconAppearance: Failed to update icon appearance", e)
                    Result.failure(e)
                }
            }

        /**
         * Ensure the identity file exists at the canonical path (identity_<hash>).
         * If the file doesn't exist, recreate it from keyData stored in the database.
         * Also updates the database filePath if it was pointing to default_identity.
         *
         * @param identity The identity to verify/recover
         * @return Result containing the verified file path, or an error if recovery failed
         */
        suspend fun ensureIdentityFileExists(identity: LocalIdentityEntity): Result<String> =
            withContext(ioDispatcher) {
                try {
                    val reticulumDir = File(context.filesDir, "reticulum")
                    val canonicalPath = File(reticulumDir, "identity_${identity.identityHash}")

                    // Check if canonical file exists
                    if (canonicalPath.exists() && canonicalPath.length() == 64L) {
                        Log.d(TAG, "Identity file exists at canonical path: ${canonicalPath.absolutePath}")

                        // Update database if it was pointing to a different path
                        if (identity.filePath != canonicalPath.absolutePath) {
                            Log.i(
                                TAG,
                                "Updating database filePath from ${identity.filePath} " +
                                    "to ${canonicalPath.absolutePath}",
                            )
                            identityDao.updateFilePath(identity.identityHash, canonicalPath.absolutePath)
                        }

                        return@withContext Result.success(canonicalPath.absolutePath)
                    }

                    // File doesn't exist or is invalid - try to recover from keyData
                    Log.w(
                        TAG,
                        "Identity file missing or invalid at ${canonicalPath.absolutePath}, " +
                            "attempting recovery",
                    )

                    // Try legacy plaintext keyData first, then fall back to decrypting encryptedKeyData
                    @Suppress("DEPRECATION")
                    val keyData =
                        identity.keyData
                            ?: if (identity.encryptedKeyData != null) {
                                keyProvider.getDecryptedKeyData(identity.identityHash).getOrNull()
                            } else {
                                null
                            }
                    if (keyData == null || keyData.size != 64) {
                        Log.e(
                            TAG,
                            "Cannot recover identity ${identity.identityHash}: " +
                                "no valid key data available (size=${keyData?.size})",
                        )
                        return@withContext Result.failure(
                            IllegalStateException("Identity file missing and no valid keyData backup available"),
                        )
                    }

                    // Ensure directory exists
                    if (!reticulumDir.exists()) {
                        reticulumDir.mkdirs()
                    }

                    // Write keyData to canonical path
                    canonicalPath.writeBytes(keyData)
                    Log.i(TAG, "Recovered identity file from keyData: ${canonicalPath.absolutePath}")

                    // Update database filePath
                    if (identity.filePath != canonicalPath.absolutePath) {
                        Log.i(TAG, "Updating database filePath to ${canonicalPath.absolutePath}")
                        identityDao.updateFilePath(identity.identityHash, canonicalPath.absolutePath)
                    }

                    Result.success(canonicalPath.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to ensure identity file exists for ${identity.identityHash}", e)
                    Result.failure(e)
                }
            }

        /**
         * Migrate the default identity file to the database if the migration_placeholder exists
         * OR if there are no identities in the database at all.
         * This is called during app initialization to replace the temporary placeholder
         * created during database migration with the actual default identity, or to add
         * the default identity on first run.
         *
         * @param identityHash Optional identity hash to use (if not provided, migration will retry later)
         * @param destinationHash Optional destination hash to use (if not provided, migration will retry later)
         */
        @Suppress("LongMethod") // Migration logic is complex but cohesive
        suspend fun migrateDefaultIdentityIfNeeded(
            identityHash: String? = null,
            destinationHash: String? = null,
        ) = withContext(ioDispatcher) {
            try {
                // Check if migration_placeholder exists
                val placeholder = identityDao.getIdentity("migration_placeholder")

                // Also check if we have ANY identities at all
                val existingIdentities = identityDao.getAllIdentitiesSync()

                if (placeholder == null && existingIdentities.isNotEmpty()) {
                    android.util.Log.d(
                        "IdentityRepository",
                        "No migration_placeholder found and identities exist - migration already completed",
                    )
                    return@withContext
                }

                if (placeholder == null && existingIdentities.isEmpty()) {
                    android.util.Log.i("IdentityRepository", "No identities found - this is a fresh install, will add default identity")
                } else if (placeholder != null) {
                    android.util.Log.i("IdentityRepository", "Found migration_placeholder - migrating default identity")
                }

                // Check if default_identity file exists
                val defaultIdentityFile = File(context.filesDir, "reticulum/default_identity")
                android.util.Log.d("IdentityRepository", "Checking for default_identity at: ${defaultIdentityFile.absolutePath}")
                android.util.Log.d(
                    "IdentityRepository",
                    "File exists: ${defaultIdentityFile.exists()}, File length: ${defaultIdentityFile.length()}",
                )

                if (!defaultIdentityFile.exists()) {
                    android.util.Log.w("IdentityRepository", "No default_identity file found at ${defaultIdentityFile.absolutePath}")

                    // If no default identity file exists, remove the placeholder (if it exists)
                    if (placeholder != null) {
                        android.util.Log.i("IdentityRepository", "Removing migration_placeholder - will wait for identity creation")
                        identityDao.delete(placeholder.identityHash)
                    }
                    return@withContext
                }

                android.util.Log.i("IdentityRepository", "Found default_identity file at: ${defaultIdentityFile.absolutePath}")

                // Check if identity hashes were provided
                if (identityHash.isNullOrEmpty() || destinationHash.isNullOrEmpty()) {
                    android.util.Log.w("IdentityRepository", "Identity hashes not provided - will retry on next app start")
                    return@withContext
                }

                android.util.Log.i("IdentityRepository", "Creating identity entry: hash=$identityHash")

                // Read key data from file and encrypt it
                val keyData =
                    try {
                        defaultIdentityFile.readBytes()
                    } catch (e: Exception) {
                        android.util.Log.w("IdentityRepository", "Failed to read key data from identity file", e)
                        null
                    }

                // Encrypt the key data
                val (encryptedKeyData, keyVersion) =
                    if (keyData != null && keyData.size == 64) {
                        try {
                            val encrypted = keyEncryptor.encryptWithDeviceKey(keyData)
                            encrypted to IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt()
                        } catch (e: Exception) {
                            android.util.Log.w("IdentityRepository", "Failed to encrypt key data", e)
                            null to 0
                        }
                    } else {
                        null to 0
                    }

                // Create the actual default identity entry
                @Suppress("DEPRECATION")
                val defaultIdentity =
                    LocalIdentityEntity(
                        identityHash = identityHash,
                        displayName = "Anonymous Peer",
                        destinationHash = destinationHash,
                        filePath = defaultIdentityFile.absolutePath,
                        keyData = null, // No longer store unencrypted
                        encryptedKeyData = encryptedKeyData,
                        keyEncryptionVersion = keyVersion,
                        createdTimestamp = defaultIdentityFile.lastModified(),
                        lastUsedTimestamp = System.currentTimeMillis(),
                        isActive = true,
                    )

                // Replace placeholder with actual identity (or just insert if no placeholder)
                if (placeholder != null) {
                    identityDao.delete(placeholder.identityHash)

                    // Update all foreign key references from placeholder to actual identity
                    database.openHelper.writableDatabase.apply {
                        execSQL(
                            "UPDATE conversations SET identityHash = ? WHERE identityHash = 'migration_placeholder'",
                            arrayOf(identityHash),
                        )
                        execSQL("UPDATE contacts SET identityHash = ? WHERE identityHash = 'migration_placeholder'", arrayOf(identityHash))
                        execSQL(
                            "UPDATE peer_identities SET identityHash = ? WHERE identityHash = 'migration_placeholder'",
                            arrayOf(identityHash),
                        )
                        execSQL("UPDATE announces SET identityHash = ? WHERE identityHash = 'migration_placeholder'", arrayOf(identityHash))
                        execSQL("UPDATE messages SET identityHash = ? WHERE identityHash = 'migration_placeholder'", arrayOf(identityHash))
                    }
                }

                identityDao.insert(defaultIdentity)

                android.util.Log.i("IdentityRepository", "✅ Successfully migrated default identity to database")
            } catch (e: Exception) {
                android.util.Log.e("IdentityRepository", "Failed to migrate default identity", e)
            }
        }

        // ==================== Key Encryption Management ====================

        /**
         * Run the encryption migration for all unencrypted identities.
         * This should be called during app initialization.
         */
        suspend fun runEncryptionMigration() =
            withContext(ioDispatcher) {
                Log.i(TAG, "Running identity key encryption migration...")
                val result = keyMigrator.migrateUnencryptedIdentities()
                result.onSuccess { migrationResult ->
                    Log.i(
                        TAG,
                        "Encryption migration completed: ${migrationResult.successCount} succeeded, " +
                            "${migrationResult.failureCount} failed",
                    )
                }.onFailure { e ->
                    Log.e(TAG, "Encryption migration failed", e)
                }
                result
            }

        /**
         * Get decrypted key data for an identity.
         * Used when key data needs to be accessed (e.g., for export or Python/RNS).
         *
         * @param identityHash The identity to get key data for
         * @param password Optional password if the identity is password-protected
         */
        suspend fun getDecryptedKeyData(
            identityHash: String,
            password: CharArray? = null,
        ): Result<ByteArray> = keyProvider.getDecryptedKeyData(identityHash, password)

        /**
         * Check if an identity requires a password to unlock.
         */
        suspend fun requiresPassword(identityHash: String): Boolean = keyProvider.requiresPassword(identityHash)

        /**
         * Enable password protection on an identity.
         */
        suspend fun enablePasswordProtection(
            identityHash: String,
            password: CharArray,
        ): Result<Unit> = keyProvider.enablePasswordProtection(identityHash, password)

        /**
         * Disable password protection on an identity.
         */
        suspend fun disablePasswordProtection(
            identityHash: String,
            currentPassword: CharArray,
        ): Result<Unit> = keyProvider.disablePasswordProtection(identityHash, currentPassword)

        /**
         * Change the password on a password-protected identity.
         */
        suspend fun changeIdentityPassword(
            identityHash: String,
            oldPassword: CharArray,
            newPassword: CharArray,
        ): Result<Unit> = keyProvider.changePassword(identityHash, oldPassword, newPassword)

        /**
         * Check if encryption migration has been completed for all identities.
         */
        fun isEncryptionMigrationCompleted(): Boolean = keyMigrator.isMigrationCompleted()

        /**
         * Get encryption migration statistics.
         */
        fun getEncryptionMigrationStats() = keyMigrator.getMigrationStats()
    }
