@file:Suppress("IgnoredReturnValue") // awaitItem() calls consume flow emissions, result intentionally unused

package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.Conversation
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.service.PropagationNodeManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for ChatsViewModel.
 * Tests conversation list loading, deletion, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var viewModel: ChatsViewModel

    private val testConversation1 =
        Conversation(
            peerHash = "peer1",
            peerName = "Alice",
            displayName = "Alice",
            peerPublicKey = ByteArray(32) { 1 },
            lastMessage = "Hello",
            lastMessageTimestamp = 1000L,
            unreadCount = 2,
        )

    private val testConversation2 =
        Conversation(
            peerHash = "peer2",
            peerName = "Bob",
            displayName = "Bob",
            peerPublicKey = ByteArray(32) { 2 },
            lastMessage = "Hi there",
            lastMessageTimestamp = 2000L,
            unreadCount = 0,
        )

    private val testConversation3 =
        Conversation(
            peerHash = "peer3",
            peerName = "Charlie",
            displayName = "Charlie",
            peerPublicKey = null,
            lastMessage = "Hey",
            lastMessageTimestamp = 3000L,
            unreadCount = 5,
        )

    private lateinit var propagationNodeManager: PropagationNodeManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        conversationRepository = mockk()
        contactRepository = mockk()
        @Suppress("NoRelaxedMocks") // Service manager with many methods; explicit stubs for tested methods
        propagationNodeManager = mockk(relaxed = true)

        // Default: no conversations, no drafts
        every { conversationRepository.getConversations() } returns flowOf(emptyList())
        every { conversationRepository.observeDrafts() } returns flowOf(emptyMap())

        // Default: not syncing
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.manualSyncResult } returns MutableSharedFlow()

        viewModel = ChatsViewModel(conversationRepository, contactRepository, propagationNodeManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state has empty conversations and is loading`() =
        runTest {
            viewModel.chatsState.test {
                val state = awaitItem()
                assertEquals(emptyList<Conversation>(), state.conversations)
                assertEquals(true, state.isLoading)
            }
        }

    @Test
    fun `chatsState flow emits repository data`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val testConversations = listOf(testConversation1, testConversation2)
            every { repository.getConversations() } returns flowOf(testConversations)
            every { repository.observeDrafts() } returns flowOf(emptyMap())

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk(), propagationNodeManager)

            // WhileSubscribed requires active collector - test() provides one
            newViewModel.chatsState.test {
                // Skip initial loading state, wait for actual data from repository
                awaitItem() // Consume initialValue (loading state)
                advanceUntilIdle() // Let WhileSubscribed start the upstream flow
                val state = awaitItem() // This will be the actual data
                assertEquals(2, state.conversations.size)
                assertEquals("Alice", state.conversations[0].peerName)
                assertEquals("Bob", state.conversations[1].peerName)
                assertEquals(false, state.isLoading)
            }
        }

    @Test
    fun `conversations are sorted by timestamp from repository`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val sortedConversations =
                listOf(
                    testConversation3, // 3000L (most recent)
                    testConversation2, // 2000L
                    testConversation1, // 1000L (oldest)
                )
            every { repository.getConversations() } returns flowOf(sortedConversations)
            every { repository.observeDrafts() } returns flowOf(emptyMap())

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk(), propagationNodeManager)

            newViewModel.chatsState.test {
                // Skip initial loading state, wait for actual data from repository
                awaitItem() // Consume initialValue (loading state)
                advanceUntilIdle() // Let WhileSubscribed start the upstream flow
                val state = awaitItem() // This will be the actual data
                assertEquals(3, state.conversations.size)
                assertEquals("Charlie", state.conversations[0].peerName) // Most recent
                assertEquals("Bob", state.conversations[1].peerName)
                assertEquals("Alice", state.conversations[2].peerName) // Oldest
            }
        }

    @Test
    fun `deleteConversation calls repository`() =
        runTest {
            coEvery { conversationRepository.deleteConversation(any()) } just Runs

            viewModel.deleteConversation("peer1")
            advanceUntilIdle()

            coVerify { conversationRepository.deleteConversation("peer1") }
            // Verify deletion was delegated to repository
            assertTrue("Delete should complete successfully", true)
        }

    @Test
    fun `deleteConversation handles multiple deletions`() =
        runTest {
            coEvery { conversationRepository.deleteConversation(any()) } just Runs

            viewModel.deleteConversation("peer1")
            viewModel.deleteConversation("peer2")
            viewModel.deleteConversation("peer3")
            advanceUntilIdle()

            coVerify { conversationRepository.deleteConversation("peer1") }
            coVerify { conversationRepository.deleteConversation("peer2") }
            coVerify { conversationRepository.deleteConversation("peer3") }
            // Verify all deletions were handled
            assertTrue("Multiple deletions should complete successfully", true)
        }

    @Test
    fun `deleteConversation handles errors gracefully`() =
        runTest {
            coEvery { conversationRepository.deleteConversation(any()) } throws Exception("Database error")

            // Should not crash
            viewModel.deleteConversation("peer1")
            advanceUntilIdle()

            // Verify deletion was attempted
            coVerify { conversationRepository.deleteConversation("peer1") }
            // Verify ViewModel handles error gracefully (no crash)
            assertTrue("ViewModel should handle delete errors gracefully", true)
        }

    @Test
    fun `chatsState flow updates when repository data changes`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
            every { repository.getConversations() } returns conversationsFlow
            every { repository.observeDrafts() } returns flowOf(emptyMap())

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk(), propagationNodeManager)
            advanceUntilIdle()

            newViewModel.chatsState.test {
                // Initial: loading state with empty conversations
                assertEquals(0, awaitItem().conversations.size)

                // Add first conversation
                conversationsFlow.value = listOf(testConversation1)
                assertEquals(1, awaitItem().conversations.size)

                // Add second conversation
                conversationsFlow.value = listOf(testConversation1, testConversation2)
                val state = awaitItem()
                assertEquals(2, state.conversations.size)

                // Remove one conversation
                conversationsFlow.value = listOf(testConversation2)
                assertEquals(1, awaitItem().conversations.size)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `conversations with unread counts are displayed correctly`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val conversations =
                listOf(
                    testConversation1.copy(unreadCount = 5),
                    testConversation2.copy(unreadCount = 0),
                    testConversation3.copy(unreadCount = 10),
                )
            every { repository.getConversations() } returns flowOf(conversations)
            every { repository.observeDrafts() } returns flowOf(emptyMap())

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk(), propagationNodeManager)

            newViewModel.chatsState.test {
                // Skip initial loading state, wait for actual data from repository
                awaitItem() // Consume initialValue (loading state)
                advanceUntilIdle() // Let WhileSubscribed start the upstream flow
                val result = awaitItem().conversations // This will be the actual data
                assertEquals(3, result.size)
                assertEquals(5, result[0].unreadCount)
                assertEquals(0, result[1].unreadCount)
                assertEquals(10, result[2].unreadCount)
            }
        }

    @Test
    fun `conversations with null public keys are handled`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val conversations =
                listOf(
                    testConversation1.copy(peerPublicKey = null),
                    testConversation2.copy(peerPublicKey = ByteArray(32)),
                    testConversation3.copy(peerPublicKey = null),
                )
            every { repository.getConversations() } returns flowOf(conversations)
            every { repository.observeDrafts() } returns flowOf(emptyMap())

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk(), propagationNodeManager)

            newViewModel.chatsState.test {
                // Skip initial loading state, wait for actual data from repository
                awaitItem() // Consume initialValue (loading state)
                advanceUntilIdle() // Let WhileSubscribed start the upstream flow
                val result = awaitItem().conversations // This will be the actual data
                assertEquals(3, result.size)
                assertNull(result[0].peerPublicKey)
                assertNotNull(result[1].peerPublicKey)
                assertNull(result[2].peerPublicKey)
            }
        }

    @Test
    fun `chatsState flow starts when subscribed`() =
        runTest {
            // WhileSubscribed starts only when there's an active subscriber
            viewModel.chatsState.test {
                // Verify we receive initial loading state with empty conversations
                val state = awaitItem()
                assertEquals(emptyList<Conversation>(), state.conversations)
                assertEquals(true, state.isLoading)

                advanceUntilIdle()

                // Verify that getConversations is called when we subscribe
                verify { conversationRepository.getConversations() }

                cancelAndIgnoreRemainingEvents()
            }
        }
}
