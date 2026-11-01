package network.columba.app.navigation

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Contract that a NomadNet site session belongs to the NomadNet tab's stack,
 * never to the stack of the tab that opened it.
 *
 * Opening a page from Contacts (node detail's "Browse Site", or a
 * `nomadnetwork://` deep link) must switch into the NomadNet tab with the
 * page loaded, leaving the Contacts flow (node detail on top) saved under the
 * Contacts tab. Tapping Contacts then reveals the node detail page the site
 * was opened from; tapping NomadNet returns to the live browser session; and
 * closing the site pops to the NomadNet tab home, not into another tab's
 * screens. Ending the session (Back or Close Site) marks the home entry, so
 * the revealed home shows the address-entry prompt instead of auto-reloading
 * the site that was just ended - and the ended state survives a tab
 * round-trip.
 *
 * A plain [ViewModel] with a mutable field stands in for the entry-scoped
 * [network.columba.app.viewmodel.NomadNetBrowserViewModel] (same
 * ViewModelStoreOwner binding, same save/restore semantics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NomadNetBrowserTabOwnershipTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var currentBrowserVm: TestBrowserViewModel

    class TestBrowserViewModel : ViewModel() {
        var page: String = "Initial"
    }

    class TestBrowserViewModelFactory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TestBrowserViewModel() as T
        }
    }

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = AppDestination.CHATS.routePattern,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                appComposable(AppDestination.CHATS) { }
                appComposable(AppDestination.CONTACTS) { }
                appComposable(
                    AppDestination.ANNOUNCE_DETAIL,
                    arguments = listOf(navArgument("destinationHash") { type = NavType.StringType }),
                ) { }
                appComposable(AppDestination.NOMADNET_HOME) { entry ->
                    // Mirrors the production home: reopens the last-browsed
                    // site unless the session was explicitly ended.
                    val sessionEnded =
                        entry.savedStateHandle
                            .getStateFlow(KEY_NOMADNET_SESSION_ENDED, false)
                            .collectAsState()
                            .value
                    Text(if (sessionEnded) ADDRESS_PROMPT else LAST_SITE)
                }
                appComposable(
                    AppDestination.NOMADNET_BROWSER,
                    arguments =
                        listOf(
                            navArgument("destinationHash") { type = NavType.StringType },
                            navArgument("path") {
                                type = NavType.StringType
                                defaultValue = "/page/index.mu"
                            },
                        ),
                ) {
                    val vm: TestBrowserViewModel = viewModel(factory = TestBrowserViewModelFactory())
                    currentBrowserVm = vm
                }
            }
        }
        composeRule.waitForIdle()
        idleMainLooper()
    }

    @Test
    fun `opening a site from contacts lands on the nomadnet stack with home beneath it`() {
        openSiteFromContacts(NODE_A)

        assertEquals(AppDestination.NOMADNET_BROWSER.routePattern, navController.currentDestination?.route)
        val routes = backStackRoutes()
        assertEquals(
            "browser must sit on the NomadNet tab's own home, with the Contacts flow saved away",
            listOf(
                AppDestination.CHATS.routePattern,
                AppDestination.NOMADNET_HOME.routePattern,
                AppDestination.NOMADNET_BROWSER.routePattern,
            ),
            routes.filterNotNull(),
        )
    }

    @Test
    fun `tapping Contacts while browsing reveals the node detail page the site was opened from`() {
        openSiteFromContacts(NODE_A)

        runOnMainThread { navController.navigateToTab(NavTab.CONTACTS) }
        idleMainLooper()
        composeRule.waitForIdle()

        assertEquals(
            "Contacts tab must reveal the saved node detail, not stay on the site or the list",
            AppDestination.ANNOUNCE_DETAIL.routePattern,
            navController.currentDestination?.route,
        )
    }

    @Test
    fun `tapping NomadNet returns to the live browser session`() {
        openSiteFromContacts(NODE_A)
        val vmBefore = currentBrowserVm
        vmBefore.page = "site-A-index"

        runOnMainThread { navController.navigateToTab(NavTab.CONTACTS) }
        idleMainLooper()
        runOnMainThread { navController.navigateToTab(NavTab.NOMADNET) }
        idleMainLooper()
        composeRule.waitForIdle()

        assertEquals(AppDestination.NOMADNET_BROWSER.routePattern, navController.currentDestination?.route)
        assertSame(
            "the NomadNet tab must restore the live session, not build a fresh home",
            vmBefore,
            currentBrowserVm,
        )
        assertEquals("site-A-index", currentBrowserVm.page)
    }

    @Test
    fun `closing the site pops to the nomadnet home showing the address prompt`() {
        openSiteFromContacts(NODE_A)

        // onCloseSite for the browser entry is endNomadNetSessionAndPop().
        runOnMainThread { navController.endNomadNetSessionAndPop() }
        idleMainLooper()
        composeRule.waitForIdle()

        assertEquals(AppDestination.NOMADNET_HOME.routePattern, navController.currentDestination?.route)
        composeRule.onNodeWithText(ADDRESS_PROMPT).assertIsDisplayed()
        composeRule.onNodeWithText(LAST_SITE).assertDoesNotExist()
    }

    @Test
    fun `an ended session stays ended across a tab round-trip`() {
        openSiteFromContacts(NODE_A)
        runOnMainThread { navController.endNomadNetSessionAndPop() }
        idleMainLooper()

        runOnMainThread { navController.navigateToTab(NavTab.CONTACTS) }
        idleMainLooper()
        runOnMainThread { navController.navigateToTab(NavTab.NOMADNET) }
        idleMainLooper()
        composeRule.waitForIdle()

        assertEquals(AppDestination.NOMADNET_HOME.routePattern, navController.currentDestination?.route)
        composeRule
            .onNodeWithText(ADDRESS_PROMPT)
            .assertIsDisplayed()
    }

    @Test
    fun `re-opening the same site is a no-op on the live session`() {
        openSiteFromContacts(NODE_A)
        val vmBefore = currentBrowserVm

        runOnMainThread { navController.openNomadNetPage(NODE_A, INDEX_PATH) }
        idleMainLooper()
        composeRule.waitForIdle()

        assertSame("re-delivering the same entity must not recreate the session", vmBefore, currentBrowserVm)
        assertEquals(
            "exactly one browser entry must exist",
            1,
            backStackRoutes().count { it == AppDestination.NOMADNET_BROWSER.routePattern },
        )
    }

    @Test
    fun `opening a different site replaces the session instead of stacking`() {
        openSiteFromContacts(NODE_A)

        runOnMainThread { navController.openNomadNetPage(NODE_B, INDEX_PATH) }
        idleMainLooper()
        composeRule.waitForIdle()

        assertEquals(AppDestination.NOMADNET_BROWSER.routePattern, navController.currentDestination?.route)
        assertEquals(
            "one site per tab: the old session must be replaced, not stacked under the new one",
            1,
            backStackRoutes().count { it == AppDestination.NOMADNET_BROWSER.routePattern },
        )
        assertEquals(NODE_B, navController.currentBackStackEntry?.arguments?.getString("destinationHash"))
    }

    /** Contacts tab -> node detail for [nodeHash] -> "Browse Site". */
    private fun openSiteFromContacts(nodeHash: String) {
        runOnMainThread { navController.navigateToTab(NavTab.CONTACTS) }
        idleMainLooper()
        runOnMainThread { navController.navigate("announce_detail/$nodeHash") }
        idleMainLooper()
        runOnMainThread { navController.openNomadNetPage(nodeHash, INDEX_PATH) }
        idleMainLooper()
        composeRule.waitForIdle()
    }

    private fun backStackRoutes(): List<String?> =
        navController.currentBackStack.value?.map { entry -> entry.destination.route } ?: emptyList()

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runOnMainThread(block: () -> Unit) {
        composeRule.runOnUiThread(block)
    }

    private companion object {
        const val NODE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NODE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val INDEX_PATH = "/page/index.mu"
        const val ADDRESS_PROMPT = "address-entry-prompt"
        const val LAST_SITE = "last-browsed-site"
    }
}
