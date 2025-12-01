package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a local Reticulum identity in the database.
 * Each identity has its own keypair and can have isolated data (contacts, conversations, messages).
 *
 * @property identityHash The 32-character hex hash of the identity (primary key)
 * @property displayName User-friendly name for the identity (e.g., "Work", "Personal")
 * @property destinationHash The LXMF destination hash for this identity
 * @property filePath Path to the identity file in app storage
 * @property keyData Raw 64-byte private key data (for backup/recovery if file is lost)
 * @property createdTimestamp Unix timestamp when the identity was created
 * @property lastUsedTimestamp Unix timestamp when the identity was last active
 * @property isActive True if this is the currently active identity (only one should be active)
 */
@Entity(tableName = "local_identities")
data class LocalIdentityEntity(
    @PrimaryKey
    val identityHash: String,
    val displayName: String,
    val destinationHash: String,
    val filePath: String,
    val keyData: ByteArray? = null,
    val createdTimestamp: Long,
    val lastUsedTimestamp: Long,
    val isActive: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LocalIdentityEntity
        return identityHash == other.identityHash
    }

    override fun hashCode(): Int = identityHash.hashCode()
}
