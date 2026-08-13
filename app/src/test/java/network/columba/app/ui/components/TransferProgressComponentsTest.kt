package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
    fun `bubble progress identifies direct send and percentage`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(outgoing(progress = 0.64f))
            }
        }

        composeRule.onNodeWithText("Sending directly").assertIsDisplayed()
        composeRule.onNodeWithText("64%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sending directly, 64 percent").assertIsDisplayed()
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


    private fun outgoing(progress: Float) = TransferProgressUpdate(
        transferId = "resource-out",
        messageHash = "aabbcc",
        direction = Direction.OUT,
        progress = progress,
        phase = TransferPhase.TRANSFERRING,
        totalBytes = 4_800_000,
        deliveryMethod = DeliveryMethod.DIRECT,
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
