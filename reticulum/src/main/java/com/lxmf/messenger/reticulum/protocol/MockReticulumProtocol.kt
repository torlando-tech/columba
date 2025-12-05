package com.lxmf.messenger.reticulum.protocol

import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.Link
import com.lxmf.messenger.reticulum.model.LinkEvent
import com.lxmf.messenger.reticulum.model.LinkStatus
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.model.PacketType
import com.lxmf.messenger.reticulum.model.ReceivedPacket
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Mock implementation of ReticulumProtocol for testing and development.
 * This allows the UI to be developed and tested without requiring
 * a full Reticulum implementation.
 */
class MockReticulumProtocol : ReticulumProtocol {
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()
    private val random = SecureRandom()

    override suspend fun initialize(config: ReticulumConfig): Result<Unit> {
        return runCatching {
            _networkStatus.value = NetworkStatus.INITIALIZING
            delay(500) // Simulate initialization delay
            _networkStatus.value = NetworkStatus.READY
        }
    }

    override suspend fun shutdown(): Result<Unit> {
        return runCatching {
            _networkStatus.value = NetworkStatus.SHUTDOWN
        }
    }

    override suspend fun createIdentity(): Result<Identity> {
        return runCatching {
            val privateKey = ByteArray(32).apply { random.nextBytes(this) }
            val publicKey = ByteArray(32).apply { random.nextBytes(this) }
            val hash =
                MessageDigest.getInstance("SHA-256")
                    .digest(publicKey)
                    .copyOf(16) // Reticulum uses truncated hash

            Identity(
                hash = hash,
                publicKey = publicKey,
                privateKey = privateKey,
            )
        }
    }

    override suspend fun loadIdentity(path: String): Result<Identity> {
        return Result.failure(NotImplementedError("Mock implementation"))
    }

    override suspend fun saveIdentity(
        identity: Identity,
        path: String,
    ): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun recallIdentity(hash: ByteArray): Identity? = null

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> {
        // Mock implementation - generate a fake identity
        val identityHash =
            ByteArray(16).apply { random.nextBytes(this) }
                .joinToString("") { "%02x".format(it) }
        val destinationHash =
            ByteArray(16).apply { random.nextBytes(this) }
                .joinToString("") { "%02x".format(it) }

        return mapOf(
            "identity_hash" to identityHash,
            "destination_hash" to destinationHash,
            "file_path" to "/mock/identities/$identityHash.identity",
        )
    }

    override suspend fun deleteIdentityFile(identityHash: String): Map<String, Any> {
        // Mock implementation - always succeed
        return mapOf("success" to true)
    }

    override suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any> {
        // Mock implementation - generate a fake identity
        val identityHash =
            ByteArray(16).apply { random.nextBytes(this) }
                .joinToString("") { "%02x".format(it) }
        val destinationHash =
            ByteArray(16).apply { random.nextBytes(this) }
                .joinToString("") { "%02x".format(it) }

        return mapOf(
            "identity_hash" to identityHash,
            "destination_hash" to destinationHash,
            "file_path" to "/mock/identities/$identityHash.identity",
        )
    }

    override suspend fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray {
        // Mock implementation - return fake identity data
        return ByteArray(256).apply { random.nextBytes(this) }
    }

    override suspend fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): Map<String, Any> {
        // Mock implementation - pretend recovery succeeded
        return mapOf(
            "success" to true,
            "file_path" to filePath,
        )
    }

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> {
        return runCatching {
            val destHash =
                MessageDigest.getInstance("SHA-256")
                    .digest((appName + aspects.joinToString()).toByteArray())
                    .copyOf(16)

            Destination(
                hash = destHash,
                hexHash = destHash.joinToString("") { "%02x".format(it) },
                identity = identity,
                direction = direction,
                type = type,
                appName = appName,
                aspects = aspects,
            )
        }
    }

    override suspend fun announceDestination(
        destination: Destination,
        appData: ByteArray?,
    ): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> {
        return runCatching {
            delay(100) // Simulate network delay
            PacketReceipt(
                hash = ByteArray(32).apply { random.nextBytes(this) },
                delivered = true,
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    override fun observePackets(): Flow<ReceivedPacket> =
        flow {
            // Mock implementation - no packets received
        }

    override suspend fun establishLink(destination: Destination): Result<Link> {
        return runCatching {
            delay(200) // Simulate link establishment delay
            Link(
                id = random.nextInt().toString(),
                destination = destination,
                status = LinkStatus.ACTIVE,
                establishedAt = System.currentTimeMillis(),
                rtt = 150f,
            )
        }
    }

    override suspend fun closeLink(link: Link): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sendOverLink(
        link: Link,
        data: ByteArray,
    ): Result<Unit> {
        return Result.success(Unit)
    }

    override fun observeLinks(): Flow<LinkEvent> =
        flow {
            // Mock implementation - no link events
        }

    override suspend fun hasPath(destinationHash: ByteArray): Boolean = true

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> {
        return Result.success(Unit)
    }

    override fun getHopCount(destinationHash: ByteArray): Int = 3

    override suspend fun getPathTableHashes(): List<String> = emptyList()

    override fun observeAnnounces(): Flow<AnnounceEvent> =
        flow {
            // Mock implementation - no announces
        }

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
    ): Result<MessageReceipt> {
        // Mock implementation
        return Result.success(
            MessageReceipt(
                messageHash = ByteArray(32) { it.toByte() },
                timestamp = System.currentTimeMillis(),
                destinationHash = destinationHash, // Return the same destination hash that was passed in
            ),
        )
    }

    override fun observeMessages(): Flow<ReceivedMessage> =
        flow {
            // Mock implementation - no messages
        }

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> =
        flow {
            // Mock implementation - no delivery status updates
        }

    override suspend fun getDebugInfo(): Map<String, Any> {
        return mapOf(
            "initialized" to (networkStatus.value == NetworkStatus.READY),
            "reticulum_available" to true,
            "storage_path" to "/mock/path",
            "interfaces" to emptyList<Map<String, Any>>(),
        )
    }

    override fun setConversationActive(active: Boolean) {
        // No-op for mock implementation
    }

    override suspend fun reconnectRNodeInterface() {
        // No-op for mock implementation
    }
}
