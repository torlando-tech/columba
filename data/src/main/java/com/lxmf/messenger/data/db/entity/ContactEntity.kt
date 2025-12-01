package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Entity representing a user's manually curated contact.
 *
 * Contacts can be added via:
 * - Starring an announce from the network (addedVia="ANNOUNCE")
 * - Scanning a QR code (addedVia="QR_CODE")
 * - Manual entry (addedVia="MANUAL")
 * - Saving from an active conversation (addedVia="CONVERSATION")
 *
 * This table stores user customizations (nickname, notes, tags) separate from network data.
 */
@Entity(
    tableName = "contacts",
    primaryKeys = ["destinationHash", "identityHash"], // Composite PK for identity separation
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE, // Delete contacts when identity deleted
        ),
    ],
    indices = [Index("identityHash")], // Index for faster queries
)
data class ContactEntity(
    val destinationHash: String, // Hex string of destination hash
    // Identity ownership
    val identityHash: String, // Which local identity owns this contact
    // Identity data
    val publicKey: ByteArray, // Public key for identity restoration
    // User customization
    val customNickname: String? = null, // User's custom name (overrides announce name)
    val notes: String? = null, // Freeform notes
    val tags: String? = null, // JSON array of tags: ["friend", "work"]
    // Metadata
    val addedTimestamp: Long, // When contact was added
    val addedVia: String, // "ANNOUNCE", "QR_CODE", "MANUAL", or "CONVERSATION"
    val lastInteractionTimestamp: Long = 0, // Last message or interaction
    // Organization
    val isPinned: Boolean = false, // Whether pinned to top of list
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContactEntity

        if (destinationHash != other.destinationHash) return false
        if (identityHash != other.identityHash) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (customNickname != other.customNickname) return false
        if (notes != other.notes) return false
        if (tags != other.tags) return false
        if (addedTimestamp != other.addedTimestamp) return false
        if (addedVia != other.addedVia) return false
        if (lastInteractionTimestamp != other.lastInteractionTimestamp) return false
        if (isPinned != other.isPinned) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.hashCode()
        result = 31 * result + identityHash.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (customNickname?.hashCode() ?: 0)
        result = 31 * result + (notes?.hashCode() ?: 0)
        result = 31 * result + (tags?.hashCode() ?: 0)
        result = 31 * result + addedTimestamp.hashCode()
        result = 31 * result + addedVia.hashCode()
        result = 31 * result + lastInteractionTimestamp.hashCode()
        result = 31 * result + isPinned.hashCode()
        return result
    }
}
