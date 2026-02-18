package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local identity operations.
 */
@Dao
interface LocalIdentityDao {
    /**
     * Get all identities ordered by last used timestamp (most recent first).
     */
    @Query("SELECT * FROM local_identities ORDER BY lastUsedTimestamp DESC")
    fun getAllIdentities(): Flow<List<LocalIdentityEntity>>

    /**
     * Get all identities synchronously.
     */
    @Query("SELECT * FROM local_identities ORDER BY lastUsedTimestamp DESC")
    suspend fun getAllIdentitiesSync(): List<LocalIdentityEntity>

    /**
     * Get the currently active identity.
     * There should only be one active identity at a time.
     */
    @Query("SELECT * FROM local_identities WHERE isActive = 1 LIMIT 1")
    fun getActiveIdentity(): Flow<LocalIdentityEntity?>

    /**
     * Get the currently active identity synchronously.
     * Used during app initialization when Flow observation isn't available.
     */
    @Query("SELECT * FROM local_identities WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveIdentitySync(): LocalIdentityEntity?

    /**
     * Get a specific identity by its hash.
     */
    @Query("SELECT * FROM local_identities WHERE identityHash = :identityHash")
    suspend fun getIdentity(identityHash: String): LocalIdentityEntity?

    /**
     * Insert or replace an identity.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: LocalIdentityEntity)

    /**
     * Set an identity as active and deactivate all others.
     * This is a transaction to ensure only one active identity.
     */
    @Transaction
    suspend fun setActive(identityHash: String) {
        // Deactivate all identities
        deactivateAll()
        // Activate the specified identity and update last used timestamp
        activateIdentity(identityHash, System.currentTimeMillis())
    }

    /**
     * Deactivate all identities.
     */
    @Query("UPDATE local_identities SET isActive = 0")
    suspend fun deactivateAll()

    /**
     * Activate a specific identity and update its last used timestamp.
     */
    @Query("UPDATE local_identities SET isActive = 1, lastUsedTimestamp = :timestamp WHERE identityHash = :identityHash")
    suspend fun activateIdentity(
        identityHash: String,
        timestamp: Long,
    )

    /**
     * Delete an identity by its hash.
     * Associated data (conversations, contacts, etc.) will be cascade deleted via foreign keys.
     */
    @Query("DELETE FROM local_identities WHERE identityHash = :identityHash")
    suspend fun delete(identityHash: String)

    /**
     * Update the display name of an identity.
     */
    @Query("UPDATE local_identities SET displayName = :displayName WHERE identityHash = :identityHash")
    suspend fun updateDisplayName(
        identityHash: String,
        displayName: String,
    )

    /**
     * Update the last used timestamp of an identity.
     */
    @Query("UPDATE local_identities SET lastUsedTimestamp = :timestamp WHERE identityHash = :identityHash")
    suspend fun updateLastUsedTimestamp(
        identityHash: String,
        timestamp: Long,
    )

    /**
     * Get the count of all identities.
     * Useful for checking if this is the first launch.
     */
    @Query("SELECT COUNT(*) FROM local_identities")
    suspend fun getIdentityCount(): Int

    /**
     * Check if an identity with the given hash exists.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM local_identities WHERE identityHash = :identityHash)")
    suspend fun identityExists(identityHash: String): Boolean

    /**
     * Update the file path of an identity.
     * Used when migrating from default_identity to identity_<hash> paths.
     */
    @Query("UPDATE local_identities SET filePath = :filePath WHERE identityHash = :identityHash")
    suspend fun updateFilePath(
        identityHash: String,
        filePath: String,
    )

    /**
     * Update the icon appearance of an identity.
     * @param identityHash The identity to update
     * @param iconName The icon name (e.g., Material icon name)
     * @param foregroundColor Hex RGB color for icon foreground (e.g., "FFFFFF")
     * @param backgroundColor Hex RGB color for icon background (e.g., "1E88E5")
     */
    @Query(
        "UPDATE local_identities SET iconName = :iconName, iconForegroundColor = :foregroundColor, iconBackgroundColor = :backgroundColor WHERE identityHash = :identityHash",
    )
    suspend fun updateIconAppearance(
        identityHash: String,
        iconName: String?,
        foregroundColor: String?,
        backgroundColor: String?,
    )

    /**
     * Update the encrypted key data and encryption version for an identity.
     * Used during key encryption migration to store the device-encrypted key.
     */
    @Query(
        """
        UPDATE local_identities
        SET encryptedKeyData = :encryptedKeyData,
            keyEncryptionVersion = :version
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun updateEncryptedKeyData(
        identityHash: String,
        encryptedKeyData: ByteArray,
        version: Int,
    )

    /**
     * Downgrade from password protection to device-only encryption.
     * Updates key data and clears all password-related fields.
     */
    @Query(
        """
        UPDATE local_identities
        SET encryptedKeyData = :encryptedKeyData,
            keyEncryptionVersion = :version,
            passwordSalt = NULL,
            passwordVerificationHash = NULL
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun clearPasswordProtection(
        identityHash: String,
        encryptedKeyData: ByteArray,
        version: Int,
    )

    /**
     * Update all encryption-related fields for password protection.
     */
    @Query(
        """
        UPDATE local_identities
        SET encryptedKeyData = :encryptedKeyData,
            keyEncryptionVersion = :version,
            passwordSalt = :passwordSalt,
            passwordVerificationHash = :passwordVerificationHash
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun updatePasswordProtection(
        identityHash: String,
        encryptedKeyData: ByteArray,
        version: Int,
        passwordSalt: ByteArray,
        passwordVerificationHash: ByteArray,
    )

    /**
     * Clear unencrypted key data after successful encryption migration.
     * This securely removes the plaintext key from the database.
     */
    @Query("UPDATE local_identities SET keyData = NULL WHERE identityHash = :identityHash")
    suspend fun clearUnencryptedKeyData(identityHash: String)

    /**
     * Get all identities that have unencrypted key data (need migration).
     * These have keyData != NULL and keyEncryptionVersion == 0.
     */
    @Query(
        """
        SELECT * FROM local_identities
        WHERE keyEncryptionVersion = 0
        AND (keyData IS NOT NULL OR encryptedKeyData IS NULL)
        ORDER BY lastUsedTimestamp DESC
        """,
    )
    suspend fun getIdentitiesNeedingEncryption(): List<LocalIdentityEntity>

    /**
     * Check if any identities still need encryption migration.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM local_identities
            WHERE keyEncryptionVersion = 0
            AND (keyData IS NOT NULL OR encryptedKeyData IS NULL)
        )
        """,
    )
    suspend fun hasUnencryptedIdentities(): Boolean
}
