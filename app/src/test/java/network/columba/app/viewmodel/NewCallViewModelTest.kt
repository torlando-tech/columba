@file:Suppress("IgnoredReturnValue")

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
        announceRepository = mockk(relaxed = true)
        rnsCore = mockk(relaxed = true)
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
    fun `invalid input produces InvalidInput state`() =
        runTest {
            viewModel.submit("nope", NewCallInputMode.IDENTITY_HASH)
            viewModel.state.test {
                val state = awaitItem()
                assert(state is NewCallLookupState.InvalidInput)
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
    fun `search network submits path request and re-checks db`() =
        runTest {
            val announce = sampleAnnounce(telephonyHash)
            coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
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
}
