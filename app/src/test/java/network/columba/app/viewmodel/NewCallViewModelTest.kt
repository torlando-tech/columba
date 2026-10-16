@file:Suppress("IgnoredReturnValue")

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.util.LxstDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewCallViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var viewModel: NewCallViewModel

    private val identityHash = "ab".repeat(16)
    private val telephonyHash = "8137b7aa68cce05c931a4ea1634ea1c7"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        announceRepository = mockk()
        rnsCore = mockk()
        viewModel = NewCallViewModel(announceRepository, rnsCore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleAnnounce(destHash: String) =
        mockk<Announce> {
            every { peerName } returns "Test Phone"
            every { destinationHash } returns destHash
        }

    @Test
    fun `invalid input produces InvalidInput with mode-specific reason`() =
        runTest {
            viewModel.submit("nope", NewCallInputMode.IDENTITY_HASH)
            viewModel.state.test {
                val state = awaitItem() as NewCallLookupState.InvalidInput
                assertEquals(NewCallInvalidReason.INVALID_IDENTITY_HASH, state.reason)
            }

            viewModel.submit("nope", NewCallInputMode.LXST_DESTINATION_HASH)
            viewModel.state.test {
                val state = awaitItem() as NewCallLookupState.InvalidInput
                assertEquals(NewCallInvalidReason.INVALID_DESTINATION_HASH, state.reason)
            }
        }

    @Test
    fun `identity hash mode derives telephony hash and finds announce`() =
        runTest {
            val announce = sampleAnnounce(telephonyHash)
            coEvery { announceRepository.getAnnounce(telephonyHash) } returns announce

            viewModel.submit(identityHash, NewCallInputMode.IDENTITY_HASH)
            viewModel.state.test {
                skipItems(1) // Searching
                val state = awaitItem() as NewCallLookupState.Resolved
                assertEquals(telephonyHash, state.telephonyDestinationHash)
                assertEquals(announce, state.announce)
            }
            coVerify { announceRepository.getAnnounce(telephonyHash) }
        }

    @Test
    fun `lxst destination mode uses hash directly`() =
        runTest {
            coEvery { announceRepository.getAnnounce(telephonyHash) } returns null

            viewModel.submit(telephonyHash, NewCallInputMode.LXST_DESTINATION_HASH)
            viewModel.state.test {
                skipItems(1) // Searching
                val state = awaitItem() as NewCallLookupState.Resolved
                assertEquals(telephonyHash, state.telephonyDestinationHash)
                assertNull(state.announce)
            }
        }

    @Test
    fun `search network falls back to db check when announce flow is empty`() =
        runTest {
            val announce = sampleAnnounce(telephonyHash)
            coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
            every { announceRepository.getAnnounceFlow(telephonyHash) } returns flowOf()
            coEvery { announceRepository.getAnnounce(telephonyHash) } returns announce

            viewModel.searchNetwork(telephonyHash)
            viewModel.state.test {
                val requesting = awaitItem() as NewCallLookupState.RequestingNetwork
                assertEquals(telephonyHash, requesting.telephonyDestinationHash)
                val resolved = awaitItem() as NewCallLookupState.Resolved
                assertEquals(announce, resolved.announce)
            }
            coVerify {
                rnsCore.requestPath(LxstDestination.hexToBytes(telephonyHash))
            }
        }

    @Test
    fun `search network failure surfaces error message`() =
        runTest {
            coEvery { rnsCore.requestPath(any()) } returns Result.failure(Exception("no route"))

            viewModel.searchNetwork(telephonyHash)
            viewModel.state.test {
                skipItems(1) // RequestingNetwork
                val failed = awaitItem() as NewCallLookupState.NetworkRequestFailed
                assertEquals(telephonyHash, failed.telephonyDestinationHash)
                assertEquals("no route", failed.message)
            }
        }

    @Test
    fun `search network resolves when announce lands after path request`() =
        runTest {
            val announce = sampleAnnounce(telephonyHash)
            coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
            every { announceRepository.getAnnounceFlow(telephonyHash) } returns flowOf(null, announce)

            viewModel.searchNetwork(telephonyHash)
            viewModel.state.test {
                skipItems(1) // RequestingNetwork
                val resolved = awaitItem() as NewCallLookupState.Resolved
                assertEquals(telephonyHash, resolved.telephonyDestinationHash)
                assertEquals(announce, resolved.announce)
            }
        }

    @Test
    fun `search network resolves not-found when no announce arrives`() =
        runTest {
            coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
            every { announceRepository.getAnnounceFlow(telephonyHash) } returns
                flow<Announce?> { awaitCancellation() }
            coEvery { announceRepository.getAnnounce(telephonyHash) } returns null

            viewModel.searchNetwork(telephonyHash)
            viewModel.state.test {
                skipItems(1) // RequestingNetwork
                val resolved = awaitItem() as NewCallLookupState.Resolved
                assertNull(resolved.announce)
            }
        }

    @Test
    fun `submit cancels in-flight network search`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { rnsCore.requestPath(any()) } coAnswers {
                gate.await()
                Result.success(Unit)
            }
            val otherHash = "cd".repeat(16)
            coEvery { announceRepository.getAnnounce(otherHash) } returns null

            viewModel.searchNetwork(telephonyHash)
            runCurrent()

            viewModel.submit(otherHash, NewCallInputMode.LXST_DESTINATION_HASH)
            advanceUntilIdle()
            viewModel.state.test {
                val state = awaitItem()
                assert(state is NewCallLookupState.Resolved)
                assertEquals(otherHash, (state as NewCallLookupState.Resolved).telephonyDestinationHash)
            }

            // The superseded path request completing late must not clobber the new result.
            gate.complete(Unit)
            advanceUntilIdle()
            viewModel.state.test {
                val state = awaitItem()
                assert(state is NewCallLookupState.Resolved)
                assertEquals(otherHash, (state as NewCallLookupState.Resolved).telephonyDestinationHash)
            }
        }
}
