package network.columba.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.crypto.IdentityKeyMigrator
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.entity.LocalIdentityEntity
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
         * If the file doesn't exist, recreate it from keyData stored in the database,
         * or recover it from the stored filePath (e.g. default_identity).
         *
         * Also performs opportunistic key backup: if the file is found but
         * encryptedKeyData is null in the DB, encrypts and saves the key now
         * to prevent future data loss.
         *
         * @param identity The identity to verify/recover
         * @return Result containing the verified file path, or an error if recovery failed
         */
        @Suppress("ReturnCount", "LongMethod") // Early returns and recovery logic
        suspend fun ensureIdentityFileExists(identity: LocalIdentityEntity): Result<String> =
            withContext(ioDispatcher) {
                val hashPrefix = identity.identityHash.take(8)
                try {
                    val reticulumDir = File(context.filesDir, "reticulum")
                    val canonicalPath = File(reticulumDir, "identity_${identity.identityHash}")

                    // Check if canonical file exists
                    if (canonicalPath.exists() && canonicalPath.length() == 64L) {
                        Log.d(TAG, "Identity file exists for $hashPrefix...")
                        updateFilePathIfNeeded(identity, canonicalPath)
                        backupKeyIfMissing(identity, canonicalPath, hashPrefix)
                        return@withContext Result.success(canonicalPath.absolutePath)
                    }

                    Log.w(TAG, "Identity file missing for $hashPrefix..., attempting recovery")

                    // Try to recover from the stored filePath (e.g. default_identity)
                    val storedFile = File(identity.filePath)
                    if (storedFile.exists() &&
                        storedFile.length() == 64L &&
                        storedFile.absolutePath != canonicalPath.absolutePath
                    ) {
                        Log.i(TAG, "Found identity file at stored path for $hashPrefix..., copying to canonical")
                        if (!reticulumDir.exists()) reticulumDir.mkdirs()
                        storedFile.copyTo(canonicalPath, overwrite = true)
                        updateFilePathIfNeeded(identity, canonicalPath)
                        backupKeyIfMissing(identity, canonicalPath, hashPrefix)
                        return@withContext Result.success(canonicalPath.absolutePath)
                    }

                    // Try legacy plaintext keyData
                    @Suppress("DEPRECATION")
                    val legacyKeyData = identity.keyData
                    if (legacyKeyData != null && legacyKeyData.size == 64) {
                        Log.i(TAG, "Recovering file from legacy keyData for $hashPrefix...")
                        if (!reticulumDir.exists()) reticulumDir.mkdirs()
                        canonicalPath.writeBytes(legacyKeyData)
                        updateFilePathIfNeeded(identity, canonicalPath)
                        backupKeyIfMissing(identity, canonicalPath, hashPrefix)
                        return@withContext Result.success(canonicalPath.absolutePath)
                    }

                    // Fall back to decrypting encryptedKeyData
                    if (identity.encryptedKeyData != null) {
                        val decryptResult = keyProvider.getDecryptedKeyData(identity.identityHash)
                        if (decryptResult.isSuccess) {
                            val keyData = decryptResult.getOrThrow()
                            if (keyData.size == 64) {
                                Log.i(TAG, "Recovering file from encryptedKeyData for $hashPrefix...")
                                if (!reticulumDir.exists()) reticulumDir.mkdirs()
                                canonicalPath.writeBytes(keyData)
                                updateFilePathIfNeeded(identity, canonicalPath)
                                return@withContext Result.success(canonicalPath.absolutePath)
                            }
                        }
                    }

                    Log.e(TAG, "Cannot recover identity $hashPrefix...: no key data or file available")
                    Result.failure(
                        IllegalStateException("Identity file missing and no valid keyData backup available"),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to ensure identity file for ${identity.identityHash.take(8)}...", e)
                    Result.failure(e)
                }
            }

        /**
         * Update the database filePath if it differs from the canonical path.
         */
        private suspend fun updateFilePathIfNeeded(
            identity: LocalIdentityEntity,
            canonicalPath: File,
        ) {
            if (identity.filePath != canonicalPath.absolutePath) {
                Log.i(TAG, "Updating filePath for ${identity.identityHash.take(8)}...")
                identityDao.updateFilePath(identity.identityHash, canonicalPath.absolutePath)
            }
        }

        /**
         * Opportunistic key backup: if the identity file exists but encryptedKeyData is null,
         * read the key from the file and encrypt it now to prevent future data loss.
         */
        private suspend fun backupKeyIfMissing(
            identity: LocalIdentityEntity,
            keyFile: File,
            hashPrefix: String,
        ) {
            if (identity.encryptedKeyData != null) return

            try {
                val keyData = keyFile.readBytes()
                if (keyData.size != 64) return

                val encrypted = keyEncryptor.encryptWithDeviceKey(keyData)
                identityDao.updateEncryptedKeyData(
                    identityHash = identity.identityHash,
                    encryptedKeyData = encrypted,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                )
                Log.i(TAG, "Opportunistic key backup saved for $hashPrefix...")
            } catch (e: Exception) {
                Log.w(TAG, "Opportunistic key backup failed for $hashPrefix...", e)
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
        @Suppress("LongMethod", "CyclomaticComplexMethod") // Migration logic is complex but cohesive
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

                if (keyData == null || keyData.size != 64) {
                    android.util.Log.e(
                        "IdentityRepository",
                        "Cannot migrate identity: key data unavailable or invalid size (${keyData?.size})",
                    )
                    // If the file exists but content is invalid, retry will never succeed —
                    // remove the placeholder to avoid an infinite failed-migration loop.
                    // If the file doesn't exist yet, keep placeholder so we retry when it appears.
                    if (defaultIdentityFile.exists() && placeholder != null) {
                        android.util.Log.w("IdentityRepository", "File exists but invalid — removing placeholder")
                        identityDao.delete(placeholder.identityHash)
                    }
                    return@withContext
                }

                // Encrypt the key data — abort if encryption fails to prevent orphaned identities
                val encryptedKeyData =
                    try {
                        keyEncryptor.encryptWithDeviceKey(keyData)
                    } catch (e: Exception) {
                        android.util.Log.e("IdentityRepository", "Failed to encrypt key data, will retry", e)
                        return@withContext
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
                        keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
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
         * Re-wrap a raw identity key with the current device Keystore key and
         * overwrite `encryptedKeyData` on the matching identity row.
         *
         * Used by the identity-unlock flow after an Auto Backup restore on a
         * new device — the Keystore-wrapped blob survived the restore but the
         * Keystore AES key that produced it did not (Keystore keys don't cross
         * the uninstall boundary). The user re-imports their `.identity` file,
         * we parse the raw 64 bytes out of it, and this method rewrites the
         * row so the next `decryptDeliveryKey` call succeeds.
         */
        suspend fun rewrapKeyWithDeviceKey(
            identityHash: String,
            keyData: ByteArray,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val encrypted = keyEncryptor.encryptWithDeviceKey(keyData)
                    identityDao.updateEncryptedKeyData(
                        identityHash = identityHash,
                        encryptedKeyData = encrypted,
                        version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    )
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-wrap key for ${identityHash.take(8)}...", e)
                    Result.failure(e)
                }
            }

        /**
         * Run the encryption migration for all unencrypted identities.
         * This should be called during app initialization.
         */
        suspend fun runEncryptionMigration() =
            withContext(ioDispatcher) {
                Log.i(TAG, "Running identity key encryption migration...")
                val result = keyMigrator.migrateUnencryptedIdentities()
                result
                    .onSuccess { migrationResult ->
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
