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
 * saved) when the tab is left, and returning shows a fresh tab home. The
 * session itself survives that: every page load persists its node and
 * path, and the home reopens the last page from the page cache, so the
 * tab still shows the site the user left.
 *
 * Re-tapping the NomadNet tab while already browsing is a no-op; the site
 * session ends via Close Site or Back, not by re-tapping the tab.
 */
fun NavController.navigateToTab(tab: NavTab) {
    if (currentDestination?.route?.startsWith("nomadnet") == true) {
        if (tab == NavTab.NOMADNET) {
            // Already browsing; the site session ends via Close Site or Back,
            // not by re-tapping the tab.
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
 * [navigateToTab]); the session survives through the persisted last
 * node/path, which the tab home reopens when the tab is tapped again.
 * Closing the site pops the browser to reveal the tab home beneath it.
 *
 * One site per tab, like upstream NomadNet: opening a page replaces the
 * previous session rather than stacking under it, and re-opening the exact
 * same entity is a no-op on the live session (no reload, no new entry).
 */
fun NavController.openNomadNetPage(
    nodeHash: String,
    path: String,
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
        route = "nomadnet_browser/${Uri.encode(nodeHash)}?path=${Uri.encode(path)}",
        identityArguments =
            mapOf(
                "destinationHash" to nodeHash,
                "path" to path,
            ),
    )
}

/**
 * [androidx.lifecycle.SavedStateHandle] key on the NomadNet tab home entry:
 * set when a site session ends via Back or Close Site, so the revealed home
 * shows the address-entry prompt instead of auto-reopening the site that was
 * just ended. See [endNomadNetSessionAndPop].
 */
const val KEY_NOMADNET_SESSION_ENDED = "nomadnet_session_ended"

/**
 * Ends the current NomadNet site session: marks the NomadNet tab home beneath
 * the browser as session-ended, then pops the browser to reveal it.
 *
 * The home normally reopens the last-browsed node (tab restore, cold start).
 * An explicitly ended session overrides that reopen: without the mark the
 * home would immediately reload the same site and Back would look like a
 * no-op. The mark travels with the home entry through process death while
 * the tab stays open. Leaving the tab drops the home entry (see
 * [navigateToTab]), so on return the persisted last node/path decide what
 * the home reopens; Close Site clears them, keeping the session ended.
 */
fun NavController.endNomadNetSessionAndPop() {
    previousBackStackEntry?.savedStateHandle?.set(KEY_NOMADNET_SESSION_ENDED, true)
    popBackStack()
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
