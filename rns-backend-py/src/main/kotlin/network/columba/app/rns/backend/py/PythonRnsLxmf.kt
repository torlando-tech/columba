package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex

/**
 * `RnsLxmf` over upstream Python LXMF, driven through Chaquopy.
 *
 * Follows the [PythonRnsCore] pattern template: every `suspend` method routes
 * through [pyResult] / [pyCall] so the GIL-holding PyObject calls run on
 * `Dispatchers.IO` and Chaquopy `PyException`s become typed [RnsError]s; the
 * observable flows are sourced from [PythonEventBridge]; where the exact
 * upstream call shape needs on-device iteration the method is an honest
 * best-effort with a `TODO(on-device)` marker — never a silent fake.
 *
 * The send path builds `LXMF.LXMessage(destination, source, ...)` from live
 * `RNS.Destination` PyObjects (recipient reconstructed from a recalled
 * `RNS.Identity`, source = [PythonRnsRuntime.localDestination]) and routes it
 * through `LXMRouter.handle_outbound(...)` — the same shape
 * `NativeRnsBackendImpl` uses against the Kotlin LXMF port.
 */
@Suppress("TooManyFunctions") // Mirrors the RnsLxmf contract surface 1:1.
class PythonRnsLxmf(
    private val runtime: PythonRnsRuntime,
    private val events: PythonEventBridge,
) : RnsLxmf {
    private companion object {
        const val TAG = "PythonRnsLxmf"

        // LXMF.LXMessage delivery-method ints (LXMF/LXMessage.py).
        const val LXMF_METHOD_OPPORTUNISTIC = 0x01
        const val LXMF_METHOD_DIRECT = 0x02
        const val LXMF_METHOD_PROPAGATED = 0x03

        /** Bound on how long to wait for a recipient identity to resolve via a path request. */
        const val PATH_RESOLVE_TIMEOUT_MS = 10_000L
        const val PATH_RESOLVE_POLL_MS = 250L
    }

    /**
     * Propagation transfer-state stream. `replay = 1` so a late UI subscriber
     * immediately sees the current state; emissions are pushed from
     * [requestMessagesFromPropagationNode] / [getPropagationState].
     */
    private val _propagationStateFlow =
        MutableSharedFlow<PropagationState>(replay = 1, extraBufferCapacity = 8)
    override val propagationStateFlow: SharedFlow<PropagationState> =
        _propagationStateFlow.asSharedFlow()

    /** Mirrors `NativeRnsBackendImpl` — a polling hint with no LXMF-side effect on this backend. */
    @Volatile
    private var conversationActive: Boolean = false

    // ==================== Send ====================

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            val fields = buildFields(
                imageData = imageData,
                imageFormat = imageFormat,
                fileAttachments = fileAttachments,
            )
            dispatchLxmessage(
                destinationHash = destinationHash,
                content = content,
                fields = fields,
                desiredMethod = LXMF_METHOD_DIRECT,
            )
        }

    @Suppress("LongParameterList") // Mirrors the RnsLxmf.sendLxmfMessageWithMethod contract.
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
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            val fields = buildFields(
                imageData = imageData,
                imageFormat = imageFormat,
                fileAttachments = fileAttachments,
                replyToMessageId = replyToMessageId,
                iconAppearance = iconAppearance,
                extraFields = extraFields,
            )
            // TODO(on-device): tryPropagationOnFail needs LXMessage failure
            // callbacks wired to a re-send via PROPAGATED — upstream LXMF does
            // not do the direct->propagated fallback itself. The desired_method
            // is honoured; the on-failure retry is on-device follow-up.
            dispatchLxmessage(
                destinationHash = destinationHash,
                content = content,
                fields = fields,
                desiredMethod = lxmfMethodInt(deliveryMethod),
            )
        }

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            // Columba reaction payload: LXMF Field 16 carries the small dict
            // {"reaction_to", "emoji", "sender"} on an otherwise-empty message.
            val reaction = mapOf(
                "reaction_to" to targetMessageId,
                "emoji" to emoji,
                "sender" to sourceIdentity.hash.toHex(),
            )
            val fields = pyDict(mapOf(LxmfFields.FIELD_REACTION to reaction))
            dispatchLxmessage(
                destinationHash = destinationHash,
                content = "",
                fields = fields,
                desiredMethod = LXMF_METHOD_OPPORTUNISTIC,
            )
        }

    /**
     * Build the recipient/source `RNS.Destination` PyObjects, construct an
     * `LXMF.LXMessage`, route it through `LXMRouter.handle_outbound`, and read
     * the resulting `.hash` back into a [MessageReceipt].
     *
     * `LXMessage.pack()` (which assigns `.hash`) runs inside `handle_outbound`,
     * so the hash is only valid to read afterwards.
     */
    // Each guard (no recipient identity / no source destination / unknown
    // method) throws a distinct typed RnsException; collapsing them would lose
    // the failure distinction the UI surfaces.
    @Suppress("ThrowsCount")
    private fun dispatchLxmessage(
        destinationHash: ByteArray,
        content: String,
        fields: PyObject,
        desiredMethod: Int,
    ): MessageReceipt {
        val router = runtime.lxmRouter
            ?: throw RnsException(RnsError.BackendNotReady)
        val recipientDest = resolveRecipientDestination(destinationHash)
        val sourceDest = runtime.localDestination
            ?: throw RnsException(RnsError.BackendNotReady)

        // LXMF.LXMessage(destination, source, content, title, fields, desired_method)
        val lxmessage = runtime.lxmfModule.callAttr(
            "LXMessage",
            recipientDest,
            sourceDest,
            content,
            "",
            fields,
            desiredMethod,
        ) ?: throw RnsException(
            RnsError.Generic("LXMF.LXMessage construction returned None", null),
        )

        // Wire per-LXMessage delivery + failure callbacks BEFORE handle_outbound
        // so the Kotlin side learns when this message is delivered (the
        // OPPORTUNISTIC packet proof / DIRECT link ack) or fails. LXMF tracks
        // this per-LXMessage, not router-wide — without it the delivery-status
        // flow never updates for sent messages.
        runtime.eventBridge.callAttr(
            "attach_lxmessage_callbacks",
            lxmessage,
            events.onLxmfDelivered,
            events.onLxmfFailure,
        )

        router.callAttr("handle_outbound", lxmessage)

        val hashBytes = lxmessage["hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
        return MessageReceipt(
            messageHash = hashBytes,
            timestamp = System.currentTimeMillis(),
            destinationHash = recipientDest["hash"]?.toJava(ByteArray::class.java)
                ?: destinationHash,
        )
    }

    /**
     * Resolve a recipient hash to a live LXMF-delivery `RNS.Destination`.
     *
     * Prefers the runtime cache, else recalls the `RNS.Identity` from RNS's
     * cache and reconstructs `RNS.Destination(identity, OUT, SINGLE, "lxmf",
     * "delivery")` — the same shape `NativeMessageSender` uses. If the identity
     * is not yet known a path is requested and we poll briefly.
     */
    private fun resolveRecipientDestination(destinationHash: ByteArray): PyObject {
        val hex = destinationHash.toHex()
        runtime.destinations[hex]?.let { return it }

        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
        val hashPy = destinationHash.toPyBytes()

        var recipientIdentity = identityClass.callAttr("recall", hashPy)
        if (recipientIdentity == null) {
            Log.d(TAG, "Recipient identity not cached, requesting path for ${hex.take(16)}")
            transport().callAttr("request_path", hashPy)
            val deadline = System.currentTimeMillis() + PATH_RESOLVE_TIMEOUT_MS
            while (recipientIdentity == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(PATH_RESOLVE_POLL_MS)
                recipientIdentity = identityClass.callAttr("recall", hashPy)
            }
        }
        recipientIdentity
            ?: throw RnsException(RnsError.IdentityNotFound(hex))

        // RNS.Destination(identity, Destination.OUT, Destination.SINGLE, "lxmf", "delivery")
        val pyDest = runtime.rnsModule.callAttr(
            "Destination",
            recipientIdentity,
            destClass["OUT"] ?: error("RNS.Destination.OUT missing"),
            destClass["SINGLE"] ?: error("RNS.Destination.SINGLE missing"),
            LxmfFields.APP_NAME,
            LxmfFields.DELIVERY_ASPECT,
        ) ?: throw RnsException(
            RnsError.Generic("RNS.Destination construction returned None for $hex", null),
        )
        runtime.destinations[hex] = pyDest
        return pyDest
    }

    /**
     * Assemble the LXMF field map as a real Python `dict` — same field shapes
     * as `NativeMessageSender.buildFields`.
     *
     * Chaquopy footgun: the nested image/attachment lists must be real Python
     * `list`s and the field map a real `dict`, or upstream LXMF sees a
     * non-iterable `ArrayList` when it packs `self.fields`.
     */
    @Suppress("LongParameterList") // Internal aggregation of the optional message extras.
    private fun buildFields(
        imageData: ByteArray? = null,
        imageFormat: String? = null,
        fileAttachments: List<Pair<String, ByteArray>>? = null,
        replyToMessageId: String? = null,
        iconAppearance: IconAppearance? = null,
        extraFields: Map<Int, Any>? = null,
    ): PyObject {
        val fields = LinkedHashMap<Int, Any>()

        if (imageData != null && imageFormat != null) {
            // FIELD_IMAGE: [format_string, image_bytes]
            fields[LxmfFields.FIELD_IMAGE] = listOf(imageFormat, imageData).toPyList()
        }

        if (!fileAttachments.isNullOrEmpty()) {
            // FIELD_FILE_ATTACHMENTS: [[name_bytes, data_bytes], ...] — byte-identical
            // to NativeMessageSender (`listOf(name.toByteArray(), data)`), so
            // cross-backend Columba attachment interop is guaranteed by construction.
            // Note: upstream Sideband uses a `str` filename (`[basename, bytes]`);
            // Columba (both backends) uses `bytes`. Columba<->Sideband attachment-
            // filename interop is a Phase B on-device verification item — not a
            // wire-shape bug on this backend.
            val attachments = fileAttachments.map { (name, data) ->
                listOf(name.toByteArray(), data).toPyList()
            }
            fields[LxmfFields.FIELD_FILE_ATTACHMENTS] = attachments.toPyList()
        }

        if (replyToMessageId != null) {
            fields[LxmfFields.FIELD_REACTION] = pyDict(mapOf("reply_to" to replyToMessageId))
        }

        if (extraFields != null) {
            // Caller-supplied fields (telemetry, commands, custom meta). Plain
            // scalars/strings pass through; callers needing nested structures
            // pre-shape them.
            fields.putAll(extraFields)
        }

        if (iconAppearance != null) {
            // FIELD_ICON_APPEARANCE: [icon_name, fg_rgb_bytes, bg_rgb_bytes]
            fields[LxmfFields.FIELD_ICON_APPEARANCE] = listOf(
                iconAppearance.iconName,
                iconAppearance.foregroundColor.hexToBytes(),
                iconAppearance.backgroundColor.hexToBytes(),
            ).toPyList()
        }

        return pyDict(fields)
    }

    // ==================== Receive ====================

    override fun observeMessages(): Flow<ReceivedMessage> = events.messages

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = events.deliveryStatus

    // ==================== LXMF identity access ====================

    override suspend fun getLxmfIdentity(): Result<Identity> =
        pyResult {
            val identity = runtime.localIdentity
                ?: throw RnsException(RnsError.BackendNotReady)
            identity.toModelIdentity()
        }

    override suspend fun getLxmfDestination(): Result<Destination> =
        pyResult {
            val pyDest = runtime.localDestination
                ?: throw RnsException(RnsError.BackendNotReady)
            val identity = runtime.localIdentity?.toModelIdentity()
                ?: Identity(hash = ByteArray(0), publicKey = ByteArray(0), privateKey = null)
            val hash = pyDest["hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
            Destination(
                hash = hash,
                hexHash = hash.toHex(),
                identity = identity,
                // The LXMF delivery destination is an inbound SINGLE destination
                // under app "lxmf" / aspect "delivery" (LXMRouter.register_delivery_identity).
                direction = Direction.IN,
                type = DestinationType.SINGLE,
                appName = LxmfFields.APP_NAME,
                aspects = listOf(LxmfFields.DELIVERY_ASPECT),
            )
        }

    // ==================== Propagation node ====================

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> =
        pyResult {
            val router = runtime.lxmRouter
                ?: throw RnsException(RnsError.BackendNotReady)
            if (destHash != null) {
                // LXMRouter.set_outbound_propagation_node requires a real `bytes`
                // of exactly the truncated-hash length, else it raises ValueError.
                router.callAttr("set_outbound_propagation_node", destHash.toPyBytes())
            } else {
                // Upstream has no clear() — assigning the attribute back to None
                // is the documented way to drop the configured node. `put(k, null)`
                // is rejected by Chaquopy's Map<String, PyObject> view, so go
                // through Python's __setattr__.
                router.callAttr("__setattr__", "outbound_propagation_node", null)
            }
            Unit
        }

    override suspend fun getOutboundPropagationNode(): Result<String?> =
        pyResult {
            val router = runtime.lxmRouter
                ?: throw RnsException(RnsError.BackendNotReady)
            router.callAttr("get_outbound_propagation_node")
                ?.toJava(ByteArray::class.java)
                ?.toHex()
        }

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> =
        pyResult {
            val router = runtime.lxmRouter
                ?: throw RnsException(RnsError.BackendNotReady)
            // The sync runs as the identity that owns the delivery destination.
            // identityPrivateKey lets a caller sync as a different identity; if
            // supplied we reconstruct it, else use the live local identity.
            val syncIdentity = identityPrivateKey?.let { key ->
                val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
                identityClass.callAttr("from_bytes", key.toPyBytes())
            } ?: runtime.localIdentity
                ?: throw RnsException(RnsError.BackendNotReady)

            // LXMRouter.request_messages_from_propagation_node(identity, max_messages).
            // max_messages == 0 (PR_ALL_MESSAGES) means "all"; the contract's
            // default of 256 is passed through verbatim.
            router.callAttr("request_messages_from_propagation_node", syncIdentity, maxMessages)
            readPropagationState(router).also { _propagationStateFlow.tryEmit(it) }
        }

    override suspend fun getPropagationState(): Result<PropagationState> =
        pyResult {
            val router = runtime.lxmRouter ?: return@pyResult PropagationState.IDLE
            readPropagationState(router).also { _propagationStateFlow.tryEmit(it) }
        }

    /**
     * Read LXMRouter's `propagation_transfer_*` attributes into a
     * [PropagationState]. The numeric `PR_*` codes 0..7 are mirrored 1:1 in
     * [PropagationState]'s companion; the `PR_*` failure codes (0xf0+) fall
     * through to a "failed" label with the raw code preserved.
     */
    private fun readPropagationState(router: PyObject): PropagationState {
        val stateCode = router["propagation_transfer_state"]
            ?.toJava(Int::class.javaObjectType) ?: PropagationState.STATE_IDLE
        val progress = router["propagation_transfer_progress"]
            ?.toJava(Double::class.javaObjectType)?.toFloat() ?: 0f
        // propagation_transfer_last_result is None until a transfer completes,
        // then it's the received-message count (or 0 on a clean empty sync).
        val lastResult = router["propagation_transfer_last_result"]
            ?.toJava(Int::class.javaObjectType) ?: 0
        return PropagationState(
            state = stateCode,
            stateName = propagationStateName(stateCode),
            progress = progress,
            messagesReceived = lastResult,
        )
    }

    private fun propagationStateName(stateCode: Int): String =
        when (stateCode) {
            PropagationState.STATE_IDLE -> "idle"
            PropagationState.STATE_PATH_REQUESTED -> "path_requested"
            PropagationState.STATE_LINK_ESTABLISHING -> "link_establishing"
            PropagationState.STATE_LINK_ESTABLISHED -> "link_established"
            PropagationState.STATE_REQUEST_SENT -> "request_sent"
            PropagationState.STATE_RECEIVING -> "receiving"
            PropagationState.STATE_RESPONSE_RECEIVED -> "response_received"
            PropagationState.STATE_COMPLETE -> "complete"
            // PR_NO_PATH / PR_LINK_FAILED / PR_TRANSFER_FAILED / PR_NO_IDENTITY_RCVD
            // / PR_NO_ACCESS / PR_FAILED — all 0xf0+ failure terminals.
            else -> "failed"
        }

    // ==================== Performance & limits ====================

    override fun setConversationActive(active: Boolean) {
        // Mirrors NativeRnsBackendImpl: upstream LXMF is callback-driven (the
        // event bridge pushes deliveries), so there is no polling cadence to
        // tighten. Kept as a light field write for parity / future use.
        conversationActive = active
        Log.d(TAG, "setConversationActive($active)")
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        // Upstream LXMF has no inbound message-size cap of its own — its
        // `message_storage_limit` bounds a propagation *node's* served store,
        // not inbound delivery, so calling it here would be wrong. The lxmf-kt
        // port (kotlin backend) enforces a real `incomingMessageSizeLimitKb`;
        // the Python equivalent is a post-reassembly drop in event_bridge.py.
        // Known degradation vs the kotlin backend: LXMF fully reassembles a
        // message before its delivery callback fires, so oversized messages are
        // rejected before reaching the UI / storage, but the bandwidth + CPU of
        // receiving them cannot be saved (upstream LXMF exposes no earlier
        // hook). Recorded in the RNS dual-build handoff.
        runCatching {
            runtime.eventBridge.callAttr("set_incoming_message_size_limit", limitKb)
        }.onFailure { Log.w(TAG, "setIncomingMessageSizeLimit($limitKb) failed", it) }
        Log.d(TAG, "setIncomingMessageSizeLimit: ${if (limitKb > 0) "${limitKb}KB" else "unlimited"}")
    }

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    private fun lxmfMethodInt(method: DeliveryMethod): Int =
        when (method) {
            DeliveryMethod.OPPORTUNISTIC -> LXMF_METHOD_OPPORTUNISTIC
            DeliveryMethod.DIRECT -> LXMF_METHOD_DIRECT
            DeliveryMethod.PROPAGATED -> LXMF_METHOD_PROPAGATED
        }

    /**
     * Kotlin `Map` -> real Python `dict`. Chaquopy maps a Java `Map` arg to a
     * Python `dict` on a plain `callAttr`, but LXMF stores `fields` and later
     * iterates/msgpacks it, so build a genuine `dict` up front. Nested values
     * that are themselves Kotlin collections are converted recursively.
     */
    private fun pyDict(map: Map<*, *>): PyObject {
        val builtins = Python.getInstance().builtins
        val dict = builtins.callAttr("dict")
        map.forEach { (key, value) ->
            dict.callAttr("__setitem__", key, toPyValue(value))
        }
        return dict
    }

    /**
     * Recursively convert Kotlin collections to Python list/dict; convert
     * `ByteArray` to Python `bytes`; pass scalars and already-built [PyObject]s
     * through unchanged.
     *
     * Branch order matters:
     *  - `is PyObject` MUST be first: Chaquopy's [PyObject] itself implements
     *    `Map`, so a pre-built PyObject value (e.g. the `.toPyList()` result
     *    `buildFields` stores for `FIELD_IMAGE` / `FIELD_FILE_ATTACHMENTS` /
     *    `FIELD_ICON_APPEARANCE`, or the `pyDict(...)` for `FIELD_REACTION`)
     *    would otherwise match `is Map<*, *>` and `pyDict` ⇄ `toPyValue` recurse
     *    until a StackOverflowError aborts the send.
     *  - `is ByteArray` before the collection branches: a raw `ByteArray` dict
     *    value crosses JNI as a `jarray('B')`, which `umsgpack` can't pack —
     *    convert it to real `bytes` (same fix `toPyList` applies to elements).
     */
    private fun toPyValue(value: Any?): Any? =
        when (value) {
            is PyObject -> value
            is ByteArray -> value.toPyBytes()
            is Map<*, *> -> pyDict(value)
            is List<*> -> value.map { toPyValue(it) }.toPyList()
            else -> value
        }

    /** `RNS.Identity` PyObject -> model. `.hash` is an attribute; keys are getters. */
    private fun PyObject.toModelIdentity(): Identity {
        val hash = this["hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
        val pub = runCatching { callAttr("get_public_key")?.toJava(ByteArray::class.java) }
            .getOrNull() ?: ByteArray(0)
        val prv = runCatching { callAttr("get_private_key")?.toJava(ByteArray::class.java) }
            .getOrNull()
        return Identity(hash = hash, publicKey = pub, privateKey = prv)
    }
}
