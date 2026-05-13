// Fire-once callback returning the IRnsCore sub-interface binder. The :rns-ipc
// client adapter wraps IRnsBackend.getCore() in suspendCancellableCoroutine
// and caches the binder reference for the lifetime of the service binding.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.ipc.IRnsCore;

oneway interface IRnsCoreCallback {
    void onCore(IRnsCore service);
}
