package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "drafts",
    primaryKeys = ["conversationHash", "identityHash"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash", "identityHash"],
            childColumns = ["conversationHash", "identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("identityHash"),
    ],
)
data class DraftEntity(
    val conversationHash: String,
    val identityHash: String,
    val content: String,
    val updatedTimestamp: Long,
)
