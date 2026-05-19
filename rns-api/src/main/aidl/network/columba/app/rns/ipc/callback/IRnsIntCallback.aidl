// Fire-once callback for AIDL methods returning an Int (or nullable Int via
// nullableIntValue with hasValue=false). Used for getHopCount, getRNodeRssi.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsIntCallback {
    void onSuccess(int value, boolean hasValue);
    void onError(in RnsError error);
}
