package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.CallDetailsState
import network.columba.app.viewmodel.BlockLookupState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallDetailsScreenTest {
    private val activityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(activityRule).around(composeRule)

    @Test
    fun `details show durable evidence and invoke current destination actions`() {
        var callAgain: Pair<String, Int>? = null
        var viewedPeer: String? = null
        composeRule.setContent {
            CallDetailsContent(
                state = callReadyState(),
                onBack = {},
                onCallAgain = { destination, profile -> callAgain = destination to profile },
                onViewPeer = { viewedPeer = it },
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Connected then ended").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Call again"))
        composeRule.onNodeWithText("Call again").performScrollTo().performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("View peer details"))
        composeRule.onNodeWithText("View peer details").performClick()

        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" to 2, callAgain)
        assertEquals("cccccccccccccccccccccccccccccccc", viewedPeer)
    }

    @Test
    fun `identity call action remains available when current destination is unavailable`() {
        composeRule.setContent {
            CallDetailsContent(
                state = callReadyState().copy(record = record().copy(currentDestinationHash = null)),
                onBack = {},
                onCallAgain = { _, _ -> },
                onViewPeer = {},
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Call again"))
        composeRule.onNodeWithText("Call again").assertIsDisplayed()
        composeRule.onNodeWithText("View peer details").assertDoesNotExist()
    }

    @Test
    fun `unknown block authority disables call again`() {
        composeRule.setContent {
            CallDetailsContent(
                state = callReadyState().copy(blockState = BlockLookupState.LOADING),
                onBack = {},
                onCallAgain = { _, _ -> },
                onViewPeer = {},
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Call again"))
        composeRule.onNodeWithText("Call again").assertIsNotEnabled()
    }

    @Test
    fun `blocked identity disables call again`() {
        composeRule.setContent {
            CallDetailsContent(
                state = callReadyState().copy(blockState = BlockLookupState.BLOCKED, canStartCall = false),
                onBack = {},
                onCallAgain = { _, _ -> },
                onViewPeer = {},
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Call again"))
        composeRule.onNodeWithText("Call again").assertIsNotEnabled()
    }

    @Test
    fun `missing identity-scoped record fails closed`() {
        composeRule.setContent {
            CallDetailsContent(
                state = CallDetailsState(record = null, isLoading = false),
                onBack = {},
                onCallAgain = { _, _ -> },
                onViewPeer = {},
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNodeWithText("Call record not found").assertIsDisplayed()
    }

    @Test
    fun `projection error fails closed without stale actions`() {
        composeRule.setContent {
            CallDetailsContent(
                state = CallDetailsState(record = record(), isLoading = false, hasError = true),
                onBack = {},
                onCallAgain = { _, _ -> },
                onViewPeer = {},
                onToggleBlocked = {},
                onDelete = {},
            )
        }

        composeRule.onNodeWithText("Couldn’t load call details").assertIsDisplayed()
        composeRule.onNodeWithText("Call again").assertDoesNotExist()
    }

    private fun record() =
        CallHistoryRecord(
            callAttemptId = "attempt-details",
            localIdentityHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            remoteIdentityHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            direction = "OUTGOING",
            peerDisplayNameSnapshot = "Snapshot",
            codecProfileCode = 2,
            attemptedAt = 1_000L,
            ringingAt = 2_000L,
            connectedAt = 3_000L,
            endedAt = 63_000L,
            outcome = "CONNECTED_ENDED",
            inferredEnding = false,
            failureReason = null,
            displayName = "Alice",
            currentDestinationHash = "cccccccccccccccccccccccccccccccc",
        )

    private fun callReadyState() =
        CallDetailsState(
            record = record(),
            isLoading = false,
            blockState = BlockLookupState.UNBLOCKED,
            canStartCall = true,
        )
}
