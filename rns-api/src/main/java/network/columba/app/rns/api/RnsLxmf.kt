package network.columba.app.rns.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage

/**
 * LXMF messaging surface: send/receive messages, observe delivery state,
 * propagation node sync, reactions (LXMF Field 16), and message size
 * limits.
 *
 * The LXMF identity/destination accessors live here (rather than in
 * [RnsCore]) because the binding LXMF identity is owned by the LXMF
 * router, not the bare RNS Identity store — the router constructs the
 * delivery destination once at startup and exposes it for callers that
 * need to address themselves (e.g., self-announces, `extraFields` source
 * stamping).
 */
@Suppress("TooManyFunctions") // Mirrors LXMF send/receive surface; can't be split without fragmenting cohesion.
interface RnsLxmf {
    // ==================== Send ====================

    suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray? = null,
        imageFormat: String? = null,
        fileAttachments: List<Pair<String, ByteArray>>? = null,
    ): Result<MessageReceipt>

    /**
     * Send an LXMF message with explicit delivery method.
     *
     * @param destinationHash Destination hash bytes
     * @param content Message content string
     * @param sourceIdentity Identity of the sender
     * @param deliveryMethod Delivery method to use (OPPORTUNISTIC, DIRECT, or PROPAGATED)
     * @param tryPropagationOnFail If true and direct fails, retry via propagation
     * @param imageData Optional image data bytes
     * @param imageFormat Optional image format (e.g., "jpg", "png", "webp")
     * @param fileAttachments Optional list of file attachments as (filename, bytes) pairs
     * @return Result containing MessageReceipt or failure
     */
    @Suppress("LongParameterList")
    suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod = DeliveryMethod.DIRECT,
        tryPropagationOnFail: Boolean = true,
        imageData: ByteArray? = null,
        imageFormat: String? = null,
        fileAttachments: List<Pair<String, ByteArray>>? = null,
        replyToMessageId: String? = null,
        replyQuotedContent: String? = null,
        iconAppearance: IconAppearance? = null,
        extraFields: Map<Int, Any>? = null,
    ): Result<MessageReceipt>

    /**
     * Send an emoji reaction to a message via LXMF Field 16.
     *
     * The reaction is sent as a lightweight LXMF message with Field 16 containing
     * the reaction data: {"reaction_to": "...", "emoji": "...", "sender": "..."}.
     *
     * @param destinationHash Destination hash bytes (16 bytes) - the recipient
     * @param targetMessageId The message hash/ID being reacted to
     * @param emoji The emoji reaction (e.g., "thumbs-up", "heart", etc.)
     * @param sourceIdentity Identity of the sender
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt>

    // ==================== Receive ====================

    fun observeMessages(): Flow<ReceivedMessage>

    /**
     * Observe delivery status updates for sent messages.
     * Emits DeliveryStatusUpdate events when messages are delivered or fail.
     */
    fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate>

    // ==================== LXMF identity access ====================

    /**
     * Return the LXMF delivery identity owned by the running LXMF router.
     * Distinct from [RnsCore.recallIdentity] — that looks up arbitrary
     * identities from the bare RNS identity store; this returns the local
     * router's identity, which is what callers need when addressing
     * themselves (self-announces, extraFields stamping, etc.).
     */
    suspend fun getLxmfIdentity(): Result<Identity>

    /** Return the LXMF delivery destination derived from [getLxmfIdentity]. */
    suspend fun getLxmfDestination(): Result<Destination>

    // ==================== Propagation node ====================

    /**
     * Set the propagation node to use for PROPAGATED delivery.
     *
     * @param destHash 16-byte destination hash of the propagation node, or null to clear
     * @return Result indicating success or failure
     */
    suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit>

    /**
     * Get the currently configured propagation node.
     *
     * @return The hex destination hash of the propagation node, or null if none set
     */
    suspend fun getOutboundPropagationNode(): Result<String?>

    /**
     * Request/sync messages from the configured propagation node.
     *
     * This is the key method for RECEIVING messages that were sent via propagation.
     * When messages are sent to a propagation node, the recipient must explicitly
     * request them. Call this method periodically (e.g., every 30-60 seconds) to
     * retrieve waiting messages.
     *
     * @param identityPrivateKey Optional private key bytes. If null, uses default identity.
     * @param maxMessages Maximum number of messages to retrieve (default 256)
     * @return Result containing the current propagation transfer state
     */
    suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray? = null,
        maxMessages: Int = 256,
    ): Result<PropagationState>

    /**
     * Get the current propagation sync state and progress.
     *
     * @return Result containing the current PropagationState
     */
    suspend fun getPropagationState(): Result<PropagationState>

    /**
     * Cancel an in-flight propagation sync.
     *
     * Mirrors Sideband's `cancel_lxmf_sync` -> upstream LXMF's
     * `cancel_propagation_node_requests`: tears down the outbound propagation
     * link (if any) and stops the path-request retry loop. A no-op when no
     * sync is active. Subsequent [requestMessagesFromPropagationNode] calls
     * start a fresh sync.
     */
    suspend fun cancelMessageSync(): Result<Unit>

    /**
     * Observable stream of propagation transfer state changes (path
     * request → link establish → receiving → complete). UI subscribes for
     * the propagation sync progress indicator.
     */
    val propagationStateFlow: SharedFlow<PropagationState>

    // ==================== Performance & limits ====================

    /**
     * Set conversation active state for context-aware message polling.
     * When a conversation is active, message polling uses faster intervals for lower latency.
     *
     * @param active true if a conversation screen is currently open and active
     */
    fun setConversationActive(active: Boolean)

    /**
     * Cap the maximum incoming LXMF message size accepted by the router.
     * Messages exceeding this size are rejected before reassembly to bound
     * memory pressure on resource-constrained builds.
     *
     * @param limitKb maximum message size in kilobytes.
     */
    fun setIncomingMessageSizeLimit(limitKb: Int)
}
