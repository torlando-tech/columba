// Observer callback for the StateFlow<CallState> on IRnsTelephony.
// Used as both snapshot (getCurrentCallState) and continuous observer.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.CallState;

oneway interface IRnsCallStateCallback {
    void onState(in CallState state);
}
