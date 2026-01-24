package com.lxmf.messenger.reticulum.protocol

import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.model.PacketType
import com.lxmf.messenger.reticulum.model.ReceivedPacket
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    // Multi-identity management
    suspend fun createIdentityWithName(displayName: String): Map<String, Any>

    suspend fun deleteIdentityFile(identityHash: String): Map<String, Any>

    suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any>

    suspend fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray

    suspend fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): Map<String, Any>

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

    fun getHopCount(destinationHash: ByteArray): Int?

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

    // ==================== Voice Calls (LXST) ====================

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
}

/**
 * Voice call state from LXST
 */
data class VoiceCallState(
    val status: String,
    val isActive: Boolean,
    val isMuted: Boolean,
    val remoteIdentity: String?,
    val profile: String?,
)

/**
 * Delivery methods for LXMF messages
 */
enum class DeliveryMethod {
    /** Single-packet delivery, max 295 bytes content, no link required */
    OPPORTUNISTIC,

    /** Link-based delivery, unlimited size, with retries */
    DIRECT,

    /** Delivery via propagation node for offline recipients */
    PROPAGATED,
}

/**
 * Receipt for a sent LXMF message
 */
data class MessageReceipt(
    val messageHash: ByteArray,
    val timestamp: Long,
    val destinationHash: ByteArray, // Actual LXMF destination hash used for sending
)

/**
 * Icon appearance data sent with messages (LXMF Field 4 - Sideband/MeshChat interop)
 */
data class IconAppearance(
    val iconName: String,
    val foregroundColor: String, // Hex RGB e.g., "FFFFFF"
    val backgroundColor: String, // Hex RGB e.g., "1E88E5"
)

/**
 * A received LXMF message
 */
data class ReceivedMessage(
    val messageHash: String,
    val content: String,
    val sourceHash: ByteArray,
    val destinationHash: ByteArray,
    val timestamp: Long,
    // LXMF fields as JSON: {"6": "hex_image_data", "7": "hex_audio_data"}
    val fieldsJson: String? = null,
    // Sender's public key (if available from RNS identity cache)
    val publicKey: ByteArray? = null,
    // Sender's icon appearance (LXMF Field 4 - Sideband/MeshChat interop)
    val iconAppearance: IconAppearance? = null,
    // Received message routing info (hop count and receiving interface)
    val receivedHopCount: Int? = null,
    val receivedInterface: String? = null,
    // Signal quality metrics (from RNode/BLE - null for TCP/AutoInterface)
    val receivedRssi: Int? = null,
    val receivedSnr: Float? = null,
)

/**
 * Delivery status update for a sent LXMF message
 */
data class DeliveryStatusUpdate(
    val messageHash: String,
    /** Status: "delivered", "failed", or "retrying_propagated" (direct failed, retrying via propagation) */
    val status: String,
    val timestamp: Long,
)

/**
 * Information about an interface that failed to initialize
 */
data class FailedInterface(
    val name: String,
    val error: String,
    val recoverable: Boolean = true,
) {
    companion object {
        /**
         * Parse a JSON array string into a list of FailedInterface objects.
         * Used by ServiceReticulumProtocol.
         */
        fun parseFromJson(jsonString: String): List<FailedInterface> {
            val jsonArray = org.json.JSONArray(jsonString)
            val failedList = mutableListOf<FailedInterface>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                failedList.add(
                    FailedInterface(
                        name = item.optString("name", "Unknown"),
                        error = item.optString("error", "Unknown error"),
                        recoverable = item.optBoolean("recoverable", true),
                    ),
                )
            }
            return failedList
        }
    }
}

/**
 * Information about an interface discovered via RNS 1.1.x interface discovery.
 *
 * RNS 1.1.0+ interface discovery provides detailed information about interfaces
 * announced by other nodes on the network, including TCP servers, RNodes, and
 * other interface types.
 */
data class DiscoveredInterface(
    // Core identification
    val name: String,
    val type: String, // "TCPServerInterface", "RNodeInterface", etc.
    val transportId: String?, // Transport identity hash
    val networkId: String?, // Network identity hash
    // Status information
    val status: String, // "available", "unknown", "stale"
    val statusCode: Int, // 1000 = available, 100 = unknown, 0 = stale
    val lastHeard: Long, // Unix timestamp in seconds
    val heardCount: Int, // Number of times this interface has been discovered
    val hops: Int, // Distance in hops from discovery source
    val stampValue: Int, // Proof-of-work stamp value
    // TCP-specific fields
    val reachableOn: String?, // Host/IP for TCP interfaces or b32 address for I2P
    val port: Int?,
    // Radio-specific fields (RNode, Weave, KISS)
    val frequency: Long?, // Frequency in Hz
    val bandwidth: Int?, // Bandwidth in Hz
    val spreadingFactor: Int?, // LoRa spreading factor (5-12)
    val codingRate: Int?, // LoRa coding rate (5-8)
    val modulation: String?, // Modulation type
    val channel: Int?, // Channel number (for Weave)
    // Location (optional, for interfaces that share location)
    val latitude: Double?,
    val longitude: Double?,
    val height: Double?, // Altitude in meters
) {
    /**
     * Returns true if this is a TCP-based interface.
     * BackboneInterface is the RNS 1.1.x upgraded TCP connection type.
     */
    val isTcpInterface: Boolean
        get() = type in listOf("TCPServerInterface", "TCPClientInterface", "BackboneInterface")

    /**
     * Returns true if this is a radio-based interface.
     */
    val isRadioInterface: Boolean
        get() = type in listOf("RNodeInterface", "WeaveInterface", "KISSInterface")

    /**
     * Returns true if location information is available.
     */
    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    companion object {
        // Status codes matching RNS
        const val STATUS_AVAILABLE = 1000
        const val STATUS_UNKNOWN = 100
        const val STATUS_STALE = 0

        /**
         * Parse a JSON array string into a list of DiscoveredInterface objects.
         */
        fun parseFromJson(jsonString: String): List<DiscoveredInterface> {
            val jsonArray = org.json.JSONArray(jsonString)
            return (0 until jsonArray.length()).map { i ->
                parseItem(jsonArray.getJSONObject(i))
            }
        }

        private fun parseItem(item: org.json.JSONObject): DiscoveredInterface {
            return DiscoveredInterface(
                // Core identification
                name = item.optString("name", "Unknown"),
                type = item.optString("type", "Unknown"),
                transportId = item.optString("transport_id", "").ifEmpty { null },
                networkId = item.optString("network_id", "").ifEmpty { null },
                // Status information
                status = item.optString("status", "unknown"),
                statusCode = item.optInt("status_code", STATUS_UNKNOWN),
                lastHeard = item.optLong("last_heard", 0),
                heardCount = item.optInt("heard_count", 0),
                hops = item.optInt("hops", 0),
                stampValue = item.optInt("stamp_value", 0),
                // TCP-specific
                reachableOn = item.optString("reachable_on", "").ifEmpty { null },
                port = item.optIntOrNull("port"),
                // Radio-specific
                frequency = item.optLongOrNull("frequency"),
                bandwidth = item.optIntOrNull("bandwidth"),
                spreadingFactor = item.optIntOrNull("spreading_factor"),
                codingRate = item.optIntOrNull("coding_rate"),
                modulation = item.optString("modulation", "").ifEmpty { null },
                channel = item.optIntOrNull("channel"),
                // Location
                latitude = item.optDoubleOrNull("latitude"),
                longitude = item.optDoubleOrNull("longitude"),
                height = item.optDoubleOrNull("height"),
            )
        }

        // JSON extension helpers for nullable values
        private fun org.json.JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

        private fun org.json.JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

        private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    }
}

/**
 * Result of conversation link operations.
 *
 * Contains all metrics needed for connection quality assessment and
 * image transfer time estimation.
 */
data class ConversationLinkResult(
    /** Whether link is currently active */
    val isActive: Boolean,
    /** Link establishment rate in bits/sec (if active) */
    val establishmentRateBps: Long? = null,
    /** Actual measured throughput from prior transfers (most accurate) */
    val expectedRateBps: Long? = null,
    /** First hop interface bitrate (for fast links like WiFi) */
    val nextHopBitrateBps: Long? = null,
    /** Round-trip time in seconds */
    val rttSeconds: Double? = null,
    /** Number of hops to destination */
    val hops: Int? = null,
    /** Link MTU in bytes (higher = faster connection) */
    val linkMtu: Int? = null,
    /** Whether the link already existed (for establish operations) */
    val alreadyExisted: Boolean = false,
    /** Error message if operation failed */
    val error: String? = null,
)

/**
 * State of propagation node message sync/transfer.
 */
data class PropagationState(
    /** Numeric state code (0=idle, 1=path_requested, 2=link_establishing, etc.) */
    val state: Int,
    /** Human-readable state name */
    val stateName: String,
    /** Transfer progress (0.0 to 1.0) */
    val progress: Float,
    /** Number of messages received in the last completed transfer */
    val messagesReceived: Int,
) {
    companion object {
        // These constants mirror LXMF.LXMRouter.PR_* from Python LXMF library.
        // Keep in sync with LXMF/LXMRouter.py propagation transfer states.
        const val STATE_IDLE = 0 // PR_IDLE
        const val STATE_PATH_REQUESTED = 1 // PR_PATH_REQUESTED
        const val STATE_LINK_ESTABLISHING = 2 // PR_LINK_ESTABLISHING
        const val STATE_LINK_ESTABLISHED = 3 // PR_LINK_ESTABLISHED
        const val STATE_REQUEST_SENT = 4 // PR_REQUEST_SENT
        const val STATE_RECEIVING = 5 // PR_RECEIVING
        const val STATE_RESPONSE_RECEIVED = 6 // PR_RESPONSE_RECEIVED
        const val STATE_COMPLETE = 7 // PR_COMPLETE

        val IDLE = PropagationState(STATE_IDLE, "idle", 0f, 0)
    }
}
