package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.model.CallHistoryRecord

/**
 * Reduced call-history DAO. Removed rejected lifecycle semantics: no DROPPED, no
 * UNANSWERED, no media/transport failure finalizers, no late-outcome promotion.
 * Connected attempts finalize only as CONNECTED_ENDED or INTERRUPTED; pre-connection
 * attempts finalize as explicit reduced outcomes, NOT_CONNECTED, FAILED, or INTERRUPTED.
 */
@Dao
@Suppress("TooManyFunctions")
interface CallHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitialUnchecked(record: CallHistoryEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM call_history_deletions WHERE callAttemptId = :callAttemptId)")
    suspend fun wasDeleted(callAttemptId: String): Boolean

    @Transaction
    suspend fun insertInitial(record: CallHistoryEntity): Long =
        if (wasDeleted(record.callAttemptId)) -1L else insertInitialUnchecked(record)

    @Query("SELECT * FROM call_history WHERE callAttemptId = :callAttemptId")
    suspend fun getByAttemptId(callAttemptId: String): CallHistoryEntity?

    @Query(
        """
        SELECT * FROM call_history
        WHERE localIdentityHash = :localIdentityHash
        ORDER BY attemptedAt ASC, callAttemptId ASC
        """,
    )
    suspend fun getForExport(localIdentityHash: String): List<CallHistoryEntity>

    @Transaction
    suspend fun recordRinging(
        callAttemptId: String,
        ringingAt: Long,
    ): Boolean {
        if (setRingingIfNull(callAttemptId, ringingAt) == 1) return true
        return getByAttemptId(callAttemptId)?.ringingAt == ringingAt
    }

    @Query(
        """
        UPDATE call_history
        SET ringingAt = :ringingAt
        WHERE callAttemptId = :callAttemptId
          AND ringingAt IS NULL
          AND attemptedAt <= :ringingAt
          AND (connectedAt IS NULL OR :ringingAt <= connectedAt)
          AND (endedAt IS NULL OR :ringingAt <= endedAt)
          AND (outcome IS NULL OR outcome IN (
              'MISSED_INCOMING', 'DECLINED_LOCAL', 'REJECTED_REMOTE', 'BUSY_REMOTE',
              'CANCELLED_LOCAL', 'NOT_CONNECTED', 'FAILED', 'INTERRUPTED'
          ))
        """,
    )
    suspend fun setRingingIfNull(
        callAttemptId: String,
        ringingAt: Long,
    ): Int

    @Transaction
    suspend fun recordConnected(
        callAttemptId: String,
        connectedAt: Long,
    ): Boolean {
        if (setConnectedIfNull(callAttemptId, connectedAt) == 1) return true
        return getByAttemptId(callAttemptId)?.connectedAt == connectedAt
    }

    @Query(
        """
        UPDATE call_history
        SET connectedAt = :connectedAt
        WHERE callAttemptId = :callAttemptId
          AND connectedAt IS NULL
          AND attemptedAt <= :connectedAt
          AND (endedAt IS NULL OR :connectedAt <= endedAt)
          AND (outcome IS NULL OR outcome IN ('CONNECTED_ENDED', 'INTERRUPTED'))
        """,
    )
    suspend fun setConnectedIfNull(
        callAttemptId: String,
        connectedAt: Long,
    ): Int

    @Query(
        """
        UPDATE call_history
        SET codecProfileCode = :codecProfileCode
        WHERE callAttemptId = :callAttemptId
          AND endedAt IS NULL
          AND (codecProfileCode IS NULL OR codecProfileCode = :codecProfileCode)
        """,
    )
    suspend fun recordCodecProfile(
        callAttemptId: String,
        codecProfileCode: Int,
    ): Int

    @Transaction
    suspend fun completeConnectedCall(
        callAttemptId: String,
        endedAt: Long,
    ): Boolean {
        if (finalizeConnectedEndedIfOpen(callAttemptId, endedAt) == 1) return true
        val existing = getByAttemptId(callAttemptId) ?: return false
        return existing.outcome == "CONNECTED_ENDED" && existing.endedAt == endedAt
    }

    @Query(
        """
        UPDATE call_history
        SET endedAt = :endedAt, outcome = 'CONNECTED_ENDED', inferredEnding = 0, failureReason = NULL
        WHERE callAttemptId = :callAttemptId
          AND outcome IS NULL
          AND connectedAt IS NOT NULL
          AND connectedAt <= :endedAt
        """,
    )
    suspend fun finalizeConnectedEndedIfOpen(
        callAttemptId: String,
        endedAt: Long,
    ): Int

    @Transaction
    suspend fun finalizeUnconnectedCall(
        callAttemptId: String,
        endedAt: Long,
        outcome: String,
    ): Boolean {
        if (finalizeUnconnectedIfOpen(callAttemptId, endedAt, outcome) == 1) return true
        val existing = getByAttemptId(callAttemptId) ?: return false
        return existing.outcome == outcome && existing.endedAt == endedAt
    }

    @Query(
        """
        UPDATE call_history
        SET endedAt = :endedAt, outcome = :outcome, inferredEnding = 0, failureReason = NULL
        WHERE callAttemptId = :callAttemptId
          AND outcome IS NULL
          AND connectedAt IS NULL
          AND attemptedAt <= :endedAt
          AND (ringingAt IS NULL OR ringingAt <= :endedAt)
          AND (
              (direction = 'INCOMING' AND :outcome IN (
                  'MISSED_INCOMING', 'DECLINED_LOCAL', 'FAILED', 'INTERRUPTED'
              ))
              OR
              (direction = 'OUTGOING' AND :outcome IN (
                  'REJECTED_REMOTE', 'BUSY_REMOTE', 'CANCELLED_LOCAL',
                  'NOT_CONNECTED', 'FAILED', 'INTERRUPTED'
              ))
          )
        """,
    )
    suspend fun finalizeUnconnectedIfOpen(
        callAttemptId: String,
        endedAt: Long,
        outcome: String,
    ): Int

    @Transaction
    suspend fun failCallAttempt(
        callAttemptId: String,
        endedAt: Long,
        failureReason: String,
    ): Boolean {
        if (finalizeFailedIfOpen(callAttemptId, endedAt, failureReason) == 1) return true
        val existing = getByAttemptId(callAttemptId) ?: return false
        return existing.outcome == "FAILED" && existing.failureReason == failureReason
    }

    @Query(
        """
        UPDATE call_history
        SET endedAt = :endedAt, outcome = 'FAILED', inferredEnding = 0, failureReason = :failureReason
        WHERE callAttemptId = :callAttemptId
          AND outcome IS NULL
          AND connectedAt IS NULL
          AND attemptedAt <= :endedAt
        """,
    )
    suspend fun finalizeFailedIfOpen(
        callAttemptId: String,
        endedAt: Long,
        failureReason: String,
    ): Int

    @Transaction
    suspend fun interruptCall(
        callAttemptId: String,
        endedAt: Long,
    ): Boolean {
        if (finalizeInterruptedIfOpen(callAttemptId, endedAt) == 1) return true
        val existing = getByAttemptId(callAttemptId) ?: return false
        return existing.outcome == "INTERRUPTED" && existing.endedAt == endedAt
    }

    @Query(
        """
        UPDATE call_history
        SET endedAt = :endedAt,
            outcome = 'INTERRUPTED',
            inferredEnding = 1,
            failureReason = NULL
        WHERE endedAt IS NULL
          AND (
              serviceInstanceId != :currentServiceInstanceId
              OR :retainedCallAttemptId IS NULL
              OR callAttemptId != :retainedCallAttemptId
          )
        """,
    )
    suspend fun reconcileOpenAttempts(
        currentServiceInstanceId: String,
        retainedCallAttemptId: String?,
        endedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM call_history
        WHERE callAttemptId = :callAttemptId
          AND serviceInstanceId = :serviceInstanceId
          AND endedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun getOpenAttemptForService(
        callAttemptId: String,
        serviceInstanceId: String,
    ): CallHistoryEntity?

    @Query(
        """
        UPDATE call_history
        SET endedAt = :endedAt, outcome = 'INTERRUPTED', inferredEnding = 0, failureReason = NULL
        WHERE callAttemptId = :callAttemptId
          AND outcome IS NULL
          AND attemptedAt <= :endedAt
          AND (ringingAt IS NULL OR ringingAt <= :endedAt)
          AND (connectedAt IS NULL OR connectedAt <= :endedAt)
        """,
    )
    suspend fun finalizeInterruptedIfOpen(
        callAttemptId: String,
        endedAt: Long,
    ): Int

    @Query(
        "SELECT * FROM call_history WHERE localIdentityHash = LOWER(:localIdentityHash) " +
            "ORDER BY attemptedAt DESC",
    )
    fun observeForLocalIdentity(localIdentityHash: String): Flow<List<CallHistoryEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT
                h.callAttemptId,
                h.localIdentityHash,
                h.remoteIdentityHash,
                h.direction,
                h.peerDisplayNameSnapshot,
                h.codecProfileCode,
                h.attemptedAt,
                h.ringingAt,
                h.connectedAt,
                h.endedAt,
                h.outcome,
                h.inferredEnding,
                h.failureReason,
                COALESCE(
                    (
                        SELECT ct.customNickname
                        FROM announces a
                        JOIN contacts ct
                          ON ct.destinationHash = a.destinationHash
                         AND ct.identityHash = h.localIdentityHash
                        WHERE a.computedIdentityHash = h.remoteIdentityHash
                          AND ct.customNickname IS NOT NULL
                          AND TRIM(ct.customNickname) != ''
                        ORDER BY
                            CASE a.aspect
                                WHEN 'lxmf.delivery' THEN 0
                                WHEN 'lxst.telephony' THEN 1
                                ELSE 2
                            END,
                            a.lastSeenTimestamp DESC
                        LIMIT 1
                    ),
                    (
                        SELECT a.peerName
                        FROM announces a
                        WHERE a.computedIdentityHash = h.remoteIdentityHash
                          AND TRIM(a.peerName) != ''
                        ORDER BY
                            CASE a.aspect
                                WHEN 'lxmf.delivery' THEN 0
                                WHEN 'lxst.telephony' THEN 1
                                ELSE 2
                            END,
                            a.lastSeenTimestamp DESC
                        LIMIT 1
                    ),
                    (
                        SELECT c.peerName
                        FROM conversations c
                        WHERE c.identityHash = h.localIdentityHash
                          AND TRIM(c.peerName) != ''
                          AND (
                              c.peerHash = h.remoteIdentityHash OR EXISTS (
                                  SELECT 1 FROM announces a
                                  WHERE a.destinationHash = c.peerHash
                                    AND a.computedIdentityHash = h.remoteIdentityHash
                                    AND a.aspect IN ('lxmf.delivery', 'lxst.telephony')
                              )
                          )
                        ORDER BY c.lastMessageTimestamp DESC
                        LIMIT 1
                    ),
                    h.peerDisplayNameSnapshot
                ) AS displayName,
                (
                    SELECT a.destinationHash
                    FROM announces a
                    WHERE a.computedIdentityHash = h.remoteIdentityHash
                      AND a.aspect IN ('lxmf.delivery', 'lxst.telephony')
                    ORDER BY
                        CASE a.aspect
                            WHEN 'lxmf.delivery' THEN 0
                            WHEN 'lxst.telephony' THEN 1
                            ELSE 2
                        END,
                        a.lastSeenTimestamp DESC
                    LIMIT 1
                ) AS currentDestinationHash,
                (
                    SELECT li.displayName FROM local_identities li
                    WHERE li.identityHash = h.localIdentityHash LIMIT 1
                ) AS localIdentityName
            FROM call_history h
            WHERE h.localIdentityHash = LOWER(:localIdentityHash)
        )
        WHERE :query = ''
           OR displayName LIKE '%' || :query || '%' COLLATE NOCASE
           OR remoteIdentityHash LIKE '%' || :query || '%' COLLATE NOCASE
           OR EXISTS (
                SELECT 1 FROM announces a
                WHERE a.computedIdentityHash = remoteIdentityHash
                  AND a.peerName LIKE '%' || :query || '%' COLLATE NOCASE
           )
           OR EXISTS (
                SELECT 1
                FROM announces a
                JOIN contacts ct ON ct.destinationHash = a.destinationHash
                  AND ct.identityHash = localIdentityHash
                WHERE a.computedIdentityHash = remoteIdentityHash
                  AND ct.customNickname LIKE '%' || :query || '%' COLLATE NOCASE
           )
        ORDER BY attemptedAt DESC
        """,
    )
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    fun observeHistory(
        localIdentityHash: String,
        query: String,
    ): Flow<List<CallHistoryRecord>>

    @Query(
        """
        SELECT
            h.callAttemptId,
            h.localIdentityHash,
            h.remoteIdentityHash,
            h.direction,
            h.peerDisplayNameSnapshot,
            h.codecProfileCode,
            h.attemptedAt,
            h.ringingAt,
            h.connectedAt,
            h.endedAt,
            h.outcome,
            h.inferredEnding,
            h.failureReason,
            COALESCE(
                (
                    SELECT ct.customNickname
                    FROM announces a
                    JOIN contacts ct
                      ON ct.destinationHash = a.destinationHash
                     AND ct.identityHash = h.localIdentityHash
                    WHERE a.computedIdentityHash = h.remoteIdentityHash
                      AND ct.customNickname IS NOT NULL
                      AND TRIM(ct.customNickname) != ''
                    ORDER BY
                        CASE a.aspect
                            WHEN 'lxmf.delivery' THEN 0
                            WHEN 'lxst.telephony' THEN 1
                            ELSE 2
                        END,
                        a.lastSeenTimestamp DESC
                    LIMIT 1
                ),
                (
                    SELECT a.peerName
                    FROM announces a
                    WHERE a.computedIdentityHash = h.remoteIdentityHash
                      AND TRIM(a.peerName) != ''
                    ORDER BY
                        CASE a.aspect
                            WHEN 'lxmf.delivery' THEN 0
                            WHEN 'lxst.telephony' THEN 1
                            ELSE 2
                        END,
                        a.lastSeenTimestamp DESC
                    LIMIT 1
                ),
                (
                    SELECT c.peerName
                    FROM conversations c
                    WHERE c.identityHash = h.localIdentityHash
                      AND TRIM(c.peerName) != ''
                      AND (
                          c.peerHash = h.remoteIdentityHash OR EXISTS (
                              SELECT 1 FROM announces a
                              WHERE a.destinationHash = c.peerHash
                                AND a.computedIdentityHash = h.remoteIdentityHash
                                AND a.aspect IN ('lxmf.delivery', 'lxst.telephony')
                          )
                      )
                    ORDER BY c.lastMessageTimestamp DESC
                    LIMIT 1
                ),
                h.peerDisplayNameSnapshot
            ) AS displayName,
            (
                SELECT a.destinationHash
                FROM announces a
                WHERE a.computedIdentityHash = h.remoteIdentityHash
                  AND a.aspect IN ('lxmf.delivery', 'lxst.telephony')
                ORDER BY
                    CASE a.aspect
                        WHEN 'lxmf.delivery' THEN 0
                        WHEN 'lxst.telephony' THEN 1
                        ELSE 2
                        END,
                        a.lastSeenTimestamp DESC
                    LIMIT 1
            ) AS currentDestinationHash,
            (
                SELECT li.displayName FROM local_identities li
                WHERE li.identityHash = h.localIdentityHash LIMIT 1
            ) AS localIdentityName
        FROM call_history h
        WHERE h.callAttemptId = :callAttemptId
          AND h.localIdentityHash = LOWER(:localIdentityHash)
        LIMIT 1
        """,
    )
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    fun observeHistoryRecord(
        callAttemptId: String,
        localIdentityHash: String,
    ): Flow<CallHistoryRecord?>

    @Query("DELETE FROM call_history WHERE callAttemptId = :callAttemptId AND endedAt IS NULL")
    suspend fun deleteUnexposedAttempt(callAttemptId: String): Int
}
