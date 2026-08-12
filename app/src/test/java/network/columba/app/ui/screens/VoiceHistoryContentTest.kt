package network.columba.app.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.VoiceHistoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceHistoryContentTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `voice history exposes loading empty and error states`() {
        var state by mutableStateOf(VoiceHistoryState(isLoading = true))
        composeRule.setContent { VoiceHistoryContent(state, {}) }
        composeRule.onNodeWithText("Loading call history").assertIsDisplayed()

        state = VoiceHistoryState(isLoading = false)
        composeRule.onNodeWithText("No calls yet").assertIsDisplayed()

        state = VoiceHistoryState(isLoading = false, hasError = true)
        composeRule.onNodeWithText("Couldn’t load call history").assertIsDisplayed()
        composeRule.onNodeWithText("Your records were not deleted. Try again in a moment.").assertIsDisplayed()
    }

    @Test
    fun `voice card shows user evidence and opens exact attempt`() {
        var selectedAttempt: String? = null
        val record =
            record(
                callAttemptId = "attempt-1",
                displayName = "Alice",
                outcome = "CONNECTED_ENDED",
                connectedAt = 1_000L,
                endedAt = 66_000L,
            ).copy(direction = "INCOMING")
        composeRule.setContent {
            VoiceHistoryContent(
                state = VoiceHistoryState(records = listOf(record), isLoading = false),
                onRecordClick = { selectedAttempt = it.callAttemptId },
            )
        }

        composeRule.onNode(hasContentDescription("Alice", substring = true)).assertIsDisplayed().performClick()
        composeRule.onNode(hasContentDescription("Incoming", substring = true)).assertExists()
        composeRule.onNode(hasContentDescription("Connected then ended", substring = true)).assertExists()
        composeRule.onNode(hasContentDescription("1:05", substring = true)).assertExists()
        assertEquals("attempt-1", selectedAttempt)
    }

    @Test
    fun `voice card uses immutable peer fallback and explicit non-connection outcome`() {
        composeRule.setContent {
            VoiceHistoryContent(
                state = VoiceHistoryState(
                    records = listOf(
                        record(
                            callAttemptId = "attempt-2",
                            displayName = null,
                            remoteIdentityHash = "aabbccdd00112233",
                            outcome = "NOT_CONNECTED",
                        ),
                    ),
                    isLoading = false,
                ),
                onRecordClick = {},
            )
        }

        composeRule.onNode(hasContentDescription("Peer AABBCCDD", substring = true)).assertExists()
        composeRule.onNode(hasContentDescription("Not connected", substring = true)).assertExists()
    }

    @Test
    fun `voice card is one focusable announcement with a touch target at 200 percent text`() {
        var selectedAttempt: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                VoiceHistoryContent(
                    state = VoiceHistoryState(
                        records = listOf(record("attempt-accessible", "Alice", outcome = "CONNECTED_ENDED")),
                        isLoading = false,
                    ),
                    onRecordClick = { selectedAttempt = it.callAttemptId },
                )
            }
        }

        val matcher = hasContentDescription("Alice", substring = true)
        composeRule.onAllNodesWithContentDescription("Alice", substring = true).assertCountEquals(1)
        composeRule.onNode(matcher)
            .assertHeightIsAtLeast(72.dp)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        val peerBounds = composeRule.onNodeWithTag("callHistoryPeerName", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timeBounds = composeRule.onNodeWithTag("callHistoryTime", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(peerBounds.bottom <= timeBounds.top)
        assertEquals("attempt-accessible", selectedAttempt)
    }

    @Test
    fun `active connected call shows its live duration`() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            VoiceHistoryContent(
                state =
                    VoiceHistoryState(
                        records =
                            listOf(
                                record(
                                    callAttemptId = "attempt-active",
                                    displayName = "Alice",
                                    outcome = null,
                                    connectedAt = now - 65_000L,
                                    endedAt = null,
                                ),
                            ),
                        isLoading = false,
                        activeCallAttemptId = "attempt-active",
                    ),
                onRecordClick = {},
            )
        }

        composeRule.onNode(hasContentDescription("1:05", substring = true)).assertExists()
    }

    @Test
    fun `contradictory duration evidence is presented as unavailable`() {
        composeRule.setContent {
            VoiceHistoryContent(
                state =
                    VoiceHistoryState(
                        records =
                            listOf(
                                record(
                                    callAttemptId = "attempt-contradictory",
                                    displayName = "Alice",
                                    outcome = "CONNECTED_ENDED",
                                    connectedAt = 2_000L,
                                    endedAt = 1_500L,
                                ),
                            ),
                        isLoading = false,
                    ),
                onRecordClick = {},
            )
        }

        composeRule.onNode(hasContentDescription("Unavailable", substring = true)).assertExists()
    }

    @Test
    fun `timezone broadcast immediately regroups unchanged history`() {
        val originalTimeZone = TimeZone.getDefault()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            composeRule.setContent {
                VoiceHistoryContent(
                    state =
                        VoiceHistoryState(
                            records =
                                listOf(
                                    record(
                                        callAttemptId = "attempt-timezone",
                                        displayName = "Alice",
                                        outcome = "CONNECTED_ENDED",
                                        attemptedAt = Instant.parse("2026-01-02T00:30:00Z").toEpochMilli(),
                                    ),
                                ),
                            isLoading = false,
                        ),
                    onRecordClick = {},
                )
            }
            composeRule.onNodeWithText("Jan 2, 2026").assertIsDisplayed()

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Jan 1, 2026").assertIsDisplayed()
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `call outcome severity preserves warning and error distinction`() {
        assertEquals(CallOutcomeSeverity.ACTIVE, callOutcomeSeverity(null, isActiveCall = true))
        assertEquals(CallOutcomeSeverity.NEUTRAL, callOutcomeSeverity(null, isActiveCall = false))
        assertEquals(CallOutcomeSeverity.ERROR, callOutcomeSeverity("MISSED_INCOMING", isActiveCall = false))
        listOf("FAILED", "INTERRUPTED").forEach {
            assertEquals(CallOutcomeSeverity.WARNING, callOutcomeSeverity(it, isActiveCall = false))
        }
        listOf("CONNECTED_ENDED", "DECLINED_LOCAL", "BUSY_REMOTE", "CANCELLED_LOCAL", "NOT_CONNECTED").forEach {
            assertEquals(CallOutcomeSeverity.NEUTRAL, callOutcomeSeverity(it, isActiveCall = false))
        }
    }

    private fun record(
        callAttemptId: String,
        displayName: String?,
        remoteIdentityHash: String = "remote",
        outcome: String?,
        attemptedAt: Long = 1_000L,
        connectedAt: Long? = null,
        endedAt: Long? = 2_000L,
    ) =
        CallHistoryRecord(
            callAttemptId = callAttemptId,
            localIdentityHash = "local",
            remoteIdentityHash = remoteIdentityHash,
            direction = "OUTGOING",
            peerDisplayNameSnapshot = null,
            codecProfileCode = 2,
            attemptedAt = attemptedAt,
            ringingAt = null,
            connectedAt = connectedAt,
            endedAt = endedAt,
            outcome = outcome,
            inferredEnding = false,
            failureReason = null,
            displayName = displayName,
            currentDestinationHash = null,
        )
}
