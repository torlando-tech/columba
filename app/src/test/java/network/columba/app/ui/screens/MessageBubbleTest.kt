package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.MessagingTestFixtures
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.MessageRenderer
import network.columba.app.ui.model.MessageUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for MessageBubble composable.
 * Tests the missing image placeholder and info dialog behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageBubbleTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Missing Image Placeholder Tests ==========

    @Test
    fun `shows loading spinner when isImageLoading is true`() {
        val message = createMessageWithImageAttachment()

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = true,
                clipboardManager = clipboardManager,
                isImageLoading = true, // Loading in progress
            )
        }

        // Spinner should be visible (loading state)
        // Note: CircularProgressIndicator doesn't have text, but the Box it's in exists
        // The "Not available" text should NOT exist during loading
        composeTestRule.onNodeWithText("Not available").assertDoesNotExist()
    }

    @Test
    fun `shows Not available placeholder when image failed to load`() {
        val message = createMessageWithImageAttachment()

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = true,
                clipboardManager = clipboardManager,
                isImageLoading = false, // Loading complete but no image
            )
        }

        // Should show "Not available" text
        composeTestRule.onNodeWithText("Not available").assertIsDisplayed()
        // Should show broken image icon
        composeTestRule.onNodeWithContentDescription("Image unavailable").assertIsDisplayed()
    }

    @Test
    fun `tapping Not available placeholder shows info dialog`() {
        val message = createMessageWithImageAttachment()

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = true,
                clipboardManager = clipboardManager,
                isImageLoading = false,
            )
        }

        // Tap on the placeholder
        composeTestRule.onNodeWithText("Not available").performClick()

        // Dialog should appear with explanation
        composeTestRule.onNodeWithText("Image Not Available").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "The original image could not be found. This can happen when " +
                "importing data without attachments included.",
        ).assertIsDisplayed()
    }

    @Test
    fun `info dialog can be dismissed with OK button`() {
        val message = createMessageWithImageAttachment()

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = true,
                clipboardManager = clipboardManager,
                isImageLoading = false,
            )
        }

        // Open dialog
        composeTestRule.onNodeWithText("Not available").performClick()
        composeTestRule.onNodeWithText("Image Not Available").assertIsDisplayed()

        // Click OK
        composeTestRule.onNodeWithText("OK").performClick()

        // Dialog should be dismissed
        composeTestRule.onNodeWithText("Image Not Available").assertDoesNotExist()
    }

    @Test
    fun `no placeholder shown for message without image attachment`() {
        // Message without hasImageAttachment flag
        val message = MessagingTestFixtures.createSentMessage()

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = true,
                clipboardManager = clipboardManager,
                isImageLoading = false,
            )
        }

        // Neither loading spinner nor "Not available" should appear
        composeTestRule.onNodeWithText("Not available").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Image unavailable").assertDoesNotExist()
    }

    @Test
    fun `received message shows Not available placeholder correctly`() {
        val message = createMessageWithImageAttachment(isFromMe = false)

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = false,
                clipboardManager = clipboardManager,
                isImageLoading = false,
            )
        }

        composeTestRule.onNodeWithText("Not available").assertIsDisplayed()
    }

    @Test
    fun `markdown renderer displays formatted content without source markers`() {
        val message =
            createTextMessage(
                content = "# Markdown heading\n\nThis is **bold** and *italic*.\n\n- First item\n- Second item",
                renderer = MessageRenderer.MARKDOWN,
            )

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = false,
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasText("Markdown heading")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Markdown heading").assertIsDisplayed()
        composeTestRule.onNodeWithText("This is bold and italic.").assertIsDisplayed()
        composeTestRule.onNodeWithText("First item").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second item").assertIsDisplayed()
        composeTestRule.onNodeWithText("# Markdown heading").assertDoesNotExist()
    }

    @Test
    fun `markdown remote image renders compact blocked placeholder`() {
        val message =
            createTextMessage(
                content = "![Tracking pixel](https://example.com/tracker.png)",
                renderer = MessageRenderer.MARKDOWN,
            )

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = false,
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasText("Remote image not loaded")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Remote image not loaded").assertIsDisplayed()
    }

    @Test
    fun `plain renderer does not infer markdown from punctuation`() {
        val message = createTextMessage(content = "# Plain heading", renderer = MessageRenderer.PLAIN)

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = false,
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule.onNodeWithText("# Plain heading").assertIsDisplayed()
        composeTestRule.onNodeWithText("Plain heading").assertDoesNotExist()
    }

    @Test
    fun `unsupported renderer falls back to literal text`() {
        val message = createTextMessage(content = "[b]Not bold[/b]", renderer = MessageRenderer.BBCODE)

        composeTestRule.setContent {
            val clipboardManager = LocalClipboardManager.current
            MessageBubble(
                message = message,
                isFromMe = false,
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule.onNodeWithText("[b]Not bold[/b]").assertIsDisplayed()
    }

    // ========== Helper Functions ==========

    private fun createTextMessage(
        content: String,
        renderer: MessageRenderer,
    ) = MessageUi(
        id = "text-message",
        destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
        content = content,
        timestamp = 1_700_000_000_000,
        isFromMe = false,
        status = "received",
        renderer = renderer,
    )

    private fun createMessageWithImageAttachment(
        id: String = "msg_with_image",
        content: String = "Check out this image",
        isFromMe: Boolean = true,
    ) = MessageUi(
        id = id,
        destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
        content = content,
        timestamp = System.currentTimeMillis(),
        isFromMe = isFromMe,
        status = if (isFromMe) "delivered" else "received",
        decodedImage = null, // No actual image (simulates missing attachment)
        hasImageAttachment = true, // But message originally had an image
        deliveryMethod = null,
        errorMessage = null,
    )
}
