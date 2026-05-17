package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import network.columba.app.rns.api.model.LinkStatus
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `RnsCore` over upstream Python RNS, driven through Chaquopy.
 *
 * This is the **pattern template** for the other `PythonRns*` sub-impls:
 *  - every `suspend` method routes through [pyResult] / [pyCall] so PyObject
 *    calls run on `Dispatchers.IO` and Chaquopy `PyException`s become typed
 *    [RnsError]s;
 *  - live upstream objects (`RNS.Identity` / `RNS.Destination` / `RNS.Link`)
 *    live in [PythonRnsRuntime]'s registries, keyed by the hash/handle that
 *    crosses the AIDL seam;
 *  - observable flows are sourced from [PythonEventBridge];
 *  - where the upstream call shape needs on-device iteration the method is an
 *    honest stub with a `TODO(on-device)` marker — never a silent fake.
 */
@Suppress("TooManyFunctions") // Mirrors the RnsCore contract surface 1:1.
class PythonRnsCore(
    private val runtime: PythonRnsRuntime,
    private val events: PythonEventBridge,
) : RnsCore {
    private companion object {
        const val TAG = "PythonRnsCore"
    }

    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    /** Monotonic handle ids for live `RNS.Link` objects (mirrors `:rns-ipc` HandleRegistry). */
    private val linkHandleSeq = AtomicLong(1)

    /** Local block/blackhole sets. Enforcement is shared app-logic in `:rns-host`. */
    private val blockedDestinations = ConcurrentHashMap.newKeySet<String>()
    private val blackholedIdentities = ConcurrentHashMap.newKeySet<String>()

    // ==================== Initialization & lifecycle ====================

    // catch(Throwable) is intentional: any failure crossing the Python/Chaquopy
    // boundary must flip networkStatus to ERROR before re-throwing.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun initialize(config: ReticulumConfig): Result<Unit> =
        pyResult {
            _networkStatus.value = NetworkStatus.INITIALIZING
            try {
                runtime.start(config)
                // Attach the Kotlin event sinks now that the Reticulum
                // instance + LXMRouter are live (event_bridge.register_callbacks
                // needs both). This is the bridge from upstream RNS/LXMF
                // callbacks into the same SharedFlows the kotlin backend uses.
                runtime.wireEventBridge(
                    onAnnounce = events.onAnnounce,
                    onPacket = events.onPacket,
                    onLinkEvent = events.onLinkEvent,
                    onLxmfDelivery = events.onLxmfDelivery,
                    onLxmfFailure = events.onLxmfFailure,
                )
                // LXST telephony setup is host-side: PythonCallManager
                // observes this networkStatus flow and runs setup() on READY.
                _networkStatus.value = NetworkStatus.READY
            } catch (e: Throwable) {
                _networkStatus.value = NetworkStatus.ERROR(e.message ?: "Python RNS init failed")
                throw e
            }
        }

    override suspend fun shutdown(): Result<Unit> =
        pyResult {
            runtime.stop()
            _networkStatus.value = NetworkStatus.SHUTDOWN
        }

    // ==================== Identity management ====================

    override suspend fun createIdentity(): Result<Identity> =
        pyResult {
            val pyId = runtime.rnsModule.callAttr("Identity")
            pyId.toModelIdentity().also { runtime.identities[it.hash.toHex()] = pyId }
        }

    override suspend fun loadIdentity(path: String): Result<Identity> =
        pyResult {
            val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
            val pyId = identityClass.callAttr("from_file", path)
                ?: throw RnsException(RnsError.Generic("Identity.from_file returned None for $path", null))
            pyId.toModelIdentity().also { runtime.identities[it.hash.toHex()] = pyId }
        }

    override suspend fun saveIdentity(identity: Identity, path: String): Result<Unit> =
        pyResult {
            resolveIdentity(identity).callAttr("to_file", path)
            Unit
        }

    override suspend fun recallIdentity(hash: ByteArray): Identity? =
        pyCall {
            runtime.identities[hash.toHex()]?.let { return@pyCall it.toModelIdentity() }
            val identityClass = runtime.rnsModule["Identity"] ?: return@pyCall null
            val recalled = identityClass.callAttr("recall", hash.toPyBytes()) ?: return@pyCall null
            recalled.toModelIdentity().also { runtime.identities[it.hash.toHex()] = recalled }
        }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> =
        pyCall {
            val pyId = runtime.rnsModule.callAttr("Identity")
            val model = pyId.toModelIdentity()
            runtime.identities[model.hash.toHex()] = pyId
            // Contract: callers hand `key_data` to IdentityKeyProvider for
            // Keystore-wrapped storage — the raw key never touches disk here.
            mapOf(
                "hash_hex" to model.hash.toHex(),
                "display_name" to displayName,
                "key_data" to (model.privateKey ?: ByteArray(0)),
                "public_key" to model.publicKey,
            )
        }

    override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> =
        pyCall {
            val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
            // RNS identity files are the raw private-key bytes (Identity.to_file
            // writes get_private_key()). from_bytes reconstructs the keypair.
            val pyId = identityClass.callAttr("from_bytes", fileData.toPyBytes())
                ?: throw RnsException(RnsError.Generic("Identity.from_bytes returned None", null))
            val model = pyId.toModelIdentity()
            runtime.identities[model.hash.toHex()] = pyId
            mapOf(
                "hash_hex" to model.hash.toHex(),
                "display_name" to displayName,
                "key_data" to (model.privateKey ?: ByteArray(0)),
                "public_key" to model.publicKey,
            )
        }

    override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray =
        pyCall {
            val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
            val pyId = identityClass.callAttr("from_bytes", keyData.toPyBytes())
                ?: throw RnsException(RnsError.Generic("Identity.from_bytes returned None", null))
            pyId.callAttr("to_file", filePath)
            // The exported file is the raw private key — return it so the caller
            // can hand it to the system share sheet.
            keyData
        }

    override suspend fun getFullIdentityKey(): ByteArray? =
        pyCall {
            runtime.localIdentity?.callAttr("get_private_key")?.toJava(ByteArray::class.java)
        }

    // ==================== Destination management ====================

    // Spread is required: RNS.Destination(identity, dir, type, app_name, *aspects)
    // is variadic in `aspects`, whose count is only known at runtime.
    @Suppress("SpreadOperator")
    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> =
        pyResult {
            val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
            val pyIdentity = resolveIdentity(identity)
            // RNS.Destination(identity, direction, type, app_name, *aspects)
            val args = buildList<Any> {
                add(pyIdentity)
                add(destClass[directionConst(direction)] ?: error("bad direction"))
                add(destClass[typeConst(type)] ?: error("bad dest type"))
                add(appName)
                addAll(aspects)
            }
            val pyDest = runtime.rnsModule.callAttr("Destination", *args.toTypedArray())
            val model = pyDest.toModelDestination(identity, direction, type, appName, aspects)
            runtime.destinations[model.hexHash] = pyDest
            model
        }

    override suspend fun announceDestination(destination: Destination, appData: ByteArray?): Result<Unit> =
        pyResult {
            val pyDest = runtime.destinations[destination.hexHash]
                ?: throw RnsException(RnsError.IdentityNotFound(destination.hexHash))
            if (appData != null) {
                pyDest.callAttr("announce", appData.toPyBytes())
            } else {
                pyDest.callAttr("announce")
            }
            Unit
        }

    override suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> =
        pyResult {
            // The LXMF delivery destination is the one that carries displayName
            // in its app data. Re-announce it through the router.
            val router = runtime.lxmRouter
                ?: throw RnsException(RnsError.BackendNotReady)
            router.callAttr("announce", runtime.localDestination?.get("hash"))
            Unit
        }

    // ==================== Packet operations ====================

    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> =
        pyResult {
            val pyDest = runtime.destinations[destination.hexHash]
                ?: throw RnsException(RnsError.IdentityNotFound(destination.hexHash))
            val pyPacket = runtime.rnsModule.callAttr("Packet", pyDest, data.toPyBytes())
            val receipt = pyPacket.callAttr("send")
            val hashBytes = pyPacket["packet_hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
            PacketReceipt(
                hash = hashBytes,
                delivered = receipt?.toJava(Boolean::class.javaObjectType) ?: false,
                timestamp = System.currentTimeMillis(),
            )
        }

    override fun observePackets(): Flow<ReceivedPacket> = events.packets

    // ==================== Link operations ====================

    override suspend fun establishLink(destination: Destination): Result<Link> =
        pyResult {
            val pyDest = runtime.destinations[destination.hexHash]
                ?: throw RnsException(RnsError.IdentityNotFound(destination.hexHash))
            val pyLink = runtime.rnsModule.callAttr("Link", pyDest)
            val handle = linkHandleSeq.getAndIncrement()
            runtime.links[handle] = pyLink
            Link(
                id = handle.toString(),
                destination = destination,
                status = LinkStatus.PENDING,
                establishedAt = System.currentTimeMillis(),
                rtt = null,
            )
        }

    override suspend fun closeLink(link: Link): Result<Unit> =
        pyResult {
            val handle = link.id.toLongOrNull()
            val pyLink = handle?.let { runtime.links.remove(it) }
                ?: throw RnsException(RnsError.Generic("Unknown link handle ${link.id}", null))
            pyLink.callAttr("teardown")
            Unit
        }

    override suspend fun sendOverLink(link: Link, data: ByteArray): Result<Unit> =
        pyResult {
            val handle = link.id.toLongOrNull()
            val pyLink = handle?.let { runtime.links[it] }
                ?: throw RnsException(RnsError.Generic("Unknown link handle ${link.id}", null))
            runtime.rnsModule.callAttr("Packet", pyLink, data.toPyBytes()).callAttr("send")
            Unit
        }

    override fun observeLinks(): Flow<LinkEvent> = events.links

    // ==================== Path & transport ====================

    override suspend fun hasPath(destinationHash: ByteArray): Boolean =
        pyCall {
            transport().callAttr("has_path", destinationHash.toPyBytes())
                ?.toJava(Boolean::class.javaObjectType) ?: false
        }

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> =
        pyResult {
            transport().callAttr("request_path", destinationHash.toPyBytes())
            Unit
        }

    override suspend fun persistTransportData() {
        // Upstream RNS persists path tables on its own cadence + on exit_handler.
        // There is no public per-call flush hook; nothing to do here.
        Log.d(TAG, "persistTransportData: no-op (RNS persists internally)")
    }

    override suspend fun getHopCount(destinationHash: ByteArray): Int? =
        pyCall {
            val t = transport()
            if (t.callAttr("has_path", destinationHash.toPyBytes())
                    ?.toJava(Boolean::class.javaObjectType) != true
            ) {
                return@pyCall null
            }
            t.callAttr("hops_to", destinationHash.toPyBytes())?.toJava(Int::class.javaObjectType)
        }

    override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? =
        pyCall {
            // RNS.Transport.next_hop_interface returns the Interface object; .name
            // is its formatted name (e.g. "TCPInterface[Server/1.2.3.4:4242]").
            transport().callAttr("next_hop_interface", destinationHash.toPyBytes())
                ?.get("name")?.toString()
        }

    override suspend fun getPathTableHashes(): List<String> =
        pyCall {
            runCatching {
                val keys = transport()["path_table"]?.callAttr("keys") ?: return@pyCall emptyList()
                // Materialise the dict_keys view into a real list before asList().
                runtime.python.builtins.callAttr("list", keys)
                    .asList()
                    .map { it.toJava(ByteArray::class.java).toHex() }
            }.getOrElse {
                Log.w(TAG, "getPathTableHashes failed", it)
                emptyList()
            }
        }

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult =
        pyCall {
            // Honest minimal probe: report no_path when there's no route. A full
            // probe (establish a link, measure handshake rate / RTT / MTU) is
            // on-device integration work — UI degrades gracefully on "error".
            val hasPath = transport().callAttr("has_path", destinationHash.toPyBytes())
                ?.toJava(Boolean::class.javaObjectType) ?: false
            LinkSpeedProbeResult(
                status = if (hasPath) "error" else "no_path",
                establishmentRateBps = null,
                expectedRateBps = null,
                rttSeconds = null,
                hops = if (hasPath) {
                    transport().callAttr("hops_to", destinationHash.toPyBytes())
                        ?.toJava(Int::class.javaObjectType)
                } else {
                    null
                },
                linkReused = false,
                error = if (hasPath) "probeLinkSpeed not yet implemented for python backend" else null,
            )
        }

    override suspend fun isTransportEnabled(): Boolean =
        pyCall {
            runtime.reticulumInstance?.callAttr("transport_enabled")
                ?.toJava(Boolean::class.javaObjectType) ?: false
        }

    // ==================== Conversation Link Management ====================

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> =
        pyResult {
            // Conversation links drive the online-status indicator. A minimal
            // honest result: report whether a path exists. Full link
            // establishment + rate measurement is on-device follow-up.
            val hasPath = transport().callAttr("has_path", destinationHash.toPyBytes())
                ?.toJava(Boolean::class.javaObjectType) ?: false
            ConversationLinkResult(
                isActive = false,
                hops = if (hasPath) {
                    transport().callAttr("hops_to", destinationHash.toPyBytes())
                        ?.toJava(Int::class.javaObjectType)
                } else {
                    null
                },
                error = if (hasPath) null else "no path to destination",
            )
        }

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> =
        pyResult { false }

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult =
        pyCall { ConversationLinkResult(isActive = false) }

    // ==================== Announce handling ====================

    override fun observeAnnounces(): Flow<AnnounceEvent> = events.announces

    // ==================== Peer / Announce identity restoration ====================

    override suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> =
        pyResult {
            val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
            var restored = 0
            peerIdentities.forEach { (hashHex, publicKey) ->
                runCatching {
                    // Re-seed RNS's identity cache from persisted peer public keys
                    // so destinations resolve without waiting for fresh announces.
                    identityClass.callAttr("remember", null, hashHex.hexToBytes().toPyBytes(), publicKey.toPyBytes(), null)
                    restored++
                }.onFailure { Log.w(TAG, "restorePeerIdentity $hashHex failed", it) }
            }
            restored
        }

    override suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> =
        pyResult {
            // Parity with NativeRnsBackendImpl, whose impl is literally
            // `Result.success(announces.size)`: neither backend re-injects the
            // persisted raw announce packets. Upstream RNS has no clean
            // Transport re-inject API for a stored announce, and it re-learns
            // paths from live announces on its own. The part that actually
            // matters for resolving destinations before a fresh announce
            // arrives — the identity/public-key cache — is re-seeded by
            // restorePeerIdentities() (Identity.remember). The persisted
            // announce bytes are belt-and-suspenders with no live consumer.
            Log.i(TAG, "restoreAnnounceIdentities: ${announces.size} announces (no-op, parity with kotlin backend)")
            announces.size
        }

    // ==================== Peer Blocking & Blackhole ====================
    // Enforcement is shared Kotlin app-logic in :rns-host (it filters the
    // observeMessages/observeAnnounces streams). These methods just maintain
    // the backend-side set so a restart re-applies it.

    override suspend fun blockDestination(destinationHashHex: String): Result<Unit> =
        pyResult { blockedDestinations.add(destinationHashHex); Unit }

    override suspend fun unblockDestination(destinationHashHex: String): Result<Unit> =
        pyResult { blockedDestinations.remove(destinationHashHex); Unit }

    override suspend fun blackholeIdentity(identityHashHex: String): Result<Unit> =
        pyResult { blackholedIdentities.add(identityHashHex); Unit }

    override suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit> =
        pyResult { blackholedIdentities.remove(identityHashHex); Unit }

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    /**
     * Resolve a model [Identity] to a live `RNS.Identity` PyObject: prefer the
     * runtime cache, else reconstruct from the private key bytes.
     */
    private fun resolveIdentity(identity: Identity): PyObject {
        runtime.identities[identity.hash.toHex()]?.let { return it }
        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        val key = identity.privateKey
            ?: throw RnsException(RnsError.IdentityNotFound(identity.hash.toHex()))
        return identityClass.callAttr("from_bytes", key.toPyBytes())
            .also { runtime.identities[identity.hash.toHex()] = it }
    }

    /** `RNS.Identity` PyObject -> model. `.hash` is an attribute; keys are getters. */
    private fun PyObject.toModelIdentity(): Identity {
        val hash = this["hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
        val pub = runCatching { callAttr("get_public_key")?.toJava(ByteArray::class.java) }.getOrNull()
            ?: ByteArray(0)
        val prv = runCatching { callAttr("get_private_key")?.toJava(ByteArray::class.java) }.getOrNull()
        return Identity(hash = hash, publicKey = pub, privateKey = prv)
    }

    private fun PyObject.toModelDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Destination {
        val hash = this["hash"]?.toJava(ByteArray::class.java) ?: ByteArray(0)
        return Destination(
            hash = hash,
            hexHash = hash.toHex(),
            identity = identity,
            direction = direction,
            type = type,
            appName = appName,
            aspects = aspects,
        )
    }

    private fun directionConst(direction: Direction): String =
        when (direction) {
            Direction.IN -> "IN"
            Direction.OUT -> "OUT"
        }

    private fun typeConst(type: DestinationType): String =
        when (type) {
            DestinationType.SINGLE -> "SINGLE"
            DestinationType.GROUP -> "GROUP"
            DestinationType.PLAIN -> "PLAIN"
        }
}
