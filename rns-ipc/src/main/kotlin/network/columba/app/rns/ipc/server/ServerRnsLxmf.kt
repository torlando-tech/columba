package network.columba.app.rns.ipc.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.ipc.AttachmentBlob
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.FieldsBlob
import network.columba.app.rns.ipc.IRnsLxmf
import network.columba.app.rns.ipc.callback.IRnsDeliveryStatusCallback
import network.columba.app.rns.ipc.callback.IRnsMessageCallback
import network.columba.app.rns.ipc.callback.IRnsPropagationStateCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import java.io.File
import java.io.IOException

internal class ServerRnsLxmf(
    private val impl: RnsLxmf,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) : IRnsLxmf.Stub() {
    private val messageHub = ObserverHub<ReceivedMessage, IRnsMessageCallback>(
        scope = scope,
        upstream = { impl.observeMessages() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> emitMessage(cb, value) },
    )

    /**
     * Deliver one inbound message to a UI-process observer. A received image/file
     * is hex-encoded inside [ReceivedMessage.fieldsJson]; when that is large, ship
     * it out-of-band as a [ParcelFileDescriptor] rather than inline, because an
     * inline multi-hundred-KB parcel overflows the Binder buffer and throws
     * `TransactionTooLargeException` (which [ObserverHub] now survives, but the
     * message would still be lost). Small messages — plain text has null/tiny
     * `fieldsJson` — stay inline (null blob) at zero overhead.
     */
    private fun emitMessage(cb: IRnsMessageCallback, message: ReceivedMessage) {
        val fields = message.fieldsJson
        if (fields != null && fields.length > INLINE_FIELDS_LIMIT) {
            val pfd = try {
                FieldsBlob.writeToPfd(cacheDir, fields)
            } catch (e: IOException) {
                // Staging failed (e.g. disk full). Drop just this message rather
                // than throwing out of the collector (which would cancel it and
                // stop all delivery). Keep the observer; the next message is fine.
                Log.e(TAG, "Failed to stage inbound fields blob; dropping this message", e)
                return
            }
            // onMessage may still throw (RemoteException/TransactionTooLargeException);
            // let it propagate to ObserverHub, which keeps the observer subscribed.
            try {
                cb.onMessage(message.copy(fieldsJson = null), pfd)
            } finally {
                pfd.close()
            }
        } else {
            cb.onMessage(message, null)
        }
    }
    private val deliveryHub = ObserverHub<DeliveryStatusUpdate, IRnsDeliveryStatusCallback>(
        scope = scope,
        upstream = { impl.observeDeliveryStatus() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onDeliveryStatus(value) },
    )
    private val propagationHub = ObserverHub<PropagationState, IRnsPropagationStateCallback>(
        scope = scope,
        upstream = { impl.propagationStateFlow },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onPropagationState(value) },
    )

    override fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        attachmentsBlob: ParcelFileDescriptor?,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        // Read the out-of-band attachment payload back into memory (fast local
        // temp-file read just staged by the client; see AttachmentBlob). Runs on
        // the dispatch coroutine, so it never blocks a Binder thread.
        val payload = withContext(Dispatchers.IO) { AttachmentBlob.readFromPfd(attachmentsBlob) }
        val receipt = impl.sendLxmfMessage(
            destinationHash,
            content,
            sourceIdentity,
            payload.imageData,
            payload.imageFormat,
            payload.fileAttachments.takeIf { it.isNotEmpty() },
        ).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        attachmentsBlob: ParcelFileDescriptor?,
        replyToMessageId: String?,
        replyQuotedContent: String?,
        iconAppearance: IconAppearance?,
        extraFields: Bundle?,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        // Read the out-of-band attachment payload back into memory (fast local
        // temp-file read just staged by the client; see AttachmentBlob). Runs on
        // the dispatch coroutine, so it never blocks a Binder thread.
        val payload = withContext(Dispatchers.IO) { AttachmentBlob.readFromPfd(attachmentsBlob) }
        val receipt = impl.sendLxmfMessageWithMethod(
            destinationHash,
            content,
            sourceIdentity,
            deliveryMethod,
            tryPropagationOnFail,
            payload.imageData,
            payload.imageFormat,
            payload.fileAttachments.takeIf { it.isNotEmpty() },
            replyToMessageId,
            replyQuotedContent,
            iconAppearance,
            extraFields?.toExtraFieldsMap(),
        ).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendReaction(destinationHash, targetMessageId, emoji, sourceIdentity).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun registerMessageObserver(cb: IRnsMessageCallback) = messageHub.registerObserver(cb)
    override fun unregisterMessageObserver(cb: IRnsMessageCallback) = messageHub.unregisterObserver(cb)

    override fun registerDeliveryStatusObserver(cb: IRnsDeliveryStatusCallback) = deliveryHub.registerObserver(cb)
    override fun unregisterDeliveryStatusObserver(cb: IRnsDeliveryStatusCallback) = deliveryHub.unregisterObserver(cb)

    override fun getLxmfIdentity(cb: IRnsResultCallback) = dispatch(cb, scope) {
        val identity = impl.getLxmfIdentity().getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.IDENTITY, identity) }
    }

    override fun getLxmfDestination(cb: IRnsResultCallback) = dispatch(cb, scope) {
        val destination = impl.getLxmfDestination().getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.DESTINATION, destination) }
    }

    override fun setOutboundPropagationNode(destHash: ByteArray?, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.setOutboundPropagationNode(destHash).bundleOrThrow() }

    override fun getOutboundPropagationNode(cb: IRnsStringCallback) = dispatchNullableString(cb, scope) {
        impl.getOutboundPropagationNode().getOrThrow()
    }

    override fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val state = impl.requestMessagesFromPropagationNode(identityPrivateKey, maxMessages).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.PROPAGATION_STATE, state) }
    }

    override fun getPropagationState(cb: IRnsResultCallback) = dispatch(cb, scope) {
        val state = impl.getPropagationState().getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.PROPAGATION_STATE, state) }
    }

    override fun cancelMessageSync(cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.cancelMessageSync().bundleOrThrow() }

    override fun registerPropagationStateObserver(cb: IRnsPropagationStateCallback) =
        propagationHub.registerObserver(cb)
    override fun unregisterPropagationStateObserver(cb: IRnsPropagationStateCallback) =
        propagationHub.unregisterObserver(cb)

    override fun setConversationActive(active: Boolean) {
        runCatching { impl.setConversationActive(active) }
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        runCatching { impl.setIncomingMessageSizeLimit(limitKb) }
    }

    private companion object {
        const val TAG = "ServerRnsLxmf"

        // `fieldsJson` longer than this crosses out-of-band via FieldsBlob.
        // Comfortably under the ~1 MB Binder async-transaction buffer (so even a
        // few small inline messages queued together stay safe) yet large enough
        // that plain-text messages (null/tiny fieldsJson) always ride inline.
        // NOTE: measured in chars (String.length), not UTF-8 bytes. fieldsJson is
        // hex-encoded binary + ASCII JSON, so chars == bytes here; a hypothetical
        // multi-byte fieldsJson could be up to 4x larger on the wire but still
        // far under the ~1 MB buffer at this 64 KB threshold.
        const val INLINE_FIELDS_LIMIT = 64 * 1024
    }
}

/** Inverse of `toExtraFieldsBundle` on the client side. */
private fun Bundle.toExtraFieldsMap(): Map<Int, Any> {
    val map = LinkedHashMap<Int, Any>(size())
    for (key in keySet()) {
        @Suppress("DEPRECATION")
        val value = get(key) ?: continue
        val field = key.toIntOrNull() ?: continue
        map[field] = value
    }
    return map
}
