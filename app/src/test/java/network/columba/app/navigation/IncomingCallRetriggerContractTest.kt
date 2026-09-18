package network.columba.app.navigation

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import network.columba.app.rns.api.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for COLUMBA-8Y (cold-start incoming-call navigation).
 *
 * `navigateToIncomingCall` no-ops (never throws) while the NavHost graph is not
 * yet attached. Because the `Incoming` StateFlow value does not change, the live
 * callState effect does NOT re-fire once the graph becomes live — so the caller must
 * retrigger the incoming-call navigation after the start destination commits. These tests
 * pin the retrigger predicate and prove that a retriggered navigate call reaches the
 * incoming-call screen once the graph is attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class IncomingCallRetriggerContractTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

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
                appComposable(AppDestination.INCOMING_CALL) { }
            }
        }
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `cold-start Incoming on an uncommitted route is eligible for retrigger`() {
        // currentRoute == null is exactly the graph-not-yet-attached window. The
        // retrigger must fire so the Incoming call is not silently discarded.
        assertTrue(
            shouldPresentIncomingCall(
                callState = CallState.Incoming("caller-a"),
                currentRoute = null,
                isAnsweringCall = false,
            ),
        )
    }

    @Test
    fun `already-presented incoming call is not retriggered`() {
        assertFalse(
            shouldPresentIncomingCall(
                callState = CallState.Incoming("caller-a"),
                currentRoute = "incoming_call/caller-a",
                isAnsweringCall = false,
            ),
        )
    }

    @Test
    fun `active voice call is not covered by an incoming retrigger`() {
        assertFalse(
            shouldPresentIncomingCall(
                callState = CallState.Incoming("caller-a"),
                currentRoute = "voice_call/caller-a?autoAnswer=true",
                isAnsweringCall = false,
            ),
        )
    }

    @Test
    fun `mid-answer navigation is not retriggered`() {
        assertFalse(
            shouldPresentIncomingCall(
                callState = CallState.Incoming("caller-a"),
                currentRoute = AppDestination.CHATS.routePattern,
                isAnsweringCall = true,
            ),
        )
    }

    @Test
    fun `non-Incoming call state is not retriggered`() {
        assertFalse(
            shouldPresentIncomingCall(
                callState = CallState.Idle,
                currentRoute = null,
                isAnsweringCall = false,
            ),
        )
    }

    @Test
    fun `navigateToIncomingCall after graph attach reaches the incoming-call screen`() {
        // Once the start destination has committed, the graph is live and a retriggered
        // navigate call lands on the incoming-call screen.
        composeRule.runOnUiThread {
            navController.navigateToIncomingCall("incoming_call/caller-a")
        }
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            AppDestination.INCOMING_CALL.routePattern,
            navController.currentDestination?.route,
        )
    }
}
