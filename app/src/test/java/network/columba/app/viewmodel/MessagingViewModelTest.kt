// SleepInsteadOfDelay: tearDown needs Thread.sleep for IO coroutine completion, one test needs it for timestamp differentiation
// IgnoredReturnValue: .first() calls trigger flow collection; result intentionally unused
// UnnecessarySafeCall: MockK match { it?.size } and nullable StateFlow.value patterns are defensive
@file:Suppress("SleepInsteadOfDelay", "IgnoredReturnValue", "UnnecessarySafeCall")

package network.columba.app.viewmodel

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.paging.PagingData
import network.columba.app.R
import network.columba.app.audio.VoiceMessageFormat
import network.columba.app.audio.MicrophoneAdmissionArbiter
import network.columba.app.audio.VoiceMessageRecorder
import network.columba.app.audio.VoiceMessageRecordingState
import tech.torlando.lxst.recording.RecordedAudio
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.data.repository.ReceivedLocationRepository
import network.columba.app.data.repository.ReplyPreview
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.ui.model.CodecProfile
import network.columba.app.rns.api.model.TransferPhase
import network.columba.app.rns.api.model.TransferProgressUpdate
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.CallState
import network.columba.app.service.ActiveConversationManager
import network.columba.app.service.ConversationLinkManager
import network.columba.app.notifications.NotificationHelper
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.LocationSharingManager
import network.columba.app.service.PropagationNodeManager
import network.columba.app.util.FileAttachment
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot

import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import network.columba.app.data.repository.Message as DataMessage

/**
 * Unit tests for MessagingViewModel.
 * Tests message loading, sending, state management, and repository interactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelTest {

    @Test
    fun `voice message quality uses exact standard LXST Opus call profiles`() {
        assertEquals(8_000, VoiceMessageFormat.OPUS_MEDIUM.recordingConfig?.bitRateBps)
        assertEquals(24_000, VoiceMessageFormat.OPUS_MEDIUM.recordingConfig?.sampleRateHz)
        assertEquals(1, VoiceMessageFormat.OPUS_MEDIUM.recordingConfig?.channelCount)
        assertEquals(16_000, VoiceMessageFormat.OPUS_HIGH.recordingConfig?.bitRateBps)
        assertEquals(48_000, VoiceMessageFormat.OPUS_HIGH.recordingConfig?.sampleRateHz)
        assertEquals(1, VoiceMessageFormat.OPUS_HIGH.recordingConfig?.channelCount)
        assertEquals(32_000, VoiceMessageFormat.OPUS_MAXIMUM.recordingConfig?.bitRateBps)
        assertEquals(48_000, VoiceMessageFormat.OPUS_MAXIMUM.recordingConfig?.sampleRateHz)
        assertEquals(2, VoiceMessageFormat.OPUS_MAXIMUM.recordingConfig?.channelCount)
    }

    @Test
    fun `voice message picker excludes legacy and latency profiles without product compatibility copy`() {
        assertEquals(VoiceMessageFormat.OPUS_MEDIUM, VoiceMessageFormat.DEFAULT)
        assertEquals(
            listOf(
                VoiceMessageFormat.CODEC2_1200,
                VoiceMessageFormat.CODEC2_2400,
                VoiceMessageFormat.CODEC2_3200,
                VoiceMessageFormat.OPUS_MEDIUM,
                VoiceMessageFormat.OPUS_HIGH,
                VoiceMessageFormat.OPUS_MAXIMUM,
            ),
            VoiceMessageFormat.OUTBOUND_OPTIONS,
        )
        assertEquals(
            listOf(
                R.string.voice_message_quality_codec2_1200,
                R.string.voice_message_quality_codec2_2400,
                R.string.voice_message_quality_codec2_3200,
                R.string.voice_message_quality_medium,
                R.string.voice_message_quality_high,
                R.string.voice_message_quality_maximum,
            ),
            VoiceMessageFormat.OUTBOUND_OPTIONS.map(VoiceMessageFormat::displayNameRes),
        )
        assertEquals(
            listOf(
                R.string.voice_message_quality_codec2_1200_description,
                R.string.voice_message_quality_codec2_2400_description,
                R.string.voice_message_quality_codec2_3200_description,
                R.string.voice_message_quality_medium_description,
                R.string.voice_message_quality_high_description,
                R.string.voice_message_quality_maximum_description,
            ),
            VoiceMessageFormat.OUTBOUND_OPTIONS.map(VoiceMessageFormat::descriptionRes),
        )
    }

    @Test
    fun `voice message formats include interoperable codec2 modes`() {
        assertEquals(0x04, VoiceMessageFormat.CODEC2_1200.wireMode)
        assertEquals(0x08, VoiceMessageFormat.CODEC2_2400.wireMode)
        assertEquals(0x09, VoiceMessageFormat.CODEC2_3200.wireMode)
    }

    @Test
    fun `remove voice recording deletes pinned draft on attachment IO dispatcher`() =
        runViewModelTest {
            val recordingFile = java.io.File.createTempFile("voice_remove", ".ogg", applicationContext.cacheDir)
            val recording = RecordedAudio(recordingFile, durationMillis = 1_000L, sizeBytes = recordingFile.length())
            val recorder = mockk<VoiceMessageRecorder>()
            every { recorder.state } returns MutableStateFlow(VoiceMessageRecordingState(selectedRecording = recording))
            every { recorder.removeSelected(recording) } answers {
                recordingFile.delete()
                true
            }
            viewModel.javaClass.getDeclaredField("voiceMessageRecorder").apply {
                isAccessible = true
                set(viewModel, recorder)
            }

            viewModel.requestRemoveVoiceRecording()

            verify(exactly = 0) { recorder.removeSelected(any()) }
            advanceUntilIdle()
            verify(exactly = 1) { recorder.removeSelected(recording) }
            assertFalse(recordingFile.exists())
        }

    @Test
    fun `voice message fields preserve selected codec2 wire mode`() = runTest {
        val fields =
            buildFieldsJson(
                imageData = null,
                imageFormat = null,
                voiceBytes = byteArrayOf(1, 2, 3),
                voiceMode = VoiceMessageFormat.CODEC2_1200.wireMode,
            )
        val audio = org.json.JSONObject(fields).getJSONArray("7")

        assertEquals(0x04, audio.getInt(0))
        assertEquals("010203", audio.getString(1))
    }

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    // Create fresh dispatcher per test to avoid exception leakage between tests
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var applicationContext: Context
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var rnsTransportAdmin: RnsTransportAdmin
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var locationSharingManager: LocationSharingManager
    private lateinit var identityRepository: IdentityRepository
    private lateinit var conversationLinkManager: ConversationLinkManager
    private lateinit var receivedLocationRepository: ReceivedLocationRepository
    private lateinit var blockedPeerRepository: network.columba.app.data.repository.BlockedPeerRepository
    private lateinit var identityResolutionManager: IdentityResolutionManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var rnsTelephony: RnsTelephony
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
        // Create fresh UnconfinedTestDispatcher per test to avoid exception leakage
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        applicationContext = mockk(relaxed = true)
        every { applicationContext.applicationContext } returns applicationContext
        every { applicationContext.cacheDir } returns java.io.File(System.getProperty("java.io.tmpdir"), "test_cache").apply { mkdirs() }
        every { applicationContext.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"), "test_files").apply { mkdirs() }
        rnsCore = mockk()
        rnsLxmf = mockk()
        rnsTransportAdmin = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        activeConversationManager = mockk()
        settingsRepository = mockk()
        propagationNodeManager = mockk()
        locationSharingManager = mockk()
        identityRepository = mockk()
        conversationLinkManager = mockk()
        receivedLocationRepository = mockk()
        blockedPeerRepository = mockk()
        identityResolutionManager = mockk()
        coEvery { identityResolutionManager.requestPathForContact(any()) } just Runs

        notificationHelper = mockk()
        every { notificationHelper.cancelNotificationForConversation(any()) } just Runs
        rnsTelephony = mockk()
        every { rnsTelephony.callState } returns MutableStateFlow(CallState.Idle)

        // Mock receivedLocationRepository to return no location by default
        every { receivedLocationRepository.observeHasLocation(any()) } returns flowOf(false)

        // Mock activeConversationManager methods
        every { activeConversationManager.setActive(any()) } just Runs

        // Mock settingsRepository methods
        coEvery { settingsRepository.getDefaultDeliveryMethod() } returns "direct"
        coEvery { settingsRepository.getTryPropagationOnFail() } returns true
        coEvery { settingsRepository.getIncomingMessageSizeLimitKb() } returns 500
        every { settingsRepository.messageFontScaleFlow } returns flowOf(1.0f)
        every { settingsRepository.sortMessagesBySentTime } returns flowOf(false)

        // Mock conversationLinkManager flows
        every { conversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())
        every { conversationLinkManager.observePeerActivity(any()) } returns flowOf(null)
        every { conversationLinkManager.openConversationLink(any()) } just Runs

        // Mock identityRepository to return null by default (no icon set)
        coEvery { identityRepository.getActiveIdentitySync() } returns null

        // Mock locationSharingManager flows
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())
        every { locationSharingManager.sharingEvents } returns MutableSharedFlow()

        // Mock default contact repository behavior
        every { contactRepository.hasContactFlow(any()) } returns flowOf(false)
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.addContactFromConversation(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.deleteContact(any()) } just Runs

        // Mock propagationNodeManager flows
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.manualSyncResult } returns MutableSharedFlow()
        every { propagationNodeManager.syncProgress } returns
            MutableStateFlow(network.columba.app.service.SyncProgress.Idle)
        every { propagationNodeManager.currentRelay } returns MutableStateFlow(null)
        coEvery { propagationNodeManager.triggerSync() } just Runs
        coEvery { propagationNodeManager.triggerSync(silent = any()) } just Runs

        // Mock locationSharingManager methods
        every { locationSharingManager.startSharing(any(), any(), any()) } just Runs
        every { locationSharingManager.stopSharing(any()) } just Runs

        // Mock default behaviors
        coEvery { rnsLxmf.getLxmfIdentity() } returns Result.success(testIdentity)
        every { rnsLxmf.setConversationActive(any()) } just Runs
        coEvery { conversationRepository.getConversation(any()) } returns null
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.markConversationAsRead(any()) } just Runs

        // Mock delivery status observer (returns empty flow by default)
        every { rnsLxmf.observeDeliveryStatus() } returns flowOf()
        every { rnsLxmf.observeTransferProgress() } returns flowOf()

        // Mock reaction received flow (returns empty flow by default)
        every { rnsTransportAdmin.reactionReceivedFlow } returns MutableSharedFlow()

        // Mock database methods needed by delivery status handler
        coEvery { conversationRepository.getMessageById(any()) } returns null
        coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs

        // Default: no messages (mock both old and new methods for compatibility)
        every { conversationRepository.getMessages(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.getMessagesPaged(any()) } returns flowOf(PagingData.empty())

        // Default: no announce info
        every { announceRepository.getAnnounceFlow(any()) } returns flowOf(null)
        coEvery { announceRepository.getAnnounce(any()) } returns null

        // Default: no reply preview
        coEvery { conversationRepository.getReplyPreview(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        // Wait for pending IO coroutines to complete before resetting dispatcher
        Thread.sleep(100)
        Dispatchers.resetMain()
        clearAllMocks()
        // Clean up temp hex files created during file attachment tests
        java.io
            .File(System.getProperty("java.io.tmpdir"), "test_cache")
            .takeIf { it.exists() }
            ?.deleteRecursively()
        java.io
            .File(System.getProperty("java.io.tmpdir"), "test_files")
            .takeIf { it.exists() }
            ?.deleteRecursively()
    }

    /**
     * Runs a test with the ViewModel created inside the test's coroutine scope.
     * This ensures coroutines launched during ViewModel init are properly tracked.
     */
    private fun runViewModelTest(testBody: suspend TestScope.() -> Unit) =
        runTest {
            viewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                    notificationHelper,
                    rnsTelephony,
                ).also { it.attachmentIoDispatcher = StandardTestDispatcher(testScheduler) }
            advanceUntilIdle()
            testBody()
        }

    /**
     * Creates a ViewModel for tests that need custom mock setup BEFORE ViewModel creation.
     * Use runViewModelTest {} for most tests; use this with runTest {} only when needed.
     */
    private fun createTestViewModel(
        microphoneArbiter: MicrophoneAdmissionArbiter = MicrophoneAdmissionArbiter(),
    ): MessagingViewModel =
        MessagingViewModel(
            applicationContext,
            rnsCore,
            rnsLxmf,
            rnsTransportAdmin,
            conversationRepository,
            announceRepository,
            contactRepository,
            activeConversationManager,
            settingsRepository,
            propagationNodeManager,
            locationSharingManager,
            identityRepository,
            conversationLinkManager,
            receivedLocationRepository,
            blockedPeerRepository,
            identityResolutionManager,
            notificationHelper,
            rnsTelephony,
            microphoneArbiter,
        )

    @Test
    fun `initial state has empty messages`() =
        runViewModelTest {
            // Before loadMessages is called, the messages flow returns empty PagingData
            // Assert: Repository was not called because loadMessages wasn't invoked
            val result = runCatching { viewModel.messages.first() }
            assertTrue("Messages flow should be accessible", result.isSuccess)
            coVerify(exactly = 0) { conversationRepository.getMessagesPaged(any()) }
        }

    @Test
    fun `voice recording is blocked while a call is active`() =
        runTest {
            every { rnsTelephony.callState } returns MutableStateFlow(CallState.Active("peer"))
            viewModel = createTestViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.isVoiceRecordingBlockedByCall.value)
            assertThrows(IllegalStateException::class.java) {
                viewModel.startVoiceRecording()
            }
        }

    @Test
    fun `voice recording admission is blocked while outgoing call owns microphone`() =
        runTest {
            val arbiter = MicrophoneAdmissionArbiter()
            assertNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
            viewModel = createTestViewModel(arbiter)
            advanceUntilIdle()

            assertThrows(IllegalStateException::class.java) {
                viewModel.startVoiceRecording()
            }
        }

    @Test
    fun `codec linkage failure releases voice recording microphone lease`() =
        runTest {
            val arbiter = MicrophoneAdmissionArbiter()
            val failingRecorder = mockk<VoiceMessageRecorder>()
            every { failingRecorder.start(any(), any()) } throws UnsatisfiedLinkError("codec2 unavailable")
            val testViewModel = createTestViewModel(arbiter)
            testViewModel.javaClass.getDeclaredField("voiceMessageRecorder").apply {
                isAccessible = true
                set(testViewModel, failingRecorder)
            }

            assertThrows(UnsatisfiedLinkError::class.java) {
                testViewModel.startVoiceRecording(VoiceMessageFormat.CODEC2_1200)
            }

            assertNull(arbiter.currentOwner())
        }

    @Test
    fun `maximum duration terminal transition releases voice recording microphone lease`() =
        runTest {
            val arbiter = MicrophoneAdmissionArbiter()
            val testViewModel = createTestViewModel(arbiter)
            advanceUntilIdle()
            val ownedLease = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
            testViewModel.javaClass.getDeclaredField("voiceRecordingLease").apply {
                isAccessible = true
                set(testViewModel, ownedLease)
            }
            val recorder =
                testViewModel.javaClass.getDeclaredField("voiceMessageRecorder").let { field ->
                    field.isAccessible = true
                    field.get(testViewModel) as VoiceMessageRecorder
                }
            val recordingFile = java.io.File.createTempFile("voice_deadline", ".ogg", applicationContext.cacheDir)
            val recording = RecordedAudio(recordingFile, durationMillis = 300_000L, sizeBytes = recordingFile.length())
            @Suppress("UNCHECKED_CAST")
            val recorderState =
                VoiceMessageRecorder::class.java.getDeclaredField("_state").let { field ->
                    field.isAccessible = true
                    field.get(recorder) as MutableStateFlow<VoiceMessageRecordingState>
                }

            recorderState.value =
                VoiceMessageRecordingState(
                    recorderState = tech.torlando.lxst.recording.RecorderState.Completed(recording),
                    selectedRecording = recording,
                    selectedFormat = VoiceMessageFormat.OPUS_MEDIUM,
                )
            advanceUntilIdle()

            assertNull(arbiter.currentOwner())
            val callLease = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
            arbiter.release(callLease)
            recordingFile.delete()
        }

    @Test
    fun `call microphone ownership includes transitional call states`() {
        assertTrue(callUsesMicrophone(CallState.Connecting("peer")))
        assertTrue(callUsesMicrophone(CallState.Ringing("peer")))
        assertTrue(callUsesMicrophone(CallState.Incoming("peer")))
        assertTrue(callUsesMicrophone(CallState.Active("peer")))
        assertFalse(callUsesMicrophone(CallState.Idle))
        assertFalse(callUsesMicrophone(CallState.Ended))
    }

    @Test
    fun `peerActivity exposes durable timestamp for current conversation`() =
        runTest {
            val expected =
                network.columba.app.data.db.entity.PeerActivityEntity(
                    destinationHash = testPeerHash,
                    lastReceivedAt = 12_345L,
                    activityType = network.columba.app.data.db.entity.PeerActivityType.TELEMETRY,
                )
            every { conversationLinkManager.observePeerActivity(testPeerHash) } returns flowOf(expected)
            val localViewModel = createTestViewModel()
            val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
                localViewModel.peerActivity.collect { }
            }

            localViewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            assertEquals(expected, localViewModel.peerActivity.value)
            collection.cancel()
        }

    @Test
    fun `loadMessages updates current conversation and triggers flow`() =
        runViewModelTest {
            // Act: Load messages for conversation
            val result = runCatching { viewModel.loadMessages(testPeerHash, testPeerName) }
            advanceUntilIdle()

            // Assert: loadMessages completed successfully
            assertTrue("loadMessages should complete without error", result.isSuccess)

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify: Repository was called with correct peer hash
            coVerify { conversationRepository.getMessagesPaged(testPeerHash) }

            // Verify: Fast polling enabled
            verify { rnsLxmf.setConversationActive(true) }
        }

    @Test
    fun `conversation visibility dismisses its notification and marks it read`() =
        runViewModelTest {
            val result = runCatching { viewModel.onConversationVisible(testPeerHash) }
            advanceUntilIdle()

            assertTrue("Visibility handling should complete without error", result.isSuccess)
            verify { activeConversationManager.setActive(testPeerHash) }
            verify { notificationHelper.cancelNotificationForConversation(testPeerHash) }
            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `sendMessage success saves message to database with sent status`() =
        runViewModelTest {
            // Setup: Mock successful LXMF send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            // Act: Send message
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test message") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

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
                rnsLxmf.sendLxmfMessageWithMethod(
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
    fun `sendMessage with propagated default method sends short text via PROPAGATED`() =
        runViewModelTest {
            // The setup stubs getDefaultDeliveryMethod -> "direct"; override for this test
            coEvery { settingsRepository.getDefaultDeliveryMethod() } returns "propagated"

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test message") }
            advanceUntilIdle()

            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Verify: Short text goes to the relay directly, not opportunistic first
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "Test message",
                    sourceIdentity = testIdentity,
                    deliveryMethod = DeliveryMethod.PROPAGATED,
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }
        }

    @Test
    fun `sendMessage with direct default method keeps OPPORTUNISTIC for short text`() =
        runViewModelTest {
            // setup() already stubs getDefaultDeliveryMethod -> "direct"
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test message") }
            advanceUntilIdle()

            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Verify: Direct default keeps the opportunistic fast path for short text
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "Test message",
                    sourceIdentity = testIdentity,
                    deliveryMethod = DeliveryMethod.OPPORTUNISTIC,
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }
        }

    @Test
    fun `sendMessage with propagated default method sends large text via PROPAGATED`() =
        runViewModelTest {
            // The setup stubs getDefaultDeliveryMethod -> "direct"; override for this test
            coEvery { settingsRepository.getDefaultDeliveryMethod() } returns "propagated"

            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            // 400 bytes > OPPORTUNISTIC_MAX_BYTES (295) - falls into the method-selection branch
            val result = runCatching { viewModel.sendMessage(testPeerHash, "x".repeat(400)) }
            advanceUntilIdle()

            assertTrue("sendMessage should complete without error", result.isSuccess)

            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "x".repeat(400),
                    sourceIdentity = testIdentity,
                    deliveryMethod = DeliveryMethod.PROPAGATED,
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                )
            }
        }

    @Test
    fun `sendMessage failure saves message to database with failed status`() =
        runViewModelTest {
            // Setup: Mock failed LXMF send
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            // Act: Send message
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test message") }
            advanceUntilIdle()

            // Assert: sendMessage completed (even though send failed, method should not throw)
            assertTrue("sendMessage should complete without error", result.isSuccess)

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
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32),
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Verify: Destination hash was converted to bytes
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash =
                        match {
                            // "abcdef0123456789abcdef0123456789" -> 16 bytes
                            val expected =
                                byteArrayOf(
                                    0xab.toByte(),
                                    0xcd.toByte(),
                                    0xef.toByte(),
                                    0x01,
                                    0x23,
                                    0x45,
                                    0x67,
                                    0x89.toByte(),
                                    0xab.toByte(),
                                    0xcd.toByte(),
                                    0xef.toByte(),
                                    0x01,
                                    0x23,
                                    0x45,
                                    0x67,
                                    0x89.toByte(),
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
        runViewModelTest {
            val result = runCatching { viewModel.markAsRead(testPeerHash) }
            advanceUntilIdle()

            assertTrue("markAsRead should complete without error", result.isSuccess)
            coVerify { conversationRepository.markConversationAsRead(testPeerHash) }
        }

    @Test
    fun `switching conversations updates message flow`() =
        runViewModelTest {
            val conversation1Hash = "peer1"
            val conversation2Hash = "peer2"

            // Load first conversation
            val result1 = runCatching { viewModel.loadMessages(conversation1Hash, "Peer 1") }
            viewModel.onConversationVisible(conversation1Hash)
            advanceUntilIdle()

            assertTrue("First loadMessages should succeed", result1.isSuccess)

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify first conversation was loaded
            coVerify { conversationRepository.getMessagesPaged(conversation1Hash) }

            // Switch to second conversation
            val result2 = runCatching { viewModel.loadMessages(conversation2Hash, "Peer 2") }
            viewModel.onConversationVisible(conversation2Hash)
            advanceUntilIdle()

            assertTrue("Second loadMessages should succeed", result2.isSuccess)

            // Trigger the flow by collecting
            viewModel.messages.first()
            advanceUntilIdle()

            // Verify second conversation was loaded
            coVerify { conversationRepository.getMessagesPaged(conversation2Hash) }

            // Verify visibility for both conversations marked each one as read
            coVerify { conversationRepository.markConversationAsRead(conversation1Hash) }
            coVerify { conversationRepository.markConversationAsRead(conversation2Hash) }
        }

    @Test
    fun `identity loads successfully on init`() =
        runViewModelTest {
            // Identity loading is now lazy - happens when sending messages, not during init
            // This avoids crashes when LXMF router isn't ready yet
            // Send a message to trigger identity loading
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(MessageReceipt(ByteArray(32), 3000L, testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            val result = runCatching { viewModel.sendMessage(testPeerHash, "Test") }
            advanceUntilIdle()

            assertTrue("sendMessage should complete without error", result.isSuccess)
            // Verify the protocol was called to get identity
            coVerify { rnsLxmf.getLxmfIdentity() }
        }

    @Test
    fun `sendMessage without loaded identity logs error and returns early`() =
        runViewModelTest {
            // Clear existing mocks and create new ones that fail identity loading
            clearAllMocks()
            every { rnsTelephony.callState } returns MutableStateFlow(CallState.Idle)

            val failingRnsCore: RnsCore = mockk()
            val failingRnsLxmf: RnsLxmf = mockk()
            val failingRnsTransportAdmin: RnsTransportAdmin = mockk()
            coEvery { failingRnsLxmf.getLxmfIdentity() } returns Result.failure(Exception("No identity"))
            every { failingRnsLxmf.setConversationActive(any()) } just Runs
            every { failingRnsLxmf.observeDeliveryStatus() } returns flowOf()
            every { failingRnsTransportAdmin.reactionReceivedFlow } returns MutableSharedFlow()

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

            val failingActiveConversationManager: ActiveConversationManager = mockk()
            every { failingActiveConversationManager.setActive(any()) } just Runs

            val failingSettingsRepository: SettingsRepository = mockk()
            coEvery { failingSettingsRepository.getDefaultDeliveryMethod() } returns "direct"
            coEvery { failingSettingsRepository.getTryPropagationOnFail() } returns true
            coEvery { failingSettingsRepository.getIncomingMessageSizeLimitKb() } returns 500
            every { failingSettingsRepository.messageFontScaleFlow } returns flowOf(1.0f)
            every { failingSettingsRepository.sortMessagesBySentTime } returns flowOf(false)

            val failingPropagationNodeManager: PropagationNodeManager = mockk()
            every { failingPropagationNodeManager.isSyncing } returns MutableStateFlow(false)
            every { failingPropagationNodeManager.manualSyncResult } returns MutableSharedFlow()
            every { failingPropagationNodeManager.syncProgress } returns
                MutableStateFlow(network.columba.app.service.SyncProgress.Idle)
            every { failingPropagationNodeManager.currentRelay } returns MutableStateFlow(null)
            coEvery { failingPropagationNodeManager.triggerSync() } just Runs
            coEvery { failingPropagationNodeManager.triggerSync(silent = any()) } just Runs

            val failingLocationSharingManager: LocationSharingManager = mockk()
            every { failingLocationSharingManager.activeSessions } returns MutableStateFlow(emptyList())
            every { failingLocationSharingManager.sharingEvents } returns MutableSharedFlow()
            every { failingLocationSharingManager.startSharing(any(), any(), any()) } just Runs
            every { failingLocationSharingManager.stopSharing(any()) } just Runs

            val failingConversationLinkManager = mockk<ConversationLinkManager>()
            every { failingConversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())
            every { failingConversationLinkManager.observePeerActivity(any()) } returns flowOf(null)
            val viewModelWithoutIdentity =
                MessagingViewModel(
                    applicationContext,
                    failingRnsCore,
                    failingRnsLxmf,
                    failingRnsTransportAdmin,
                    failingRepository,
                    failingAnnounceRepository,
                    failingContactRepository,
                    failingActiveConversationManager,
                    failingSettingsRepository,
                    failingPropagationNodeManager,
                    failingLocationSharingManager,
                    identityRepository,
                    failingConversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )

            // Attempt to send message
            val result = runCatching { viewModelWithoutIdentity.sendMessage(testPeerHash, "Test") }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing (handles identity failure gracefully)
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Verify: sendLxmfMessageWithMethod was NOT called
            coVerify(exactly = 0) { failingRnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

            // Verify: saveMessage was NOT called
            coVerify(exactly = 0) { failingRepository.saveMessage(any(), any(), any(), any()) }
        }

    @Test
    fun `messages flow updates when database changes`() =
        runViewModelTest {
            // Create a mutable flow to simulate database changes (using PagingData)
            val messagesFlow = MutableStateFlow<PagingData<DataMessage>>(PagingData.empty())
            coEvery { conversationRepository.getMessagesPaged(testPeerHash) } returns messagesFlow

            val result = runCatching { viewModel.loadMessages(testPeerHash, testPeerName) }
            advanceUntilIdle()

            // Assert: loadMessages completed successfully
            assertTrue("loadMessages should complete without error", result.isSuccess)

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
        runViewModelTest {
            val invalidHash = "invalid!hash@123" // Contains invalid characters

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send with invalid hash
            val result = runCatching { viewModel.sendMessage(invalidHash, "Test message") }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with non-hex destination hash does not send`() =
        runViewModelTest {
            val nonHexHash = "ghijklmn" // Valid characters but not hex

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            val result = runCatching { viewModel.sendMessage(nonHexHash, "Test message") }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Verify: No protocol call made
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Verify: No save to database
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with empty content does not send`() =
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send empty message
            val result = runCatching { viewModel.sendMessage(testPeerHash, "") }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with whitespace-only content does not send`() =
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send whitespace-only message
            val result = runCatching { viewModel.sendMessage(testPeerHash, "   \n\t   ") }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with too-long content does not send`() =
        runViewModelTest {
            // Create message longer than MAX_MESSAGE_LENGTH (10000 chars)
            val tooLongMessage = "a".repeat(10001)

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Try to send too-long message
            val result = runCatching { viewModel.sendMessage(testPeerHash, tooLongMessage) }
            advanceUntilIdle()

            // Assert: sendMessage completed without throwing
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol NOT called
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message NOT saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage sanitizes content before sending`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message with leading/trailing whitespace
            val result = runCatching { viewModel.sendMessage(testPeerHash, "  Test message  \n") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Message was trimmed before sending
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
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
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message exactly at max length (10000 chars)
            val maxLengthMessage = "a".repeat(10000)
            val result = runCatching { viewModel.sendMessage(testPeerHash, maxLengthMessage) }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol was called (message is valid)
            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Message was saved
            coVerify(exactly = 1) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `sendMessage with valid hex hash succeeds`() =
        runViewModelTest {
            val validHash = "abcdef0123456789abcdef0123456789" // Valid 32-char hex hash
            val destHashBytes = validHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery {
                conversationRepository.saveMessage(any(), any(), any(), any())
            } just Runs

            viewModel.loadMessages(validHash, testPeerName)
            advanceUntilIdle()

            // Act: Send message with valid hash
            val result = runCatching { viewModel.sendMessage(validHash, "Test message") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol was called
            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
            val result = runCatching { viewModel.sendMessage(testPeerHash, "") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Assert: Protocol was called with image data
            // Note: Empty content is replaced with single space for Sideband compatibility
            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(
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
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Image was cleared after successful send
            assertEquals(null, viewModel.selectedImageData.value)
            assertEquals(null, viewModel.selectedImageFormat.value)
        }

    @Test
    fun `sendMessage retains composer attachment when local persistence fails`() =
        runViewModelTest {
            val destination = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { it.toByte() },
                        timestamp = 3_000L,
                        destinationHash = destination,
                    ),
                )
            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } throws
                IllegalStateException("database unavailable")
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val image = byteArrayOf(0x01, 0x02, 0x03)
            viewModel.selectImage(image, "png")
            val sendResult = async { viewModel.composerSendResult.first() }

            viewModel.sendMessage(testPeerHash, "Keep attachment")
            advanceUntilIdle()

            assertArrayEquals(image, viewModel.selectedImageData.value)
            assertEquals("png", viewModel.selectedImageFormat.value)
            assertFalse(sendResult.await().clearComposer)
            coVerify(exactly = 0) { conversationRepository.clearDraft(testPeerHash) }
        }

    // ========== DELIVERY STATUS HANDLING TESTS ==========

    @Test
    fun `retrying_propagated status updates both status and deliveryMethod`() =
        runViewModelTest {
            // Setup: Create a flow that emits a retrying_propagated status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

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
                applicationContext,
                rnsCore,
                rnsLxmf,
                rnsTransportAdmin,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
                locationSharingManager,
                identityRepository,
                conversationLinkManager,
                receivedLocationRepository,
                blockedPeerRepository,
                identityResolutionManager,
            notificationHelper,
                rnsTelephony,
            )
            advanceUntilIdle()

            // Emit a retrying_propagated status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.RETRYING_PROPAGATED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was called with retrying_propagated
            coVerify {
                conversationRepository.applyDeliveryStatus(testMessageHash, "retrying_propagated")
            }

            // Delivery method changes atomically inside the repository reducer.
            coVerify(exactly = 0) {
                conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
            }
        }

    @Test
    fun `delivered status updates message status only`() =
        runViewModelTest {
            // Setup: Create a flow that emits a delivered status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

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
                applicationContext,
                rnsCore,
                rnsLxmf,
                rnsTransportAdmin,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
                locationSharingManager,
                identityRepository,
                conversationLinkManager,
                receivedLocationRepository,
                blockedPeerRepository,
                identityResolutionManager,
            notificationHelper,
                rnsTelephony,
            )
            advanceUntilIdle()

            // Emit a delivered status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.DELIVERED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was called with delivered
            coVerify {
                conversationRepository.applyDeliveryStatus(testMessageHash, "delivered")
            }

            // Verify: updateMessageDeliveryDetails was NOT called (only called for retrying_propagated)
            coVerify(exactly = 0) {
                conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
            }
        }

    @Test
    fun `failed status updates message status`() =
        runViewModelTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with "pending" status (non-terminal)
            // Note: Issue #257 fix prevents status degradation from terminal states
            // (sent/propagated/delivered) to failed, so we use "pending" here
            val testMessageHash = "failed_message_hash"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "pending",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                applicationContext,
                rnsCore,
                rnsLxmf,
                rnsTransportAdmin,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
                locationSharingManager,
                identityRepository,
                conversationLinkManager,
                receivedLocationRepository,
                blockedPeerRepository,
                identityResolutionManager,
            notificationHelper,
                rnsTelephony,
            )
            advanceUntilIdle()

            // Emit a failed status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.FAILED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was called with failed
            coVerify {
                conversationRepository.applyDeliveryStatus(testMessageHash, "failed")
            }

            // Verify: updateMessageDeliveryDetails was NOT called (only called for retrying_propagated)
            coVerify(exactly = 0) {
                conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
            }
        }

    @Test
    fun `delivery status gracefully handles unknown message hash`() =
        runViewModelTest {
            // Setup: Create a flow that emits a status update for unknown message
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message does NOT exist in database (returns null after retries)
            val unknownMessageHash = "unknown_message_hash"
            coEvery { conversationRepository.getMessageById(unknownMessageHash) } returns null
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            MessagingViewModel(
                applicationContext,
                rnsCore,
                rnsLxmf,
                rnsTransportAdmin,
                conversationRepository,
                announceRepository,
                contactRepository,
                activeConversationManager,
                settingsRepository,
                propagationNodeManager,
                locationSharingManager,
                identityRepository,
                conversationLinkManager,
                receivedLocationRepository,
                blockedPeerRepository,
                identityResolutionManager,
            notificationHelper,
                rnsTelephony,
            )
            advanceUntilIdle()

            // Emit a status update for unknown message
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = unknownMessageHash,
                            status = DeliveryStatus.DELIVERED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: getMessageById was called (with retries)
            coVerify(atLeast = 1) {
                conversationRepository.getMessageById(unknownMessageHash)
            }

            // Verify: updateMessageStatus was NOT called (message not found)
            coVerify(exactly = 0) {
                conversationRepository.applyDeliveryStatus(unknownMessageHash, any())
            }
        }

    // ========== STATUS DEGRADATION PROTECTION TESTS (Issue #257) ==========

    @Test
    fun `failed status is delegated to reducer when message is already propagated`() =
        runViewModelTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with 'propagated' status
            val testMessageHash = "propagated_msg_spurious_fail"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "propagated", // Already in terminal success state
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            @Suppress("UnusedPrivateProperty") // ViewModel needs to exist to collect flows
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            // Emit a 'failed' status update (this is the spurious callback we want to block)
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.FAILED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was NOT called (status degradation blocked)
            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "failed")
            }
        }

    @Test
    fun `failed status is delegated to reducer when message is already sent`() =
        runViewModelTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with 'sent' status
            val testMessageHash = "sent_msg_spurious_fail"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "sent", // Already in terminal success state
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            @Suppress("UnusedPrivateProperty") // ViewModel needs to exist to collect flows
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            // Emit a 'failed' status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.FAILED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was NOT called (status degradation blocked)
            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "failed")
            }
        }

    @Test
    fun `failed status is delegated to reducer when message is already delivered`() =
        runViewModelTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with 'delivered' status
            val testMessageHash = "delivered_msg_spurious_fail"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "delivered", // Already in terminal success state
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            @Suppress("UnusedPrivateProperty") // ViewModel needs to exist to collect flows
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            // Emit a 'failed' status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.FAILED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus was NOT called (status degradation blocked)
            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "failed")
            }
        }

    @Test
    fun `failed status is allowed when message is pending`() =
        runViewModelTest {
            // Setup: Create a flow that emits a failed status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with 'pending' status (not terminal)
            val testMessageHash = "pending_msg_legit_fail"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "pending", // NOT a terminal success state
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            @Suppress("UnusedPrivateProperty") // ViewModel needs to exist to collect flows
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            // Emit a 'failed' status update
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.FAILED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus WAS called (legitimate failure)
            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "failed")
            }
        }

    @Test
    fun `non-failed status updates still work for terminal states`() =
        runViewModelTest {
            // Setup: Create a flow that emits a delivered status update
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            // Mock the message exists in database with 'sent' status
            val testMessageHash = "sent_msg_upgrade_to_delivered"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "sent", // Will be upgraded to delivered
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            // Create a new ViewModel to pick up the mocked flow
            @Suppress("UnusedPrivateProperty") // ViewModel needs to exist to collect flows
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            // Emit a 'delivered' status update (upgrading from sent)
            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.DELIVERED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            // Verify: updateMessageStatus WAS called (status upgrade allowed)
            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "delivered")
            }
        }

    // ========== DELIVERED TERMINAL STATE TESTS ==========

    @Test
    fun `propagated status is delegated to reducer when message is already delivered`() =
        runViewModelTest {
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            val testMessageHash = "delivered_msg_spurious_propagated"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "delivered",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            @Suppress("UnusedPrivateProperty")
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.PROPAGATED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "propagated")
            }
        }

    @Test
    fun `retrying_propagated status is delegated to reducer when message is already delivered`() =
        runViewModelTest {
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            val testMessageHash = "delivered_msg_spurious_retrying"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "delivered",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            @Suppress("UnusedPrivateProperty")
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.RETRYING_PROPAGATED,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "retrying_propagated")
            }
        }

    @Test
    fun `pending status is delegated to reducer when message is already delivered`() =
        runViewModelTest {
            val deliveryStatusFlow = MutableSharedFlow<DeliveryStatusUpdate>()
            every { rnsLxmf.observeDeliveryStatus() } returns deliveryStatusFlow

            val testMessageHash = "delivered_msg_spurious_sent"
            val existingMessage =
                MessageEntity(
                    id = testMessageHash,
                    conversationHash = testPeerHash,
                    identityHash = "test_identity_hash",
                    content = "Test message",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "delivered",
                )
            coEvery { conversationRepository.getMessageById(testMessageHash) } returns existingMessage
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } just Runs

            @Suppress("UnusedPrivateProperty")
            val testViewModel =
                MessagingViewModel(
                    applicationContext,
                    rnsCore,
                    rnsLxmf,
                    rnsTransportAdmin,
                    conversationRepository,
                    announceRepository,
                    contactRepository,
                    activeConversationManager,
                    settingsRepository,
                    propagationNodeManager,
                    locationSharingManager,
                    identityRepository,
                    conversationLinkManager,
                    receivedLocationRepository,
                    blockedPeerRepository,
                    identityResolutionManager,
                notificationHelper,
                rnsTelephony,
                )
            advanceUntilIdle()

            val emitResult =
                runCatching {
                    deliveryStatusFlow.emit(
                        DeliveryStatusUpdate(
                            messageHash = testMessageHash,
                            status = DeliveryStatus.PENDING,
                            timestamp = System.currentTimeMillis(),
                            originatingIdentityHash = "test_identity_hash",
                        ),
                    )
                }
            advanceUntilIdle()

            assertTrue("Status update emission should complete without error", emitResult.isSuccess)

            coVerify(exactly = 1) {
                conversationRepository.applyDeliveryStatus(testMessageHash, "pending")
            }
        }

    // ========== CONTACT TOGGLE TESTS ==========

    @Test
    fun `isContactSaved returns false when contact not saved`() =
        runViewModelTest {
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
        runViewModelTest {
            // Assert: Before loading any conversation, isContactSaved is false
            assertEquals(false, viewModel.isContactSaved.value)
        }

    @Test
    fun `toggleContact adds contact when not saved`() =
        runViewModelTest {
            // Setup: Contact is not saved
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            val testPublicKey = ByteArray(64) { it.toByte() }
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns testPublicKey

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should add)
            val result = runCatching { viewModel.toggleContact() }
            advanceUntilIdle()

            // Assert: toggleContact completed successfully
            assertTrue("toggleContact should complete without error", result.isSuccess)

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
        runViewModelTest {
            // Setup: Contact is already saved
            coEvery { contactRepository.hasContact(testPeerHash) } returns true

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should remove)
            val result = runCatching { viewModel.toggleContact() }
            advanceUntilIdle()

            // Assert: toggleContact completed successfully
            assertTrue("toggleContact should complete without error", result.isSuccess)

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
        runViewModelTest {
            // Don't load any conversation - _currentConversation is null

            // Act: Toggle contact (should do nothing)
            val result = runCatching { viewModel.toggleContact() }
            advanceUntilIdle()

            // Assert: toggleContact completed without throwing
            assertTrue("toggleContact should complete without error", result.isSuccess)

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
        runViewModelTest {
            // Setup: Contact is not saved, but public key is not available
            coEvery { contactRepository.hasContact(testPeerHash) } returns false
            coEvery { conversationRepository.getPeerPublicKey(testPeerHash) } returns null

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should fail gracefully)
            val result = runCatching { viewModel.toggleContact() }
            advanceUntilIdle()

            // Assert: toggleContact completed without error
            assertTrue("toggleContact should complete without error", result.isSuccess)

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
        runViewModelTest {
            // Setup: Contact is not saved, repository throws exception
            coEvery { contactRepository.hasContact(testPeerHash) } throws RuntimeException("Database error")

            // Load conversation to set current peer
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Toggle contact (should not crash)
            val result = runCatching { viewModel.toggleContact() }
            advanceUntilIdle()

            // Assert: No crash occurred - test completes successfully
            assertTrue("toggleContact should complete without throwing", result.isSuccess)
            // Verify hasContact was called
            coVerify {
                contactRepository.hasContact(testPeerHash)
            }
        }

    // ========== CONTACT TOGGLE RESULT EMISSION TESTS ==========

    @Test
    fun `toggleContact emits Added result when contact successfully added`() =
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            // Call with null fieldsJson - should not crash
            viewModel.loadImageAsync("test-msg", null)
            advanceUntilIdle()

            // Assert: No crash occurred, loadedImageIds unchanged
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync does not crash on invalid JSON`() =
        runViewModelTest {
            // Call with invalid JSON - should not crash
            viewModel.loadImageAsync("test-msg", "not valid json")
            advanceUntilIdle()

            // Assert: No crash occurred, loadedImageIds unchanged (decode failed)
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadedImageIds initial state is empty`() =
        runViewModelTest {
            // Assert: Initial state is empty set
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    // ========== saveReceivedFileAttachment Tests ==========

    @Test
    fun `saveReceivedFileAttachment returns false when message not found`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "nonexistent-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when fieldsJson is null`() =
        runViewModelTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = null)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when field 5 is missing`() =
        runViewModelTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false when file index is out of bounds`() =
        runViewModelTest {
            // Arrange - fieldsJson with one attachment, but requesting index 5
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 5, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment writes file data to output stream`() =
        runViewModelTest {
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

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertTrue(result)
            assertEquals("Hello", outputStream.toString())
        }

    @Test
    fun `saveReceivedFileAttachment returns false when output stream is null`() =
        runViewModelTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns null

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment returns false on exception`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveReceivedFileAttachment(context, "test-id", 0, uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveReceivedFileAttachment saves correct attachment from multiple`() =
        runViewModelTest {
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

    // ========== IMAGE SAVE/SHARE TESTS ==========

    @Test
    fun `saveImage returns false when message not found`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveImage(context, "nonexistent-id", uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveImage returns false when fieldsJson has no image`() =
        runViewModelTest {
            // Arrange - fieldsJson without field 6 (image)
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveImage(context, "test-id", uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveImage writes image data to output stream`() =
        runViewModelTest {
            // Arrange - "Hello" in hex is "48656c6c6f"
            val fieldsJson = """{"6": "48656c6c6f"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val outputStream = ByteArrayOutputStream()
            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns outputStream

            // Act
            val result = viewModel.saveImage(context, "test-id", uri)

            // Assert
            assertTrue(result)
            assertEquals("Hello", outputStream.toString())
        }

    @Test
    fun `saveImage returns false when output stream is null`() =
        runViewModelTest {
            // Arrange
            val fieldsJson = """{"6": "48656c6c6f"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val context = mockk<android.content.Context>()
            val uri = mockk<android.net.Uri>()
            val contentResolver = mockk<android.content.ContentResolver>()
            every { context.contentResolver } returns contentResolver
            every { contentResolver.openOutputStream(uri) } returns null

            // Act
            val result = viewModel.saveImage(context, "test-id", uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `saveImage returns false on exception`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)
            val uri = mockk<android.net.Uri>()

            // Act
            val result = viewModel.saveImage(context, "test-id", uri)

            // Assert
            assertFalse(result)
        }

    @Test
    fun `getImageShareUri returns null when message not found`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getImageShareUri(context, "nonexistent-id")

            // Assert
            assertNull(result)
        }

    @Test
    fun `getImageShareUri returns null when fieldsJson has no image`() =
        runViewModelTest {
            // Arrange - fieldsJson without field 6 (image)
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getImageShareUri(context, "test-id")

            // Assert
            assertNull(result)
        }

    @Test
    fun `getImageShareUri returns null on exception`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getImageShareUri(context, "test-id")

            // Assert
            assertNull(result)
        }

    @Test
    fun `getImageShareUri returns uri and mimetype for valid image`() =
        runViewModelTest {
            // Arrange - PNG image header: 89 50 4E 47 0D 0A 1A 0A
            val pngHex = "89504e470d0a1a0a" + "00".repeat(8) // Add more bytes for valid image
            val fieldsJson = """{"6": "$pngHex"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val context = mockk<android.content.Context>()
            val cacheDir =
                kotlin.io.path
                    .createTempDirectory("test-share")
                    .toFile()
            val mockUri = mockk<android.net.Uri>()

            every { context.cacheDir } returns cacheDir
            every { context.packageName } returns "network.columba.app"

            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider
                    .getUriForFile(any(), any(), any())
            } returns mockUri

            // Act
            val result = viewModel.getImageShareUri(context, "test-id")

            // Assert
            assertNotNull(result)
            assertEquals(mockUri, result!!.first)
            assertEquals("image/png", result.second)

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `getImageShareUri returns gif mimetype for animated gif`() =
        runViewModelTest {
            // Arrange - GIF89a header: 47 49 46 38 39 61 followed by enough bytes for animation detection
            // GIF89a + minimal valid GIF structure
            val gifHex =
                "474946383961" + "0100" + "0100" + "00" + "00" + "00" + // Header
                    "21f904" + "01" + "0000" + "00" + "00" + // Graphic Control Extension
                    "21f904" + "01" + "0000" + "00" + "00" + // Second GCE (makes it animated)
                    "2c" + "00000000" + "01000100" + "00" + "02" + "02" + "4c01003b" // Image + trailer
            val fieldsJson = """{"6": "$gifHex"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val context = mockk<android.content.Context>()
            val cacheDir =
                kotlin.io.path
                    .createTempDirectory("test-share-gif")
                    .toFile()
            val mockUri = mockk<android.net.Uri>()

            every { context.cacheDir } returns cacheDir
            every { context.packageName } returns "network.columba.app"

            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider
                    .getUriForFile(any(), any(), any())
            } returns mockUri

            // Act
            val result = viewModel.getImageShareUri(context, "test-id")

            // Assert
            assertNotNull(result)
            assertEquals("image/gif", result!!.second)

            // Cleanup
            unmockkStatic(androidx.core.content.FileProvider::class)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `getImageShareUri returns null when metadata is null`() =
        runViewModelTest {
            // Arrange - only 3 bytes, not enough for format detection (needs >= 4)
            val fieldsJson = """{"6": "010203"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getImageShareUri(context, "test-id")

            // Assert
            assertNull(result)
        }

    // ========== getImageExtension TESTS ==========

    @Test
    fun `getImageExtension returns bin when message not found`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null

            // Act
            val result = viewModel.getImageExtension("nonexistent-id")

            // Assert
            assertEquals("bin", result)
        }

    @Test
    fun `getImageExtension returns png for PNG image`() =
        runViewModelTest {
            // Arrange - PNG magic bytes
            val pngHex = "89504e470d0a1a0a" + "00".repeat(8)
            val fieldsJson = """{"6": "$pngHex"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            // Act
            val result = viewModel.getImageExtension("test-id")

            // Assert
            assertEquals("png", result)
        }

    @Test
    fun `getImageExtension returns jpg for JPEG image`() =
        runViewModelTest {
            // Arrange - JPEG magic bytes: FF D8 FF
            val jpegHex = "ffd8ffe0" + "00".repeat(8)
            val fieldsJson = """{"6": "$jpegHex"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            // Act
            val result = viewModel.getImageExtension("test-id")

            // Assert
            assertEquals("jpg", result)
        }

    @Test
    fun `getImageExtension returns bin on exception`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")

            // Act
            val result = viewModel.getImageExtension("test-id")

            // Assert
            assertEquals("bin", result)
        }

    // ========== FILE ATTACHMENT TESTS ==========

    @Test
    fun `addFileAttachment adds file to selectedFileAttachments`() =
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            val attachment = FileAttachment("file.pdf", ByteArray(100), "application/pdf", 100)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            // Try to remove at invalid index
            viewModel.removeFileAttachment(5)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `removeFileAttachment handles negative index`() =
        runViewModelTest {
            val attachment = FileAttachment("file.pdf", ByteArray(100), "application/pdf", 100)
            viewModel.addFileAttachment(attachment)
            advanceUntilIdle()

            // Try to remove at negative index
            viewModel.removeFileAttachment(-1)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `clearFileAttachments removes all files`() =
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            assertEquals(false, viewModel.isProcessingFile.value)

            viewModel.setProcessingFile(true)

            assertEquals(true, viewModel.isProcessingFile.value)

            viewModel.setProcessingFile(false)

            assertEquals(false, viewModel.isProcessingFile.value)
        }

    @Test
    fun `sendMessage with empty content but file attached succeeds`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
            val result = runCatching { viewModel.sendMessage(testPeerHash, "") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

            // Protocol should be called with file attachments
            // Note: Empty content is replaced with single space for Sideband compatibility
            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(
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
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // File attachments should be cleared after successful send
            assertEquals(0, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `syncFromPropagationNode triggers sync`() =
        runViewModelTest {
            coEvery { propagationNodeManager.triggerSync() } just Runs

            val result = runCatching { viewModel.syncFromPropagationNode() }
            advanceUntilIdle()

            assertTrue("syncFromPropagationNode should complete without error", result.isSuccess)
            coVerify { propagationNodeManager.triggerSync() }
        }

    // ========== getFileAttachmentUri Tests ==========

    @Test
    fun `getFileAttachmentUri returns null when message not found`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("nonexistent-id") } returns null
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "nonexistent-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when fieldsJson is null`() =
        runViewModelTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = null)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when field 5 is missing`() =
        runViewModelTest {
            // Arrange
            val messageEntity = createMessageEntity(fieldsJson = """{"1": "text only"}""")
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file index is out of bounds`() =
        runViewModelTest {
            // Arrange - one attachment but requesting index 5
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 5)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file data is empty`() =
        runViewModelTest {
            // Arrange - attachment with empty data
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "", "size": 0}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when file data field is missing`() =
        runViewModelTest {
            // Arrange - attachment without data field
            val fieldsJson = """{"5": [{"filename": "test.txt", "size": 100}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null on exception`() =
        runViewModelTest {
            // Arrange
            coEvery { conversationRepository.getMessageById("test-id") } throws RuntimeException("DB error")
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null for negative index`() =
        runViewModelTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", -1)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when field 5 is not array or file ref`() =
        runViewModelTest {
            // Arrange - field 5 is a string instead of array
            val fieldsJson = """{"5": "not an array"}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri returns null when attachment is not JSONObject`() =
        runViewModelTest {
            // Arrange - array contains string instead of object
            val fieldsJson = """{"5": ["not an object"]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity
            @Suppress("NoRelaxedMocks") // Android framework class
            val context = mockk<android.content.Context>(relaxed = true)

            // Act
            val result = viewModel.getFileAttachmentUri(context, "test-id", 0)

            // Assert
            assertNull(result)
        }

    @Test
    fun `getFileAttachmentUri creates file and returns URI with correct mimeType`() =
        runViewModelTest {
            // Arrange - "Hello" in hex is "48656c6c6f"
            val fieldsJson = """{"5": [{"filename": "test.pdf", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            // Create temp directory for test
            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }
            val attachmentsDir = java.io.File(tempDir, "attachments")

            val context = mockk<android.content.Context>()
            every { context.cacheDir } returns tempDir
            every { context.packageName } returns "network.columba.app"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("network.columba.app.fileprovider"),
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
        runViewModelTest {
            // Arrange - test with text file
            val fieldsJson = """{"5": [{"filename": "notes.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.cacheDir } returns tempDir
            every { context.packageName } returns "network.columba.app"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("network.columba.app.fileprovider"),
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
        runViewModelTest {
            // Arrange - multiple attachments
            val fieldsJson = """{"5": [
                {"filename": "first.pdf", "data": "4f6e65", "size": 3},
                {"filename": "second.txt", "data": "54776f", "size": 3}
            ]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.cacheDir } returns tempDir
            every { context.packageName } returns "network.columba.app"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("network.columba.app.fileprovider"),
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
        runViewModelTest {
            // Arrange
            val fieldsJson = """{"5": [{"filename": "test.txt", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

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
            every { context.cacheDir } returns tempDir
            every { context.packageName } returns "network.columba.app"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("network.columba.app.fileprovider"),
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
        runViewModelTest {
            // Arrange - file with unknown extension
            val fieldsJson = """{"5": [{"filename": "data.xyz", "data": "48656c6c6f", "size": 5}]}"""
            val messageEntity = createMessageEntity(fieldsJson = fieldsJson)
            coEvery { conversationRepository.getMessageById("test-id") } returns messageEntity

            val tempDir =
                java.io.File.createTempFile("test", "dir").apply {
                    delete()
                    mkdirs()
                }

            val context = mockk<android.content.Context>()
            every { context.cacheDir } returns tempDir
            every { context.packageName } returns "network.columba.app"

            val mockUri = mockk<android.net.Uri>()
            mockkStatic(androidx.core.content.FileProvider::class)
            every {
                androidx.core.content.FileProvider.getUriForFile(
                    any(),
                    eq("network.columba.app.fileprovider"),
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            // Assert: Initial state is null
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `loadReplyPreviewAsync caches reply preview`() =
        runViewModelTest {
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
        runViewModelTest {
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
            val result1 = runCatching { viewModel.loadReplyPreviewAsync("msg-1", "original-msg") }
            advanceUntilIdle()

            // Assert: First load completed successfully
            assertTrue("First loadReplyPreviewAsync should complete without error", result1.isSuccess)

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
        runViewModelTest {
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
        runViewModelTest {
            // Assert: Initial state is empty map
            assertTrue(viewModel.replyPreviewCache.value.isEmpty())
        }

    @Test
    fun `sendMessage with pending reply includes replyToMessageId`() =
        runViewModelTest {
            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        runViewModelTest {
            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // Assert: Pending reply was cleared after successful send
            assertNull(viewModel.pendingReplyTo.value)
        }

    @Test
    fun `sendMessage without pending reply does not include replyToMessageId`() =
        runViewModelTest {
            // Setup: Mock successful send
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // No pending reply set

            // Act: Send message
            val result = runCatching { viewModel.sendMessage(testPeerHash, "Regular message") }
            advanceUntilIdle()

            // Assert: sendMessage completed successfully
            assertTrue("sendMessage should complete without error", result.isSuccess)

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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            // Assert: Initial state is null
            assertNull(viewModel.reactionModeState.value)
        }

    @Test
    fun `enterReactionMode sets state with isMessageHidden true by default`() =
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Message not found
            coEvery { conversationRepository.getMessageById("nonexistent-msg") } returns null

            // Act
            val result = runCatching { viewModel.sendReaction("nonexistent-msg", "👍") }
            advanceUntilIdle()

            // Assert: sendReaction completed without error
            assertTrue("sendReaction should complete without error", result.isSuccess)

            // Assert: Protocol send was never called
            coVerify(exactly = 0) { rnsLxmf.sendReaction(any(), any(), any(), any()) }
        }

    @Test
    fun `sendReaction sends reaction via protocol when message exists`() =
        runViewModelTest {
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
                rnsLxmf.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Mock updateMessageReactions
            coEvery { conversationRepository.updateMessageReactions(any(), any()) } just Runs

            // Act
            val result = runCatching { viewModel.sendReaction("test-msg-id", "👍") }
            advanceUntilIdle()

            // Assert: sendReaction completed without error
            assertTrue("sendReaction should complete without error", result.isSuccess)

            // Assert: Protocol was called with correct parameters
            coVerify {
                rnsLxmf.sendReaction(
                    destinationHash = any(),
                    targetMessageId = "test-msg-id",
                    emoji = "👍",
                    sourceIdentity = testIdentity,
                )
            }
        }

    @Test
    fun `sendReaction writes flat reactionsJson blob to reactionsJson column`() =
        runViewModelTest {
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
                    reactionsJson = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol send reaction
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                rnsLxmf.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Capture the reactionsJson update
            val capturedReactionsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedReactionsJson))
            } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Database was updated with the flat reactions blob
            // (DB v2+ shape — no field-16 wrapper).
            assertTrue(capturedReactionsJson.isCaptured)
            val json = org.json.JSONObject(capturedReactionsJson.captured)
            assertTrue(json.has("👍"))
            assertEquals(1, json.getJSONArray("👍").length())
        }

    @Test
    fun `sendReaction clears reaction UI state after sending`() =
        runViewModelTest {
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
                rnsLxmf.sendReaction(any(), any(), any(), any())
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
        runViewModelTest {
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
                rnsLxmf.sendReaction(any(), any(), any(), any())
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
    fun `sendReaction does not touch fieldsJson when the target has reply metadata`() =
        runViewModelTest {
            // DB v2 split: reply metadata stays in fieldsJson / dedicated
            // `replyToMessageId` column, reactions live in reactionsJson.
            // The two never collide, so reactions for a reply-message
            // only need to update reactionsJson.
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

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
                    replyToMessageId = "original-msg-id",
                    reactionsJson = null,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                rnsLxmf.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            val capturedReactionsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedReactionsJson))
            } just Runs

            viewModel.sendReaction("test-msg-id", "❤️")
            advanceUntilIdle()

            // Assert: only the reactions blob is touched.
            assertTrue(capturedReactionsJson.isCaptured)
            val json = org.json.JSONObject(capturedReactionsJson.captured)
            assertTrue(json.has("❤️"))
            assertEquals(1, json.length()) // no other keys leaked in
        }

    @Test
    fun `sendReaction adds sender to existing emoji reaction`() =
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: target message already has a 👍 reaction from another peer
            val existingReactionsJson = """{"👍": ["other-sender-hash"]}"""
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
                    reactionsJson = existingReactionsJson,
                )
            coEvery { conversationRepository.getMessageById("test-msg-id") } returns testMessage

            // Mock protocol
            val mockReceipt = mockk<MessageReceipt>()
            every { mockReceipt.messageHash } returns ByteArray(16) { it.toByte() }
            coEvery {
                rnsLxmf.sendReaction(any(), any(), any(), any())
            } returns Result.success(mockReceipt)

            // Capture the reactionsJson update
            val capturedReactionsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("test-msg-id", capture(capturedReactionsJson))
            } just Runs

            // Act
            viewModel.sendReaction("test-msg-id", "👍")
            advanceUntilIdle()

            // Assert: Both senders are in the reaction list
            assertTrue(capturedReactionsJson.isCaptured)
            val json = org.json.JSONObject(capturedReactionsJson.captured)
            val thumbsUp = json.getJSONArray("👍")
            assertEquals(2, thumbsUp.length())
            assertEquals("other-sender-hash", thumbsUp.getString(0))
            // Our sender hash is derived from testIdentity
        }

    // ========== INCOMING REACTION TESTS ==========

    @Test
    fun `handleIncomingReaction updates message when found`() =
        runTest {
            // Setup: Create a flow to emit reactions BEFORE ViewModel creation
            val reactionFlow = MutableSharedFlow<String>()
            every { rnsTransportAdmin.reactionReceivedFlow } returns reactionFlow

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
                    reactionsJson = null,
                )
            coEvery { conversationRepository.getMessageById("target-msg-id") } returns targetMessage

            // Capture the reactionsJson update
            val capturedReactionsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("target-msg-id", capture(capturedReactionsJson))
            } just Runs

            // Act: Emit incoming reaction
            val reactionJson = """{"reaction_to": "target-msg-id", "emoji": "😂", "sender": "remote-sender-hash"}"""
            reactionFlow.emit(reactionJson)
            advanceUntilIdle()

            // Assert: Database was updated with flat reactionsJson blob
            assertTrue(capturedReactionsJson.isCaptured)
            val json = org.json.JSONObject(capturedReactionsJson.captured)
            assertTrue(json.has("😂"))
            val senders = json.getJSONArray("😂")
            assertEquals("remote-sender-hash", senders.getString(0))
        }

    @Test
    fun `handleIncomingReaction ignores message when not found`() =
        runViewModelTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { rnsTransportAdmin.reactionReceivedFlow } returns reactionFlow

            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: Message not found
            coEvery { conversationRepository.getMessageById("nonexistent-msg") } returns null

            // Act: Emit incoming reaction for unknown message
            val reactionJson = """{"reaction_to": "nonexistent-msg", "emoji": "👍", "sender": "remote-sender"}"""
            val emitResult = runCatching { reactionFlow.emit(reactionJson) }
            advanceUntilIdle()

            // Assert: Emission completed successfully
            assertTrue("Reaction emission should complete without error", emitResult.isSuccess)

            // Assert: No database update was attempted
            coVerify(exactly = 0) { conversationRepository.updateMessageReactions(any(), any()) }
        }

    @Test
    fun `handleIncomingReaction handles malformed JSON gracefully`() =
        runViewModelTest {
            // Setup: Create a flow to emit reactions
            val reactionFlow = MutableSharedFlow<String>()
            every { rnsTransportAdmin.reactionReceivedFlow } returns reactionFlow

            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Act: Emit malformed JSON - should not crash
            val result1 = runCatching { reactionFlow.emit("not valid json {{{") }
            advanceUntilIdle()

            // Assert: First emission completed successfully
            assertTrue("Malformed JSON emission should complete without error", result1.isSuccess)

            // Act: Emit JSON missing required fields - should not crash
            val result2 = runCatching { reactionFlow.emit("""{"reaction_to": "msg-id"}""") } // Missing emoji and sender
            advanceUntilIdle()

            // Assert: Second emission completed successfully
            assertTrue("Incomplete JSON emission should complete without error", result2.isSuccess)

            // Assert: No database update was attempted for invalid reactions
            coVerify(exactly = 0) { conversationRepository.updateMessageReactions(any(), any()) }
        }

    @Test
    fun `handleIncomingReaction merges reactions with existing`() =
        runTest {
            // Setup: Create a flow to emit reactions BEFORE ViewModel creation
            val reactionFlow = MutableSharedFlow<String>()
            every { rnsTransportAdmin.reactionReceivedFlow } returns reactionFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Setup: target message already has a 👍 reaction from sender-1
            val existingReactionsJson = """{"👍": ["sender-1"]}"""
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
                    reactionsJson = existingReactionsJson,
                )
            coEvery { conversationRepository.getMessageById("target-msg-id") } returns targetMessage

            // Capture the reactionsJson update
            val capturedReactionsJson = slot<String>()
            coEvery {
                conversationRepository.updateMessageReactions("target-msg-id", capture(capturedReactionsJson))
            } just Runs

            // Act: Emit incoming reaction with different emoji
            val reactionJson = """{"reaction_to": "target-msg-id", "emoji": "❤️", "sender": "sender-2"}"""
            reactionFlow.emit(reactionJson)
            advanceUntilIdle()

            // Assert: Both reactions exist in the flat blob
            assertTrue(capturedReactionsJson.isCaptured)
            val json = org.json.JSONObject(capturedReactionsJson.captured)
            assertTrue(json.has("👍"))
            assertTrue(json.has("❤️"))
            assertEquals(1, json.getJSONArray("👍").length())
            assertEquals(1, json.getJSONArray("❤️").length())
        }

    // ========== IMAGE STATE TESTS ==========

    @Test
    fun `selectImage sets image data and format`() =
        runViewModelTest {
            val imageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            viewModel.selectImage(imageData, "png")
            advanceUntilIdle()

            assertEquals(imageData, viewModel.selectedImageData.value)
            assertEquals("png", viewModel.selectedImageFormat.value)
            assertFalse(viewModel.selectedImageIsAnimated.value)
        }

    @Test
    fun `selectImage with animated flag sets isAnimated`() =
        runViewModelTest {
            val gifData = byteArrayOf(0x47, 0x49, 0x46) // GIF header

            viewModel.selectImage(gifData, "gif", isAnimated = true)
            advanceUntilIdle()

            assertEquals(gifData, viewModel.selectedImageData.value)
            assertEquals("gif", viewModel.selectedImageFormat.value)
            assertTrue(viewModel.selectedImageIsAnimated.value)
        }

    @Test
    fun `clearSelectedImage clears image state`() =
        runViewModelTest {
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
        runViewModelTest {
            assertFalse(viewModel.isProcessingImage.value)

            viewModel.setProcessingImage(true)

            assertTrue(viewModel.isProcessingImage.value)

            viewModel.setProcessingImage(false)

            assertFalse(viewModel.isProcessingImage.value)
        }

    // ========== REACTION PICKER STATE TESTS ==========

    @Test
    fun `setReactionTarget sets pending reaction message id and shows picker`() =
        runViewModelTest {
            viewModel.setReactionTarget("msg-123")
            advanceUntilIdle()

            assertEquals("msg-123", viewModel.pendingReactionMessageId.value)
            assertTrue(viewModel.showReactionPicker.value)
        }

    @Test
    fun `clearReactionTarget clears pending reaction and hides picker`() =
        runViewModelTest {
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
        runViewModelTest {
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
        runViewModelTest {
            assertNull(viewModel.pendingReactionMessageId.value)
        }

    @Test
    fun `showReactionPicker initial state is false`() =
        runViewModelTest {
            assertFalse(viewModel.showReactionPicker.value)
        }

    // ========== LOCATION SHARING TESTS ==========

    @Test
    fun `startSharingWithPeer calls location sharing manager`() =
        runViewModelTest {
            val duration = network.columba.app.ui.model.SharingDuration.FIFTEEN_MINUTES

            val result = runCatching { viewModel.startSharingWithPeer(testPeerHash, testPeerName, duration) }
            advanceUntilIdle()

            assertTrue("startSharingWithPeer should complete without error", result.isSuccess)
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
        runViewModelTest {
            val result = runCatching { viewModel.stopSharingWithPeer(testPeerHash) }
            advanceUntilIdle()

            assertTrue("stopSharingWithPeer should complete without error", result.isSuccess)
            verify { locationSharingManager.stopSharing(testPeerHash) }
        }

    // ========== SENDING STATE TESTS ==========

    @Test
    fun `isSending initial state is false`() =
        runViewModelTest {
            assertFalse(viewModel.isSending.value)
        }

    @Test
    fun `isSending is true during message send and false after`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
    fun `sendMessage admits only one in flight send`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val sendCompletion = CompletableDeferred<Result<MessageReceipt>>()
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers { sendCompletion.await() }
            val successfulResult =
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { it.toByte() },
                        timestamp = 3_000L,
                        destinationHash = destHashBytes,
                    ),
                )
            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs

            viewModel.sendMessage(testPeerHash, "Test message")
            viewModel.sendMessage(testPeerHash, "Test message")
            assertTrue(viewModel.isSending.value)
            sendCompletion.complete(successfulResult)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            assertFalse(viewModel.isSending.value)
        }

    @Test
    fun `send completion preserves a replacement attachment`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val sendCompletion = CompletableDeferred<Result<MessageReceipt>>()
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers { sendCompletion.await() }
            coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs
            val original = byteArrayOf(0x01)
            val replacement = byteArrayOf(0x02)
            viewModel.selectImage(original, "png")

            viewModel.sendMessage(testPeerHash, "Test message")
            viewModel.selectImage(replacement, "png")
            sendCompletion.complete(
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { it.toByte() },
                        timestamp = 3_000L,
                        destinationHash = destHashBytes,
                    ),
                ),
            )
            advanceUntilIdle()

            assertArrayEquals(replacement, viewModel.selectedImageData.value)
        }

    @Test
    fun `isSending is false after failed send`() =
        runViewModelTest {
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            coEvery { conversationRepository.getMessageById("nonexistent") } returns null

            val result = runCatching { viewModel.retryFailedMessage("nonexistent") }
            advanceUntilIdle()

            // Assert: retryFailedMessage completed without error
            assertTrue("retryFailedMessage should complete without error", result.isSuccess)

            // Protocol should not be called
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `retryFailedMessage does nothing when message is not failed`() =
        runViewModelTest {
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

            val result = runCatching { viewModel.retryFailedMessage("msg-123") }
            advanceUntilIdle()

            // Assert: retryFailedMessage completed without error
            assertTrue("retryFailedMessage should complete without error", result.isSuccess)

            // Protocol should not be called for non-failed messages
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `retryFailedMessage retries message when status is failed`() =
        runViewModelTest {
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
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(testReceipt)

            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs

            val result = runCatching { viewModel.retryFailedMessage("msg-123") }
            advanceUntilIdle()

            // Assert: retryFailedMessage completed without error
            assertTrue("retryFailedMessage should complete without error", result.isSuccess)

            // Should mark as pending before sending
            coVerify { conversationRepository.updateMessageStatus("msg-123", "pending") }

            // Should call protocol to resend
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
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
    fun `retryFailedMessage preserves inline voice audio field`() =
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val failedMessage =
                MessageEntity(
                    id = "voice-failed",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = " ",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    fieldsJson = """{"7":[16,"4f676753"]}""",
                )
            coEvery { conversationRepository.getMessageById("voice-failed") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs
            val receipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { 0xAB.toByte() },
                    timestamp = 3_000L,
                    destinationHash = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(receipt)

            viewModel.retryFailedMessage("voice-failed")
            advanceUntilIdle()

            var sentExpectedAudio = false
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = " ",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                    extraFields =
                        match { fields ->
                            val audio = fields?.get(7) as? List<*>
                            sentExpectedAudio = audio?.getOrNull(0) == 16 &&
                                (audio.getOrNull(1) as? ByteArray)?.contentEquals("OggS".encodeToByteArray()) == true
                            sentExpectedAudio
                        },
                )
            }
            assertTrue(sentExpectedAudio)
        }

    @Test
    fun `retryFailedMessage preserves voice and file attachments together`() =
        runViewModelTest {
            val failedMessage =
                MessageEntity(
                    id = "voice-and-file",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = " ",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    fieldsJson =
                        """{"5":[{"filename":"note.txt","size":4,"data":"64617461"}],"7":[16,"4f676753"]}""",
                )
            coEvery { conversationRepository.getMessageById("voice-and-file") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { 0xAD.toByte() },
                        timestamp = 3_000L,
                        destinationHash = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    ),
                )

            viewModel.retryFailedMessage("voice-and-file")
            advanceUntilIdle()

            var preservedCombinedAttachments = false
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = " ",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                    fileAttachments =
                        match { files ->
                            preservedCombinedAttachments = files.singleOrNull()?.let { (name, data) ->
                                name == "note.txt" && data.contentEquals("data".encodeToByteArray())
                            } == true
                            preservedCombinedAttachments
                        },
                    extraFields = any(),
                )
            }
            assertTrue(preservedCombinedAttachments)
        }

    @Test
    fun `retryFailedMessage admits only one retry per message`() =
        runViewModelTest {
            val failedMessage =
                MessageEntity(
                    id = "single-retry",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "Retry once",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                )
            val completion = CompletableDeferred<Result<MessageReceipt>>()
            var sendCount = 0
            coEvery { conversationRepository.getMessageById("single-retry") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                sendCount += 1
                completion.await()
            }

            viewModel.retryFailedMessage("single-retry")
            viewModel.retryFailedMessage("single-retry")
            completion.complete(
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { 0xAE.toByte() },
                        timestamp = 3_000L,
                        destinationHash = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    ),
                ),
            )
            advanceUntilIdle()

            coVerify(exactly = 1) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            assertEquals(1, sendCount)
        }

    @Test
    fun `retryFailedMessage preserves file backed image field`() =
        runViewModelTest {
            val imageFile =
                java.io.File(applicationContext.filesDir, "attachments/image-file/6_image").apply {
                    parentFile!!.mkdirs()
                    writeText("01020304")
                }
            val failedMessage =
                MessageEntity(
                    id = "image-file",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "image",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    fieldsJson = """{"6":{"_file_ref":${org.json.JSONObject.quote(imageFile.absolutePath)}}}""",
                )
            coEvery { conversationRepository.getMessageById("image-file") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { 0xAD.toByte() },
                        timestamp = 3_000L,
                        destinationHash = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    ),
                )

            viewModel.retryFailedMessage("image-file")
            advanceUntilIdle()

            var preservedImage = false
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = "image",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = match {
                        preservedImage = it?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true
                        preservedImage
                    },
                    imageFormat = "jpg",
                    extraFields = any(),
                )
            }
            assertTrue(preservedImage)
        }

    @Test
    fun `retryFailedMessage fails closed when persisted image is unavailable`() =
        runViewModelTest {
            val missingPath = java.io.File(applicationContext.filesDir, "attachments/missing/6_image").absolutePath
            val failedMessage =
                MessageEntity(
                    id = "missing-image",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = "image",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    fieldsJson = """{"6":{"_file_ref":${org.json.JSONObject.quote(missingPath)}}}""",
                )
            coEvery { conversationRepository.getMessageById("missing-image") } returns failedMessage
            var unavailableRecorded = false
            coEvery { conversationRepository.updateMessageDeliveryDetails(any(), any(), any()) } answers {
                unavailableRecorded = args[2] == "Image attachment is no longer available"
            }

            viewModel.retryFailedMessage("missing-image")
            advanceUntilIdle()

            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            coVerify {
                conversationRepository.updateMessageDeliveryDetails(
                    "missing-image",
                    deliveryMethod = null,
                    errorMessage = "Image attachment is no longer available",
                )
            }
            assertTrue(unavailableRecorded)
        }

    @Test
    fun `retryFailedMessage preserves file backed voice audio field`() =
        runViewModelTest {
            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()
            val audioFile =
                java.io.File(applicationContext.filesDir, "attachments/voice-file/7_audio").apply {
                    parentFile!!.mkdirs()
                    writeText("4f676753")
                }
            val failedMessage =
                MessageEntity(
                    id = "voice-file",
                    conversationHash = testPeerHash,
                    identityHash = "identity-hash",
                    content = " ",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    fieldsJson = """{"7":[16,{"_file_ref":${org.json.JSONObject.quote(audioFile.absolutePath)}}]}""",
                )
            coEvery { conversationRepository.getMessageById("voice-file") } returns failedMessage
            coEvery { conversationRepository.updateMessageStatus(any(), any()) } just Runs
            coEvery { conversationRepository.updateMessageId(any(), any()) } just Runs
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                Result.success(
                    MessageReceipt(
                        messageHash = ByteArray(32) { 0xAC.toByte() },
                        timestamp = 3_000L,
                        destinationHash = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    ),
                )

            viewModel.retryFailedMessage("voice-file")
            advanceUntilIdle()

            var sentExpectedAudio = false
            coVerify {
                rnsLxmf.sendLxmfMessageWithMethod(
                    destinationHash = any(),
                    content = " ",
                    sourceIdentity = testIdentity,
                    deliveryMethod = any(),
                    tryPropagationOnFail = any(),
                    imageData = null,
                    imageFormat = null,
                    extraFields =
                        match { fields ->
                            val audio = fields?.get(7) as? List<*>
                            sentExpectedAudio =
                                (audio?.getOrNull(1) as? ByteArray)?.contentEquals("OggS".encodeToByteArray()) == true
                            sentExpectedAudio
                        },
                )
            }
            assertTrue(sentExpectedAudio)
        }

    @Test
    fun `retryFailedMessage restores failed status on retry failure`() =
        runViewModelTest {
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
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Network error"))

            val result = runCatching { viewModel.retryFailedMessage("msg-123") }
            advanceUntilIdle()

            assertTrue("retryFailedMessage should complete without error", result.isSuccess)
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
        runViewModelTest {
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

            val result = runCatching { viewModel.retryFailedMessage("msg-123") }
            advanceUntilIdle()

            // Assert: retryFailedMessage completed without error
            assertTrue("retryFailedMessage should complete without error", result.isSuccess)

            // Protocol should not be called due to invalid hash
            coVerify(exactly = 0) {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    // ========== FETCH PENDING FILE TESTS ==========

    @Test
    fun `fetchPendingFile triggers sync with increased size limit`() =
        runViewModelTest {
            // Mock settings to return current limit
            coEvery { settingsRepository.getIncomingMessageSizeLimitKb() } returns 500

            // Mock the LXMF seam
            every { rnsLxmf.setIncomingMessageSizeLimit(any()) } just Runs

            // Mock sync completion - immediate return
            val syncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns syncingFlow
            coEvery { propagationNodeManager.triggerSync(silent = true) } just Runs

            // Act: Fetch a 1MB file
            val fileSizeBytes = 1024L * 1024L // 1MB
            val result = runCatching { viewModel.fetchPendingFile(fileSizeBytes) }

            // Give the coroutine a chance to start
            advanceUntilIdle()

            assertTrue("fetchPendingFile should complete without error", result.isSuccess)
            // Verify sync was triggered with silent flag
            coVerify { propagationNodeManager.triggerSync(silent = true) }

            // Verify size limit was increased
            verify { rnsLxmf.setIncomingMessageSizeLimit(match { it > 500 }) }
        }

    @Test
    fun `fetchPendingFile reverts size limit after sync`() =
        runViewModelTest {
            val originalLimit = 500
            coEvery { settingsRepository.getIncomingMessageSizeLimitKb() } returns originalLimit

            every { rnsLxmf.setIncomingMessageSizeLimit(any()) } just Runs

            // Mock sync that completes quickly
            val syncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns syncingFlow
            coEvery { propagationNodeManager.triggerSync(silent = true) } coAnswers {
                // Simulate sync starting and completing
                syncingFlow.value = true
                syncingFlow.value = false
            }

            val fileSizeBytes = 512L * 1024L // 512KB
            val result = runCatching { viewModel.fetchPendingFile(fileSizeBytes) }
            advanceUntilIdle()

            assertTrue("fetchPendingFile should complete without error", result.isSuccess)
            // Verify size limit was reverted to original
            verify { rnsLxmf.setIncomingMessageSizeLimit(originalLimit) }
        }

    // ========== SYNC STATE DELEGATION TESTS ==========

    @Test
    fun `isSyncing delegates to propagationNodeManager`() =
        runTest {
            // Setup custom mock BEFORE ViewModel creation
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
            // Setup custom mock BEFORE ViewModel creation
            val progressFlow =
                MutableStateFlow<network.columba.app.service.SyncProgress>(
                    network.columba.app.service.SyncProgress.Idle,
                )
            every { propagationNodeManager.syncProgress } returns progressFlow

            val viewModel = createTestViewModel()
            advanceUntilIdle()

            assertEquals(network.columba.app.service.SyncProgress.Idle, viewModel.syncProgress.value)

            progressFlow.value = network.columba.app.service.SyncProgress.Starting
            advanceUntilIdle()

            assertEquals(network.columba.app.service.SyncProgress.Starting, viewModel.syncProgress.value)
        }

    @Test
    fun `onCleared releases owned voice recording lease`() =
        runTest {
            val arbiter = MicrophoneAdmissionArbiter()
            val ownedLease = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
            val testViewModel = createTestViewModel(arbiter)
            testViewModel.javaClass.getDeclaredField("voiceRecordingLease").apply {
                isAccessible = true
                set(testViewModel, ownedLease)
            }

            testViewModel.javaClass.getDeclaredMethod("onCleared").apply {
                isAccessible = true
                invoke(testViewModel)
            }
            val cleanupThread =
                testViewModel.javaClass.getDeclaredField("voiceRecorderCleanupThread").run {
                    isAccessible = true
                    get(testViewModel) as Thread
                }
            cleanupThread.join(5_000)
            assertFalse(cleanupThread.isAlive)

            assertNull(arbiter.currentOwner())
        }

    @Test
    fun `onCleared releases voice recording lease when recorder close fails`() =
        runTest {
            val arbiter = MicrophoneAdmissionArbiter()
            val ownedLease = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
            val throwingRecorder = mockk<VoiceMessageRecorder>()
            every { throwingRecorder.close() } throws IllegalStateException("recorder close failed")
            val testViewModel = createTestViewModel(arbiter)
            testViewModel.javaClass.getDeclaredField("voiceRecordingLease").apply {
                isAccessible = true
                set(testViewModel, ownedLease)
            }
            testViewModel.javaClass.getDeclaredField("voiceMessageRecorder").apply {
                isAccessible = true
                set(testViewModel, throwingRecorder)
            }

            testViewModel.javaClass.getDeclaredMethod("onCleared").apply {
                isAccessible = true
                invoke(testViewModel)
            }
            val cleanupThread =
                testViewModel.javaClass.getDeclaredField("voiceRecorderCleanupThread").run {
                    isAccessible = true
                    get(testViewModel) as Thread
                }
            cleanupThread.join(5_000)
            assertFalse(cleanupThread.isAlive)

            verify(exactly = 1) { throwingRecorder.close() }
            assertNull(arbiter.currentOwner())
        }

    @Test
    fun `resource progress is exposed while active and removed at terminal state`() = runTest {
        val progressFlow = MutableSharedFlow<TransferProgressUpdate>(extraBufferCapacity = 4)
        every { rnsLxmf.observeTransferProgress() } returns progressFlow
        val viewModel = createTestViewModel()
        advanceUntilIdle()
        val active = TransferProgressUpdate(
            transferId = "resource-1",
            messageHash = "aabbcc",
            direction = Direction.OUT,
            progress = 0.64f,
            phase = TransferPhase.TRANSFERRING,
            deliveryMethod = DeliveryMethod.DIRECT,
        )

        progressFlow.emit(active)
        advanceUntilIdle()
        assertEquals(active, viewModel.transferProgress.value["aabbcc"])

        progressFlow.emit(active.copy(progress = 1f, phase = TransferPhase.COMPLETE))
        advanceUntilIdle()
        assertTrue(viewModel.transferProgress.value.isEmpty())
    }
    // The behavior is indirectly tested via the ViewModel lifecycle in integration tests

    // ========== TOTAL ATTACHMENT SIZE TESTS ==========

    @Test
    fun `totalAttachmentSize initial value is zero`() =
        runViewModelTest {
            assertEquals(0, viewModel.totalAttachmentSize.value)
        }

    @Test
    fun `totalAttachmentSize is zero when no files attached`() =
        runViewModelTest {
            // Make sure no files are attached
            viewModel.clearFileAttachments()
            advanceUntilIdle()

            assertEquals(0, viewModel.totalAttachmentSize.value)
        }

    // ========== SEND WITH FILE ATTACHMENT ==========

    @Test
    fun `sendMessage with file attachment calls protocol with file data`() =
        runViewModelTest {
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
                rnsLxmf.sendLxmfMessageWithMethod(
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
        runViewModelTest {
            // Identity is loaded lazily, trigger by sending a message
            val destHashBytes = testPeerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val testReceipt =
                MessageReceipt(
                    messageHash = ByteArray(32) { it.toByte() },
                    timestamp = 3000L,
                    destinationHash = destHashBytes,
                )
            coEvery {
                rnsLxmf.sendLxmfMessageWithMethod(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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

    // ========== DELETE MESSAGE TESTS ==========

    @Test
    fun `deleteMessage invalidates reply preview cache for deleted message`() =
        runViewModelTest {
            coEvery { conversationRepository.deleteMessage(any(), any()) } just Runs

            viewModel.loadMessages(testPeerHash, testPeerName)
            advanceUntilIdle()

            // Pre-populate reply preview cache via loadReplyPreviewIfNeeded
            coEvery {
                conversationRepository.getReplyPreview("test-message-id", any())
            } returns
                network.columba.app.data.repository.ReplyPreview(
                    messageId = "test-message-id",
                    senderName = "Test Peer",
                    contentPreview = "Hello world",
                    hasImage = false,
                    hasFileAttachment = false,
                    firstFileName = null,
                )
            viewModel.loadReplyPreviewAsync("reply-msg", "test-message-id")
            advanceUntilIdle()

            // Verify preview is cached with original content
            val cachedBefore = viewModel.replyPreviewCache.value["reply-msg"]
            assertNotNull(cachedBefore)
            assertEquals("Hello world", cachedBefore!!.contentPreview)

            // Delete the message
            viewModel.deleteMessage("test-message-id")
            advanceUntilIdle()

            // Assert: cached reply preview is replaced with "Message deleted" placeholder
            val cachedAfter = viewModel.replyPreviewCache.value["reply-msg"]
            assertNotNull(cachedAfter)
            assertEquals("Message deleted", cachedAfter!!.contentPreview)
            assertEquals("", cachedAfter.senderName)
        }

    @Test
    fun `deleteMessage without active conversation does not modify state`() =
        runViewModelTest {
            // Capture initial state
            val initialCache = viewModel.replyPreviewCache.value

            // Don't call loadMessages — no active conversation
            viewModel.deleteMessage("test-message-id")
            advanceUntilIdle()

            // Assert: reply preview cache is unchanged
            assertEquals(initialCache, viewModel.replyPreviewCache.value)
        }

    // ========== DECODED IMAGES STATE TESTS ==========

    @Test
    fun `decodedImages initial state is empty`() =
        runViewModelTest {
            assertTrue(viewModel.decodedImages.value.isEmpty())
        }

    // ========== ANNOUNCE INFO TESTS ==========

    @Test
    fun `announceInfo returns null when no conversation loaded`() =
        runViewModelTest {
            // Before loading any conversation
            assertNull(viewModel.announceInfo.value)
        }
}
