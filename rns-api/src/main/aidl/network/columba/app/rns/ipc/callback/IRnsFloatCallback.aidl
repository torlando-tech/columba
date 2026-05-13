// Fire-once callback for AIDL methods returning a Float.
// Used for getNomadnetDownloadProgress.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsFloatCallback {
    void onSuccess(float value);
    void onError(in RnsError error);
}
