package network.columba.app.rns.backend.kt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.rns.api.model.InterfaceConfig
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
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

    /**
     * Columba mode string last applied to each running interface, keyed by name.
     * [syncInterfaces] compares the desired mode against this to detect a saved
     * mode edit on an already-running interface and restart it so the change
     * takes effect. The diff-based sync otherwise only starts names that are not
     * already running, so a persisted mode edit would never reach the running
     * object (issue #1169, PR #1188 review P1). Tracking just the mode (not the
     * full config) avoids spurious restarts from non-deterministic config fields.
     */
    private val runningModes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Per-name start generation, used to invalidate a superseded async start
     * (RNode, BLE) before it can register. An async start captures the current
     * generation when it launches; [stopInterface], [restartInterface], and
     * [syncInterfaces] (when a name is no longer desired) advance the
     * generation. A start that finishes after its interface was stopped,
     * restarted, or deleted sees the mismatch and refuses to call
     * [registerAndTrack], so it cannot revive an interface the user has since
     * disabled or deleted (issue #1169, PR #1188 review). A generation counter
     * (rather than the launch Job) is race-free: a stale start can never
     * clobber or cancel a newer start's bookkeeping.
     */
    private val startGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Current start generation for [name]. */
    private fun currentGeneration(name: String): Long = startGenerations[name] ?: 0L

    /** Advances and returns the new start generation for [name]. */
    private fun nextGeneration(name: String): Long =
        startGenerations.compute(name) { _, cur -> (cur ?: 0L) + 1L } ?: 1L

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
     * Optional host bridge for RNode USB / BLE serial opening. Supplied by
     * `:rns-host/src/kotlinBackend/.../HostBackendModule.kt`'s Hilt @Provides;
     * threaded into [RNodeConnectionHelper] each time an RNode interface
     * starts. Null in unit-test mode where no RNode hardware is reachable
     * anyway.
     */
    var rnodeHostBridge: RNodeHostBridge? = null

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

        // Invalidate any in-flight async start (RNode/BLE) whose name is no
        // longer desired. Such a start is not yet in runningInterfaces, so the
        // stop loop above doesn't reach it; without this a start still waiting
        // on a radio connection would register and revive an interface the
        // user just disabled or deleted (issue #1169, PR #1188 review).
        for (name in startGenerations.keys.toSet()) {
            if (name !in desiredNames) nextGeneration(name)
        }

        // Restart interfaces whose saved mode changed while they were running.
        // The diff-based sync below only *starts* names not already running, so a
        // persisted mode edit on a running interface would otherwise be dropped
        // (the running object keeps the mode it was started with). Issue #1169,
        // PR #1188 review P1.
        for (name in runningNames intersect desiredNames) {
            val config = configs.first { it.name == name }
            val previousMode = runningModes[name]
            val desiredMode = configModeOf(config)
            if (previousMode != null && previousMode != desiredMode) {
                Log.i(TAG, "Mode changed for running interface $name: $previousMode -> $desiredMode; restarting")
                restartInterface(config)
            }
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

    /** Extracts the Columba mode string from an [InterfaceConfig]. */
    private fun configModeOf(config: InterfaceConfig): String =
        when (config) {
            is InterfaceConfig.AutoInterface -> config.mode
            is InterfaceConfig.TCPClient -> config.mode
            is InterfaceConfig.TCPServer -> config.mode
            is InterfaceConfig.UDP -> config.mode
            is InterfaceConfig.RNode -> config.mode
            is InterfaceConfig.AndroidBLE -> config.mode
        }

    private fun startInterface(config: InterfaceConfig) {
        // BLE requires async setup (scan + GATT + MTU negotiation)
        if (config is InterfaceConfig.AndroidBLE) {
            startBleInterface(config)
            return
        }
        try {
            val iface = createInterface(config) ?: return
            iface.start()
            registerAndTrack(config.name, iface, config, currentGeneration(config.name))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interface ${config.name}: ${e.message}", e)
        }
    }

    private fun registerAndTrack(
        name: String,
        iface: network.reticulum.interfaces.Interface,
        config: InterfaceConfig,
        generation: Long,
    ) {
        if (currentGeneration(name) != generation) {
            // A stop/restart/delete advanced the generation after this start
            // began; do not revive an interface that is no longer wanted.
            // The start paths call iface.start() BEFORE this check, so the
            // object already holds a live radio connection and background
            // coroutines. Detach it so disabling the interface also releases
            // those resources; otherwise the object keeps running but never
            // enters runningInterfaces, so stopInterface/shutdownAll cannot
            // find it (issue #1169, PR #1188 review).
            Log.i(TAG, "Start for $name superseded (gen $generation -> ${currentGeneration(name)}); not registering, detaching")
            runCatching { iface.detach() }
                .onFailure { e -> Log.w(TAG, "Error detaching superseded interface $name: ${e.message}") }
            return
        }
        val ref =
            network.reticulum.interfaces.InterfaceAdapter
                .getOrCreate(iface)
        Transport.registerInterface(ref)
        runningInterfaces[name] = iface
        runningModes[name] = configModeOf(config)

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
     * every distinct transition.
     *
     * Previously used `.drop(1)` to skip the initial replay value StateFlow
     * hands out on subscribe, on the assumption that the initial value was
     * already surfaced via the [notifyListeners] call at the bottom of
     * [registerAndTrack]. That assumption broke when the interface
     * transitions online *before* the collector can subscribe:
     *
     *   1. [startInterface] calls [iface.start()] (line 125).
     *   2. For RNode over a warm USB connection, KISS handshake completes
     *      in well under a second, transitioning `iface.online` false→true.
     *   3. [registerAndTrack] runs, captures `online=true` in its
     *      [notifyListeners] snapshot.
     *   4. [observeOnlineState] subscribes — StateFlow replays the CURRENT
     *      value (`true`). With `.drop(1)` that emission is swallowed as
     *      "the initial value". There is no subsequent change, so the
     *      collector never fires again.
     *   5. Later, [notifyListeners] is called for unrelated reasons (e.g.
     *      user toggles another interface) but the interface-list-UI caches
     *      the first snapshot and never re-reads — the RNode card stays
     *      "offline" until the app restarts.
     *
     * The fix is to remove the drop: the StateFlow's initial replay is now
     * propagated as one extra [notifyListeners] call (idempotent — same
     * snapshot as the one emitted at the bottom of [registerAndTrack]),
     * which closes the window where a fast transition between start() and
     * subscribe can strand the UI in the wrong state.
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
        // Advance + capture the start generation synchronously (before the
        // coroutine runs) so a stop/restart/delete that lands after this call
        // but before the async start finishes sees a higher generation and the
        // stale start's registerAndTrack bails. Calling it inside the coroutine
        // would leave a window where an early stop is absorbed (the coroutine
        // would read the already-bumped value and pass the check).
        val gen = nextGeneration(config.name)
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
                    ).withMode(config.name, config.mode)
                iface.onPacketReceived = { data, fromInterface ->
                    Transport.inbound(
                        data,
                        network.reticulum.interfaces.InterfaceAdapter
                            .getOrCreate(fromInterface),
                    )
                }
                iface.start()
                registerAndTrack(config.name, iface, config, gen)
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

    /**
     * Starts an RNode interface. The suspend work is launched on [scope] and
     * this returns immediately (non-suspending), so the sync thread that calls
     * [startInterface] is never blocked on the USB/BLE radio connection. That
     * matters for the generation guard: if the start were a blocking suspend
     * call, a disable issued from that same thread could not interleave with
     * an in-flight start to bump the generation.
     */
    private fun startRNodeInterface(
        config: InterfaceConfig.RNode,
        gen: Long,
        scope: CoroutineScope,
    ) {
        scope.launch(Dispatchers.IO) {
            RNodeConnectionHelper.startRNodeInterface(
                config = config,
                appContext = appContext,
                hostBridge = rnodeHostBridge,
                scope = scope,
                onRegisterAndTrack = { name, iface -> registerAndTrack(name, iface, config, gen) },
                onMonitorLifecycle = ::monitorRNodeLifecycle,
                onEnsureRecovery = ::ensureRNodeRecovery,
            )
        }
    }

    private fun stopInterface(name: String) {
        // Invalidate any in-flight async start (RNode/BLE) for this name so it
        // cannot register later and revive an interface we are tearing down.
        nextGeneration(name)
        rnodeRecoveryJobs.remove(name)?.cancel()
        // Remove from runningInterfaces BEFORE onlineObservers so that a
        // concurrent observeOnlineState() still sitting in its compute
        // block sees the interface as unmanaged and bails out instead of
        // leaving an orphaned collector behind.
        val iface = runningInterfaces.remove(name)
        runningModes.remove(name)
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

    @androidx.annotation.VisibleForTesting
    internal fun createInterface(config: InterfaceConfig): network.reticulum.interfaces.Interface? {
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
                ).withMode(config.name, config.mode)

            is InterfaceConfig.TCPClient ->
                TCPClientInterface(
                    name = config.name,
                    targetHost = config.targetHost,
                    targetPort = config.targetPort,
                    useKissFraming = config.kissFraming,
                    keepAlive = false, // Disable for mobile battery
                    ifacNetname = config.networkName,
                    ifacNetkey = config.passphrase,
                ).withMode(config.name, config.mode)

            is InterfaceConfig.UDP ->
                UDPInterface(
                    name = config.name,
                    bindIp = config.listenIp,
                    bindPort = config.listenPort,
                    forwardIp = config.forwardIp,
                    forwardPort = config.forwardPort,
                ).withMode(config.name, config.mode)

            is InterfaceConfig.TCPServer ->
                TCPServerInterface(
                    name = config.name,
                    bindAddress = config.listenIp,
                    bindPort = config.listenPort,
                    ifacNetname = config.networkName,
                    ifacNetkey = config.passphrase,
                ).withMode(config.name, config.mode).apply {
                    // Register each spawned child interface with Transport BEFORE
                    // start() opens the accept loop, so the first incoming
                    // connection can't race us into a silent-drop: Python RNS
                    // does the same at RNS/Interfaces/TCPInterface.py:623
                    // (Transport.interfaces.append(spawned_interface)), and
                    // reticulum-kt PR #47 spawned-without-register bug
                    // manifested as path responses dropped in
                    // Transport.kt:3632-3658. See also: the conformance-bridge
                    // reference implementation in WireTcp.kt:198-210.
                    onClientConnected = { spawnedChild ->
                        runCatching {
                            Transport.registerInterface(
                                network.reticulum.interfaces.InterfaceAdapter
                                    .getOrCreate(spawnedChild),
                            )
                        }.onFailure { e ->
                            Log.e(
                                TAG,
                                "Failed to register spawned client ${spawnedChild.name} " +
                                    "for server ${config.name}: ${e.message}",
                                e,
                            )
                        }
                        Log.i(
                            TAG,
                            "TCPServer ${config.name}: client connected (${spawnedChild.name})",
                        )
                        // Parity with registerAndTrack: surface the new sub-interface
                        // to the UI immediately instead of waiting for the next
                        // debugInfoFlow poll. Without this the InterfaceList card
                        // for a TCP server lags by a second or two on every client
                        // connect/disconnect pair.
                        notifyListeners()
                    }
                    // TCPServerInterface.clientDisconnected already deregisters
                    // via Transport; this callback is purely for surface-level
                    // logging so a silent child-leak doesn't masquerade as
                    // "server healthy" in the UI.
                    onClientDisconnected = { spawnedChild ->
                        Log.i(
                            TAG,
                            "TCPServer ${config.name}: client disconnected (${spawnedChild.name})",
                        )
                        notifyListeners()
                    }
                }

            is InterfaceConfig.RNode -> {
                // Advance + capture the start generation synchronously (on the
                // calling thread, before startRNodeInterface returns) so a
                // stop/restart/delete landing after this call but before the
                // async start finishes invalidates it. startRNodeInterface
                // launches its suspend work on the factory scope and returns
                // immediately, so this does not block the sync thread.
                val gen = nextGeneration(config.name)
                startRNodeInterface(config, gen, scope)
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
