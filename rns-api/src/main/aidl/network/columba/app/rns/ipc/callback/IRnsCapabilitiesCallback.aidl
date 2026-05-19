// Observer callback for the StateFlow<BackendCapabilities> on IRnsBackend.
// Used as both snapshot (getCapabilities) and continuous observer (register/unregister).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.BackendCapabilities;

oneway interface IRnsCapabilitiesCallback {
    void onCapabilities(in BackendCapabilities capabilities);
}
