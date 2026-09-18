package network.columba.app.rns.host.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.model.CallHistoryRecord

/**
 * In-memory [CallHistoryDao] fake that mirrors the reduced durable semantics:
 * idempotent admission, set-if-null milestones, one-way finalization, and
 * interrupted recovery. The default @Transaction wrappers on the real interface
 * delegate to the "IfOpen" / "Unchecked" methods implemented here.
 */
internal class RecordingCallHistoryDao(
    private var deleteFailures: Int = 0,
) : CallHistoryDao {
    val inserted = mutableListOf<CallHistoryEntity>()
    val deleted = mutableListOf<String>()

    override suspend fun insertInitialUnchecked(record: CallHistoryEntity): Long {
        if (inserted.any { it.callAttemptId == record.callAttemptId }) return -1L
        if (deleted.any { it == record.callAttemptId }) return -1L
        inserted += record
        return inserted.size.toLong()
    }

    override suspend fun wasDeleted(callAttemptId: String): Boolean = callAttemptId in deleted

    override suspend fun getByAttemptId(callAttemptId: String): CallHistoryEntity? =
        inserted.singleOrNull { it.callAttemptId == callAttemptId }

    override suspend fun getForExport(localIdentityHash: String): List<CallHistoryEntity> =
        inserted.filter { it.localIdentityHash == localIdentityHash }

    override fun observeHistory(
        localIdentityHash: String,
        query: String,
    ): Flow<List<CallHistoryRecord>> = flowOf(emptyList())

    override fun observeHistoryRecord(
        callAttemptId: String,
        localIdentityHash: String,
    ): Flow<CallHistoryRecord?> = flowOf(null)

    override suspend fun reconcileOpenAttempts(
        currentServiceInstanceId: String,
        retainedCallAttemptId: String?,
        endedAt: Long,
    ): Int {
        var updated = 0
        inserted.replaceAll { record ->
            val isRetained =
                record.serviceInstanceId == currentServiceInstanceId &&
                    record.callAttemptId == retainedCallAttemptId
            if (record.endedAt == null && !isRetained) {
                updated++
                record.copy(endedAt = endedAt, outcome = "INTERRUPTED", inferredEnding = true)
            } else {
                record
            }
        }
        return updated
    }

    override suspend fun getOpenAttemptForService(
        callAttemptId: String,
        serviceInstanceId: String,
    ): CallHistoryEntity? =
        inserted.singleOrNull {
            it.callAttemptId == callAttemptId &&
                it.serviceInstanceId == serviceInstanceId &&
                it.endedAt == null
        }

    override suspend fun setRingingIfNull(
        callAttemptId: String,
        ringingAt: Long,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].ringingAt != null) return 0
        inserted[index] = inserted[index].copy(ringingAt = ringingAt)
        return 1
    }

    override suspend fun setConnectedIfNull(
        callAttemptId: String,
        connectedAt: Long,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].connectedAt != null) return 0
        inserted[index] = inserted[index].copy(connectedAt = connectedAt)
        return 1
    }

    override suspend fun recordCodecProfile(
        callAttemptId: String,
        codecProfileCode: Int,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].endedAt != null) return 0
        val existing = inserted[index].codecProfileCode
        if (existing != null && existing != codecProfileCode) return 0
        inserted[index] = inserted[index].copy(codecProfileCode = codecProfileCode)
        return 1
    }

    override suspend fun finalizeConnectedEndedIfOpen(
        callAttemptId: String,
        endedAt: Long,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].connectedAt == null || inserted[index].outcome != null) return 0
        inserted[index] = inserted[index].copy(endedAt = endedAt, outcome = "CONNECTED_ENDED", inferredEnding = false)
        return 1
    }

    override suspend fun finalizeUnconnectedIfOpen(
        callAttemptId: String,
        endedAt: Long,
        outcome: String,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].connectedAt != null || inserted[index].outcome != null) return 0
        inserted[index] = inserted[index].copy(endedAt = endedAt, outcome = outcome, inferredEnding = false)
        return 1
    }

    override suspend fun finalizeFailedIfOpen(
        callAttemptId: String,
        endedAt: Long,
        failureReason: String,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].connectedAt != null || inserted[index].outcome != null) return 0
        inserted[index] =
            inserted[index].copy(endedAt = endedAt, outcome = "FAILED", failureReason = failureReason, inferredEnding = false)
        return 1
    }

    override suspend fun finalizeInterruptedIfOpen(
        callAttemptId: String,
        endedAt: Long,
    ): Int {
        val index = inserted.indexOfFirst { it.callAttemptId == callAttemptId }
        if (index == -1 || inserted[index].outcome != null) return 0
        inserted[index] = inserted[index].copy(endedAt = endedAt, outcome = "INTERRUPTED", inferredEnding = false)
        return 1
    }

    override fun observeForLocalIdentity(localIdentityHash: String): Flow<List<CallHistoryEntity>> =
        flowOf(inserted.filter { it.localIdentityHash == localIdentityHash.lowercase() })

    override suspend fun deleteUnexposedAttempt(callAttemptId: String): Int {
        if (deleteFailures > 0) {
            deleteFailures--
            return 0
        }
        deleted += callAttemptId
        return if (inserted.removeAll { it.callAttemptId == callAttemptId }) 1 else 0
    }
}
