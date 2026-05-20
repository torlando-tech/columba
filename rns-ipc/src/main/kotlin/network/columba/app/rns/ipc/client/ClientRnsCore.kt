package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsCore
import network.columba.app.rns.ipc.callback.IRnsAnnounceCallback
import network.columba.app.rns.ipc.callback.IRnsLinkEventCallback
import network.columba.app.rns.ipc.callback.IRnsNetworkStatusCallback
import network.columba.app.rns.ipc.callback.IRnsPacketCallback
import network.columba.app.rns.ipc.toAnnounceRestoreEntries
import network.columba.app.rns.ipc.toPeerIdentityEntries

internal class ClientRnsCore(
    private val remote: IRnsCore,
    private val scope: CoroutineScope,
) : RnsCore {
    override suspend fun initialize(config: ReticulumConfig): Result<Unit> = runCatching {
        awaitResult { cb -> remote.initialize(config, cb) }
        Unit
    }

    override suspend fun shutdown(): Result<Unit> = runCatching {
        awaitResult { cb -> remote.shutdown(cb) }
        Unit
    }

    // INITIALIZING is the canonical "not-yet-bound" state — matches the
    // observer's first emission when the backend hasn't completed initialize()
    // yet, so UI gates on `is READY` don't trip on the brief pre-snapshot gap.
    private val networkStatusState = MutableStateFlow<NetworkStatus>(NetworkStatus.INITIALIZING)

    init {
        callbackFlow<NetworkStatus> {
            val cb = object : IRnsNetworkStatusCallback.Stub() {
                override fun onStatus(status: NetworkStatus?) { if (status != null) trySend(status) }
            }
            remote.registerNetworkStatusObserver(cb)
            awaitClose { runCatching { remote.unregisterNetworkStatusObserver(cb) } }
        }.onEach { networkStatusState.value = it }.launchIn(scope)

        scope.launch {
            // Snapshot fetch races the observer's first emission; the observer
            // is authoritative, so the snapshot only matters if observer hasn't
            // fired yet by the first read on the UI side.
            runCatching {
                awaitNetworkStatus { cb -> remote.getCurrentNetworkStatus(cb) }
                    ?.let { networkStatusState.value = it }
            }
        }
    }

    override val networkStatus: StateFlow<NetworkStatus> get() = networkStatusState.asStateFlow()

    override suspend fun createIdentity(): Result<Identity> = runCatching {
        val bundle = awaitResult { cb -> remote.createIdentity(cb) }
        bundle.classLoader = Identity::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Identity>(BundleKeys.IDENTITY)
            ?: throw RnsException(RnsError.Generic("createIdentity payload missing 'identity'", null))
    }

    override suspend fun loadIdentity(path: String): Result<Identity> = runCatching {
        val bundle = awaitResult { cb -> remote.loadIdentity(path, cb) }
        bundle.classLoader = Identity::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Identity>(BundleKeys.IDENTITY)
            ?: throw RnsException(RnsError.Generic("loadIdentity payload missing 'identity'", null))
    }

    override suspend fun saveIdentity(identity: Identity, path: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.saveIdentity(identity, path, cb) }
        Unit
    }

    override suspend fun recallIdentity(hash: ByteArray): Identity? {
        val bundle = awaitResult { cb -> remote.recallIdentity(hash, cb) }
        bundle.classLoader = Identity::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelable(BundleKeys.IDENTITY)
    }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> {
        val bundle = awaitResult { cb -> remote.createIdentityWithName(displayName, cb) }
        return identityKeyMapFromBundle(bundle)
    }

    override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> {
        val bundle = awaitResult { cb -> remote.importIdentityFile(fileData, displayName, cb) }
        return identityKeyMapFromBundle(bundle)
    }

    override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray =
        awaitNullableByteArray { cb -> remote.exportIdentityFile(keyData, filePath, cb) }
            ?: throw RnsException(RnsError.Generic("exportIdentityFile returned null", null))

    // Promoted to suspend on the Kotlin contract in A.10 — the oneway AIDL
    // shape was already callback-driven (IRnsByteArrayCallback / IRnsIntCallback
    // / IRnsStringCallback), so the seam is honest now.
    override suspend fun getFullIdentityKey(): ByteArray? =
        awaitNullableByteArray { cb -> remote.getFullIdentityKey(cb) }

    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> = runCatching {
        val bundle = awaitResult { cb ->
            remote.createDestination(identity, direction, type, appName, aspects, cb)
        }
        bundle.classLoader = Destination::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Destination>(BundleKeys.DESTINATION)
            ?: throw RnsException(RnsError.Generic("createDestination payload missing 'destination'", null))
    }

    override suspend fun announceDestination(destination: Destination, appData: ByteArray?): Result<Unit> = runCatching {
        awaitResult { cb -> remote.announceDestination(destination, appData, cb) }
        Unit
    }

    override suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.triggerAutoAnnounce(displayName, cb) }
        Unit
    }

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> = runCatching {
        val bundle = awaitResult { cb -> remote.sendPacket(destination, data, packetType, cb) }
        bundle.classLoader = PacketReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<PacketReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendPacket payload missing 'receipt'", null))
    }

    override fun observePackets(): Flow<ReceivedPacket> = callbackFlow {
        val cb = object : IRnsPacketCallback.Stub() {
            override fun onPacket(packet: ReceivedPacket?) { if (packet != null) trySend(packet) }
        }
        remote.registerPacketObserver(cb)
        awaitClose { runCatching { remote.unregisterPacketObserver(cb) } }
    }

    override suspend fun establishLink(destination: Destination): Result<Link> = runCatching {
        val bundle = awaitResult { cb -> remote.establishLink(destination, cb) }
        bundle.classLoader = Link::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<Link>(BundleKeys.LINK)
            ?: throw RnsException(RnsError.Generic("establishLink payload missing 'link'", null))
    }

    override suspend fun closeLink(link: Link): Result<Unit> = runCatching {
        awaitResult { cb -> remote.closeLink(link, cb) }
        Unit
    }

    override suspend fun sendOverLink(link: Link, data: ByteArray): Result<Unit> = runCatching {
        awaitResult { cb -> remote.sendOverLink(link, data, cb) }
        Unit
    }

    override fun observeLinks(): Flow<LinkEvent> = callbackFlow {
        val cb = object : IRnsLinkEventCallback.Stub() {
            override fun onLinkEvent(event: LinkEvent?) { if (event != null) trySend(event) }
        }
        remote.registerLinkObserver(cb)
        awaitClose { runCatching { remote.unregisterLinkObserver(cb) } }
    }

    override suspend fun hasPath(destinationHash: ByteArray): Boolean =
        awaitBool { cb -> remote.hasPath(destinationHash, cb) }

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> = runCatching {
        awaitResult { cb -> remote.requestPath(destinationHash, cb) }
        Unit
    }

    override suspend fun persistTransportData() {
        awaitResult { cb -> remote.persistTransportData(cb) }
    }

    override suspend fun getHopCount(destinationHash: ByteArray): Int? =
        awaitNullableInt { cb -> remote.getHopCount(destinationHash, cb) }

    override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? =
        awaitNullableString { cb -> remote.getNextHopInterfaceName(destinationHash, cb) }

    override suspend fun getPathTableHashes(): List<String> =
        awaitStringList { cb -> remote.getPathTableHashes(cb) }

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult {
        val bundle = awaitResult { cb ->
            remote.probeLinkSpeed(destinationHash, timeoutSeconds, deliveryMethod, cb)
        }
        bundle.classLoader = LinkSpeedProbeResult::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelable(BundleKeys.PROBE)
            ?: throw RnsException(RnsError.Generic("probeLinkSpeed payload missing 'probe'", null))
    }

    override suspend fun isTransportEnabled(): Boolean =
        awaitBool { cb -> remote.isTransportEnabled(cb) }

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> = runCatching {
        val bundle = awaitResult { cb -> remote.establishConversationLink(destinationHash, timeoutSeconds, cb) }
        bundle.classLoader = ConversationLinkResult::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<ConversationLinkResult>(BundleKeys.LINK_RESULT)
            ?: throw RnsException(RnsError.Generic("establishConversationLink payload missing 'link_result'", null))
    }

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> = runCatching {
        awaitBool { cb -> remote.closeConversationLink(destinationHash, cb) }
    }

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult {
        val bundle = awaitResult { cb -> remote.getConversationLinkStatus(destinationHash, cb) }
        bundle.classLoader = ConversationLinkResult::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelable(BundleKeys.LINK_RESULT)
            ?: throw RnsException(RnsError.Generic("getConversationLinkStatus payload missing 'link_result'", null))
    }

    override fun observeAnnounces(): Flow<AnnounceEvent> = callbackFlow {
        val cb = object : IRnsAnnounceCallback.Stub() {
            override fun onAnnounce(event: AnnounceEvent?) { if (event != null) trySend(event) }
        }
        remote.registerAnnounceObserver(cb)
        awaitClose { runCatching { remote.unregisterAnnounceObserver(cb) } }
    }

    override suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> = runCatching {
        val bundle = awaitResult { cb ->
            remote.restorePeerIdentities(peerIdentities.toPeerIdentityEntries(), cb)
        }
        bundle.getInt(BundleKeys.COUNT, 0)
    }

    override suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> = runCatching {
        val bundle = awaitResult { cb ->
            remote.restoreAnnounceIdentities(announces.toAnnounceRestoreEntries(), cb)
        }
        bundle.getInt(BundleKeys.COUNT, 0)
    }

    override suspend fun blockDestination(destinationHashHex: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.blockDestination(destinationHashHex, cb) }
        Unit
    }

    override suspend fun unblockDestination(destinationHashHex: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.unblockDestination(destinationHashHex, cb) }
        Unit
    }

    override suspend fun blackholeIdentity(identityHashHex: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.blackholeIdentity(identityHashHex, cb) }
        Unit
    }

    override suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit> = runCatching {
        awaitResult { cb -> remote.unblackholeIdentity(identityHashHex, cb) }
        Unit
    }

    private fun identityKeyMapFromBundle(bundle: android.os.Bundle): Map<String, Any> {
        val map = LinkedHashMap<String, Any>(6)
        bundle.getByteArray(BundleKeys.KEY_DATA)?.let { map[BundleKeys.KEY_DATA] = it }
        bundle.getString(BundleKeys.DISPLAY_NAME)?.let { map[BundleKeys.DISPLAY_NAME] = it }
        bundle.getString(BundleKeys.IDENTITY_HASH)?.let { map[BundleKeys.IDENTITY_HASH] = it }
        bundle.getString(BundleKeys.DESTINATION_HASH)?.let { map[BundleKeys.DESTINATION_HASH] = it }
        bundle.getString(BundleKeys.FILE_PATH)?.let { map[BundleKeys.FILE_PATH] = it }
        bundle.getByteArray(BundleKeys.PUBLIC_KEY)?.let { map[BundleKeys.PUBLIC_KEY] = it }
        return map
    }
}

/**
 * Snapshot read for the [NetworkStatus] StateFlow over the
 * [IRnsNetworkStatusCallback] surface (which has no error path, just a
 * one-shot `onStatus`). Suspends until the host fires once.
 */
private suspend inline fun awaitNetworkStatus(
    crossinline call: (IRnsNetworkStatusCallback) -> Unit,
): NetworkStatus? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    val cb = object : IRnsNetworkStatusCallback.Stub() {
        override fun onStatus(status: NetworkStatus?) {
            if (delivered.compareAndSet(false, true)) {
                cont.resumeWith(Result.success(status))
            }
        }
    }
    try {
        call(cb)
    } catch (e: android.os.RemoteException) {
        if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.success(null))
    }
}
