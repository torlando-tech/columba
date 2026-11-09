package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/** Packed LXMF bytes kept outside message/UI cursors, with chunks below CursorWindow limits. */
@Entity(
    tableName = "message_signature_evidence",
    primaryKeys = ["messageId", "identityHash", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id", "identityHash"],
            childColumns = ["messageId", "identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SignatureEvidenceChunkEntity(
    val messageId: String,
    val identityHash: String,
    val chunkIndex: Int,
    val bytes: ByteArray,
)
