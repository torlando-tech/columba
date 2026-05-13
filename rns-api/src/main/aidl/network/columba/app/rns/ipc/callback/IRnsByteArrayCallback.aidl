// Fire-once callback for AIDL methods returning raw bytes (nullable).
// Used for getFullIdentityKey (nullable), exportIdentityFile.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsByteArrayCallback {
    void onSuccess(in @nullable byte[] data);
    void onError(in RnsError error);
}
