package network.columba.app.reticulum.protocol

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** Coroutine scope for async interface startup (BLE, RNode). */
    var scope: CoroutineScope? = null

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
        val collectorScope = scope ?: return
        // Atomically replace any previous collector under the same key
        // (restartInterface() re-registers under the same name, and
        // ConcurrentHashMap#compute serialises with concurrent remove()s
        // in stopInterface() so a racing teardown either cancels the
        // freshly-launched job or runs entirely before it is stored).
        onlineObservers.compute(name) { _, previous ->
            previous?.cancel()
            iface.online
                .drop(1)
                .onEach { online ->
                    Log.d(TAG, "Interface $name online → $online")
                    notifyListeners()
                }.launchIn(collectorScope)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startBleInterface(config: InterfaceConfig.AndroidBLE) {
        val ctx = appContext
        val parentScope = scope
        if (ctx == null || parentScope == null) {
            Log.e(TAG, "Cannot start BLE interface: appContext or scope not set")
            return
        }
        parentScope.launch(Dispatchers.IO) {
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
                        scope = parentScope,
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
        onlineObservers.remove(name)?.cancel()
        val iface = runningInterfaces.remove(name) ?: return
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
                scope?.launch(Dispatchers.IO) {
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
