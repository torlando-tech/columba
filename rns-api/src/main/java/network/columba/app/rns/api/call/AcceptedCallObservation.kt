package network.columba.app.rns.api.call

import kotlinx.coroutines.CompletableDeferred

/**
 * Reduced internal observation envelope for an accepted call attempt.
 *
 * Late-outcome promotion, connected drops, and inferred-unanswered finalization were
 * removed: the reduced model records only the lifecycle facts Python LXST can actually
 * observe (connected, generic ended, explicit unconnected terminal outcomes, and a
 * Columba-observed prerequisite failure).
 */
internal sealed interface AcceptedCallObservation {
    val callAttemptId: String

    /** The established callback fired for the exact accepted attempt. */
    data class Connected(
        override val callAttemptId: String,
        val remoteIdentityHash: String?,
        val codecProfileCode: Int?,
    ) : AcceptedCallObservation

    /** The generic ended callback fired for the exact accepted attempt. */
    data class Ended(
        override val callAttemptId: String,
        val remoteIdentityHash: String?,
    ) : AcceptedCallObservation

    /** An explicit unconnected terminal outcome must be finalized for the exact attempt. */
    data class FinalizeUnconnected(
        override val callAttemptId: String,
        val outcome: UnconnectedOutcome,
    ) : AcceptedCallObservation

    /** Serialization barrier used to order lifecycle events across the service seam. */
    data class Barrier(
        override val callAttemptId: String,
        val acknowledgement: CompletableDeferred<Unit>,
    ) : AcceptedCallObservation
}

internal fun matchesAcceptedRemote(
    snapshot: CallAttemptSnapshot,
    remoteIdentityHash: String?,
): Boolean =
    remoteIdentityHash == null || remoteIdentityHash.equals(snapshot.remoteIdentityHash, ignoreCase = true)
