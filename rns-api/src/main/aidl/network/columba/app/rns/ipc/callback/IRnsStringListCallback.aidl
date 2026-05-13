// Fire-once callback for AIDL methods returning a List<String> or Set<String>.
// Used for getPathTableHashes, getAutoconnectedEndpoints (Set→List on the wire).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsStringListCallback {
    void onSuccess(in List<String> values);
    void onError(in RnsError error);
}
