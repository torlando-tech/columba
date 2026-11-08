package network.columba.app.service

import androidx.annotation.StringRes
import network.columba.app.R
import network.columba.app.rns.api.model.PropagationState

/**
 * Pure mapping from a [PropagationState] to a localized "why did this relay go
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
     * Classification of an unreachable-relay explanation. The render site resolves
     * [messageResource] with [Explanation.args]; [Explanation.storageToken] is the stable
     * machine form persisted in the DB TEXT column.
     */
    enum class Reason {
        NO_PATH,
        NO_LINK,
        CONNECTION_FAILED,
        TRANSFER_FAILED,
        IDENTITY_NOT_RECEIVED,
        ACCESS_DENIED,
        RELAY_UNREACHABLE,
        RELAY_UNREACHABLE_CODE,
        SYNC_FAILED,
    }

    /**
     * A localized explanation of why a relay went unreachable: [reason] + [args] resolve to
     * the UI string; [storageToken] round-trips through [Explanation.decode] for persistence.
     */
    data class Explanation(
        val reason: Reason,
        val args: List<Any> = emptyList(),
    ) {
        /** Stable machine token for the `lastFailureExplanation` DB column. */
        fun storageToken(): String =
            when (reason) {
                Reason.RELAY_UNREACHABLE_CODE ->
                    "${TOKEN_PREFIX}relay_unreachable_code:${args.first()}"
                Reason.SYNC_FAILED -> "${TOKEN_PREFIX}sync_failed:${args.first()}"
                else -> TOKEN_PREFIX + reason.storageKey()
            }

        companion object {
            private const val TOKEN_PREFIX = "pf:"

            /**
             * Rebuilds an [Explanation] from a [storageToken]. Returns null for legacy rows
             * written before tokens existed (plain English sentences), which callers render
             * verbatim.
             */
            fun decode(token: String): Explanation? {
                if (!token.startsWith(TOKEN_PREFIX)) return null
                val body = token.removePrefix(TOKEN_PREFIX)
                val firstColon = body.indexOf(':')
                val key = if (firstColon >= 0) body.substring(0, firstColon) else body
                val arg = if (firstColon >= 0) body.substring(firstColon + 1) else null
                return when (key) {
                    "no_path" -> Explanation(Reason.NO_PATH)
                    "no_link" -> Explanation(Reason.NO_LINK)
                    "connection" -> Explanation(Reason.CONNECTION_FAILED)
                    "transfer" -> Explanation(Reason.TRANSFER_FAILED)
                    "identity_not_received" -> Explanation(Reason.IDENTITY_NOT_RECEIVED)
                    "access_denied" -> Explanation(Reason.ACCESS_DENIED)
                    "relay_unreachable" -> Explanation(Reason.RELAY_UNREACHABLE)
                    "relay_unreachable_code" ->
                        arg?.let { Explanation(Reason.RELAY_UNREACHABLE_CODE, listOf(it)) }
                    "sync_failed" -> arg?.let { Explanation(Reason.SYNC_FAILED, listOf(it)) }
                    else -> null
                }
            }
        }
    }

    /**
     * Human-readable explanation of an unreachable relay, for the UI and the recorded
     * [PreferredPropagationNodeEntity.lastFailureExplanation]. Resolves the specific reason
     * when the state name or code is precise enough, falling back to a generic message
     * otherwise.
     */
    fun explanation(state: PropagationState): Explanation =
        when (state.stateName) {
            "no_path" -> Explanation(Reason.NO_PATH)
            "no_link" -> Explanation(Reason.NO_LINK)
            "failed" -> failedExplanation(state.state)
            else -> Explanation(Reason.SYNC_FAILED, listOf(state.stateName))
        }

    /**
     * Explanation for a generic `failed` state, disambiguated by the raw [code]. Handles both
     * backend code spaces: Python `0xf0+` failure terminals and the native enum ordinals
     * (FAILED = 8).
     */
    private fun failedExplanation(code: Int): Explanation =
        when (code) {
            // Python backend PR_* failure terminals (0xf0+).
            0xf0 -> Explanation(Reason.NO_PATH)
            0xf1 -> Explanation(Reason.CONNECTION_FAILED)
            0xf2 -> Explanation(Reason.TRANSFER_FAILED)
            0xf3 -> Explanation(Reason.IDENTITY_NOT_RECEIVED)
            0xf4 -> Explanation(Reason.ACCESS_DENIED)
            // Native backend LXMRouter$PropagationTransferState ordinals: FAILED(8), NO_PATH(9), NO_LINK(10).
            8 -> Explanation(Reason.RELAY_UNREACHABLE)
            9 -> Explanation(Reason.NO_PATH)
            10 -> Explanation(Reason.NO_LINK)
            else -> Explanation(Reason.RELAY_UNREACHABLE_CODE, listOf(Integer.toHexString(code)))
        }
}

/** Stable lowercase key used in [PropagationFailureReason.Explanation.storageToken]. */
private fun PropagationFailureReason.Reason.storageKey(): String =
    when (this) {
        PropagationFailureReason.Reason.NO_PATH -> "no_path"
        PropagationFailureReason.Reason.NO_LINK -> "no_link"
        PropagationFailureReason.Reason.CONNECTION_FAILED -> "connection"
        PropagationFailureReason.Reason.TRANSFER_FAILED -> "transfer"
        PropagationFailureReason.Reason.IDENTITY_NOT_RECEIVED -> "identity_not_received"
        PropagationFailureReason.Reason.ACCESS_DENIED -> "access_denied"
        PropagationFailureReason.Reason.RELAY_UNREACHABLE -> "relay_unreachable"
        PropagationFailureReason.Reason.RELAY_UNREACHABLE_CODE -> "relay_unreachable_code"
        PropagationFailureReason.Reason.SYNC_FAILED -> "sync_failed"
    }

/** Localized message resource for each [PropagationFailureReason.Reason]. */
@StringRes
fun PropagationFailureReason.Reason.messageResource(): Int =
    when (this) {
        PropagationFailureReason.Reason.NO_PATH -> R.string.prop_fail_no_path
        PropagationFailureReason.Reason.NO_LINK -> R.string.prop_fail_no_link
        PropagationFailureReason.Reason.CONNECTION_FAILED -> R.string.prop_fail_connection
        PropagationFailureReason.Reason.TRANSFER_FAILED -> R.string.prop_fail_transfer
        PropagationFailureReason.Reason.IDENTITY_NOT_RECEIVED -> R.string.prop_fail_identity_not_received
        PropagationFailureReason.Reason.ACCESS_DENIED -> R.string.prop_fail_access_denied
        PropagationFailureReason.Reason.RELAY_UNREACHABLE -> R.string.prop_fail_relay_unreachable
        PropagationFailureReason.Reason.RELAY_UNREACHABLE_CODE -> R.string.prop_fail_relay_unreachable_code
        PropagationFailureReason.Reason.SYNC_FAILED -> R.string.prop_fail_sync_failed
    }
