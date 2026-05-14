package network.columba.app.reticulum.protocol

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.BatteryProfile
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
import network.columba.app.rns.api.model.NomadnetPageResult
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.api.model.VoiceCallState
import network.columba.app.rns.backend.kt.NativeRnsBackend

/**
 * Strangler-fig facade implementing [ReticulumProtocol] on top of
 * [NativeRnsBackend].
 *
 * Phase A.8 split the 2005-line `NativeReticulumProtocol` body into
 * `NativeRnsBackendImpl` (in `:rns-backend-kt`) which implements all 6
 * [network.columba.app.rns.api.RnsBackend] sub-interfaces. This facade
 * delegates every `ReticulumProtocol` method to the matching sub-impl so
 * the 32 UI-side `ReticulumProtocol`-injecting call sites keep working
 * unchanged until A.10 rewires them to inject specific sub-interfaces.
 *
 * Construction:
 * - The `:app` Hilt module `ReticulumModule` provides this as
 *   `@Singleton`, passing in the singleton [NativeRnsBackend] that
 *   `:rns-host`'s `HostBackendModule` (kotlinBackend flavor) constructs
 *   with the `RNodeHostBridge`. Sharing the singleton is load-bearing:
 *   `ReticulumProtocol` callers and `RnsBackend`/`RnsTelephony` callers
 *   (A.9+) must observe the same RNS Transport / LXMF Router / call
 *   state. Constructing the backend inside this facade — as a prior
 *   draft did — split the UI-process Hilt graph into two parallel
 *   stacks.
 *
 * Plan deviation #8: The handoff suggested splitting into 6 separate sub-
 * impl classes with a private state holder. Instead `NativeRnsBackendImpl`
 * holds the state and implements all 6 sub-interfaces directly; the root
 * [NativeRnsBackend] exposes the same instance through 6 typed accessors.
 * Architecturally indistinguishable from outside, but cuts the file count
 * and avoids threading shared mutable state through a holder.
 */
@Suppress("TooManyFunctions")
class NativeReticulumProtocol(
    val backend: NativeRnsBackend,
) : ReticulumProtocol {

    /**
     * Construct a facade that builds its own [NativeRnsBackend] with
     * defaults. Retained for unit tests and for the rare consumer that
     * needs a UI-process-only stack without going through Hilt. Production
     * paths go through `ReticulumModule.provideReticulumProtocol` which
     * injects the singleton [NativeRnsBackend] from `HostBackendModule`.
     */
    constructor(appContext: Context? = null) : this(NativeRnsBackend(appContext = appContext))

    private val impl get() = backend.impl

    // ==================== Initialization & lifecycle ====================

    override suspend fun initialize(config: ReticulumConfig): Result<Unit> = impl.initialize(config)

    override suspend fun shutdown(): Result<Unit> = impl.shutdown()

    override val networkStatus: StateFlow<NetworkStatus> get() = impl.networkStatus

    override fun getStatus(): Result<String> = impl.getStatus()

    override fun isInitialized(): Result<Boolean> = impl.isInitialized()

    // ==================== Identity management ====================

    override suspend fun createIdentity(): Result<Identity> = impl.createIdentity()

    override suspend fun loadIdentity(path: String): Result<Identity> = impl.loadIdentity(path)

    override suspend fun saveIdentity(identity: Identity, path: String): Result<Unit> = impl.saveIdentity(identity, path)

    override suspend fun recallIdentity(hash: ByteArray): Identity? = impl.recallIdentity(hash)

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> = impl.createIdentityWithName(displayName)

    override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> =
        impl.importIdentityFile(fileData, displayName)

    override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray =
        impl.exportIdentityFile(keyData, filePath)

    override suspend fun getFullIdentityKey(): ByteArray? = impl.getFullIdentityKey()

    override suspend fun getLxmfIdentity(): Result<Identity> = impl.getLxmfIdentity()

    override suspend fun getLxmfDestination(): Result<Destination> = impl.getLxmfDestination()

    // ==================== Destination & announces ====================

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> = impl.createDestination(identity, direction, type, appName, aspects)

    override suspend fun announceDestination(destination: Destination, appData: ByteArray?): Result<Unit> =
        impl.announceDestination(destination, appData)

    override suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> = impl.triggerAutoAnnounce(displayName)

    override fun observeAnnounces(): Flow<AnnounceEvent> = impl.observeAnnounces()

    override suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> =
        impl.restorePeerIdentities(peerIdentities)

    override suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> =
        impl.restoreAnnounceIdentities(announces)

    // ==================== Packet operations ====================

    override suspend fun sendPacket(destination: Destination, data: ByteArray, packetType: PacketType): Result<PacketReceipt> =
        impl.sendPacket(destination, data, packetType)

    override fun observePackets(): Flow<ReceivedPacket> = impl.observePackets()

    // ==================== Link operations ====================

    override suspend fun establishLink(destination: Destination): Result<Link> = impl.establishLink(destination)

    override suspend fun closeLink(link: Link): Result<Unit> = impl.closeLink(link)

    override suspend fun sendOverLink(link: Link, data: ByteArray): Result<Unit> = impl.sendOverLink(link, data)

    override fun observeLinks(): Flow<LinkEvent> = impl.observeLinks()

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> = impl.establishConversationLink(destinationHash, timeoutSeconds)

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> =
        impl.closeConversationLink(destinationHash)

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult =
        impl.getConversationLinkStatus(destinationHash)

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult = impl.probeLinkSpeed(destinationHash, timeoutSeconds, deliveryMethod)

    // ==================== Path & transport ====================

    override suspend fun hasPath(destinationHash: ByteArray): Boolean = impl.hasPath(destinationHash)

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> = impl.requestPath(destinationHash)

    override suspend fun persistTransportData() = impl.persistTransportData()

    override suspend fun getHopCount(destinationHash: ByteArray): Int? = impl.getHopCount(destinationHash)

    override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? = impl.getNextHopInterfaceName(destinationHash)

    override suspend fun getPathTableHashes(): List<String> = impl.getPathTableHashes()

    override suspend fun isTransportEnabled(): Boolean = impl.isTransportEnabled()

    // ==================== LXMF messaging ====================

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> = impl.sendLxmfMessage(
        destinationHash, content, sourceIdentity, imageData, imageFormat, fileAttachments,
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
        iconAppearance: IconAppearance?,
        extraFields: Map<Int, Any>?,
    ): Result<MessageReceipt> = impl.sendLxmfMessageWithMethod(
        destinationHash, content, sourceIdentity, deliveryMethod, tryPropagationOnFail,
        imageData, imageFormat, fileAttachments, replyToMessageId, iconAppearance, extraFields,
    )

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> = impl.sendReaction(destinationHash, targetMessageId, emoji, sourceIdentity)

    override fun observeMessages(): Flow<ReceivedMessage> = impl.observeMessages()

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = impl.observeDeliveryStatus()

    override fun setConversationActive(active: Boolean) = impl.setConversationActive(active)

    override fun setIncomingMessageSizeLimit(limitKb: Int) = impl.setIncomingMessageSizeLimit(limitKb)

    // ==================== Propagation ====================

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> = impl.setOutboundPropagationNode(destHash)

    override suspend fun getOutboundPropagationNode(): Result<String?> = impl.getOutboundPropagationNode()

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> = impl.requestMessagesFromPropagationNode(identityPrivateKey, maxMessages)

    override suspend fun getPropagationState(): Result<PropagationState> = impl.getPropagationState()

    override val propagationStateFlow: SharedFlow<PropagationState> get() = impl.propagationStateFlow

    // ==================== Telemetry ====================

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> = impl.sendLocationTelemetry(destinationHash, locationJson, sourceIdentity, iconAppearance)

    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> = impl.sendTelemetryRequest(destinationHash, sourceIdentity, timebase, isCollectorRequest)

    override suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit> = impl.setTelemetryCollectorMode(enabled)

    override suspend fun storeOwnTelemetry(locationJson: String, iconAppearance: IconAppearance?): Result<Unit> =
        impl.storeOwnTelemetry(locationJson, iconAppearance)

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> =
        impl.setTelemetryAllowedRequesters(allowedHashes)

    override val locationTelemetryFlow: SharedFlow<String> get() = impl.locationTelemetryFlow

    override val reactionReceivedFlow: SharedFlow<String> get() = impl.reactionReceivedFlow

    // ==================== Voice calls (LXST) ====================

    override suspend fun initiateCall(destinationHash: String, profileCode: Int?): Result<Unit> =
        impl.initiateCall(destinationHash, profileCode)

    override suspend fun answerCall(): Result<Unit> = impl.answerCall()

    override suspend fun hangupCall() = impl.hangupCall()

    override suspend fun setCallMuted(muted: Boolean) = impl.setCallMuted(muted)

    override suspend fun setCallSpeaker(speakerOn: Boolean) = impl.setCallSpeaker(speakerOn)

    override suspend fun getCallState(): Result<VoiceCallState> = impl.getCallState()

    // ==================== NomadNet ====================

    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<ReticulumProtocol.NomadnetPageResult> {
        // The :rns-api NomadnetPageResult and ReticulumProtocol.NomadnetPageResult are
        // structurally identical but live in different packages. Map one to the other
        // until A.10 rewires call sites to consume the :rns-api shape directly.
        return impl.requestNomadnetPage(destinationHash, path, formDataJson, timeoutSeconds).map { apiResult ->
            ReticulumProtocol.NomadnetPageResult(
                content = apiResult.content,
                path = apiResult.path,
                type = apiResult.type,
                filePath = apiResult.filePath,
                fileName = apiResult.fileName,
                fileSize = apiResult.fileSize,
            )
        }
    }

    override suspend fun cancelNomadnetPageRequest() = impl.cancelNomadnetPageRequest()

    override suspend fun getNomadnetRequestStatus(): String = impl.getNomadnetRequestStatus()

    override suspend fun getNomadnetDownloadProgress(): Float = impl.getNomadnetDownloadProgress()

    override suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> = impl.identifyNomadnetLink(destinationHash)

    override val nomadnetRequestStatusFlow: StateFlow<String> get() = impl.nomadnetRequestStatusFlow

    override val nomadnetDownloadProgressFlow: StateFlow<Float> get() = impl.nomadnetDownloadProgressFlow

    // ==================== Transport admin / battery / interfaces ====================

    override fun setBatteryProfile(profile: BatteryProfile) = impl.setBatteryProfile(profile)

    override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) = impl.reloadInterfaces(configs)

    override suspend fun setDiscoveryEnabled(enabled: Boolean) = impl.setDiscoveryEnabled(enabled)

    override suspend fun setAutoconnectLimit(count: Int) = impl.setAutoconnectLimit(count)

    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) = impl.setAutoconnectIfacOnly(enabled)

    override suspend fun getDebugInfo(): Map<String, Any> = impl.getDebugInfo()

    override suspend fun getFailedInterfaces(): List<FailedInterface> = impl.getFailedInterfaces()

    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? = impl.getInterfaceStats(interfaceName)

    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> = impl.getDiscoveredInterfaces()

    override suspend fun isDiscoveryEnabled(): Boolean = impl.isDiscoveryEnabled()

    override suspend fun getAutoconnectedEndpoints(): Set<String> = impl.getAutoconnectedEndpoints()

    override suspend fun reconnectRNodeInterface() = impl.reconnectRNodeInterface()

    override fun getRNodeRssi(): Int = impl.getRNodeRssi()

    override fun getBleConnectionDetails(): String = impl.getBleConnectionDetails()

    override val interfaceStatusChanged: SharedFlow<Unit> get() = impl.interfaceStatusChanged

    override val bleConnectionsFlow: SharedFlow<String> get() = impl.bleConnectionsFlow

    override val debugInfoFlow: SharedFlow<String> get() = impl.debugInfoFlow

    override val interfaceStatusFlow: SharedFlow<String> get() = impl.interfaceStatusFlow

    // ==================== Blocking & blackhole ====================

    override suspend fun blockDestination(destinationHashHex: String): Result<Unit> = impl.blockDestination(destinationHashHex)

    override suspend fun unblockDestination(destinationHashHex: String): Result<Unit> = impl.unblockDestination(destinationHashHex)

    override suspend fun blackholeIdentity(identityHashHex: String): Result<Unit> = impl.blackholeIdentity(identityHashHex)

    override suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit> = impl.unblackholeIdentity(identityHashHex)

    // ==================== Shared instance ====================

    override suspend fun isSharedInstanceAvailable(): Boolean = impl.isSharedInstanceAvailable()

    // ==================== Version helpers ====================

    override suspend fun getReticulumVersion(): String? = impl.getReticulumVersion()

    override suspend fun getLxmfVersion(): String? = impl.getLxmfVersion()

    override suspend fun getBleReticulumVersion(): String? = impl.getBleReticulumVersion()

    override suspend fun getLxstVersion(): String? = impl.getLxstVersion()
}
