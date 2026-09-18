package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.TransferPhase
import network.columba.app.rns.api.model.TransferProgressUpdate
import network.columba.app.service.SyncProgress
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferProgressComponentsTest {
    private val activityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(activityRule).around(composeRule)

    @Test
    fun `bubble progress identifies active Resource and percentage`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(outgoing(progress = 0.64f))
            }
        }

        composeRule.onNodeWithText("Transferring Resource").assertIsDisplayed()
        composeRule.onNodeWithText("64%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Transferring Resource, 64 percent").assertIsDisplayed()
        composeRule.onNodeWithTag("message_resource_progress_bar").assertIsDisplayed()
    }

    @Test
    fun `direct retry shows live attempt counters without stale Resource percentage`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(
                        progress = 0.03f,
                        phase = TransferPhase.PREPARING,
                        currentAttempt = 2,
                        maxAttempts = 5,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Retrying direct send (2/5)").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("3%").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("message_resource_progress_bar").fetchSemanticsNodes().size)
    }

    @Test
    fun `initial direct setup is not mislabeled as a retry`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(
                        progress = 0.01f,
                        phase = TransferPhase.PREPARING,
                        currentAttempt = 1,
                        maxAttempts = 5,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Sending directly").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Retrying direct send (1/5)").fetchSemanticsNodes().size)
    }

    @Test
    fun `propagation fallback shows relay state without stale direct percentage`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(
                        progress = 0.03f,
                        phase = TransferPhase.PREPARING,
                        deliveryMethod = DeliveryMethod.PROPAGATED,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Sending to relay network").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("3%").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("message_resource_progress_bar").fetchSemanticsNodes().size)
    }

    @Test
    fun `tray labels propagation sync as aggregate relay progress`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = listOf(incoming(progress = 0.38f)),
                    syncProgress = SyncProgress.InProgress("receiving", 0.41f),
                )
            }
        }

        composeRule.onNodeWithText("Receiving messages via relay").assertIsDisplayed()
        composeRule.onNodeWithText("41%").assertIsDisplayed()
        composeRule.onNodeWithText("1 direct transfer also active").assertIsDisplayed()
    }

    @Test
    fun `tray dismisses relay transfer once byte progress reaches completion`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = emptyList(),
                    syncProgress = SyncProgress.InProgress("receiving", 1f),
                )
            }
        }

        assertEquals(
            0,
            composeRule.onAllNodesWithText("Receiving messages via relay").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `tray labels direct incoming Resource without claiming a peer`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = listOf(incoming(progress = 0.38f)),
                    syncProgress = SyncProgress.Idle,
                )
            }
        }

        composeRule.onNodeWithText("Receiving a message directly").assertIsDisplayed()
        composeRule.onNodeWithText("38%").assertIsDisplayed()
    }

    private fun outgoing(
        progress: Float,
        phase: TransferPhase = TransferPhase.TRANSFERRING,
        deliveryMethod: DeliveryMethod = DeliveryMethod.DIRECT,
        currentAttempt: Int? = null,
        maxAttempts: Int? = null,
    ) = TransferProgressUpdate(
        transferId = "resource-out",
        messageHash = "aabbcc",
        direction = Direction.OUT,
        progress = progress,
        phase = phase,
        totalBytes = 4_800_000,
        deliveryMethod = deliveryMethod,
        currentAttempt = currentAttempt,
        maxAttempts = maxAttempts,
    )

    private fun incoming(progress: Float) = TransferProgressUpdate(
        transferId = "resource-in",
        direction = Direction.IN,
        progress = progress,
        phase = TransferPhase.TRANSFERRING,
        totalBytes = 2_100_000,
        deliveryMethod = DeliveryMethod.DIRECT,
    )
}
