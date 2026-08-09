package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Reduced call-history row. Removed outcomes (UNANSWERED / DROPPED / media causes) are
 * rejected at the DAO boundary via reduced IN-clause guards, never persisted.
 */
@Entity(
    tableName = "call_history",
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["localIdentityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["localIdentityHash", "attemptedAt"]),
        Index(value = ["remoteIdentityHash"]),
    ],
)
data class CallHistoryEntity(
    @PrimaryKey
    val callAttemptId: String,
    val localIdentityHash: String,
    val remoteIdentityHash: String,
    val direction: String,
    val peerDisplayNameSnapshot: String?,
    val codecProfileCode: Int?,
    val attemptedAt: Long,
    val ringingAt: Long?,
    val connectedAt: Long?,
    val endedAt: Long?,
    val outcome: String?,
    val inferredEnding: Boolean,
    val failureReason: String?,
    val serviceInstanceId: String,
)
