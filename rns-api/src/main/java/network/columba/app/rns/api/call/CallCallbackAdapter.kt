/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.api.call

/**
 * Single symmetric callback contract shared across native and Python backends.
 *
 * LXST telephony events are translated through this contract into the shared serialized
 * lifecycle owner ([AcceptedCallLifecycle]). Both `NativeCallManager` and
 * `PythonCallManager` wire the same contract, so callback semantics are equivalent
 * across backends.
 *
 * The contract intentionally contains only events the reduced lifecycle can represent:
 *   - incoming ringing (informational; ringing is durably persisted during admission)
 *   - established
 *   - generic ended
 *   - busy
 *   - rejected
 *   - local decline (incoming hangup before answer)
 *   - local cancel (outgoing hangup before connection)
 */
interface CallCallbackAdapter {
    /** Remote is ringing (outgoing) / this device is ringing (incoming). */
    fun onRinging(identityHash: String)

    /** Call established, audio flowing. */
    fun onEstablished(identityHash: String)

    /** Generic ended (either side hung up, or link closed). */
    fun onGenericEnded(identityHash: String?)

    /** Remote is busy (outgoing pre-connection). */
    fun onBusy(identityHash: String?)

    /** Remote rejected the call (outgoing pre-connection). */
    fun onRejected(identityHash: String?)

    /** Local user declined an incoming call before answering. */
    fun onLocalDecline()

    /** Local user cancelled an outgoing call before connection. */
    fun onLocalCancel()
}
