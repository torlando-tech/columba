package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTransportAdmin
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback
import network.columba.app.rns.ipc.callback.IRnsUnitEventCallback
import network.columba.app.rns.ipc.toAnyMap

internal class ClientRnsTransportAdmin(
    private val remote: IRnsTransportAdmin,
    private val scope: CoroutineScope,
) : RnsTransportAdmin {
    override fun setBatteryProfile(profile: BatteryProfile) {
        // Fire-and-forget on the Kotlin side; errors logged on host.
        runCatching { remote.setBatteryProfile(profile) }
    }

    override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {
        awaitResult { cb -> remote.reloadInterfaces(configs, cb) }
    }

    override suspend fun setDiscoveryEnabled(enabled: Boolean) {
        awaitResult { cb -> remote.setDiscoveryEnabled(enabled, cb) }
    }

    override suspend fun setAutoconnectLimit(count: Int) {
        awaitResult { cb -> remote.setAutoconnectLimit(count, cb) }
    }

    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) {
        awaitResult { cb -> remote.setAutoconnectIfacOnly(enabled, cb) }
    }

    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> {
        val bundle = awaitResult { cb -> remote.getDiscoveredInterfaces(cb) }
        bundle.classLoader = DiscoveredInterface::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelableArrayList<DiscoveredInterface>(BundleKeys.INTERFACES).orEmpty()
    }

    override suspend fun isDiscoveryEnabled(): Boolean =
        awaitBool { cb -> remote.isDiscoveryEnabled(cb) }

    override suspend fun getAutoconnectedEndpoints(): Set<String> =
        awaitStringList { cb -> remote.getAutoconnectedEndpoints(cb) }.toSet()

    override suspend fun isSharedInstanceAvailable(): Boolean =
        awaitBool { cb -> remote.isSharedInstanceAvailable(cb) }

    override suspend fun isHostingSharedInstance(): Boolean =
        awaitBool { cb -> remote.isHostingSharedInstance(cb) }

    override suspend fun getSharedInstanceAccessConfig(): String? =
        awaitNullableString { cb -> remote.getSharedInstanceAccessConfig(cb) }

    override suspend fun getDebugInfo(): Map<String, Any> {
        val bundle = awaitResult { cb -> remote.getDebugInfo(cb) }
        return bundle.toAnyMap()
    }

    override suspend fun getFailedInterfaces(): List<FailedInterface> {
        val bundle = awaitResult { cb -> remote.getFailedInterfaces(cb) }
        bundle.classLoader = FailedInterface::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelableArrayList<FailedInterface>(BundleKeys.INTERFACES).orEmpty()
    }

    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? {
        val bundle = awaitResult { cb -> remote.getInterfaceStats(interfaceName, cb) }
        if (!bundle.getBoolean(BundleKeys.HAS_STATS, false)) return null
        return bundle.toAnyMap(skip = setOf(BundleKeys.HAS_STATS))
    }

    override suspend fun reconnectRNodeInterface() {
        awaitResult { cb -> remote.reconnectRNodeInterface(cb) }
    }

    // getRNodeRssi is non-suspend on the Kotlin side but oneway across AIDL.
    // Cache the most recent value seeded from the once-on-init fetch and
    // refreshed via the interface-status observer (which fires whenever RSSI
    // changes meaningfully). -100 is the noise-floor sentinel until the first
    // observer event or snapshot fetch lands.
    @Volatile private var lastRssi: Int = -100

    override fun getRNodeRssi(): Int = lastRssi

    // Battery is a LIVE value: fresh AIDL round-trip per call. The stats screen
    // polls at 1s and the notification poller at ~15s, so a bind-time cache
    // (the getRNodeRssi pattern) would be wrong. -1 when absent / on error.
    override suspend fun getRNodeBattery(): Int =
        runCatching {
            awaitNullableInt { cb -> remote.getRNodeBattery(cb) } ?: -1
        }.getOrDefault(-1)

    // getBleConnectionDetails is also non-suspend on the Kotlin side. Same
    // observer-cache trick — the BLE connections SharedFlow emits the same
    // JSON snapshot whenever peers connect/disconnect, so caching covers the
    // "current peers" question without per-call IPC.
    @Volatile private var lastBleConnections: String = "[]"

    override fun getBleConnectionDetails(): String = lastBleConnections

    private val interfaceStatusChangedShared = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val bleConnectionsShared = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private val debugInfoShared = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val interfaceStatusShared = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 32)
    private val interfaceStatusReady = CompletableDeferred<Unit>()
    private val reactionReceivedShared = MutableSharedFlow<String>(extraBufferCapacity = 32)

    init {
        callbackFlow<Unit> {
            val cb = object : IRnsUnitEventCallback.Stub() { override fun onEvent() { trySend(Unit) } }
            if (!registerObserverOrClose { remote.registerInterfaceStatusChangedObserver(cb) }) return@callbackFlow
            awaitClose { runCatching { remote.unregisterInterfaceStatusChangedObserver(cb) } }
        }.onEach { interfaceStatusChangedShared.emit(it) }.launchIn(scope)

        stringEventFlow(
            register = { remote.registerBleConnectionsObserver(it) },
            unregister = { remote.unregisterBleConnectionsObserver(it) },
        ).onEach { lastBleConnections = it; bleConnectionsShared.emit(it) }.launchIn(scope)

        stringEventFlow(
            register = { remote.registerDebugInfoObserver(it) },
            unregister = { remote.unregisterDebugInfoObserver(it) },
        ).onEach { debugInfoShared.emit(it) }.launchIn(scope)

        val interfaceStatusJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                interfaceStatusEventFlow(
                    register = { cb, readyCb -> remote.registerInterfaceStatusObserver(cb, readyCb) },
                    unregister = { remote.unregisterInterfaceStatusObserver(it) },
                ).collect { interfaceStatusShared.emit(it) }
            }
        interfaceStatusJob.invokeOnCompletion { cause ->
            if (!interfaceStatusReady.isCompleted) {
                interfaceStatusReady.completeExceptionally(
                    cause ?: IllegalStateException("Interface status observer ended before registration was ready"),
                )
            }
        }

        stringEventFlow(
            register = { remote.registerReactionReceivedObserver(it) },
            unregister = { remote.unregisterReactionReceivedObserver(it) },
        ).onEach { reactionReceivedShared.emit(it) }.launchIn(scope)

        scope.launch {
            runCatching { lastRssi = awaitNullableInt { cb -> remote.getRNodeRssi(cb) } ?: -100 }
            runCatching {
                val ble = awaitNullableString { cb -> remote.getBleConnectionDetails(cb) }
                if (ble != null) lastBleConnections = ble
            }
        }
    }

    private fun stringEventFlow(
        register: (IRnsStringEventCallback) -> Unit,
        unregister: (IRnsStringEventCallback) -> Unit,
    ): Flow<String> = callbackFlow {
        val cb = object : IRnsStringEventCallback.Stub() {
            override fun onString(value: String?) { if (value != null) trySend(value) }
        }
        if (!registerObserverOrClose { register(cb) }) return@callbackFlow
        awaitClose { runCatching { unregister(cb) } }
    }

    private fun interfaceStatusEventFlow(
        register: (IRnsStringEventCallback, IRnsUnitEventCallback) -> Unit,
        unregister: (IRnsStringEventCallback) -> Unit,
    ): Flow<String> = callbackFlow {
        val cb = object : IRnsStringEventCallback.Stub() {
            override fun onString(value: String?) {
                if (value != null) trySend(value)
            }
        }
        val readyCb = object : IRnsUnitEventCallback.Stub() {
            override fun onEvent() {
                interfaceStatusReady.complete(Unit)
            }
        }
        if (!registerObserverOrClose { register(cb, readyCb) }) return@callbackFlow
        awaitClose { runCatching { unregister(cb) } }
    }

    internal suspend fun awaitInterfaceStatusReady() {
        interfaceStatusReady.await()
    }

    override val interfaceStatusChanged: SharedFlow<Unit> get() = interfaceStatusChangedShared.asSharedFlow()
    override val bleConnectionsFlow: SharedFlow<String> get() = bleConnectionsShared.asSharedFlow()
    override val debugInfoFlow: SharedFlow<String> get() = debugInfoShared.asSharedFlow()
    override val interfaceStatusFlow: SharedFlow<String> get() = interfaceStatusShared.asSharedFlow()
    override val reactionReceivedFlow: SharedFlow<String> get() = reactionReceivedShared.asSharedFlow()
}
