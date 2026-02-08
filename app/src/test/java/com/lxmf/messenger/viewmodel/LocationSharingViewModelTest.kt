package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.model.SharingDuration
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LocationSharingViewModel.
 *
 * Tests the location sharing state computation and action delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSharingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockLocationSharingManager: LocationSharingManager
    private lateinit var mockContactRepository: ContactRepository
    private lateinit var viewModel: LocationSharingViewModel

    // Flows for mocking
    private lateinit var activeSessionsFlow: MutableStateFlow<List<SharingSession>>
    private lateinit var isSharingFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockLocationSharingManager = mockk()
        mockContactRepository = mockk()

        // Initialize flows
        activeSessionsFlow = MutableStateFlow(emptyList())
        isSharingFlow = MutableStateFlow(false)

        // Mock manager flows
        every { mockLocationSharingManager.activeSessions } returns activeSessionsFlow
        every { mockLocationSharingManager.isSharing } returns isSharingFlow

        // Mock manager actions
        every { mockLocationSharingManager.startSharing(any(), any(), any()) } just Runs
        every { mockLocationSharingManager.stopSharing(any()) } just Runs

        // Mock repository - default empty contacts
        every { mockContactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        viewModel = LocationSharingViewModel(mockLocationSharingManager, mockContactRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== setCurrentPeer Tests ==========

    @Test
    fun `setCurrentPeer updates currentPeerHash`() {
        val testHash = "abc123def456"

        viewModel.setCurrentPeer(testHash)

        assertEquals(testHash, viewModel.currentPeerHash.value)
    }

    @Test
    fun `setCurrentPeer with null clears currentPeerHash`() {
        viewModel.setCurrentPeer("abc123")
        viewModel.setCurrentPeer(null)

        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== locationSharingState Tests ==========

    @Test
    fun `locationSharingState is NONE when no peer set`() =
        runTest {
            // No peer set - initial value should be NONE
            assertEquals(LocationSharingState.NONE, viewModel.locationSharingState.value)
        }

    @Test
    fun `locationSharingState is NONE when not sharing in either direction`() =
        runTest {
            val peerHash = "abc123"
            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.NONE, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is SHARING_WITH_THEM when we share but they dont`() =
        runTest {
            val peerHash = "abc123"

            // We have an active session with this peer
            activeSessionsFlow.value = listOf(createSharingSession(peerHash))

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.SHARING_WITH_THEM, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is THEY_SHARE_WITH_ME when they share but we dont`() =
        runTest {
            val peerHash = "abc123"

            // They are sharing with us (contact has isReceivingLocationFrom = true)
            val contact = createEnrichedContact(peerHash, isReceivingLocationFrom = true)
            every { mockContactRepository.getEnrichedContacts() } returns flowOf(listOf(contact))

            // Recreate viewModel with updated mock
            viewModel = LocationSharingViewModel(mockLocationSharingManager, mockContactRepository)

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.THEY_SHARE_WITH_ME, viewModel.locationSharingState.value)
            job.cancel()
        }

    @Test
    fun `locationSharingState is MUTUAL when both sharing`() =
        runTest {
            val peerHash = "abc123"

            // We have an active session with this peer
            activeSessionsFlow.value = listOf(createSharingSession(peerHash))

            // They are also sharing with us
            val contact = createEnrichedContact(peerHash, isReceivingLocationFrom = true)
            every { mockContactRepository.getEnrichedContacts() } returns flowOf(listOf(contact))

            // Recreate viewModel with updated mock
            viewModel = LocationSharingViewModel(mockLocationSharingManager, mockContactRepository)

            viewModel.setCurrentPeer(peerHash)

            // Collect the flow to trigger subscription (WhileSubscribed requires active subscriber)
            val collectedStates = mutableListOf<LocationSharingState>()
            val job =
                backgroundScope.launch {
                    viewModel.locationSharingState.collect { collectedStates.add(it) }
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocationSharingState.MUTUAL, viewModel.locationSharingState.value)
            job.cancel()
        }

    // ========== startSharing Tests ==========

    @Test
    fun `startSharing delegates to manager with correct parameters`() {
        val peerHash = "abc123"
        val peerName = "Alice"
        val duration = SharingDuration.ONE_HOUR

        viewModel.setCurrentPeer(peerHash)
        assertEquals(peerHash, viewModel.currentPeerHash.value)

        viewModel.startSharing(peerName, duration)

        verify {
            mockLocationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }
    }

    @Test
    fun `startSharing does nothing when no peer set`() {
        // Verify precondition
        assertNull(viewModel.currentPeerHash.value)

        viewModel.startSharing("Alice", SharingDuration.ONE_HOUR)

        verify(exactly = 0) { mockLocationSharingManager.startSharing(any(), any(), any()) }
    }

    // ========== stopSharing Tests ==========

    @Test
    fun `stopSharing delegates to manager with current peer`() {
        val peerHash = "abc123"

        viewModel.setCurrentPeer(peerHash)
        assertEquals(peerHash, viewModel.currentPeerHash.value)

        viewModel.stopSharing()

        verify { mockLocationSharingManager.stopSharing(peerHash) }
    }

    @Test
    fun `stopSharing does nothing when no peer set`() {
        // Verify precondition
        assertNull(viewModel.currentPeerHash.value)

        viewModel.stopSharing()

        verify(exactly = 0) { mockLocationSharingManager.stopSharing(any()) }
    }

    // ========== startSharingWith Tests ==========

    @Test
    fun `startSharingWith delegates to manager without setting current peer`() {
        val peerHash = "abc123"
        val peerName = "Bob"
        val duration = SharingDuration.FOUR_HOURS

        // Verify currentPeerHash is null before
        assertNull(viewModel.currentPeerHash.value)

        viewModel.startSharingWith(peerHash, peerName, duration)

        // Should delegate to manager
        verify {
            mockLocationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }

        // Should NOT set current peer
        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== stopSharingWith Tests ==========

    @Test
    fun `stopSharingWith delegates to manager`() {
        val peerHash = "abc123"

        // Verify currentPeerHash is unchanged (null)
        assertNull(viewModel.currentPeerHash.value)

        viewModel.stopSharingWith(peerHash)

        verify { mockLocationSharingManager.stopSharing(peerHash) }

        // Should still NOT have a current peer
        assertNull(viewModel.currentPeerHash.value)
    }

    // ========== isSharing Tests ==========

    @Test
    fun `isSharing exposes manager isSharing state`() {
        assertEquals(isSharingFlow, viewModel.isSharing)
    }

    @Test
    fun `isSharing reflects manager state changes`() =
        runTest {
            assertEquals(false, viewModel.isSharing.value)

            isSharingFlow.value = true

            assertEquals(true, viewModel.isSharing.value)
        }

    // ========== Helper Functions ==========

    private fun createSharingSession(destinationHash: String): SharingSession =
        SharingSession(
            destinationHash = destinationHash,
            displayName = "Test User",
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 3600_000,
        )

    private fun createEnrichedContact(
        destinationHash: String,
        isReceivingLocationFrom: Boolean = false,
    ): EnrichedContact =
        EnrichedContact(
            destinationHash = destinationHash,
            publicKey = null,
            displayName = "Test User",
            customNickname = null,
            announceName = null,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = false,
            unreadCount = 0,
            lastMessageTimestamp = null,
            notes = null,
            tags = null,
            addedTimestamp = System.currentTimeMillis(),
            addedVia = "MANUAL",
            isPinned = false,
            isReceivingLocationFrom = isReceivingLocationFrom,
        )
}
