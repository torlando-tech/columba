package network.columba.app.rns.host.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private var currentDefaultNetwork: Network? = null

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
                    val previous = currentDefaultNetwork
                    currentDefaultNetwork = network
                    Log.d(TAG, "Default network available: $network (previous: $previous)")

                    // Trigger on first connection OR default-network switch.
                    // First-connection case (previous == null) handles the scenario where
                    // the app starts without WiFi and later connects — AutoInterface needs to
                    // scan for the new network interface. The caller guards against premature
                    // invocation before Reticulum is initialized.
                    if (previous == null || previous != network) {
                        Log.i(TAG, "Network changed - reacquiring locks and triggering announce")
                        handleNetworkChange()
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    // Only the current default route can drive transport loss. If another
                    // callback already promoted a replacement default, ignore this stale loss.
                    if (currentDefaultNetwork == network) {
                        currentDefaultNetwork = null
                        emitTransportIfChanged(CurrentTransport.NONE)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    // Log capability changes for debugging
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.v(TAG, "Network capabilities changed: internet=$hasInternet, validated=$isValidated")

                    // Only the current default route can drive transport classification.
                    if (network == currentDefaultNetwork) {
                        emitTransportIfChanged(currentTransportOf(networkCapabilities))
                    }
                }
            }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
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
        currentDefaultNetwork = null
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
