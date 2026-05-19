// AIDL surface mirroring the Kotlin RnsLxmf interface.
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - sendLxmfMessage / sendLxmfMessageWithMethod / sendReaction → "receipt": MessageReceipt
//   - getLxmfIdentity     → "identity": Identity
//   - getLxmfDestination  → "destination": Destination
//   - requestMessagesFromPropagationNode / getPropagationState → "state": PropagationState
//   - getOutboundPropagationNode (Result<String?>) — uses IRnsStringCallback, not Bundle.
//   - Result<Unit>        → Bundle.EMPTY
//
// File attachments cross AIDL as `List<FileAttachment>` Parcelable wrappers
// (AIDL has neither Pair nor List<byte[]>). :rns-ipc converts to/from the
// Kotlin `List<Pair<String, ByteArray>>` shape at the seam. extraFields is a
// generic Bundle whose keys are stringified LXMF field numbers ("4", "5",
// "16", …); values are whatever the field accepts (typically byte[] or
// String). See the per-call documentation for which fields each method writes.
package network.columba.app.rns.ipc;

import network.columba.app.rns.api.model.DeliveryMethod;
import network.columba.app.rns.api.model.FileAttachment;
import network.columba.app.rns.api.model.IconAppearance;
import network.columba.app.rns.api.model.Identity;
import network.columba.app.rns.ipc.callback.IRnsDeliveryStatusCallback;
import network.columba.app.rns.ipc.callback.IRnsMessageCallback;
import network.columba.app.rns.ipc.callback.IRnsPropagationStateCallback;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;
import network.columba.app.rns.ipc.callback.IRnsStringCallback;

oneway interface IRnsLxmf {
    // ==================== Send ====================

    void sendLxmfMessage(
        in byte[] destinationHash,
        String content,
        in Identity sourceIdentity,
        in @nullable byte[] imageData,
        in @nullable String imageFormat,
        in List<FileAttachment> fileAttachments,
        in IRnsResultCallback cb);

    void sendLxmfMessageWithMethod(
        in byte[] destinationHash,
        String content,
        in Identity sourceIdentity,
        in DeliveryMethod deliveryMethod,
        boolean tryPropagationOnFail,
        in @nullable byte[] imageData,
        in @nullable String imageFormat,
        in List<FileAttachment> fileAttachments,
        in @nullable String replyToMessageId,
        in @nullable String replyQuotedContent,
        in @nullable IconAppearance iconAppearance,
        in @nullable Bundle extraFields,
        in IRnsResultCallback cb);

    void sendReaction(
        in byte[] destinationHash,
        String targetMessageId,
        String emoji,
        in Identity sourceIdentity,
        in IRnsResultCallback cb);

    // ==================== Receive ====================

    // Flow<ReceivedMessage>: observer register/unregister.
    void registerMessageObserver(in IRnsMessageCallback cb);
    void unregisterMessageObserver(in IRnsMessageCallback cb);

    // Flow<DeliveryStatusUpdate>: observer register/unregister.
    void registerDeliveryStatusObserver(in IRnsDeliveryStatusCallback cb);
    void unregisterDeliveryStatusObserver(in IRnsDeliveryStatusCallback cb);

    // ==================== LXMF identity access ====================

    void getLxmfIdentity(in IRnsResultCallback cb);
    void getLxmfDestination(in IRnsResultCallback cb);

    // ==================== Propagation node ====================

    void setOutboundPropagationNode(in @nullable byte[] destHash, in IRnsResultCallback cb);

    // Returns Result<String?>; uses IRnsStringCallback (nullable String supported there).
    void getOutboundPropagationNode(in IRnsStringCallback cb);

    void requestMessagesFromPropagationNode(in @nullable byte[] identityPrivateKey, int maxMessages, in IRnsResultCallback cb);
    void getPropagationState(in IRnsResultCallback cb);
    void cancelMessageSync(in IRnsResultCallback cb);

    // SharedFlow<PropagationState>: observer register/unregister.
    void registerPropagationStateObserver(in IRnsPropagationStateCallback cb);
    void unregisterPropagationStateObserver(in IRnsPropagationStateCallback cb);

    // ==================== Performance & limits ====================
    //
    // Both setters are fire-and-forget on the Kotlin side (non-suspend `fun`).
    // Errors are logged on the host; no callback is invoked.

    void setConversationActive(boolean active);
    void setIncomingMessageSizeLimit(int limitKb);
}
