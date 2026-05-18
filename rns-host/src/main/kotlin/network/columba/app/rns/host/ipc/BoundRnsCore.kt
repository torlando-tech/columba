package network.columba.app.rns.host.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
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
 * UI-side proxy that delegates every [RnsCore] member to the currently-bound
 * [RnsBackend] (the AIDL client returned by
 * [network.columba.app.rns.host.ReticulumServiceConnection]).
 *
 * Suspend methods first await a live binding via [backendFlow], then forward.
 * Flow / StateFlow accessors are republished through `flatMapLatest` so a
 * rebind (binder death + START_STICKY recovery) cancels the previous
 * subscription and resubscribes to the fresh backend without a terminal
 * completion reaching downstream UI collectors.
 */
internal class BoundRnsCore(
    private val backendFlow: StateFlow<RnsBackend?>,
    scope: CoroutineScope,
) : RnsCore {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    /**
     * Wait for the NEXT non-null binding emission, skipping the current value
     * if any. Used by [initialize] to recover when the cached binding turned
     * out to be a dead binder — we need to wait for a fresh
     * `onServiceConnected`, not re-take the same stale reference.
     */
    private suspend fun awaitNextFreshBound(): RnsBackend =
        backendFlow.drop(1).filterNotNull().first()

    /**
     * Resilient initialize that survives an `onServiceDisconnected` race during
     * Apply & Restart. If the cached binding is dead at call time, the AIDL
     * layer surfaces it as [RnsError.BackendNotReady] (see
     * `ClientAidlBridge.remoteToRnsException`); rather than propagate that
     * spurious failure to the UI, we drop the dead reference and wait for the
     * next fresh binding before retrying. Bounded to one retry so a genuinely
     * misconfigured backend still fails fast.
     *
     * Punch-list item 10: InterfaceConfigManager Step 9 used to fail here
     * within milliseconds because the `:reticulum` we just sent ACTION_STOP
     * to had System.exit'd but its binder reference was still sitting in
     * [backendFlow] (onServiceDisconnected hadn't propagated yet, or had
     * propagated but with no null sentinel so the StateFlow still held the
     * dead client). The retry-on-dead-binder gives Android time to deliver
     * `onServiceConnected` for the fresh `:reticulum` process the apply
     * sequence's Step 7 `startForegroundService(ACTION_START)` triggered.
     */
    override suspend fun initialize(config: ReticulumConfig): Result<Unit> {
        val first = awaitBound().core.initialize(config)
        val firstError = first.exceptionOrNull()
        if (firstError is RnsException && firstError.error is RnsError.BackendNotReady) {
            return awaitNextFreshBound().core.initialize(config)
        }
        return first
    }

    override suspend fun shutdown(): Result<Unit> = awaitBound().core.shutdown()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val networkStatus: StateFlow<NetworkStatus> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.core.networkStatus }
            .stateIn(scope, SharingStarted.Eagerly, NetworkStatus.INITIALIZING)

    override suspend fun createIdentity(): Result<Identity> = awaitBound().core.createIdentity()

    override suspend fun loadIdentity(path: String): Result<Identity> =
        awaitBound().core.loadIdentity(path)

    override suspend fun saveIdentity(identity: Identity, path: String): Result<Unit> =
        awaitBound().core.saveIdentity(identity, path)

    override suspend fun recallIdentity(hash: ByteArray): Identity? =
        awaitBound().core.recallIdentity(hash)

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> =
        awaitBound().core.createIdentityWithName(displayName)

    override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> =
        awaitBound().core.importIdentityFile(fileData, displayName)

    override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray =
        awaitBound().core.exportIdentityFile(keyData, filePath)

    override suspend fun getFullIdentityKey(): ByteArray? = awaitBound().core.getFullIdentityKey()

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> =
        awaitBound().core.createDestination(identity, direction, type, appName, aspects)

    override suspend fun announceDestination(destination: Destination, appData: ByteArray?): Result<Unit> =
        awaitBound().core.announceDestination(destination, appData)

    override suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> =
        awaitBound().core.triggerAutoAnnounce(displayName)

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> = awaitBound().core.sendPacket(destination, data, packetType)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePackets(): Flow<ReceivedPacket> =
        backendFlow.filterNotNull().flatMapLatest { it.core.observePackets() }

    override suspend fun establishLink(destination: Destination): Result<Link> =
        awaitBound().core.establishLink(destination)

    override suspend fun closeLink(link: Link): Result<Unit> = awaitBound().core.closeLink(link)

    override suspend fun sendOverLink(link: Link, data: ByteArray): Result<Unit> =
        awaitBound().core.sendOverLink(link, data)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLinks(): Flow<LinkEvent> =
        backendFlow.filterNotNull().flatMapLatest { it.core.observeLinks() }

    override suspend fun hasPath(destinationHash: ByteArray): Boolean =
        awaitBound().core.hasPath(destinationHash)

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> =
        awaitBound().core.requestPath(destinationHash)

    override suspend fun persistTransportData() {
        awaitBound().core.persistTransportData()
    }

    override suspend fun getHopCount(destinationHash: ByteArray): Int? =
        awaitBound().core.getHopCount(destinationHash)

    override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? =
        awaitBound().core.getNextHopInterfaceName(destinationHash)

    override suspend fun getPathTableHashes(): List<String> = awaitBound().core.getPathTableHashes()

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult =
        awaitBound().core.probeLinkSpeed(destinationHash, timeoutSeconds, deliveryMethod)

    override suspend fun isTransportEnabled(): Boolean = awaitBound().core.isTransportEnabled()

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> =
        awaitBound().core.establishConversationLink(destinationHash, timeoutSeconds)

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> =
        awaitBound().core.closeConversationLink(destinationHash)

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult =
        awaitBound().core.getConversationLinkStatus(destinationHash)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAnnounces(): Flow<AnnounceEvent> =
        backendFlow.filterNotNull().flatMapLatest { it.core.observeAnnounces() }

    override suspend fun restorePeerIdentities(
        peerIdentities: List<Pair<String, ByteArray>>,
    ): Result<Int> = awaitBound().core.restorePeerIdentities(peerIdentities)

    override suspend fun restoreAnnounceIdentities(
        announces: List<Pair<String, ByteArray>>,
    ): Result<Int> = awaitBound().core.restoreAnnounceIdentities(announces)

    override suspend fun blockDestination(destinationHashHex: String): Result<Unit> =
        awaitBound().core.blockDestination(destinationHashHex)

    override suspend fun unblockDestination(destinationHashHex: String): Result<Unit> =
        awaitBound().core.unblockDestination(destinationHashHex)

    override suspend fun blackholeIdentity(identityHashHex: String): Result<Unit> =
        awaitBound().core.blackholeIdentity(identityHashHex)

    override suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit> =
        awaitBound().core.unblackholeIdentity(identityHashHex)
}
