package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Delivery lifecycle of an outbound LXMF message, from optimistic creation
 * through terminal success or failure.
 *
 * This is the closed vocabulary that used to live as magic strings
 * (`"pending"`, `"sent"`, …) matched by hand across both backends and the
 * UI. Every consumer `when` over this type is exhaustive, so adding a state
 * is a compile error at each consumer instead of a silently-dropped status.
 *
 * Not to be confused with [DeliveryMethod] (how a send is routed:
 * opportunistic / direct / propagated). [Propagated] here is a *state* —
 * "stored on the relay node" — the success analogue of a PROPAGATED-method
 * send, where [Delivered] means "acknowledged by the recipient" for a
 * DIRECT/OPPORTUNISTIC send.
 *
 * The string form survives only at persistence/log boundaries via
 * [encode]/[decode]; Room keeps storing the legacy strings so existing
 * databases need no migration.
 */
sealed interface DeliveryState : Parcelable {
    /**
     * Terminal-success states must never degrade to [Failed] (Issue #257):
     * LXMF can fire spurious failure callbacks after a success confirmation.
     */
    val isTerminalSuccess: Boolean
        get() = this is Sent || this is Propagated || this is Delivered

    /** Outbound message handed to the router, awaiting a delivery outcome. */
    @Parcelize
    data object Pending : DeliveryState

    /**
     * Transmitted, no proof yet. Legacy initial state (and the Room column
     * default) predating [Pending]; still counts as terminal success.
     */
    @Parcelize
    data object Sent : DeliveryState

    /** Delivery proof received from the recipient (rendered ✓✓). */
    @Parcelize
    data object Delivered : DeliveryState

    /**
     * Stored on the configured propagation node (rendered ✓). The relay
     * holds the message for the recipient to fetch — success for a
     * PROPAGATED-method send, weaker than [Delivered].
     */
    @Parcelize
    data object Propagated : DeliveryState

    /**
     * Direct delivery failed; the backend rebuilt the message as PROPAGATED
     * and re-routed it via the relay (the Sideband retry pattern). A second
     * failure surfaces as [Failed].
     */
    @Parcelize
    data object RetryingViaPropagation : DeliveryState

    /** Delivery failed with no retry remaining. */
    @Parcelize
    data object Failed : DeliveryState

    /**
     * Stable string form for the Room `status` column and logcat contracts
     * (the maestro harness greps these, uppercased). The values are the
     * pre-existing legacy strings — do not change them.
     */
    fun encode(): String =
        when (this) {
            Pending -> "pending"
            Sent -> "sent"
            Delivered -> "delivered"
            Propagated -> "propagated"
            RetryingViaPropagation -> "retrying_propagated"
            Failed -> "failed"
        }

    companion object {
        /**
         * Inverse of [encode]. Returns null for unrecognized values (old
         * databases are an open string set); callers render null as the
         * neutral/unknown presentation, matching the historical `else`
         * branches.
         */
        fun decode(raw: String): DeliveryState? =
            when (raw) {
                "pending" -> Pending
                "sent" -> Sent
                "delivered" -> Delivered
                "propagated" -> Propagated
                "retrying_propagated" -> RetryingViaPropagation
                "failed" -> Failed
                else -> null
            }
    }
}
