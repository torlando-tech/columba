@file:Suppress("MatchingDeclarationName") // file groups CurrentTransport + filterByTransport + helpers; the filter is the focus

package network.columba.app.rns.host.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction

/**
 * The Android transport class the device's currently-active default network reports.
 * `ETHERNET` is bucketed with `WIFI_LIKE` because the user-facing "is this Wi-Fi or
 * cellular?" question collapses both to non-cellular.
 */
enum class CurrentTransport {
    /** Wi-Fi or Ethernet — the local link is non-cellular. */
    WIFI_LIKE,

    /** Cellular (mobile data). */
    CELLULAR,

    /**
     * A live default network exists, but Android's callback-visible capabilities do not
     * identify a Wi-Fi / Ethernet / cellular underlay. VPN-only defaults land here.
     */
    UNKNOWN,

    /** No active default network. */
    NONE,
}

/**
 * Filters a list of `InterfaceConfig`s down to the ones that should be active given the
 * device's current transport. Non-IP interfaces (`AndroidBLE`, and `RNode` connected
 * over Bluetooth/USB rather than TCP) bypass the filter entirely — they don't ride on
 * the IP carrier so the restriction is meaningless for them.
 *
 * For NONE (no active network), no IP interface is allowed regardless of restriction —
 * starting a TCP/UDP socket on a vanished route would just churn until reconnect.
 */
fun filterByTransport(
    configs: List<InterfaceConfig>,
    transport: CurrentTransport,
): List<InterfaceConfig> = configs.filter { config -> config.passesTransport(transport) }

private fun InterfaceConfig.passesTransport(transport: CurrentTransport): Boolean {
    if (!ridesOnIpCarrier()) return true
    if (transport == CurrentTransport.NONE) return false
    return when (networkRestriction) {
        NetworkRestriction.ANY -> true
        NetworkRestriction.WIFI_ONLY -> transport == CurrentTransport.WIFI_LIKE
        NetworkRestriction.CELLULAR_ONLY -> transport == CurrentTransport.CELLULAR
    }
}

/**
 * Whether this interface's connection rides on Android's IP carrier (and therefore needs
 * to honour the transport restriction). RNode is multi-modal: only `tcp` mode rides IP;
 * Bluetooth and USB are out-of-band and ignore the restriction.
 *
 * Public so the UI-side mirror in `InterfaceManagementUtils.entityRidesOnIpCarrier` (in the
 * `:app` module) can pin its truth table against this one in a unit test (see
 * `entityRidesOnIpCarrier_truthTable_matchesInterfaceTransportFilter`). `internal` won't
 * cross the `:rns-host` → `:app` module boundary, so the drift pin requires it be public.
 * The two predicates MUST stay aligned — the UI claims an interface is restricted iff the
 * runtime filter would actually drop it.
 */
fun InterfaceConfig.ridesOnIpCarrier(): Boolean =
    when (this) {
        is InterfaceConfig.AutoInterface -> true
        is InterfaceConfig.TCPClient -> true
        is InterfaceConfig.TCPServer -> true
        is InterfaceConfig.UDP -> true
        is InterfaceConfig.AndroidBLE -> false
        is InterfaceConfig.RNode -> connectionMode == "tcp"
    }

/**
 * Snapshot the currently active default network. A missing default route maps to `NONE`;
 * a live default whose capabilities are not yet published maps to `UNKNOWN`.
 */
fun currentTransportOf(connectivityManager: ConnectivityManager): CurrentTransport {
    val activeNetwork = connectivityManager.activeNetwork ?: return CurrentTransport.NONE
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return CurrentTransport.UNKNOWN
    return currentTransportOf(capabilities)
}

/**
 * Map a `NetworkCapabilities` instance to the closest matching `CurrentTransport`.
 * Wi-Fi and Ethernet collapse to `WIFI_LIKE`; cellular maps to `CELLULAR`. If Android
 * exposes only VPN or another unsupported transport on the live default network,
 * classify that as `UNKNOWN` so unrestricted IP interfaces stay alive without falsely
 * enabling Wi-Fi-only or cellular-only ones. `NONE` is reserved for no live default.
 */
fun currentTransportOf(capabilities: NetworkCapabilities): CurrentTransport =
    when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CurrentTransport.WIFI_LIKE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CurrentTransport.WIFI_LIKE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CurrentTransport.CELLULAR
        else -> CurrentTransport.UNKNOWN
    }

/**
 * Convenience overload reading `ConnectivityManager` from a `Context`.
 */
fun currentTransportOf(context: Context): CurrentTransport {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return CurrentTransport.NONE
    return currentTransportOf(cm)
}
