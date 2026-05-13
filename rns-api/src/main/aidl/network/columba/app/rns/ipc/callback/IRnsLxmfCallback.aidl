// Fire-once callback returning the IRnsLxmf sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.ipc.IRnsLxmf;

oneway interface IRnsLxmfCallback {
    void onLxmf(IRnsLxmf service);
}
