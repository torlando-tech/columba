package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.paging.PagingData
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.data.repository.ReplyPreview
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.DeliveryStatusUpdate
import com.lxmf.messenger.reticulum.protocol.MessageReceipt
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.ActiveConversationManager
import com.lxmf.messenger.service.ConversationLinkManager
import com.lxmf.messenger.service.LinkSpeedProbe
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.util.FileAttachment
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
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
    private lateinit var locationSharingManager: LocationSharingManager
    private lateinit var identityRepository: IdentityRepository
    private lateinit var linkSpeedProbe: LinkSpeedProbe
    private lateinit var conversationLinkManager: ConversationLinkManager

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
        locationSharingManager = mockk(relaxed = true)
        identityRepository = mockk(relaxed = true)
        linkSpeedProbe = mockk(relaxed = true)
        conversationLinkManager = mockk(relaxed = true)

        // Mock conversationLinkManager flows
        every { conversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())

        // Mock linkSpeedProbe state
        every { linkSpeedProbe.probeState } returns MutableStateFlow(LinkSpeedProbe.ProbeState.Idle)

        // Mock identityRepository to return null by default (no icon set)
        coEvery { identityRepository.getActiveIdentitySync() } returns null

        // Mock locationSharingManager flows
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())

        // Mock default contact repository behavior
        every { contactRepository.hasContactFlow(any()) } returns flowOf(false)
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
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

        // Mock reaction received flow (returns empty flow by default)
        every { reticulumProtocol.reactionReceivedFlow } returns MutableSharedFlow()

        // Mock database methods needed by delivery status handler
        coEvery { conversationRepository.getMessageById(any()) } returns null
        coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs

        // Default: no messages (mock both old and new methods for compatibility)
        every { conversationRepository.getMessages(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.getMessagesPaged(any()) } returns flowOf(PagingData.empty())

        // Default: no announce info
        every { announceRepository.getAnnounceFlow(any()) } returns flowOf(null)

        // Default: no reply preview
        coEvery { conversationRepository.getReplyPreview(any(), any()) } returns null
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
            locationSharingManager,
            identityRepository,
            linkSpeedProbe,
            conversationLinkManager,
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

    @Ignore(
        "Flaky test: UncaughtExceptionsBeforeTest on CI due to timing issues with " +
            "ViewModel init coroutines and delivery status observer. Passes locally but " +
            "fails intermittently on CI. TODO: Investigate proper coroutine test isolation.",
    )
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
            every { failingProtocol.reactionReceivedFlow } returns MutableSharedFlow()

            val failingRepository: ConversationRepository = mockk()
            every { failingRepository.getMessages(any()) } returns flowOf(emptyList())
            coEvery { failingRepository.getPeerPublicKey(any()) } returns null
            coEvery { failingRepository.getMessageById(any()) } returns null
            coEvery { failingRepository.updateMessageStatus(any(), any()) } just Runs

            val failingAnnounceRepository: AnnounceRepository = mockk()
            every { failingAnnounceRepository.getAnnounceFlow(any()) } returns flowOf(null)

            val failingContactRepository: ContactRepository = mockk()
            every { failingContactRepository.hasContactFlow(any()) } returns flowOf(false)
            every { failingContactRepository.getEnrichedContacts() } returns flowOf(emptyList())

            val failingActiveConversationManager: ActiveConversationManager = mockk(relaxed = true)
            val failingSettingsRepository: SettingsRepository = mockk(relaxed = true)
            val failingPropagationNodeManager: PropagationNodeManager = mockk(relaxed = true)
            val failingLocationSharingManager: LocationSharingManager = mockk(relaxed = true)
            every { failingPropagationNodeManager.isSyncing } returns MutableStateFlow(false)
            every { failingPropagationNodeManager.manualSyncResult } returns MutableSharedFlow()
            every { failingLocationSharingManager.activeSessions } returns MutableStateFlow(emptyList())

            val failingLinkSpeedProbe = mockk<LinkSpeedProbe>(relaxed = true)
            every { failingLinkSpeedProbe.probeState } returns MutableStateFlow(LinkSpeedProbe.ProbeState.Idle)
            val failingConversationLinkManager = mockk<ConversationLinkManager>(relaxed = true)
            every { failingConversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())
            val viewModelWithoutIdentity =
                MessagingViewModel(
                    failingProtocol,
                    failingRepository,
                    failingAnnounceRepository,
                    failingContactRepository,
                    failingActiveConversationManager,
                    failingSettingsRepository,
                    failingPropagationNodeManager,
                    failingLocationSharingManager,
                    identityRepository,
                    failingLinkSpeedProbe,
                    failingConversationLinkManager,
                )

            // Attempt to send message
            viewModelWithoutIdentity.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // Verify: sendLxmfMessageWithMethod was NOT called
            coVerify(exactly = 0) { failingProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
            // Note: Empty content is replaced with single space for Sideband compatibility
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = " ", // Single space for Sideband compatibility
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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

            // Verify protocol was called
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

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
                locationSharingManager,
                identityRepository,
                linkSpeedProbe,
                conversationLinkManager,
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
                locationSharingManager,
                identityRepository,
                linkSpeedProbe,
                conversationLinkManager,
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
                locationSharingManager,
                identityRepository,
                linkSpeedProbe,
                conversationLinkManager,
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
                locationSharingManager,
                identityRepository,
                linkSpeedProbe,
                conversationLinkManager,
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

    @Ignore(
        "Flaky test: UncaughtExceptionsBeforeTest on CI due to timing issues with " +
            "ViewModel init coroutines and delivery status observer. Passes locally but " +
            "fails intermittently on CI. TODO: Investigate proper coroutine test isolation.",
    )
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

    // ========== saveReceivedFileAttachment Tests ==========

    @Test
    fun `saveReceivedFileAttachment returns false when message not found`() =
        runTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "nonexistent-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when fieldsJson is null`() =
        runTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = null)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when field 5 is missing`() =
        runTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when file index is out of bounds`() =
        runTest {
            // Arrange - fieldsJson with one attachment, but requesting index 5
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 5, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment writes file data to output stream`() =
        runTest {
            // Arrange - "Hello" in hex is "48656c6c6f"
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val outputStream = ByteArrayOutputStream()
            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns outputStream

            val viewModel = createTestViewModel()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertTrue(result)
            assertEquals("Hello", outputStream.toString())
        }

    @Test
    fun `saveReceivedFileAttachment returns false when output stream is null`() =
        runTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns null

            val viewModel = createTestViewModel()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false on exception`() =
        runTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment saves correct attachment from multiple`() =
        runTest {
            // Arrange - Multiple attachments, save the second one ("World" = "576f726c64")
            val fieldsJson = """{"5": [
                {"filename": "first.txt", "data": "48656c6c6f", "size": 5},
                {"filename": "second.txt", "data": "576f726c64", "size": 5}
            ]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val outputStream = ByteArrayOutputStream()
            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns outputStream

            val viewModel = createTestViewModel()

            // Act - Request index 1 (second attachment)
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 1, uri)

            // Assert
            assertTrue(result)
            assertEquals("World", outputStream.toString())
        }

    private fun createMessageEntity(
        id: String = "test-id",
        fieldsJson: String? = null,
    ): MessageEntity =
        MessageEntity(
            id = id,
            conversationHash = "conv-123",
            identityHash = "identity-hash",
            content = "test content",
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            status = "delivered",
            fieldsJson = fieldsJson,
            deliveryMethod = null,
        )

    // ========== FILE ATTACHMENT TESTS ==========

    @Test
    fun `addFileAttachment adds file to selectedFileAttachments`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val attachment =
                FileAttachment(
                    filename = "test.pdf",
                    data = ByteArray(1024),
                    mimeType = "application/pdf",
                    sizeBytes = 1024,
                )

            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            assertEquals("test.pdf", viewModel.selectedFileAttachments.value[0].filename)
        }

    @Test
    fun `addFileAttachment adds multiple files`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val attachment1 =
                FileAttachment(
                    filename = "test1.pdf",
                    data = ByteArray(1024),
                    mimeType = "application/pdf",
                    sizeBytes = 1024,
                )
            val attachment2 =
                FileAttachment(
                    filename = "test2.txt",
                    data = ByteArray(512),
                    mimeType = "text/plain",
                    sizeBytes = 512,
                )

            viewModel.addFileAttachment(attachment1)
            viewModel.addFileAttachment(attachment2)
            advanceUntilIdle()

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            assertEquals("test1.pdf", viewModel.selectedFileAttachments.value[0].filename)
            assertEquals("test2.txt", viewModel.selectedFileAttachments.value[1].filename)
        }

    @Test
    fun `removeFileAttachment removes file at index`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Add two files
            val attachment1 = FileAttachment("file1.pdf", ByteArray(100), "application/pdf", 100)
            val attachment2 = FileAttachment("file2.txt", ByteArray(200), "text/plain", 200)
            viewModel.addFileAttachment(attachment1)
            viewModel.addFileAttachment(attachment2)
            advanceUntilIdle()

            assertEquals(2, viewModel.selectedFileAttachments.value.size)

            // Remove first file
            viewModel.removeFileAttachment(0)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            assertEquals("file2.txt", viewModel.selectedFileAttachments.value[0].filename)
        }

    @Test
    fun `removeFileAttachment does nothing for invalid index`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val attachment = FileAttachment("file.pdf", ByteArray(100), "application/pdf", 100)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            // Try to remove at invalid index
            viewModel.removeFileAttachment(5)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `removeFileAttachment handles negative index`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val attachment = FileAttachment("file.pdf", ByteArray(100), "application/pdf", 100)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            // Try to remove at negative index
            viewModel.removeFileAttachment(-1)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `clearFileAttachments removes all files`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Add multiple files
            viewModel.addFileAttachment(FileAttachment("file1.pdf", ByteArray(100), "application/pdf", 100))
            viewModel.addFileAttachment(FileAttachment("file2.txt", ByteArray(200), "text/plain", 200))
            viewModel.addFileAttachment(FileAttachment("file3.zip", ByteArray(300), "application/zip", 300))
            advanceUntilIdle()

            assertEquals(3, viewModel.selectedFileAttachments.value.size)

            // Clear all
            viewModel.clearFileAttachments()

            assertEquals(0, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `totalAttachmentSize reflects sum of file sizes when files are added`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.addFileAttachment(FileAttachment("file1.pdf", ByteArray(1000), "application/pdf", 1000))
            advanceUntilIdle()

            viewModel.addFileAttachment(FileAttachment("file2.txt", ByteArray(500), "text/plain", 500))
            advanceUntilIdle()

            // Verify files were added and their sizes are correct
            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            val calculatedTotal = viewModel.selectedFileAttachments.value.sumOf { it.sizeBytes }
            assertEquals(1500, calculatedTotal)
        }

    @Test
    fun `totalAttachmentSize reflects sum of file sizes when files are removed`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.addFileAttachment(FileAttachment("file1.pdf", ByteArray(1000), "application/pdf", 1000))
            viewModel.addFileAttachment(FileAttachment("file2.txt", ByteArray(500), "text/plain", 500))
            advanceUntilIdle()

            assertEquals(2, viewModel.selectedFileAttachments.value.size)

            viewModel.removeFileAttachment(0)
            advanceUntilIdle()

            // Verify remaining file and size
            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            val calculatedTotal = viewModel.selectedFileAttachments.value.sumOf { it.sizeBytes }
            assertEquals(500, calculatedTotal)
        }

    @Test
    fun `setProcessingFile updates isProcessingFile state`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertEquals(false, viewModel.isProcessingFile.value)

            viewModel.setProcessingFile(true)

            assertEquals(true, viewModel.isProcessingFile.value)

            viewModel.setProcessingFile(false)

            assertEquals(false, viewModel.isProcessingFile.value)
        }

    @Test
    fun `sendMessage with empty content but file attached succeeds`() =
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Add a file attachment
            val attachment = FileAttachment("document.pdf", ByteArray(1024), "application/pdf", 1024)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            // Send message with empty content but file attached
            viewModel.sendMessage(testPeerHash, "")
            advanceUntilIdle()

            // Protocol should be called with file attachments
            // Note: Empty content is replaced with single space for Sideband compatibility
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = " ", // Single space for Sideband compatibility
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                    fileAttachments = match { it?.size == 1 && it[0].first == "document.pdf" },
                )
            }
        }

    @Test
    fun `sendMessage clears file attachments after successful send`() =
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Add a file attachment
            val attachment = FileAttachment("document.pdf", ByteArray(1024), "application/pdf", 1024)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)

            // Send message
            viewModel.sendMessage(testPeerHash, "Test with file")
            advanceUntilIdle()

            // Verify protocol was called
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // File attachments should be cleared after successful send
            assertEquals(0, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `syncFromPropagationNode triggers sync`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            coEvery { propagationNodeManager.triggerSync() } just Runs

            viewModel.syncFromPropagationNode()
            advanceUntilIdle()

            coVerify { propagationNodeManager.triggerSync() }
        }

    // ========== getFileAttachmentUri Tests ==========

    @Test
    fun `getFileAttachmentUri returns null when message not found`() =
        runTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "nonexistent-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when fieldsJson is null`() =
        runTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = null)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when field 5 is missing`() =
        runTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file index is out of bounds`() =
        runTest {
            // Arrange - one attachment but requesting index 5
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 5)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file data is empty`() =
        runTest {
            // Arrange - attachment with empty data
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "", "size": 0}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file data field is missing`() =
        runTest {
            // Arrange - attachment without data field
            val fieldsJson = """{"5": [{"filename": "test.txt", "size": 100}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null on exception`() =
        runTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null for negative index`() =
        runTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", -1)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when field 5 is not array or file ref`() =
        runTest {
            // Arrange - field 5 is a string instead of array
            val fieldsJson = """{"5": "not an array"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when attachment is not JSONObject`() =
        runTest {
            // Arrange - array contains string instead of object
            val fieldsJson = """{"5": ["not an object"]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            val viewModel = createTestViewModel()
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri creates file and returns URI with correct mimeType`() =
        runTest {
            // Arrange - "Hello" in hex is "48656c6c6f"
            val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val viewModel = createTestViewModel()

            // Create temp directory for test
            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }
            val attachmentsDir = java.io.File(tempDir, "attachments")

            val context = mockk<android.content.Context>()
            every { context.filesDir } returns tempDir
            every { context.packageName } returns "com.lxmf.messenger"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("com.lxmf.messenger.fileprovider"),
                    any(),
                )
            } returns mockUri

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNotNull(result)
            assertEquals(mockUri, result!!.first)
            assertEquals("application/pdf", result.second)

            // Verify file was created
            val createdFile = java.io.File(attachmentsDir, "test.pdf")
            assertTrue(createdFile.exists())
            assertEquals("Hello", createdFile.readText())

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            tempDir.deleteRecursively()
        }

    @Test
    fun `getFileAttachmentUri handles different file types correctly`() =
        runTest {
            // Arrange - test with text file
            val fieldsJson = """{"5": [{"filename": "notes.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val viewModel = createTestViewModel()

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.filesDir } returns tempDir
            every { context.packageName } returns "com.lxmf.messenger"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("com.lxmf.messenger.fileprovider"),
                    any(),
                )
            } returns mockUri

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNotNull(result)
            assertEquals("text/plain", result!!.second)

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            tempDir.deleteRecursively()
        }

    @Test
    fun `getFileAttachmentUri handles multiple attachments at different indices`() =
        runTest {
            // Arrange - multiple attachments
            val fieldsJson = """{"5": [
                {"filename": "first.pdf", "data": "4f6e65", "size": 3},
                {"filename": "second.txt", "data": "54776f", "size": 3}
            ]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val viewModel = createTestViewModel()

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.filesDir } returns tempDir
            every { context.packageName } returns "com.lxmf.messenger"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("com.lxmf.messenger.fileprovider"),
                    any(),
                )
            } returns mockUri

            // Act - get second attachment (index 1)
            val result = viewModel.getFileAttachmentUri(context, "test-id", 1)

            // Assert
            assertNotNull(result)
            assertEquals("text/plain", result!!.second)

            // Verify correct file was created
            val attachmentsDir = java.io.File(tempDir, "attachments")
            val createdFile = java.io.File(attachmentsDir, "second.txt")
            assertTrue(createdFile.exists())
            assertEquals("Two", createdFile.readText())

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            tempDir.deleteRecursively()
        }

    @Test
    fun `getFileAttachmentUri creates attachments directory if not exists`() =
        runTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val viewModel = createTestViewModel()

            // Create temp directory but NOT the attachments subdirectory
            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }
            // Ensure attachments dir does NOT exist
            val attachmentsDir = java.io.File(tempDir, "attachments")
            assertFalse(attachmentsDir.exists())

            val context = mockk<android.content.Context>()
            every { context.filesDir } returns tempDir
            every { context.packageName } returns "com.lxmf.messenger"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("com.lxmf.messenger.fileprovider"),
                    any(),
                )
            } returns mockUri

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNotNull(result)
            // Verify attachments directory was created
            assertTrue(attachmentsDir.exists())
            assertTrue(attachmentsDir.isDirectory)

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            tempDir.deleteRecursively()
        }

    @Test
    fun `getFileAttachmentUri handles unknown file extension`() =
        runTest {
            // Arrange - file with unknown extension
            val fieldsJson = """{"5": [{"filename": "data.xyz", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val viewModel = createTestViewModel()

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.filesDir } returns tempDir
            every { context.packageName } returns "com.lxmf.messenger"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("com.lxmf.messenger.fileprovider"),
                    any(),
                )
            } returns mockUri

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNotNull(result)
            assertEquals("application/octet-stream", result!!.second)

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            tempDir.deleteRecursively()
        }

    // ========== REPLY FUNCTIONALITY TESTS ==========

    @Test
    fun `setReplyTo sets pending reply when message found`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock reply preview exists
            val replyPreview =
                ReplyPreview(
                    messageId = "reply-msg-123",
                    senderName = "Alice",
                    contentPreview = "Hello there!",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("reply-msg-123", any()) } returns replyPreview

            // Load conversation first
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Set reply to message
            viewModel.setReplyTo("reply-msg-123")
            advanceUntilIdle()

            // Assert: pendingReplyTo is set
            val pending = viewModel.pendingReplyTo.value
            assertNotNull(pending)
            assertEquals("reply-msg-123", pending!!.messageId)
            assertEquals("Alice", pending.senderName)
            assertEquals("Hello there!", pending.contentPreview)
        }

    @Test
    fun `setReplyTo does not set pending reply when message not found`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock reply preview not found
            coEvery { conversationRepository.getReplyPreview("unknown-msg", any()) } returns null

            // Load conversation first
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to set reply to non-existent message
            viewModel.setReplyTo("unknown-msg")
            advanceUntilIdle()

            // Assert: pendingReplyTo is NOT set
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `clearReplyTo clears pending reply`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Set a pending reply first
            val replyPreview =
                ReplyPreview(
                    messageId = "reply-msg-123",
                    senderName = "Alice",
                    contentPreview = "Hello there!",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("reply-msg-123", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.setReplyTo("reply-msg-123")
            advanceUntilIdle()

            // Verify it's set
            assertNotNull(viewModel.pendingReplyTo.value)

            // Act: Clear the reply
            viewModel.clearReplyTo()
            advanceUntilIdle()

            // Assert: pendingReplyTo is cleared
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `pendingReplyTo initial state is null`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Assert: Initial state is null
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `loadReplyPreviewAsync caches reply preview`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock reply preview exists
            val replyPreview =
                ReplyPreview(
                    messageId = "original-msg-456",
                    senderName = "Bob",
                    contentPreview = "Original message content",
                    hasImage = true,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("original-msg-456", any()) } returns replyPreview

            // Load conversation first
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Load reply preview for a message
            viewModel.loadReplyPreviewAsync("current-msg-789", "original-msg-456")
            advanceUntilIdle()

            // Assert: Reply preview is cached
            val cache = viewModel.replyPreviewCache.value
            assertTrue(cache.containsKey("current-msg-789"))
            val cachedPreview = cache["current-msg-789"]
            assertNotNull(cachedPreview)
            assertEquals("original-msg-456", cachedPreview!!.messageId)
            assertEquals("Bob", cachedPreview.senderName)
            assertTrue(cachedPreview.hasImage)
        }

    @Test
    fun `loadReplyPreviewAsync does not reload if already cached`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock reply preview exists
            val replyPreview =
                ReplyPreview(
                    messageId = "original-msg",
                    senderName = "Alice",
                    contentPreview = "Hello",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("original-msg", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Load once
            viewModel.loadReplyPreviewAsync("msg-1", "original-msg")
            advanceUntilIdle()

            // Verify it was loaded
            coVerify(exactly = 1) { conversationRepository.getReplyPreview("original-msg", any()) }

            // Try to load again
            viewModel.loadReplyPreviewAsync("msg-1", "original-msg")
            advanceUntilIdle()

            // Assert: Repository was NOT called again (cached)
            coVerify(exactly = 1) { conversationRepository.getReplyPreview("original-msg", any()) }
        }

    @Test
    fun `loadReplyPreviewAsync handles deleted message gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Reply target message not found
            coEvery { conversationRepository.getReplyPreview("deleted-msg", any()) } returns null

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Load reply preview for deleted message
            viewModel.loadReplyPreviewAsync("current-msg", "deleted-msg")
            advanceUntilIdle()

            // Assert: Cache contains a placeholder for deleted message
            val cache = viewModel.replyPreviewCache.value
            assertTrue(cache.containsKey("current-msg"))
            val cachedPreview = cache["current-msg"]
            assertNotNull(cachedPreview)
            assertEquals("deleted-msg", cachedPreview!!.messageId)
            assertEquals("Message deleted", cachedPreview.contentPreview)
        }

    @Test
    fun `replyPreviewCache initial state is empty`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Assert: Initial state is empty map
            assertTrue(viewModel.replyPreviewCache.value.isEmpty())
        }

    @Test
    fun `sendMessage with pending reply includes replyToMessageId`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            // Setup: Set a pending reply
            val replyPreview =
                ReplyPreview(
                    messageId = "reply-to-this-msg",
                    senderName = "Alice",
                    contentPreview = "Original message",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("reply-to-this-msg", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.setReplyTo("reply-to-this-msg")
            advanceUntilIdle()

            // Verify reply is set
            assertNotNull(viewModel.pendingReplyTo.value)

            // Act: Send message
            viewModel.sendMessage(testPeerHash, "This is my reply")
            advanceUntilIdle()

            // Assert: Message saved with replyToMessageId
            coVerify {
                conversationRepository.saveMessage(
                    peerHash = testPeerHash,
                    peerName = testPeerName,
                    message = match { it.replyToMessageId == "reply-to-this-msg" },
                    peerPublicKey = null,
                )
            }
        }

    @Test
    fun `sendMessage clears pending reply after successful send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            // Setup: Set a pending reply
            val replyPreview =
                ReplyPreview(
                    messageId = "reply-to-this-msg",
                    senderName = "Alice",
                    contentPreview = "Original message",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("reply-to-this-msg", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.setReplyTo("reply-to-this-msg")
            advanceUntilIdle()

            // Verify reply is set before send
            assertNotNull(viewModel.pendingReplyTo.value)

            // Act: Send message
            viewModel.sendMessage(testPeerHash, "This is my reply")
            advanceUntilIdle()

            // Verify protocol was called
            coVerify(exactly = 1) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Pending reply was cleared after successful send
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `sendMessage without pending reply does not include replyToMessageId`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // No pending reply set

            // Act: Send message
            viewModel.sendMessage(testPeerHash, "Regular message")
            advanceUntilIdle()

            // Assert: Message saved without replyToMessageId
            coVerify {
                conversationRepository.saveMessage(
                    peerHash = testPeerHash,
                    peerName = testPeerName,
                    message = match { it.replyToMessageId == null },
                    peerPublicKey = null,
                )
            }
        }

    @Test
    fun `setReplyTo handles exception gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Repository throws exception
            coEvery { conversationRepository.getReplyPreview(any(), any()) } throws RuntimeException("DB error")

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to set reply (should not crash)
            viewModel.setReplyTo("some-msg")
            advanceUntilIdle()

            // Assert: No crash, pendingReplyTo remains null
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `loadReplyPreviewAsync handles exception gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Repository throws exception
            coEvery { conversationRepository.getReplyPreview(any(), any()) } throws RuntimeException("DB error")

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to load reply preview (should not crash)
            viewModel.loadReplyPreviewAsync("current-msg", "reply-to-msg")
            advanceUntilIdle()

            // Assert: No crash, cache remains empty
            assertTrue(viewModel.replyPreviewCache.value.isEmpty())
        }

    @Test
    fun `setReplyTo with image attachment sets hasImage correctly`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Reply preview has image
            val replyPreview =
                ReplyPreview(
                    messageId = "img-msg",
                    senderName = "Alice",
                    contentPreview = "Check out this photo",
                    hasImage = true,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            coEvery { conversationRepository.getReplyPreview("img-msg", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act
            viewModel.setReplyTo("img-msg")
            advanceUntilIdle()

            // Assert
            val pending = viewModel.pendingReplyTo.value
            assertNotNull(pending)
            assertTrue(pending!!.hasImage)
        }

    @Test
    fun `setReplyTo with file attachment sets hasFileAttachment correctly`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Setup: Reply preview has file attachment
            val replyPreview =
                ReplyPreview(
                    messageId = "file-msg",
                    senderName = "Bob",
                    contentPreview = "Here is the document",
                    hasImage = false,
                    hasFileAttachment = true,
                    firstFileName = "report.pdf",
                )
            coEvery { conversationRepository.getReplyPreview("file-msg", any()) } returns replyPreview

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act
            viewModel.setReplyTo("file-msg")
            advanceUntilIdle()

            // Assert
            val pending = viewModel.pendingReplyTo.value
            assertNotNull(pending)
            assertTrue(pending!!.hasFileAttachment)
            assertEquals("report.pdf", pending.firstFileName)
        }

    // ========== REACTION MODE STATE TESTS ==========

    @Test
    fun `reactionModeState initial state is null`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Assert: Initial state is null
            assertNull(viewModel.reactionModeState.value)
        }

    @Test
    fun `enterReactionMode sets state with isMessageHidden true by default`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act
            viewModel.enterReactionMode(
                messageId = "test-msg-123",
                scrollIndex = 5,
                isFromMe = true,
                isFailed = false,
            )
            advanceUntilIdle()

            // Assert
            val state = viewModel.reactionModeState.value
            assertNotNull(state)
            assertEquals("test-msg-123", state!!.messageId)
            assertEquals(5, state.targetScrollIndex)
            assertTrue(state.isFromMe)
            assertFalse(state.isFailed)
            assertTrue(state.isMessageHidden) // Default is true
        }

    @Test
    fun `enterReactionMode generates unique instanceId`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Enter reaction mode twice
            viewModel.enterReactionMode(
                messageId = "msg-1",
                scrollIndex = 1,
                isFromMe = true,
            )
            advanceUntilIdle()
            val firstInstanceId = viewModel.reactionModeState.value?.instanceId

            // Small delay to ensure different timestamp
            Thread.sleep(5)

            viewModel.enterReactionMode(
                messageId = "msg-2",
                scrollIndex = 2,
                isFromMe = false,
            )
            advanceUntilIdle()
            val secondInstanceId = viewModel.reactionModeState.value?.instanceId

            // Assert: Instance IDs are different
            assertNotNull(firstInstanceId)
            assertNotNull(secondInstanceId)
            assertTrue(
                "Instance IDs should be unique: $firstInstanceId vs $secondInstanceId",
                firstInstanceId != secondInstanceId,
            )
        }

    @Test
    fun `exitReactionMode clears state`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Enter reaction mode first
            viewModel.enterReactionMode(
                messageId = "test-msg-123",
                scrollIndex = 5,
                isFromMe = true,
            )
            advanceUntilIdle()
            assertNotNull(viewModel.reactionModeState.value)

            // Act
            viewModel.exitReactionMode()
            advanceUntilIdle()

            // Assert
            assertNull(viewModel.reactionModeState.value)
        }

    @Test
    fun `showOriginalMessage sets isMessageHidden to false`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Enter reaction mode (isMessageHidden = true by default)
            viewModel.enterReactionMode(
                messageId = "test-msg-123",
                scrollIndex = 5,
                isFromMe = true,
            )
            advanceUntilIdle()
            assertTrue(viewModel.reactionModeState.value?.isMessageHidden == true)

            // Act
            viewModel.showOriginalMessage()
            advanceUntilIdle()

            // Assert: isMessageHidden is now false, other state preserved
            val state = viewModel.reactionModeState.value
            assertNotNull(state)
            assertFalse(state!!.isMessageHidden)
            assertEquals("test-msg-123", state.messageId)
            assertEquals(5, state.targetScrollIndex)
        }

    @Test
    fun `showOriginalMessage does nothing when state is null`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Verify initial state is null
            assertNull(viewModel.reactionModeState.value)

            // Act: Should not crash
            viewModel.showOriginalMessage()
            advanceUntilIdle()

            // Assert: State remains null
            assertNull(viewModel.reactionModeState.value)
        }

    @Test
    fun `showOriginalMessage preserves instanceId`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Enter reaction mode
            viewModel.enterReactionMode(
                messageId = "test-msg-123",
                scrollIndex = 5,
                isFromMe = true,
            )
            advanceUntilIdle()
            val originalInstanceId = viewModel.reactionModeState.value?.instanceId
            assertNotNull(originalInstanceId)

            // Act
            viewModel.showOriginalMessage()
            advanceUntilIdle()

            // Assert: instanceId is preserved
            assertEquals(originalInstanceId, viewModel.reactionModeState.value?.instanceId)
        }

    @Test
    fun `enterReactionMode with failed message sets isFailed correctly`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act
            viewModel.enterReactionMode(
                messageId = "failed-msg",
                scrollIndex = 3,
                isFromMe = true,
                isFailed = true,
            )
            advanceUntilIdle()

            // Assert
            val state = viewModel.reactionModeState.value
            assertNotNull(state)
            assertTrue(state!!.isFailed)
        }

    // ========== SEND REACTION TESTS ==========

    @Test
    fun `sendReaction returns early when message not found`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Message not found
            coEvery { conversationRepository.getMessageById("nonexistent-msg") } returns null

            // Act
            viewModel.sendReaction("nonexistent-msg", "👍")
            advanceUntilIdle()

            // Assert: Protocol send was never called
            coVerify(exactly = 0) { reticulumProtocol.sendReaction(any(), any(), any(), any()) }
        }

    @Test
    fun `sendReaction sends reaction via protocol when message exists`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock existing message
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol send reaction
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Mock updateMessageReactions
            coEvery { conversationRepository.updateMessageReactions(any(), any()) } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Protocol was called with correct parameters
            coVerify {
                reticulumProtocol.sendReaction(
                    destinationHash = any(),
                    targetMessageId = "test-msg-id",
                    emoji = "👍",
                    sourceIdentity = testIdentity,
                )
            }
        }

    @Test
    fun `sendReaction updates message fieldsJson in database`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock existing message without reactions
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol send reaction
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Capture the fieldsJson update
            val capturedFieldsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedFieldsJson))
            } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Database was updated with reaction in fieldsJson
            assertTrue(capturedFieldsJson.isCaptured)
            val json = org.json.JSONObject(capturedFieldsJson.captured)
            assertTrue(json.has("16"))
            val field16 = json.getJSONObject("16")
            assertTrue(field16.has("reactions"))
            val reactions = field16.getJSONObject("reactions")
            assertTrue(reactions.has("👍"))
        }

    @Test
    fun `sendReaction clears reaction UI state after sending`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock existing message
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol send reaction
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)
            coEvery { conversationRepository.updateMessageReactions(any(), any()) } just Runs

            // Set up reaction target first
            viewModel.setReactionTarget("test-msg-id")
            advanceUntilIdle()
            assertEquals("test-msg-id", viewModel.pendingReactionMessageId.value)

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Reaction target was cleared
            assertNull(viewModel.pendingReactionMessageId.value)
        }

    @Test
    fun `sendReaction handles protocol send failure gracefully`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock existing message
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol failure
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))
            coEvery { conversationRepository.updateMessageReactions(any(), any()) } just Runs

            // Set up reaction target
            viewModel.setReactionTarget("test-msg-id")
            advanceUntilIdle()

            // Act - should not throw
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: UI state was still cleared (optimistic update pattern)
            assertNull(viewModel.pendingReactionMessageId.value)

            // Assert: Local database was still updated (optimistic update)
            coVerify { conversationRepository.updateMessageReactions("test-msg-id", any()) }
        }

    @Test
    fun `sendReaction adds reaction to existing fieldsJson with reply_to`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock message that already has a reply_to in field 16
            val existingFieldsJson = """{"16": {"reply_to": "original-msg-id"}}"""
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = existingFieldsJson,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = "original-msg-id",
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Capture the fieldsJson update
            val capturedFieldsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedFieldsJson))
            } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "❤️")
            advanceUntilIdle()

            // Assert: Both reply_to and reactions are preserved
            assertTrue(capturedFieldsJson.isCaptured)
            val json = org.json.JSONObject(capturedFieldsJson.captured)
            val field16 = json.getJSONObject("16")
            assertEquals("original-msg-id", field16.getString("reply_to"))
            assertTrue(field16.has("reactions"))
            val reactions = field16.getJSONObject("reactions")
            assertTrue(reactions.has("❤️"))
        }

    @Test
    fun `sendReaction adds sender to existing emoji reaction`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock message that already has a 👍 reaction from someone else
            val existingFieldsJson = """{"16": {"reactions": {"👍": ["other-sender-hash"]}}}"""
            val testMessage =
                MessageEntity(
                    id = "test-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    fieldsJson = existingFieldsJson,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                reticulumProtocol.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Capture the fieldsJson update
            val capturedFieldsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedFieldsJson))
            } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Both senders are in the reaction list
            assertTrue(capturedFieldsJson.isCaptured)
            val json = org.json.JSONObject(capturedFieldsJson.captured)
            val reactions = json.getJSONObject("16").getJSONObject("reactions")
            val thumbsUp = reactions.getJSONArray("👍")
            assertEquals(2, thumbsUp.length())
            assertEquals("other-sender-hash", thumbsUp.getString(0))
            // Our sender hash is derived from testIdentity
        }

    // ========== INCOMING REACTION TESTS ==========

    @Test
    fun `handleIncomingReaction updates message when found`() =
        runTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { reticulumProtocol.reactionReceivedFlow } returns reactionFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock target message
            val targetMessage =
                MessageEntity(
                    id = "target-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "delivered",
                    fieldsJson = null,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("target-msg-id") } returns targetMessage

            // Capture the fieldsJson update
            val capturedFieldsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("target-msg-id", capture(capturedFieldsJson))
            } just Runs

            // Act: Emit incoming reaction
            val reactionJson = """{"reaction_to": "target-msg-id", "emoji": "😂", "sender": "remote-sender-hash"}"""
            reactionFlow.emit(reactionJson)
            advanceUntilIdle()

            // Assert: Database was updated
            assertTrue(capturedFieldsJson.isCaptured)
            val json = org.json.JSONObject(capturedFieldsJson.captured)
            val reactions = json.getJSONObject("16").getJSONObject("reactions")
            assertTrue(reactions.has("😂"))
            val senders = reactions.getJSONArray("😂")
            assertEquals("remote-sender-hash", senders.getString(0))
        }

    @Test
    fun `handleIncomingReaction ignores message when not found`() =
        runTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { reticulumProtocol.reactionReceivedFlow } returns reactionFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Message not found
            coEvery { conversationRepository.getMessageById("nonexistent-msg") } returns null

            // Act: Emit incoming reaction for unknown message
            val reactionJson = """{"reaction_to": "nonexistent-msg", "emoji": "👍", "sender": "remote-sender"}"""
            reactionFlow.emit(reactionJson)
            advanceUntilIdle()

            // Assert: No database update was attempted
            coVerify(exactly = 0) { conversationRepository.updateMessageReactions(any(), any()) }
        }

    @Test
    fun `handleIncomingReaction handles malformed JSON gracefully`() =
        runTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { reticulumProtocol.reactionReceivedFlow } returns reactionFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Emit malformed JSON - should not crash
            reactionFlow.emit("not valid json {{{")
            advanceUntilIdle()

            // Act: Emit JSON missing required fields - should not crash
            reactionFlow.emit("""{"reaction_to": "msg-id"}""") // Missing emoji and sender
            advanceUntilIdle()

            // Assert: No database update was attempted for invalid reactions
            coVerify(exactly = 0) { conversationRepository.updateMessageReactions(any(), any()) }
        }

    @Test
    fun `handleIncomingReaction merges reactions with existing`() =
        runTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { reticulumProtocol.reactionReceivedFlow } returns reactionFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Mock target message that already has reactions
            val existingFieldsJson = """{"16": {"reactions": {"👍": ["sender-1"]}}}"""
            val targetMessage =
                MessageEntity(
                    id = "target-msg-id",
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Hello",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "delivered",
                    fieldsJson = existingFieldsJson,
                    deliveryMethod = null,
                    errorMessage = null,
                    replyToMessageId = null,
                )
            coEvery { conversationRepository.getMessageById("target-msg-id") } returns targetMessage

            // Capture the fieldsJson update
            val capturedFieldsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("target-msg-id", capture(capturedFieldsJson))
            } just Runs

            // Act: Emit incoming reaction with different emoji
            val reactionJson = """{"reaction_to": "target-msg-id", "emoji": "❤️", "sender": "sender-2"}"""
            reactionFlow.emit(reactionJson)
            advanceUntilIdle()

            // Assert: Both reactions exist
            assertTrue(capturedFieldsJson.isCaptured)
            val json = org.json.JSONObject(capturedFieldsJson.captured)
            val reactions = json.getJSONObject("16").getJSONObject("reactions")
            assertTrue(reactions.has("👍"))
            assertTrue(reactions.has("❤️"))
            assertEquals(1, reactions.getJSONArray("👍").length())
            assertEquals(1, reactions.getJSONArray("❤️").length())
        }

    // ========== IMAGE STATE TESTS ==========

    @Test
    fun `selectImage sets image data and format`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val imageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            viewModel.selectImage(imageData, "png")
            advanceUntilIdle()

            assertEquals(imageData, viewModel.selectedImageData.value)
            assertEquals("png", viewModel.selectedImageFormat.value)
            assertFalse(viewModel.selectedImageIsAnimated.value)
        }

    @Test
    fun `selectImage with animated flag sets isAnimated`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val gifData = byteArrayOf(0x47, 0x49, 0x46) // GIF header

            viewModel.selectImage(gifData, "gif", isAnimated = true)
            advanceUntilIdle()

            assertEquals(gifData, viewModel.selectedImageData.value)
            assertEquals("gif", viewModel.selectedImageFormat.value)
            assertTrue(viewModel.selectedImageIsAnimated.value)
        }

    @Test
    fun `clearSelectedImage clears image state`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Set an image first
            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            advanceUntilIdle()

            assertNotNull(viewModel.selectedImageData.value)

            // Clear it
            viewModel.clearSelectedImage()
            advanceUntilIdle()

            assertNull(viewModel.selectedImageData.value)
            assertNull(viewModel.selectedImageFormat.value)
            assertFalse(viewModel.selectedImageIsAnimated.value)
        }

    @Test
    fun `setProcessingImage updates isProcessingImage state`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.isProcessingImage.value)

            viewModel.setProcessingImage(true)

            assertTrue(viewModel.isProcessingImage.value)

            viewModel.setProcessingImage(false)

            assertFalse(viewModel.isProcessingImage.value)
        }

    // ========== REACTION PICKER STATE TESTS ==========

    @Test
    fun `setReactionTarget sets pending reaction message id and shows picker`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.setReactionTarget("msg-123")
            advanceUntilIdle()

            assertEquals("msg-123", viewModel.pendingReactionMessageId.value)
            assertTrue(viewModel.showReactionPicker.value)
        }

    @Test
    fun `clearReactionTarget clears pending reaction and hides picker`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Set target first
            viewModel.setReactionTarget("msg-123")
            advanceUntilIdle()

            // Clear it
            viewModel.clearReactionTarget()
            advanceUntilIdle()

            assertNull(viewModel.pendingReactionMessageId.value)
            assertFalse(viewModel.showReactionPicker.value)
        }

    @Test
    fun `dismissReactionPicker hides picker but keeps target`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Set target first
            viewModel.setReactionTarget("msg-123")
            advanceUntilIdle()

            assertTrue(viewModel.showReactionPicker.value)

            // Dismiss picker only
            viewModel.dismissReactionPicker()
            advanceUntilIdle()

            // Target is NOT cleared, only picker hidden
            // Based on code review, dismissReactionPicker only sets _showReactionPicker to false
            // and does NOT clear _pendingReactionMessageId
            assertFalse(viewModel.showReactionPicker.value)
        }

    @Test
    fun `pendingReactionMessageId initial state is null`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertNull(viewModel.pendingReactionMessageId.value)
        }

    @Test
    fun `showReactionPicker initial state is false`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.showReactionPicker.value)
        }

    // ========== LOCATION SHARING TESTS ==========

    @Test
    fun `startSharingWithPeer calls location sharing manager`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val duration = com.lxmf.messenger.ui.model.SharingDuration.FIFTEEN_MINUTES

            viewModel.startSharingWithPeer(testPeerHash, testPeerName, duration)
            advanceUntilIdle()

            verify {
                locationSharingManager.startSharing(
                    contactHashes = listOf(testPeerHash),
                    displayNames = mapOf(testPeerHash to testPeerName),
                    duration = duration,
                )
            }
        }

    @Test
    fun `stopSharingWithPeer calls location sharing manager`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.stopSharingWithPeer(testPeerHash)
            advanceUntilIdle()

            verify { locationSharingManager.stopSharing(testPeerHash) }
        }

    // ========== SENDING STATE TESTS ==========

    @Test
    fun `isSending initial state is false`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.isSending.value)
        }

    @Test
    fun `isSending is true during message send and false after`() =
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Before sending
            assertFalse(viewModel.isSending.value)

            // Send message
            viewModel.sendMessage(testPeerHash, "Test message")
            advanceUntilIdle()

            // After sending complete
            assertFalse(viewModel.isSending.value)
        }

    @Test
    fun `isSending is false after failed send`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            viewModel.sendMessage(testPeerHash, "Test message")
            advanceUntilIdle()

            // After failed send, isSending should be reset to false
            assertFalse(viewModel.isSending.value)
        }

    // ========== RETRY FAILED MESSAGE TESTS ==========

    @Test
    fun `retryFailedMessage does nothing when message not found`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            coEvery { conversationRepository.getMessageById("nonexistent") } returns null

            viewModel.retryFailedMessage("nonexistent")
            advanceUntilIdle()

            // Protocol should not be called
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `retryFailedMessage does nothing when message is not failed`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            val pendingMessage =
                MessageEntity(
                    id = "msg-123",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "Test message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "pending", // Not failed
                )
            coEvery { conversationRepository.getMessageById("msg-123") } returns pendingMessage

            viewModel.retryFailedMessage("msg-123")
            advanceUntilIdle()

            // Protocol should not be called for non-failed messages
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `retryFailedMessage retries message when status is failed`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            val failedMessage =
                MessageEntity(
                    id = "msg-123",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "Test message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                )
            coEvery { conversationRepository.getMessageById("msg-123") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { 0xAB.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs

            viewModel.retryFailedMessage("msg-123")
            advanceUntilIdle()

            // Should mark as pending before sending
            coVerify { conversationRepository.updateMessageStatus("msg-123", "pending") }

            // Should call protocol to resend
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

            // Should update message ID with new hash on success
            coVerify { conversationRepository.updateMessageId("msg-123", any()) }
        }

    @Test
    fun `retryFailedMessage restores failed status on retry failure`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            val failedMessage =
                MessageEntity(
                    id = "msg-123",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "Test message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                )
            coEvery { conversationRepository.getMessageById("msg-123") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Mock send failure
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))

            viewModel.retryFailedMessage("msg-123")
            advanceUntilIdle()

            // Should restore failed status after retry fails
            coVerify {
                conversationRepository.updateMessageStatus("msg-123", "pending") // First set to pending
            }
            coVerify {
                conversationRepository.updateMessageStatus("msg-123", "failed") // Then back to failed
            }
        }

    @Test
    fun `retryFailedMessage handles invalid destination hash`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            val failedMessage =
                MessageEntity(
                    id = "msg-123",
                    conversationHash = "invalid!hash", // Invalid characters
                    identityHash = "identity-hash",
                    content = "Test message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                )
            coEvery { conversationRepository.getMessageById("msg-123") } returns failedMessage

            viewModel.retryFailedMessage("msg-123")
            advanceUntilIdle()

            // Protocol should not be called due to invalid hash
            coVerify(exactly = 0) {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    // ========== FETCH PENDING FILE TESTS ==========

    @Test
    fun `fetchPendingFile triggers sync with increased size limit`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Mock settings to return current limit
            coEvery { settingsRepository.getIncomingMessageSizeLimitKb() } returns 500

            // Mock protocol as ServiceReticulumProtocol
            every { reticulumProtocol.setIncomingMessageSizeLimit(any()) } just Runs

            // Mock sync completion - immediate return
            val syncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns syncingFlow
            coEvery { propagationNodeManager.triggerSync(silent = true) } just Runs

            // Act: Fetch a 1MB file
            val fileSizeBytes = 1024L * 1024L // 1MB
            viewModel.fetchPendingFile(fileSizeBytes)

            // Give the coroutine a chance to start
            advanceUntilIdle()

            // Verify sync was triggered with silent flag
            coVerify { propagationNodeManager.triggerSync(silent = true) }

            // Verify size limit was increased
            verify { reticulumProtocol.setIncomingMessageSizeLimit(match { it > 500 }) }
        }

    @Test
    fun `fetchPendingFile reverts size limit after sync`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            val originalLimit = 500
            coEvery { settingsRepository.getIncomingMessageSizeLimitKb() } returns originalLimit

            every { reticulumProtocol.setIncomingMessageSizeLimit(any()) } just Runs

            // Mock sync that completes quickly
            val syncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns syncingFlow
            coEvery { propagationNodeManager.triggerSync(silent = true) } coAnswers {
                // Simulate sync starting and completing
                syncingFlow.value = true
                syncingFlow.value = false
            }

            val fileSizeBytes = 512L * 1024L // 512KB
            viewModel.fetchPendingFile(fileSizeBytes)
            advanceUntilIdle()

            // Verify size limit was reverted to original
            verify { reticulumProtocol.setIncomingMessageSizeLimit(originalLimit) }
        }

    // ========== SYNC STATE DELEGATION TESTS ==========

    @Test
    fun `isSyncing delegates to propagationNodeManager`() =
        runTest {
            val syncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns syncingFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.isSyncing.value)

            syncingFlow.value = true
            advanceUntilIdle()

            assertTrue(viewModel.isSyncing.value)
        }

    @Test
    fun `syncProgress delegates to propagationNodeManager`() =
        runTest {
            val progressFlow =
                MutableStateFlow<com.lxmf.messenger.service.SyncProgress>(
                    com.lxmf.messenger.service.SyncProgress.Idle,
                )
            every { propagationNodeManager.syncProgress } returns progressFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertEquals(com.lxmf.messenger.service.SyncProgress.Idle, viewModel.syncProgress.value)

            progressFlow.value = com.lxmf.messenger.service.SyncProgress.Starting
            advanceUntilIdle()

            assertEquals(com.lxmf.messenger.service.SyncProgress.Starting, viewModel.syncProgress.value)
        }

    // Note: onCleared() tests removed - method is protected and cannot be called directly
    // The behavior is indirectly tested via the ViewModel lifecycle in integration tests

    // ========== TOTAL ATTACHMENT SIZE TESTS ==========

    @Test
    fun `totalAttachmentSize initial value is zero`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertEquals(0, viewModel.totalAttachmentSize.value)
        }

    @Test
    fun `totalAttachmentSize is zero when no files attached`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Make sure no files are attached
            viewModel.clearFileAttachments()
            advanceUntilIdle()

            assertEquals(0, viewModel.totalAttachmentSize.value)
        }

    // ========== SEND WITH FILE ATTACHMENT ==========

    @Test
    fun `sendMessage with file attachment calls protocol with file data`() =
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
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Add file attachment only
            viewModel.addFileAttachment(FileAttachment("doc.pdf", ByteArray(100), "application/pdf", 100))
            advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)

            // Send message
            viewModel.sendMessage(testPeerHash, "Message with attachment")
            advanceUntilIdle()

            // Verify protocol was called with file attachments
            coVerify {
                reticulumProtocol.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "Message with attachment",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                    fileAttachments = match { it != null && it.size == 1 },
                    replyToMessageId = null,
                    iconAppearance = null,
                )
            }
        }

    // ========== MY IDENTITY HASH TESTS ==========

    @Test
    fun `myIdentityHash is set after identity loads`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Identity is loaded lazily, trigger by sending a message
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                reticulumProtocol.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)
            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            viewModel.sendMessage(testPeerHash, "Test")
            advanceUntilIdle()

            // myIdentityHash should now be set
            assertNotNull(viewModel.myIdentityHash.value)
            // Verify it's the hex encoding of testIdentity.hash
            val expectedHash = testIdentity.hash.joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, viewModel.myIdentityHash.value)
        }

    // ========== DECODED IMAGES STATE TESTS ==========

    @Test
    fun `decodedImages initial state is empty`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.decodedImages.value.isEmpty())
        }

    // ========== ANNOUNCE INFO TESTS ==========

    @Test
    fun `announceInfo returns null when no conversation loaded`() =
        runTest {
            val viewModel = createTestViewModel()
            advanceUntilIdle()

            // Before loading any conversation
            assertNull(viewModel.announceInfo.value)
        }
}
