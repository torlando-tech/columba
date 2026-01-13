package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.MessagingTestFixtures
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.MessageUi
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

    // ========== Helper Functions ==========

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
