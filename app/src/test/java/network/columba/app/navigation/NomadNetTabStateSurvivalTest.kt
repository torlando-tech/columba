package network.columba.app.navigation

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
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
 * Contract that the bottom-nav tab switch preserves the live NomadNet browser.
 *
 * The NomadNet browser keeps its entry-scoped [ViewModel] (and therefore its
 * in-memory page) alive when the user moves to another tab and comes back,
 * rather than tearing it down and re-fetching. This is verified by asserting
 * the *same* view-model instance (not just a restored saved-state handle) is
 * current after a tab round-trip, and that a loaded page marker it carried is
 * still there.
 *
 * A plain [ViewModel] with a mutable field stands in for the production
 * [network.columba.app.viewmodel.NomadNetBrowserViewModel], whose
 * `browserState` is a plain [androidx.lifecycle.MutableStateFlow]. Both are
 * entry-scoped via [androidx.hilt.navigation.compose.hiltViewModel] /
 * [viewModel] (both bind to the NavBackStackEntry's ViewModelStoreOwner), so
 * the save/restore semantics under test are identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NomadNetTabStateSurvivalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var currentVm: TestBrowserViewModel

    /** Entry-scoped stand-in for NomadNetBrowserViewModel. */
    class TestBrowserViewModel : ViewModel() {
        /** Mirrors the loaded page (path + document); plain field, like production. */
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
                appComposable(AppDestination.NOMADNET_HOME) {
                    // hiltViewModel() in production; plain viewModel() here binds
                    // to the same NavBackStackEntry ViewModelStoreOwner.
                    val vm: TestBrowserViewModel = viewModel(factory = TestBrowserViewModelFactory())
                    currentVm = vm
                }
            }
        }
        composeRule.waitForIdle()
        idleMainLooper()
        assertCurrentRoute(AppDestination.CHATS.routePattern)
    }

    @Test
    fun `leaving and returning to the NomadNet tab preserves the live browser view model`() {
        navigateTo(AppDestination.NOMADNET_HOME.routePattern)
        val vmBefore = currentVm
        vmBefore.page = "loaded-forum-thread"

        // Simulate the user tapping Chats, then tapping back to NomadNet.
        runOnMainThread { navController.navigateToTab(NavTab.CHATS) }
        idleMainLooper()
        runOnMainThread { navController.navigateToTab(NavTab.NOMADNET) }
        idleMainLooper()
        composeRule.waitForIdle()

        // The same entry-scoped view model must be current with its page intact:
        // no teardown, no re-fetch.
        assertSame(
            "tab round-trip must reuse the NomadNet view model, not recreate it",
            vmBefore,
            currentVm,
        )
        assertEquals("loaded-forum-thread", currentVm.page)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun navigateTo(route: String) {
        runOnMainThread { navController.navigate(route) }
        composeRule.waitForIdle()
        idleMainLooper()
    }

    private fun runOnMainThread(block: () -> Unit) {
        composeRule.runOnUiThread(block)
    }

    private fun assertCurrentRoute(expected: String) {
        assertEquals(expected, navController.currentDestination?.route)
    }
}
