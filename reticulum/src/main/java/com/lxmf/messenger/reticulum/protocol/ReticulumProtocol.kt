package com.lxmf.messenger.reticulum.protocol

import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
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

    // Announce handling
    fun observeAnnounces(): Flow<AnnounceEvent>

    // LXMF Messaging
    suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray? = null,
        imageFormat: String? = null,
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
)

/**
 * Delivery status update for a sent LXMF message
 */
data class DeliveryStatusUpdate(
    val messageHash: String,
    val status: String, // "delivered" or "failed"
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
         * Used by both PythonReticulumProtocol and ServiceReticulumProtocol.
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
