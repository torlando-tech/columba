// Fire-once callback returning the IRnsTransportAdmin sub-interface binder.
// See IRnsCoreCallback.aidl for the usage pattern.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.ipc.IRnsTransportAdmin;

oneway interface IRnsTransportAdminCallback {
    void onTransportAdmin(IRnsTransportAdmin service);
}
