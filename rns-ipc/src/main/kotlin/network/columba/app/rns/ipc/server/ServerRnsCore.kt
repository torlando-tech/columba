package network.columba.app.rns.ipc.server

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.AnnounceRestoreEntry
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.PeerIdentityEntry
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsCore
import network.columba.app.rns.ipc.callback.IRnsAnnounceCallback
import network.columba.app.rns.ipc.callback.IRnsBoolCallback
import network.columba.app.rns.ipc.callback.IRnsByteArrayCallback
import network.columba.app.rns.ipc.callback.IRnsIntCallback
import network.columba.app.rns.ipc.callback.IRnsLinkEventCallback
import network.columba.app.rns.ipc.callback.IRnsNetworkStatusCallback
import network.columba.app.rns.ipc.callback.IRnsPacketCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.callback.IRnsStringListCallback
import network.columba.app.rns.ipc.toAnnounceRestorePairs
import network.columba.app.rns.ipc.toPeerIdentityPairs
import android.os.RemoteException

internal class ServerRnsCore(
    private val impl: RnsCore,
    private val scope: CoroutineScope,
) : IRnsCore.Stub() {
    private val networkStatusHub = ObserverHub<NetworkStatus, IRnsNetworkStatusCallback>(
        scope = scope,
        upstream = { impl.networkStatus },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onStatus(value) },
    )
    private val packetHub = ObserverHub<ReceivedPacket, IRnsPacketCallback>(
        scope = scope,
        upstream = { impl.observePackets() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onPacket(value) },
    )
    private val linkHub = ObserverHub<LinkEvent, IRnsLinkEventCallback>(
        scope = scope,
        upstream = { impl.observeLinks() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onLinkEvent(value) },
    )
    private val announceHub = ObserverHub<AnnounceEvent, IRnsAnnounceCallback>(
        scope = scope,
        upstream = { impl.observeAnnounces() },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onAnnounce(value) },
    )

    override fun initialize(config: ReticulumConfig, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.initialize(config).bundleOrThrow() }

    override fun shutdown(cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.shutdown().bundleOrThrow() }

    override fun getCurrentNetworkStatus(cb: IRnsNetworkStatusCallback) {
        // Snapshot fire-once shape — read the current StateFlow value and
        // deliver it without driving the observer machinery. The callback has
        // no error path, so RemoteException is the only failure to swallow.
        try { cb.onStatus(impl.networkStatus.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerNetworkStatusObserver(cb: IRnsNetworkStatusCallback) =
        networkStatusHub.registerObserver(cb)
    override fun unregisterNetworkStatusObserver(cb: IRnsNetworkStatusCallback) =
        networkStatusHub.unregisterObserver(cb)

    override fun createIdentity(cb: IRnsResultCallback) = dispatch(cb, scope) {
        val identity = impl.createIdentity().getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.IDENTITY, identity) }
    }

    override fun loadIdentity(path: String, cb: IRnsResultCallback) = dispatch(cb, scope) {
        val identity = impl.loadIdentity(path).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.IDENTITY, identity) }
    }

    override fun saveIdentity(identity: Identity, path: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.saveIdentity(identity, path).bundleOrThrow() }

    override fun recallIdentity(hash: ByteArray, cb: IRnsResultCallback) = dispatch(cb, scope) {
        val identity = impl.recallIdentity(hash)
        Bundle().apply { if (identity != null) putParcelable(BundleKeys.IDENTITY, identity) }
    }

    override fun createIdentityWithName(displayName: String, cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.createIdentityWithName(displayName).toIdentityKeyBundle()
    }

    override fun importIdentityFile(fileData: ByteArray, displayName: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) {
            impl.importIdentityFile(fileData, displayName).toIdentityKeyBundle()
        }

    override fun exportIdentityFile(keyData: ByteArray, filePath: String, cb: IRnsByteArrayCallback) =
        dispatchNullableByteArray(cb, scope) { impl.exportIdentityFile(keyData, filePath) }

    override fun getFullIdentityKey(cb: IRnsByteArrayCallback) = dispatchNullableByteArray(cb, scope) {
        impl.getFullIdentityKey()
    }

    override fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: MutableList<String>,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val destination = impl.createDestination(identity, direction, type, appName, aspects).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.DESTINATION, destination) }
    }

    override fun announceDestination(destination: Destination, appData: ByteArray?, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.announceDestination(destination, appData).bundleOrThrow() }

    override fun triggerAutoAnnounce(displayName: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.triggerAutoAnnounce(displayName).bundleOrThrow() }

    override fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendPacket(destination, data, packetType).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun registerPacketObserver(cb: IRnsPacketCallback) = packetHub.registerObserver(cb)
    override fun unregisterPacketObserver(cb: IRnsPacketCallback) = packetHub.unregisterObserver(cb)

    override fun establishLink(destination: Destination, cb: IRnsResultCallback) = dispatch(cb, scope) {
        val link = impl.establishLink(destination).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.LINK, link) }
    }

    override fun closeLink(link: Link, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.closeLink(link).bundleOrThrow() }

    override fun sendOverLink(link: Link, data: ByteArray, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.sendOverLink(link, data).bundleOrThrow() }

    override fun registerLinkObserver(cb: IRnsLinkEventCallback) = linkHub.registerObserver(cb)
    override fun unregisterLinkObserver(cb: IRnsLinkEventCallback) = linkHub.unregisterObserver(cb)

    override fun hasPath(destinationHash: ByteArray, cb: IRnsBoolCallback) = dispatchBool(cb, scope) {
        impl.hasPath(destinationHash)
    }

    override fun requestPath(destinationHash: ByteArray, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.requestPath(destinationHash).bundleOrThrow() }

    override fun persistTransportData(cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.persistTransportData(); Bundle.EMPTY }

    override fun getHopCount(destinationHash: ByteArray, cb: IRnsIntCallback) = dispatchNullableInt(cb, scope) {
        impl.getHopCount(destinationHash)
    }

    override fun getNextHopInterfaceName(destinationHash: ByteArray, cb: IRnsStringCallback) =
        dispatchNullableString(cb, scope) { impl.getNextHopInterfaceName(destinationHash) }

    override fun getPathTableHashes(cb: IRnsStringListCallback) = dispatchStringList(cb, scope) {
        impl.getPathTableHashes()
    }

    override fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val probe = impl.probeLinkSpeed(destinationHash, timeoutSeconds, deliveryMethod)
        Bundle().apply { putParcelable(BundleKeys.PROBE, probe) }
    }

    override fun isTransportEnabled(cb: IRnsBoolCallback) = dispatchBool(cb, scope) {
        impl.isTransportEnabled()
    }

    override fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val result = impl.establishConversationLink(destinationHash, timeoutSeconds).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.LINK_RESULT, result) }
    }

    override fun closeConversationLink(destinationHash: ByteArray, cb: IRnsBoolCallback) =
        dispatchBool(cb, scope) {
            impl.closeConversationLink(destinationHash).getOrThrow()
        }

    override fun getConversationLinkStatus(destinationHash: ByteArray, cb: IRnsResultCallback) =
        dispatch(cb, scope) {
            val result = impl.getConversationLinkStatus(destinationHash)
            Bundle().apply { putParcelable(BundleKeys.LINK_RESULT, result) }
        }

    override fun registerAnnounceObserver(cb: IRnsAnnounceCallback) = announceHub.registerObserver(cb)
    override fun unregisterAnnounceObserver(cb: IRnsAnnounceCallback) = announceHub.unregisterObserver(cb)

    override fun restorePeerIdentities(entries: MutableList<PeerIdentityEntry>, cb: IRnsResultCallback) =
        dispatch(cb, scope) {
            val count = impl.restorePeerIdentities(entries.toPeerIdentityPairs()).getOrThrow()
            Bundle().apply { putInt(BundleKeys.COUNT, count) }
        }

    override fun restoreAnnounceIdentities(entries: MutableList<AnnounceRestoreEntry>, cb: IRnsResultCallback) =
        dispatch(cb, scope) {
            val count = impl.restoreAnnounceIdentities(entries.toAnnounceRestorePairs()).getOrThrow()
            Bundle().apply { putInt(BundleKeys.COUNT, count) }
        }

    override fun blockDestination(destinationHashHex: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.blockDestination(destinationHashHex).bundleOrThrow() }
    override fun unblockDestination(destinationHashHex: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.unblockDestination(destinationHashHex).bundleOrThrow() }
    override fun blackholeIdentity(identityHashHex: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.blackholeIdentity(identityHashHex).bundleOrThrow() }
    override fun unblackholeIdentity(identityHashHex: String, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.unblackholeIdentity(identityHashHex).bundleOrThrow() }
}

private fun Map<String, Any>.toIdentityKeyBundle(): Bundle {
    val bundle = Bundle()
    // Marshal the full create/import result map across AIDL. The set here
    // mirrors what NativeRnsBackendImpl.buildIdentityResult + PythonRnsCore.
    // buildIdentityResult produce, and is the same set IdentityManagerViewModel
    // reads on the other side. Pre-fix, only KEY_DATA / DISPLAY_NAME /
    // (the wrongly-named) IDENTITY_HASH_HEX were carried — VM threw
    // "No identity_hash in result" because (a) the bundle didn't carry
    // identity_hash at all, (b) it didn't carry destination_hash or
    // file_path either.
    (this[BundleKeys.KEY_DATA] as? ByteArray)?.let { bundle.putByteArray(BundleKeys.KEY_DATA, it) }
    (this[BundleKeys.DISPLAY_NAME] as? String)?.let { bundle.putString(BundleKeys.DISPLAY_NAME, it) }
    (this[BundleKeys.IDENTITY_HASH] as? String)?.let { bundle.putString(BundleKeys.IDENTITY_HASH, it) }
    (this[BundleKeys.DESTINATION_HASH] as? String)?.let { bundle.putString(BundleKeys.DESTINATION_HASH, it) }
    (this[BundleKeys.FILE_PATH] as? String)?.let { bundle.putString(BundleKeys.FILE_PATH, it) }
    (this[BundleKeys.PUBLIC_KEY] as? ByteArray)?.let { bundle.putByteArray(BundleKeys.PUBLIC_KEY, it) }
    return bundle
}
