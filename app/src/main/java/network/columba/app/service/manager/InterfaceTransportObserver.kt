package network.columba.app.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.columba.app.repository.InterfaceRepository
import network.columba.app.reticulum.protocol.ReticulumProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main-process observer of the device's transport class. When the transport changes
 * (Wi-Fi/Ethernet ↔ cellular ↔ none), re-applies the per-interface network restriction
 * and asks the native stack to reload the resulting subset.
 *
 * Lives in the main process — `:reticulum`'s `NetworkChangeManager` cannot reach the
 * Hilt-injected `InterfaceRepository`, and `ReticulumProtocol.reloadInterfaces` is
 * called from this side anyway. Each Android process gets its own `NetworkCallback`
 * delivery, so observing here is independent of the service-side observer.
 *
 * Idle until `start()`. The first capability update after `start()` is treated as a
 * transition (so an app that boots on cellular with a wifi-only AutoInterface drops
 * the AutoInterface immediately, not on the first carrier change). After init the
 * filter only fires on actual transport changes — same-bucket capability updates are
 * coalesced.
 */
@Singleton
class InterfaceTransportObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val interfaceRepository: InterfaceRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "InterfaceTransportObserver"
        }

        private val connectivityManager: ConnectivityManager by lazy {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }

        private var callback: ConnectivityManager.NetworkCallback? = null

        @Volatile
        private var lastTransport: CurrentTransport? = null

        @Volatile
        private var reloadJob: Job? = null

        private val _currentTransport = MutableStateFlow(CurrentTransport.NONE)

        /**
         * Reactive view of the device's current transport class. Emits on every transition
         * the observer treats as meaningful (i.e. once per call to `applyTransport()` that
         * actually changed `lastTransport`). Seeded with `NONE` until the first capability
         * callback fires after `start()` — collectors should treat that initial value as
         * "unknown" rather than "definitely no network".
         */
        val currentTransport: StateFlow<CurrentTransport> = _currentTransport.asStateFlow()

        /**
         * Start observing transport changes. Caller supplies the `CoroutineScope` to launch
         * reload work in (typically `Application`-scoped so it outlives ViewModel rebuilds).
         */
        fun start(scope: CoroutineScope) {
            if (callback != null) {
                Log.d(TAG, "Already observing — skipping duplicate start()")
                return
            }
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        // The callback fires for every network matching NET_CAPABILITY_INTERNET,
                        // not just the default route — classify by activeNetwork so a cellular
                        // backup network's capability update doesn't masquerade as a transport
                        // change while Wi-Fi is still the default.
                        applyTransport(snapshotTransport(), scope)
                    }

                    override fun onLost(network: Network) {
                        if (connectivityManager.activeNetwork == null) {
                            applyTransport(CurrentTransport.NONE, scope)
                        }
                    }
                }
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            try {
                connectivityManager.registerNetworkCallback(request, cb)
                callback = cb
                // Seed the reactive view at start() so the StateFlow's initial value
                // reflects reality before the first capability callback fires. Without
                // this, collectors that subscribe between start() and the first callback
                // would briefly observe NONE even on a Wi-Fi-connected device. We do NOT
                // touch `lastTransport` here — leaving it null preserves the
                // "first callback always emits a transition" contract that
                // `applyTransport()` relies on (boot-on-cellular drops a wifi-only
                // AutoInterface immediately, not on the first carrier change).
                _currentTransport.value = currentTransportOf(connectivityManager)
                Log.d(TAG, "Transport observer started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register transport observer", e)
            }
        }

        /**
         * Stop observing. Idempotent.
         */
        fun stop() {
            callback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering transport observer", e)
                }
            }
            callback = null
            lastTransport = null
            reloadJob?.cancel()
            reloadJob = null
            // Deliberately do NOT reset `_currentTransport` to NONE here — `stop()` runs
            // at app teardown, and a NONE blip on the StateFlow would lie to any UI still
            // alive (e.g. a Compose recomposition flushing during shutdown).
        }

        /**
         * Read the device's current transport without registering a callback. Used by
         * one-shot config-build sites (`StartupConfigLoader`, `applyInterfaceChanges`,
         * `syncNativeInterfaces`) where a synchronous read is wanted instead of collecting
         * the [currentTransport] Flow.
         */
        fun snapshotTransport(): CurrentTransport = currentTransportOf(connectivityManager)

        private fun applyTransport(
            transport: CurrentTransport,
            scope: CoroutineScope,
        ) {
            if (transport == lastTransport) return
            val previous = lastTransport
            lastTransport = transport
            _currentTransport.value = transport
            Log.i(TAG, "Transport changed: $previous → $transport — re-filtering interfaces")
            // Cancel any in-flight reload — the latest transport wins.
            reloadJob?.cancel()
            reloadJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        val configs = interfaceRepository.enabledInterfaces.first()
                        val filtered = filterByTransport(configs, transport)
                        Log.d(
                            TAG,
                            "Reload-on-transport: ${configs.size} enabled → ${filtered.size} active for $transport",
                        )
                        reticulumProtocol.reloadInterfaces(filtered)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload interfaces on transport change", e)
                    }
                }
        }
    }
