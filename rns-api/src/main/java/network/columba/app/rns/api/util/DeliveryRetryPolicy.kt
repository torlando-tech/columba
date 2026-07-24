package network.columba.app.rns.api.util

/**
 * The shared try-propagation-on-fail retry decision (the Sideband pattern):
 * when a DIRECT/OPPORTUNISTIC delivery fails, rebuild the message as
 * PROPAGATED and re-route it via the configured propagation node instead of
 * reporting failure. Mirrors Sideband's `core.message_notification` retry
 * block (`Sideband/sbapp/sideband/core.py:4440`).
 *
 * Both backend adapters consult this one predicate; only the *mechanism*
 * (rebuilding the live LXMessage and re-submitting it to the router) stays
 * flavor-local, because it manipulates flavor-owned router/message objects
 * (upstream Python LXMF vs lxmf-kt).
 *
 * "Give up after one retry" is encoded by the desired-method flip: the retry
 * rebuilds the message with desired method PROPAGATED, so when that attempt
 * fails too, [shouldRetryViaPropagation] is false and the failure is
 * reported for real — matching upstream's single-retry behaviour.
 */
object DeliveryRetryPolicy {
    /**
     * @param tryPropagationOnFail the caller opted into the fallback at send
     *   time (per-message; also implies the original method wasn't PROPAGATED)
     * @param desiredMethodIsPropagated the message's *current* desired method
     *   is PROPAGATED — true on the failure that follows a retry, which is
     *   what terminates the loop
     * @param propagationNodeConfigured an outbound propagation node is set;
     *   without one there is nothing to fall back to
     */
    fun shouldRetryViaPropagation(
        tryPropagationOnFail: Boolean,
        desiredMethodIsPropagated: Boolean,
        propagationNodeConfigured: Boolean,
    ): Boolean = tryPropagationOnFail && !desiredMethodIsPropagated && propagationNodeConfigured
}
