package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.repository.ConversationRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Unit tests for MessageDetailViewModel.
 *
 * Tests the reactive message observation feature that allows the Message Details
 * screen to automatically update when message status changes (e.g., pending → delivered).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDetailViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    // Use UnconfinedTestDispatcher for immediate execution of coroutines
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var viewModel: MessageDetailViewModel

    private val testMessageId = "test-message-id-12345"
    private val testConversationHash = "abcdef0123456789"
    private val testIdentityHash = "identity-hash-12345"

    /**
     * Create a test MessageEntity with the given parameters.
     */
    private fun createTestMessageEntity(
        id: String = testMessageId,
        status: String = "pending",
        deliveryMethod: String? = "direct",
        errorMessage: String? = null,
        content: String = "Test message content",
    ) = MessageEntity(
        id = id,
        conversationHash = testConversationHash,
        identityHash = testIdentityHash,
        content = content,
        timestamp = System.currentTimeMillis(),
        isFromMe = true,
        status = status,
        isRead = true,
        fieldsJson = null,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        conversationRepository = mockk()
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial message state is null before loadMessage is called`() =
        runTest {
            // Given: A new ViewModel with no message loaded
            every { conversationRepository.observeMessageById(any()) } returns flowOf(null)
            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }

            // Then: Message should be null
            assertNull(viewModel.message.value)

            collectJob.cancel()
        }

    // ========== Message Loading Tests ==========

    @Test
    fun `loadMessage triggers observation of the message`() =
        runTest {
            // Given: Repository returns a message flow
            val messageEntity = createTestMessageEntity(status = "pending")
            every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(messageEntity)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle() // Ensure subscription is established

            // When: Load message is called
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle() // Ensure all coroutines complete

            // Then: Repository should be called to observe the message
            verify { conversationRepository.observeMessageById(testMessageId) }

            // And: Message should be loaded
            val message = viewModel.message.value
            assertNotNull(message)
            assertEquals(testMessageId, message?.id)
            assertEquals("pending", message?.status)

            collectJob.cancel()
        }

    @Test
    fun `loadMessage converts entity to MessageUi correctly`() =
        runTest {
            // Given: Repository returns a message with all fields populated
            val messageEntity =
                createTestMessageEntity(
                    status = "delivered",
                    deliveryMethod = "opportunistic",
                    content = "Hello, World!",
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(messageEntity)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message is called
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: All fields should be correctly mapped
            val message = viewModel.message.value
            assertNotNull(message)
            assertEquals(testMessageId, message?.id)
            assertEquals("delivered", message?.status)
            assertEquals("opportunistic", message?.deliveryMethod)
            assertEquals("Hello, World!", message?.content)
            assertEquals(true, message?.isFromMe)

            collectJob.cancel()
        }

    @Test
    fun `loadMessage handles null message (not found) gracefully`() =
        runTest {
            // Given: Repository returns null (message not found)
            every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(null)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message is called
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: Message should be null
            assertNull(viewModel.message.value)

            collectJob.cancel()
        }

    // ========== Reactive Update Tests ==========

    @Test
    fun `message state updates when status changes from pending to delivered`() =
        runTest {
            // Given: A MutableStateFlow to simulate database changes
            val messageFlow =
                MutableStateFlow<MessageEntity?>(
                    createTestMessageEntity(status = "pending"),
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns messageFlow

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: Initial status should be pending
            assertEquals("pending", viewModel.message.value?.status)

            // When: Status changes to delivered (simulating delivery proof received)
            messageFlow.value = createTestMessageEntity(status = "delivered")
            advanceUntilIdle()

            // Then: UI should reflect the new status
            assertEquals("delivered", viewModel.message.value?.status)

            collectJob.cancel()
        }

    @Test
    fun `message state updates when status changes from pending to failed`() =
        runTest {
            // Given: A MutableStateFlow to simulate database changes
            val messageFlow =
                MutableStateFlow<MessageEntity?>(
                    createTestMessageEntity(status = "pending"),
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns messageFlow

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: Initial status should be pending
            assertEquals("pending", viewModel.message.value?.status)
            assertNull(viewModel.message.value?.errorMessage)

            // When: Status changes to failed with error message
            messageFlow.value =
                createTestMessageEntity(
                    status = "failed",
                    errorMessage = "Connection timeout",
                )
            advanceUntilIdle()

            // Then: UI should reflect the failure and error message
            assertEquals("failed", viewModel.message.value?.status)
            assertEquals("Connection timeout", viewModel.message.value?.errorMessage)

            collectJob.cancel()
        }

    @Test
    fun `message state updates when delivery method changes`() =
        runTest {
            // Given: A message initially sent via direct
            val messageFlow =
                MutableStateFlow<MessageEntity?>(
                    createTestMessageEntity(status = "pending", deliveryMethod = "direct"),
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns messageFlow

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: Initial delivery method should be direct
            assertEquals("direct", viewModel.message.value?.deliveryMethod)

            // When: Message is retried via propagation (retrying_propagated scenario)
            messageFlow.value =
                createTestMessageEntity(
                    status = "pending",
                    deliveryMethod = "propagated",
                )
            advanceUntilIdle()

            // Then: UI should reflect the new delivery method
            assertEquals("propagated", viewModel.message.value?.deliveryMethod)

            collectJob.cancel()
        }

    // ========== Message Switching Tests ==========

    @Test
    fun `switching to a different message updates the observed message`() =
        runTest {
            // Given: Two different messages
            val firstMessageId = "first-message-id"
            val secondMessageId = "second-message-id"

            val firstMessage = createTestMessageEntity(id = firstMessageId, content = "First message")
            val secondMessage = createTestMessageEntity(id = secondMessageId, content = "Second message")

            every { conversationRepository.observeMessageById(firstMessageId) } returns flowOf(firstMessage)
            every { conversationRepository.observeMessageById(secondMessageId) } returns flowOf(secondMessage)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load first message
            viewModel.loadMessage(firstMessageId)
            advanceUntilIdle()

            // Then: First message should be loaded
            assertEquals(firstMessageId, viewModel.message.value?.id)
            assertEquals("First message", viewModel.message.value?.content)

            // When: Switch to second message
            viewModel.loadMessage(secondMessageId)
            advanceUntilIdle()

            // Then: Second message should be loaded
            assertEquals(secondMessageId, viewModel.message.value?.id)
            assertEquals("Second message", viewModel.message.value?.content)

            collectJob.cancel()
        }

    @Test
    fun `switching messages cancels previous observation (flatMapLatest behavior)`() =
        runTest {
            // Given: A slow-emitting first message and a fast second message
            val firstMessageId = "first-message-id"
            val secondMessageId = "second-message-id"

            val firstMessageFlow = MutableStateFlow<MessageEntity?>(null)
            val secondMessage = createTestMessageEntity(id = secondMessageId, content = "Second message")

            every { conversationRepository.observeMessageById(firstMessageId) } returns firstMessageFlow
            every { conversationRepository.observeMessageById(secondMessageId) } returns flowOf(secondMessage)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load first message (but it hasn't emitted yet)
            viewModel.loadMessage(firstMessageId)
            advanceUntilIdle()

            // When: Immediately switch to second message
            viewModel.loadMessage(secondMessageId)
            advanceUntilIdle()

            // Then: Should show second message (first should be cancelled)
            assertEquals(secondMessageId, viewModel.message.value?.id)

            // When: First message finally emits (after we've switched away)
            firstMessageFlow.value = createTestMessageEntity(id = firstMessageId, content = "First message")
            advanceUntilIdle()

            // Then: Should still show second message (first observation was cancelled)
            assertEquals(secondMessageId, viewModel.message.value?.id)

            collectJob.cancel()
        }

    // ========== Edge Case Tests ==========

    @Test
    fun `handles all delivery methods correctly`() =
        runTest {
            val deliveryMethods = listOf("opportunistic", "direct", "propagated")

            for (method in deliveryMethods) {
                val messageEntity = createTestMessageEntity(deliveryMethod = method)
                every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(messageEntity)

                viewModel = MessageDetailViewModel(conversationRepository)

                // Start collecting to trigger the StateFlow
                val collectJob = launch { viewModel.message.collect {} }
                advanceUntilIdle()

                viewModel.loadMessage(testMessageId)
                advanceUntilIdle()

                assertEquals(
                    "Delivery method $method should be correctly mapped",
                    method,
                    viewModel.message.value?.deliveryMethod,
                )

                collectJob.cancel()
                viewModel.viewModelScope.cancel()
                clearAllMocks()
                conversationRepository = mockk()
            }
        }

    @Test
    fun `handles all status values correctly`() =
        runTest {
            val statuses = listOf("pending", "sent", "delivered", "failed")

            for (status in statuses) {
                val messageEntity = createTestMessageEntity(status = status)
                every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(messageEntity)

                viewModel = MessageDetailViewModel(conversationRepository)

                // Start collecting to trigger the StateFlow
                val collectJob = launch { viewModel.message.collect {} }
                advanceUntilIdle()

                viewModel.loadMessage(testMessageId)
                advanceUntilIdle()

                assertEquals(
                    "Status $status should be correctly mapped",
                    status,
                    viewModel.message.value?.status,
                )

                collectJob.cancel()
                viewModel.viewModelScope.cancel()
                clearAllMocks()
                conversationRepository = mockk()
            }
        }

    @Test
    fun `handles message with null optional fields`() =
        runTest {
            // Given: A message with null deliveryMethod and errorMessage
            val messageEntity =
                createTestMessageEntity(
                    deliveryMethod = null,
                    errorMessage = null,
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns flowOf(messageEntity)

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Then: Optional fields should be null in the UI model
            val message = viewModel.message.value
            assertNotNull(message)
            assertNull(message?.deliveryMethod)
            assertNull(message?.errorMessage)

            collectJob.cancel()
        }

    @Test
    fun `multiple rapid status updates are all reflected`() =
        runTest {
            // Given: A MutableStateFlow to simulate rapid database changes
            val messageFlow =
                MutableStateFlow<MessageEntity?>(
                    createTestMessageEntity(status = "pending"),
                )
            every { conversationRepository.observeMessageById(testMessageId) } returns messageFlow

            viewModel = MessageDetailViewModel(conversationRepository)

            // Start collecting to trigger the StateFlow
            val collectJob = launch { viewModel.message.collect {} }
            advanceUntilIdle()

            // When: Load message
            viewModel.loadMessage(testMessageId)
            advanceUntilIdle()

            // Simulate rapid status transitions: pending → sent → delivered
            val statusSequence = listOf("pending", "sent", "delivered")
            for (status in statusSequence) {
                messageFlow.value = createTestMessageEntity(status = status)
                advanceUntilIdle()
                assertEquals(status, viewModel.message.value?.status)
            }

            collectJob.cancel()
        }
}
