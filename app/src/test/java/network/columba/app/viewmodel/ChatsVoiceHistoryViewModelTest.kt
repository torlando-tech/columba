@file:Suppress("IgnoredReturnValue") // awaitItem() calls consume flow emissions, result intentionally unused

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.BlockedPeerRepository
import network.columba.app.data.repository.CallHistoryRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.ReceivedLocationRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.PropagationNodeManager
import network.columba.app.service.SyncProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsVoiceHistoryViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var callHistoryRepository: CallHistoryRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var receivedLocationRepository: ReceivedLocationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var blockedPeerRepository: BlockedPeerRepository
    private lateinit var reticulumProtocol: RnsCore
    private lateinit var rnsTelephony: RnsTelephony
    private lateinit var liveCallState: MutableStateFlow<CallState>
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var identityResolutionManager: IdentityResolutionManager
    private lateinit var viewModel: ChatsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        conversationRepository = mockk()
        callHistoryRepository = mockk()
        contactRepository = mockk()
        receivedLocationRepository = mockk()
        announceRepository = mockk()
        blockedPeerRepository = mockk()
        reticulumProtocol = mockk()
        rnsTelephony = mockk()
        propagationNodeManager = mockk()
        identityResolutionManager = mockk()
        coEvery { identityResolutionManager.requestPathForContact(any()) } just Runs
        every { conversationRepository.getConversations() } returns flowOf(emptyList())
        every { conversationRepository.observeDrafts() } returns flowOf(emptyMap())
        every { callHistoryRepository.observeActiveIdentityHash() } returns flowOf("local")
        every { callHistoryRepository.observeHistory(any(), any()) } returns flowOf(emptyList())
        liveCallState = MutableStateFlow(CallState.Idle)
        every { rnsTelephony.callState } returns liveCallState
        coEvery { rnsTelephony.getCallState() } returns Result.failure(IllegalStateException("No active call"))
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.manualSyncResult } returns MutableSharedFlow()
        every { propagationNodeManager.syncProgress } returns MutableStateFlow(SyncProgress.Idle)
        coEvery { reticulumProtocol.isTransportEnabled() } returns false
        viewModel = ChatsViewModel(
            conversationRepository,
            callHistoryRepository,
            contactRepository,
            announceRepository,
            blockedPeerRepository,
            reticulumProtocol,
            rnsTelephony,
            propagationNodeManager,
            receivedLocationRepository,
            identityResolutionManager,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `voice search exposes identity-scoped history records`() =
        runTest {
            val record =
                CallHistoryRecord(
                    callAttemptId = "attempt-voice",
                    localIdentityHash = "local",
                    remoteIdentityHash = "remote",
                    direction = "OUTGOING",
                    peerDisplayNameSnapshot = null,
                    codecProfileCode = 2,
                    attemptedAt = 1L,
                    ringingAt = null,
                    connectedAt = null,
                    endedAt = 2L,
                    outcome = "NOT_CONNECTED",
                    inferredEnding = false,
                    failureReason = null,
                    displayName = "Alice",
                    currentDestinationHash = null,
                )
            every { callHistoryRepository.observeHistory("local", "alice") } returns flowOf(listOf(record))
            viewModel.voiceSearchQuery.value = "alice"

            viewModel.voiceHistoryState.test {
                awaitItem()
                advanceUntilIdle()
                val state = awaitItem()
                assertEquals("attempt-voice", state.records.single().callAttemptId)
                assertEquals(false, state.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `voice history reacts when exact live call ownership changes without a Room emission`() =
        runTest {
            val record = voiceRecord(endedAt = null)
            every { callHistoryRepository.observeHistory("local", "") } returns flowOf(listOf(record))

            viewModel.voiceHistoryState.test {
                awaitItem()
                val recovering = awaitItem()
                assertNull(recovering.activeCallAttemptId)

                coEvery { rnsTelephony.getCallState() } returns
                    Result.success(
                        VoiceCallState(
                            status = "active",
                            isActive = true,
                            isMuted = false,
                            remoteIdentity = record.remoteIdentityHash,
                            profile = null,
                            callAttemptId = record.callAttemptId,
                        ),
                    )
                liveCallState.value = CallState.Active(record.remoteIdentityHash)

                assertEquals(record.callAttemptId, awaitItem().activeCallAttemptId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `voice history refreshes exact ownership after binder recovery without a state transition`() =
        runTest {
            val record = voiceRecord(endedAt = null)
            var binderAvailable = false
            every { callHistoryRepository.observeHistory("local", "") } returns flowOf(listOf(record))
            liveCallState.value = CallState.Active(record.remoteIdentityHash)
            coEvery { rnsTelephony.getCallState() } answers {
                if (!binderAvailable) {
                    Result.failure(IllegalStateException("binder unavailable"))
                } else {
                    Result.success(
                        VoiceCallState(
                            status = "active",
                            isActive = true,
                            isMuted = false,
                            remoteIdentity = record.remoteIdentityHash,
                            profile = null,
                            callAttemptId = record.callAttemptId,
                        ),
                    )
                }
            }

            viewModel.voiceHistoryState.test {
                awaitItem()
                assertNull(awaitItem().activeCallAttemptId)
                binderAvailable = true
                advanceTimeBy(500L)
                assertEquals(record.callAttemptId, awaitItem().activeCallAttemptId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `open history routes exact active attempt back to call`() =
        runTest {
            val record = voiceRecord(endedAt = null)
            coEvery { rnsTelephony.getCallState() } returns
                Result.success(
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = false,
                        remoteIdentity = record.remoteIdentityHash,
                        profile = null,
                        callAttemptId = record.callAttemptId,
                    ),
                )

            viewModel.callHistoryNavigation.test {
                viewModel.openCallHistory(record)
                advanceUntilIdle()
                val destination = awaitItem() as CallHistoryNavigation.ActiveCall
                assertEquals(record.callAttemptId, destination.callAttemptId)
                assertEquals(record.localIdentityHash, destination.localIdentityHash)
                assertEquals(record.remoteIdentityHash, destination.remoteIdentityHash)
                assertEquals(2, destination.profileCode)
            }
        }

    @Test
    fun `voice selection and query restore from saved state`() =
        runTest {
            val handle =
                SavedStateHandle(
                    mapOf(
                        "voice_history_query" to "alice",
                        "chats_selected_segment" to ChatsSegment.VOICE.name,
                    ),
                )
            val restored =
                ChatsViewModel(
                    conversationRepository,
                    callHistoryRepository,
                    contactRepository,
                    announceRepository,
                    blockedPeerRepository,
                    reticulumProtocol,
                    rnsTelephony,
                    propagationNodeManager,
                    receivedLocationRepository,
                    identityResolutionManager,
                    handle,
                )

            assertEquals("alice", restored.voiceSearchQuery.value)
            assertEquals(ChatsSegment.VOICE, restored.selectedSegment.value)
            restored.voiceSearchQuery.value = "bob"
            restored.selectedSegment.value = ChatsSegment.TEXT
            advanceUntilIdle()
            assertEquals("bob", handle.get<String>("voice_history_query"))
            assertEquals(ChatsSegment.TEXT.name, handle.get<String>("chats_selected_segment"))
        }

    @Test
    fun `open history fails closed to details when live attempt id differs`() =
        runTest {
            val record = voiceRecord(endedAt = null)
            coEvery { rnsTelephony.getCallState() } returns
                Result.success(
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = false,
                        remoteIdentity = record.remoteIdentityHash,
                        profile = null,
                        callAttemptId = "different-attempt",
                    ),
                )

            viewModel.callHistoryNavigation.test {
                viewModel.openCallHistory(record)
                advanceUntilIdle()
                assertEquals(CallHistoryNavigation.Details(record.callAttemptId), awaitItem())
            }
        }

    @Test
    fun `voice search recovers after a repository flow failure`() =
        runTest {
            every { callHistoryRepository.observeHistory("local", "") } returns flow { error("database unavailable") }
            every { callHistoryRepository.observeHistory("local", "retry") } returns flowOf(emptyList())

            viewModel.voiceHistoryState.test {
                awaitItem()
                advanceUntilIdle()
                assertEquals(true, awaitItem().hasError)

                viewModel.voiceSearchQuery.value = "retry"
                advanceUntilIdle()
                val loading = awaitItem()
                assertEquals(true, loading.isLoading)
                val recovered = awaitItem()
                assertEquals(false, recovered.hasError)
                assertEquals(false, recovered.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `voice history retry preserves cached rows when refresh fails`() =
        runTest {
            val record = voiceRecord(endedAt = 2_000L)
            every { callHistoryRepository.observeHistory("local", "") } returnsMany
                listOf(
                    flowOf(listOf(record)),
                    flow { error("database unavailable") },
                )

            viewModel.voiceHistoryState.test {
                assertEquals(true, awaitItem().isLoading)
                assertEquals(listOf(record), awaitItem().records)

                viewModel.retryVoiceHistory()
                advanceUntilIdle()
                assertEquals(listOf(record), awaitItem().records)
                val failedRefresh = awaitItem()
                assertEquals(listOf(record), failedRefresh.records)
                assertEquals(true, failedRefresh.hasError)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun voiceRecord(endedAt: Long?): CallHistoryRecord =
        CallHistoryRecord(
            callAttemptId = "attempt-active",
            localIdentityHash = "local",
            remoteIdentityHash = "0123456789abcdef0123456789abcdef",
            direction = "OUTGOING",
            peerDisplayNameSnapshot = "Alice",
            codecProfileCode = 2,
            attemptedAt = 1L,
            ringingAt = 2L,
            connectedAt = 3L,
            endedAt = endedAt,
            outcome = endedAt?.let { "CONNECTED_ENDED" },
            inferredEnding = false,
            failureReason = null,
            displayName = "Alice",
            currentDestinationHash = "destination",
        )
}
