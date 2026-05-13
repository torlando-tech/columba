// Generic fire-once callback for AIDL methods that return a structured payload.
//
// The Bundle keys are documented per-method on the corresponding sub-interface
// (see IRnsCore.aidl, IRnsLxmf.aidl, etc.). Methods returning Result<Unit> on
// the Kotlin side fire onSuccess with Bundle.EMPTY.
//
// Errors are typed via RnsError — :rns-ipc translates these into
// Result.failure(RnsException(err)) on the UI side so callers keep the
// familiar `result.fold` shape.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsResultCallback {
    void onSuccess(in Bundle resultPayload);
    void onError(in RnsError error);
}
