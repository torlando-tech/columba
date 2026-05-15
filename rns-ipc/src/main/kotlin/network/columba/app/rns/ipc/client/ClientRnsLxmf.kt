package network.columba.app.rns.ipc.client

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsLxmf
import network.columba.app.rns.ipc.callback.IRnsDeliveryStatusCallback
import network.columba.app.rns.ipc.callback.IRnsMessageCallback
import network.columba.app.rns.ipc.callback.IRnsPropagationStateCallback
import network.columba.app.rns.ipc.toFileAttachments

internal class ClientRnsLxmf(
    private val remote: IRnsLxmf,
    private val scope: CoroutineScope,
) : RnsLxmf {
    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> = runCatching {
        val bundle = awaitResult { cb ->
            remote.sendLxmfMessage(
                destinationHash,
                content,
                sourceIdentity,
                imageData,
                imageFormat,
                fileAttachments.toFileAttachments(),
                cb,
            )
        }
        bundle.classLoader = MessageReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<MessageReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendLxmfMessage payload missing 'receipt'", null))
    }

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
        iconAppearance: IconAppearance?,
        extraFields: Map<Int, Any>?,
    ): Result<MessageReceipt> = runCatching {
        val bundle = awaitResult { cb ->
            remote.sendLxmfMessageWithMethod(
                destinationHash,
                content,
                sourceIdentity,
                deliveryMethod,
                tryPropagationOnFail,
                imageData,
                imageFormat,
                fileAttachments.toFileAttachments(),
                replyToMessageId,
                iconAppearance,
                extraFields?.toExtraFieldsBundle(),
                cb,
            )
        }
        bundle.classLoader = MessageReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<MessageReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendLxmfMessageWithMethod payload missing 'receipt'", null))
    }

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> = runCatching {
        val bundle = awaitResult { cb ->
            remote.sendReaction(destinationHash, targetMessageId, emoji, sourceIdentity, cb)
        }
        bundle.classLoader = MessageReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<MessageReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendReaction payload missing 'receipt'", null))
    }

    override fun observeMessages(): Flow<ReceivedMessage> = callbackFlow {
        val cb = object : IRnsMessageCallback.Stub() {
            override fun onMessage(message: ReceivedMessage?) { if (message != null) trySend(message) }
        }
        remote.registerMessageObserver(cb)
        awaitClose { runCatching { remote.unregisterMessageObserver(cb) } }
    }

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = callbackFlow {
        val cb = object : IRnsDeliveryStatusCallback.Stub() {
            override fun onDeliveryStatus(update: DeliveryStatusUpdate?) { if (update != null) trySend(update) }
        }
        remote.registerDeliveryStatusObserver(cb)
        awaitClose { runCatching { remote.unregisterDeliveryStatusObserver(cb) } }
    }

    override suspend fun getLxmfIdentity(): Result<Identity> = runCatching {
        val bundle = awaitResult { cb -> remote.getLxmfIdentity(cb) }
        bundle.classLoader = Identity::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Identity>(BundleKeys.IDENTITY)
            ?: throw RnsException(RnsError.Generic("getLxmfIdentity payload missing 'identity'", null))
    }

    override suspend fun getLxmfDestination(): Result<Destination> = runCatching {
        val bundle = awaitResult { cb -> remote.getLxmfDestination(cb) }
        bundle.classLoader = Destination::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Destination>(BundleKeys.DESTINATION)
            ?: throw RnsException(RnsError.Generic("getLxmfDestination payload missing 'destination'", null))
    }

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> = runCatching {
        awaitResult { cb -> remote.setOutboundPropagationNode(destHash, cb) }
        Unit
    }

    override suspend fun getOutboundPropagationNode(): Result<String?> = runCatching {
        awaitNullableString { cb -> remote.getOutboundPropagationNode(cb) }
    }

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> = runCatching {
        val bundle = awaitResult { cb ->
            remote.requestMessagesFromPropagationNode(identityPrivateKey, maxMessages, cb)
        }
        bundle.classLoader = PropagationState::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<PropagationState>(BundleKeys.PROPAGATION_STATE)
            ?: throw RnsException(RnsError.Generic("requestMessagesFromPropagationNode payload missing 'state'", null))
    }

    override suspend fun getPropagationState(): Result<PropagationState> = runCatching {
        val bundle = awaitResult { cb -> remote.getPropagationState(cb) }
        bundle.classLoader = PropagationState::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<PropagationState>(BundleKeys.PROPAGATION_STATE)
            ?: throw RnsException(RnsError.Generic("getPropagationState payload missing 'state'", null))
    }

    override suspend fun cancelMessageSync(): Result<Unit> = runCatching {
        awaitResult { cb -> remote.cancelMessageSync(cb) }
        Unit
    }

    private val propagationShared = MutableSharedFlow<PropagationState>(extraBufferCapacity = 16)

    init {
        callbackFlow<PropagationState> {
            val cb = object : IRnsPropagationStateCallback.Stub() {
                override fun onPropagationState(state: PropagationState?) {
                    if (state != null) trySend(state)
                }
            }
            remote.registerPropagationStateObserver(cb)
            awaitClose { runCatching { remote.unregisterPropagationStateObserver(cb) } }
        }.onEach { propagationShared.emit(it) }.launchIn(scope)
    }

    override val propagationStateFlow: SharedFlow<PropagationState>
        get() = propagationShared.asSharedFlow()

    override fun setConversationActive(active: Boolean) {
        runCatching { remote.setConversationActive(active) }
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        runCatching { remote.setIncomingMessageSizeLimit(limitKb) }
    }
}

/**
 * Pack a `Map<Int, Any>` of LXMF field/value entries into a Bundle whose keys
 * are the stringified field numbers (`"4"`, `"5"`, `"16"`, ...). Mirrors the
 * AIDL contract documented on `IRnsLxmf.sendLxmfMessageWithMethod`.
 */
private fun Map<Int, Any>.toExtraFieldsBundle(): Bundle {
    val bundle = Bundle()
    for ((field, value) in this) {
        val key = field.toString()
        when (value) {
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is ByteArray -> bundle.putByteArray(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
    return bundle
}
