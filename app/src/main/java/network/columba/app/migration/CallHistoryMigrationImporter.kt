package network.columba.app.migration

import android.util.Log
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.CallHistoryDeletionEntity
import network.columba.app.data.db.entity.CallHistoryEntity

/**
 * Reduced call-history migration importer.
 *
 * Only the reduced outcome vocabulary is accepted: `CONNECTED_ENDED`,
 * `MISSED_INCOMING`, `DECLINED_LOCAL`, `REJECTED_REMOTE`, `BUSY_REMOTE`,
 * `CANCELLED_LOCAL`, `NOT_CONNECTED`, `FAILED`, and `INTERRUPTED`.
 * Authoritative `UNANSWERED`, `DROPPED`, and media/transport causes are
 * rejected. `FAILED` carries only a reduced Columba-observed prerequisite reason.
 * Import is additive and idempotent by attempt ID; conflicting rows are skipped and
 * diagnosed; malformed input fails transactionally without partial history.
 */
internal class CallHistoryMigrationImporter(
    private val database: ColumbaDatabase,
) {
    data class Result(
        val imported: Int,
        val conflicts: Int,
    )

    private val outgoingOutcomes =
        setOf(
            "CONNECTED_ENDED", "REJECTED_REMOTE", "BUSY_REMOTE", "CANCELLED_LOCAL",
            "NOT_CONNECTED", "FAILED", "INTERRUPTED",
        )
    private val incomingOutcomes =
        setOf(
            "CONNECTED_ENDED", "MISSED_INCOMING", "DECLINED_LOCAL", "FAILED", "INTERRUPTED",
        )
    private val connectedOutcomes = setOf("CONNECTED_ENDED")
    private val unconnectedOutcomes =
        setOf(
            "MISSED_INCOMING", "DECLINED_LOCAL", "REJECTED_REMOTE", "BUSY_REMOTE",
            "CANCELLED_LOCAL", "NOT_CONNECTED", "FAILED",
        )
    private val failureReasons =
        setOf(
            "NETWORK_UNAVAILABLE", "MICROPHONE_PERMISSION_DENIED", "LOCAL_IDENTITY_UNAVAILABLE",
            "ANOTHER_CALL_ACTIVE", "INVALID_PEER_IDENTITY", "SERVICE_STARTUP_FAILURE",
            "UNKNOWN_PREREQUISITE_FAILURE",
        )

    fun validate(records: List<CallHistoryExport>) {
        records.forEach { record ->
            require(record.callAttemptId.isNotBlank()) { "Call history attempt ID is empty" }
            require(record.localIdentityHash.isNotBlank() && record.remoteIdentityHash.isNotBlank()) {
                "Call history identity hash is empty"
            }
            require(record.localIdentityHash.isIdentityHash() && record.remoteIdentityHash.isIdentityHash()) {
                "Call history identity hash is malformed"
            }
            val allowedOutcomes =
                when (record.direction) {
                    "OUTGOING" -> outgoingOutcomes
                    "INCOMING" -> incomingOutcomes
                    else -> error("Unsupported call history direction")
                }
            validateTimeline(record)
            val endedAt = record.endedAt
            val outcome = record.outcome
            if (endedAt == null || outcome == null) {
                require(endedAt == null && outcome == null) { "Call history final evidence is incomplete" }
                require(!record.inferredEnding && record.failureReason == null) { "Open call contains terminal evidence" }
                val latestOpenTimestamp =
                    maxOf(record.attemptedAt, record.ringingAt ?: record.attemptedAt, record.connectedAt ?: record.attemptedAt)
                require(latestOpenTimestamp < Long.MAX_VALUE) { "Open call recovery timestamp would overflow" }
            } else {
                validateFinalized(record, endedAt, outcome, allowedOutcomes)
            }
        }
    }

    fun validateDeletions(deletions: List<CallHistoryDeletionExport>) {
        require(deletions.map { it.callAttemptId }.distinct().size == deletions.size) {
            "Call deletion attempt ID is duplicated"
        }
        deletions.forEach { deletion ->
            require(deletion.callAttemptId.isNotBlank()) { "Call deletion attempt ID is empty" }
            require(deletion.localIdentityHash.isIdentityHash()) { "Call deletion identity hash is malformed" }
            require(deletion.deletedAt >= 0L) { "Call deletion timestamp is invalid" }
        }
    }

    suspend fun importDeletions(deletions: List<CallHistoryDeletionExport>) {
        deletions.forEach { deletion ->
            database.callHistoryDeletionDao().importDeletion(
                CallHistoryDeletionEntity(
                    callAttemptId = deletion.callAttemptId,
                    localIdentityHash = deletion.localIdentityHash.lowercase(),
                    deletedAt = deletion.deletedAt,
                ),
            )
        }
    }

    suspend fun import(records: List<CallHistoryExport>): Result {
        var imported = 0
        var conflicts = 0
        val dao = database.callHistoryDao()
        records.forEach { exported ->
            val entity = exported.toEntity()
            val existing = dao.getByAttemptId(exported.callAttemptId)
            when {
                dao.wasDeleted(exported.callAttemptId) -> conflicts++
                existing == null -> {
                    if (dao.insertInitial(entity) != -1L) imported++ else conflicts++
                }
                existing.sameTransferEvidence(entity) -> Unit
                else -> {
                    conflicts++
                    Log.w(TAG, "Skipped conflicting call history attempt ${exported.callAttemptId}")
                }
            }
        }
        return Result(imported, conflicts)
    }

    private fun validateTimeline(record: CallHistoryExport) {
        require(record.attemptedAt >= 0L) { "Call history attempted timestamp is invalid" }
        require(record.ringingAt == null || record.ringingAt >= record.attemptedAt) {
            "Call history ringing timestamp is out of order"
        }
        require(record.connectedAt == null || record.connectedAt >= record.attemptedAt) {
            "Call history connected timestamp is out of order"
        }
        require(record.ringingAt == null || record.connectedAt == null || record.ringingAt <= record.connectedAt) {
            "Call history ringing timestamp follows connection"
        }
    }

    private fun validateFinalized(
        record: CallHistoryExport,
        endedAt: Long,
        outcome: String,
        allowedOutcomes: Set<String>,
    ) {
        require(outcome in allowedOutcomes) { "Outcome is incompatible with call direction" }
        require(record.attemptedAt <= endedAt) { "Call history timestamps are out of order" }
        require(record.ringingAt == null || record.ringingAt <= endedAt) { "Call history ringing timestamp is out of order" }
        require(record.connectedAt == null || record.connectedAt <= endedAt) { "Call history connected timestamp is out of order" }
        require(record.connectedAt != null || outcome !in connectedOutcomes) {
            "Connected call outcome has no connection timestamp"
        }
        require(record.connectedAt == null || outcome !in unconnectedOutcomes) {
            "Unconnected call outcome contains connection evidence"
        }
        require(!record.inferredEnding || outcome == "INTERRUPTED") {
            "Only interrupted calls may have inferred endings"
        }
        if (outcome == "FAILED") {
            require(record.failureReason in failureReasons) { "Failed call has unsupported failure evidence" }
        } else {
            require(record.failureReason == null) { "Unexpected call failure evidence" }
        }
    }

    private fun CallHistoryExport.toEntity(): CallHistoryEntity {
        val wasOpen = endedAt == null
        val recoveredAt = maxOf(attemptedAt, ringingAt ?: attemptedAt, connectedAt ?: attemptedAt) + 1L
        return CallHistoryEntity(
            callAttemptId = callAttemptId,
            localIdentityHash = localIdentityHash.lowercase(),
            remoteIdentityHash = remoteIdentityHash.lowercase(),
            direction = direction,
            peerDisplayNameSnapshot = peerDisplayNameSnapshot,
            codecProfileCode = codecProfileCode,
            attemptedAt = attemptedAt,
            ringingAt = ringingAt,
            connectedAt = connectedAt,
            endedAt = endedAt ?: recoveredAt,
            outcome = outcome ?: "INTERRUPTED",
            inferredEnding = inferredEnding || wasOpen,
            failureReason = failureReason,
            serviceInstanceId = "migration-import-v8",
        )
    }

    private fun CallHistoryEntity.sameTransferEvidence(other: CallHistoryEntity): Boolean =
        copy(serviceInstanceId = other.serviceInstanceId) == other

    private fun String.isIdentityHash(): Boolean = matches(Regex("^[0-9a-fA-F]{32}$"))

    private companion object {
        const val TAG = "CallHistoryImporter"
    }
}
