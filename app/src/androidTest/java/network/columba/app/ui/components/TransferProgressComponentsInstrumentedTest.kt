package network.columba.app.ui.components

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.TransferPhase
import network.columba.app.rns.api.model.TransferProgressUpdate
import network.columba.app.service.SyncProgress
import network.columba.app.test.TestHostActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TransferProgressComponentsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun rendersOutgoingBubbleProgress() {
        composeRule.setContent {
            MaterialTheme {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.width(260.dp).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("field-notes.pdf")
                        MessageTransferProgress(outgoing())
                    }
                }
            }
        }

        composeRule.onNodeWithText("Transferring Resource").assertIsDisplayed()
        saveScreenshot("transfer-progress-bubble.png", "message_transfer_progress")
    }

    @Test
    fun rendersConversationTransferTray() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = listOf(incoming()),
                    syncProgress = SyncProgress.InProgress("receiving", 0.41f),
                    modifier = Modifier.width(360.dp).padding(16.dp),
                )
            }
        }

        composeRule.onNodeWithText("Receiving messages via relay").assertIsDisplayed()
        composeRule.onNodeWithText("1 direct transfer also active").assertIsDisplayed()
        saveScreenshot("transfer-progress-tray.png", "conversation_transfer_tray")
    }

    @Test
    fun dismissesCompletedRelayTransferTray() {
        composeRule.setContent {
            MaterialTheme {
                ConversationTransferTray(
                    incomingTransfers = emptyList(),
                    syncProgress = SyncProgress.InProgress("receiving", 1f),
                    modifier = Modifier.width(360.dp).padding(16.dp),
                )
            }
        }

        assertTrue(
            composeRule.onAllNodesWithText("Receiving messages via relay").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun saveScreenshot(name: String, tag: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ColumbaTest",
            )
        }
        val output = checkNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        )
        context.contentResolver.openOutputStream(output).use { stream ->
            checkNotNull(stream)
            composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue(output.toString().isNotBlank())
    }

    private fun outgoing() = TransferProgressUpdate(
        transferId = "resource-out",
        messageHash = "aabbcc",
        direction = Direction.OUT,
        progress = 0.64f,
        phase = TransferPhase.TRANSFERRING,
        totalBytes = 4_800_000,
        deliveryMethod = DeliveryMethod.DIRECT,
    )

    private fun incoming() = TransferProgressUpdate(
        transferId = "resource-in",
        direction = Direction.IN,
        progress = 0.38f,
        phase = TransferPhase.TRANSFERRING,
        totalBytes = 2_100_000,
        deliveryMethod = DeliveryMethod.DIRECT,
    )
}
