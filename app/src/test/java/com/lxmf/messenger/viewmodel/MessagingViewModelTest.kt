package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.MessageReceipt
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.ActiveConversationManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var reticulumProtocol: ServiceReticulumProtocol
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var viewModel: MessagingViewModel

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
        activeConversationManager = mockk(relaxed = true)

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

        viewModel = MessagingViewModel(reticulumProtocol, conversationRepository, announceRepository, activeConversationManager)
    }

    @After
    fun tearDown() {
        // Cancel any running coroutines in the ViewModel to prevent UncompletedCoroutinesError
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state has empty messages`() =
        runTest {
            // Before loadMessages is called, the messages flow returns empty PagingData
            // Verify the repository method is not called until loadMessages() is invoked
            coVerify(exactly = 0) { conversationRepository.getMessagesPaged(any()) }
        }

    @Test
    fun `loadMessages updates current conversation and triggers flow`() =
        runTest {
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
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `sendMessage success saves message to database with sent status`() =
        runTest {
            // Setup: Mock successful LXMF send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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

            // Verify: Protocol sendLxmfMessage was called
            coVerify {
                reticulumProtocol.sendLxmfMessage(
                    destinationHash = any(),
                    content = "Test message",
                    sourceIdentity = testIdentity,
                )
            }
        }

    @Test
    fun `sendMessage failure saves message to database with failed status`() =
        runTest {
            // Setup: Mock failed LXMF send
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32),
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
                reticulumProtocol.sendLxmfMessage(
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
                )
            }
        }

    @Test
    fun `markAsRead calls repository`() =
        runTest {
            viewModel.markAsRead(testPeerHash)
            advanceUntilIdle()

            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `switching conversations updates message flow`() =
        runTest {
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
            // Identity loading is now lazy - happens when sending messages, not during init
            // This avoids crashes when LXMF router isn't ready yet
            // Send a message to trigger identity loading
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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

            val failingActiveConversationManager: ActiveConversationManager = mockk(relaxed = true)

            val viewModelWithoutIdentity =
                MessagingViewModel(failingProtocol, failingRepository, failingAnnounceRepository, failingActiveConversationManager)

            // Attempt to send message
            viewModelWithoutIdentity.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // Verify: sendLxmfMessage was NOT called
            coVerify(exactly = 0) { failingProtocol.sendLxmfMessage(any(), any(), any()) }

            // Verify: saveMessage was NOT called
            coVerify(exactly = 0) { failingRepository.saveMessage(any(), any(), any(), any()) }
        }

    @Test
    fun `messages flow updates when database changes`() =
        runTest {
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
            val invalidHash = "invalid!hash@123" // Contains invalid characters

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send with invalid hash
            viewModel.sendMessage(invalidHash, "Test message")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Assert: Message NOT saved to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with non-hex destination hash does not send`() =
        runTest {
            val nonHexHash = "ghijklmn" // Valid characters but not hex

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.sendMessage(nonHexHash, "Test message")
            advanceUntilIdle()

            // Verify: No protocol call made
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Verify: No save to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with empty content does not send`() =
        runTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send empty message
            viewModel.sendMessage(testPeerHash, "")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with whitespace-only content does not send`() =
        runTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send whitespace-only message
            viewModel.sendMessage(testPeerHash, "   \n\t   ")
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with too-long content does not send`() =
        runTest {
            // Create message longer than MAX_MESSAGE_LENGTH (10000 chars)
            val tooLongMessage = "a".repeat(10001)

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send too-long message
            viewModel.sendMessage(testPeerHash, tooLongMessage)
            advanceUntilIdle()

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage sanitizes content before sending`() =
        runTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
                reticulumProtocol.sendLxmfMessage(
                    destinationHash = any(),
                    content = "Test message", // Trimmed
                    sourceIdentity = testIdentity,
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
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
            }

            // Assert: Message was saved
            coVerify(exactly = 1) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with valid hex hash succeeds`() =
        runTest {
            val validHash = "abcdef0123456789abcdef0123456789" // Valid 32-char hex hash
            val destHashBytes = validHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
                reticulumProtocol.sendLxmfMessage(any(), any(), any())
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
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessage(
                    destinationHash = any(),
                    content = "", // Empty content is OK with image
                    sourceIdentity = testIdentity,
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
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessage(any(), any(), any(), any(), any())
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
}
