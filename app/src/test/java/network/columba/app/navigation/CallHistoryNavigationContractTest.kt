package network.columba.app.navigation

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import network.columba.app.R
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.ui.screens.CallDetailsContent
import network.columba.app.ui.screens.VoiceHistoryContent
import network.columba.app.viewmodel.BlockLookupState
import network.columba.app.viewmodel.CallDetailsState
import network.columba.app.viewmodel.VoiceHistoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallHistoryNavigationContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private val record =
        CallHistoryRecord(
            callAttemptId = "attempt/with spaces",
            localIdentityHash = LOCAL_IDENTITY,
            remoteIdentityHash = REMOTE_IDENTITY,
            direction = "OUTGOING",
            peerDisplayNameSnapshot = "Peer",
            codecProfileCode = 2,
            attemptedAt = 100L,
            ringingAt = 110L,
            connectedAt = 120L,
            endedAt = 200L,
            outcome = "CONNECTED_ENDED",
            inferredEnding = false,
            failureReason = null,
            displayName = "Peer",
            currentDestinationHash = REMOTE_IDENTITY,
            localIdentityName = "Local",
        )

    @Before
    fun setUp() {
        composeRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = AppDestination.CHATS.routePattern) {
                    composable(AppDestination.CHATS.routePattern) {
                        VoiceHistoryContent(
                            state = VoiceHistoryState(records = listOf(record), isLoading = false),
                            onRecordClick = { navController.navigate(callDetailsRoute(it.callAttemptId)) },
                        )
                    }
                    callDetailsDestination(navController) { onBack, onCallAgain, onViewPeer ->
                        CallDetailsContent(
                            state =
                                CallDetailsState(
                                    record = record,
                                    isLoading = false,
                                    canStartCall = true,
                                    localIdentityMatches = true,
                                    blockState = BlockLookupState.UNBLOCKED,
                                ),
                            onBack = onBack,
                            onCallAgain = { destination, profile -> onCallAgain(destination, profile, LOCAL_IDENTITY) },
                            onViewPeer = onViewPeer,
                            onToggleBlocked = {},
                            onDelete = {},
                        )
                    }
                    composable("announce_detail/{destinationHash}") { }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `call details opens from a voice history record and back returns to chat`() {
        composeRule.onNodeWithText("Peer", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("attempt/with spaces", navController.currentBackStackEntry?.arguments?.getString("callAttemptId"))
        assertEquals(AppDestination.CALL_DETAILS.routePattern, navController.currentDestination?.route)

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertEquals(AppDestination.CHATS.routePattern, navController.currentDestination?.route)
    }

    @Test
    fun `call-again route preserves caller identity authority`() {
        val route = callAgainRoute(record.remoteIdentityHash, 2, record.localIdentityHash)
        val parsed = Uri.parse(route)
        assertTrue(route.startsWith("voice_call/$REMOTE_IDENTITY"))
        assertEquals("false", parsed.getQueryParameter("autoAnswer"))
        assertEquals(LOCAL_IDENTITY, parsed.getQueryParameter(EXPECTED_LOCAL_IDENTITY_ARGUMENT))
    }

    @Test
    fun `active call route carries exact attempt and local identity authority`() {
        val route = activeCallRoute(record.callAttemptId, record.remoteIdentityHash, 2, record.localIdentityHash)
        val parsed = Uri.parse(route)
        assertTrue(route.contains("$ACTIVE_ONLY_ARGUMENT=true"))
        assertEquals(record.callAttemptId, parsed.getQueryParameter(EXPECTED_CALL_ATTEMPT_ID_ARGUMENT))
        assertEquals(LOCAL_IDENTITY, parsed.getQueryParameter(EXPECTED_LOCAL_IDENTITY_ARGUMENT))
        assertTrue(route.startsWith("voice_call/$REMOTE_IDENTITY"))
    }

    @Test
    fun `peer details back returns to the exact call details record`() {
        composeRule.runOnUiThread { navController.navigate(callDetailsRoute(record.callAttemptId)) }
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(10)
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.call_details_view_peer))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("announce_detail/{destinationHash}", navController.currentDestination?.route)

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertEquals(AppDestination.CALL_DETAILS.routePattern, navController.currentDestination?.route)
        assertEquals(record.callAttemptId, navController.currentBackStackEntry?.arguments?.getString("callAttemptId"))
    }

    private companion object {
        const val LOCAL_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REMOTE_IDENTITY = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
