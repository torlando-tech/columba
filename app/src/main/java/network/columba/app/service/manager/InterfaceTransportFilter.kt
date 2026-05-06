@file:Suppress("MatchingDeclarationName") // file groups CurrentTransport + filterByTransport + helpers; the filter is the focus

package network.columba.app.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import network.columba.app.reticulum.model.InterfaceConfig
import network.columba.app.reticulum.model.NetworkRestriction

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
 */
private fun InterfaceConfig.ridesOnIpCarrier(): Boolean =
    when (this) {
        is InterfaceConfig.AutoInterface -> true
        is InterfaceConfig.TCPClient -> true
        is InterfaceConfig.TCPServer -> true
        is InterfaceConfig.UDP -> true
        is InterfaceConfig.AndroidBLE -> false
        is InterfaceConfig.RNode -> connectionMode == "tcp"
    }

/**
 * Snapshot the device's current transport from the system `ConnectivityManager`. Returns
 * `NONE` if no default network is active or capabilities are unavailable.
 */
fun currentTransportOf(connectivityManager: ConnectivityManager): CurrentTransport {
    val active = connectivityManager.activeNetwork ?: return CurrentTransport.NONE
    val caps = connectivityManager.getNetworkCapabilities(active) ?: return CurrentTransport.NONE
    return currentTransportOf(caps)
}

/**
 * Map a `NetworkCapabilities` instance to the closest matching `CurrentTransport`. Wi-Fi
 * and Ethernet collapse to `WIFI_LIKE`; cellular maps to `CELLULAR`; anything else (e.g.
 * `TRANSPORT_VPN` alone, `TRANSPORT_BLUETOOTH` tether) falls through to `NONE`.
 *
 * For VPN over an underlying transport (the common case), Android typically reports both
 * `TRANSPORT_VPN` and the underlying transport on the same `NetworkCapabilities` — so the
 * underlying-transport check still wins, matching Tyler's intent in the plan's open
 * questions ("VPN behavior: underlying transport").
 */
fun currentTransportOf(capabilities: NetworkCapabilities): CurrentTransport =
    when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CurrentTransport.WIFI_LIKE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CurrentTransport.WIFI_LIKE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CurrentTransport.CELLULAR
        else -> CurrentTransport.NONE
    }

/**
 * Convenience overload reading `ConnectivityManager` from a `Context`.
 */
fun currentTransportOf(context: Context): CurrentTransport {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return CurrentTransport.NONE
    return currentTransportOf(cm)
}
