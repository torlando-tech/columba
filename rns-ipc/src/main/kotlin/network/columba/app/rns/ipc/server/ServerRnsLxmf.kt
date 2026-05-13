package network.columba.app.rns.ipc.server

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.FileAttachment
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsLxmf
import network.columba.app.rns.ipc.callback.IRnsDeliveryStatusCallback
import network.columba.app.rns.ipc.callback.IRnsMessageCallback
import network.columba.app.rns.ipc.callback.IRnsPropagationStateCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.toAttachmentPairs

internal class ServerRnsLxmf(
    private val impl: RnsLxmf,
    private val scope: CoroutineScope,
) : IRnsLxmf.Stub() {
    private val messageHub = ObserverHub<ReceivedMessage, IRnsMessageCallback>(
        scope = scope,
        upstream = { impl.observeMessages() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onMessage(value) },
    )
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
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: MutableList<FileAttachment>,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendLxmfMessage(
            destinationHash,
            content,
            sourceIdentity,
            imageData,
            imageFormat,
            fileAttachments.toAttachmentPairs().takeIf { it.isNotEmpty() },
        ).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: MutableList<FileAttachment>,
        replyToMessageId: String?,
        iconAppearance: IconAppearance?,
        extraFields: Bundle?,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendLxmfMessageWithMethod(
            destinationHash,
            content,
            sourceIdentity,
            deliveryMethod,
            tryPropagationOnFail,
            imageData,
            imageFormat,
            fileAttachments.toAttachmentPairs().takeIf { it.isNotEmpty() },
            replyToMessageId,
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
