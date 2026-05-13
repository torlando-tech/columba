// Fire-once callback for AIDL methods returning a Boolean.
// Used for hasPath, isTransportEnabled, isDiscoveryEnabled, isSharedInstanceAvailable,
// identifyNomadnetLink, closeConversationLink (Result<Boolean>).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsBoolCallback {
    void onSuccess(boolean value);
    void onError(in RnsError error);
}
