/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.api.call

/**
 * Default [CallCallbackAdapter] that routes LXST events into the shared serialized
 * lifecycle owner ([AcceptedCallLifecycle]).
 *
 * Identity mismatches fail closed: a callback whose remote identity hash does not match
 * the currently owned attempt is dropped WITHOUT mutating history. Busy / rejected are
 * further scoped to outgoing pre-connection attempts (mirroring the owner's own guards).
 */
class SerializedLifecycleCallbackAdapter(
    private val owner: AcceptedCallLifecycle,
) : CallCallbackAdapter {

    override fun onRinging(identityHash: String) {
        // Ringing is durably persisted during incoming admission; informational only here.
    }

    override fun onEstablished(identityHash: String) {
        val attempt = owner.activeAttempt ?: return
        if (!matchesAcceptedRemote(attempt, identityHash)) return
        owner.observeConnected(attempt.callAttemptId, identityHash)
    }

    override fun onGenericEnded(identityHash: String?) {
        val attempt = owner.activeAttempt ?: return
        if (!matchesAcceptedRemote(attempt, identityHash)) return
        owner.observeEnded(attempt.callAttemptId, identityHash)
    }

    override fun onBusy(identityHash: String?) {
        val attempt = owner.activeAttempt ?: return
        if (attempt.direction != CallAttemptDirection.OUTGOING) return
        if (!matchesAcceptedRemote(attempt, identityHash)) return
        owner.observeBusy(attempt.callAttemptId)
    }

    override fun onRejected(identityHash: String?) {
        val attempt = owner.activeAttempt ?: return
        if (attempt.direction != CallAttemptDirection.OUTGOING) return
        if (!matchesAcceptedRemote(attempt, identityHash)) return
        owner.observeRejected(attempt.callAttemptId)
    }

    override fun onLocalDecline() {
        owner.recordLocalEndIntent()
    }

    override fun onLocalCancel() {
        owner.recordLocalEndIntent()
    }
}
