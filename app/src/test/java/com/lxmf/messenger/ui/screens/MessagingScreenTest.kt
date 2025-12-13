package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.PagingData
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.test.MessagingTestFixtures
import com.lxmf.messenger.viewmodel.MessagingViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for MessagingScreen.kt.
 * Tests the main messaging screen including message display, input, and navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var mockViewModel: MessagingViewModel

    @Before
    fun setup() {
        mockViewModel = mockk(relaxed = true)

        // Setup default mock returns
        every { mockViewModel.messages } returns flowOf(PagingData.empty())
        every { mockViewModel.announceInfo } returns MutableStateFlow(null)
        every { mockViewModel.selectedImageData } returns MutableStateFlow(null)
        every { mockViewModel.selectedImageFormat } returns MutableStateFlow(null)
        every { mockViewModel.isProcessingImage } returns MutableStateFlow(false)
        every { mockViewModel.isSyncing } returns MutableStateFlow(false)
        every { mockViewModel.manualSyncResult } returns MutableSharedFlow()
    }

    // ========== Empty State Tests ==========

    @Test
    fun emptyState_displaysNoMessagesYet() {
        // Given - empty messages
        every { mockViewModel.messages } returns flowOf(PagingData.empty())

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                onPeerClick = {},
                onViewMessageDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("No messages yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send a message to start the conversation").assertIsDisplayed()
    }

    // ========== TopAppBar Tests ==========

    @Test
    fun topAppBar_displaysPeerName() {
        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = "Alice",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun topAppBar_backButton_callsOnBackClick() {
        // Given
        var backClicked = false

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = { backClicked = true },
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // Then
        assertTrue(backClicked)
    }

    @Test
    fun topAppBar_peerHeader_callsOnPeerClick() {
        // Given
        var peerClicked = false

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = "Alice",
                onBackClick = {},
                onPeerClick = { peerClicked = true },
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Alice").performClick()

        // Then
        assertTrue(peerClicked)
    }

    @Test
    fun topAppBar_syncButton_callsSyncFromPropagationNode() {
        // Given
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Sync messages").performClick()

        // Then
        verify { mockViewModel.syncFromPropagationNode() }
    }

    @Test
    fun topAppBar_syncButton_disabledWhenSyncing() {
        // Given
        every { mockViewModel.isSyncing } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then - sync button should not be clickable (disabled state)
        // When syncing, a CircularProgressIndicator is shown instead of the icon
        composeTestRule.onNodeWithContentDescription("Sync messages").assertDoesNotExist()
    }

    // ========== Online Status Tests ==========

    @Test
    fun onlineStatus_displaysOnline_whenRecentlySeen() {
        // Given - announce with recent lastSeenTimestamp (< 5 minutes ago)
        every { mockViewModel.announceInfo } returns MutableStateFlow(
            MessagingTestFixtures.createOnlineAnnounce(),
        )

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Online").assertIsDisplayed()
    }

    @Test
    fun onlineStatus_displaysOffline_whenNotRecentlySeen() {
        // Given - announce with old lastSeenTimestamp (> 5 minutes ago)
        every { mockViewModel.announceInfo } returns MutableStateFlow(
            MessagingTestFixtures.createOfflineAnnounce(),
        )

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then - should show relative time instead of "Online"
        composeTestRule.onNodeWithText("Online").assertDoesNotExist()
    }

    // ========== Message List Tests ==========

    @Test
    fun messageList_displaysMessages() {
        // Given - multiple messages
        val messages = MessagingTestFixtures.createMultipleMessages(3)
        every { mockViewModel.messages } returns flowOf(PagingData.from(messages))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - verify messages are displayed
        composeTestRule.onNodeWithText("Received message 1", substring = true).assertIsDisplayed()
    }

    // ========== MessageInputBar Tests ==========

    @Test
    fun inputBar_emptyText_sendButtonDisabled() {
        // Given - no text entered
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun inputBar_withText_sendButtonEnabled() {
        // Given
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Type a message...").performTextInput("Hello")

        // Then
        composeTestRule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test
    fun inputBar_withImageOnly_sendButtonEnabled() {
        // Given - image selected but no text
        every { mockViewModel.selectedImageData } returns MutableStateFlow(
            MessagingTestFixtures.createTestImageData(),
        )

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test
    fun inputBar_sendClick_callsSendMessage() {
        // Given
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Type a message...").performTextInput("Test message")
        composeTestRule.onNodeWithContentDescription("Send message").performClick()

        // Then
        verify { mockViewModel.sendMessage(MessagingTestFixtures.Constants.TEST_DESTINATION_HASH, "Test message") }
    }

    @Test
    fun inputBar_attachmentButton_showsLoadingIndicator_whenProcessing() {
        // Given - image processing in progress
        every { mockViewModel.isProcessingImage } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then - attachment button icon is replaced with loading indicator
        // When processing, the "Attach image" icon is replaced with CircularProgressIndicator
        composeTestRule.onNodeWithContentDescription("Attach image").assertDoesNotExist()
    }

    @Test
    fun inputBar_imagePreview_shown_whenImageSelected() {
        // Given - image selected
        every { mockViewModel.selectedImageData } returns MutableStateFlow(
            MessagingTestFixtures.createTestImageData(),
        )

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then - image preview should be shown with size info
        composeTestRule.onNodeWithText("Image attached", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove image").assertIsDisplayed()
    }

    @Test
    fun inputBar_clearImageButton_callsClearSelectedImage() {
        // Given - image selected
        every { mockViewModel.selectedImageData } returns MutableStateFlow(
            MessagingTestFixtures.createTestImageData(),
        )

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Remove image").performClick()

        // Then
        verify { mockViewModel.clearSelectedImage() }
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun screen_loadsMessages_onComposition() {
        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        verify { mockViewModel.loadMessages(MessagingTestFixtures.Constants.TEST_DESTINATION_HASH, MessagingTestFixtures.Constants.TEST_PEER_NAME) }
    }

    // ========== Message Display Tests ==========

    @Test
    fun sentMessage_displaysContent() {
        // Given
        val message = MessagingTestFixtures.createSentMessage(content = "Hello from me")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Hello from me").assertIsDisplayed()
    }

    @Test
    fun receivedMessage_displaysContent() {
        // Given
        val message = MessagingTestFixtures.createReceivedMessage(content = "Hello from peer")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Hello from peer").assertIsDisplayed()
    }

    @Test
    fun sentMessage_showsDeliveryStatus_pending() {
        // Given
        val message = MessagingTestFixtures.createPendingMessage(content = "Pending test")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - pending shows "○"
        composeTestRule.onNodeWithText("○").assertIsDisplayed()
    }

    @Test
    fun sentMessage_showsDeliveryStatus_sent() {
        // Given
        val message = MessagingTestFixtures.createSentMessage(content = "Sent test", status = "sent")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - sent shows "✓"
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun sentMessage_showsDeliveryStatus_delivered() {
        // Given
        val message = MessagingTestFixtures.createDeliveredMessage(content = "Delivered test")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - delivered shows "✓✓"
        composeTestRule.onNodeWithText("✓✓").assertIsDisplayed()
    }

    @Test
    fun sentMessage_showsDeliveryStatus_failed() {
        // Given
        val message = MessagingTestFixtures.createFailedMessage(content = "Failed test")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - failed shows "!"
        composeTestRule.onNodeWithText("!").assertIsDisplayed()
    }

    @Test
    fun receivedMessage_doesNotShowDeliveryStatus() {
        // Given - received message should not show delivery status indicators
        val message = MessagingTestFixtures.createReceivedMessage(content = "From peer")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))

        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - no delivery status indicators should be shown
        composeTestRule.onNodeWithText("○").assertDoesNotExist()
        composeTestRule.onNodeWithText("✓").assertDoesNotExist()
        composeTestRule.onNodeWithText("✓✓").assertDoesNotExist()
        composeTestRule.onNodeWithText("!").assertDoesNotExist()
    }

    // ========== Message Input Validation Tests ==========

    @Test
    fun inputBar_displaysPlaceholder() {
        // When
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Type a message...").assertIsDisplayed()
    }
}
