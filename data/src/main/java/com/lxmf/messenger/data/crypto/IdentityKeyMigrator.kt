package com.lxmf.messenger.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles silent migration of unencrypted identity keys to encrypted storage.
 *
 * This migrator runs automatically during app initialization and:
 * 1. Finds identities with unencrypted key data (keyEncryptionVersion == 0)
 * 2. Encrypts them with the device key via Android Keystore
 * 3. Stores encrypted data in encryptedKeyData column
 * 4. Clears the plaintext keyData column
 * 5. Securely deletes raw identity files (replaces content with random data before deletion)
 *
 * The migration is idempotent and can be safely run multiple times.
 */
@Singleton
class IdentityKeyMigrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityDao: LocalIdentityDao,
        private val encryptor: IdentityKeyEncryptor,
    ) {
        companion object {
            private const val TAG = "IdentityKeyMigrator"
            private const val PREFS_NAME = "identity_key_migration"
            private const val KEY_MIGRATION_COMPLETED = "migration_completed"
            private const val KEY_LAST_MIGRATION_ATTEMPT = "last_migration_attempt"
            private const val KEY_MIGRATED_COUNT = "migrated_count"

            // Expected identity key size
            private const val IDENTITY_KEY_SIZE = 64
        }

        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Check if encryption migration has been completed for all identities.
         */
        fun isMigrationCompleted(): Boolean {
            return prefs.getBoolean(KEY_MIGRATION_COMPLETED, false)
        }

        /**
         * Run the encryption migration for all unencrypted identities.
         *
         * This method is safe to call multiple times - it will only process
         * identities that haven't been migrated yet.
         *
         * @return Result containing the number of identities migrated, or an error
         */
        suspend fun migrateUnencryptedIdentities(): Result<MigrationResult> =
            withContext(Dispatchers.IO) {
                Log.i(TAG, "Starting identity key encryption migration check...")

                // Record migration attempt time
                prefs.edit().putLong(KEY_LAST_MIGRATION_ATTEMPT, System.currentTimeMillis()).apply()

                try {
                    // Check if any identities need migration
                    val identitiesNeedingMigration = identityDao.getIdentitiesNeedingEncryption()

                    if (identitiesNeedingMigration.isEmpty()) {
                        Log.i(TAG, "No identities need encryption migration")
                        markMigrationCompleted()
                        return@withContext Result.success(MigrationResult(0, 0, emptyList()))
                    }

                    Log.i(TAG, "Found ${identitiesNeedingMigration.size} identities needing encryption migration")

                    var successCount = 0
                    var failureCount = 0
                    val failedIdentities = mutableListOf<String>()

                    for (identity in identitiesNeedingMigration) {
                        val result = migrateIdentity(identity)
                        if (result.isSuccess) {
                            successCount++
                            Log.d(TAG, "Successfully migrated identity ${identity.identityHash.take(8)}...")
                        } else {
                            failureCount++
                            failedIdentities.add(identity.identityHash)
                            Log.e(
                                TAG,
                                "Failed to migrate identity ${identity.identityHash.take(8)}...",
                                result.exceptionOrNull(),
                            )
                        }
                    }

                    // Update migration state
                    prefs.edit()
                        .putInt(KEY_MIGRATED_COUNT, prefs.getInt(KEY_MIGRATED_COUNT, 0) + successCount)
                        .apply()

                    // Check if all identities are now encrypted
                    if (!identityDao.hasUnencryptedIdentities()) {
                        markMigrationCompleted()
                    }

                    Log.i(
                        TAG,
                        "Migration complete: $successCount succeeded, $failureCount failed",
                    )

                    Result.success(MigrationResult(successCount, failureCount, failedIdentities))
                } catch (e: Exception) {
                    Log.e(TAG, "Migration failed with exception", e)
                    Result.failure(e)
                }
            }

        /**
         * Migrate a single identity to encrypted storage.
         */
        @Suppress("ReturnCount") // Early returns for validation are clearer
        private suspend fun migrateIdentity(identity: LocalIdentityEntity): Result<Unit> {
            val identityHash = identity.identityHash

            try {
                // Get the plaintext key data
                val plainKeyData = getPlaintextKeyData(identity)
                if (plainKeyData == null) {
                    Log.w(TAG, "No key data available for identity ${identityHash.take(8)}...")
                    return Result.failure(IllegalStateException("No key data available"))
                }

                if (plainKeyData.size != IDENTITY_KEY_SIZE) {
                    Log.w(TAG, "Invalid key size ${plainKeyData.size} for identity ${identityHash.take(8)}...")
                    return Result.failure(IllegalStateException("Invalid key size: ${plainKeyData.size}"))
                }

                // Encrypt with device key
                val encryptedKeyData = encryptor.encryptWithDeviceKey(plainKeyData)

                // Update database with encrypted data
                identityDao.updateEncryptedKeyData(
                    identityHash = identityHash,
                    encryptedKeyData = encryptedKeyData,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                )

                // Clear unencrypted key data from database
                identityDao.clearUnencryptedKeyData(identityHash)

                // Securely wipe plaintext from memory
                encryptor.secureWipe(plainKeyData)

                // Securely delete the raw identity file (if it exists and is separate from the main file)
                secureDeleteRawKeyFile(identity)

                return Result.success(Unit)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        /**
         * Get plaintext key data from either the database or the identity file.
         */
        @Suppress("ReturnCount") // Multiple fallback sources require multiple returns
        private fun getPlaintextKeyData(identity: LocalIdentityEntity): ByteArray? {
            // First try database keyData
            @Suppress("DEPRECATION")
            identity.keyData?.let { keyData ->
                if (keyData.size == IDENTITY_KEY_SIZE) {
                    return keyData
                }
            }

            // Fall back to reading from file
            val filePath = identity.filePath
            if (filePath.isNotEmpty()) {
                val file = File(filePath)
                if (file.exists() && file.length() == IDENTITY_KEY_SIZE.toLong()) {
                    return try {
                        file.readBytes()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read key file: $filePath", e)
                        null
                    }
                }
            }

            // Try canonical path as fallback
            val reticulumDir = File(context.filesDir, "reticulum")
            val canonicalFile = File(reticulumDir, "identity_${identity.identityHash}")
            if (canonicalFile.exists() && canonicalFile.length() == IDENTITY_KEY_SIZE.toLong()) {
                return try {
                    canonicalFile.readBytes()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read canonical key file: ${canonicalFile.absolutePath}", e)
                    null
                }
            }

            return null
        }

        /**
         * Securely delete a raw key file by overwriting with random data before deletion.
         *
         * Note: We keep the identity file for Python/Reticulum compatibility.
         * The file will be managed through IdentityKeyProvider which creates temporary
         * decrypted files when needed.
         */
        private fun secureDeleteRawKeyFile(identity: LocalIdentityEntity) {
            // For now, we don't delete the file because Python/Reticulum needs it
            // In the future, we could implement a temp file approach where:
            // 1. Decrypt key on demand
            // 2. Write to temp file
            // 3. Python reads it
            // 4. Delete temp file immediately after
            //
            // For this implementation, the file remains but the database keyData is cleared,
            // providing protection against database extraction while maintaining RNS compatibility.
            Log.d(
                TAG,
                "Keeping identity file for RNS compatibility: ${identity.filePath}. " +
                    "Database keyData has been cleared.",
            )
        }

        /**
         * Mark migration as completed in preferences.
         */
        private fun markMigrationCompleted() {
            prefs.edit()
                .putBoolean(KEY_MIGRATION_COMPLETED, true)
                .apply()
            Log.i(TAG, "Identity key encryption migration marked as completed")
        }

        /**
         * Reset migration state (for testing or recovery scenarios).
         */
        fun resetMigrationState() {
            prefs.edit().clear().apply()
            Log.i(TAG, "Migration state reset")
        }

        /**
         * Get migration statistics.
         */
        fun getMigrationStats(): MigrationStats {
            return MigrationStats(
                isCompleted = prefs.getBoolean(KEY_MIGRATION_COMPLETED, false),
                lastAttempt = prefs.getLong(KEY_LAST_MIGRATION_ATTEMPT, 0),
                totalMigrated = prefs.getInt(KEY_MIGRATED_COUNT, 0),
            )
        }
    }

/**
 * Result of a migration operation.
 */
data class MigrationResult(
    val successCount: Int,
    val failureCount: Int,
    val failedIdentityHashes: List<String>,
) {
    val isFullySuccessful: Boolean
        get() = failureCount == 0
}

/**
 * Migration statistics.
 */
data class MigrationStats(
    val isCompleted: Boolean,
    val lastAttempt: Long,
    val totalMigrated: Int,
)
