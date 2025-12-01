package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "conversations",
    primaryKeys = ["peerHash", "identityHash"], // Composite PK for identity separation
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE, // Delete conversations when identity deleted
        ),
    ],
    indices = [Index("identityHash")], // Index for faster queries
)
data class ConversationEntity(
    val peerHash: String, // Destination hash of peer
    val identityHash: String, // Which local identity owns this conversation
    val peerName: String, // Name from announce app_data or "Unknown"
    val peerPublicKey: ByteArray? = null, // Public key of peer for identity restoration
    val lastMessage: String, // Preview of last message
    val lastMessageTimestamp: Long, // Timestamp of last message (for sorting)
    val unreadCount: Int = 0, // Number of unread messages
    val lastSeenTimestamp: Long = 0, // When user last viewed this conversation
)
