package network.columba.app.rns.api.call

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serialized owner of at most one accepted call attempt.
 *
 * Reduced lifecycle owner: removed late-outcome promotion, connected drops, inferred
 * unanswered finalization, and session fences. It owns durable admission (persistence
 * completes before LXST acceptance/exposure), serializes terminal finalization (one-way),
 * and finalizes unmatched open rows as `INTERRUPTED` on true restart.
 */
@Suppress("TooManyFunctions") // A single serialized lifecycle state machine intentionally owns all transitions.
class AcceptedCallLifecycle(
    internal val recorder: CallLifecycleRecorder,
    private val scope: CoroutineScope,
    retainedAttempt: RetainedCallAttempt? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val retryDelayMillis: Long = 1_000L,
    private val shutdownRetryAttempts: Int = 3,
    private val onDeferredShutdownCleanupComplete: () -> Unit = {},
) {
    private val stateLock = Any()
    private val admissionReservation = AtomicReference<Any?>(null)
    internal val shutdownStarted = AtomicBoolean(false)
    private val observations = Channel<AcceptedCallObservation>(Channel.UNLIMITED)
    @Volatile
    var activeAttempt: CallAttemptSnapshot? = retainedAttempt?.snapshot
        private set(value) {
            val previous = field
            field = value
            if (value == null) {
                previous?.let { ServiceLiveCallAttemptRegistry.clearIf(it.callAttemptId) }
            } else {
                ServiceLiveCallAttemptRegistry.retain(value)
            }
        }
    @Volatile
    internal var connectedPersisted = retainedAttempt?.connected == true
    @Volatile
    internal var pendingTerminalOutcome: UnconnectedOutcome? = null
    @Volatile
    internal var pendingFailed: CallFailureReason? = null

    private val cleanupRetrier =
        AcceptedCallCleanupRetrier(
            scope,
            dispatcher,
            retryDelayMillis,
            shutdownRetryAttempts,
            isOwned = { activeAttempt?.callAttemptId == it },
            release = ::releaseIfExact,
        )

    init {
        scope.launch(dispatcher) {
            for (observation in observations) process(observation)
        }
    }

    internal fun submit(observation: AcceptedCallObservation) = observations.trySend(observation)

    suspend fun admitIncoming(
        request: CallAttemptRequest,
        expose: suspend (CallAttemptSnapshot) -> Unit,
    ): Result<Unit> = admit(request, persistRinging = true, expose = expose)

    suspend fun admitOutgoing(
        request: CallAttemptRequest,
        launch: suspend (CallAttemptSnapshot) -> Unit,
    ): Result<Unit> = admit(request, persistRinging = false, expose = launch)

    /** The established callback fired; applies only to the owned remote identity. */
    fun observeConnected(
        callAttemptId: String,
        remoteIdentityHash: String?,
        codecProfileCode: Int? = null,
    ) {
        submit(
            AcceptedCallObservation.Connected(
                callAttemptId,
                remoteIdentityHash,
                codecProfileCode,
            ),
        )
    }

    /** The generic ended callback fired for the exact accepted attempt. */
    fun observeEnded(
        callAttemptId: String,
        remoteIdentityHash: String?,
    ) {
        submit(AcceptedCallObservation.Ended(callAttemptId, remoteIdentityHash))
    }

    /** Explicit busy: applies only to an owned outgoing pre-connection attempt. */
    @Suppress("ReturnCount")
    fun observeBusy(callAttemptId: String) {
        val outcome =
            synchronized(stateLock) {
                val snapshot = activeAttempt ?: return
                if (snapshot.callAttemptId != callAttemptId) return
                if (connectedPersisted) return
                if (snapshot.direction != CallAttemptDirection.OUTGOING) return
                if (pendingTerminalOutcome != null) return
                pendingTerminalOutcome = UnconnectedOutcome.BUSY_REMOTE
                UnconnectedOutcome.BUSY_REMOTE
            }
        submit(AcceptedCallObservation.FinalizeUnconnected(callAttemptId, outcome))
    }

    /** Explicit rejection: applies only to an owned outgoing pre-connection attempt. */
    @Suppress("ReturnCount")
    fun observeRejected(callAttemptId: String) {
        val outcome =
            synchronized(stateLock) {
                val snapshot = activeAttempt ?: return
                if (snapshot.callAttemptId != callAttemptId) return
                if (connectedPersisted) return
                if (snapshot.direction != CallAttemptDirection.OUTGOING) return
                if (pendingTerminalOutcome != null) return
                pendingTerminalOutcome = UnconnectedOutcome.REJECTED_REMOTE
                UnconnectedOutcome.REJECTED_REMOTE
            }
        submit(AcceptedCallObservation.FinalizeUnconnected(callAttemptId, outcome))
    }

    /** Local hangup/decline intent on a never-connected attempt. */
    fun recordLocalEndIntent() {
        val outcome =
            synchronized(stateLock) {
                val snapshot = activeAttempt ?: return
                if (connectedPersisted) return
                if (pendingTerminalOutcome != null) return
                val outcome =
                    when (snapshot.direction) {
                        CallAttemptDirection.INCOMING -> UnconnectedOutcome.DECLINED_LOCAL
                        CallAttemptDirection.OUTGOING -> UnconnectedOutcome.CANCELLED_LOCAL
                    }
                pendingTerminalOutcome = outcome
                snapshot.callAttemptId to outcome
            }
        submit(AcceptedCallObservation.FinalizeUnconnected(outcome.first, outcome.second))
    }

    /** Persist a Columba-observed prerequisite failure for an owned never-connected attempt. */
    @Suppress("ReturnCount")
    fun failCallAttempt(
        callAttemptId: String,
        failureReason: CallFailureReason,
    ): Boolean {
        val latched =
            synchronized(stateLock) {
                val snapshot = activeAttempt ?: return false
                if (snapshot.callAttemptId != callAttemptId) return false
                if (connectedPersisted) return false
                if (pendingFailed != null) return false
                pendingFailed = failureReason
                true
            }
        if (latched) submit(AcceptedCallObservation.FinalizeFailed(callAttemptId, failureReason))
        return latched
    }

    suspend fun shutdown(): Result<Unit> {
        shutdownStarted.set(true)
        while (admissionReservation.get() != null) delay(retryDelayMillis)
        awaitObservationBarrier()
        val snapshot = activeAttempt
        if (snapshot == null) return awaitObservationBarrier().let { Result.success(Unit) }
        val finalize: suspend () -> Result<Unit> =
            {
                val outcome = synchronized(stateLock) { pendingTerminalOutcome }
                when {
                    outcome != null -> recorder.finalizeUnconnectedCall(snapshot.callAttemptId, outcome!!)
                    connectedPersisted -> recorder.completeConnectedCall(snapshot.callAttemptId)
                    else -> recorder.interruptCall(snapshot.callAttemptId)
                }
            }
        val result =
            cleanupRetrier.retry(
                snapshot.callAttemptId,
                finalize,
                onDeferredComplete = onDeferredShutdownCleanupComplete,
            )
        awaitObservationBarrier()
        return result
    }

    private suspend fun awaitObservationBarrier() {
        val acknowledgement = CompletableDeferred<Unit>()
        observations.trySend(
            AcceptedCallObservation.Barrier(activeAttempt?.callAttemptId ?: "", acknowledgement),
        )
        acknowledgement.await()
    }

    private suspend fun admit(
        request: CallAttemptRequest,
        persistRinging: Boolean,
        expose: suspend (CallAttemptSnapshot) -> Unit,
    ): Result<Unit> {
        var snapshot: CallAttemptSnapshot? = null
        var exposed = false
        val reservation = Any()
        return try {
            check(!shutdownStarted.get()) { "Call lifecycle is shutting down" }
            check(admissionReservation.compareAndSet(null, reservation)) {
                "Another call admission is already in progress"
            }
            run {
                check(!shutdownStarted.get()) { "Call lifecycle is shutting down" }
                check(activeAttempt == null) { "Another call attempt is awaiting finalization" }
                val accepted = recorder.acceptCallAttempt(request).getOrThrow()
                snapshot = accepted
                synchronized(stateLock) {
                    check(activeAttempt == null) { "Another call attempt became active during admission" }
                    begin(accepted)
                }
                if (persistRinging) {
                    recorder.recordCallRinging(accepted.callAttemptId).getOrElse { error ->
                        cleanupRetrier.retry(
                            accepted.callAttemptId,
                            cleanup = { recorder.discardCallAttempt(accepted.callAttemptId) },
                        ).exceptionOrNull()?.let(error::addSuppressed)
                        throw error
                    }
                }
                exposed = true
                expose(accepted)
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            snapshot?.let { s ->
                withContext(NonCancellable) {
                    cleanupRetrier.retry(
                        s.callAttemptId,
                        cleanup = {
                            if (exposed) {
                                recorder.interruptCall(s.callAttemptId)
                            } else {
                                recorder.discardCallAttempt(s.callAttemptId)
                            }
                        },
                    ).exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            throw error
        } catch (error: Exception) {
            snapshot?.let { s ->
                withContext(NonCancellable) {
                    val cleanup: suspend () -> Result<Unit> = {
                        when {
                            exposed && request.direction == CallAttemptDirection.OUTGOING ->
                                recorder.failCallAttempt(
                                    s.callAttemptId,
                                    CallFailureReason.UNKNOWN_PREREQUISITE_FAILURE,
                                )
                            exposed -> recorder.interruptCall(s.callAttemptId)
                            else -> recorder.discardCallAttempt(s.callAttemptId)
                        }
                    }
                    cleanupRetrier.retry(s.callAttemptId, cleanup)
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            Result.failure(error)
        } finally {
            admissionReservation.compareAndSet(reservation, null)
        }
    }

    private fun begin(snapshot: CallAttemptSnapshot) {
        activeAttempt = snapshot
        connectedPersisted = false
        pendingTerminalOutcome = null
        pendingFailed = null
    }

    private suspend fun process(observation: AcceptedCallObservation) {
        if (observation is AcceptedCallObservation.Barrier) {
            observation.acknowledgement.complete(Unit)
            return
        }
        val snapshot = activeAttempt
        if (snapshot == null || snapshot.callAttemptId != observation.callAttemptId) {
            return
        }
        when (observation) {
            is AcceptedCallObservation.Connected -> processConnected(snapshot, observation)
            is AcceptedCallObservation.Ended -> processEnded(snapshot, observation)
            is AcceptedCallObservation.FinalizeUnconnected -> processUnconnected(snapshot, observation)
            is AcceptedCallObservation.FinalizeFailed -> processFailed(snapshot, observation)
            is AcceptedCallObservation.Barrier -> error("handled before active-attempt lookup")
        }
    }

    private suspend fun processConnected(
        snapshot: CallAttemptSnapshot,
        observation: AcceptedCallObservation.Connected,
    ) {
        if (!matchesAcceptedRemote(snapshot, observation.remoteIdentityHash)) return
        if (connectedPersisted) return
        recorder.recordCallConnected(snapshot.callAttemptId)
            .onSuccess { connectedPersisted = true }
            .onFailure { schedule(AcceptedCallObservation.Connected(snapshot.callAttemptId, observation.remoteIdentityHash, observation.codecProfileCode)) }
    }

    private suspend fun processEnded(
        snapshot: CallAttemptSnapshot,
        observation: AcceptedCallObservation.Ended,
    ) {
        if (!matchesAcceptedRemote(snapshot, observation.remoteIdentityHash)) return
        if (connectedPersisted) {
            recorder.completeConnectedCall(snapshot.callAttemptId)
                .onSuccess { releaseIfExact(snapshot.callAttemptId) }
                .onFailure { schedule(AcceptedCallObservation.Ended(snapshot.callAttemptId, observation.remoteIdentityHash)) }
            return
        }
        val outcome =
            when (snapshot.direction) {
                CallAttemptDirection.INCOMING -> UnconnectedOutcome.MISSED_INCOMING
                CallAttemptDirection.OUTGOING -> UnconnectedOutcome.NOT_CONNECTED
            }
        finalizeUnconnected(snapshot, outcome)
    }

    private suspend fun processUnconnected(
        snapshot: CallAttemptSnapshot,
        observation: AcceptedCallObservation.FinalizeUnconnected,
    ) {
        if (connectedPersisted) return
        if (!directionAllows(snapshot.direction, observation.outcome)) return
        val latched =
            synchronized(stateLock) {
                when {
                    pendingTerminalOutcome == observation.outcome -> true
                    pendingTerminalOutcome != null -> false
                    else -> {
                        pendingTerminalOutcome = observation.outcome
                        true
                    }
                }
            }
        if (!latched) return
        finalizeUnconnected(snapshot, observation.outcome)
    }

    private suspend fun processFailed(
        snapshot: CallAttemptSnapshot,
        observation: AcceptedCallObservation.FinalizeFailed,
    ) {
        if (connectedPersisted) return
        val latched =
            synchronized(stateLock) {
                when {
                    pendingFailed == observation.failureReason -> true
                    pendingFailed != null -> false
                    else -> {
                        pendingFailed = observation.failureReason
                        true
                    }
                }
            }
        if (!latched) return
        recorder.failCallAttempt(snapshot.callAttemptId, observation.failureReason)
            .onSuccess { releaseIfExact(snapshot.callAttemptId) }
            .onFailure { schedule(AcceptedCallObservation.FinalizeFailed(snapshot.callAttemptId, observation.failureReason)) }
    }

    private suspend fun finalizeUnconnected(
        snapshot: CallAttemptSnapshot,
        outcome: UnconnectedOutcome,
    ) {
        recorder.finalizeUnconnectedCall(snapshot.callAttemptId, outcome)
            .onSuccess { releaseIfExact(snapshot.callAttemptId) }
            .onFailure { schedule(AcceptedCallObservation.FinalizeUnconnected(snapshot.callAttemptId, outcome)) }
    }

    private fun directionAllows(
        direction: CallAttemptDirection,
        outcome: UnconnectedOutcome,
    ): Boolean =
        when (direction) {
            CallAttemptDirection.INCOMING ->
                outcome == UnconnectedOutcome.MISSED_INCOMING ||
                    outcome == UnconnectedOutcome.DECLINED_LOCAL
            CallAttemptDirection.OUTGOING ->
                outcome == UnconnectedOutcome.REJECTED_REMOTE ||
                    outcome == UnconnectedOutcome.BUSY_REMOTE ||
                    outcome == UnconnectedOutcome.CANCELLED_LOCAL ||
                    outcome == UnconnectedOutcome.NOT_CONNECTED
        }

    internal fun releaseIfExact(callAttemptId: String) {
        synchronized(stateLock) {
            if (activeAttempt?.callAttemptId != callAttemptId) return
            connectedPersisted = false
            pendingTerminalOutcome = null
            pendingFailed = null
            activeAttempt = null
        }
    }

    internal fun schedule(observation: AcceptedCallObservation) {
        scope.launch(dispatcher) {
            delay(retryDelayMillis)
            observations.trySend(observation)
        }
    }
}
