// Observer callback for Flow<DeliveryStatusUpdate> (RnsLxmf.observeDeliveryStatus).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.DeliveryStatusUpdate;

oneway interface IRnsDeliveryStatusCallback {
    void onDeliveryStatus(in DeliveryStatusUpdate update);
}
