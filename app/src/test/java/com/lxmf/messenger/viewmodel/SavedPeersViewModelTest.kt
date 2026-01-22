package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.data.repository.AnnounceRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for SavedPeersViewModel.
 * Tests favorite management and announce deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPeersViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var announceRepository: AnnounceRepository
    private lateinit var viewModel: SavedPeersViewModel

    private val testAnnounce =
        Announce(
            destinationHash = "abc123def456",
            peerName = "TestPeer",
            publicKey = ByteArray(64) { it.toByte() },
            appData = null,
            hops = 2,
            lastSeenTimestamp = System.currentTimeMillis(),
            nodeType = "lxmf.delivery",
            receivingInterface = "TCPClientInterface[Test Server]",
            isFavorite = true,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        announceRepository = mockk()

        // Setup default mocks
        every { announceRepository.getFavoriteAnnounces() } returns flowOf(listOf(testAnnounce))
        every { announceRepository.searchFavoriteAnnounces(any()) } returns flowOf(emptyList())
        every { announceRepository.getFavoriteCount() } returns flowOf(1)
        coEvery { announceRepository.toggleFavorite(any()) } just Runs
        coEvery { announceRepository.setFavorite(any(), any()) } just Runs
        coEvery { announceRepository.deleteAnnounce(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SavedPeersViewModel = SavedPeersViewModel(announceRepository)

    // ========== Remove Favorite Tests ==========

    @Test
    fun `removeFavorite calls repository with correct parameters`() =
        runTest {
            viewModel = createViewModel()
            advanceUntilIdle()

            val testHash = "abc123def456"
            viewModel.removeFavorite(testHash)
            advanceUntilIdle()

            coVerify { announceRepository.setFavorite(testHash, false) }
        }

    @Test
    fun `removeFavorite handles errors gracefully`() =
        runTest {
            coEvery { announceRepository.setFavorite(any(), any()) } throws Exception("Database error")

            viewModel = createViewModel()
            advanceUntilIdle()

            // Should not crash
            viewModel.removeFavorite("abc123")
            advanceUntilIdle()

            // Verify attempt was made
            coVerify { announceRepository.setFavorite("abc123", false) }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    // ========== Delete Announce Tests ==========

    @Test
    fun `deleteAnnounce calls repository with correct hash`() =
        runTest {
            viewModel = createViewModel()
            advanceUntilIdle()

            val testHash = "abc123def456"
            viewModel.deleteAnnounce(testHash)
            advanceUntilIdle()

            coVerify { announceRepository.deleteAnnounce(testHash) }
        }

    @Test
    fun `deleteAnnounce handles errors gracefully`() =
        runTest {
            coEvery { announceRepository.deleteAnnounce(any()) } throws Exception("Database error")

            viewModel = createViewModel()
            advanceUntilIdle()

            // Should not crash
            viewModel.deleteAnnounce("abc123")
            advanceUntilIdle()

            // Verify attempt was made
            coVerify { announceRepository.deleteAnnounce("abc123") }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    // ========== Toggle Favorite Tests ==========

    @Test
    fun `toggleFavorite calls repository with correct hash`() =
        runTest {
            viewModel = createViewModel()
            advanceUntilIdle()

            val testHash = "abc123def456"
            viewModel.toggleFavorite(testHash)
            advanceUntilIdle()

            coVerify { announceRepository.toggleFavorite(testHash) }
        }

    @Test
    fun `toggleFavorite handles errors gracefully`() =
        runTest {
            coEvery { announceRepository.toggleFavorite(any()) } throws Exception("Database error")

            viewModel = createViewModel()
            advanceUntilIdle()

            // Should not crash
            viewModel.toggleFavorite("abc123")
            advanceUntilIdle()

            // Verify attempt was made
            coVerify { announceRepository.toggleFavorite("abc123") }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

}
