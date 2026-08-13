package network.columba.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.data.repository.BlockedPeerRepository
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.CallHistoryRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.VoiceCallState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: CallHistoryRepository
    private lateinit var blockedPeerRepository: BlockedPeerRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsTelephony: RnsTelephony
    private lateinit var callState: MutableStateFlow<CallState>

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        blockedPeerRepository = mockk()
        announceRepository = mockk()
        rnsCore = mockk()
        rnsTelephony = mockk()
        callState = MutableStateFlow(CallState.Idle)
        every { rnsTelephony.callState } returns callState
        every { repository.observeActiveIdentityHash() } returns flowOf("local")
        coEvery { rnsTelephony.getCallState() } returns
            Result.success(
                VoiceCallState(
                    status = "idle",
                    isActive = false,
                    isMuted = false,
                    remoteIdentity = null,
                    profile = null,
                ),
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unblock removes prior blackhole runtime state`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(true)
            coEvery { blockedPeerRepository.getIdentityBlock("remote", "local") } returns
                BlockedPeerEntity(
                    peerHash = "destination",
                    identityHash = "local",
                    peerIdentityHash = "remote",
                    displayName = "Alice",
                    blockedTimestamp = 1L,
                    isBlackholeEnabled = true,
                )
            coEvery { blockedPeerRepository.getApprovedDestinationHashes("remote") } returns listOf("destination")
            coJustRun { blockedPeerRepository.unblockIdentity("remote", "local") }
            coEvery { rnsCore.unblockDestination("destination") } returns Result.success(Unit)
            coEvery { rnsCore.unblockIdentity("remote") } returns Result.success(Unit)
            coEvery { rnsCore.unblackholeIdentity("remote") } returns Result.success(Unit)
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var resolved = awaitItem()
                while (resolved.blockState != BlockLookupState.BLOCKED) resolved = awaitItem()
                assertTrue(resolved.blockState == BlockLookupState.BLOCKED)
                viewModel.toggleBlocked()
                advanceUntilIdle()
                coVerify(exactly = 1) { blockedPeerRepository.unblockIdentity("remote", "local") }
                coVerify(exactly = 1) { rnsCore.unblockDestination("destination") }
                coVerify(exactly = 1) { rnsCore.unblackholeIdentity("remote") }
                coVerifyOrder {
                    blockedPeerRepository.unblockIdentity("remote", "local")
                    rnsCore.unblockDestination("destination")
                    rnsCore.unblockIdentity("remote")
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `unblock releases destination learned during durable removal`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(true)
            coEvery { blockedPeerRepository.getIdentityBlock("remote", "local") } returns
                BlockedPeerEntity(
                    peerHash = "destination",
                    identityHash = "local",
                    peerIdentityHash = "remote",
                    displayName = "Alice",
                    blockedTimestamp = 1L,
                    isBlackholeEnabled = false,
                )
            coEvery { blockedPeerRepository.getApprovedDestinationHashes("remote") } returnsMany
                listOf(listOf("destination"), listOf("destination", "rotated"))
            coJustRun { blockedPeerRepository.unblockIdentity("remote", "local") }
            coEvery { rnsCore.unblockDestination(any()) } returns Result.success(Unit)
            coEvery { rnsCore.unblockIdentity("remote") } returns Result.success(Unit)
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var resolved = awaitItem()
                while (resolved.blockState != BlockLookupState.BLOCKED) resolved = awaitItem()
                viewModel.toggleBlocked()
                advanceUntilIdle()

                assertFalse(viewModel.state.value.actionError)
                coVerify(exactly = 1) { rnsCore.unblockDestination("destination") }
                coVerify(exactly = 1) { rnsCore.unblockDestination("rotated") }
                coVerifyOrder {
                    blockedPeerRepository.unblockIdentity("remote", "local")
                    blockedPeerRepository.getApprovedDestinationHashes("remote")
                    rnsCore.unblockDestination("destination")
                    rnsCore.unblockDestination("rotated")
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `projection failure exits loading and clears actions`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flow { error("database unavailable") }
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                val error = awaitItem()
                assertTrue(error.hasError)
                assertTrue(!error.isLoading)
                assertTrue(error.record == null)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `active call collision disables call again`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { rnsTelephony.getCallState() } returns
                Result.success(
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = false,
                        remoteIdentity = "other",
                        profile = null,
                        callAttemptId = "other-attempt",
                    ),
                )
            callState.value = CallState.Active("other")

            viewModel().state.test {
                awaitItem()
                advanceUntilIdle()
                val ready = awaitItem()
                assertFalse(ready.canStartCall)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `exact live ownership reacts while details remains open`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record().copy(endedAt = null, outcome = null))
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { rnsTelephony.getCallState() } returns
                Result.success(
                    VoiceCallState(
                        status = "active",
                        isActive = true,
                        isMuted = false,
                        remoteIdentity = "remote",
                        profile = null,
                        callAttemptId = "attempt",
                    ),
                )
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var recovering = awaitItem()
                while (!recovering.recoveryPending) recovering = awaitItem()

                callState.value = CallState.Active("remote")
                advanceUntilIdle()
                var active = awaitItem()
                while (!active.isActiveAttempt) active = awaitItem()
                assertFalse(active.recoveryPending)

                callState.value = CallState.Ended
                advanceUntilIdle()
                var ended = awaitItem()
                while (!ended.recoveryPending) ended = awaitItem()
                assertFalse(ended.isActiveAttempt)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `identity switch preserves details evidence and disables call again`() =
        runTest {
            val activeIdentity = MutableStateFlow<String?>("local")
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { repository.observeActiveIdentityHash() } returns activeIdentity
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var ready = awaitItem()
                while (!ready.canStartCall) ready = awaitItem()
                assertEquals("attempt", ready.record?.callAttemptId)

                activeIdentity.value = "other-local"
                advanceUntilIdle()
                val switched = awaitItem()

                assertEquals("attempt", switched.record?.callAttemptId)
                assertFalse(switched.canStartCall)
                assertFalse(switched.localIdentityMatches)
                viewModel.toggleBlocked()
                advanceUntilIdle()
                coVerify(exactly = 0) { blockedPeerRepository.blockIdentity(any(), any(), any(), any()) }
                coVerify(exactly = 0) { blockedPeerRepository.unblockIdentity(any(), any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `view peer re-resolves destination at activation time`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record().copy(currentDestinationHash = "stale"))
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { announceRepository.findTelephonyByIdentityHash("remote") } returns
                mockk {
                    every { destinationHash } returns "fresh"
                }
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                while (awaitItem().record == null) Unit
                var resolved: String? = null

                viewModel.resolveCurrentPeerDestination { resolved = it }
                advanceUntilIdle()

                assertEquals("fresh", resolved)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `terminal exact runtime snapshot leaves unfinished row in recovery`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record().copy(endedAt = null, outcome = null))
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { rnsTelephony.getCallState() } returns
                Result.success(
                    VoiceCallState(
                        status = "ended",
                        isActive = false,
                        isMuted = false,
                        remoteIdentity = "remote",
                        profile = null,
                        callAttemptId = "attempt",
                    ),
                )

            viewModel().state.test {
                awaitItem()
                advanceUntilIdle()
                var recovering = awaitItem()
                while (!recovering.recoveryPending) recovering = awaitItem()
                assertTrue(recovering.recoveryPending)
                assertFalse(recovering.canStartCall)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed block persistence performs no runtime mutation`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { blockedPeerRepository.getApprovedDestinationHashes("remote") } returns listOf("destination")
            coEvery { blockedPeerRepository.blockIdentity(any(), any(), any(), any()) } throws
                IllegalStateException("database unavailable")
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var ready = awaitItem()
                while (ready.blockState != BlockLookupState.UNBLOCKED) ready = awaitItem()
                viewModel.toggleBlocked()
                advanceUntilIdle()

                coVerify(exactly = 0) { rnsCore.blockIdentity(any()) }
                coVerify(exactly = 0) { rnsCore.blockDestination(any()) }
                assertTrue(viewModel.state.value.actionError)
                assertFalse(viewModel.state.value.runtimeBlockRecoveryRequired)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed runtime block keeps durable authority for service reconciliation`() =
        runTest {
            every { repository.observeRecord("attempt") } returns flowOf(record())
            every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
            coEvery { blockedPeerRepository.getApprovedDestinationHashes("remote") } returns listOf("destination")
            coJustRun { blockedPeerRepository.blockIdentity("remote", "Alice", false, "local") }
            coEvery { rnsCore.blockIdentity("remote") } returns
                Result.failure(IllegalStateException("runtime unavailable"))
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                advanceUntilIdle()
                var ready = awaitItem()
                while (ready.blockState != BlockLookupState.UNBLOCKED) ready = awaitItem()
                viewModel.toggleBlocked()
                advanceUntilIdle()

                coVerifyOrder {
                    blockedPeerRepository.blockIdentity("remote", "Alice", false, "local")
                    rnsCore.blockIdentity("remote")
                }
                coVerify(exactly = 0) { blockedPeerRepository.unblockIdentity(any(), any()) }
                assertTrue(viewModel.state.value.runtimeBlockRecoveryRequired)
                assertFalse(viewModel.state.value.canStartCall)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `call again failure navigation result is consumable`() {
        every { repository.observeRecord("attempt") } returns flowOf(record())
        every { blockedPeerRepository.isIdentityBlockedFlow("remote") } returns flowOf(false)
        val savedState =
            SavedStateHandle(
                mapOf(
                    "callAttemptId" to "attempt",
                    CALL_AGAIN_FAILURE_RESULT to CallInitiationFailure.BLOCKED_IDENTITY.name,
                ),
            )
        val viewModel = viewModel(savedState)

        assertEquals(CallInitiationFailure.BLOCKED_IDENTITY.name, viewModel.callAgainFailure.value)
        viewModel.consumeCallAgainFailure()
        assertEquals(null, viewModel.callAgainFailure.value)
    }

    private fun viewModel(savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("callAttemptId" to "attempt"))) =
        CallDetailsViewModel(
            savedStateHandle = savedStateHandle,
            repository = repository,
            announceRepository = announceRepository,
            blockedPeerRepository = blockedPeerRepository,
            rnsCore = rnsCore,
            rnsTelephony = rnsTelephony,
        )

    private fun record() =
        CallHistoryRecord(
            callAttemptId = "attempt",
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
            currentDestinationHash = "destination",
        )
}
