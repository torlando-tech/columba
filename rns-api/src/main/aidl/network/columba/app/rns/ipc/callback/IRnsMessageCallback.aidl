// Observer callback for Flow<ReceivedMessage> (RnsLxmf.observeMessages).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.ReceivedMessage;

oneway interface IRnsMessageCallback {
    // Large fieldsJson and original packed signatureEvidence travel together in
    // fieldsBlob, a read-only fd over a delete-on-close temp file. Both fields
    // are stripped from the inline message; :rns-ipc restores them on receipt.
    // A null blob means the message is complete inline. signatureStatus always
    // stays in the Parcelable. Mirrors send-side attachmentsBlob on IRnsLxmf.
    void onMessage(in @nullable ReceivedMessage message, in @nullable ParcelFileDescriptor fieldsBlob);
}
