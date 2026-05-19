// Fire-once callback returning the IRnsNomadnet sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.ipc.IRnsNomadnet;

oneway interface IRnsNomadnetCallback {
    void onNomadnet(IRnsNomadnet service);
}
