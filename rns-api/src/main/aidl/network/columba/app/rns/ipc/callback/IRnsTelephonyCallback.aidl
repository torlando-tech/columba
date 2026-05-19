// Fire-once callback returning the IRnsTelephony sub-interface binder. See
// IRnsCoreCallback.aidl for the usage pattern.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.ipc.IRnsTelephony;

oneway interface IRnsTelephonyCallback {
    void onTelephony(IRnsTelephony service);
}
