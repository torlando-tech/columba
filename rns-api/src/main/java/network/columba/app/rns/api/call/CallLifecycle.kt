package network.columba.app.rns.api.call

/**
 * Reduced call-history domain model for the rebuilt feature.
 *
 * Connection is the outcome boundary. Once connected, only `CONNECTED_ENDED` or
 * `INTERRUPTED` are valid; `DROPPED` is not classified because a connected
 * generic ending cannot be distinguished as clean or dropped. Before connection, explicit
 * local decline or cancellation, explicit remote rejection or busy, and explicit incoming
 * missed are valid; every other generic pre-connection ending becomes `NOT_CONNECTED`.
 * `FAILED` is reserved for failures Columba independently observes before or outside LXST
 * ownership; it is never inferred from a generic LXST ending. `UNANSWERED`,
 * `DROPPED`, and media/transport terminal causes are not authoritative persisted
 * outcomes because Python LXST does not expose enough information to classify them.
 */

/** Direction of one accepted LXST call attempt. */
enum class CallAttemptDirection {
    INCOMING,
    OUTGOING,
}

/** Immutable terminal classification stored on a finalized call-history row. */
enum class CallFinalOutcome {
    CONNECTED_ENDED,
    MISSED_INCOMING,
    DECLINED_LOCAL,
    REJECTED_REMOTE,
    BUSY_REMOTE,
    CANCELLED_LOCAL,
    NOT_CONNECTED,
    FAILED,
    INTERRUPTED,
}

/**
 * Sanitized reason for a `FAILED` attempt, restricted to prerequisites Columba can
 * independently observe before LXST accepts the call or from its own checks. Transport,
 * media, protocol, and internal-termination causes are excluded because Python LXST cannot
 * classify them for call history.
 */
enum class CallFailureReason {
    NETWORK_UNAVAILABLE,
    MICROPHONE_PERMISSION_DENIED,
    LOCAL_IDENTITY_UNAVAILABLE,
    ANOTHER_CALL_ACTIVE,
    INVALID_PEER_IDENTITY,
    SERVICE_STARTUP_FAILURE,
    UNKNOWN_PREREQUISITE_FAILURE,
}

/**
 * Terminal outcomes valid only for a never-connected attempt. Distinct from the persisted
 * [CallFinalOutcome] so the recorder API cannot express a connected-only outcome on an
 * unconnected finalization.
 */
enum class UnconnectedOutcome {
    MISSED_INCOMING,
    DECLINED_LOCAL,
    REJECTED_REMOTE,
    BUSY_REMOTE,
    CANCELLED_LOCAL,
    NOT_CONNECTED,
}

/** Maps an [UnconnectedOutcome] to its persisted [CallFinalOutcome]. */
fun UnconnectedOutcome.toCallFinalOutcome(): CallFinalOutcome = when (this) {
    UnconnectedOutcome.MISSED_INCOMING -> CallFinalOutcome.MISSED_INCOMING
    UnconnectedOutcome.DECLINED_LOCAL -> CallFinalOutcome.DECLINED_LOCAL
    UnconnectedOutcome.REJECTED_REMOTE -> CallFinalOutcome.REJECTED_REMOTE
    UnconnectedOutcome.BUSY_REMOTE -> CallFinalOutcome.BUSY_REMOTE
    UnconnectedOutcome.CANCELLED_LOCAL -> CallFinalOutcome.CANCELLED_LOCAL
    UnconnectedOutcome.NOT_CONNECTED -> CallFinalOutcome.NOT_CONNECTED
}

/**
 * Facts known at the service boundary where an attempt becomes accepted.
 *
 * An outgoing request is accepted immediately before outbound signalling. An incoming
 * request is accepted only after privacy and busy-line admission, immediately before
 * ringing is exposed.
 */
data class CallAttemptRequest(
    val direction: CallAttemptDirection,
    val localIdentityHash: String,
    val remoteIdentityHash: String,
    val codecProfileCode: Int?,
) {
    init {
        require(localIdentityHash.isNotBlank()) { "Local identity hash must not be blank" }
        require(remoteIdentityHash.isNotBlank()) { "Remote identity hash must not be blank" }
    }
}

/** Exact immutable identity of the currently accepted service-local attempt. */
data class CallAttemptSnapshot(
    val callAttemptId: String,
    val direction: CallAttemptDirection,
    val localIdentityHash: String,
    val remoteIdentityHash: String,
    val codecProfileCode: Int?,
    val attemptedAt: Long,
)

/**
 * Structured, service-local lifecycle events authored before live UI state becomes lossy.
 */
sealed interface CallLifecycleEvent {
    data class AttemptAccepted(
        val snapshot: CallAttemptSnapshot,
    ) : CallLifecycleEvent

    /** Admission was revoked before any signalling, ringing, or user-visible call state. */
    data class AttemptDiscarded(
        val callAttemptId: String,
    ) : CallLifecycleEvent

    /** connectedAt is the established-callback observation time. */
    data class AttemptConnected(
        val callAttemptId: String,
        val connectedAt: Long,
    ) : CallLifecycleEvent

    /** Ringing is observable only for incoming attempts; outgoing ringing is unavailable. */
    data class AttemptRinging(
        val callAttemptId: String,
        val ringingAt: Long,
    ) : CallLifecycleEvent

    /** A finalized attempt is immutable: no further transition is valid for this callAttemptId. */
    data class AttemptFinalized(
        val callAttemptId: String,
        val outcome: CallFinalOutcome,
        val endedAt: Long,
    ) : CallLifecycleEvent
}

/**
 * Synchronous admission + finalization boundary shared by both production call managers.
 *
 * Implementations must finish durable admission before returning success. A failure means
 * the caller must not start outbound signalling or expose inbound ringing.
 *
 * Finalization is single-shot per attempt: once a terminal outcome is recorded for a
 * callAttemptId, no other lifecycle transition may be emitted for that same attempt.
 */
interface CallLifecycleRecorder {
    suspend fun acceptCallAttempt(request: CallAttemptRequest): Result<CallAttemptSnapshot>

    /** Remove an accepted attempt that never crossed the externally visible call boundary. */
    suspend fun discardCallAttempt(callAttemptId: String): Result<Unit>

    /** Persist the first audio-established milestone (established-callback observation time). */
    suspend fun recordCallConnected(callAttemptId: String): Result<Unit>

    /** Persist ringing before exposing an accepted incoming attempt to transport, ringtone, or UI. */
    suspend fun recordCallRinging(callAttemptId: String): Result<Unit>

    /** Finalize an exact connected attempt after orderly teardown as `CONNECTED_ENDED`. */
    suspend fun completeConnectedCall(callAttemptId: String): Result<Unit>

    /** Finalize an exact connected attempt when service teardown interrupts normal completion. */
    suspend fun interruptCall(callAttemptId: String): Result<Unit>

    /** Finalize an exact never-connected attempt with a direction-valid terminal outcome. */
    suspend fun finalizeUnconnectedCall(
        callAttemptId: String,
        outcome: UnconnectedOutcome,
    ): Result<Unit>

    /** Persist `FAILED` for a prerequisite Columba independently observed before acceptance. */
    suspend fun failCallAttempt(
        callAttemptId: String,
        failureReason: CallFailureReason,
    ): Result<Unit>
}
