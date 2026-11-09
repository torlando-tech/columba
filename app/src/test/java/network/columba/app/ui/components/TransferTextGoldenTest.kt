package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.TransferPhase
import network.columba.app.rns.api.model.TransferProgressUpdate
import network.columba.app.service.SyncProgress
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for message/transfer progress components: bubble transfer
 * status, conversation transfer tray and the propagation-node sync sheet.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `message transfer progress transferring`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(progress = TRANSFERRING_PROGRESS, phase = TransferPhase.TRANSFERRING, deliveryMethod = DeliveryMethod.DIRECT),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_transferring", composeRule)
    }

    @Test
    fun `message transfer progress preparing direct`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(progress = 0f, phase = TransferPhase.PREPARING, deliveryMethod = DeliveryMethod.DIRECT),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_preparing_direct", composeRule)
    }

    @Test
    fun `message transfer progress retrying direct`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(
                        progress = 0.2f,
                        phase = TransferPhase.PREPARING,
                        deliveryMethod = DeliveryMethod.DIRECT,
                        currentAttempt = 2,
                        maxAttempts = 3,
                    ),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_retrying_direct", composeRule)
    }

    @Test
    fun `message transfer progress propagated`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(progress = 0.5f, phase = TransferPhase.PREPARING, deliveryMethod = DeliveryMethod.PROPAGATED),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_propagated", composeRule)
    }

    @Test
    fun `message transfer progress complete`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(progress = 1f, phase = TransferPhase.COMPLETE),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_complete", composeRule)
    }

    @Test
    fun `message transfer progress failed`() {
        composeRule.setContent {
            MaterialTheme {
                MessageTransferProgress(
                    outgoing(progress = 0.75f, phase = TransferPhase.FAILED),
                )
            }
        }
        TextGoldens.assertGolden("message_transfer_progress_failed", composeRule)
    }

    @Test
    fun `conversation transfer tray relay with direct active`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = listOf(incoming(INCOMING_PROGRESS_A)),
                    syncProgress = SyncProgress.InProgress(RELAY_STATE_NAME, RELAY_SYNC_PROGRESS),
                )
            }
        }
        TextGoldens.assertGolden("conversation_transfer_tray_relay_with_direct", composeRule)
    }

    @Test
    fun `conversation transfer tray direct plural`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = listOf(incoming(INCOMING_PROGRESS_A), incoming(INCOMING_PROGRESS_B)),
                    syncProgress = SyncProgress.Idle,
                )
            }
        }
        TextGoldens.assertGolden("conversation_transfer_tray_direct_plural", composeRule)
    }

    @Test
    fun `conversation transfer tray hidden without progress`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = emptyList(),
                    syncProgress = SyncProgress.Idle,
                )
            }
        }
        TextGoldens.assertGolden("conversation_transfer_tray_hidden", composeRule)
    }

    @Test
    fun `sync status bottom sheet idle`() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBottomSheet(
                    syncProgress = SyncProgress.Idle,
                    onDismiss = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("sync_status_bottom_sheet_idle", composeRule)
    }

    @Test
    fun `sync status bottom sheet starting`() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBottomSheet(
                    syncProgress = SyncProgress.Starting,
                    onDismiss = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("sync_status_bottom_sheet_starting", composeRule)
    }

    @Test
    fun `sync status bottom sheet in progress`() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBottomSheet(
                    syncProgress = SyncProgress.InProgress(RELAY_STATE_NAME, RELAY_SYNC_PROGRESS),
                    onDismiss = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("sync_status_bottom_sheet_in_progress", composeRule)
    }

    @Test
    fun `sync status bottom sheet in progress without percent`() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBottomSheet(
                    syncProgress = SyncProgress.InProgress("path_requested", 0f),
                    onDismiss = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("sync_status_bottom_sheet_in_progress_no_percent", composeRule)
    }

    @Test
    fun `sync status bottom sheet complete`() {
        composeRule.setContent {
            MaterialTheme {
                SyncStatusBottomSheet(
                    syncProgress = SyncProgress.Complete,
                    onDismiss = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("sync_status_bottom_sheet_complete", composeRule)
    }

    private fun outgoing(
        progress: Float,
        phase: TransferPhase,
        deliveryMethod: DeliveryMethod? = null,
        currentAttempt: Int? = null,
        maxAttempts: Int? = null,
    ) = TransferProgressUpdate(
        transferId = TRANSFER_ID_OUT,
        messageHash = MESSAGE_HASH,
        direction = Direction.OUT,
        progress = progress,
        phase = phase,
        totalBytes = OUT_TOTAL_BYTES,
        deliveryMethod = deliveryMethod,
        currentAttempt = currentAttempt,
        maxAttempts = maxAttempts,
    )

    private fun incoming(progress: Float) = TransferProgressUpdate(
        transferId = TRANSFER_ID_IN,
        direction = Direction.IN,
        progress = progress,
        phase = TransferPhase.TRANSFERRING,
        totalBytes = IN_TOTAL_BYTES,
        deliveryMethod = DeliveryMethod.DIRECT,
    )

    private companion object {
        const val TRANSFER_ID_OUT = "resource-out"
        const val MESSAGE_HASH = "aabbcc"
        const val OUT_TOTAL_BYTES = 4_800_000L
        const val TRANSFER_ID_IN = "resource-in"
        const val IN_TOTAL_BYTES = 2_100_000L
        const val TRANSFERRING_PROGRESS = 0.64f
        const val INCOMING_PROGRESS_A = 0.38f
        const val INCOMING_PROGRESS_B = 0.60f
        const val RELAY_STATE_NAME = "receiving"
        const val RELAY_SYNC_PROGRESS = 0.41f
    }
}
