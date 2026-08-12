package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Minimal authority preventing a deleted call attempt from being recreated. */
@Entity(
    tableName = "call_history_deletions",
    primaryKeys = ["callAttemptId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["localIdentityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("localIdentityHash")],
)
data class CallHistoryDeletionEntity(
    val callAttemptId: String,
    val localIdentityHash: String,
    val deletedAt: Long,
)
