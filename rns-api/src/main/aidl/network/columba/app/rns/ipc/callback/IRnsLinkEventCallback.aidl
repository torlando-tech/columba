// Observer callback for Flow<LinkEvent> (RnsCore.observeLinks).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.LinkEvent;

oneway interface IRnsLinkEventCallback {
    void onLinkEvent(in LinkEvent event);
}
