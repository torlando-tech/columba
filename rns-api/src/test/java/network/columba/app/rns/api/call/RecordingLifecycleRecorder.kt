package network.columba.app.rns.api.call

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred

/**
 * Reduced [CallLifecycleRecorder] fake that records every operation and can inject
 * transient failures for retry coverage.
 */
@Suppress("LongParameterList")
internal class RecordingLifecycleRecorder(
    private var acceptFailures: Int = 0,
    private var ringingFailures: Int = 0,
    private var connectedFailures: Int = 0,
    private var finalizationFailures: Int = 0,
    private var interruptFailures: Int = 0,
    private var discardFailures: Int = 0,
    private val acceptStarted: CompletableDeferred<Unit>? = null,
    private val acceptGate: CompletableDeferred<Unit>? = null,
    private val ringingStarted: CompletableDeferred<Unit>? = null,
    private val ringingGate: CompletableDeferred<Unit>? = null,
) : CallLifecycleRecorder {
    val operations = Collections.synchronizedList(mutableListOf<String>())
    val accepted = Collections.synchronizedList(mutableListOf<CallAttemptSnapshot>())
    val connectedAttempts = Collections.synchronizedList(mutableListOf<String>())
    val ringingAttempts = Collections.synchronizedList(mutableListOf<String>())
    val completedAttempts = Collections.synchronizedList(mutableListOf<String>())
    val interruptAttempts = Collections.synchronizedList(mutableListOf<String>())
    val discardAttempts = Collections.synchronizedList(mutableListOf<String>())
    val finalizedOutcomes = Collections.synchronizedList(mutableListOf<UnconnectedOutcome>())
    val failed = Collections.synchronizedList(mutableListOf<CallFailureReason>())

    private var sequence = 0

    override suspend fun acceptCallAttempt(request: CallAttemptRequest): Result<CallAttemptSnapshot> {
        operations += "accept"
        acceptStarted?.complete(Unit)
        acceptGate?.await()
        if (acceptFailures > 0) {
            acceptFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        val snapshot =
            CallAttemptSnapshot(
                callAttemptId = "attempt-${++sequence}",
                direction = request.direction,
                localIdentityHash = request.localIdentityHash,
                remoteIdentityHash = request.remoteIdentityHash,
                codecProfileCode = request.codecProfileCode,
                attemptedAt = 1_000L,
            )
        accepted += snapshot
        return Result.success(snapshot)
    }

    override suspend fun discardCallAttempt(callAttemptId: String): Result<Unit> {
        operations += "discard"
        discardAttempts += callAttemptId
        if (discardFailures > 0) {
            discardFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun recordCallConnected(callAttemptId: String): Result<Unit> {
        operations += "connected"
        connectedAttempts += callAttemptId
        if (connectedFailures > 0) {
            connectedFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun recordCallRinging(callAttemptId: String): Result<Unit> {
        operations += "ringing"
        ringingAttempts += callAttemptId
        ringingStarted?.complete(Unit)
        ringingGate?.await()
        if (ringingFailures > 0) {
            ringingFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun completeConnectedCall(callAttemptId: String): Result<Unit> {
        operations += "complete"
        completedAttempts += callAttemptId
        if (finalizationFailures > 0) {
            finalizationFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun interruptCall(callAttemptId: String): Result<Unit> {
        operations += "interrupt"
        interruptAttempts += callAttemptId
        if (interruptFailures > 0) {
            interruptFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun finalizeUnconnectedCall(
        callAttemptId: String,
        outcome: UnconnectedOutcome,
    ): Result<Unit> {
        operations += "finalize:$outcome"
        finalizedOutcomes += outcome
        if (finalizationFailures > 0) {
            finalizationFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }

    override suspend fun failCallAttempt(
        callAttemptId: String,
        failureReason: CallFailureReason,
    ): Result<Unit> {
        operations += "fail:$failureReason"
        failed += failureReason
        if (finalizationFailures > 0) {
            finalizationFailures--
            return Result.failure(IllegalStateException("database unavailable"))
        }
        return Result.success(Unit)
    }
}
