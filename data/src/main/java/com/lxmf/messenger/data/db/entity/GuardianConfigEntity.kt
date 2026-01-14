package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores guardian (parental control) configuration for a local identity.
 *
 * When a guardian is paired:
 * - guardianDestinationHash: The parent's LXMF destination (for allowing their messages)
 * - guardianPublicKey: The parent's Ed25519 public key (for verifying control command signatures)
 *
 * Lock state:
 * - isLocked: When true, only contacts in the allow list can send/receive messages
 * - lockedTimestamp: When the lock was applied
 *
 * Anti-replay protection:
 * - lastCommandNonce: The nonce from the last processed command (reject duplicates)
 * - lastCommandTimestamp: Timestamp of last command (reject stale commands)
 */
@Entity(
    tableName = "guardian_config",
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("identityHash")],
)
data class GuardianConfigEntity(
    @PrimaryKey
    val identityHash: String,
    // Guardian identity (null if no guardian paired)
    val guardianDestinationHash: String? = null,
    val guardianPublicKey: ByteArray? = null,
    val guardianName: String? = null,
    // Lock state
    val isLocked: Boolean = false,
    val lockedTimestamp: Long = 0,
    // Anti-replay
    val lastCommandNonce: String? = null,
    val lastCommandTimestamp: Long = 0,
    // Pairing metadata
    val pairedTimestamp: Long = 0,
) {
    fun hasGuardian(): Boolean = guardianDestinationHash != null && guardianPublicKey != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GuardianConfigEntity

        if (identityHash != other.identityHash) return false
        if (guardianDestinationHash != other.guardianDestinationHash) return false
        if (guardianPublicKey != null) {
            if (other.guardianPublicKey == null) return false
            if (!guardianPublicKey.contentEquals(other.guardianPublicKey)) return false
        } else if (other.guardianPublicKey != null) {
            return false
        }
        if (guardianName != other.guardianName) return false
        if (isLocked != other.isLocked) return false
        if (lockedTimestamp != other.lockedTimestamp) return false
        if (lastCommandNonce != other.lastCommandNonce) return false
        if (lastCommandTimestamp != other.lastCommandTimestamp) return false
        if (pairedTimestamp != other.pairedTimestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identityHash.hashCode()
        result = 31 * result + (guardianDestinationHash?.hashCode() ?: 0)
        result = 31 * result + (guardianPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (guardianName?.hashCode() ?: 0)
        result = 31 * result + isLocked.hashCode()
        result = 31 * result + lockedTimestamp.hashCode()
        result = 31 * result + (lastCommandNonce?.hashCode() ?: 0)
        result = 31 * result + lastCommandTimestamp.hashCode()
        result = 31 * result + pairedTimestamp.hashCode()
        return result
    }
}
