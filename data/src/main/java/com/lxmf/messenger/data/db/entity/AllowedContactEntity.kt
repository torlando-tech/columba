package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Represents a contact that is allowed to communicate when parental controls are locked.
 *
 * When a device is locked by a guardian:
 * - Only contacts in this list can send messages to or receive messages from this identity
 * - The guardian is automatically allowed (stored in GuardianConfigEntity, not here)
 *
 * The guardian manages this list via signed LXMF control commands (ALLOW_ADD, ALLOW_REMOVE, ALLOW_SET).
 */
@Entity(
    tableName = "allowed_contacts",
    primaryKeys = ["identityHash", "contactHash"],
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
data class AllowedContactEntity(
    val identityHash: String,
    val contactHash: String,
    val displayName: String? = null,
    val addedTimestamp: Long,
)
