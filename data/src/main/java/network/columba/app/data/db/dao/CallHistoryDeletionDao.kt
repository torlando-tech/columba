package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.columba.app.data.db.entity.CallHistoryDeletionEntity

@Dao
interface CallHistoryDeletionDao {
    @Query(
        """
        SELECT callAttemptId, localIdentityHash
        FROM call_history
        WHERE callAttemptId = :callAttemptId
          AND localIdentityHash = LOWER(:localIdentityHash)
          AND endedAt IS NOT NULL
        LIMIT 1
        """,
    )
    suspend fun getFinalizedCandidate(
        callAttemptId: String,
        localIdentityHash: String,
    ): CallHistoryDeletionCandidate?

    @Query(
        """
        SELECT callAttemptId, localIdentityHash
        FROM call_history
        WHERE localIdentityHash = LOWER(:localIdentityHash)
          AND endedAt IS NOT NULL
        """,
    )
    suspend fun getFinalizedCandidates(localIdentityHash: String): List<CallHistoryDeletionCandidate>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletion(authority: CallHistoryDeletionEntity): Long

    @Query("SELECT * FROM call_history_deletions WHERE callAttemptId = :callAttemptId")
    suspend fun getDeletion(callAttemptId: String): CallHistoryDeletionEntity?

    @Query("SELECT * FROM call_history_deletions ORDER BY deletedAt, callAttemptId")
    suspend fun getAllForExport(): List<CallHistoryDeletionEntity>

    @Query(
        """
        SELECT * FROM call_history_deletions
        WHERE localIdentityHash = LOWER(:localIdentityHash)
        ORDER BY deletedAt, callAttemptId
        """,
    )
    suspend fun getForExport(localIdentityHash: String): List<CallHistoryDeletionEntity>

    @Query(
        """
        SELECT callAttemptId, localIdentityHash, endedAt
        FROM call_history
        WHERE callAttemptId = :callAttemptId
        """,
    )
    suspend fun getExistingCall(callAttemptId: String): ExistingCallForDeletion?

    @Query(
        """
        DELETE FROM call_history
        WHERE callAttemptId = :callAttemptId
          AND localIdentityHash = LOWER(:localIdentityHash)
          AND endedAt IS NOT NULL
        """,
    )
    suspend fun deleteFinalizedRow(callAttemptId: String, localIdentityHash: String): Int

    @Transaction
    suspend fun deleteFinalized(
        callAttemptId: String,
        localIdentityHash: String,
        deletedAt: Long,
    ): Int {
        val candidate = getFinalizedCandidate(callAttemptId, localIdentityHash) ?: return 0
        insertDeletion(candidate.toEntity(deletedAt))
        check(deleteFinalizedRow(candidate.callAttemptId, candidate.localIdentityHash) == 1)
        return 1
    }

    @Transaction
    suspend fun clearFinalized(
        localIdentityHash: String,
        deletedAt: Long,
        afterCandidatesSelected: suspend () -> Unit = {},
    ): Int {
        val candidates = getFinalizedCandidates(localIdentityHash)
        afterCandidatesSelected()
        candidates.forEach { candidate ->
            insertDeletion(candidate.toEntity(deletedAt))
            check(deleteFinalizedRow(candidate.callAttemptId, candidate.localIdentityHash) == 1)
        }
        return candidates.size
    }

    @Transaction
    suspend fun importDeletion(authority: CallHistoryDeletionEntity) {
        val existingAuthority = getDeletion(authority.callAttemptId)
        require(existingAuthority == null || existingAuthority == authority) {
            "Deletion authority conflicts with immutable existing evidence"
        }
        val existingCall = getExistingCall(authority.callAttemptId)
        require(existingCall == null || existingCall.localIdentityHash == authority.localIdentityHash) {
            "Deletion authority identity conflicts with an existing call"
        }
        require(existingCall?.endedAt != null || existingCall == null) {
            "Deletion authority cannot remove an unfinished call"
        }
        insertDeletion(authority)
        if (existingCall != null) {
            check(deleteFinalizedRow(authority.callAttemptId, authority.localIdentityHash) == 1)
        }
    }
}

data class CallHistoryDeletionCandidate(
    val callAttemptId: String,
    val localIdentityHash: String,
) {
    fun toEntity(deletedAt: Long) =
        CallHistoryDeletionEntity(
            callAttemptId = callAttemptId,
            localIdentityHash = localIdentityHash,
            deletedAt = deletedAt,
        )
}

data class ExistingCallForDeletion(
    val callAttemptId: String,
    val localIdentityHash: String,
    val endedAt: Long?,
)
