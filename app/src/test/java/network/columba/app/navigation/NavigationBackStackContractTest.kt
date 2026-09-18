package network.columba.app.navigation

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NavController-level contracts using placeholder destination content.
 *
 * These tests exercise the real Android Back dispatcher and navigation stack,
 * but screen-specific BackHandlers require separate UI/instrumented coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class NavigationBackStackContractTest {
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
                AppDestination.entries.forEach { destination ->
                    appComposable(
                        destination = destination,
                        arguments = testArguments(destination),
                    ) { }
                }
            }
        }
        composeRule.waitForIdle()
        idleMainLooper()
        assertCurrentDestination(AppDestination.CHATS)
    }

    @Test
    fun `Android Back returns every child destination to every top-level caller`() {
        val callers = AppDestination.entries.filter { it.backContract == BackContract.TOP_LEVEL }
        val children = AppDestination.entries.filter { it.backContract == BackContract.POP_TO_CALLER }

        callers.forEach { caller ->
            if (caller != AppDestination.CHATS) {
                navigateTo(caller.sampleRoute)
            }
            children.forEach { destination ->
                navigateTo(destination.sampleRoute)
                assertCurrentDestination(destination)

                pressAndroidBack()

                assertCurrentDestination(
                    caller,
                    "Android Back from ${destination.name} must return to ${caller.name}",
                )
            }
            if (caller != AppDestination.CHATS) {
                pressAndroidBack()
                assertCurrentDestination(AppDestination.CHATS)
            }
        }
    }

    @Test
    fun `completing every registered flow removes it from the back stack`() {
        val flows = AppDestination.entries.filter { it.completionContract != CompletionContract.NONE }
        assertTrue("At least one completion flow must be registered", flows.isNotEmpty())

        flows.forEach { flow ->
            navigateTo(flow.sampleRoute)
            assertCurrentDestination(flow)

            runOnMainThread {
                navController.completeCurrentFlow(flow)
            }
            idleMainLooper()

            when (flow.completionContract) {
                CompletionContract.NONE -> error("Filtered above")
                CompletionContract.RETURN_TO_CALLER -> {
                    assertCurrentDestination(
                        AppDestination.CHATS,
                        "Completing ${flow.name} must return directly to its caller",
                    )
                }
                CompletionContract.SHOW_RESULT -> {
                    assertEquals(
                        "Completion target for ${flow.name}",
                        flow.completionTargetRoute,
                        navController.currentDestination?.route,
                    )
                    pressAndroidBack()
                    assertCurrentDestination(
                        AppDestination.CHATS,
                        "Android Back after completing ${flow.name} must not reveal the completed flow",
                    )
                }
            }
        }
    }

    @Test
    fun `flow completion reuses an existing result destination without duplication`() {
        AppDestination.entries
            .filter { it.completionTargetRoute != null }
            .forEach { flow ->
                val result = AppDestination.entries.single {
                    it.routePattern == flow.completionTargetRoute
                }
                navigateTo(result.sampleRoute)
                navigateTo(flow.sampleRoute)

                runOnMainThread {
                    navController.completeCurrentFlow(flow)
                }
                idleMainLooper()
                assertCurrentDestination(result)

                pressAndroidBack()

                assertCurrentDestination(
                    AppDestination.CHATS,
                    "Completing ${flow.name} must not duplicate ${result.name}",
                )
            }
    }

    @Test
    fun `flow completion removes intermediate destinations above an existing result`() {
        AppDestination.entries
            .filter { it.completionContract == CompletionContract.SHOW_RESULT }
            .forEach { flow ->
                val result = AppDestination.entries.single {
                    it.routePattern == flow.completionTargetRoute
                }
                navigateTo(result.sampleRoute)
                navigateTo(AppDestination.DISCOVERED_INTERFACES.sampleRoute)
                navigateTo(flow.sampleRoute)

                runOnMainThread {
                    navController.completeCurrentFlow(flow)
                }
                idleMainLooper()
                assertCurrentDestination(result)

                pressAndroidBack()

                assertCurrentDestination(
                    AppDestination.CHATS,
                    "Completing ${flow.name} must remove intermediate destinations above ${result.name}",
                )
            }
    }

    @Test
    fun `repeated external navigation to the same entity creates one Back destination`() {
        val destinations = AppDestination.entries.filter { it.externalIdentityArguments.isNotEmpty() }
        assertTrue("At least one external entity destination must be registered", destinations.isNotEmpty())

        destinations.forEach { destination ->
            runOnMainThread {
                navController.navigateToEntity(
                    destination = destination,
                    route = destination.sampleRoute,
                    identityArguments = destination.externalIdentityArguments,
                )
                navController.navigateToEntity(
                    destination = destination,
                    route = destination.sampleRoute,
                    identityArguments = destination.externalIdentityArguments,
                )
            }
            assertCurrentDestination(destination)

            pressAndroidBack()

            assertCurrentDestination(
                AppDestination.CHATS,
                "Repeated delivery of ${destination.name} must not require an ineffective extra Back press",
            )
        }
    }

    @Test
    fun `external navigation to a different entity preserves legitimate history`() {
        runOnMainThread {
            navController.navigateToEntity(
                destination = AppDestination.ANNOUNCE_DETAIL,
                route = "announce_detail/announce-a",
                identityArguments = mapOf("destinationHash" to "announce-a"),
            )
            navController.navigateToEntity(
                destination = AppDestination.ANNOUNCE_DETAIL,
                route = "announce_detail/announce-b",
                identityArguments = mapOf("destinationHash" to "announce-b"),
            )
        }
        assertBackStackArgument("destinationHash", "announce-b")

        pressAndroidBack()

        assertCurrentDestination(AppDestination.ANNOUNCE_DETAIL)
        assertBackStackArgument("destinationHash", "announce-a")
    }

    @Test
    fun `singleton external destination replaces a different entity payload`() {
        runOnMainThread {
            navController.navigateToEntity(
                destination = AppDestination.IDENTITY_MANAGER,
                route = "identity_manager?base32Key=key-a",
                identityArguments = mapOf("base32Key" to "key-a"),
            )
            navController.navigateToEntity(
                destination = AppDestination.IDENTITY_MANAGER,
                route = "identity_manager?base32Key=key-b",
                identityArguments = mapOf("base32Key" to "key-b"),
            )
        }
        assertCurrentDestination(AppDestination.IDENTITY_MANAGER)
        assertBackStackArgument("base32Key", "key-b")

        pressAndroidBack()

        assertCurrentDestination(AppDestination.CHATS)
    }

    @Test
    fun `repeated incoming call ingress replaces the current call destination`() {
        runOnMainThread {
            navController.navigateToIncomingCall("incoming_call/caller-a")
            navController.navigateToIncomingCall("incoming_call/caller-a")
            navController.navigateToIncomingCall("incoming_call/caller-b")
        }
        assertCurrentDestination(AppDestination.INCOMING_CALL)
        assertBackStackArgument("identityHash", "caller-b")

        pressAndroidBack()

        assertCurrentDestination(
            AppDestination.CHATS,
            "Repeated incoming-call producers must not leave stale call screens in history",
        )
    }

    @Test
    fun `answering a call removes incoming call and intermediate destinations`() {
        navigateTo("incoming_call/caller-a")
        navigateTo(AppDestination.ANNOUNCE_DETAIL.sampleRoute)

        runOnMainThread {
            navController.navigateToAnsweredCall("voice_call/caller-a?autoAnswer=true")
        }
        assertCurrentDestination(AppDestination.VOICE_CALL)

        pressAndroidBack()

        assertCurrentDestination(
            AppDestination.CHATS,
            "Back after answering must not expose an active incoming-call destination",
        )
    }

    @Test
    fun `answering an already visible voice call does not duplicate it`() {
        navigateTo("voice_call/caller-a?autoAnswer=false")

        runOnMainThread {
            navController.navigateToAnsweredCall("voice_call/caller-a?autoAnswer=true")
        }
        assertCurrentDestination(AppDestination.VOICE_CALL)

        pressAndroidBack()

        assertCurrentDestination(AppDestination.CHATS)
    }

    @Test
    fun `late incoming call ingress cannot cover an active voice call`() {
        navigateTo("voice_call/caller-a?autoAnswer=true")

        runOnMainThread {
            navController.navigateToIncomingCall("incoming_call/caller-a")
        }
        assertCurrentDestination(AppDestination.VOICE_CALL)

        pressAndroidBack()

        assertCurrentDestination(AppDestination.CHATS)
    }

    private fun testArguments(destination: AppDestination) =
        when (destination) {
            AppDestination.USB_DEVICE_ACTION,
            AppDestination.RNODE_WIZARD,
            AppDestination.RNODE_FLASHER,
            -> listOf(navArgument("usbDeviceId") { type = NavType.IntType })
            else -> emptyList()
        }

    private fun navigateTo(route: String) {
        runOnMainThread { navController.navigate(route) }
        idleMainLooper()
    }

    private fun pressAndroidBack() {
        runOnMainThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        idleMainLooper()
    }

    private fun runOnMainThread(block: () -> Unit) {
        composeRule.runOnUiThread(block)
        composeRule.waitForIdle()
        idleMainLooper()
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertCurrentDestination(
        expected: AppDestination,
        message: String = "Expected ${expected.name}",
    ) {
        assertEquals(message, expected.routePattern, navController.currentDestination?.route)
    }

    private fun assertBackStackArgument(
        name: String,
        expected: String,
    ) {
        assertEquals(expected, navController.currentBackStackEntry?.arguments?.getString(name))
    }
}
