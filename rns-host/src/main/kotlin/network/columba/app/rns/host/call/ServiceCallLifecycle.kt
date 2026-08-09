package network.columba.app.rns.host.call

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.columba.app.rns.api.call.CallAttemptSnapshot
import network.columba.app.rns.api.call.CallFailureReason
import network.columba.app.rns.api.call.CallFinalOutcome
import network.columba.app.rns.api.call.CallLifecycleEvent
import network.columba.app.rns.api.call.CallLifecycleRecorder
import network.columba.app.rns.api.call.ServiceLiveCallAttemptRegistry
import network.columba.app.rns.api.call.UnconnectedOutcome
import network.columba.app.rns.api.call.toCallFinalOutcome

/**
 * Service-process owner of the backend-neutral reduced call-lifecycle seam.
 *
 * The initial Room row is made durable before outbound signalling or inbound ringing can cross
 * the externally visible call boundary. Finalization is single-shot per attempt.
 */
@Singleton
class ServiceCallLifecycle internal constructor(
    private val callHistoryDao: CallHistoryDao,
    private val serviceInstanceId: String,
    private val callAttemptIdFactory: () -> String,
    private val nowMillis: () -> Long,
    private val peerDisplayNameProvider: suspend (String) -> String? = { null },
    private val terminalCommitAcknowledgement: suspend (String, Long) -> Unit = { _, _ -> },
    private val beforeAttemptAcceptedPublication: suspend (CallAttemptSnapshot) -> Unit = {},
) : CallLifecycleRecorder {
    @Inject
    constructor(
        callHistoryDao: CallHistoryDao,
        processIdentity: ServiceProcessIdentity,
    ) : this(
        callHistoryDao = callHistoryDao,
        serviceInstanceId = processIdentity.value,
        callAttemptIdFactory = { UUID.randomUUID().toString() },
        nowMillis = System::currentTimeMillis,
    )

    private val _events = MutableSharedFlow<CallLifecycleEvent>(replay = 0)
    val events: SharedFlow<CallLifecycleEvent> = _events.asSharedFlow()

    private val _activeAttempt = MutableStateFlow<CallAttemptSnapshot?>(null)
    val activeAttempt: StateFlow<CallAttemptSnapshot?> = _activeAttempt.asStateFlow()
    private val admissionMutex = Mutex()
    private val terminalPersisted = ConcurrentHashMap.newKeySet<String>()

    suspend fun reconcileOpenAttempts(retainedAttempt: CallAttemptSnapshot? = null): Result<Int> =
        runCatching {
            admissionMutex.withLock {
                check(_activeAttempt.value == null || _activeAttempt.value == retainedAttempt) {
                    "Cannot replace active call ownership during recovery"
                }
                retainedAttempt?.let { snapshot ->
                    val durable =
                        callHistoryDao.getOpenAttemptForService(snapshot.callAttemptId, serviceInstanceId)
                            ?: error("Exact live call attempt has no matching durable row")
                    check(
                        durable.direction == snapshot.direction.name &&
                            durable.localIdentityHash == snapshot.localIdentityHash &&
                            durable.remoteIdentityHash == snapshot.remoteIdentityHash &&
                            durable.codecProfileCode == snapshot.codecProfileCode &&
                            durable.attemptedAt == snapshot.attemptedAt
                    ) {
                        "Exact live call attempt does not match durable lifecycle evidence"
                    }
                }
                callHistoryDao.reconcileOpenAttempts(
                    currentServiceInstanceId = serviceInstanceId,
                    retainedCallAttemptId = retainedAttempt?.callAttemptId,
                    endedAt = nowMillis(),
                ).also {
                    _activeAttempt.value = retainedAttempt
                }
            }
        }

    suspend fun isRetainedAttemptConnected(callAttemptId: String): Boolean {
        check(_activeAttempt.value?.callAttemptId == callAttemptId) {
            "Connected recovery evidence requested for a non-retained attempt"
        }
        return callHistoryDao.getByAttemptId(callAttemptId)?.connectedAt != null
    }

    override suspend fun acceptCallAttempt(request: CallAttemptRequest): Result<CallAttemptSnapshot> =
        admissionMutex.withLock {
            try {
                check(_activeAttempt.value == null) { "Another call attempt is already active" }
                val snapshot =
                    CallAttemptSnapshot(
                        callAttemptId = callAttemptIdFactory(),
                        direction = request.direction,
                        localIdentityHash = request.localIdentityHash.lowercase(),
                        remoteIdentityHash = request.remoteIdentityHash.lowercase(),
                        codecProfileCode = request.codecProfileCode,
                        attemptedAt = nowMillis(),
                    )
                check(
                    callHistoryDao.insertInitial(
                        CallHistoryEntity(
                            callAttemptId = snapshot.callAttemptId,
                            localIdentityHash = snapshot.localIdentityHash,
                            remoteIdentityHash = snapshot.remoteIdentityHash,
                            direction = snapshot.direction.name,
                            peerDisplayNameSnapshot = peerDisplayNameProvider(snapshot.remoteIdentityHash),
                            codecProfileCode = snapshot.codecProfileCode,
                            attemptedAt = snapshot.attemptedAt,
                            ringingAt = null,
                            connectedAt = null,
                            endedAt = null,
                            outcome = null,
                            inferredEnding = false,
                            failureReason = null,
                            serviceInstanceId = serviceInstanceId,
                        ),
                    ) != -1L,
                ) { "Call attempt ID already exists" }
                _activeAttempt.value = snapshot
                try {
                    beforeAttemptAcceptedPublication(snapshot)
                    _events.emit(CallLifecycleEvent.AttemptAccepted(snapshot))
                } catch (error: CancellationException) {
                    rollbackUnpublishedAttempt(snapshot, error)
                    throw error
                } catch (error: Exception) {
                    rollbackUnpublishedAttempt(snapshot, error)
                    throw error
                }
                Result.success(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private suspend fun rollbackUnpublishedAttempt(
        snapshot: CallAttemptSnapshot,
        publicationFailure: Throwable,
    ) {
        val rollbackFailure =
            withContext(NonCancellable) {
                runCatching {
                    check(callHistoryDao.deleteUnexposedAttempt(snapshot.callAttemptId) == 1) {
                        "Failed to roll back unpublished call attempt"
                    }
                    check(_activeAttempt.compareAndSet(snapshot, null)) {
                        "Call attempt changed while unpublished admission was rolled back"
                    }
                }.exceptionOrNull()
            }
        rollbackFailure?.let(publicationFailure::addSuppressed)
    }

    override suspend fun discardCallAttempt(callAttemptId: String): Result<Unit> =
        try {
            val active = _activeAttempt.value
            check(active?.callAttemptId == callAttemptId) {
                "Cannot discard call attempt that is not the active attempt"
            }
            check(callHistoryDao.deleteUnexposedAttempt(callAttemptId) == 1) {
                "Cannot discard call attempt whose durable row is missing or finalized"
            }
            check(_activeAttempt.compareAndSet(active, null)) {
                "Call attempt changed while discard was in progress"
            }
            _events.emit(CallLifecycleEvent.AttemptDiscarded(callAttemptId))
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    override suspend fun recordCallConnected(callAttemptId: String): Result<Unit> =
        try {
            val active = _activeAttempt.value
            check(active?.callAttemptId == callAttemptId) {
                "Cannot connect call attempt that is not the active attempt"
            }
            if (callHistoryDao.getByAttemptId(callAttemptId)?.connectedAt != null) {
                return Result.success(Unit)
            }
            val connectedAt = nowMillis()
            check(callHistoryDao.recordConnected(callAttemptId, connectedAt)) {
                "Connected milestone contradicted durable call history"
            }
            _events.emit(CallLifecycleEvent.AttemptConnected(callAttemptId, connectedAt))
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    override suspend fun recordCallRinging(callAttemptId: String): Result<Unit> =
        try {
            val active = _activeAttempt.value
            check(active?.callAttemptId == callAttemptId) {
                "Cannot ring call attempt that is not the active attempt"
            }
            if (callHistoryDao.getByAttemptId(callAttemptId)?.ringingAt != null) {
                return Result.success(Unit)
            }
            val ringingAt = nowMillis()
            check(callHistoryDao.recordRinging(callAttemptId, ringingAt)) {
                "Ringing milestone contradicted durable call history"
            }
            _events.emit(CallLifecycleEvent.AttemptRinging(callAttemptId, ringingAt))
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    override suspend fun completeConnectedCall(callAttemptId: String): Result<Unit> =
        finalizeTerminal(callAttemptId, CallFinalOutcome.CONNECTED_ENDED) { endedAt ->
            callHistoryDao.completeConnectedCall(callAttemptId, endedAt)
        }

    override suspend fun interruptCall(callAttemptId: String): Result<Unit> =
        finalizeTerminal(callAttemptId, CallFinalOutcome.INTERRUPTED) { endedAt ->
            callHistoryDao.interruptCall(callAttemptId, endedAt)
        }

    override suspend fun finalizeUnconnectedCall(
        callAttemptId: String,
        outcome: UnconnectedOutcome,
    ): Result<Unit> =
        finalizeTerminal(callAttemptId, outcome.toCallFinalOutcome()) { endedAt ->
            val active = checkNotNull(_activeAttempt.value)
            check(outcome.isValidBeforeConnection(active.direction)) {
                "Outcome $outcome is invalid for an unconnected ${active.direction} call"
            }
            callHistoryDao.finalizeUnconnectedCall(callAttemptId, endedAt, outcome.name)
        }

    override suspend fun failCallAttempt(
        callAttemptId: String,
        failureReason: CallFailureReason,
    ): Result<Unit> =
        finalizeTerminal(callAttemptId, CallFinalOutcome.FAILED) { endedAt ->
            callHistoryDao.failCallAttempt(callAttemptId, endedAt, failureReason.name)
        }

    /** True service restart finalizes the owned open attempt as `INTERRUPTED`. */
    suspend fun shutdown(): Result<Unit> {
        val active = _activeAttempt.value ?: return Result.success(Unit)
        return finalizeTerminal(active.callAttemptId, CallFinalOutcome.INTERRUPTED) { endedAt ->
            callHistoryDao.interruptCall(active.callAttemptId, endedAt)
        }
    }

    private suspend fun finalizeTerminal(
        callAttemptId: String,
        outcome: CallFinalOutcome,
        persist: suspend (Long) -> Boolean,
    ): Result<Unit> =
        try {
            val active = _activeAttempt.value
            if (active == null || active.callAttemptId != callAttemptId) {
                // Idempotent no-op for an already-finalized row; strict failure otherwise.
                val existing = callHistoryDao.getByAttemptId(callAttemptId)
                check(existing?.outcome == outcome.name) {
                    "Cannot finalize call attempt that is not the active attempt"
                }
                return Result.success(Unit)
            }
            val endedAt = nowMillis()
            val persisted = persist(endedAt)
            if (persisted) {
                terminalPersisted += callAttemptId
            } else {
                check(
                    terminalPersisted.contains(callAttemptId) &&
                        callHistoryDao.getByAttemptId(callAttemptId)?.outcome == outcome.name
                ) {
                    "Terminal finalization contradicted durable call history"
                }
            }
            terminalCommitAcknowledgement(callAttemptId, endedAt)
            withContext(NonCancellable) {
                check(_activeAttempt.compareAndSet(active, null)) {
                    "Call attempt changed while terminal finalization was in progress"
                }
                _events.emit(CallLifecycleEvent.AttemptFinalized(callAttemptId, outcome, endedAt))
                terminalPersisted.remove(callAttemptId)
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun UnconnectedOutcome.isValidBeforeConnection(direction: CallAttemptDirection): Boolean =
        when (direction) {
            CallAttemptDirection.INCOMING ->
                this in setOf(
                    UnconnectedOutcome.MISSED_INCOMING,
                    UnconnectedOutcome.DECLINED_LOCAL,
                )
            CallAttemptDirection.OUTGOING ->
                this in setOf(
                    UnconnectedOutcome.REJECTED_REMOTE,
                    UnconnectedOutcome.BUSY_REMOTE,
                    UnconnectedOutcome.CANCELLED_LOCAL,
                    UnconnectedOutcome.NOT_CONNECTED,
                )
        }
}
