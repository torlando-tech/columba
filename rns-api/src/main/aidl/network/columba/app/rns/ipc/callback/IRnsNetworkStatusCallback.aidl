// Observer callback for the StateFlow<NetworkStatus> on IRnsCore.
// Used as both snapshot (getCurrentNetworkStatus) and continuous observer.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.NetworkStatus;

oneway interface IRnsNetworkStatusCallback {
    void onStatus(in NetworkStatus status);
}
