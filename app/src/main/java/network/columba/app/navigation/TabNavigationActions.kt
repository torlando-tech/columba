package network.columba.app.navigation

import androidx.navigation.NavController

/**
 * Switches the bottom navigation bar to [tab] while preserving the state of
 * every top-level destination, including the NomadNet browser.
 *
 * The NomadNet browser keeps its live site session (the entry-scoped
 * [network.columba.app.viewmodel.NomadNetBrowserViewModel] and its in-memory
 * page) when the user moves to another tab and comes back: the entry is saved
 * and restored rather than popped, so the page the user left is still on
 * screen with no re-fetch.
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
    }
    navigate(tab.tabRoute) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
