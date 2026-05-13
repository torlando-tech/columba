package network.columba.app.reticulum.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.api.model.VoiceCallState

/**
 * Clean abstraction layer for Reticulum Network Stack.
 * This interface enables seamless migration from Python (via Chaquopy)
 * to Rust (via UniFFI) while maintaining API stability for the Kotlin UI layer.
 */
interface ReticulumProtocol {
    // Initialization & lifecycle
    suspend fun initialize(config: ReticulumConfig): Result<Unit>

    suspend fun shutdown(): Result<Unit>

    val networkStatus: StateFlow<NetworkStatus>

    // Identity management
    suspend fun createIdentity(): Result<Identity>

    suspend fun loadIdentity(path: String): Result<Identity>

    suspend fun saveIdentity(
        identity: Identity,
        path: String,
    ): Result<Unit>

    suspend fun recallIdentity(hash: ByteArray): Identity?

    // Multi-identity management.
    //
    // These methods never write plaintext private keys to the app's internal
    // filesystem. `createIdentityWithName` and `importIdentityFile` return the
    // raw 64-byte key via the `key_data` map entry; callers are expected to
    // hand it to `IdentityKeyProvider` which wraps it with the Android
    // Keystore before writing to Room. `exportIdentityFile` takes the already-
    // decrypted bytes and writes to a user-chosen `filePath` (typically a SAF
    // URI-backed scratch file) for the user to share via the system chooser.
    suspend fun createIdentityWithName(displayName: String): Map<String, Any>

    suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any>

    suspend fun exportIdentityFile(
        keyData: ByteArray,
        filePath: String,
    ): ByteArray

    // Destination management
    suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination>

    suspend fun announceDestination(
        destination: Destination,
        appData: ByteArray? = null,
    ): Result<Unit>

    // Packet operations
    suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType = PacketType.DATA,
    ): Result<PacketReceipt>

    fun observePackets(): Flow<ReceivedPacket>

    // Link operations
    suspend fun establishLink(destination: Destination): Result<Link>

    suspend fun closeLink(link: Link): Result<Unit>

    suspend fun sendOverLink(
        link: Link,
        data: ByteArray,
    ): Result<Unit>

    fun observeLinks(): Flow<LinkEvent>

    // Path & transport
    suspend fun hasPath(destinationHash: ByteArray): Boolean

    suspend fun requestPath(destinationHash: ByteArray): Result<Unit>

    suspend fun persistTransportData()

    fun getHopCount(destinationHash: ByteArray): Int?

    /**
     * Get the next-hop interface name for a destination.
     * Returns the formatted name of the interface that would be used to reach
     * this destination (e.g., "TCPInterface[Server/1.2.3.4:4242]").
     *
     * @param destinationHash 16-byte destination hash
     * @return Formatted interface name, or null if path is unknown
     */
    fun getNextHopInterfaceName(destinationHash: ByteArray): String?

    suspend fun getPathTableHashes(): List<String>

    /**
     * Probe link speed to a destination by checking existing links or sending
     * an empty LXMF message to establish one.
     *
     * @param destinationHash The destination to probe (16 bytes)
     * @param timeoutSeconds How long to wait for link establishment (default 10s)
     * @param deliveryMethod "direct" or "propagated" - affects which link to check/establish
     * @return LinkSpeedProbeResult with measured speeds or error status
     */
    suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float = 10.0f,
        deliveryMethod: String = "direct",
    ): LinkSpeedProbeResult

    // ==================== Conversation Link Management ====================

    /**
     * Establish a link to a destination for real-time connectivity.
     * Used to show "Online" status and enable instant link speed probing.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash)
     * @param timeoutSeconds How long to wait for link establishment
     * @return Result containing ConversationLinkResult with link status and speed
     */
    suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float = 10.0f,
    ): Result<ConversationLinkResult>

    /**
     * Close an active link to a destination.
     * Called when conversation has been inactive for too long.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash)
     * @return Result indicating success and whether link was active
     */
    suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean>

    /**
     * Check if a link is active to a destination.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash)
     * @return ConversationLinkResult with current link status
     */
    suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult

    // Announce handling
    fun observeAnnounces(): Flow<AnnounceEvent>

    // LXMF Messaging
    suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray? = null,
        imageFormat: String? = null,
        fileAttachments: List<Pair<String, ByteArray>>? = null,
    ): Result<MessageReceipt>

    fun observeMessages(): Flow<ReceivedMessage>

    /**
     * Observe delivery status updates for sent messages.
     * Emits DeliveryStatusUpdate events when messages are delivered or fail.
     */
    fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate>

    // Debug/diagnostics
    suspend fun getDebugInfo(): Map<String, Any>

    /**
     * Get list of interfaces that failed to initialize.
     * Returns a list of FailedInterface objects containing the interface name and error message.
     */
    suspend fun getFailedInterfaces(): List<FailedInterface>

    /**
     * Get statistics for a specific interface.
     *
     * @param interfaceName The name of the interface
     * @return Map containing interface stats (online, rxb, txb) or null if not found
     */
    suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>?

    // RNS 1.1.x Interface Discovery

    /**
     * Get list of discovered interfaces from RNS 1.1.x discovery system.
     * Requires RNS 1.1.0 or later.
     *
     * @return List of DiscoveredInterface objects with interface info
     */
    suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface>

    /**
     * Check if interface discovery and auto-connect is enabled.
     * Requires RNS 1.1.0 or later.
     *
     * @return true if RNS is configured to auto-connect discovered interfaces
     */
    suspend fun isDiscoveryEnabled(): Boolean

    /**
     * Get list of currently auto-connected interface endpoints.
     * Auto-connected interfaces are created dynamically by RNS discovery.
     * Requires RNS 1.1.0 or later.
     *
     * @return Set of endpoint strings like "host:port" for auto-connected interfaces
     */
    suspend fun getAutoconnectedEndpoints(): Set<String>

    // Performance optimization

    /**
     * Set conversation active state for context-aware message polling.
     * When a conversation is active, message polling uses faster intervals for lower latency.
     *
     * @param active true if a conversation screen is currently open and active
     */
    fun setConversationActive(active: Boolean)

    // RNode management

    /**
     * Attempt to reconnect to the RNode interface.
     * Use this when the RNode has disconnected and automatic reconnection has failed.
     */
    suspend fun reconnectRNodeInterface()

    // Propagation node support

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
        iconAppearance: IconAppearance? = null,
        extraFields: Map<Int, Any>? = null,
    ): Result<MessageReceipt>

    /**
     * Send location telemetry to a destination via LXMF field 7.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash)
     * @param locationJson JSON string with location data
     * @param sourceIdentity Identity of the sender
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance? = null,
    ): Result<MessageReceipt>

    /**
     * Send a telemetry request to a collector via LXMF FIELD_COMMANDS.
     *
     * The collector will respond with FIELD_TELEMETRY_STREAM containing
     * all known telemetry entries since the specified timebase.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash) of the collector
     * @param sourceIdentity Identity of the sender
     * @param timebase Optional Unix timestamp (milliseconds) to request telemetry since
     * @param isCollectorRequest True if requesting from a collector (default)
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long? = null,
        isCollectorRequest: Boolean = true,
    ): Result<MessageReceipt>

    /**
     * Enable or disable telemetry collector (host) mode.
     *
     * When enabled, this device will:
     * - Store incoming FIELD_TELEMETRY location data from peers
     * - Handle FIELD_COMMANDS telemetry requests
     * - Respond with FIELD_TELEMETRY_STREAM containing all stored entries
     *
     * @param enabled True to enable host mode, False to disable
     * @return Result indicating success
     */
    suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit>

    /**
     * Store the host's own location in the collected telemetry so it is included
     * in FIELD_TELEMETRY_STREAM responses sent to group members.
     *
     * @param locationJson JSON string with location data (lat, lng, acc, ts, etc.)
     * @param iconAppearance Optional icon appearance for the host
     * @return Result indicating success
     */
    suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance? = null,
    ): Result<Unit>

    /**
     * Set the list of identity hashes allowed to request telemetry in host mode.
     *
     * Only requesters whose identity hash is in the set will receive responses;
     * requests from others will be silently ignored. If the set is empty,
     * all requests will be blocked.
     *
     * @param allowedHashes Set of 32-character hex identity hash strings
     * @return Result indicating success
     */
    suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit>

    /**
     * Send an emoji reaction to a message via LXMF Field 16.
     *
     * The reaction is sent as a lightweight LXMF message with Field 16 containing
     * the reaction data: {"reaction_to": "...", "emoji": "...", "sender": "..."}.
     *
     * @param destinationHash Destination hash bytes (16 bytes) - the recipient
     * @param targetMessageId The message hash/ID being reacted to
     * @param emoji The emoji reaction (e.g., "👍", "❤️", "😂", "😮", "😢", "😡")
     * @param sourceIdentity Identity of the sender
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt>

    // Protocol version information (for About screen)

    /**
     * Get Reticulum version string.
     * @return Version string like "0.8.5" or null if unavailable
     */
    suspend fun getReticulumVersion(): String?

    /**
     * Get LXMF version string.
     * @return Version string like "0.5.4" or null if unavailable
     */
    suspend fun getLxmfVersion(): String?

    /**
     * Get BLE-Reticulum version string.
     * @return Version string like "0.2.2" or null if unavailable
     */
    suspend fun getBleReticulumVersion(): String?

    /**
     * Get the LXST voice calling protocol version.
     * @return Version string like "LXST-kt 0.0.1" or null if unavailable
     */
    suspend fun getLxstVersion(): String? = null

    // ==================== Voice Calls (LXST) ====================

    // Peer Blocking & Blackhole

    suspend fun blockDestination(destinationHashHex: String): Result<Unit>

    suspend fun unblockDestination(destinationHashHex: String): Result<Unit>

    suspend fun blackholeIdentity(identityHashHex: String): Result<Unit>

    suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit>

    suspend fun isTransportEnabled(): Boolean

    /**
     * Initiate an outgoing voice call to a destination.
     *
     * @param destinationHash Hex string of destination identity hash (32 chars)
     * @param profileCode LXST codec profile code (0x10-0x80), or null to use default
     * @return Result with success/failure status
     */
    suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int? = null,
    ): Result<Unit>

    /**
     * Answer an incoming voice call.
     *
     * @return Result with success/failure status
     */
    suspend fun answerCall(): Result<Unit>

    /**
     * End the current voice call (hangup).
     */
    suspend fun hangupCall()

    /**
     * Set microphone mute state during a call.
     *
     * @param muted true to mute, false to unmute
     */
    suspend fun setCallMuted(muted: Boolean)

    /**
     * Set speaker/earpiece mode during a call.
     *
     * @param speakerOn true for speaker, false for earpiece
     */
    suspend fun setCallSpeaker(speakerOn: Boolean)

    /**
     * Get current call state.
     *
     * @return Result containing CallState
     */
    suspend fun getCallState(): Result<VoiceCallState>

    // ==================== Service Lifecycle (no-op for native stack) ====================

    suspend fun bindService() {}

    fun unbindService() {}

    fun cleanup() {}

    fun getStatus(): Result<String> = Result.success("ready")

    fun isInitialized(): Result<Boolean> = Result.success(true)

    suspend fun waitForReady(timeoutMs: Long = 5000): Result<Unit> = Result.success(Unit)

    fun forceExit() {}

    // ==================== Identity Access ====================

    suspend fun getLxmfIdentity(): Result<Identity> = Result.failure(UnsupportedOperationException("Not implemented"))

    suspend fun getLxmfDestination(): Result<Destination> = Result.failure(UnsupportedOperationException("Not implemented"))

    /**
     * Return the raw 64-byte private key (X25519_prv + Ed25519_prv) for encrypted Room
     * storage, or null if the implementation has no key material to export yet.
     * Implementations without persistent keys (tests, mocks) return null.
     */
    fun getFullIdentityKey(): ByteArray? = null

    // ==================== Announce & Identity Restoration ====================

    suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> =
        announceDestination(
            Destination(
                hash = ByteArray(0),
                hexHash = "",
                identity = Identity(hash = ByteArray(0), publicKey = ByteArray(0), privateKey = null),
                direction = Direction.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                aspects = listOf("delivery"),
            ),
            displayName.toByteArray(Charsets.UTF_8),
        )

    suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> = Result.success(0)

    suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> = Result.success(0)

    // ==================== Observable Flows ====================

    val interfaceStatusChanged: SharedFlow<Unit>
        get() = MutableSharedFlow()

    val bleConnectionsFlow: SharedFlow<String>
        get() = MutableSharedFlow()

    val debugInfoFlow: SharedFlow<String>
        get() = MutableSharedFlow()

    val interfaceStatusFlow: SharedFlow<String>
        get() = MutableSharedFlow()

    val locationTelemetryFlow: SharedFlow<String>
        get() = MutableSharedFlow()

    val reactionReceivedFlow: SharedFlow<String>
        get() = MutableSharedFlow()

    val propagationStateFlow: SharedFlow<PropagationState>
        get() = MutableSharedFlow()

    val nomadnetRequestStatusFlow: StateFlow<String>
        get() = MutableStateFlow("idle")

    val nomadnetDownloadProgressFlow: StateFlow<Float>
        get() = MutableStateFlow(0f)

    // ==================== BLE ====================

    fun getBleConnectionDetails(): String = "[]"

    fun getRNodeRssi(): Int = -100

    // ==================== Shared Instance ====================

    val supportsSharedInstanceAvailabilityChecks: Boolean
        get() = false

    suspend fun isSharedInstanceAvailable(): Boolean = false

    // ==================== Message Size Limit ====================

    fun setIncomingMessageSizeLimit(limitKb: Int) {}

    // ==================== Battery / Performance ====================

    fun setBatteryProfile(profile: network.columba.app.rns.api.model.BatteryProfile) {}

    // ==================== Hot-reload Interfaces ====================

    /** Reload network interfaces from the given config without restarting Reticulum. */
    suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {}

    /** Enable or disable interface discovery without restarting. */
    suspend fun setDiscoveryEnabled(enabled: Boolean) {}

    /** Update the auto-connect limit without restarting. Default no-op (ServiceReticulumProtocol needs restart). */
    suspend fun setAutoconnectLimit(count: Int) {}

    /**
     * When enabled, auto-connect only accepts discovered interfaces that
     * published an IFAC network name. Useful on mixed-trust networks where
     * the user only wants Columba to auto-join known private networks.
     */
    suspend fun setAutoconnectIfacOnly(enabled: Boolean) {}

    // ==================== NomadNet Page Browsing ====================

    /**
     * Result of a NomadNet page request.
     */
    data class NomadnetPageResult(
        val content: String,
        val path: String,
        val type: String = "page",
        val filePath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0L,
    )

    suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String = "/page/index.mu",
        formDataJson: String? = null,
        timeoutSeconds: Float = 45f,
    ): Result<NomadnetPageResult> = Result.failure(UnsupportedOperationException("NomadNet browsing not yet supported on native stack"))

    suspend fun cancelNomadnetPageRequest() {}

    suspend fun getNomadnetRequestStatus(): String = "idle"

    suspend fun getNomadnetDownloadProgress(): Float = 0f

    suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> = Result.success(false)

    // ==================== Alternative Relay ====================

    var alternativeRelayHandler: (suspend (excludeHashes: List<String>) -> ByteArray?)?
        get() = null
        set(_) {}

    var onServiceNeedsInitialization: (suspend () -> Unit)?
        get() = null
        set(_) {}
}

