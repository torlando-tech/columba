package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a local Reticulum identity in the database.
 * Each identity has its own keypair and can have isolated data (contacts, conversations, messages).
 *
 * @property identityHash The 32-character hex hash of the identity (primary key)
 * @property displayName User-friendly name for the identity (e.g., "Work", "Personal")
 * @property destinationHash The LXMF destination hash for this identity
 * @property filePath Path to the identity file in app storage
 * @property keyData Raw 64-byte private key data (DEPRECATED: use encryptedKeyData)
 * @property encryptedKeyData AES-256-GCM encrypted key data using Android Keystore
 * @property keyEncryptionVersion Encryption version: 0=plain, 1=device-only, 2=device+password
 * @property passwordSalt Salt used for password-based key derivation (if password protected)
 * @property passwordVerificationHash Hash for verifying password correctness (if password protected)
 * @property createdTimestamp Unix timestamp when the identity was created
 * @property lastUsedTimestamp Unix timestamp when the identity was last active
 * @property isActive True if this is the currently active identity (only one should be active)
 */
@Entity(
    tableName = "local_identities",
    indices = [
        Index("lastUsedTimestamp"), // For ordering
        Index("isActive"), // For active identity queries
    ],
)
data class LocalIdentityEntity(
    @PrimaryKey
    val identityHash: String,
    val displayName: String,
    val destinationHash: String,
    val filePath: String,
    @Deprecated("Use encryptedKeyData instead - this field will be cleared after migration")
    val keyData: ByteArray? = null,
    val encryptedKeyData: ByteArray? = null,
    val keyEncryptionVersion: Int = 0, // 0=plain, 1=device, 2=device+password
    val passwordSalt: ByteArray? = null,
    val passwordVerificationHash: ByteArray? = null,
    val createdTimestamp: Long,
    val lastUsedTimestamp: Long,
    val isActive: Boolean,
    val iconName: String? = null,
    val iconForegroundColor: String? = null, // Hex RGB e.g., "FFFFFF"
    val iconBackgroundColor: String? = null, // Hex RGB e.g., "1E88E5"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LocalIdentityEntity
        return identityHash == other.identityHash
    }

    override fun hashCode(): Int = identityHash.hashCode()
}
