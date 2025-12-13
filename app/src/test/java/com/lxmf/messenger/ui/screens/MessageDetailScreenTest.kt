package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.test.MessageDetailTestFixtures
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.viewmodel.MessageDetailViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for MessageDetailScreen.
 * Tests the message details display including status, delivery method,
 * timestamp formatting, and error handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageDetailScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Loading State Tests ==========

    @Test
    fun `loading state displays loading text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns MutableStateFlow(null)

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Loading...").assertIsDisplayed()
    }

    @Test
    fun `loading state displays top app bar`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns MutableStateFlow(null)

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Message Details").assertIsDisplayed()
    }

    // ========== Top App Bar Tests ==========

    @Test
    fun `top app bar displays correct title`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.deliveredMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Message Details").assertIsDisplayed()
    }

    @Test
    fun `back button invokes callback`() {
        var backClicked = false
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.deliveredMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = { backClicked = true },
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertTrue("Back callback should be invoked", backClicked)
    }

    // ========== Timestamp Card Tests ==========

    @Test
    fun `timestamp card displays sent label`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.deliveredMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // The timestamp card has "Sent" as its title
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
    }

    // ========== Status Card Tests - Delivered ==========

    @Test
    fun `status card delivered displays correct text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.deliveredMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivered").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message was successfully delivered to recipient")
            .assertIsDisplayed()
    }

    // ========== Status Card Tests - Failed ==========

    @Test
    fun `status card failed displays correct text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.failedMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message delivery failed").assertIsDisplayed()
    }

    // ========== Status Card Tests - Pending ==========

    @Test
    fun `status card pending displays correct text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.pendingMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for delivery confirmation").assertIsDisplayed()
    }

    // ========== Status Card Tests - Sent (default) ==========

    @Test
    fun `status card sent displays correct text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.sentMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Message has been sent").assertIsDisplayed()
    }

    @Test
    fun `status card unknown status displays default sent text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.createMessageUi(
                    MessageDetailTestFixtures.MessageConfig(status = "unknown_status"),
                ),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Message has been sent").assertIsDisplayed()
    }

    // ========== Delivery Method Card Tests - Opportunistic ==========

    @Test
    fun `delivery method card opportunistic displays correct content`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.opportunisticMessage(),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delivery Method").assertIsDisplayed()
        // Scroll to the text before asserting (may be off-screen)
        composeTestRule.onNodeWithText("Opportunistic").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Single packet delivery for small messages, no link required")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Delivery Method Card Tests - Direct ==========

    @Test
    fun `delivery method card direct displays correct content`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.directMessage(),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Delivery Method").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Direct").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Link-based delivery with retries, supports large messages")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Delivery Method Card Tests - Propagated ==========

    @Test
    fun `delivery method card propagated displays correct content`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.propagatedMessage(),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Delivery Method").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Propagated").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivered via relay node for offline recipients")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Delivery Method Card Tests - Unknown Method ==========

    @Test
    fun `delivery method card unknown method displays capitalized text`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.createMessageUi(
                    MessageDetailTestFixtures.MessageConfig(
                        status = "delivered",
                        deliveryMethod = "custom_method",
                    ),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Delivery Method").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom_method").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Unknown delivery method").performScrollTo().assertIsDisplayed()
    }

    // ========== Delivery Method Card Visibility Tests ==========

    @Test
    fun `delivery method card not displayed when delivery method is null`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.messageWithNoDeliveryMethod(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Delivery Method").assertDoesNotExist()
    }

    // ========== Error Details Card Tests ==========

    @Test
    fun `error details card displayed when status failed and error message exists`() {
        val errorMessage = "Connection timeout after 30 seconds"
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.failedMessage(errorMessage = errorMessage),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Error Details").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(errorMessage).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `error details card not displayed when status failed but error message is null`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.failedWithoutErrorMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Error Details").assertDoesNotExist()
    }

    @Test
    fun `error details card not displayed when status failed but error message is blank`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.createMessageUi(
                    MessageDetailTestFixtures.MessageConfig(
                        status = "failed",
                        errorMessage = "   ",
                    ),
                ),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Error Details").assertDoesNotExist()
    }

    @Test
    fun `error details card not displayed when status is not failed`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.createMessageUi(
                    MessageDetailTestFixtures.MessageConfig(
                        status = "delivered",
                        errorMessage = "Some error",
                    ),
                ),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Error Details").assertDoesNotExist()
    }

    // ========== ViewModel Integration Tests ==========

    @Test
    fun `screen calls loadMessage on launch`() {
        val testMessageId = "test-message-id"
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns MutableStateFlow(null)

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = testMessageId,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.waitForIdle()

        verify { mockViewModel.loadMessage(testMessageId) }
    }

    // ========== State Transition Tests ==========

    @Test
    fun `screen updates UI when message state changes`() {
        val messageFlow =
            MutableStateFlow<MessageUi?>(
                MessageDetailTestFixtures.pendingMessage(),
            )
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns messageFlow

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()

        messageFlow.value = MessageDetailTestFixtures.deliveredMessage()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delivered").assertIsDisplayed()
    }

    // ========== Card Count Verification Tests ==========

    @Test
    fun `screen displays correct number of cards for delivered message`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.deliveredMessage(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Should show 3 cards (Timestamp, Status, Delivery Method)
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivery Method").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error Details").assertDoesNotExist()
    }

    @Test
    fun `screen displays correct number of cards for failed message with error`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.failedMessage("Network error"),
            )

        composeTestRule.setContent {
            MaterialTheme {
                MessageDetailScreen(
                    messageId = "test-id",
                    onBackClick = {},
                    viewModel = mockViewModel,
                )
            }
        }

        // Should show 4 cards (Timestamp, Status, Delivery Method, Error Details)
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivery Method").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Error Details").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `screen displays correct number of cards for message without delivery method`() {
        val mockViewModel = mockk<MessageDetailViewModel>(relaxed = true)
        every { mockViewModel.message } returns
            MutableStateFlow(
                MessageDetailTestFixtures.messageWithNoDeliveryMethod(),
            )

        composeTestRule.setContent {
            MessageDetailScreen(
                messageId = "test-id",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Should show 2 cards (Timestamp, Status)
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivery Method").assertDoesNotExist()
        composeTestRule.onNodeWithText("Error Details").assertDoesNotExist()
    }
}
