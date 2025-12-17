package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.DeliveryStatusUpdate
import com.lxmf.messenger.reticulum.protocol.MessageReceipt
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.ActiveConversationManager
import com.lxmf.messenger.service.PropagationNodeManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.lxmf.messenger.data.repository.Message as DataMessage

/**
 * Unit tests for MessagingViewModel.
 * Tests message loading, sending, state management, and repository interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    // Use UnconfinedTestDispatcher to avoid race conditions in ViewModel init coroutines
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var reticulumProtocol: ServiceReticulumProtocol
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var propagationNodeManager: PropagationNodeManager

    private val testPeerHash = "abcdef0123456789abcdef0123456789" // Valid 32-char hex hash
    private val testPeerName = "Test Peer"
    private val testIdentity =
        Identity(
            hash = ByteArray(16) { it.toByte() },
            publicKey = ByteArray(32) { it.toByte() },
            privateKey = ByteArray(32) { it.toByte() },
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        reticulumProtocol = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        activeConversationManager = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        propagationNodeManager = mockk(relaxed = true)

        // Mock default contact repository behavior
        every { contactRepository.hasContactFlow(any()) } returns flowOf(false)
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.addContactFromConversation(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.deleteContact(any()) } just Runs

        // Mock propagationNodeManager flows
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.manualSyncResult } returns MutableSharedFlow()

        // Mock default behaviors
        coEvery { reticulumProtocol.getLxmfIdentity() } returns Result.success(testIdentity)
        every { reticulumProtocol.setConversationActive(any()) } just Runs
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.markConversationAsRead(any()) } just Runs

        // Mock delivery status observer (returns empty flow by default)
        every { reticulumProtocol.observeDeliveryStatus() } returns flowOf()

        // Mock database methods needed by delivery status handler
        coEvery { conversationRepository.getMessageById(any()) } returns null
        coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs

        // Default: no messages (mock both old and new methods for compatibility)
        every { conversationRepository.getMessages(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.getMessagesPaged(any()) } returns flowOf(PagingData.empty())

        // Default: no announce info
        every { announceRepository.getAnnounceFlow(any()) } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /**
     * Creates a new ViewModel instance inside a test's runTest scope.
     * This ensures coroutines launched during init are properly tracked by the test infrastructure.
     */
    private fun createTestViewModel(): MessagingViewModel =
        MessagingViewModel(
            reticulumProtocol,
            conversationRepository,
            announceRepository,
            contactRepository,
            activeConversationManager,
            settingsRepository,
            propagationNodeManager,
        )

    @Test
    fun `initial state has empty messages`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Before loadMessages is called, the messages flow returns empty PagingData
            // Verify the repository method is not called until loadMessages() is invoked
            coVerify(exactly = 0) { conversationRepository.getMessagesPaged(any()) }
        }

    @Test
    fun `loadMessages updates current conversation and triggers flow`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Act: Load messages for conversation
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify: Repository was called with correct peer hash
            coVerify { conversationRepository.getMessagesPaged(testPeerHash) }

            // Verify: Conversation marked as read
            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }

            // Verify: Fast polling enabled
            verify { reticulumProtocol.setConversationActive(true) }
        }

    @Test
    fun `loadMessages marks conversation as read`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `sendMessage success saves message to database with sent status`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock successful LXMF send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            // Act: Send message
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            viewModel.sendMessage(testPeerHash, "Test message")
            advanceUntilIdle()

            // Assert: Message saved to database with "pending" status (will be updated to "delivered" by delivery status observer)
            coVerify {
                conversationRepository.saveMessage(
                    peerHash = testPeerHash,
                    peerName = testPeerName,
                    message = match { it.content == "Test message" && it.status == "pending" && it.isFromMe },
                    peerPublicKey = null,
                )
            }

            // Verify: Protocol sendLxmfMessageWithMethod was called
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "Test message",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }
        }

    @Test
    fun `sendMessage failure saves message to database with failed status`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock failed LXMF send
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            // Act: Send message
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            viewModel.sendMessage(testPeerHash, "Test message")
            advanceUntilIdle()

            // Assert: Message saved with failed status
            coVerify {
                conversationRepository.saveMessage(
                    peerHash = testPeerHash,
                    peerName = testPeerName,
                    message = match { it.content == "Test message" && it.status == "failed" && it.isFromMe },
                    peerPublicKey = null,
                )
            }
        }

    @Test
    fun `sendMessage converts destination hash to bytes correctly`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32),
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            viewModel.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // Verify: Destination hash was converted to bytes
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash =
                        match {
                            // "abcdef0123456789abcdef0123456789" -> 16 bytes
                            val expected =
                                byteArrayOf(
                                    0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x01, 0x23, 0x45, 0x67, 0x89.toByte(),
                                    0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x01, 0x23, 0x45, 0x67, 0x89.toByte(),
                                )
                            it.contentEquals(expected)
                        },
                    content = "Test",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }
        }

    @Test
    fun `markAsRead calls repository`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.markAsRead(testPeerHash)
            advanceUntilIdle()

            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `switching conversations updates message flow`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val conversation1Hash = "peer1"
            val conversation2Hash = "peer2"

            // Load first conversation
            viewModel.loadMessages(conversation1Hash, "Peer 1")
            advanceUntilIdle()

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify first conversation was loaded
            coVerify { conversationRepository.getMessagesPaged(conversation1Hash) }

            // Switch to second conversation
            viewModel.loadMessages(conversation2Hash, "Peer 2")
            advanceUntilIdle()

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify second conversation was loaded
            coVerify { conversationRepository.getMessagesPaged(conversation2Hash) }

            // Verify both conversations were marked as read
            coVerify { conversationRepository.markConversationAsRead(conversation1Hash) }
            coVerify { conversationRepository.markConversationAsRead(conversation2Hash) }
        }

    @Test
    fun `identity loads successfully on init`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Identity loading is now lazy - happens when sending messages, not during init
            // This avoids crashes when LXMF router isn't ready yet
            // Send a message to trigger identity loading
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(MessageReceipt(ByteArray(32), 3000L, testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // Verify the protocol was called to get identity
            coVerify { reticulumProtocol.getLxmfIdentity() }
        }

    @Test
    fun `sendMessage without loaded identity logs error and returns early`() =
        runTest {
            // Clear existing mocks and create new ones that fail identity loading
            clearAllMocks()

            val failingProtocol: ServiceReticulumProtocol = mockk()
            coEvery { failingProtocol.getLxmfIdentity() } returns Result.failure(Exception("No identity"))
            every { failingProtocol.setConversationActive(any()) } just Runs
            every { failingProtocol.observeDeliveryStatus() } returns flowOf()

            val failingRepository: ConversationRepository = mockk()
            every { failingRepository.getMessages(any()) } returns flowOf(emptyList())
            coEvery { failingRepository.getPeerPublicKey(any()) } returns null
            coEvery { failingRepository.getMessageById(any()) } returns null
            coEvery { failingRepository.updateMessageStatus(any(), any()) } just Runs

            val failingAnnounceRepository: AnnounceRepository = mockk()
            every { failingAnnounceRepository.getAnnounceFlow(any()) } returns flowOf(null)

            val failingContactRepository: ContactRepository = mockk()
            every { failingContactRepository.hasContactFlow(any()) } returns flowOf(false)

            val failingActiveConversationManager: ActiveConversationManager = mockk(relaxed = true)
            val failingSettingsRepository: SettingsRepository = mockk(relaxed = true)
            val failingPropagationNodeManager: PropagationNodeManager = mockk(relaxed = true)
            every { failingPropagationNodeManager.isSyncing } returns MutableStateFlow(false)
            every { failingPropagationNodeManager.manualSyncResult } returns MutableSharedFlow()

            val viewModelWithoutIdentity =
                MessagingViewModel(
                    failingProtocol,
                    failingRepository,
                    failingAnnounceRepository,
                    failingContactRepository,
                    failingActiveConversationManager,
                    failingSettingsRepository,
                    failingPropagationNodeManager,
                )

            // Attempt to send message
            viewModelWithoutIdentity.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // Verify: sendLxmfMessageWithMethod was NOT called
            coVerify(exactly = 0) { failingProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any()) }

            // Verify: saveMessage was NOT called
            coVerify(exactly = 0) { failingRepository.saveMessage(any(), any(), any(), any()) }
        }

    @Test
    fun `messages flow updates when database changes`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Create a mutable flow to simulate database changes (using PagingData)
            val messagesFlow = MutableStateFlow<PagingData<DataMessage>>(PagingData.empty())
            coEvery { conversationRepository.getMessagesPaged(testPeerHash) } returns messagesFlow

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify repository was called
            coVerify { conversationRepository.getMessagesPaged(testPeerHash) }

            // Database emits new message - this tests that the ViewModel is wired up to observe changes
            messagesFlow.value =
                PagingData.from(
                    listOf(
                        DataMessage("m1", testPeerHash, "New message", 1000L, false),
                    ),
                )
            advanceUntilIdle()

            // The ViewModel's messages flow is connected to repository flow
            // We verify the connection was established (above verify call)
            // and that we can emit changes without errors
        }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `sendMessage with invalid destination hash does not send or save`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val invalidHash = "invalid!hash@123" // Contains invalid characters

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send with invalid hash
            viewModel.sendMessage(invalidHash, "Test message")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with non-hex destination hash does not send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val nonHexHash = "ghijklmn" // Valid characters but not hex

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.sendMessage(nonHexHash, "Test message")
            advanceUntilIdle()

            // Verify: No protocol call made
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Verify: No save to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with empty content does not send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send empty message
            viewModel.sendMessage(testPeerHash, "")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with whitespace-only content does not send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send whitespace-only message
            viewModel.sendMessage(testPeerHash, "   \n\t   ")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with too-long content does not send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Create message longer than MAX_MESSAGE_LENGTH (10000 chars)
            val tooLongMessage = "a".repeat(10001)

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send too-long message
            viewModel.sendMessage(testPeerHash, tooLongMessage)
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage sanitizes content before sending`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message with leading/trailing whitespace
            viewModel.sendMessage(testPeerHash, "  Test message  \n")
            advanceUntilIdle()

            // Assert: Message was trimmed before sending
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "Test message", // Trimmed
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }

            // Assert: Trimmed message saved to database
            coVerify {
                conversationRepository.saveMessage(
                    peerHash = testPeerHash,
                    peerName = testPeerName,
                    message = match { it.content == "Test message" && it.isFromMe },
                    peerPublicKey = null,
                )
            }
        }

    @Test
    fun `sendMessage accepts valid message at max length`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message exactly at max length (10000 chars)
            val maxLengthMessage = "a".repeat(10000)
            viewModel.sendMessage(testPeerHash, maxLengthMessage)
            advanceUntilIdle()

            // Assert: Protocol was called (message is valid)
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message was saved
            coVerify(exactly = 1) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with valid hex hash succeeds`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val validHash = "abcdef0123456789abcdef0123456789" // Valid 32-char hex hash
            val destHashBytes = validHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(validHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message with valid hash
            viewModel.sendMessage(validHash, "Test message")
            advanceUntilIdle()

            // Assert: Protocol was called
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message was saved
            coVerify(exactly = 1) {
                conversationRepository.saveMessage(
                    peerHash = validHash,
                    peerName = testPeerName,
                    message = any(),
                    peerPublicKey = null,
                )
            }
        }

    // ========== IMAGE ATTACHMENT TESTS ==========

    @Test
    fun `sendMessage with empty content but image attached succeeds`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Select an image first
            val testImageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
            viewModel.selectImage(testImageData, "png")
            advanceUntilIdle()

            // Act: Send message with empty content but image attached
            viewModel.sendMessage(testPeerHash, "")
            advanceUntilIdle()

            // Assert: Protocol was called with image data
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "", // Empty content is OK with image
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = testImageData,
                    imageFormat = "png",
                )
            }

            // Assert: Message was saved
            coVerify(exactly = 1) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage clears image after successful send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Select an image
            val testImageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            viewModel.selectImage(testImageData, "png")
            advanceUntilIdle()

            // Verify image is selected
            assertEquals(testImageData, viewModel.selectedImageData.value)

            // Send message
            viewModel.sendMessage(testPeerHash, "Test with image")
            advanceUntilIdle()

            // Assert: Image was cleared after successful send
            assertEquals(null, viewModel.selectedImageData.value)
            assertEquals(null, viewModel.selectedImageFormat.value)
        }

    // ========== DELIVERY STATUS HANDLING TESTS ==========

    @Test
    fun `retrying_propagated status updates both status and deliveryMethod`() =
        runTest {
            // Setup: Create a flow that emits a retrying_propagated status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { reticulumProtocol.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database
            val testMessageHash = "test_message_hash_123"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "sent",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                reticulumProtocol,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
            )
            advanceUntilIdle()

            // Emit a retrying_propagated status update
            deliveryStatusFlow.emit(
                DeliveryStatusUpdate(
                    messageHash = testMessageHash,
                    status = "retrying_propagated",
                    timestamp = System.currentTimeMillis(),
                ),
            )
            advanceUntilIdle()

            // Verify: updateMessageStatus was called with retrying_propagated
            coVerify {
                conversationRepository.updateMessageStatus(testMessageHash, "retrying_propagated")
            }

            // Verify: updateMessageDeliveryDetails was called to change deliveryMethod to "propagated"
            coVerify {
                conversationRepository.updateMessageDeliveryDetails(
                    messageId = testMessageHash,
                    deliveryMethod = "propagated",
                    errorMessage = null,
                )
            }
        }

    @Test
    fun `delivered status updates message status only`() =
        runTest {
            // Setup: Create a flow that emits a delivered status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { reticulumProtocol.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database
            val testMessageHash = "delivered_message_hash"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "sent",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                reticulumProtocol,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
            )
            advanceUntilIdle()

            // Emit a delivered status update
            deliveryStatusFlow.emit(
                DeliveryStatusUpdate(
                    messageHash = testMessageHash,
                    status = "delivered",
                    timestamp = System.currentTimeMillis(),
                ),
            )
            advanceUntilIdle()

            // Verify: updateMessageStatus was called with delivered
            coVerify {
                conversationRepository.updateMessageStatus(testMessageHash, "delivered")
            }

            // Verify: updateMessageDeliveryDetails was NOT called (only called for retrying_propagated)
            coVerify(exactly = 0) {
                conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
            }
        }

    @Test
    fun `failed status updates message status`() =
        runTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { reticulumProtocol.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database
            val testMessageHash = "failed_message_hash"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "sent",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                reticulumProtocol,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
            )
            advanceUntilIdle()

            // Emit a failed status update
            deliveryStatusFlow.emit(
                DeliveryStatusUpdate(
                    messageHash = testMessageHash,
                    status = "failed",
                    timestamp = System.currentTimeMillis(),
                ),
            )
            advanceUntilIdle()

            // Verify: updateMessageStatus was called with failed
            coVerify {
                conversationRepository.updateMessageStatus(testMessageHash, "failed")
            }

            // Verify: updateMessageDeliveryDetails was NOT called (only called for retrying_propagated)
            coVerify(exactly = 0) {
                conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
            }
        }

    @Test
    fun `delivery status gracefully handles unknown message hash`() =
        runTest {
            // Setup: Create a flow that emits a status update for unknown message
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { reticulumProtocol.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message does NOT exist in database (returns null after retries)
            val unknownMessageHash = "unknown_message_hash"
            coEvery { conversationRepository.getMessageById(unknownMessageHash) } returns null
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                reticulumProtocol,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
            )
            advanceUntilIdle()

            // Emit a status update for unknown message
            deliveryStatusFlow.emit(
                DeliveryStatusUpdate(
                    messageHash = unknownMessageHash,
                    status = "delivered",
                    timestamp = System.currentTimeMillis(),
                ),
            )
            advanceUntilIdle()

            // Verify: getMessageById was called (with retries)
            coVerify(atLeast = 1) {
                conversationRepository.getMessageById(unknownMessageHash)
            }

            // Verify: updateMessageStatus was NOT called (message not found)
            coVerify(exactly = 0) {
                conversationRepository.updateMessageStatus(unknownMessageHash, any())
            }

            // Verify: No crash occurred - test completes successfully
        }

    // ========== CONTACT TOGGLE TESTS ==========

    @Test
    fun `isContactSaved returns false when contact not saved`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved (default mock behavior)
            every { contactRepository.hasContactFlow(testPeerHash) } returns flowOf(false)

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Assert: isContactSaved is false
            assertEquals(false, viewModel.isContactSaved.value)
        }

    @Test
    fun `isContactSaved has initial value of false before loading conversation`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Assert: Before loading any conversation, isContactSaved is false
            assertEquals(false, viewModel.isContactSaved.value)
        }

    @Test
    fun `toggleContact adds contact when not saved`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            val testPublicKey = ByteArray(64) { it.toByte() }
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns testPublicKey

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should add)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: addContactFromConversation was called
            coVerify {
                contactRepository.addContactFromConversation(testPeerHash, testPublicKey)
            }

            // Assert: deleteContact was NOT called
            coVerify(exactly = 0) {
                contactRepository.deleteContact(any())
            }
        }

    @Test
    fun `toggleContact removes contact when already saved`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is already saved
            coEvery { contactRepository.hasContact(testPeerHash) } returns true

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should remove)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: deleteContact was called
            coVerify {
                contactRepository.deleteContact(testPeerHash)
            }

            // Assert: addContactFromConversation was NOT called
            coVerify(exactly = 0) {
                contactRepository.addContactFromConversation(any(), any())
            }
        }

    @Test
    fun `toggleContact does nothing when no conversation loaded`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Don't load any conversation - _currentConversation is null

            // Act: Toggle contact (should do nothing)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: No contact repository methods were called
            coVerify(exactly = 0) {
                contactRepository.hasContact(any())
            }
            coVerify(exactly = 0) {
                contactRepository.addContactFromConversation(any(), any())
            }
            coVerify(exactly = 0) {
                contactRepository.deleteContact(any())
            }
        }

    @Test
    fun `toggleContact handles missing public key gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved, but public key is not available
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns null

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should fail gracefully)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: addContactFromConversation was NOT called (no public key)
            coVerify(exactly = 0) {
                contactRepository.addContactFromConversation(any(), any())
            }

            // Assert: deleteContact was NOT called
            coVerify(exactly = 0) {
                contactRepository.deleteContact(any())
            }
        }

    @Test
    fun `toggleContact handles repository exception gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved, repository throws exception
            coEvery { contactRepository.hasContact(testPeerHash) } throws RuntimeException("Database error")

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should not crash)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: No crash occurred - test completes successfully
            // Verify hasContact was called
            coVerify {
                contactRepository.hasContact(testPeerHash)
            }
        }

    // ========== CONTACT TOGGLE RESULT EMISSION TESTS ==========

    @Test
    fun `toggleContact emits Added result when contact successfully added`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved, public key is available
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            val testPublicKey = ByteArray(64) { it.toByte() }
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns testPublicKey

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Start collecting BEFORE toggling to ensure we catch the emission
            var emittedResult: ContactToggleResult? = null
            val collectJob =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    emittedResult = viewModel.contactToggleResult.first()
                }

            // Act: Toggle contact (should add)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: ContactToggleResult.Added was emitted
            assertEquals(ContactToggleResult.Added, emittedResult)
            collectJob.cancel() // Clean up if not completed
        }

    @Test
    fun `toggleContact emits Removed result when contact successfully removed`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is already saved
            coEvery { contactRepository.hasContact(testPeerHash) } returns true

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Start collecting BEFORE toggling to ensure we catch the emission
            var emittedResult: ContactToggleResult? = null
            val collectJob =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    emittedResult = viewModel.contactToggleResult.first()
                }

            // Act: Toggle contact (should remove)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: ContactToggleResult.Removed was emitted
            assertEquals(ContactToggleResult.Removed, emittedResult)
            collectJob.cancel()
        }

    @Test
    fun `toggleContact emits Error result when public key unavailable`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved, but public key is not available
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns null

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Start collecting BEFORE toggling to ensure we catch the emission
            var emittedResult: ContactToggleResult? = null
            val collectJob =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    emittedResult = viewModel.contactToggleResult.first()
                }

            // Act: Toggle contact (should fail with error)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: ContactToggleResult.Error was emitted with appropriate message
            assert(emittedResult is ContactToggleResult.Error)
            assert((emittedResult as ContactToggleResult.Error).message.contains("Identity not available"))
            collectJob.cancel()
        }

    @Test
    fun `toggleContact emits Error result on repository exception`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Contact is not saved, repository throws exception
            coEvery { contactRepository.hasContact(testPeerHash) } throws RuntimeException("Database error")

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Start collecting BEFORE toggling to ensure we catch the emission
            var emittedResult: ContactToggleResult? = null
            val collectJob =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    emittedResult = viewModel.contactToggleResult.first()
                }

            // Act: Toggle contact (should fail with error)
            viewModel.toggleContact()
            advanceUntilIdle()

            // Assert: ContactToggleResult.Error was emitted
            assert(emittedResult is ContactToggleResult.Error)
            assert((emittedResult as ContactToggleResult.Error).message.contains("Database error"))
            collectJob.cancel()
        }

    // ========== ASYNC IMAGE LOADING TESTS ==========
    // Note: More comprehensive tests for loadImageAsync are in MessageMapperTest and ImageCacheTest
    // using Robolectric. These tests verify the basic behavior without requiring Robolectric.

    @Test
    fun `loadImageAsync does not crash on null fieldsJson`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Call with null fieldsJson - should not crash
            viewModel.loadImageAsync("test-msg", null)
            advanceUntilIdle()

            // Assert: No crash occurred, loadedImageIds unchanged
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync does not crash on invalid JSON`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Call with invalid JSON - should not crash
            viewModel.loadImageAsync("test-msg", "not valid json")
            advanceUntilIdle()

            // Assert: No crash occurred, loadedImageIds unchanged (decode failed)
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadedImageIds initial state is empty`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Assert: Initial state is empty set
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

}
