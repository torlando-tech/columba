package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.VoiceCallState

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import network.reticulum.common.DestinationDirection
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import network.reticulum.transport.Transport

internal class NativeMessageSender(
    private val routerProvider: () -> LXMRouter?,
    private val deliveryIdentityProvider: () -> NativeIdentity?,
    private val deliveryDestinationProvider: () -> NativeDestination?,
    private val deliveryStatusFlow: MutableSharedFlow<DeliveryStatusUpdate>,
    private val scopeProvider: () -> kotlinx.coroutines.CoroutineScope,
) {
    companion object {
        private const val TAG = "NativeReticulumProtocol"
    }

    data class MessageOptions(
        val tryPropagationOnFail: Boolean = false,
        val imageData: ByteArray? = null,
        val imageFormat: String? = null,
        val fileAttachments: List<Pair<String, ByteArray>>? = null,
        val replyToMessageId: String? = null,
        val replyQuotedContent: String? = null,
        val iconAppearance: IconAppearance? = null,
        val extraFields: Map<Int, Any>? = null,
    )

    suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        deliveryMethod: DeliveryMethod,
        options: MessageOptions = MessageOptions(),
    ): Result<MessageReceipt> =
        sendMessage(
            destinationHash = destinationHash,
            content = content,
            deliveryMethod = deliveryMethod,
            options = options,
        )

    private suspend fun sendMessage(
        destinationHash: ByteArray,
        content: String,
        deliveryMethod: DeliveryMethod,
        options: MessageOptions = MessageOptions(),
    ): Result<MessageReceipt> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val router = routerProvider() ?: error("Router not initialized")
                val recipientIdentity = resolveRecipientIdentity(destinationHash)
                val recipientDest =
                    NativeDestination.create(
                        recipientIdentity,
                        DestinationDirection.OUT,
                        NativeDestinationType.SINGLE,
                        LXMRouter.APP_NAME,
                        LXMRouter.DELIVERY_ASPECT,
                    )

                check(deliveryIdentityProvider() != null) { "Delivery identity not initialized" }
                val sourceDest =
                    deliveryDestinationProvider()
                        ?: error("Delivery destination not initialized")

                val fields = buildFields(options)
                val lxmfMethod = mapDeliveryMethod(deliveryMethod)

                val message =
                    LXMessage.create(
                        destination = recipientDest,
                        source = sourceDest,
                        content = content,
                        fields = fields,
                        desiredMethod = lxmfMethod,
                    )

                installDeliveryCallbacks(message, router, options.tryPropagationOnFail, lxmfMethod)
                router.handleOutbound(message)

                MessageReceipt(
                    messageHash = message.hash ?: ByteArray(0),
                    timestamp = System.currentTimeMillis(),
                    destinationHash = recipientDest.hash,
                )
            }
        }

    private suspend fun resolveRecipientIdentity(destinationHash: ByteArray): NativeIdentity {
        var recipientIdentity =
            NativeIdentity.recall(destinationHash)
                ?: NativeIdentity.recallByIdentityHash(destinationHash)

        if (recipientIdentity == null) {
            Log.d(TAG, "Recipient identity not cached, requesting path for ${destinationHash.toHex().take(16)}")
            Transport.requestPath(destinationHash)
            val deadline = System.currentTimeMillis() + 10_000
            while (recipientIdentity == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(250)
                recipientIdentity = NativeIdentity.recall(destinationHash)
                    ?: NativeIdentity.recallByIdentityHash(destinationHash)
            }
            checkNotNull(recipientIdentity) {
                "Recipient not found after path request: ${destinationHash.toHex().take(16)}"
            }
        }
        return recipientIdentity
    }

    private fun buildFields(options: MessageOptions): MutableMap<Int, Any> {
        val fields = mutableMapOf<Int, Any>()

        if (options.imageData != null && options.imageFormat != null) {
            fields[LxmfFields.FIELD_IMAGE] = listOf(options.imageFormat, options.imageData)
        }

        if (!options.fileAttachments.isNullOrEmpty()) {
            fields[LxmfFields.FIELD_FILE_ATTACHMENTS] =
                options.fileAttachments.map { (name, data) ->
                    listOf(name.toByteArray(), data)
                }
        }

        if (options.replyToMessageId != null) {
            // MeshChatX-compatible reply format (`meshchat.py:16697`):
            //   fields[0x30] = raw 32-byte hash (NOT a hex string)
            //   fields[0x31] = optional UTF-8 quoted content
            // ~32 bytes lighter per reply than the prior
            // `fields[0x10] = {reply_to: <hex>}` overload, and cleanly
            // separates the reply-target hash from the reactions field.
            runCatching {
                val hashBytes = options.replyToMessageId.hexToBytes()
                if (hashBytes != null && hashBytes.isNotEmpty()) {
                    fields[LxmfFields.FIELD_REPLY_HASH] = hashBytes
                    options.replyQuotedContent?.takeIf { it.isNotEmpty() }?.let {
                        fields[LxmfFields.FIELD_REPLY_QUOTE] = it.toByteArray(Charsets.UTF_8)
                    }
                } else {
                    Log.w(TAG, "replyToMessageId could not be hex-decoded: ${options.replyToMessageId.take(16)}")
                }
            }
        }

        if (options.extraFields != null) {
            fields.putAll(options.extraFields)
        }

        if (options.iconAppearance != null) {
            fields[LxmfFields.FIELD_ICON_APPEARANCE] =
                listOf(
                    options.iconAppearance.iconName,
                    options.iconAppearance.foregroundColor.hexToBytes(),
                    options.iconAppearance.backgroundColor.hexToBytes(),
                )
        }

        return fields
    }

    private fun mapDeliveryMethod(deliveryMethod: DeliveryMethod): NativeDeliveryMethod =
        when (deliveryMethod) {
            DeliveryMethod.OPPORTUNISTIC -> NativeDeliveryMethod.OPPORTUNISTIC
            DeliveryMethod.DIRECT -> NativeDeliveryMethod.DIRECT
            DeliveryMethod.PROPAGATED -> NativeDeliveryMethod.PROPAGATED
        }

    private fun installDeliveryCallbacks(
        message: LXMessage,
        router: LXMRouter,
        tryPropagationOnFail: Boolean,
        lxmfMethod: NativeDeliveryMethod,
    ) {
        message.deliveryCallback = deliveryCallback@{ msg ->
            val hash = msg.hash?.toHex() ?: return@deliveryCallback
            // In lxmf-kt, deliveryCallback fires with state == SENT only for
            // PROPAGATED messages (where SENT is the final state, set when the
            // resource completes uploading to the propagation node) and with
            // state == DELIVERED for DIRECT/OPPORTUNISTIC (where the receipt's
            // delivery confirmation transitions state to DELIVERED before the
            // callback runs). Distinguish by method, not state — checking state
            // alone risks misclassifying a future direct path that briefly
            // transitions through SENT before DELIVERED.
            val status =
                if (msg.method == NativeDeliveryMethod.PROPAGATED ||
                    msg.desiredMethod == NativeDeliveryMethod.PROPAGATED
                ) {
                    "propagated"
                } else {
                    "delivered"
                }
            Log.i(
                TAG,
                "Delivery callback for $hash -> $status (state=${msg.state}, method=${msg.method}, desired=${msg.desiredMethod})",
            )
            deliveryStatusFlow.tryEmit(
                DeliveryStatusUpdate(hash, status, System.currentTimeMillis()),
            )
        }
        message.failedCallback = failedCallback@{ msg ->
            val hash = msg.hash?.toHex() ?: return@failedCallback
            val currentMethod = msg.desiredMethod

            if (tryPropagationOnFail &&
                currentMethod != NativeDeliveryMethod.PROPAGATED &&
                router.getActivePropagationNode() != null
            ) {
                Log.i(TAG, "${currentMethod ?: lxmfMethod} delivery failed for $hash, falling back to PROPAGATED")
                deliveryStatusFlow.tryEmit(
                    DeliveryStatusUpdate(hash, "retrying_propagated", System.currentTimeMillis()),
                )
                msg.desiredMethod = NativeDeliveryMethod.PROPAGATED
                msg.state = network.reticulum.lxmf.MessageState.OUTBOUND
                msg.deliveryAttempts = 0
                scopeProvider().launch(Dispatchers.IO) {
                    router.handleOutbound(msg)
                }
                return@failedCallback
            }

            deliveryStatusFlow.tryEmit(
                DeliveryStatusUpdate(hash, "failed", System.currentTimeMillis()),
            )
        }
    }
}
