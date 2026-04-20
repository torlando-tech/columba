package network.columba.app.reticulum.protocol

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.reticulum.model.InterfaceConfig
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.udp.UDPInterface
import network.reticulum.transport.Transport

/**
 * Creates and registers reticulum-kt network interfaces from Columba [InterfaceConfig] objects.
 * Matches Carina's InterfaceManager pattern: diff-based sync, async BLE startup.
 */
@Suppress("TooManyFunctions") // cohesive interface-lifecycle helpers; splitting would obscure coordination
internal object NativeInterfaceFactory {
    private const val TAG = "NativeInterfaceFactory"

    /** Running interfaces keyed by config name. */
    private val runningInterfaces = java.util.concurrent.ConcurrentHashMap<String, network.reticulum.interfaces.Interface>()
    private val rnodeRecoveryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Per-interface collector jobs watching [network.reticulum.interfaces.Interface.online].
     * reticulum-kt v0.0.9+ exposes `online` as a `StateFlow<Boolean>`; some
     * interface types (notably [network.reticulum.interfaces.rnode.RNodeInterface])
     * don't flip online until their handshake completes 3-5s after
     * registration. Without an observer the Columba UI caches the initial
     * `false` value forever. This map keeps the collector jobs alive for
     * the lifetime of the interface and lets us cancel them in [stopInterface].
     */
    private val onlineObservers = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** App context for BLE driver construction. */
    var appContext: Context? = null

    /**
     * Process-lifetime coroutine scope for async interface startup (BLE,
     * RNode) and per-interface online-state observers. Owned by the factory
     * itself — not assigned externally — so the scope never ends up in a
     * null-or-cancelled state between protocol init cycles.
     *
     * Previously this was a nullable `var` assigned by
     * [NativeReticulumProtocol.initialize]; if the protocol was shut down
     * (which cancels the protocol's scope) without clearing this reference,
     * subsequent interface-toggle attempts would silently no-op because
     * `cancelledScope.launch(...)` returns a pre-cancelled Job. The
     * concrete symptom: user toggles a saved RNode interface, factory
     * logs "Sync complete: started 1", but nothing downstream ever runs.
     *
     * `SupervisorJob` ensures a single interface's startup failure doesn't
     * cancel siblings. No callsite cancels this scope; per-interface
     * cleanup flows through each interface's own stop() method.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Diff-based sync: compare running interfaces with desired config.
     * Only stops removed ones, only starts new ones, leaves unchanged running.
     */
    fun syncInterfaces(configs: List<InterfaceConfig>) {
        val desiredNames = configs.filter { it.enabled }.map { it.name }.toSet()
        val runningNames = runningInterfaces.keys.toSet()

        // Stop removed interfaces
        for (name in runningNames - desiredNames) {
            stopInterface(name)
        }

        // Start new interfaces
        for (name in desiredNames - runningNames) {
            val config = configs.first { it.name == name }
            startInterface(config)
        }

        Log.d(
            TAG,
            "Sync complete: ${runningInterfaces.size} running (stopped ${(runningNames - desiredNames).size}, started ${(desiredNames - runningNames).size})",
        )
    }

    val currentInterfaces: Collection<network.reticulum.interfaces.Interface> get() = runningInterfaces.values

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Force-stop and recreate a single interface without affecting others. */
    fun restartInterface(config: InterfaceConfig) {
        stopInterface(config.name)
        if (config.enabled) {
            startInterface(config)
        }
    }

    fun shutdownAll() {
        for (name in runningInterfaces.keys.toList()) {
            stopInterface(name)
        }
    }

    private fun startInterface(config: InterfaceConfig) {
        // BLE requires async setup (scan + GATT + MTU negotiation)
        if (config is InterfaceConfig.AndroidBLE) {
            startBleInterface(config)
            return
        }
        try {
            val iface = createInterface(config) ?: return
            val rnsInterface = iface as network.reticulum.interfaces.Interface
            rnsInterface.start()
            registerAndTrack(config.name, rnsInterface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interface ${config.name}: ${e.message}", e)
        }
    }

    private fun registerAndTrack(
        name: String,
        iface: network.reticulum.interfaces.Interface,
    ) {
        val ref =
            network.reticulum.interfaces.InterfaceAdapter
                .getOrCreate(iface)
        Transport.registerInterface(ref)
        runningInterfaces[name] = iface

        // Acquire multicast lock when AutoInterface starts (needed for multicast receive)
        if (iface is network.reticulum.interfaces.auto.AutoInterface) {
            MulticastLockHelper.acquire(appContext)
        }

        observeOnlineState(name, iface)

        Log.i(TAG, "Started interface: $name (online=${iface.online.value})")
        notifyListeners()
    }

    /**
     * Collect the interface's online StateFlow and re-notify listeners on
     * every distinct transition. The initial value was already surfaced via
     * the [notifyListeners] call in [registerAndTrack] (which emits a
     * snapshot with `online=${iface.online.value}`), so we drop(1) to
     * avoid a duplicate no-op notify for the initial replay StateFlow
     * hands out on subscribe. StateFlow itself guarantees structural-equality
     * deduplication at the emitter, so no explicit distinctUntilChanged() is
     * needed here.
     */
    private fun observeOnlineState(
        name: String,
        iface: network.reticulum.interfaces.Interface,
    ) {
        // Atomically replace any previous collector under the same key.
        // runningInterfaces is the source of truth for "this interface is
        // currently managed"; stopInterface() removes it BEFORE
        // onlineObservers, so a racing teardown that won the
        // runningInterfaces lock first will leave us with an absent key —
        // we detect that here and refuse to install the collector,
        // preventing an orphaned observer from surviving teardown.
        onlineObservers.compute(name) { _, previous ->
            previous?.cancel()
            if (!runningInterfaces.containsKey(name)) {
                return@compute null
            }
            iface.online
                .drop(1)
                .onEach { online ->
                    Log.d(TAG, "Interface $name online → $online")
                    notifyListeners()
                }.launchIn(scope)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startBleInterface(config: InterfaceConfig.AndroidBLE) {
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "Cannot start BLE interface: appContext not set")
            return
        }
        scope.launch(Dispatchers.IO) {
            // Give the driver its own scope rather than the factory's.
            // BLEInterface.detach() calls driver.shutdown(), which internally
            // cancels whatever scope it was constructed with. Sharing the
            // factory's process-lifetime scope here would let a single BLE
            // interface stop nuke the factory's ability to start any future
            // interface — the cancelled-scope bug this PR set out to
            // eliminate. Declared outside the try so a thrown exception can
            // still cancel it and release the driver's aggregator coroutines.
            val driverScope =
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val identityHash =
                    Transport.identity?.hash
                        ?: error("Transport identity not available for BLE interface")

                val bluetoothManager =
                    ctx.getSystemService(Context.BLUETOOTH_SERVICE)
                        as android.bluetooth.BluetoothManager

                val driver =
                    network.reticulum.android.ble.AndroidBLEDriver(
                        context = ctx,
                        bluetoothManager = bluetoothManager,
                        scope = driverScope,
                    )
                driver.setTransportIdentity(identityHash)

                val iface =
                    network.reticulum.interfaces.ble.BLEInterface(
                        name = config.name,
                        driver = driver,
                        transportIdentity = identityHash,
                    )
                iface.onPacketReceived = { data, fromInterface ->
                    Transport.inbound(
                        data,
                        network.reticulum.interfaces.InterfaceAdapter
                            .getOrCreate(fromInterface),
                    )
                }
                iface.start()
                registerAndTrack(config.name, iface)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE interface ${config.name}: ${e.message}", e)
                // AndroidBLEDriver launches event-aggregator coroutines from
                // its init{} block, so the scope holds live work as soon as
                // the driver is constructed. Cancel it on the failure path to
                // avoid orphaning those jobs when stopInterface won't run
                // (nothing is tracked in runningInterfaces).
                driverScope.cancel()
            }
        }
    }

    private suspend fun startRNodeInterface(config: InterfaceConfig.RNode) {
        RNodeConnectionHelper.startRNodeInterface(
            config = config,
            appContext = appContext,
            scope = scope,
            onRegisterAndTrack = ::registerAndTrack,
            onMonitorLifecycle = ::monitorRNodeLifecycle,
            onEnsureRecovery = ::ensureRNodeRecovery,
        )
    }

    private fun stopInterface(name: String) {
        rnodeRecoveryJobs.remove(name)?.cancel()
        // Remove from runningInterfaces BEFORE onlineObservers so that a
        // concurrent observeOnlineState() still sitting in its compute
        // block sees the interface as unmanaged and bails out instead of
        // leaving an orphaned collector behind.
        val iface = runningInterfaces.remove(name)
        onlineObservers.remove(name)?.cancel()
        if (iface == null) return
        try {
            val ref =
                network.reticulum.interfaces.InterfaceAdapter
                    .getOrCreate(iface)
            Transport.deregisterInterface(ref)
            iface.spawnedInterfaces?.forEach { child ->
                Transport.deregisterInterface(
                    network.reticulum.interfaces.InterfaceAdapter
                        .getOrCreate(child),
                )
            }
            // Release multicast lock when last AutoInterface stops
            if (iface is network.reticulum.interfaces.auto.AutoInterface) {
                val anyAutoLeft =
                    runningInterfaces.values.any {
                        it is network.reticulum.interfaces.auto.AutoInterface
                    }
                if (!anyAutoLeft) MulticastLockHelper.release()
            }

            iface.detach()
            Log.i(TAG, "Stopped interface: $name")
            notifyListeners()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping interface $name: ${e.message}")
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            runCatching { listener.invoke() }
                .onFailure { Log.w(TAG, "Interface listener failed: ${it.message}") }
        }
    }

    private fun monitorRNodeLifecycle(
        config: InterfaceConfig.RNode,
        iface: network.reticulum.interfaces.rnode.RNodeInterface,
    ) {
        RNodeRecoveryHelper.monitorLifecycle(
            config = config,
            iface = iface,
            scope = scope,
            runningInterfaces = runningInterfaces,
            onNotifyListeners = ::notifyListeners,
            onEnsureRecovery = ::ensureRNodeRecovery,
        )
    }

    private fun ensureRNodeRecovery(config: InterfaceConfig.RNode) {
        RNodeRecoveryHelper.ensureRecovery(
            config = config,
            scope = scope,
            rnodeRecoveryJobs = rnodeRecoveryJobs,
            runningInterfaces = runningInterfaces,
            onStopInterface = ::stopInterface,
            onStartInterface = ::startInterface,
        )
    }

    private fun createInterface(config: InterfaceConfig): Any? {
        fun mapScopeToHex(scopeName: String): String =
            when (scopeName.lowercase()) {
                "link" -> "2"
                "admin" -> "4"
                "site" -> "5"
                "organisation", "organization" -> "8"
                "global" -> "e"
                else -> scopeName
            }

        return when (config) {
            is InterfaceConfig.AutoInterface ->
                AutoInterface(
                    name = config.name,
                    discoveryScope = mapScopeToHex(config.discoveryScope),
                )

            is InterfaceConfig.TCPClient ->
                TCPClientInterface(
                    name = config.name,
                    targetHost = config.targetHost,
                    targetPort = config.targetPort,
                    useKissFraming = config.kissFraming,
                    keepAlive = false, // Disable for mobile battery
                    ifacNetname = config.networkName,
                    ifacNetkey = config.passphrase,
                )

            is InterfaceConfig.UDP ->
                UDPInterface(
                    name = config.name,
                    bindIp = config.listenIp,
                    bindPort = config.listenPort,
                    forwardIp = config.forwardIp,
                    forwardPort = config.forwardPort,
                )

            is InterfaceConfig.TCPServer -> {
                Log.w(TAG, "TCPServer not yet supported in native stack")
                null
            }

            is InterfaceConfig.RNode -> {
                scope.launch(Dispatchers.IO) {
                    startRNodeInterface(config)
                }
                null
            }

            is InterfaceConfig.AndroidBLE -> {
                null
            }

            else -> {
                Log.w(TAG, "Unknown interface type: ${config::class.simpleName}")
                null
            }
        }
    }
}
