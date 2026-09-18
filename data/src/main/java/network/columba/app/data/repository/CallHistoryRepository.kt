package network.columba.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.dao.CallHistoryDeletionDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.model.CallHistoryRecord

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryRepository
    @Inject
    constructor(
        private val callHistoryDao: CallHistoryDao,
        private val callHistoryDeletionDao: CallHistoryDeletionDao,
        private val localIdentityDao: LocalIdentityDao,
        private val peerIconDao: PeerIconDao,
    ) {
        fun observeHistory(query: String = ""): Flow<List<CallHistoryRecord>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    observeHistory(identity.identityHash, query)
                }
            }

        fun observeActiveIdentityHash(): Flow<String?> =
            localIdentityDao.getActiveIdentity().map { it?.identityHash }.distinctUntilChanged()

        fun observeHistory(localIdentityHash: String, query: String): Flow<List<CallHistoryRecord>> =
            callHistoryDao.observeHistory(localIdentityHash, query.trim()).flatMapLatest(::enrichIcons)

        suspend fun getRecord(callAttemptId: String): CallHistoryRecord? {
            val identityHash = callHistoryDao.getByAttemptId(callAttemptId)?.localIdentityHash ?: return null
            val record = callHistoryDao.observeHistoryRecord(callAttemptId, identityHash).first() ?: return null
            val icon = record.currentDestinationHash?.let { peerIconDao.getIcon(it) }
            return record.copy(
                iconName = icon?.iconName,
                iconForegroundColor = icon?.foregroundColor,
                iconBackgroundColor = icon?.backgroundColor,
            )
        }

        fun observeRecord(callAttemptId: String): Flow<CallHistoryRecord?> =
            flow {
                emit(callHistoryDao.getByAttemptId(callAttemptId)?.localIdentityHash)
            }.flatMapLatest { recordedIdentityHash ->
                if (recordedIdentityHash == null) {
                    flowOf(null)
                } else {
                    callHistoryDao.observeHistoryRecord(callAttemptId, recordedIdentityHash).flatMapLatest { record ->
                        val destination = record?.currentDestinationHash
                        if (record == null || destination == null) {
                            flowOf(record)
                        } else {
                            peerIconDao.observeIcon(destination).map { icon ->
                                record.copy(
                                    iconName = icon?.iconName,
                                    iconForegroundColor = icon?.foregroundColor,
                                    iconBackgroundColor = icon?.backgroundColor,
                                )
                            }
                        }
                    }
                }
            }

        private fun enrichIcons(records: List<CallHistoryRecord>): Flow<List<CallHistoryRecord>> {
            if (records.isEmpty()) return flowOf(emptyList())
            val iconFlows =
                records.map { record ->
                    record.currentDestinationHash?.let(peerIconDao::observeIcon) ?: flowOf(null)
                }
            return combine(iconFlows) { icons ->
                records.mapIndexed { index, record ->
                    val icon = icons[index]
                    record.copy(
                        iconName = icon?.iconName,
                        iconForegroundColor = icon?.foregroundColor,
                        iconBackgroundColor = icon?.backgroundColor,
                    )
                }
            }
        }

        suspend fun deleteFinalized(callAttemptId: String): Result<Unit> =
            runCatching {
                val identity = localIdentityDao.getActiveIdentitySync() ?: error("No active local identity")
                check(
                    callHistoryDeletionDao.deleteFinalized(
                        callAttemptId,
                        identity.identityHash,
                        System.currentTimeMillis(),
                    ) == 1,
                ) {
                    "Call record is missing, unfinished, or belongs to another identity"
                }
            }

        suspend fun clearFinalized(): Result<Int> =
            runCatching {
                val identity = localIdentityDao.getActiveIdentitySync() ?: error("No active local identity")
                callHistoryDeletionDao.clearFinalized(identity.identityHash, System.currentTimeMillis())
            }
    }
