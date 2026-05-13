package network.columba.app.rns.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig

/**
 * RNS protocol primitives: lifecycle, identity, destination, packet, link,
 * path/transport, conversation links, announces, peer/announce identity
 * restoration, and per-destination blocking/blackhole.
 *
 * Methods on this interface map directly to RNS Reticulum / Identity /
 * Destination / Link / Transport classes — anything that's purely an RNS
 * protocol concept (no LXMF, no LXST, no NomadNet) belongs here.
 */
@Suppress("TooManyFunctions") // Mirrors the RNS protocol surface; splitting further would fragment cohesion.
interface RnsCore {
    // ==================== Initialization & lifecycle ====================

    suspend fun initialize(config: ReticulumConfig): Result<Unit>

    suspend fun shutdown(): Result<Unit>

    val networkStatus: StateFlow<NetworkStatus>

    // ==================== Identity management ====================

    suspend fun createIdentity(): Result<Identity>

    suspend fun loadIdentity(path: String): Result<Identity>

    suspend fun saveIdentity(
        identity: Identity,
        path: String,
    ): Result<Unit>

    suspend fun recallIdentity(hash: ByteArray): Identity?

    /**
     * Multi-identity management.
     *
     * These methods never write plaintext private keys to the app's internal
     * filesystem. `createIdentityWithName` and `importIdentityFile` return the
     * raw 64-byte key via the `key_data` map entry; callers are expected to
     * hand it to `IdentityKeyProvider` which wraps it with the Android
     * Keystore before writing to Room. `exportIdentityFile` takes the already-
     * decrypted bytes and writes to a user-chosen `filePath` (typically a SAF
     * URI-backed scratch file) for the user to share via the system chooser.
     */
    suspend fun createIdentityWithName(displayName: String): Map<String, Any>

    suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any>

    suspend fun exportIdentityFile(
        keyData: ByteArray,
        filePath: String,
    ): ByteArray

    /**
     * Return the raw 64-byte private key (X25519_prv + Ed25519_prv) for encrypted Room
     * storage, or null if the implementation has no key material to export yet.
     * Implementations without persistent keys (tests, mocks) return null.
     */
    fun getFullIdentityKey(): ByteArray?

    // ==================== Destination management ====================

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

    /**
     * Convenience wrapper that constructs the standard LXMF delivery
     * destination from [displayName] and announces it. Implementations are
     * free to short-circuit to the cached LXMF identity rather than
     * re-deriving on every call.
     */
    suspend fun triggerAutoAnnounce(displayName: String): Result<Unit>

    // ==================== Packet operations ====================

    suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType = PacketType.DATA,
    ): Result<PacketReceipt>

    fun observePackets(): Flow<ReceivedPacket>

    // ==================== Link operations ====================

    suspend fun establishLink(destination: Destination): Result<Link>

    suspend fun closeLink(link: Link): Result<Unit>

    suspend fun sendOverLink(
        link: Link,
        data: ByteArray,
    ): Result<Unit>

    fun observeLinks(): Flow<LinkEvent>

    // ==================== Path & transport ====================

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

    /**
     * Returns true if this device is configured as a transport node (i.e.,
     * `enableTransport = true` in [ReticulumConfig] and the underlying RNS
     * Transport is running).
     */
    suspend fun isTransportEnabled(): Boolean

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

    // ==================== Announce handling ====================

    fun observeAnnounces(): Flow<AnnounceEvent>

    // ==================== Peer / Announce identity restoration ====================

    /**
     * Re-seed the backend's identity store with peer public keys recovered
     * from app storage at startup, so the stack can resolve destinations to
     * identities without waiting for fresh announces.
     *
     * @param peerIdentities list of (`hashHex`, `publicKey`) pairs.
     * @return Result containing the count of identities successfully restored.
     */
    suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int>

    /**
     * Re-seed the backend's announce/destination cache with announces
     * recovered from app storage at startup.
     *
     * @param announces list of (`destinationHashHex`, `announcePacketBytes`) pairs.
     * @return Result containing the count of announces successfully restored.
     */
    suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int>

    // ==================== Peer Blocking & Blackhole ====================

    suspend fun blockDestination(destinationHashHex: String): Result<Unit>

    suspend fun unblockDestination(destinationHashHex: String): Result<Unit>

    suspend fun blackholeIdentity(identityHashHex: String): Result<Unit>

    suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit>
}
