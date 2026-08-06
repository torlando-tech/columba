package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.TransferProgressUpdate;

oneway interface IRnsTransferProgressCallback {
    void onTransferProgress(in TransferProgressUpdate update);
}
