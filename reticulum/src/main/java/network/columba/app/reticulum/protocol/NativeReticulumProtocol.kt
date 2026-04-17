package network.columba.app.reticulum.protocol

import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.columba.app.reticulum.model.AnnounceEvent
import network.columba.app.reticulum.model.BatteryProfile
import network.columba.app.reticulum.model.DestinationType
import network.columba.app.reticulum.model.Direction
import network.columba.app.reticulum.model.LinkEvent
import network.columba.app.reticulum.model.LinkSpeedProbeResult
import network.columba.app.reticulum.model.LinkStatus
import network.columba.app.reticulum.model.NetworkStatus
import network.columba.app.reticulum.model.NodeType
import network.columba.app.reticulum.model.PacketReceipt
import network.columba.app.reticulum.model.PacketType
import network.columba.app.reticulum.model.ReceivedPacket
import network.columba.app.reticulum.model.ReticulumConfig
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import network.reticulum.transport.Transport
import org.json.JSONObject
import org.msgpack.core.MessagePack
import tech.torlando.columba.reticulum.BuildConfig
import network.columba.app.reticulum.model.Destination as ColumbaDestination
import network.columba.app.reticulum.model.Identity as ColumbaIdentity
import network.columba.app.reticulum.model.Link as ColumbaLink

/**
 * Native Kotlin implementation of [ReticulumProtocol] backed by reticulum-kt and lxmf-kt.
 *
 * Replaces the Python/Chaquopy-based [ServiceReticulumProtocol], eliminating the Python GIL
 * bottleneck that causes voice call latency, send spinner delays, and announce contention.
 *
 * Migration is phased:
 * - Phase 1: Read path (announces, message reception, path queries)
 * - Phase 2: Write path (message sending, propagation, identity management)
 * - Phase 3: Interfaces & transport initialization
 * - Phase 4: LXST telephony (voice calls)
 * - Phase 5: Remaining features (telemetry, NomadNet)
 */
class NativeReticulumProtocol(
    private val appContext: android.content.Context? = null,
) : ReticulumProtocol {
    companion object {
        private const val TAG = "NativeReticulumProtocol"
        private const val FIELD_COLUMBA_META = 0x70

        fun NativeIdentity.toColumba(): ColumbaIdentity =
            ColumbaIdentity(
                hash = this.hash,
                publicKey = this.getPublicKey(),
                privateKey = if (this.hasPrivateKey) this.sigPrv else null,
            )

        fun ColumbaIdentity.toNative(): NativeIdentity =
            if (privateKey != null) {
                NativeIdentity.fromPrivateKey(privateKey!!)
            } else {
                NativeIdentity.fromPublicKey(publicKey)
            }

        fun NativeDestination.toColumba(identity: ColumbaIdentity): ColumbaDestination =
            ColumbaDestination(
                hash = this.hash,
                hexHash = this.hexHash,
                identity = identity,
                direction = if (this.direction == DestinationDirection.IN) Direction.IN else Direction.OUT,
                type =
                    when (this.type) {
                        NativeDestinationType.SINGLE -> DestinationType.SINGLE
                        NativeDestinationType.GROUP -> DestinationType.GROUP
                        NativeDestinationType.PLAIN -> DestinationType.PLAIN
                        else -> DestinationType.SINGLE
                    },
                appName = this.appName,
                aspects = this.aspects,
            )

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun hexStringToByteArray(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }

        fun createNativeDestination(
            identity: NativeIdentity,
            direction: DestinationDirection,
            type: NativeDestinationType,
            appName: String,
            aspects: List<String>,
        ): NativeDestination =
            when (aspects.size) {
                0 -> NativeDestination.create(identity, direction, type, appName)
                1 -> NativeDestination.create(identity, direction, type, appName, aspects[0])
                2 -> NativeDestination.create(identity, direction, type, appName, aspects[0], aspects[1])
                else -> NativeDestination.create(identity, direction, type, appName, aspects[0], aspects[1], aspects[2])
            }

        fun resolveNodeType(aspect: String): NodeType =
            when (aspect) {
                "lxmf.propagation" -> NodeType.PROPAGATION_NODE
                "nomadnetwork.node" -> NodeType.NODE
                "lxst.telephony" -> NodeType.PHONE
                else -> NodeType.PEER
            }

        fun parseStampMeta(
            aspect: String,
            appData: ByteArray?,
        ): Triple<Int?, Int?, Int?> =
            if (aspect == "lxmf.propagation") {
                appData?.let { AppDataParser.parsePropagationStampMeta(it) } ?: Triple(null, null, null)
            } else {
                Triple(appData?.let { AppDataParser.parsePeerStampCost(it) }, null, null)
            }

        fun buildPeerAnnounceAppData(displayName: String): ByteArray {
            val packer = MessagePack.newDefaultBufferPacker()
            val nameBytes = displayName.toByteArray(Charsets.UTF_8)
            packer.packArrayHeader(2)
            packer.packBinaryHeader(nameBytes.size)
            packer.writePayload(nameBytes)
            packer.packNil()
            return packer.toByteArray()
        }

        fun network.reticulum.link.Link.toColumbaLink(destHash: ByteArray): ColumbaLink {
            val identity =
                this.destination?.identity?.toColumba()
                    ?: ColumbaIdentity(hash = destHash, publicKey = ByteArray(0), privateKey = null)
            return ColumbaLink(
                id = this.linkId.joinToString("") { "%02x".format(it) },
                destination =
                    ColumbaDestination(
                        hash = destHash,
                        hexHash = destHash.joinToString("") { "%02x".format(it) },
                        identity = identity,
                        direction = Direction.OUT,
                        type = DestinationType.SINGLE,
                        appName = "lxmf",
                        aspects = listOf("delivery"),
                    ),
                status =
                    when (this.status) {
                        network.reticulum.link.LinkConstants.ACTIVE -> LinkStatus.ACTIVE
                        network.reticulum.link.LinkConstants.CLOSED -> LinkStatus.CLOSED
                        else -> LinkStatus.PENDING
                    },
                establishedAt = System.currentTimeMillis(),
                rtt = this.rtt?.toFloat(),
            )
        }
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // When true, auto-connect ignores discovered interfaces that did not
    // advertise an IFAC network name. Set via setAutoconnectIfacOnly().
    @Volatile private var autoconnectIfacOnly: Boolean = false

    // Native reticulum-kt / lxmf-kt instances
    private var reticulum: Reticulum? = null
    private var router: LXMRouter? = null
    private var deliveryIdentity: NativeIdentity? = null
    private var deliveryDestination: NativeDestination? = null
    private var storagePath: String? = null
    private var lastConfig: ReticulumConfig? = null
    private var reticulumDatabase: network.reticulum.android.db.ReticulumDatabase? = null
    private var reticulumDbWriteExecutor: java.util.concurrent.ExecutorService? = null

    // Active link tracking (destination hex hash → native link)
    private val activeLinks = java.util.concurrent.ConcurrentHashMap<String, network.reticulum.link.Link>()

    // LXST telephony — transport + full call manager (GIL-free voice calls)
    private val callTransport =
        network.columba.app.reticulum.call.telephone
            .NativeNetworkTransport()

    /**
     * Wires [Telephone], [PacketRouter], [AudioDevice], and the `lxst.telephony` destination.
     * Null until [setupNativeTelephone] is called during [initialize].
     */
    private var callManager: network.columba.app.reticulum.call.telephone.NativeCallManager? = null

    // Blocked destinations and blackholed identities
    private val blockedDestinations =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val blackholedIdentities =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    // Propagation node tracking

    // Telemetry collector state
    @Volatile private var telemetryCollectorEnabled = false
    private val storedTelemetry = java.util.concurrent.ConcurrentHashMap<String, NativeTelemetryHandler.StoredTelemetryEntry>()

    @Volatile private var telemetryAllowedRequesters = emptySet<String>()

    @Volatile private var activePropagationNodeHash: ByteArray? = null

    // Flows for observation
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _announces = MutableSharedFlow<AnnounceEvent>(extraBufferCapacity = 64)
    private val _messages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusUpdate>(extraBufferCapacity = 64)
    private val _locationTelemetryFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _reactionReceivedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 16)
    private val _links = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)
    private val _debugInfoFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    private val _interfaceStatusFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    private val _interfaceStatusChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    // Delegate handlers
    private val nomadNetHandler = NativeNomadNetHandler(appContext, { deliveryIdentity })

    private val messageSender by lazy {
        NativeMessageSender(
            routerProvider = { router },
            deliveryIdentityProvider = { deliveryIdentity },
            deliveryDestinationProvider = { deliveryDestination },
            deliveryStatusFlow = _deliveryStatus,
            scopeProvider = { scope },
        )
    }

    private val telemetryHandler by lazy {
        NativeTelemetryHandler(
            scopeProvider = { scope },
            locationTelemetryFlow = _locationTelemetryFlow,
            deliveryIdentityProvider = { deliveryIdentity },
            sendMessageFn = { destHash, content, method, extraFields ->
                messageSender.sendLxmfMessageWithMethod(
                    destinationHash = destHash,
                    content = content,
                    deliveryMethod = method,
                    options = NativeMessageSender.MessageOptions(extraFields = extraFields),
                )
            },
            storedTelemetry = storedTelemetry,
            telemetryCollectorEnabledProvider = { telemetryCollectorEnabled },
            telemetryAllowedRequestersProvider = { telemetryAllowedRequesters },
        )
    }

    private val interfaceFactoryListener: () -> Unit = {
        applyBatteryProfileInternal()
        emitInterfaceSnapshotsAsync()
    }

    @Volatile private var selectedBatteryProfile: BatteryProfile = BatteryProfile.BALANCED

    @Volatile private var dozeThrottleMultiplier = 1.0f

    @Volatile private var systemPowerSaveEnabled = false
    private var batteryMonitor: network.reticulum.android.BatteryMonitor? = null

    // ==================== Phase 1: Initialization ====================

    private fun initializeRouter(config: ReticulumConfig): NativeIdentity {
        val identity =
            when {
                // Preferred path: caller decrypted the key in memory (e.g. from an
                // Android Keystore-wrapped blob) and handed us raw bytes. Avoids
                // ever writing the plaintext key to disk.
                config.deliveryIdentityKey != null -> {
                    val loaded = NativeIdentity.fromBytes(config.deliveryIdentityKey!!)
                    if (loaded == null) {
                        // Malformed bytes, wrong length, etc. Silently falling through
                        // would start Reticulum with a brand-new ephemeral identity
                        // while Room still has the original marked active — messages
                        // would appear to come from an identity the user never saw.
                        // Log loudly so the mismatch is at least observable.
                        Log.e(TAG, "NativeIdentity.fromBytes returned null for deliveryIdentityKey - creating fresh identity instead")
                    }
                    loaded ?: NativeIdentity.create()
                }
                // Legacy path: load from a plaintext file on disk.
                config.identityFilePath != null -> {
                    val loaded = NativeIdentity.fromFile(config.identityFilePath!!)
                    if (loaded == null) {
                        Log.e(TAG, "NativeIdentity.fromFile returned null for ${config.identityFilePath} - creating fresh identity instead")
                    }
                    loaded ?: NativeIdentity.create()
                }
                // No identity provided — create a fresh one.
                else -> NativeIdentity.create()
            }
        deliveryIdentity = identity

        router =
            LXMRouter(
                identity = identity,
                storagePath = config.storagePath,
            )

        deliveryDestination =
            router!!.registerDeliveryIdentity(
                identity = identity,
                displayName = config.displayName,
            )

        router!!.registerDeliveryCallback { message ->
            handleIncomingMessage(message)
        }

        router!!.registerFailedDeliveryCallback { message ->
            val hash = message.hash?.joinToString("") { "%02x".format(it) } ?: return@registerFailedDeliveryCallback
            _deliveryStatus.tryEmit(DeliveryStatusUpdate(hash, "failed", System.currentTimeMillis()))
        }

        return identity
    }

    private fun initializePersistentStores(configDir: String) {
        if (reticulumDatabase != null) return

        val context = appContext?.applicationContext
        if (context == null) {
            Log.w(TAG, "No appContext — skipping Room persistent store setup")
            return
        }

        val db =
            Room
                .databaseBuilder(
                    context,
                    network.reticulum.android.db.ReticulumDatabase::class.java,
                    "reticulum.db",
                ).addMigrations(
                    network.reticulum.android.db.ReticulumDatabase.MIGRATION_1_2,
                ).build()

        val executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "NativeReticulumDB-write").apply { isDaemon = true }
            }

        reticulumDatabase = db
        reticulumDbWriteExecutor = executor

        Transport.pathStore =
            network.reticulum.android.db.store
                .RoomPathStore(db.pathDao(), executor)
        Transport.packetHashStore =
            network.reticulum.android.db.store
                .RoomPacketHashStore(db.packetHashDao(), executor)
        Transport.tunnelStore =
            network.reticulum.android.db.store
                .RoomTunnelStore(db.tunnelDao(), db.tunnelPathDao(), executor)
        Transport.announceStore =
            network.reticulum.android.db.store
                .RoomAnnounceStore(db.announceCacheDao(), executor)
        Transport.discoveryStore =
            network.reticulum.android.db.store
                .RoomDiscoveryStore(db.discoveredInterfaceDao(), executor)
        network.reticulum.identity.Identity.identityStore =
            network.reticulum.android.db.store.RoomIdentityStore(
                db.knownDestinationDao(),
                db.identityRatchetDao(),
                executor,
            )

        network.reticulum.android.db
            .FileMigrator(
                db,
                "$configDir/storage",
                "$configDir/cache",
            ).migrateIfNeeded()

        Log.i(TAG, "Native Reticulum Room stores initialized")
    }

    private fun closePersistentStores() {
        Transport.pathStore = null
        Transport.packetHashStore = null
        Transport.tunnelStore = null
        Transport.announceStore = null
        Transport.discoveryStore = null
        network.reticulum.identity.Identity.identityStore = null

        reticulumDbWriteExecutor?.let { executor ->
            executor.shutdown()
            try {
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        reticulumDbWriteExecutor = null

        reticulumDatabase?.close()
        reticulumDatabase = null
    }

    override suspend fun initialize(config: ReticulumConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                _networkStatus.value = NetworkStatus.INITIALIZING
                // Cancel any coroutines from a previous init cycle before replacing the scope.
                // Otherwise doze-state/battery-monitor observers and orphaned launches from a
                // (possibly failed-partway) prior initialize() stay alive and emit stale state
                // once the fresh initialize() installs a new scope.
                scope.cancel()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                storagePath = config.storagePath
                lastConfig = config
                selectedBatteryProfile = config.batteryProfile
                dozeThrottleMultiplier = 1.0f
                Log.i(TAG, "Initializing native Reticulum stack")

                initializePersistentStores(config.storagePath)

                // Start Reticulum with a fresh in-memory transport identity so the
                // node-level RPC private key never touches disk. Transport identity
                // continuity across app restarts isn't load-bearing on a phone client
                // (unlike a fixed-location relay where established paths matter).
                // Passing an override also triggers reticulum-kt to scrub and delete
                // any stale $storagePath/transport_identity left from a prior run.
                reticulum =
                    Reticulum.start(
                        configDir = config.storagePath,
                        enableTransport = config.enableTransport,
                        transportIdentity = NativeIdentity.create(),
                    )

                val identity = initializeRouter(config)
                // Key bytes are now inside NativeIdentity; drop them from the cached
                // config so each setBatteryProfile copy doesn't carry the key for the
                // session. Shortens the in-memory lifetime of the raw key material.
                lastConfig = lastConfig?.copy(deliveryIdentityKey = null)

                // Create and register network interfaces from config
                NativeInterfaceFactory.appContext = appContext
                NativeInterfaceFactory.scope = scope
                NativeInterfaceFactory.addListener(interfaceFactoryListener)
                NativeInterfaceFactory.syncInterfaces(config.enabledInterfaces)

                // Register announce handlers for all relevant aspects
                registerAnnounceHandlers()

                // Apply the IFAC-only autoconnect filter before starting the
                // discovery listener so the filter is active from the first
                // announce — otherwise a non-IFAC interface could race in and
                // grab the slot before the user-facing screen has a chance to
                // re-push the setting.
                autoconnectIfacOnly = config.autoconnectIfacOnly

                // Wire up interface discovery if configured
                if (config.discoverInterfaces) {
                    startDiscovery(config)
                }

                // Start the router processing loop
                router!!.start()

                // Forward any propagation node hash that was set before the router existed
                // (PropagationNodeManager starts early and sets the relay before initialize)
                activePropagationNodeHash?.let { hash ->
                    val hexHash = hash.joinToString("") { "%02x".format(it) }
                    val success = router!!.setActivePropagationNode(hexHash)
                    Log.d(TAG, "Forwarded saved propagation node to router: ${hexHash.take(16)} (success=$success)")
                }

                if (appContext != null) {
                    startBatteryMonitor()
                    startDozeObserver()
                }

                applyBatteryProfileInternal()
                emitInterfaceSnapshotsAsync()

                // Set up native telephony (requires Context for AudioDevice/PacketRouter)
                if (appContext != null) {
                    setupNativeTelephone(identity)
                } else {
                    Log.w(TAG, "No appContext — skipping Telephone setup (unit test mode)")
                }

                _networkStatus.value = NetworkStatus.READY
                Log.i(TAG, "Native Reticulum stack initialized")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to initialize native Reticulum", e)
                NativeInterfaceFactory.removeListener(interfaceFactoryListener)
                batteryMonitor?.stop()
                batteryMonitor = null
                systemPowerSaveEnabled = false
                // Tear down anything that may have been brought up before the
                // failure point: interfaces registered by NativeInterfaceFactory
                // and the Reticulum Transport itself. Without this, a retry
                // would call Reticulum.start() on an already-running instance
                // and inherit stale interface state.
                try {
                    NativeInterfaceFactory.shutdownAll()
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Error shutting down interfaces during init failure cleanup", cleanupError)
                }
                try {
                    Reticulum.stop()
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Error stopping Reticulum during init failure cleanup", cleanupError)
                }
                closePersistentStores()
                // Cancel the scope we created at the top of initialize() so any
                // coroutines launched before the failure don't leak across retries.
                scope.cancel()
                _networkStatus.value = NetworkStatus.ERROR(e.message ?: "Unknown error")
            }
        }

    override fun getStatus(): Result<String> =
        runCatching {
            when (_networkStatus.value) {
                is NetworkStatus.READY -> "READY"
                is NetworkStatus.INITIALIZING -> "INITIALIZING"
                is NetworkStatus.CONNECTING -> "CONNECTING"
                is NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                is NetworkStatus.ERROR -> "ERROR: ${(_networkStatus.value as NetworkStatus.ERROR).message}"
            }
        }

    override fun isInitialized(): Result<Boolean> = Result.success(_networkStatus.value is NetworkStatus.READY)

    override suspend fun shutdown(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.i(TAG, "Shutting down native Reticulum stack")
                _networkStatus.value = NetworkStatus.SHUTDOWN
                callManager?.shutdown()
                callManager = null
                router?.stop()
                dozeObserver?.stop()
                dozeObserver = null
                batteryMonitor?.stop()
                batteryMonitor = null
                systemPowerSaveEnabled = false
                NativeInterfaceFactory.removeListener(interfaceFactoryListener)
                NativeInterfaceFactory.shutdownAll()
                Reticulum.stop()
                closePersistentStores()
                router = null
                reticulum = null
                deliveryIdentity = null
                deliveryDestination = null
                // Drop the ReticulumConfig reference so the delivery-identity key bytes
                // it carries (and any copies via setBatteryProfile) become GC-eligible.
                // True in-memory zeroing isn't achievable on the JVM, but releasing the
                // strong reference matches the key lifecycle care elsewhere in this flow.
                lastConfig = null
                Transport.customJobIntervalMs = null
                Transport.customTablesCullIntervalMs = null
                Transport.customAnnouncesCheckIntervalMs = null
                scope.cancel()
            }
        }

    // ==================== Phase 1: Announce Handling ====================

    private fun registerAnnounceHandlers() {
        // Register known aspects so Transport can resolve them for us
        Transport.registerKnownAspect("lxmf.delivery")
        Transport.registerKnownAspect("lxmf.propagation")
        Transport.registerKnownAspect("nomadnetwork.node")
        Transport.registerKnownAspect("lxst.telephony")

        // null aspectFilter = receive all announces; Transport resolves the aspect for us
        Transport.registerAnnounceHandler(
            object : network.reticulum.transport.RichAnnounceHandler {
                override fun handleAnnounceWithContext(
                    destinationHash: ByteArray,
                    announcedIdentity: NativeIdentity,
                    appData: ByteArray?,
                    hops: Int,
                    receivingInterfaceName: String?,
                    matchedAspect: String?,
                ): Boolean {
                    if (matchedAspect == null) return false // unknown aspect — not an app we handle
                    handleAnnounce(matchedAspect, destinationHash, announcedIdentity, appData, hops, receivingInterfaceName)
                    return true
                }
            },
        )
    }

    /**
     * Start interface discovery (both announcer and listener).
     *
     * The announcer sends our interface discovery announces so other nodes can find us.
     * The listener registers an InterfaceAnnounceHandler that receives and persists
     * discoveries from other nodes, and optionally auto-connects TCP interfaces.
     */
    private fun startDiscovery(config: ReticulumConfig) {
        // Start the announcer (sends our discovery announces)
        Transport.enableDiscovery()

        // Parse authorized discovery sources (hex identity hashes → ByteArrayKey set)
        val discoverySources =
            config.interfaceDiscoverySources
                ?.map { network.reticulum.common.ByteArrayKey(hexStringToByteArray(it)) }
                ?.toSet()

        // Auto-connect factory: creates a TCPClientInterface from discovered
        // info. Always register the factory, even when autoconnect count is 0
        // at init time — InterfaceDiscovery.autoConnectFactory is immutable
        // after construction, so if we pass null here the user can't later turn
        // on autoconnect without restarting the service. `maxAutoConnected` is
        // mutable and gates the factory's callers.
        val autoConnectFactory: (network.reticulum.discovery.DiscoveredInterface) -> network.reticulum.transport.InterfaceRef? =
            { discovered -> createAutoconnectInterface(discovered) }

        // Start the listener (receives others' discovery announces)
        Transport.discoverInterfaces(
            requiredValue = config.requiredDiscoveryValue,
            discoverySources = discoverySources,
            autoConnectFactory = autoConnectFactory,
            maxAutoConnected = config.autoconnectDiscoveredInterfaces,
        )

        Log.i(TAG, "Interface discovery started (announce + listen, autoconnect=${config.autoconnectDiscoveredInterfaces})")
    }

    /**
     * Auto-connect factory for discovered TCP interfaces.
     * Creates a TCPClientInterface and registers it with Transport.
     */
    private fun createAutoconnectInterface(discovered: network.reticulum.discovery.DiscoveredInterface): network.reticulum.transport.InterfaceRef? {
        val host = discovered.reachableOn
        val port = discovered.port
        val ifacMissing = autoconnectIfacOnly && discovered.ifacNetname.isNullOrBlank()
        if (host == null || port == null || ifacMissing) {
            if (ifacMissing) {
                Log.i(
                    TAG,
                    "Skipping auto-connect for ${discovered.name} at $host:$port — IFAC-only mode and interface has no IFAC",
                )
            }
            return null
        }
        return try {
            val iface =
                network.reticulum.interfaces.tcp.TCPClientInterface(
                    name = "Discovered: ${discovered.name}",
                    targetHost = host,
                    targetPort = port,
                    ifacNetname = discovered.ifacNetname,
                    ifacNetkey = discovered.ifacNetkey,
                )
            iface.start()
            val ref =
                network.reticulum.interfaces.InterfaceAdapter
                    .getOrCreate(iface)
            Transport.registerInterface(ref)
            Log.i(TAG, "Auto-connected discovered interface: ${discovered.name} at $host:$port")
            ref
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-connect discovered ${discovered.name}: ${e.message}")
            null
        }
    }

    private fun handleAnnounce(
        aspect: String,
        destinationHash: ByteArray,
        announcedIdentity: NativeIdentity,
        appData: ByteArray?,
        announceHops: Int = 0,
        receivingInterfaceName: String? = null,
    ) {
        val destHex = destinationHash.joinToString("") { "%02x".format(it) }
        if (blockedDestinations.contains(destHex) || blackholedIdentities.contains(announcedIdentity.hexHash)) return

        val hops = if (announceHops > 0) announceHops else (Transport.hopsTo(destinationHash) ?: 0)
        val nodeType = resolveNodeType(aspect)
        val displayName = appData?.let { AppDataParser.parseDisplayName(it, aspect) }
        val stampMeta = Companion.parseStampMeta(aspect, appData)

        val event =
            AnnounceEvent(
                destinationHash = destinationHash,
                identity = announcedIdentity.toColumba(),
                appData = appData,
                hops = hops,
                timestamp = System.currentTimeMillis(),
                nodeType = nodeType,
                aspect = aspect,
                displayName = displayName,
                stampCost = stampMeta.first,
                stampCostFlexibility = stampMeta.second,
                peeringCost = stampMeta.third,
                receivingInterface = receivingInterfaceName,
            )

        _announces.tryEmit(event)
        emitInterfaceSnapshotsAsync()

        if (aspect == "lxmf.propagation" && appData != null) {
            router?.handlePropagationAnnounce(destinationHash, announcedIdentity, appData)
        }
    }

    override fun observeAnnounces(): Flow<AnnounceEvent> = _announces.asSharedFlow()

    override val debugInfoFlow = _debugInfoFlow.asSharedFlow()
    override val interfaceStatusFlow = _interfaceStatusFlow.asSharedFlow()
    override val interfaceStatusChanged = _interfaceStatusChanged.asSharedFlow()
    override val nomadnetRequestStatusFlow: StateFlow<String> = nomadNetHandler.requestStatusFlow
    override val nomadnetDownloadProgressFlow: StateFlow<Float> = nomadNetHandler.downloadProgressFlow

    // ==================== Phase 1: Message Reception ====================

    private fun handleIncomingMessage(message: LXMessage) {
        scope.launch {
            val sourceHash = message.sourceHash
            val destHash = message.destinationHash
            val content = message.content
            val fields = message.fields
            val timestamp =
                message.timestamp?.let { (it * 1000).toLong() }
                    ?: System.currentTimeMillis()

            // Serialize fields once up front so reaction detection can fall back to the
            // JSON form if msgpack unpacking yields an unexpected runtime type.
            val fieldsJson =
                if (fields.isNotEmpty()) {
                    AppDataParser.serializeFieldsToJson(fields)
                } else {
                    null
                }

            // Reactions are lightweight control messages in Field 16 and should not be
            // surfaced as regular chat bubbles. Mirror the old Python wrapper behavior:
            // emit a reaction event and skip normal message processing.
            val directReactionField = fields[16] as? Map<*, *>
            val jsonReactionField =
                fieldsJson
                    ?.let { runCatching { org.json.JSONObject(it).optJSONObject("16") }.getOrNull() }

            if (fields.containsKey(16)) {
                Log.d(
                    TAG,
                    "Incoming field16 type=${fields[16]?.javaClass?.name}, directKeys=${directReactionField?.keys}, jsonField16=$jsonReactionField",
                )
            }

            val reactionTo =
                (directReactionField?.get("reaction_to") as? String)
                    ?: jsonReactionField?.optString("reaction_to")?.takeIf { it.isNotBlank() }
            if (reactionTo != null) {
                val emoji =
                    (directReactionField?.get("emoji") as? String)
                        ?: jsonReactionField?.optString("emoji")
                        ?: ""
                val sender =
                    (directReactionField?.get("sender") as? String)
                        ?: jsonReactionField?.optString("sender")
                        ?: ""

                val reactionJson =
                    org.json
                        .JSONObject()
                        .put("reaction_to", reactionTo)
                        .put("emoji", emoji)
                        .put("sender", sender)
                        .put("source_hash", sourceHash.joinToString("") { "%02x".format(it) })
                        .put("timestamp", timestamp)
                        .toString()

                Log.d(TAG, "Reaction received for ${message.hash?.toHex()?.take(16) ?: "unknown"} -> $reactionJson")
                _reactionReceivedFlow.tryEmit(reactionJson)
                return@launch
            }

            val isLocationOnlyMessage =
                telemetryHandler.handleIncomingTelemetry(
                    message = message,
                    timestamp = timestamp,
                )

            // Extract icon appearance from fields
            val iconAppearance = telemetryHandler.extractIconAppearance(fields)

            // Check for telemetry commands before emitting to UI
            telemetryHandler.handleTelemetryCommands(message)

            if (isLocationOnlyMessage) {
                Log.d(TAG, "Telemetry-only LXMF message received, skipping chat emission")
                return@launch
            }

            val received =
                ReceivedMessage(
                    messageHash = message.hash?.let { it.joinToString("") { b -> "%02x".format(b) } } ?: "",
                    content = content,
                    sourceHash = sourceHash,
                    destinationHash = destHash,
                    timestamp = timestamp,
                    fieldsJson = fieldsJson,
                    publicKey = null, // Will be populated by recall if needed
                    iconAppearance = iconAppearance,
                )

            _messages.tryEmit(received)
        }
    }

    override val locationTelemetryFlow = _locationTelemetryFlow.asSharedFlow()

    override val reactionReceivedFlow = _reactionReceivedFlow.asSharedFlow()

    override fun observeMessages(): Flow<ReceivedMessage> = _messages.asSharedFlow()

    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = _deliveryStatus.asSharedFlow()

    // ==================== Phase 1: Path & Transport Queries ====================

    override suspend fun hasPath(destinationHash: ByteArray): Boolean = Transport.hasPath(destinationHash)

    override suspend fun requestPath(destinationHash: ByteArray): Result<Unit> =
        runCatching {
            Transport.requestPath(destinationHash)
            Unit
        }

    override fun getHopCount(destinationHash: ByteArray): Int? = Transport.hopsTo(destinationHash)

    override fun getNextHopInterfaceName(destinationHash: ByteArray): String? = Transport.nextHopInterface(destinationHash)?.name

    override suspend fun getPathTableHashes(): List<String> = Transport.pathTable.keys.map { it.toString() }

    override suspend fun persistTransportData() {
        // Transport persists automatically via its internal mechanisms
        NativeIdentity.saveKnownDestinations()
    }

    // ==================== Phase 1: Identity (Read-Only) ====================

    override suspend fun recallIdentity(hash: ByteArray): ColumbaIdentity? {
        val recalled = NativeIdentity.recall(hash) ?: NativeIdentity.recallByIdentityHash(hash)
        return recalled?.toColumba()
    }

    // ==================== Phase 2: Identity Management ====================

    override suspend fun createIdentity(): Result<ColumbaIdentity> =
        withContext(Dispatchers.IO) {
            runCatching {
                NativeIdentity.create().toColumba()
            }
        }

    override suspend fun loadIdentity(path: String): Result<ColumbaIdentity> =
        withContext(Dispatchers.IO) {
            runCatching {
                NativeIdentity.fromFile(path)?.toColumba()
                    ?: error("Failed to load identity from $path")
            }
        }

    override suspend fun saveIdentity(
        identity: ColumbaIdentity,
        path: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nativeIdentity = identity.toNative()
                nativeIdentity.toFile(path)
                Unit
            }
        }

    /**
     * Get the full 64-byte private key (X25519_prv + Ed25519_prv) for encrypted Room storage.
     * Returns null if identity not initialized or has no private key.
     */
    override fun getFullIdentityKey(): ByteArray? =
        try {
            deliveryIdentity?.getPrivateKey()
        } catch (_: Exception) {
            null
        }

    override suspend fun getLxmfIdentity(): Result<ColumbaIdentity> =
        runCatching {
            val identity = deliveryIdentity ?: error("Delivery identity not initialized")
            identity.toColumba()
        }

    override suspend fun getLxmfDestination(): Result<ColumbaDestination> =
        runCatching {
            val dest = deliveryDestination ?: error("Delivery destination not initialized")
            val identity =
                deliveryIdentity?.toColumba()
                    ?: ColumbaIdentity(hash = ByteArray(0), publicKey = ByteArray(0), privateKey = null)
            dest.toColumba(identity)
        }

    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val identity = NativeIdentity.create()
            val hexHash = identity.hexHash
            val path = "$storagePath/identities/$hexHash"
            java.io
                .File(path)
                .parentFile
                ?.mkdirs()
            identity.toFile(path)
            mapOf(
                "success" to true,
                "identity_hash" to hexHash,
                "display_name" to displayName,
                "public_key" to identity.getPublicKey(),
            )
        }

    override suspend fun deleteIdentityFile(identityHash: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val path = "$storagePath/identities/$identityHash"
            val deleted = java.io.File(path).delete()
            mapOf("success" to deleted)
        }

    override suspend fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val identity =
                NativeIdentity.fromBytes(fileData)
                    ?: return@withContext mapOf("success" to false, "error" to "Invalid identity data")
            val hexHash = identity.hexHash
            val path = "$storagePath/identities/$hexHash"
            java.io
                .File(path)
                .parentFile
                ?.mkdirs()
            identity.toFile(path)
            mapOf(
                "success" to true,
                "identity_hash" to hexHash,
                "display_name" to displayName,
                "public_key" to identity.getPublicKey(),
            )
        }

    override suspend fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val path = "$storagePath/identities/$identityHash"
            val file = java.io.File(path)
            if (!file.exists()) throw java.io.FileNotFoundException("Identity file not found: $identityHash")
            val data = file.readBytes()
            // Also write to the export path
            java.io.File(filePath).writeBytes(data)
            data
        }

    override suspend fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val identity =
                NativeIdentity.fromBytes(keyData)
                    ?: return@withContext mapOf("success" to false, "error" to "Invalid key data")
            identity.toFile(filePath)
            mapOf(
                "success" to true,
                "identity_hash" to identity.hexHash,
                "public_key" to identity.getPublicKey(),
            )
        }

    // ==================== Phase 2: Message Sending ====================

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: ColumbaIdentity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> =
        messageSender.sendLxmfMessageWithMethod(
            destinationHash = destinationHash,
            content = content,
            deliveryMethod = DeliveryMethod.DIRECT,
            options =
                NativeMessageSender.MessageOptions(
                    imageData = imageData,
                    imageFormat = imageFormat,
                    fileAttachments = fileAttachments,
                ),
        )

    override suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: ColumbaIdentity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
        replyToMessageId: String?,
        iconAppearance: IconAppearance?,
        extraFields: Map<Int, Any>?,
    ): Result<MessageReceipt> =
        messageSender.sendLxmfMessageWithMethod(
            destinationHash = destinationHash,
            content = content,
            deliveryMethod = deliveryMethod,
            options =
                NativeMessageSender.MessageOptions(
                    tryPropagationOnFail = tryPropagationOnFail,
                    imageData = imageData,
                    imageFormat = imageFormat,
                    fileAttachments = fileAttachments,
                    replyToMessageId = replyToMessageId,
                    iconAppearance = iconAppearance,
                    extraFields = extraFields,
                ),
        )

    // ==================== Phase 2: Propagation ====================

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> =
        runCatching {
            activePropagationNodeHash = destHash
            val hexHash = destHash?.joinToString("") { "%02x".format(it) }
            if (hexHash != null) {
                val success = router?.setActivePropagationNode(hexHash) ?: false
                Log.d(TAG, "Propagation node set: ${hexHash.take(16)} (success=$success)")
            } else {
                router?.clearActivePropagationNode()
                Log.d(TAG, "Propagation node cleared")
            }
        }

    override suspend fun getOutboundPropagationNode(): Result<String?> =
        runCatching {
            router?.getActivePropagationNode()?.destHash?.joinToString("") { "%02x".format(it) }
                ?: activePropagationNodeHash?.joinToString("") { "%02x".format(it) }
        }

    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> =
        try {
            val r = router ?: return Result.success(PropagationState.IDLE)
            val activeNode = r.getActivePropagationNode()
            val propNodeCount = r.getPropagationNodes().size
            Log.d(TAG, "Propagation sync: activeNode=${activeNode?.hexHash?.take(12)}, totalPropNodes=$propNodeCount")
            r.requestMessagesFromPropagationNode()
            val state = r.propagationTransferState
            Log.d(TAG, "Triggered propagation node sync (max=$maxMessages), lxmRouterState=$state")
            // Return the state we just set, not a re-read that might race with callbacks
            Result.success(
                PropagationState(
                    state = state.ordinal,
                    stateName = state.name.lowercase(),
                    progress = r.propagationTransferProgress.toFloat(),
                    messagesReceived = r.propagationTransferLastResult,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Propagation sync exception: ${e.message}", e)
            Result.success(PropagationState(state = 0, stateName = "failed", progress = 0f, messagesReceived = 0))
        }

    override suspend fun getPropagationState(): Result<PropagationState> =
        runCatching {
            val r = router ?: return@runCatching PropagationState.IDLE
            PropagationState(
                state = r.propagationTransferState.ordinal,
                stateName = r.propagationTransferState.name.lowercase(),
                progress = r.propagationTransferProgress.toFloat(),
                messagesReceived = r.propagationTransferLastResult,
            )
        }

    // ==================== Phase 2: Reactions & Telemetry Stubs ====================

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: ColumbaIdentity,
    ): Result<MessageReceipt> {
        val senderHash = sourceIdentity.hash.joinToString("") { "%02x".format(it) }

        return sendLxmfMessageWithMethod(
            destinationHash = destinationHash,
            content = "",
            sourceIdentity = sourceIdentity,
            deliveryMethod = DeliveryMethod.OPPORTUNISTIC,
            extraFields =
                mapOf(
                    16 to
                        mapOf(
                            "reaction_to" to targetMessageId,
                            "emoji" to emoji,
                            "sender" to senderHash,
                        ),
                ),
        )
    }

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: ColumbaIdentity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> {
        val locationData = JSONObject(locationJson)
        val fields = mutableMapOf<Int, Any>()

        if (locationData.optBoolean("cease", false)) {
            fields[FIELD_COLUMBA_META] =
                JSONObject()
                    .put("cease", true)
                    .put("ts", locationData.optLong("ts", System.currentTimeMillis()))
                    .toString()
        } else {
            fields[LXMFConstants.FIELD_TELEMETRY] = telemetryHandler.packLocationTelemetry(locationData)

            val meta = JSONObject()
            if (locationData.has("expires") && !locationData.isNull("expires")) {
                meta.put("expires", locationData.getLong("expires"))
            }
            if (locationData.optInt("approxRadius", 0) > 0) {
                meta.put("approxRadius", locationData.getInt("approxRadius"))
            }
            if (meta.length() > 0) {
                fields[FIELD_COLUMBA_META] = meta.toString()
            }
        }

        return sendLxmfMessageWithMethod(
            destinationHash = destinationHash,
            content = "",
            sourceIdentity = sourceIdentity,
            deliveryMethod = DeliveryMethod.DIRECT,
            iconAppearance = iconAppearance,
            extraFields = fields,
        )
    }

    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: ColumbaIdentity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> {
        val commands =
            listOf(
                mapOf(
                    0x01 to listOf(timebase, isCollectorRequest),
                ),
            )
        return sendLxmfMessageWithMethod(
            destinationHash = destinationHash,
            content = "",
            sourceIdentity = sourceIdentity,
            deliveryMethod = DeliveryMethod.DIRECT,
            extraFields =
                mapOf(
                    LXMFConstants.FIELD_COMMANDS to commands,
                ),
        )
    }

    override suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit> =
        runCatching {
            telemetryCollectorEnabled = enabled
            Log.d(TAG, "Telemetry collector mode: $enabled")
        }

    override suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
    ): Result<Unit> =
        runCatching {
            val locationData = JSONObject(locationJson)
            val ownHash = deliveryDestination?.hexHash ?: deliveryIdentity?.hexHash ?: "local"
            val appearanceField =
                iconAppearance?.let {
                    listOf(
                        it.iconName,
                        it.foregroundColor.hexToBytes(),
                        it.backgroundColor.hexToBytes(),
                    )
                }
            telemetryHandler.storeTelemetryForCollector(
                sourceHashHex = ownHash,
                packedTelemetry = telemetryHandler.packLocationTelemetry(locationData),
                timestampSeconds = locationData.optLong("ts", System.currentTimeMillis()) / 1000L,
                appearanceField = appearanceField,
            )
            Log.d(TAG, "Stored own telemetry ($ownHash)")
        }

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> =
        runCatching {
            telemetryAllowedRequesters = allowedHashes.toSet()
            Log.d(TAG, "Set telemetry allowed requesters: ${allowedHashes.size} hashes")
        }

    // ==================== Phase 3: Destination & Packet Operations ====================

    override suspend fun createDestination(
        identity: ColumbaIdentity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<ColumbaDestination> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nativeIdentity = identity.toNative()
                val nativeDir =
                    when (direction) {
                        Direction.IN -> DestinationDirection.IN
                        Direction.OUT -> DestinationDirection.OUT
                        else -> DestinationDirection.OUT
                    }
                val nativeType =
                    when (type) {
                        DestinationType.SINGLE -> NativeDestinationType.SINGLE
                        DestinationType.GROUP -> NativeDestinationType.GROUP
                        DestinationType.PLAIN -> NativeDestinationType.PLAIN
                        else -> NativeDestinationType.SINGLE
                    }
                val dest = createNativeDestination(nativeIdentity, nativeDir, nativeType, appName, aspects)
                dest.toColumba(identity)
            }
        }

    private fun announceLocalPeerDestinations(
        appData: ByteArray?,
        reason: String,
    ) {
        val deliveryDest =
            deliveryDestination
                ?: error("Delivery destination not initialized")

        deliveryDest.announce(appData)
        callManager?.announce(appData)

        Log.i(
            TAG,
            "Announced local peer destinations for $reason " +
                "(lxmf.delivery=${deliveryDest.hexHash.take(16)}, lxst.telephony=${callManager != null})",
        )
    }

    override suspend fun triggerAutoAnnounce(displayName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appData = Companion.buildPeerAnnounceAppData(displayName)
                announceLocalPeerDestinations(appData, "auto-announce '$displayName'")
                Unit
            }
        }

    override suspend fun announceDestination(
        destination: ColumbaDestination,
        appData: ByteArray?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                announceLocalPeerDestinations(appData, "announceDestination(${destination.hexHash.take(16)})")
                Unit
            }
        }

    override suspend fun sendPacket(
        destination: ColumbaDestination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "sendPacket: ${data.size} bytes to ${destination.hexHash.take(16)}")
                PacketReceipt(
                    hash = ByteArray(32),
                    delivered = false,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }

    override fun observePackets(): Flow<ReceivedPacket> = _packets.asSharedFlow()

    // ==================== Phase 3: Link Operations ====================

    override suspend fun establishLink(destination: ColumbaDestination): Result<ColumbaLink> =
        withContext(Dispatchers.IO) {
            establishConversationLink(destination.hash).map { linkResult ->
                ColumbaLink(
                    id = destination.hexHash,
                    destination = destination,
                    status = if (linkResult.isActive) LinkStatus.ACTIVE else LinkStatus.CLOSED,
                    establishedAt = System.currentTimeMillis(),
                    rtt = linkResult.rttSeconds?.toFloat(),
                )
            }
        }

    override suspend fun closeLink(link: ColumbaLink): Result<Unit> =
        runCatching {
            closeConversationLink(link.destination.hash)
            Unit
        }

    override suspend fun sendOverLink(
        link: ColumbaLink,
        data: ByteArray,
    ): Result<Unit> =
        runCatching {
            val nativeLink =
                activeLinks[link.destination.hexHash]
                    ?: error("No active link for ${link.destination.hexHash.take(16)}")
            nativeLink.send(data)
            Unit
        }

    override fun observeLinks(): Flow<LinkEvent> = _links.asSharedFlow()

    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hexHash = destinationHash.joinToString("") { "%02x".format(it) }

                // Check if link already exists
                activeLinks[hexHash]?.let { existing ->
                    if (existing.status == network.reticulum.link.LinkConstants.ACTIVE) {
                        return@runCatching ConversationLinkResult(
                            isActive = true,
                            rttSeconds = existing.rtt?.let { it / 1000.0 },
                            alreadyExisted = true,
                        )
                    }
                }

                // Recall identity and create destination
                val recipientIdentity =
                    NativeIdentity.recall(destinationHash)
                        ?: NativeIdentity.recallByIdentityHash(destinationHash)
                        ?: return@runCatching ConversationLinkResult(isActive = false, error = "Identity not known")

                val dest =
                    NativeDestination.create(
                        recipientIdentity,
                        network.reticulum.common.DestinationDirection.OUT,
                        NativeDestinationType.SINGLE,
                        LXMRouter.APP_NAME,
                        LXMRouter.DELIVERY_ASPECT,
                    )

                // Create link with callbacks
                val link =
                    network.reticulum.link.Link.create(
                        destination = dest,
                        establishedCallback = { l ->
                            activeLinks[hexHash] = l
                            _links.tryEmit(
                                LinkEvent.Established(l.toColumbaLink(destinationHash)),
                            )
                        },
                        closedCallback = { l ->
                            activeLinks.remove(hexHash)
                            _links.tryEmit(
                                LinkEvent.Closed(l.toColumbaLink(destinationHash), l.teardownReason.toString()),
                            )
                        },
                    )

                activeLinks[hexHash] = link

                // Wait for establishment with timeout
                val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
                while (link.status != network.reticulum.link.LinkConstants.ACTIVE &&
                    link.status != network.reticulum.link.LinkConstants.CLOSED &&
                    System.currentTimeMillis() < deadline
                ) {
                    kotlinx.coroutines.delay(100)
                }

                if (link.status == network.reticulum.link.LinkConstants.ACTIVE) {
                    ConversationLinkResult(
                        isActive = true,
                        rttSeconds = link.rtt?.let { it / 1000.0 },
                        hops = Transport.hopsTo(destinationHash),
                    )
                } else {
                    activeLinks.remove(hexHash)
                    ConversationLinkResult(isActive = false, error = "Link establishment timed out")
                }
            }
        }

    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> =
        runCatching {
            val hexHash = destinationHash.joinToString("") { "%02x".format(it) }
            val link = activeLinks.remove(hexHash) ?: return@runCatching false
            link.teardown()
            true
        }

    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult {
        val hexHash = destinationHash.joinToString("") { "%02x".format(it) }
        val link = activeLinks[hexHash]
        return if (link != null && link.status == network.reticulum.link.LinkConstants.ACTIVE) {
            ConversationLinkResult(
                isActive = true,
                rttSeconds = link.rtt?.let { it / 1000.0 },
                hops = Transport.hopsTo(destinationHash),
            )
        } else {
            ConversationLinkResult(isActive = false)
        }
    }

    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult {
        val hexHash = destinationHash.joinToString("") { "%02x".format(it) }
        val existingLink = activeLinks[hexHash]
        if (existingLink != null && existingLink.status == network.reticulum.link.LinkConstants.ACTIVE) {
            return LinkSpeedProbeResult(
                status = "active",
                establishmentRateBps = null,
                expectedRateBps = null,
                rttSeconds = existingLink.rtt?.let { it / 1000.0 },
                hops = Transport.hopsTo(destinationHash),
                linkReused = true,
            )
        }
        val result = establishConversationLink(destinationHash, timeoutSeconds).getOrNull()
        return LinkSpeedProbeResult(
            status = if (result?.isActive == true) "established" else "failed",
            establishmentRateBps = result?.establishmentRateBps,
            expectedRateBps = result?.expectedRateBps,
            rttSeconds = result?.rttSeconds,
            hops = result?.hops,
            linkReused = false,
            error = result?.error,
        )
    }

    // ==================== Phase 3: Interface Management ====================

    private fun emitInterfaceSnapshotsAsync() {
        scope.launch {
            runCatching {
                val debugInfo = getDebugInfo()
                _debugInfoFlow.emit(JSONObject(debugInfo).toString())

                val statusJson = JSONObject()
                @Suppress("UNCHECKED_CAST")
                (debugInfo["interfaces"] as? List<Map<String, Any>>)
                    ?.forEach { iface ->
                        val name = iface["name"] as? String ?: return@forEach
                        val online = iface["online"] as? Boolean ?: false
                        statusJson.put(name, online)
                    }
                _interfaceStatusFlow.emit(statusJson.toString())
                _interfaceStatusChanged.emit(Unit)
            }.onFailure { error ->
                Log.w(TAG, "Failed to emit interface snapshot: ${error.message}")
            }
        }
    }

    private fun startBatteryMonitor() {
        val context = appContext ?: return
        val monitor = network.reticulum.android.BatteryMonitor(context)
        monitor.setListener(
            object : network.reticulum.android.BatteryMonitor.BatteryStateListener {
                override fun onBatteryStateChanged(info: network.reticulum.android.BatteryMonitor.BatteryInfo) {
                    systemPowerSaveEnabled = info.powerSaveMode
                    applyBatteryProfileInternal()
                }

                override fun onPowerSaveModeChanged(enabled: Boolean) {
                    systemPowerSaveEnabled = enabled
                    applyBatteryProfileInternal()
                }
            },
        )
        systemPowerSaveEnabled = monitor.isPowerSaveMode
        monitor.start()
        batteryMonitor = monitor
    }

    private fun applyBatteryProfileInternal() {
        val androidMode =
            when (selectedBatteryProfile) {
                BatteryProfile.MAXIMUM_BATTERY -> network.reticulum.android.ReticulumConfig.BatteryMode.MAXIMUM_BATTERY
                BatteryProfile.BALANCED -> network.reticulum.android.ReticulumConfig.BatteryMode.BALANCED
                BatteryProfile.PERFORMANCE -> network.reticulum.android.ReticulumConfig.BatteryMode.PERFORMANCE
            }
        val profileConfig = network.reticulum.android.ReticulumConfig(batteryOptimization = androidMode)

        val baseJobIntervalMs =
            when (selectedBatteryProfile) {
                BatteryProfile.PERFORMANCE -> 30_000L
                BatteryProfile.BALANCED -> 60_000L
                BatteryProfile.MAXIMUM_BATTERY -> 120_000L
            }
        val profileAutoMultiplier =
            when (selectedBatteryProfile) {
                BatteryProfile.PERFORMANCE -> 1.0f
                BatteryProfile.BALANCED -> 1.5f
                BatteryProfile.MAXIMUM_BATTERY -> 3.0f
            }

        val powerSaveMultiplier = if (systemPowerSaveEnabled) 2.0f else 1.0f
        val combinedMultiplier = dozeThrottleMultiplier * powerSaveMultiplier
        val effectiveJobInterval = (baseJobIntervalMs * combinedMultiplier).toLong()
        val effectiveTablesCull = (profileConfig.getEffectiveTablesCullInterval() * combinedMultiplier).toLong()
        val effectiveAnnouncesCheck = (profileConfig.getEffectiveAnnouncesCheckInterval() * combinedMultiplier).toLong()
        val effectiveAutoMultiplier = profileAutoMultiplier * combinedMultiplier
        val forceBleLowPower =
            selectedBatteryProfile == BatteryProfile.MAXIMUM_BATTERY ||
                dozeThrottleMultiplier > 1.0f ||
                systemPowerSaveEnabled

        Transport.customJobIntervalMs = effectiveJobInterval
        Transport.customTablesCullIntervalMs = effectiveTablesCull
        Transport.customAnnouncesCheckIntervalMs = effectiveAnnouncesCheck

        NativeInterfaceFactory
            .currentInterfaces
            .filterIsInstance<network.reticulum.interfaces.auto.AutoInterface>()
            .forEach { it.throttleMultiplier = effectiveAutoMultiplier }

        NativeInterfaceFactory
            .currentInterfaces
            .filterIsInstance<network.reticulum.interfaces.ble.BLEInterface>()
            .forEach { bleInterface ->
                scope.launch {
                    bleInterface.setScanLowPower(forceBleLowPower)
                }
            }

        Log.d(
            TAG,
            "Applied battery profile=$selectedBatteryProfile, doze=${dozeThrottleMultiplier}x, powerSave=$systemPowerSaveEnabled, " +
                "job=${effectiveJobInterval}ms, tablesCull=${effectiveTablesCull}ms, announces=${effectiveAnnouncesCheck}ms, " +
                "auto=${effectiveAutoMultiplier}x, bleLowPower=$forceBleLowPower",
        )
    }

    override suspend fun getDebugInfo(): Map<String, Any> {
        val isReady = _networkStatus.value is NetworkStatus.READY
        val transportInterfaces = Transport.getInterfaces()
        val interfaceList =
            transportInterfaces.map { iface ->
                mapOf(
                    "name" to iface.name,
                    "type" to iface.name.substringBefore("[").trim(),
                    "online" to iface.online,
                    "parent_name" to (iface.parentInterface?.name ?: ""),
                    "can_send" to iface.canSend,
                    "rx_bytes" to iface.rxBytes,
                    "tx_bytes" to iface.txBytes,
                )
            }
        return mapOf(
            "initialized" to isReady,
            "reticulum_available" to isReady,
            "storage_path" to (storagePath ?: ""),
            "transport_enabled" to Transport.transportEnabled,
            "interfaces" to interfaceList,
            "path_table_size" to Transport.pathTable.size,
            "announce_table_size" to Transport.announceTable.size,
            "link_table_size" to Transport.linkTable.size,
            "multicast_lock_held" to false,
            "wake_lock_held" to false,
            "heartbeat_age_seconds" to 0L,
            "health_check_running" to false,
            "network_monitor_running" to false,
            "maintenance_running" to false,
            "last_lock_refresh_age_seconds" to 0L,
            "failed_interface_count" to 0,
        )
    }

    override suspend fun reloadInterfaces(configs: List<network.columba.app.reticulum.model.InterfaceConfig>) {
        withContext(Dispatchers.IO) {
            NativeInterfaceFactory.syncInterfaces(configs)
        }
    }

    override suspend fun setDiscoveryEnabled(enabled: Boolean) {
        if (enabled) {
            val cfg = lastConfig ?: error("Cannot enable discovery before initialize() has run")
            startDiscovery(cfg)
        } else {
            Transport.disableDiscovery()
        }
        Log.i(TAG, "Interface discovery ${if (enabled) "enabled" else "disabled"}")
    }

    override suspend fun setAutoconnectLimit(count: Int) {
        Transport.setMaxAutoConnected(count)
        Log.i(TAG, "Auto-connect limit updated to $count (no restart)")
    }

    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) {
        autoconnectIfacOnly = enabled
        Log.i(TAG, "Auto-connect IFAC-only filter ${if (enabled) "enabled" else "disabled"}")
    }

    override suspend fun getFailedInterfaces(): List<FailedInterface> = emptyList()

    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? {
        val iface = Transport.getInterfaces().find { it.name == interfaceName } ?: return null
        return mapOf(
            "name" to iface.name,
            "online" to iface.online,
            "status" to if (iface.online) "Online" else "Offline",
            "rxb" to iface.rxBytes,
            "txb" to iface.txBytes,
            "mode" to iface.mode.name,
            "bitrate" to iface.bitrate,
            "mtu" to iface.hwMtu,
        )
    }

    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> =
        Transport.listDiscoveredInterfaces().map { (info, status) ->
            val statusCode =
                when (status) {
                    "available" -> DiscoveredInterface.STATUS_AVAILABLE
                    "unknown" -> DiscoveredInterface.STATUS_UNKNOWN
                    else -> DiscoveredInterface.STATUS_STALE
                }
            DiscoveredInterface(
                name = info.name,
                type = info.type,
                transportId = info.transportId,
                networkId = info.networkId,
                status = status,
                statusCode = statusCode,
                lastHeard = info.lastHeard,
                heardCount = info.heardCount,
                hops = info.hops,
                stampValue = info.stampValue,
                reachableOn = info.reachableOn,
                port = info.port,
                frequency = info.frequency,
                bandwidth = info.bandwidth?.toInt(),
                spreadingFactor = info.spreadingFactor,
                codingRate = info.codingRate,
                modulation = info.modulation,
                channel = info.channel,
                latitude = info.latitude,
                longitude = info.longitude,
                height = info.height,
                ifacNetname = info.ifacNetname,
                ifacNetkey = info.ifacNetkey,
                transport = info.transport,
                discoveryHash = info.discoveryHash.joinToString("") { "%02x".format(it) },
                receivedAt = info.received,
                discoveredAt = info.discovered,
            )
        }

    override suspend fun isDiscoveryEnabled(): Boolean = Transport.isDiscoveryEnabled()

    override suspend fun getAutoconnectedEndpoints(): Set<String> = Transport.getAutoconnectedEndpoints()

    override fun setConversationActive(active: Boolean) {
        // No polling-based optimization needed — native stack uses callbacks
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        router?.incomingMessageSizeLimitKb = if (limitKb > 0) limitKb else null
        Log.d(TAG, "Incoming message size limit set to ${if (limitKb > 0) "${limitKb}KB" else "unlimited"}")
    }

    override fun setBatteryProfile(profile: BatteryProfile) {
        selectedBatteryProfile = profile
        lastConfig = lastConfig?.copy(batteryProfile = profile)
        applyBatteryProfileInternal()
    }

    override suspend fun reconnectRNodeInterface() {
        withContext(Dispatchers.IO) {
            // Find and restart any RNode interfaces
            val config = lastConfig
            if (config != null) {
                val rnodeConfigs = config.enabledInterfaces.filterIsInstance<network.columba.app.reticulum.model.InterfaceConfig.RNode>()
                for (rnode in rnodeConfigs) {
                    Log.i(TAG, "Reconnecting RNode interface: ${rnode.name}")
                    NativeInterfaceFactory.restartInterface(rnode)
                }
                if (rnodeConfigs.isEmpty()) {
                    Log.w(TAG, "No RNode interfaces configured")
                }
            }
        }
    }

    // ==================== Phase 4: Voice Calls ====================

    /**
     * Create and wire the native telephony stack.
     *
     * Called once during [initialize] after the delivery identity is available.
     * Idempotent — safe to call again after [shutdown].
     */
    private var dozeObserver: network.reticulum.android.DozeStateObserver? = null

    private fun startDozeObserver() {
        val ctx = appContext ?: return
        val observer = network.reticulum.android.DozeStateObserver(ctx)
        observer.start()
        dozeObserver = observer

        scope.launch {
            observer.state.collect { state ->
                dozeThrottleMultiplier =
                    when (state) {
                        is network.reticulum.android.DozeState.Dozing -> 5.0f
                        is network.reticulum.android.DozeState.Active -> 1.0f
                        else -> 1.0f
                    }
                applyBatteryProfileInternal()
            }
        }
    }

    private fun setupNativeTelephone(identity: NativeIdentity) {
        val ctx = appContext ?: return
        val manager =
            network.columba.app.reticulum.call.telephone
                .NativeCallManager(ctx, identity, callTransport)
        manager.setup()
        callManager = manager
        Log.i(TAG, "📞 Native Telephone ready")
    }

    override suspend fun initiateCall(
        destinationHash: String,
        profileCode: Int?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mgr =
                    callManager
                        ?: error("Call manager not initialized")
                mgr.call(destinationHash, profileCode)
            }
        }

    override suspend fun answerCall(): Result<Unit> =
        runCatching {
            val mgr =
                callManager
                    ?: error("Call manager not initialized")
            mgr.answer()
        }

    override suspend fun hangupCall() {
        callManager?.hangup()
    }

    override suspend fun setCallMuted(muted: Boolean) {
        callManager?.muteMicrophone(muted)
    }

    override suspend fun setCallSpeaker(speakerOn: Boolean) {
        callManager?.setSpeaker(speakerOn)
    }

    override suspend fun getCallState(): Result<VoiceCallState> =
        runCatching {
            val coordinator =
                tech.torlando.lxst.core.CallCoordinator
                    .getInstance()
            val callState = coordinator.callState.value
            val isMuted = coordinator.isMuted.value
            val remoteIdentity = coordinator.remoteIdentity.value

            when (callState) {
                is tech.torlando.lxst.core.CallState.Idle,
                is tech.torlando.lxst.core.CallState.Ended,
                is tech.torlando.lxst.core.CallState.Busy,
                is tech.torlando.lxst.core.CallState.Rejected,
                ->
                    VoiceCallState(
                        status = callState::class.simpleName?.lowercase() ?: "idle",
                        isActive = false,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        profile = null,
                    )
                is tech.torlando.lxst.core.CallState.Connecting,
                is tech.torlando.lxst.core.CallState.Ringing,
                is tech.torlando.lxst.core.CallState.Incoming,
                ->
                    VoiceCallState(
                        status = callState::class.simpleName?.lowercase() ?: "connecting",
                        isActive = false,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        profile = null,
                    )
                is tech.torlando.lxst.core.CallState.Active ->
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = isMuted,
                        remoteIdentity = remoteIdentity,
                        profile = callManager?.telephone?.activeProfile?.abbreviation,
                    )
            }
        }

    // ==================== NomadNet Browsing ====================

    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<ReticulumProtocol.NomadnetPageResult> = nomadNetHandler.requestNomadnetPage(destinationHash, path, formDataJson, timeoutSeconds)

    override suspend fun cancelNomadnetPageRequest() {
        nomadNetHandler.nomadnetCancelled = true
        nomadNetHandler.requestStatusFlow.value = "cancelled"
    }

    override suspend fun getNomadnetRequestStatus(): String = nomadNetHandler.requestStatusFlow.value

    override suspend fun getNomadnetDownloadProgress(): Float = nomadNetHandler.downloadProgressFlow.value

    override suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> = nomadNetHandler.identifyNomadnetLink(destinationHash)

    // ==================== Version Info ====================

    override suspend fun getReticulumVersion(): String? = "Reticulum-kt ${BuildConfig.RNS_KT_VERSION}"

    override suspend fun getLxmfVersion(): String? = "LXMF-kt ${BuildConfig.LXMF_KT_VERSION}"

    override suspend fun getBleReticulumVersion(): String? = null

    override suspend fun getLxstVersion(): String? = "LXST-kt ${BuildConfig.LXST_KT_VERSION}"

    // ==================== Blocking & Transport ====================

    override suspend fun blockDestination(destinationHashHex: String): Result<Unit> =
        runCatching {
            blockedDestinations.add(destinationHashHex)
            Log.d(TAG, "Blocked destination: ${destinationHashHex.take(16)}")
        }

    override suspend fun unblockDestination(destinationHashHex: String): Result<Unit> =
        runCatching {
            blockedDestinations.remove(destinationHashHex)
            Log.d(TAG, "Unblocked destination: ${destinationHashHex.take(16)}")
        }

    override suspend fun blackholeIdentity(identityHashHex: String): Result<Unit> =
        runCatching {
            blackholedIdentities.add(identityHashHex)
            Log.d(TAG, "Blackholed identity: ${identityHashHex.take(16)}")
        }

    override suspend fun unblackholeIdentity(identityHashHex: String): Result<Unit> =
        runCatching {
            blackholedIdentities.remove(identityHashHex)
            Log.d(TAG, "Unblackholed identity: ${identityHashHex.take(16)}")
        }

    override suspend fun isTransportEnabled(): Boolean = Transport.transportEnabled
}
