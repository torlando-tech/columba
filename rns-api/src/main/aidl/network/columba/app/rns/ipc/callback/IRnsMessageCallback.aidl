// Observer callback for Flow<ReceivedMessage> (RnsLxmf.observeMessages).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.ReceivedMessage;

oneway interface IRnsMessageCallback {
    void onMessage(in ReceivedMessage message);
}
