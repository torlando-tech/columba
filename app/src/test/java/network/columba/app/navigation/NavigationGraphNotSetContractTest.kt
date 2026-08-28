package network.columba.app.navigation

import android.app.Application
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for Sentry COLUMBA-8Y: cold-start race where the
 * `LaunchedEffect(callState)` in ColumbaNavigation observes an already-Incoming
 * call state and calls [NavController.navigateToIncomingCall] before the NavHost
 * has attached its navigation graph. `NavController.navigate(route)` then throws:
 *
 *   IllegalArgumentException: Cannot navigate to <route>. Navigation graph has not
 *   been set for NavController <this>.
 *
 * This test drives the production [NavController.navigateToIncomingCall] on a
 * fresh NavController whose graph is never set — the exact Sentry state.
 *
 * NOTE for the fixer: on androidx.navigation 2.8.4 the `NavController.graph`
 * getter ITSELF throws `IllegalStateException("You must call setGraph() before
 * calling getGraph()")` while the graph is unset — it never returns null. A
 * guard of the form `if (graph == null || ...)` would crash instead of fixing
 * the race. The guard must use a graph-less check (e.g. `currentDestination` is
 * null and no start destination has been committed, or `currentBackStackEntry`
 * is null) rather than reading `NavController.graph` unguarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationGraphNotSetContractTest {
    @Test
    fun `navigateToIncomingCall without a navigation graph is a no-op instead of throwing`() {
        // A fresh NavController with no setGraph()/NavHost — reproduces the
        // cold-start window between Activity creation and graph attachment.
        val controller = NavController(ApplicationProvider.getApplicationContext())
        assertTrue(
            "Precondition: empty back stack must yield null currentDestination",
            controller.currentDestination == null,
        )

        // RED (pre-fix): the VOICE_CALL guard passes (currentDestination is
        // null), navigate(route) reaches NavController.navigate with an unset
        // graph and throws IllegalArgumentException with the literal
        // "Navigation graph has not been set for NavController".
        // GREEN (post-fix): the graph-not-set guard skips the navigation.
        controller.navigateToIncomingCall("incoming_call/0123456789abcdef")

        assertNull(
            "Skipping must leave the back stack empty (no navigation attempted)",
            controller.currentDestination,
        )
    }
}
