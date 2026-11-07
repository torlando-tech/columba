package network.columba.app.navigation

import android.net.Uri
import androidx.navigation.NavController

/**
 * Switches the bottom navigation bar to [tab].
 *
 * Every tab but NomadNet uses the standard saveState/restoreState pattern:
 * the stack being left is saved under its root and the destination tab's
 * saved stack is restored.
 *
 * The NomadNet tab deliberately opts out of save/restore. androidx keys a
 * saved stack by the unique id of its bottom entry and `restoreState`
 * restores the oldest saved stack matching the navigation target, so once
 * the tab has been saved twice a restore resurrects the *earlier* session
 * underneath the newer one - a stale site on the tab while a different
 * site is the live session. Instead the NomadNet stack is dropped (never
 * saved) when the tab is left, and returning shows the tab home, which is a
 * chooser (address field, "Continue" for the last page, saved sites) rather
 * than an auto-loading session. The site itself survives that: every page
 * load persists its node and path, so the home's Continue row reopens it.
 *
 * Re-tapping the NomadNet tab while browsing on it lands on the tab home,
 * the standard "the tab icon goes to its root" behaviour; the browser that was
 * on top is dropped, not ended (Back inside the browser is a plain pop).
 */
fun NavController.navigateToTab(tab: NavTab) {
    if (currentDestination?.route?.startsWith("nomadnet") == true) {
        if (tab == NavTab.NOMADNET) {
            // Already on the tab: reveal its root (the home). The browser is
            // always pushed on top of it, so this is a plain back to the list.
            if (currentDestination?.route != AppDestination.NOMADNET_HOME.routePattern) {
                popBackStack(AppDestination.NOMADNET_HOME.routePattern, inclusive = false)
            }
            return
        }
        // Leaving the NomadNet stack: drop it without saving it, per the KDoc.
        popBackStack(AppDestination.NOMADNET_HOME.routePattern, inclusive = true)
    }
    navigate(tab.tabRoute) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = tab != NavTab.NOMADNET
    }
}

/**
 * Opens the NomadNet page [path] on node [nodeHash] as the NomadNet tab's
 * session, from anywhere (node detail's "Browse Site", a `nomadnetwork://`
 * deep link, a QR scan).
 *
 * The browser entry always lives on the NomadNet tab's stack —
 * [AppDestination.NOMADNET_HOME] root with [AppDestination.NOMADNET_BROWSER]
 * on top — never pushed onto the calling tab's stack. Entering it is a tab
 * switch: the calling tab's stack (e.g. contacts -> node detail) is saved
 * under its own root, so tapping that tab later reveals exactly where the
 * user left off. The NomadNet stack itself is never saved (see
 * [navigateToTab]); the site survives through the persisted last node/path,
 * which the tab home offers as its Continue row.
 * Closing the site pops the browser to reveal the tab home beneath it.
 *
 * One site per tab, like upstream NomadNet: opening a page replaces the
 * previous session rather than stacking under it, and re-opening the exact
 * same entity is a no-op on the live session (no reload, no new entry).
 */
fun NavController.openNomadNetPage(
    nodeHash: String,
    path: String,
    restoreSession: Boolean = false,
) {
    if (isShowingBrowserEntity(nodeHash, path)) {
        return
    }
    if (currentDestination?.route?.startsWith("nomadnet") == true) {
        // Already on the NomadNet stack: drop the current site, keep the home root.
        popBackStack(AppDestination.NOMADNET_HOME.routePattern, inclusive = false)
    } else {
        // Tab switch into the NomadNet stack. Deliberately no restoreState:
        // a stale saved session must not resurface underneath the
        // newly-requested page; the fresh home is replaced as the top entry.
        navigate(AppDestination.NOMADNET_HOME.routePattern) {
            popUpTo(graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
        }
    }
    navigateToEntity(
        destination = AppDestination.NOMADNET_BROWSER,
        // restoreSession is not part of the entity identity (a page is the same
        // page either way): it only asks the browser to serve the in-memory
        // session snapshot instead of re-fetching.
        route = "nomadnet_browser/${Uri.encode(nodeHash)}?path=${Uri.encode(path)}&restoreSession=$restoreSession",
        identityArguments =
            mapOf(
                "destinationHash" to nodeHash,
                "path" to path,
            ),
    )
}

/** True when the top entry is the browser showing exactly this node and path. */
private fun NavController.isShowingBrowserEntity(
    nodeHash: String,
    path: String,
): Boolean {
    val arguments = currentBackStackEntry?.arguments
    return currentBackStackEntry?.destination?.route == AppDestination.NOMADNET_BROWSER.routePattern &&
        arguments?.getString("destinationHash") == nodeHash &&
        arguments?.getString("path") == path
}
