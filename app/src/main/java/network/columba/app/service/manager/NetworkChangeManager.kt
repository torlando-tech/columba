package network.columba.app.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network connectivity changes and triggers lock reacquisition.
 *
 * When network connectivity changes (WiFi reconnects, mobile data switches, etc.),
 * Android may release or invalidate the wake locks and multicast locks that the
 * service depends on. This manager detects those changes and ensures locks are
 * reacquired.
 *
 * Additionally, triggers an LXMF announce on network changes so that peers can
 * discover this device on the new network.
 *
 * Inspired by Sideband's carrier change detection pattern.
 *
 * Per-interface network restrictions (Wi-Fi only / cellular only) are enforced by
 * a separate observer in the main process (see `InterfaceTransportObserver`); this
 * manager runs in the `:reticulum` service process and stays focused on lock/announce
 * concerns. Both observers monitor the same `ConnectivityManager` independently —
 * each `NetworkCallback` fires per-process, so duplication is unavoidable.
 */
class NetworkChangeManager(
    private val context: Context,
    private val lockManager: LockManager,
    private val onNetworkChanged: () -> Unit = {},
    private val onTransportChanged: (CurrentTransport) -> Unit = {},
) {
    companion object {
        private const val TAG = "NetworkChangeManager"
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    private var lastNetworkId: String? = null

    // Last-emitted transport, used to suppress duplicate `onTransportChanged` callbacks
    // when capabilities update without actually changing the transport class. Initialised
    // to null so the first observed transport always fires (including NONE-on-startup).
    private var lastTransport: CurrentTransport? = null

    /**
     * Start monitoring network changes.
     * Safe to call multiple times - previous callback will be unregistered first.
     */
    fun start() {
        if (isMonitoring) {
            stop()
        }

        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkId = network.toString()
                    Log.d(TAG, "Network available: $networkId (previous: $lastNetworkId)")

                    // Trigger on first connection OR network switch.
                    // First-connection case (lastNetworkId == null) handles the scenario where
                    // the app starts without WiFi and later connects — AutoInterface needs to
                    // scan for the new network interface. The caller guards against premature
                    // invocation before Reticulum is initialized.
                    if (lastNetworkId == null || lastNetworkId != networkId) {
                        Log.i(TAG, "Network changed - reacquiring locks and triggering announce")
                        handleNetworkChange()
                    }
                    lastNetworkId = networkId
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    // If no default network remains, emit NONE so transport-restricted
                    // interfaces detach. ConnectivityManager.activeNetwork goes null only
                    // after the OS finishes the disconnection, so check it here.
                    if (connectivityManager.activeNetwork == null) {
                        emitTransportIfChanged(CurrentTransport.NONE)
                    }
                    // Don't clear lastNetworkId here - we want to detect when a new network connects
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    // Log capability changes for debugging
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.v(TAG, "Network capabilities changed: internet=$hasInternet, validated=$isValidated")

                    // Compute transport class and emit if it changed since last emission.
                    // `onCapabilitiesChanged` fires after `onAvailable` and again whenever a
                    // capability flips (validation, metered, etc.) — the last-value cache
                    // collapses those into a single `onTransportChanged` per actual transport
                    // transition.
                    emitTransportIfChanged(currentTransportOf(networkCapabilities))
                }
            }

        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            isMonitoring = true
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stop monitoring network changes.
     * Safe to call multiple times or when not monitoring.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null
        isMonitoring = false
        lastNetworkId = null
        lastTransport = null
    }

    /**
     * Check if network monitoring is active.
     */
    fun isMonitoring(): Boolean = isMonitoring

    /**
     * Handle network change by reacquiring locks and notifying listeners.
     */
    private fun handleNetworkChange() {
        // Reacquire all locks to ensure they're valid on the new network
        try {
            lockManager.acquireAll()
            Log.d(TAG, "Locks reacquired after network change")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reacquire locks after network change", e)
        }

        // Trigger callback for additional handling (e.g., LXMF announce)
        try {
            onNetworkChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Error in network change callback", e)
        }
    }

    private fun emitTransportIfChanged(transport: CurrentTransport) {
        if (transport == lastTransport) return
        lastTransport = transport
        try {
            onTransportChanged(transport)
        } catch (e: Exception) {
            Log.e(TAG, "Error in transport change callback", e)
        }
    }
}
