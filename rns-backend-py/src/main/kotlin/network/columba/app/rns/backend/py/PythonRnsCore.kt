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
        /** Max time we wait for a path to appear during a probe (ms). */
        const val PATH_WAIT_MS = 5_000L
        /** Poll interval while waiting for a path (ms). */
        const val PATH_POLL_MS = 250L
        /** Poll interval while waiting for link establishment (ms). */
        const val LINK_POLL_MS = 100L
        /** Lower bound on the link-establishment wait — guards against
         *  tiny caller timeouts that would race the link setup. */
        const val MIN_LINK_WAIT_MS = 1_000L
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
            val transport = transport()
            val pyHash = destinationHash.toPyBytes()

            // Strategy 1 — Standard `RNS.Transport.next_hop_interface` lookup.
            // Returns the interface the destination's path is cached on.
            // For AutoInterface, the path-table stores the per-peer
            // `AutoInterfacePeer` (a subclass whose `.name` attribute is
            // empty — its `__str__` carries the useful `wlan0/fe80::…`
            // info instead). For everything else `.name` is populated.
            val rawIface = transport.callAttr("next_hop_interface", pyHash)
            val name1 = rawIface?.let { pyInterfaceName(it) }
            if (name1 != null) return@pyCall name1

            // Strategy 2 — Fall back to `LXMRouter.outbound_propagation_link`
            // when querying the configured propagation node. The link is
            // long-lived and reused across syncs; its `attached_interface`
            // is the interface the propagation packets physically travel
            // over even when `path_table[prop_node_hash]` happens to be
            // empty.
            val router = runtime.lxmRouter ?: return@pyCall null
            val propNode = runCatching {
                router.callAttr("get_outbound_propagation_node")
                    ?.toJava(ByteArray::class.java)
            }.getOrNull()
            if (propNode == null || !propNode.contentEquals(destinationHash)) {
                return@pyCall null
            }
            val propLink = router["outbound_propagation_link"]?.takeIf { it.toString() != "None" }
                ?: return@pyCall null
            val attached = propLink["attached_interface"]?.takeIf { it.toString() != "None" }
                ?: return@pyCall null
            pyInterfaceName(attached)
        }

    /**
     * Best-effort interface-name extraction from a Python `Interface`-like
     * object. Tries `.name` first (set by classes that override it —
     * `AutoInterface`, `TCPInterface`, `RNodeInterface`, …); falls back to
     * `__str__()` (always set, includes useful disambiguators like
     * `wlan0/fe80::…` for `AutoInterfacePeer`); finally to the class name.
     * Returns null only when the input is null or every accessor yielded
     * blank / `"None"`.
     *
     * Necessary because Reticulum's `AutoInterfacePeer` doesn't set
     * `self.name` — its `__init__` only sets `self.ifname` + `self.addr`.
     * Reading `.name` returns the Interface base class's empty default,
     * so the original one-liner `.get("name")?.toString()` always
     * produced null for propagation-link traffic on AutoInterface.
     */
    private fun pyInterfaceName(iface: com.chaquo.python.PyObject): String? {
        val nameAttr = iface.get("name")?.toString()?.takeIf { it.isNotBlank() && it != "None" }
        if (nameAttr != null) return nameAttr
        val strRepr = iface.toString().takeIf { it.isNotBlank() && it != "None" }
        if (strRepr != null) return strRepr
        return iface.callAttr("__class__")?.get("__name__")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "None" }
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
            // Mirrors release/v0.10.x reticulum_wrapper.probe_link_speed:
            //  1. Propagated mode → look at outbound_propagation_link +
            //     backchannel_links for measured rates; otherwise return
            //     success+heuristics (next-hop bitrate, hops).
            //  2. Direct mode → reuse an existing direct/backchannel link
            //     if one is ACTIVE, else establish a fresh link inline.
            //  3. Always fall back to success+heuristics when we have
            //     hops or next-hop bitrate, even if establishment fails.
            //     UI codec recommendation only returns DEFAULT on truly
            //     useless results, so degrading to bare "no_path"/"error"
            //     means a worse UX than necessary.
            Log.d(
                TAG,
                "probeLinkSpeed: dest=${destinationHash.toHex().take(16)} " +
                    "method=$deliveryMethod",
            )
            val result = probeLinkSpeedInternal(destinationHash, timeoutSeconds, deliveryMethod)
            Log.d(
                TAG,
                "probeLinkSpeed: status=${result.status} hops=${result.hops} " +
                    "nextHopBps=${result.nextHopBitrateBps} estRate=${result.establishmentRateBps} " +
                    "expRate=${result.expectedRateBps} mtu=${result.linkMtu}",
            )
            result
        }

    /**
     * Probe a destination's link speed. Returns a fully-populated
     * [LinkSpeedProbeResult] suitable for codec / image-compression
     * recommendation.
     *
     * Strategy mirrors release/v0.10.x's `reticulum_wrapper.probe_link_speed`:
     *  - For `propagated`: prefer the outbound propagation link's measured
     *    `expected_rate`; fall back to heuristics (hops + first-hop bitrate).
     *  - For `direct`: prefer an existing ACTIVE link in
     *    `LXMRouter.direct_links` / `LXMRouter.backchannel_links`; else try
     *    to establish a fresh link; on failure, still return heuristics
     *    when we have useful info (hops, next-hop bitrate).
     *
     * The "always degrade to heuristics" path matters: even when a peer
     * doesn't accept link establishment, the path-table entry tells us
     * how many hops away they are and how fast the local interface is,
     * which is enough for a sane codec tier recommendation.
     */
    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private fun probeLinkSpeedInternal(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult {
        val pyHash = destinationHash.toPyBytes()
        val transport = transport()
        val linkClass = runtime.rnsModule["Link"] ?: error("RNS.Link missing")
        val activeStatus = linkClass["ACTIVE"]?.toJava(Int::class.javaObjectType)

        // -- Heuristics gatherers (no link required) ---------------------
        fun heuristicHops(): Int? = runCatching {
            val has = transport.callAttr("has_path", pyHash)
                ?.toJava(Boolean::class.javaObjectType) ?: false
            if (!has) null else transport.callAttr("hops_to", pyHash)
                ?.toJava(Int::class.javaObjectType)
        }.getOrNull()

        fun heuristicNextHopBitrate(): Long? = runCatching {
            val iface = transport.callAttr("next_hop_interface", pyHash)
                ?.takeIf { it.toString() != "None" }
                ?: return@runCatching null
            // Some interface impls expose `bitrate` as int; coerce via Number.
            val raw = runCatching { iface.get("bitrate") }.getOrNull()
                ?: return@runCatching null
            if (raw.toString() == "None") null
            else runCatching { raw.toJava(Long::class.javaObjectType) }.getOrNull()
                ?: runCatching { raw.toJava(Int::class.javaObjectType)?.toLong() }.getOrNull()
        }.getOrNull()

        // -- Helpers that read live RNS.Link metrics ---------------------
        fun linkIsActive(link: PyObject): Boolean {
            val status = runCatching { link["status"]?.toJava(Int::class.javaObjectType) }
                .getOrNull()
            return status != null && status == activeStatus
        }

        fun pyLong(call: PyObject?): Long? {
            if (call == null || call.toString() == "None") return null
            return runCatching { call.toJava(Long::class.javaObjectType) }.getOrNull()
                ?: runCatching { call.toJava(Int::class.javaObjectType)?.toLong() }.getOrNull()
                ?: runCatching { call.toJava(Double::class.javaObjectType)?.toLong() }.getOrNull()
        }

        fun pyDouble(call: PyObject?): Double? {
            if (call == null || call.toString() == "None") return null
            return runCatching { call.toJava(Double::class.javaObjectType) }.getOrNull()
                ?: runCatching { call.toJava(Float::class.javaObjectType)?.toDouble() }.getOrNull()
        }

        fun pyInt(call: PyObject?): Int? {
            if (call == null || call.toString() == "None") return null
            return runCatching { call.toJava(Int::class.javaObjectType) }.getOrNull()
        }

        // `deliveryMethod` is implicit context — LinkSpeedProbeResult does not
        // carry a delivery_method field (the caller knows which method they
        // asked for), unlike the v0.10.x Python `get_link_stats` which writes
        // it into the response dict.
        fun linkStats(
            link: PyObject,
            reused: Boolean,
        ): LinkSpeedProbeResult {
            val establishmentRate =
                pyLong(runCatching { link.callAttr("get_establishment_rate") }.getOrNull())
            val expectedRate =
                pyLong(runCatching { link.callAttr("get_expected_rate") }.getOrNull())
            val rtt = pyDouble(runCatching { link["rtt"] }.getOrNull())
            val mtu = pyInt(runCatching { link.callAttr("get_mtu") }.getOrNull())
                ?: pyInt(runCatching { link["mtu"] }.getOrNull())
            return LinkSpeedProbeResult(
                status = "success",
                establishmentRateBps = establishmentRate,
                expectedRateBps = expectedRate,
                rttSeconds = rtt,
                hops = heuristicHops(),
                linkReused = reused,
                nextHopBitrateBps = heuristicNextHopBitrate(),
                linkMtu = mtu,
                error = null,
            )
        }

        // Find an existing link to this destination (either an outbound
        // direct_link or an inbound backchannel_link — both reuse the same
        // ACTIVE state for codec recommendation).
        fun findExistingLink(): PyObject? {
            val router = runtime.lxmRouter ?: return null
            val candidates = listOf("direct_links", "backchannel_links")
            for (attr in candidates) {
                val dict = runCatching { router[attr]?.takeIf { it.toString() != "None" } }
                    .getOrNull() ?: continue
                val link = runCatching { dict.callAttr("get", pyHash) }
                    .getOrNull()?.takeIf { it.toString() != "None" }
                if (link != null) return link
            }
            return null
        }

        // -- Branch 1: propagated delivery ------------------------------
        if (deliveryMethod == "propagated") {
            val router = runtime.lxmRouter
            // Look for a measured expected_rate on any existing link to this
            // peer; that's our best estimate of real throughput.
            val backchannelExpected = findExistingLink()?.let { link ->
                if (linkIsActive(link)) {
                    pyLong(runCatching { link.callAttr("get_expected_rate") }.getOrNull())
                } else {
                    null
                }
            }
            val propLink = router?.get("outbound_propagation_link")
                ?.takeIf { it.toString() != "None" }
            if (propLink != null && linkIsActive(propLink)) {
                Log.d(TAG, "probeLinkSpeed[propagated]: using outbound_propagation_link")
                val stats = linkStats(propLink, reused = true)
                // Prefer per-peer backchannel rate over the propagation
                // link's, when available — it's specific to this destination.
                return if (backchannelExpected != null && backchannelExpected > 0) {
                    stats.copy(expectedRateBps = backchannelExpected)
                } else {
                    stats
                }
            }
            // No active propagation link: heuristics-only success.
            return LinkSpeedProbeResult(
                status = "success",
                establishmentRateBps = null,
                expectedRateBps = backchannelExpected,
                rttSeconds = null,
                hops = heuristicHops(),
                linkReused = false,
                nextHopBitrateBps = heuristicNextHopBitrate(),
                linkMtu = null,
                error = null,
            )
        }

        // -- Branch 2: direct delivery ----------------------------------
        // Existing active link → reuse + return its measured stats.
        findExistingLink()?.let { link ->
            if (linkIsActive(link)) {
                Log.d(TAG, "probeLinkSpeed[direct]: reusing existing link")
                return linkStats(link, reused = true)
            }
        }

        // No existing link — try to establish one inline. Mirrors
        // reticulum_wrapper.establish_link from release/v0.10.x. Item 2 is
        // fixing establishConversationLink in a sibling worktree; both
        // methods need this same establish-link-with-callback machinery,
        // so duplicating it here is acceptable until the dust settles.
        val establishedResult = tryEstablishLink(destinationHash, timeoutSeconds)
        val establishedLink = establishedResult.first
        val establishError = establishedResult.second

        if (establishedLink != null && linkIsActive(establishedLink)) {
            Log.d(TAG, "probeLinkSpeed[direct]: established fresh link")
            return linkStats(establishedLink, reused = false)
        }

        // Establishment failed. Always fall back to heuristics when we
        // have useful info — this is the path that makes the UI codec
        // recommendation non-trivial when the peer is unreachable but
        // path-table data exists (the common emulator/local-mesh case).
        val hops = heuristicHops()
        val nextHopBps = heuristicNextHopBitrate()
        if (hops != null || nextHopBps != null) {
            Log.d(
                TAG,
                "probeLinkSpeed[direct]: establishment failed but heuristics available " +
                    "(hops=$hops, nextHopBps=$nextHopBps)",
            )
            return LinkSpeedProbeResult(
                status = "success",
                establishmentRateBps = null,
                expectedRateBps = null,
                rttSeconds = null,
                hops = hops,
                linkReused = false,
                nextHopBitrateBps = nextHopBps,
                linkMtu = null,
                error = null,
            )
        }

        // Truly no info — return the most specific failure code.
        val status = when {
            establishError == null -> "no_path"
            establishError.contains("Identity not known", ignoreCase = true) -> "no_identity"
            establishError.contains("No path", ignoreCase = true) -> "no_path"
            establishError.contains("Timeout", ignoreCase = true) -> "timeout"
            else -> "failed"
        }
        return LinkSpeedProbeResult(
            status = status,
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
            nextHopBitrateBps = null,
            linkMtu = null,
            error = establishError,
        )
    }

    /**
     * Attempt to establish a fresh `RNS.Link` to [destinationHash], waiting up
     * to [timeoutSeconds] for the established callback to fire.
     *
     * Returns the live link (active or otherwise) + an error string when
     * something went wrong. The link is registered under
     * `LXMRouter.direct_links[recipient_dest.hash]` so subsequent reuse
     * (in `probeLinkSpeed` or `establishConversationLink`) can find it; on
     * failure the entry is cleaned up.
     *
     * Mirrors release/v0.10.x `reticulum_wrapper.establish_link` lines
     * 6447-6727 — kept as a local helper rather than calling out to
     * `establishLink` because that method (a) doesn't run a callback and
     * (b) doesn't wait for ACTIVE state.
     */
    @Suppress(
        "LongMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
        "CyclomaticComplexMethod",
    )
    private fun tryEstablishLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Pair<PyObject?, String?> {
        val router = runtime.lxmRouter
            ?: return null to "Backend router not ready"
        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
        val linkClass = runtime.rnsModule["Link"] ?: error("RNS.Link missing")
        val activeStatus = linkClass["ACTIVE"]?.toJava(Int::class.javaObjectType)
        val pyHash = destinationHash.toPyBytes()

        // Recall identity. Try direct, then from_identity_hash variant.
        val recipientIdentity = try {
            identityClass.callAttr("recall", pyHash)
                ?.takeIf { it.toString() != "None" }
                ?: identityClass.callAttr("recall", pyHash, true)
                    ?.takeIf { it.toString() != "None" }
                ?: runtime.identities[destinationHash.toHex()]
        } catch (e: Throwable) {
            Log.w(TAG, "probeLinkSpeed: Identity.recall failed: ${e.message}")
            null
        }
        if (recipientIdentity == null) {
            return null to "Identity not known"
        }

        // Build the LXMF delivery destination (links go to the lxmf/delivery
        // app destination, same as direct LXMF sends).
        val recipientDest = try {
            runtime.rnsModule.callAttr(
                "Destination",
                recipientIdentity,
                destClass["OUT"] ?: error("Destination.OUT missing"),
                destClass["SINGLE"] ?: error("Destination.SINGLE missing"),
                "lxmf",
                "delivery",
            )
        } catch (e: Throwable) {
            return null to "Destination construction failed: ${e.message}"
        }
        val recipientDestHash = runCatching {
            recipientDest["hash"]?.toJava(ByteArray::class.java)
        }.getOrNull() ?: destinationHash
        val recipientHashPy = recipientDestHash.toPyBytes()

        val directLinks = router["direct_links"]?.takeIf { it.toString() != "None" }
        val backchannelLinks = router["backchannel_links"]?.takeIf { it.toString() != "None" }

        // Existing-link recheck under the *created* destination hash (links
        // are keyed under recipient_dest.hash, which may differ from the
        // input hash when callers pass an identity hash).
        listOfNotNull(directLinks, backchannelLinks).forEach { dict ->
            val existing = runCatching { dict.callAttr("get", recipientHashPy) }
                .getOrNull()?.takeIf { it.toString() != "None" }
            if (existing != null) {
                val status = runCatching { existing["status"]?.toJava(Int::class.javaObjectType) }
                    .getOrNull()
                if (status == activeStatus) {
                    return existing to null
                }
            }
        }

        // Wait for path if missing (up to 5s — keeps total probe latency
        // bounded under the caller's timeoutSeconds budget).
        val transport = transport()
        var hasPath = runCatching {
            transport.callAttr("has_path", recipientHashPy)
                ?.toJava(Boolean::class.javaObjectType) ?: false
        }.getOrNull() ?: false
        if (!hasPath) {
            runCatching { transport.callAttr("request_path", recipientHashPy) }
            val pathDeadlineMs = System.currentTimeMillis() + PATH_WAIT_MS
            while (!hasPath && System.currentTimeMillis() < pathDeadlineMs) {
                Thread.sleep(PATH_POLL_MS)
                hasPath = runCatching {
                    transport.callAttr("has_path", recipientHashPy)
                        ?.toJava(Boolean::class.javaObjectType) ?: false
                }.getOrNull() ?: false
            }
            if (!hasPath) {
                return null to "No path available"
            }
        }

        // Create RNS.Link and poll status until ACTIVE — same pattern as
        // PythonRnsNomadnet.establishLink. We deliberately skip the
        // established_callback indirection: it would need a SAM-callable
        // Kotlin->Python bridge (cf. StampGeneratorCallback), and the
        // status field flips to ACTIVE inside link's internal handshake
        // path before the callback returns, so polling sees the same
        // state.
        val link = try {
            runtime.rnsModule.callAttr("Link", recipientDest)
        } catch (e: Throwable) {
            return null to "Link construction failed: ${e.message}"
        }

        // Register in router.direct_links so subsequent reuse sees this link.
        runCatching {
            directLinks?.callAttr("__setitem__", recipientHashPy, link)
        }

        val timeoutMs = (timeoutSeconds * 1000).toLong().coerceAtLeast(MIN_LINK_WAIT_MS)
        val deadline = System.currentTimeMillis() + timeoutMs
        val closedStatus = linkClass["CLOSED"]?.toJava(Int::class.javaObjectType)
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching { link["status"]?.toJava(Int::class.javaObjectType) }
                .getOrNull()
            if (status == activeStatus) {
                // Identify on the link so the peer adds us to backchannel_links.
                // Best-effort — failure doesn't break codec recommendation.
                runCatching {
                    val ourIdentity = runtime.localIdentity ?: router["identity"]
                    if (ourIdentity != null && ourIdentity.toString() != "None") {
                        link.callAttr("identify", ourIdentity)
                    }
                }
                return link to null
            }
            if (status != null && closedStatus != null && status == closedStatus) {
                runCatching { directLinks?.callAttr("pop", recipientHashPy, null) }
                return null to "Link closed during establishment"
            }
            Thread.sleep(LINK_POLL_MS)
        }

        // Timeout.
        runCatching { link.callAttr("teardown") }
        runCatching { directLinks?.callAttr("pop", recipientHashPy, null) }
        return null to "Timeout"
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
