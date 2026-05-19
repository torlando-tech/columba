// Observer callback for Flow<ReceivedPacket> (RnsCore.observePackets).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.ReceivedPacket;

oneway interface IRnsPacketCallback {
    void onPacket(in ReceivedPacket packet);
}
