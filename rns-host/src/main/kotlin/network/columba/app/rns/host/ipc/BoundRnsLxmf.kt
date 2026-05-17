package network.columba.app.rns.host.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage

/**
 * UI-side proxy that delegates every [RnsLxmf] member to the currently-bound
 * [RnsBackend] returned by the AIDL connection driver. Same shape as
 * [BoundRnsCore] — suspend forwarders, flow republishing via `flatMapLatest`,
 * non-suspend mutators fire-and-forget after awaiting a live binding.
 */
internal class BoundRnsLxmf(
    private val backendFlow: StateFlow<RnsBackend?>,
    private val scope: CoroutineScope,
) : RnsLxmf {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> =
        awaitBound().lxmf.sendLxmfMessage(
            destinationHash,
            content,
            sourceIdentity,
            imageData,
            imageFormat,
            fileAttachments,
        )

    @Suppress("LongParameterList")
    override suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
        replyToMessageId: String?,
        replyQuotedContent: String?,
        iconAppearance: IconAppearance?,
        extraFields: Map<Int, Any>?,
    ): Result<MessageReceipt> =
        awaitBound().lxmf.sendLxmfMessageWithMethod(
            destinationHash,
            content,
            sourceIdentity,
            deliveryMethod,
            tryPropagationOnFail,
            imageData,
            imageFormat,
            fileAttachments,
            replyToMessageId,
            replyQuotedContent,
            iconAppearance,
            extraFields,
        )

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> =
        awaitBound().lxmf.sendReaction(destinationHash, targetMessageId, emoji, sourceIdentity)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(): Flow<ReceivedMessage> =
        backendFlow.filterNotNull().flatMapLatest { it.lxmf.observeMessages() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> =
        backendFlow.filterNotNull().flatMapLatest { it.lxmf.observeDeliveryStatus() }

    override suspend fun getLxmfIdentity(): Result<Identity> = awaitBound().lxmf.getLxmfIdentity()

    override suspend fun getLxmfDestination(): Result<Destination> =
        awaitBound().lxmf.getLxmfDestination()

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> =
        awaitBound().lxmf.setOutboundPropagationNode(destHash)

    override suspend fun getOutboundPropagationNode(): Result<String?> =
        awaitBound().lxmf.getOutboundPropagationNode()

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> =
        awaitBound().lxmf.requestMessagesFromPropagationNode(identityPrivateKey, maxMessages)

    override suspend fun getPropagationState(): Result<PropagationState> =
        awaitBound().lxmf.getPropagationState()

    override suspend fun cancelMessageSync(): Result<Unit> = awaitBound().lxmf.cancelMessageSync()

    // SharedFlow republishing: flatMapLatest subscribes to the active backend's
    // propagationStateFlow on each rebind, then shareIn caches replay=1 so late
    // subscribers see the most recent upstream emission across the rebind seam.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val propagationStateFlow: SharedFlow<PropagationState> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.lxmf.propagationStateFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    override fun setConversationActive(active: Boolean) {
        scope.launch { awaitBound().lxmf.setConversationActive(active) }
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        scope.launch { awaitBound().lxmf.setIncomingMessageSizeLimit(limitKb) }
    }
}
