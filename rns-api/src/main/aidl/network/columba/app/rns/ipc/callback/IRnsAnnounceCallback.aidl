// Observer callback for Flow<AnnounceEvent> (RnsCore.observeAnnounces).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.AnnounceEvent;

oneway interface IRnsAnnounceCallback {
    void onAnnounce(in AnnounceEvent event);
}
