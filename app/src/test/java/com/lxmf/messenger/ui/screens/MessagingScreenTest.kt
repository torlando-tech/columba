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
import com.lxmf.messenger.test.MessagingTestFixtures
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.model.ReplyPreviewUi
import com.lxmf.messenger.viewmodel.ContactToggleResult
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
import org.junit.rules.RuleChain
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
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

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
        every { mockViewModel.isContactSaved } returns MutableStateFlow(false)
        every { mockViewModel.manualSyncResult } returns MutableSharedFlow()
        every { mockViewModel.loadedImageIds } returns MutableStateFlow(emptySet())
        every { mockViewModel.contactToggleResult } returns MutableSharedFlow()
        // File attachment mocks
        every { mockViewModel.selectedFileAttachments } returns MutableStateFlow(emptyList())
        every { mockViewModel.totalAttachmentSize } returns MutableStateFlow(0)
        every { mockViewModel.fileAttachmentError } returns MutableSharedFlow()
        every { mockViewModel.isProcessingFile } returns MutableStateFlow(false)
        // Location sharing mocks
        every { mockViewModel.contacts } returns MutableStateFlow(emptyList())
        every { mockViewModel.locationSharingState } returns MutableStateFlow(LocationSharingState.NONE)
        // Reply mocks
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(null)
        every { mockViewModel.replyPreviewCache } returns MutableStateFlow(emptyMap())
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

    // ========== Star Toggle Button Tests ==========

    @Test
    fun topAppBar_starButton_displaysCorrectContentDescription_whenNotSaved() {
        // Given
        every { mockViewModel.isContactSaved } returns MutableStateFlow(false)

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
        composeTestRule.onNodeWithContentDescription("Save to contacts").assertIsDisplayed()
    }

    @Test
    fun topAppBar_starButton_displaysCorrectContentDescription_whenSaved() {
        // Given
        every { mockViewModel.isContactSaved } returns MutableStateFlow(true)

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
        composeTestRule.onNodeWithContentDescription("Remove from contacts").assertIsDisplayed()
    }

    @Test
    fun topAppBar_starButton_callsToggleContact() {
        // Given
        every { mockViewModel.isContactSaved } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Save to contacts").performClick()

        // Then
        verify { mockViewModel.toggleContact() }
    }

    @Test
    fun topAppBar_starButton_whenSaved_callsToggleContact() {
        // Given
        every { mockViewModel.isContactSaved } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Remove from contacts").performClick()

        // Then
        verify { mockViewModel.toggleContact() }
    }

    // ========== Online Status Tests ==========

    @Test
    fun onlineStatus_displaysOnline_whenRecentlySeen() {
        // Given - announce with recent lastSeenTimestamp (< 5 minutes ago)
        every { mockViewModel.announceInfo } returns
            MutableStateFlow(
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
        every { mockViewModel.announceInfo } returns
            MutableStateFlow(
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
        every { mockViewModel.selectedImageData } returns
            MutableStateFlow(
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
        every { mockViewModel.selectedImageData } returns
            MutableStateFlow(
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
        every { mockViewModel.selectedImageData } returns
            MutableStateFlow(
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

    // ========== Async Image Loading Tests ==========

    @Test
    fun messageWithUncachedImage_triggersAsyncLoad() {
        // Given - message with image that needs async loading
        val messageWithImage = MessagingTestFixtures.createMessageWithUncachedImage()
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithImage)))

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

        // Then - should trigger async image loading
        verify { mockViewModel.loadImageAsync(messageWithImage.id, messageWithImage.fieldsJson) }
    }

    @Test
    fun messageWithCachedImage_doesNotTriggerAsyncLoad() {
        // Given - message with already cached image (decodedImage is non-null)
        // Since we can't easily create an ImageBitmap in tests, we simulate
        // a message that has hasImageAttachment but with the image in loadedImageIds
        val messageId = "cached-img-msg"
        val messageWithCachedId = MessagingTestFixtures.createMessageWithUncachedImage(id = messageId)
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithCachedId)))
        every { mockViewModel.loadedImageIds } returns MutableStateFlow(setOf(messageId))

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

        // Then - should NOT trigger async image loading (already in loadedImageIds)
        verify(exactly = 0) { mockViewModel.loadImageAsync(messageId, any()) }
    }

    @Test
    fun messageWithNoImage_doesNotTriggerAsyncLoad() {
        // Given - regular text message without image
        val textMessage = MessagingTestFixtures.createReceivedMessage()
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(textMessage)))

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

        // Then - should NOT trigger async image loading
        verify(exactly = 0) { mockViewModel.loadImageAsync(any(), any()) }
    }

    @Test
    fun loadedImageIds_update_triggersRecomposition() {
        // Given - message that initially needs loading
        val messageId = "loading-msg"
        val message = MessagingTestFixtures.createMessageWithUncachedImage(id = messageId)
        val loadedIdsFlow = MutableStateFlow<Set<String>>(emptySet())

        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message)))
        every { mockViewModel.loadedImageIds } returns loadedIdsFlow

        // When - render initially
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Initial load should be triggered
        verify { mockViewModel.loadImageAsync(messageId, any()) }

        // Then - update loadedImageIds (simulating image loaded)
        loadedIdsFlow.value = setOf(messageId)
        composeTestRule.waitForIdle()

        // Second load should not be triggered (image already in loadedImageIds)
        verify(exactly = 1) { mockViewModel.loadImageAsync(messageId, any()) }
    }

    @Test
    fun multipleMessagesWithImages_triggerIndependentLoads() {
        // Given - multiple messages with uncached images
        val message1 = MessagingTestFixtures.createMessageWithUncachedImage(id = "img-msg-1")
        val message2 = MessagingTestFixtures.createMessageWithUncachedImage(id = "img-msg-2")
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(message1, message2)))

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

        // Then - both should trigger async loads
        verify { mockViewModel.loadImageAsync("img-msg-1", any()) }
        verify { mockViewModel.loadImageAsync("img-msg-2", any()) }
    }

    @Test
    fun messageWithImageAndNullFieldsJson_doesNotTriggerLoad() {
        // Given - message where hasImageAttachment is true but fieldsJson is null
        // (image was already cached at mapping time)
        val message = MessagingTestFixtures.createMessageWithCachedImage(id = "cached-msg")
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

        // Then - should NOT trigger async load (fieldsJson is null)
        verify(exactly = 0) { mockViewModel.loadImageAsync(any(), any()) }
    }

    // ========== Contact Toggle Result Tests ==========

    @Test
    fun contactToggleResult_added_triggersToastCollection() {
        // Given - a flow with replay so value is available when LaunchedEffect starts collecting
        val contactToggleFlow = MutableSharedFlow<ContactToggleResult>(replay = 1)
        contactToggleFlow.tryEmit(ContactToggleResult.Added)
        every { mockViewModel.contactToggleResult } returns contactToggleFlow

        // When - render screen (LaunchedEffect will collect the replayed value)
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = "Alice",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Toast.makeText is called - code path exercised for coverage
    }

    @Test
    fun contactToggleResult_removed_triggersToastCollection() {
        // Given - a flow with replay
        val contactToggleFlow = MutableSharedFlow<ContactToggleResult>(replay = 1)
        contactToggleFlow.tryEmit(ContactToggleResult.Removed)
        every { mockViewModel.contactToggleResult } returns contactToggleFlow

        // When - render screen
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = "Bob",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun contactToggleResult_error_triggersToastCollection() {
        // Given - a flow with replay
        val contactToggleFlow = MutableSharedFlow<ContactToggleResult>(replay = 1)
        contactToggleFlow.tryEmit(ContactToggleResult.Error("Identity not available"))
        every { mockViewModel.contactToggleResult } returns contactToggleFlow

        // When - render screen
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = "Charlie",
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()
    }

    // ========== Reply Functionality Tests ==========

    @Test
    fun replyInputBar_displayed_whenPendingReplyIsSet() {
        // Given - a pending reply is set
        val replyPreview = ReplyPreviewUi(
            messageId = "reply-msg-123",
            senderName = "Alice",
            contentPreview = "Original message content",
        )
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(replyPreview)

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

        // Then - ReplyInputBar should be displayed
        composeTestRule.onNodeWithText("Replying to Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Original message content").assertIsDisplayed()
    }

    @Test
    fun replyInputBar_notDisplayed_whenNoPendingReply() {
        // Given - no pending reply (default state from setup)
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(null)

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

        // Then - ReplyInputBar should not be displayed
        composeTestRule.onNodeWithText("Replying to", substring = true).assertDoesNotExist()
    }

    @Test
    fun replyInputBar_cancelButton_callsClearReplyTo() {
        // Given - a pending reply is set
        val replyPreview = ReplyPreviewUi(
            messageId = "reply-msg-123",
            senderName = "Bob",
            contentPreview = "Some content",
        )
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(replyPreview)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When - tap cancel button
        composeTestRule.onNodeWithContentDescription("Cancel reply").performClick()

        // Then - clearReplyTo should be called
        verify { mockViewModel.clearReplyTo() }
    }

    @Test
    fun replyInputBar_withImageReply_displaysPhotoIndicator() {
        // Given - a pending reply to an image message
        val replyPreview = ReplyPreviewUi(
            messageId = "img-reply-123",
            senderName = "Charlie",
            contentPreview = "",
            hasImage = true,
        )
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(replyPreview)

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

        // Then - should show Photo indicator
        composeTestRule.onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test
    fun replyInputBar_withFileReply_displaysFilename() {
        // Given - a pending reply to a file message
        val replyPreview = ReplyPreviewUi(
            messageId = "file-reply-123",
            senderName = "David",
            contentPreview = "",
            hasFileAttachment = true,
            firstFileName = "document.pdf",
        )
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(replyPreview)

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

        // Then - should show filename
        composeTestRule.onNodeWithText("document.pdf").assertIsDisplayed()
    }

    @Test
    fun inputBar_withPendingReply_sendButtonEnabled() {
        // Given - pending reply with text entered
        val replyPreview = ReplyPreviewUi(
            messageId = "reply-msg",
            senderName = "Eve",
            contentPreview = "Original",
        )
        every { mockViewModel.pendingReplyTo } returns MutableStateFlow(replyPreview)

        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }

        // When - enter text
        composeTestRule.onNodeWithText("Type a message...").performTextInput("Reply message")

        // Then - send button should be enabled
        composeTestRule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test
    fun messageWithReply_displaysReplyPreviewBubble() {
        // Given - a message that is a reply to another message
        val replyPreview = ReplyPreviewUi(
            messageId = "original-msg",
            senderName = "Frank",
            contentPreview = "This is the original message",
        )
        val messageWithReply = MessagingTestFixtures.createMessageWithReply(
            content = "This is my reply",
            replyPreview = replyPreview,
        )
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithReply)))

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

        // Then - reply preview should be displayed
        composeTestRule.onNodeWithText("Frank").assertIsDisplayed()
        composeTestRule.onNodeWithText("This is the original message").assertIsDisplayed()
        composeTestRule.onNodeWithText("This is my reply").assertIsDisplayed()
    }

    @Test
    fun messageWithReply_loadsReplyPreviewAsync_whenNotCached() {
        // Given - a message with replyToMessageId but no cached preview
        val messageWithReplyId = MessagingTestFixtures.createMessageWithReplyId(
            id = "msg-with-reply",
            replyToMessageId = "original-msg-id",
        )
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithReplyId)))
        every { mockViewModel.replyPreviewCache } returns MutableStateFlow(emptyMap())

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

        // Then - should trigger async loading of reply preview
        verify { mockViewModel.loadReplyPreviewAsync("msg-with-reply", "original-msg-id") }
    }

    @Test
    fun messageWithReply_doesNotReload_whenCached() {
        // Given - a message with replyToMessageId and cached preview
        val messageId = "cached-reply-msg"
        val replyToId = "original-msg"
        val messageWithReplyId = MessagingTestFixtures.createMessageWithReplyId(
            id = messageId,
            replyToMessageId = replyToId,
        )
        val cachedPreview = ReplyPreviewUi(
            messageId = replyToId,
            senderName = "Grace",
            contentPreview = "Cached content",
        )
        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithReplyId)))
        every { mockViewModel.replyPreviewCache } returns MutableStateFlow(mapOf(messageId to cachedPreview))

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

        // Then - should NOT trigger async loading (already cached)
        verify(exactly = 0) { mockViewModel.loadReplyPreviewAsync(any(), any()) }

        // And should display cached preview
        composeTestRule.onNodeWithText("Grace").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cached content").assertIsDisplayed()
    }

    @Test
    fun replyPreviewCache_update_triggersRecomposition() {
        // Given - message with reply that initially has no cache
        val messageId = "recompose-test-msg"
        val replyToId = "original-for-recompose"
        val messageWithReplyId = MessagingTestFixtures.createMessageWithReplyId(
            id = messageId,
            replyToMessageId = replyToId,
        )
        val cacheFlow = MutableStateFlow<Map<String, ReplyPreviewUi>>(emptyMap())

        every { mockViewModel.messages } returns flowOf(PagingData.from(listOf(messageWithReplyId)))
        every { mockViewModel.replyPreviewCache } returns cacheFlow

        // When - render initially
        composeTestRule.setContent {
            MessagingScreen(
                destinationHash = MessagingTestFixtures.Constants.TEST_DESTINATION_HASH,
                peerName = MessagingTestFixtures.Constants.TEST_PEER_NAME,
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Initial load should be triggered
        verify { mockViewModel.loadReplyPreviewAsync(messageId, replyToId) }

        // Then - update cache (simulating async load completion)
        val loadedPreview = ReplyPreviewUi(
            messageId = replyToId,
            senderName = "Henry",
            contentPreview = "Loaded content",
        )
        cacheFlow.value = mapOf(messageId to loadedPreview)
        composeTestRule.waitForIdle()

        // Should display loaded preview
        composeTestRule.onNodeWithText("Henry").assertIsDisplayed()
    }
}
