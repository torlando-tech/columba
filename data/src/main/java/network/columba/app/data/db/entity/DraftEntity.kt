package network.columba.app.data.db.entity

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
    // LXMF FIELD_THREAD (0x08) bytes this draft belongs to; null = Main.
    // Additive v4→v5 column (threads-spec §5). The primary key is
    // intentionally unchanged — the P1 constraint is "additive migration
    // only, no data rewrite"; the per-thread PK cutover ships with the
    // P2 draft UI that needs multi-draft storage.
    val threadId: ByteArray? = null,
)
