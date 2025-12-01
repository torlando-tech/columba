package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.Conversation
import com.lxmf.messenger.data.repository.ConversationRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            peerPublicKey = ByteArray(32) { 1 },
            lastMessage = "Hello",
            lastMessageTimestamp = 1000L,
            unreadCount = 2,
        )

    private val testConversation2 =
        Conversation(
            peerHash = "peer2",
            peerName = "Bob",
            peerPublicKey = ByteArray(32) { 2 },
            lastMessage = "Hi there",
            lastMessageTimestamp = 2000L,
            unreadCount = 0,
        )

    private val testConversation3 =
        Conversation(
            peerHash = "peer3",
            peerName = "Charlie",
            peerPublicKey = null,
            lastMessage = "Hey",
            lastMessageTimestamp = 3000L,
            unreadCount = 5,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        conversationRepository = mockk()
        contactRepository = mockk()

        // Default: no conversations
        every { conversationRepository.getConversations() } returns flowOf(emptyList())

        viewModel = ChatsViewModel(conversationRepository, contactRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state has empty conversations`() =
        runTest {
            viewModel.conversations.test {
                assertEquals(emptyList<Conversation>(), awaitItem())
            }
        }

    @Test
    fun `conversations flow emits repository data`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val testConversations = listOf(testConversation1, testConversation2)
            every { repository.getConversations() } returns flowOf(testConversations)

            // NOW create ViewModel - it will immediately start collecting
            val newViewModel = ChatsViewModel(repository, mockk())
            advanceUntilIdle() // Let the flow collect

            newViewModel.conversations.test {
                val conversations = awaitItem()
                assertEquals(2, conversations.size)
                assertEquals("Alice", conversations[0].peerName)
                assertEquals("Bob", conversations[1].peerName)
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

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk())
            advanceUntilIdle()

            newViewModel.conversations.test {
                val conversations = awaitItem()
                assertEquals(3, conversations.size)
                assertEquals("Charlie", conversations[0].peerName) // Most recent
                assertEquals("Bob", conversations[1].peerName)
                assertEquals("Alice", conversations[2].peerName) // Oldest
            }
        }

    @Test
    fun `deleteConversation calls repository`() =
        runTest {
            coEvery { conversationRepository.deleteConversation(any()) } just Runs

            viewModel.deleteConversation("peer1")
            advanceUntilIdle()

            coVerify { conversationRepository.deleteConversation("peer1") }
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
        }

    @Test
    fun `conversations flow updates when repository data changes`() =
        runTest {
            // Create fresh mocks and configure BEFORE creating ViewModel
            val repository: ConversationRepository = mockk()
            val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
            every { repository.getConversations() } returns conversationsFlow

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk())
            advanceUntilIdle()

            newViewModel.conversations.test {
                // Initial: empty
                assertEquals(0, awaitItem().size)

                // Add first conversation
                conversationsFlow.value = listOf(testConversation1)
                assertEquals(1, awaitItem().size)

                // Add second conversation
                conversationsFlow.value = listOf(testConversation1, testConversation2)
                val conversations = awaitItem()
                assertEquals(2, conversations.size)

                // Remove one conversation
                conversationsFlow.value = listOf(testConversation2)
                assertEquals(1, awaitItem().size)

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

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk())
            advanceUntilIdle()

            newViewModel.conversations.test {
                val result = awaitItem()
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

            // NOW create ViewModel
            val newViewModel = ChatsViewModel(repository, mockk())
            advanceUntilIdle()

            newViewModel.conversations.test {
                val result = awaitItem()
                assertEquals(3, result.size)
                assertNull(result[0].peerPublicKey)
                assertNotNull(result[1].peerPublicKey)
                assertNull(result[2].peerPublicKey)
            }
        }

    @Test
    fun `conversations flow is eagerly started`() =
        runTest {
            // Advance dispatcher to execute StateFlow initialization
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify that getConversations is called immediately on ViewModel creation
            verify { conversationRepository.getConversations() }
        }
}
