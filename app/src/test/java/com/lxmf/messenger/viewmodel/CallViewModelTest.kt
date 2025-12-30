package com.lxmf.messenger.viewmodel

import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.ContactEntity
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.call.bridge.CallBridge
import com.lxmf.messenger.reticulum.call.bridge.CallState
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for CallViewModel.
 *
 * Tests call state observation, UI actions, and helper methods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockContactRepository: ContactRepository
    private lateinit var mockCallBridge: CallBridge
    private lateinit var viewModel: CallViewModel

    // StateFlows for mocking CallBridge
    private lateinit var callStateFlow: MutableStateFlow<CallState>
    private lateinit var isMutedFlow: MutableStateFlow<Boolean>
    private lateinit var isSpeakerOnFlow: MutableStateFlow<Boolean>
    private lateinit var remoteIdentityFlow: MutableStateFlow<String?>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockContactRepository = mockk(relaxed = true)
        mockCallBridge = mockk(relaxed = true)

        // Initialize state flows
        callStateFlow = MutableStateFlow<CallState>(CallState.Idle)
        isMutedFlow = MutableStateFlow(false)
        isSpeakerOnFlow = MutableStateFlow(false)
        remoteIdentityFlow = MutableStateFlow<String?>(null)

        // Mock CallBridge singleton
        mockkObject(CallBridge.Companion)
        every { CallBridge.getInstance() } returns mockCallBridge
        every { mockCallBridge.callState } returns callStateFlow
        every { mockCallBridge.isMuted } returns isMutedFlow
        every { mockCallBridge.isSpeakerOn } returns isSpeakerOnFlow
        every { mockCallBridge.remoteIdentity } returns remoteIdentityFlow
        every { mockCallBridge.hasActiveCall() } answers {
            when (callStateFlow.value) {
                is CallState.Connecting,
                is CallState.Ringing,
                is CallState.Incoming,
                is CallState.Active,
                -> true
                else -> false
            }
        }

        viewModel = CallViewModel(mockContactRepository)
    }

    @After
    fun tearDown() {
        unmockkObject(CallBridge.Companion)
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Call State Observation Tests ==========

    @Test
    fun `callState reflects CallBridge state`() = runTest {
        viewModel.callState.test(timeout = 5.seconds) {
            assertEquals(CallState.Idle, awaitItem())

            callStateFlow.value = CallState.Connecting("abc123")
            assertEquals(CallState.Connecting("abc123"), awaitItem())

            callStateFlow.value = CallState.Active("abc123")
            assertEquals(CallState.Active("abc123"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isMuted reflects CallBridge state`() = runTest {
        viewModel.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            isMutedFlow.value = true
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isSpeakerOn reflects CallBridge state`() = runTest {
        viewModel.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            isSpeakerOnFlow.value = true
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Connecting State Tests ==========

    @Test
    fun `isConnecting is true when call state is Connecting`() = runTest {
        viewModel.isConnecting.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callStateFlow.value = CallState.Connecting("abc123")
            advanceUntilIdle()

            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isConnecting is false when call is active`() = runTest {
        viewModel.isConnecting.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callStateFlow.value = CallState.Active("abc123")
            advanceUntilIdle()

            // Should remain false (Active is not Connecting)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Peer Name Resolution Tests ==========

    @Test
    fun `peerName resolves from contact repository`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        val mockContact =
            mockk<ContactEntity> {
                every { displayName } returns "Test User"
            }
        coEvery { mockContactRepository.getContactByDestinationHash(testHash) } returns mockContact

        viewModel.peerName.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            remoteIdentityFlow.value = testHash
            advanceUntilIdle()

            assertEquals("Test User", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `peerName falls back to formatted hash when contact not found`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        coEvery { mockContactRepository.getContactByDestinationHash(testHash) } returns null

        viewModel.peerName.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            remoteIdentityFlow.value = testHash
            advanceUntilIdle()

            val name = awaitItem()
            assertTrue(name?.contains("abc123") == true)
            assertTrue(name?.contains("...") == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `peerName handles short hash gracefully`() = runTest {
        val testHash = "abc123"
        coEvery { mockContactRepository.getContactByDestinationHash(testHash) } returns null

        viewModel.peerName.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            remoteIdentityFlow.value = testHash
            advanceUntilIdle()

            assertEquals("abc123", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Duration Tests ==========

    @Test
    fun `callDuration resets when call ends`() = runTest {
        viewModel.callDuration.test(timeout = 5.seconds) {
            assertEquals(0L, awaitItem())

            // Simulate call ending
            callStateFlow.value = CallState.Ended
            advanceUntilIdle()

            // Duration should reset
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== UI Action Tests ==========

    @Test
    fun `initiateCall forwards to CallBridge`() = runTest {
        val testHash = "abc123def456"

        viewModel.initiateCall(testHash)
        advanceUntilIdle()

        verify { mockCallBridge.initiateCall(testHash) }
    }

    @Test
    fun `answerCall forwards to CallBridge`() = runTest {
        viewModel.answerCall()
        advanceUntilIdle()

        verify { mockCallBridge.answerCall() }
    }

    @Test
    fun `declineCall forwards to CallBridge`() = runTest {
        viewModel.declineCall()
        advanceUntilIdle()

        verify { mockCallBridge.declineCall() }
    }

    @Test
    fun `endCall forwards to CallBridge`() = runTest {
        viewModel.endCall()
        advanceUntilIdle()

        verify { mockCallBridge.endCall() }
    }

    @Test
    fun `toggleMute forwards to CallBridge`() = runTest {
        viewModel.toggleMute()
        advanceUntilIdle()

        verify { mockCallBridge.toggleMute() }
    }

    @Test
    fun `toggleSpeaker forwards to CallBridge`() = runTest {
        viewModel.toggleSpeaker()
        advanceUntilIdle()

        verify { mockCallBridge.toggleSpeaker() }
    }

    // ========== hasActiveCall Tests ==========

    @Test
    fun `hasActiveCall returns true when call is active`() {
        callStateFlow.value = CallState.Active("abc123")
        assertTrue(viewModel.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns false when idle`() {
        callStateFlow.value = CallState.Idle
        assertFalse(viewModel.hasActiveCall())
    }

    // ========== Duration Formatting Tests ==========

    @Test
    fun `formatDuration formats seconds correctly`() {
        assertEquals("00:00", viewModel.formatDuration(0))
        assertEquals("00:30", viewModel.formatDuration(30))
        assertEquals("01:00", viewModel.formatDuration(60))
        assertEquals("01:30", viewModel.formatDuration(90))
        assertEquals("10:00", viewModel.formatDuration(600))
        assertEquals("59:59", viewModel.formatDuration(3599))
    }

    @Test
    fun `formatDuration handles large values`() {
        assertEquals("100:00", viewModel.formatDuration(6000))
    }

    // ========== Status Text Tests ==========

    @Test
    fun `getStatusText returns correct text for Idle`() {
        assertEquals("", viewModel.getStatusText(CallState.Idle))
    }

    @Test
    fun `getStatusText returns correct text for Connecting`() {
        assertEquals("Connecting...", viewModel.getStatusText(CallState.Connecting("abc")))
    }

    @Test
    fun `getStatusText returns correct text for Ringing`() {
        assertEquals("Ringing...", viewModel.getStatusText(CallState.Ringing("abc")))
    }

    @Test
    fun `getStatusText returns correct text for Incoming`() {
        assertEquals("Incoming Call", viewModel.getStatusText(CallState.Incoming("abc")))
    }

    @Test
    fun `getStatusText returns correct text for Active`() {
        assertEquals("Connected", viewModel.getStatusText(CallState.Active("abc")))
    }

    @Test
    fun `getStatusText returns correct text for Busy`() {
        assertEquals("Line Busy", viewModel.getStatusText(CallState.Busy))
    }

    @Test
    fun `getStatusText returns correct text for Rejected`() {
        assertEquals("Call Rejected", viewModel.getStatusText(CallState.Rejected))
    }

    @Test
    fun `getStatusText returns correct text for Ended`() {
        assertEquals("Call Ended", viewModel.getStatusText(CallState.Ended))
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `peerName handles repository exception gracefully`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        coEvery {
            mockContactRepository.getContactByDestinationHash(testHash)
        } throws RuntimeException("Database error")

        viewModel.peerName.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            remoteIdentityFlow.value = testHash
            advanceUntilIdle()

            // Should fallback to formatted hash
            val name = awaitItem()
            assertTrue(name?.contains("abc123") == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
