package network.columba.app.rns.api.call

import java.util.concurrent.atomic.AtomicReference

data class RetainedCallAttempt(
    val snapshot: CallAttemptSnapshot,
    val connected: Boolean,
)

/**
 * Process-local handoff for exact live-call ownership across service/recorder recreation.
 *
 * This state intentionally does not survive process death. Durable recovery then interrupts all
 * unmatched attempts rather than guessing ownership.
 */
object ServiceLiveCallAttemptRegistry {
    private val retained = AtomicReference<RetainedCallAttempt?>(null)

    fun recovery(): RetainedCallAttempt? = retained.get()

    fun snapshot(): CallAttemptSnapshot? = recovery()?.snapshot

    fun retain(snapshot: CallAttemptSnapshot) {
        retained.updateAndGet { current ->
            RetainedCallAttempt(
                snapshot = snapshot,
                connected = current?.takeIf { it.snapshot.callAttemptId == snapshot.callAttemptId }?.connected == true,
            )
        }
    }

    fun markConnected(callAttemptId: String) {
        retained.updateAndGet { current ->
            current?.takeIf { it.snapshot.callAttemptId == callAttemptId }?.copy(connected = true) ?: current
        }
    }

    fun recordAcceptedCodec(
        callAttemptId: String,
        codecProfileCode: Int,
    ) {
        retained.updateAndGet { current ->
            current
                ?.takeIf { it.snapshot.callAttemptId == callAttemptId }
                ?.copy(snapshot = current.snapshot.copy(codecProfileCode = codecProfileCode))
                ?: current
        }
    }

    fun clearIf(callAttemptId: String) {
        retained.updateAndGet { current ->
            current?.takeUnless { it.snapshot.callAttemptId == callAttemptId }
        }
    }
}
