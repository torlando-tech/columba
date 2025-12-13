package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.TestFactories
import com.lxmf.messenger.viewmodel.ChatsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ChatsScreen.kt.
 * Tests conversation list display, search, sync, and user interactions.
 * Uses Robolectric for local testing without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatsScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== EmptyChatsState Tests ==========

    @Test
    fun emptyState_displaysPrimaryText() {
        // Given/When
        composeTestRule.setContent {
            EmptyChatsState()
        }

        // Then
        composeTestRule.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    @Test
    fun emptyState_displaysSecondaryText() {
        // Given/When
        composeTestRule.setContent {
            EmptyChatsState()
        }

        // Then
        composeTestRule.onNodeWithText("Messages from peers will appear here").assertIsDisplayed()
    }

    // ========== DeleteConversationDialog Tests ==========

    @Test
    fun deleteDialog_displaysTitle() {
        // Given/When
        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Alice",
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delete Conversation?").assertIsDisplayed()
    }

    @Test
    fun deleteDialog_displaysPeerNameInMessage() {
        // Given/When
        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Bob",
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            "Are you sure you want to delete your conversation with Bob? This will permanently delete all messages.",
        ).assertIsDisplayed()
    }

    @Test
    fun deleteDialog_displaysCancelButton() {
        // Given/When
        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Alice",
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun deleteDialog_displaysDeleteButton() {
        // Given/When
        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Alice",
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun deleteDialog_cancelClick_callsOnDismiss() {
        // Given
        var dismissCalled = false

        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Alice",
                onConfirm = {},
                onDismiss = { dismissCalled = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then
        assertTrue(dismissCalled)
    }

    @Test
    fun deleteDialog_deleteClick_callsOnConfirm() {
        // Given
        var confirmCalled = false

        composeTestRule.setContent {
            DeleteConversationDialog(
                peerName = "Alice",
                onConfirm = { confirmCalled = true },
                onDismiss = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Delete").performClick()

        // Then
        assertTrue(confirmCalled)
    }

    // ========== ConversationContextMenu Tests ==========

    @Test
    fun contextMenu_expanded_displaysAllItems() {
        // Given/When
        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Save to Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as Unread").assertIsDisplayed()
        composeTestRule.onNodeWithText("View Peer Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete Conversation").assertIsDisplayed()
    }

    @Test
    fun contextMenu_notSaved_showsSaveToContacts() {
        // Given/When
        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Save to Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove from Contacts").assertDoesNotExist()
    }

    @Test
    fun contextMenu_isSaved_showsRemoveFromContacts() {
        // Given/When
        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = true,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Remove from Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save to Contacts").assertDoesNotExist()
    }

    @Test
    fun contextMenu_saveClick_callsOnSaveToContacts() {
        // Given
        var saveCalled = false

        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = { saveCalled = true },
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Save to Contacts").performClick()

        // Then
        assertTrue(saveCalled)
    }

    @Test
    fun contextMenu_removeClick_callsOnRemoveFromContacts() {
        // Given
        var removeCalled = false

        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = true,
                onSaveToContacts = {},
                onRemoveFromContacts = { removeCalled = true },
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Remove from Contacts").performClick()

        // Then
        assertTrue(removeCalled)
    }

    @Test
    fun contextMenu_markUnreadClick_callsOnMarkAsUnread() {
        // Given
        var markUnreadCalled = false

        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = { markUnreadCalled = true },
                onDeleteConversation = {},
                onViewDetails = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Mark as Unread").performClick()

        // Then
        assertTrue(markUnreadCalled)
    }

    @Test
    fun contextMenu_viewDetailsClick_callsOnViewDetails() {
        // Given
        var viewDetailsCalled = false

        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = {},
                onViewDetails = { viewDetailsCalled = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("View Peer Details").performClick()

        // Then
        assertTrue(viewDetailsCalled)
    }

    @Test
    fun contextMenu_deleteClick_callsOnDeleteConversation() {
        // Given
        var deleteCalled = false

        composeTestRule.setContent {
            ConversationContextMenu(
                expanded = true,
                onDismiss = {},
                isSaved = false,
                onSaveToContacts = {},
                onRemoveFromContacts = {},
                onMarkAsUnread = {},
                onDeleteConversation = { deleteCalled = true },
                onViewDetails = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Delete Conversation").performClick()

        // Then
        assertTrue(deleteCalled)
    }

    // ========== ConversationCard Tests ==========

    @Test
    fun conversationCard_displaysPeerName() {
        // Given
        val conversation = TestFactories.createConversation(peerName = "Test User")

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Test User").assertIsDisplayed()
    }

    @Test
    fun conversationCard_displaysLastMessage() {
        // Given
        val conversation = TestFactories.createConversation(lastMessage = "Hello, how are you?")

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Hello, how are you?").assertIsDisplayed()
    }

    @Test
    fun conversationCard_withUnreadCount_displaysBadge() {
        // Given
        val conversation = TestFactories.createConversation(unreadCount = 5)

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun conversationCard_withHighUnreadCount_displaysCappedBadge() {
        // Given
        val conversation = TestFactories.createConversation(unreadCount = 150)

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test
    fun conversationCard_zeroUnread_doesNotShowBadge() {
        // Given
        val conversation = TestFactories.createConversation(unreadCount = 0)

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then - Badge with number should not exist
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun conversationCard_isSaved_displaysSavedIcon() {
        // Given
        val conversation = TestFactories.createConversation()

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = true,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Saved contact").assertIsDisplayed()
    }

    @Test
    fun conversationCard_notSaved_doesNotShowSavedIcon() {
        // Given
        val conversation = TestFactories.createConversation()

        // When
        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Saved contact").assertDoesNotExist()
    }

    @Test
    fun conversationCard_click_invokesOnClick() {
        // Given
        var clicked = false
        val conversation = TestFactories.createConversation(peerName = "Alice")

        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = { clicked = true },
                onLongPress = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Alice").performClick()

        // Then
        assertTrue(clicked)
    }

    @Test
    fun conversationCard_longPress_invokesOnLongPress() {
        // Given
        var longPressed = false
        val conversation = TestFactories.createConversation(peerName = "Alice")

        composeTestRule.setContent {
            ConversationCard(
                conversation = conversation,
                isSaved = false,
                onClick = {},
                onLongPress = { longPressed = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Alice").performTouchInput { longClick() }

        // Then
        assertTrue(longPressed)
    }

    // ========== ChatsScreen Display Tests ==========

    @Test
    fun chatsScreen_displaysTopAppBarTitle() {
        // Given
        val mockViewModel = createMockChatsViewModel()

        // When
        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test
    fun chatsScreen_displaysConversationCount_singular() {
        // Given
        val mockViewModel =
            createMockChatsViewModel(
                conversations = listOf(TestFactories.createConversation()),
            )

        // When
        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("1 conversation").assertIsDisplayed()
    }

    @Test
    fun chatsScreen_displaysConversationCount_plural() {
        // Given
        val mockViewModel =
            createMockChatsViewModel(
                conversations = TestFactories.createMultipleConversations(3),
            )

        // When
        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("3 conversations").assertIsDisplayed()
    }

    @Test
    fun chatsScreen_emptyList_displaysEmptyState() {
        // Given
        val mockViewModel = createMockChatsViewModel(conversations = emptyList())

        // When
        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    @Test
    fun chatsScreen_withConversations_displaysCards() {
        // Given
        val conversations =
            listOf(
                TestFactories.createConversation(peerHash = "peer1", peerName = "Alice"),
                TestFactories.createConversation(peerHash = "peer2", peerName = "Bob"),
            )
        val mockViewModel = createMockChatsViewModel(conversations = conversations)

        // When
        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    // ========== ChatsScreen Interaction Tests ==========

    @Test
    fun chatsScreen_conversationClick_callsOnChatClick() {
        // Given
        var clickedPeerHash: String? = null
        var clickedPeerName: String? = null
        val conversations =
            listOf(
                TestFactories.createConversation(peerHash = "alice_hash", peerName = "Alice"),
            )
        val mockViewModel = createMockChatsViewModel(conversations = conversations)

        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { hash, name ->
                    clickedPeerHash = hash
                    clickedPeerName = name
                },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Alice").performClick()

        // Then
        assertEquals("alice_hash", clickedPeerHash)
        assertEquals("Alice", clickedPeerName)
    }

    @Test
    fun chatsScreen_syncButton_triggersSync() {
        // Given
        val mockViewModel =
            createMockChatsViewModel(
                conversations = listOf(TestFactories.createConversation()),
            )

        composeTestRule.setContent {
            ChatsScreen(
                onChatClick = { _, _ -> },
                onViewPeerDetails = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Sync messages").performClick()

        // Then
        verify { mockViewModel.syncFromPropagationNode() }
    }

    // ========== Test Helpers ==========

    private fun createMockChatsViewModel(
        conversations: List<com.lxmf.messenger.data.repository.Conversation> = emptyList(),
        searchQuery: String = "",
        isSyncing: Boolean = false,
    ): ChatsViewModel {
        val mockViewModel = mockk<ChatsViewModel>(relaxed = true)

        every { mockViewModel.conversations } returns MutableStateFlow(conversations)
        every { mockViewModel.searchQuery } returns MutableStateFlow(searchQuery)
        every { mockViewModel.isSyncing } returns MutableStateFlow(isSyncing)
        every { mockViewModel.manualSyncResult } returns MutableSharedFlow()

        // Default: contacts are not saved
        every { mockViewModel.isContactSaved(any()) } returns MutableStateFlow(false)

        return mockViewModel
    }
}
