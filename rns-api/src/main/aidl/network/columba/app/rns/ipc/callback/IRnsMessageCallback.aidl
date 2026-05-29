// Observer callback for Flow<ReceivedMessage> (RnsLxmf.observeMessages).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.ReceivedMessage;

oneway interface IRnsMessageCallback {
    // A received message's image/file payload is hex-encoded inside
    // `ReceivedMessage.fieldsJson`, which makes a received photo several hundred
    // KB and overflows the ~1 MB shared Binder transaction buffer when marshaled
    // inline (-> TransactionTooLargeException, which silently detached the
    // observer and killed all further delivery). When `fieldsJson` is large the
    // server strips it from `message` (sets it null) and ships it out-of-band as
    // `fieldsBlob`, a read-only fd over a delete-on-close temp file that
    // :rns-ipc's FieldsBlob writes on the server and reads on the client; null
    // means the message is complete inline. Mirrors the send-side attachmentsBlob
    // on IRnsLxmf.
    void onMessage(in @nullable ReceivedMessage message, in @nullable ParcelFileDescriptor fieldsBlob);
}
