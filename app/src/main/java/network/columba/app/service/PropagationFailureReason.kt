package network.columba.app.service

import network.columba.app.rns.api.model.PropagationState

/**
 * Pure mapping from a [PropagationState] to a human-readable "why did this relay go
 * unreachable" explanation, plus the cross-backend predicate that decides whether a given
 * state is a *node-unreachable* failure (as opposed to an in-flight transfer error or a
 * success).
 *
 * The predicate keys off [PropagationState.stateName] rather than [PropagationState.state]
 * on purpose: the two RNS backends disagree on the numeric code. The native backend
 * ([NativeRnsBackendImpl]) stores the `LXMRouter.PropagationTransferState` **ordinal**
 * (FAILED = 8, NO_PATH = 9, NO_LINK = 10) while the Python backend maps every `PR_*` failure
 * terminal (0xf0+) to a generic `stateName = "failed"` with the raw 0xf0+ code preserved. The
 * one thing both agree on is the failure *names* — `failed` / `no_path` / `no_link` — so that
 * is the stable axis for "this relay could not be reached for any reason".
 *
 * This is deliberately independent of network liveness: it classifies an event LXMF already
 * emitted, it never probes.
 */
object PropagationFailureReason {
    /**
     * `stateName`s that mean the propagation node itself was unreachable (no path, no link, or
     * a generic failure). `no_path` / `no_link` are emitted only by the native backend; the
     * Python backend folds all failure terminals into `failed`.
     */
    private val UNREACHABLE_STATE_NAMES = setOf("failed", "no_path", "no_link")

    /**
     * Whether [state] represents the active propagation node being unreachable. Used to decide
     * when to record a failure + mark the relay unreachable (and fail over to the next one).
     */
    fun isUnreachable(state: PropagationState): Boolean = state.stateName in UNREACHABLE_STATE_NAMES

    /**
     * Human-readable explanation of an unreachable relay, for the UI and the recorded
     * [PreferredPropagationNodeEntity.lastFailureExplanation]. Resolves the specific reason
     * when the state name or code is precise enough, falling back to a generic message
     * otherwise.
     */
    fun explanation(state: PropagationState): String =
        when (state.stateName) {
            "no_path" -> "No path to relay"
            "no_link" -> "No link to relay"
            "failed" -> failedExplanation(state.state)
            else -> "Sync failed (${state.stateName})"
        }

    /**
     * Explanation for a generic `failed` state, disambiguated by the raw [code]. Handles both
     * backend code spaces: Python `0xf0+` failure terminals and the native enum ordinals
     * (FAILED = 8).
     */
    private fun failedExplanation(code: Int): String =
        when (code) {
            // Python backend PR_* failure terminals (0xf0+).
            0xf0 -> "No path to relay"
            0xf1 -> "Connection failed"
            0xf2 -> "Transfer failed"
            0xf3 -> "Identity not received"
            0xf4 -> "Access denied"
            // Native backend LXMRouter$PropagationTransferState ordinals: FAILED(8), NO_PATH(9), NO_LINK(10).
            8 -> "Relay unreachable"
            9 -> "No path to relay"
            10 -> "No link to relay"
            else -> "Relay unreachable (code 0x${Integer.toHexString(code)})"
        }
}
