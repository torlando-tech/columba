package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.data.db.entity.ContactEntity
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CallViewModel.
 *
 * Tests call state observation, UI actions, and helper methods.
 * Uses UnconfinedTestDispatcher to avoid infinite loop issues with the duration timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
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
        // Transition to Idle to stop any running duration timer
        callStateFlow.value = CallState.Idle
        unmockkObject(CallBridge.Companion)
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Call State Tests ==========

    @Test
    fun `callState exposes CallBridge callState`() {
        assertEquals(callStateFlow, viewModel.callState)
    }

    @Test
    fun `isMuted exposes CallBridge isMuted`() {
        assertEquals(isMutedFlow, viewModel.isMuted)
    }

    @Test
    fun `isSpeakerOn exposes CallBridge isSpeakerOn`() {
        assertEquals(isSpeakerOnFlow, viewModel.isSpeakerOn)
    }

    @Test
    fun `remoteIdentity exposes CallBridge remoteIdentity`() {
        assertEquals(remoteIdentityFlow, viewModel.remoteIdentity)
    }

    // ========== isConnecting Tests ==========

    @Test
    fun `isConnecting is true when state is Connecting`() = runTest {
        callStateFlow.value = CallState.Connecting("abc123")
        assertTrue(viewModel.isConnecting.value)
    }

    @Test
    fun `isConnecting is false when state is Idle`() = runTest {
        callStateFlow.value = CallState.Idle
        assertFalse(viewModel.isConnecting.value)
    }

    @Test
    fun `isConnecting is false when state is Ringing`() = runTest {
        callStateFlow.value = CallState.Ringing("abc123")
        assertFalse(viewModel.isConnecting.value)
    }

    // ========== UI Action Tests ==========

    @Test
    fun `initiateCall forwards to CallBridge`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        viewModel.initiateCall(testHash)
        verify { mockCallBridge.initiateCall(testHash) }
    }

    @Test
    fun `answerCall forwards to CallBridge`() = runTest {
        viewModel.answerCall()
        verify { mockCallBridge.answerCall() }
    }

    @Test
    fun `endCall forwards to CallBridge`() = runTest {
        viewModel.endCall()
        verify { mockCallBridge.endCall() }
    }

    @Test
    fun `declineCall forwards to CallBridge`() = runTest {
        viewModel.declineCall()
        verify { mockCallBridge.declineCall() }
    }

    @Test
    fun `toggleMute forwards to CallBridge`() = runTest {
        viewModel.toggleMute()
        verify { mockCallBridge.toggleMute() }
    }

    @Test
    fun `toggleSpeaker forwards to CallBridge`() = runTest {
        viewModel.toggleSpeaker()
        verify { mockCallBridge.toggleSpeaker() }
    }

    // ========== hasActiveCall Tests ==========

    @Test
    fun `hasActiveCall returns true when call is active`() {
        callStateFlow.value = CallState.Active("abc123")
        assertTrue(viewModel.hasActiveCall())
        callStateFlow.value = CallState.Idle // Stop duration timer
    }

    @Test
    fun `hasActiveCall returns true when connecting`() {
        callStateFlow.value = CallState.Connecting("abc123")
        assertTrue(viewModel.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true when ringing`() {
        callStateFlow.value = CallState.Ringing("abc123")
        assertTrue(viewModel.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true when incoming`() {
        callStateFlow.value = CallState.Incoming("abc123")
        assertTrue(viewModel.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns false when idle`() {
        callStateFlow.value = CallState.Idle
        assertFalse(viewModel.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns false when ended`() {
        callStateFlow.value = CallState.Ended
        assertFalse(viewModel.hasActiveCall())
    }

    // ========== Duration Formatting Tests ==========

    @Test
    fun `formatDuration formats seconds correctly`() {
        assertEquals("00:00", viewModel.formatDuration(0))
        assertEquals("00:01", viewModel.formatDuration(1))
        assertEquals("00:59", viewModel.formatDuration(59))
    }

    @Test
    fun `formatDuration formats minutes correctly`() {
        assertEquals("01:00", viewModel.formatDuration(60))
        assertEquals("01:30", viewModel.formatDuration(90))
        assertEquals("09:59", viewModel.formatDuration(599))
    }

    @Test
    fun `formatDuration formats longer durations correctly`() {
        assertEquals("60:00", viewModel.formatDuration(3600))
        assertEquals("90:45", viewModel.formatDuration(5445))
    }

    // ========== Status Text Tests ==========

    @Test
    fun `getStatusText returns Connecting for Connecting state`() {
        assertEquals("Connecting...", viewModel.getStatusText(CallState.Connecting("abc")))
    }

    @Test
    fun `getStatusText returns Ringing for Ringing state`() {
        assertEquals("Ringing...", viewModel.getStatusText(CallState.Ringing("abc")))
    }

    @Test
    fun `getStatusText returns Incoming for Incoming state`() {
        assertEquals("Incoming Call", viewModel.getStatusText(CallState.Incoming("abc")))
    }

    @Test
    fun `getStatusText returns Connected for Active state`() {
        assertEquals("Connected", viewModel.getStatusText(CallState.Active("abc")))
    }

    @Test
    fun `getStatusText returns Call Ended for Ended state`() {
        assertEquals("Call Ended", viewModel.getStatusText(CallState.Ended))
    }

    @Test
    fun `getStatusText returns Line Busy for Busy state`() {
        assertEquals("Line Busy", viewModel.getStatusText(CallState.Busy))
    }

    @Test
    fun `getStatusText returns Rejected for Rejected state`() {
        assertEquals("Call Rejected", viewModel.getStatusText(CallState.Rejected))
    }

    @Test
    fun `getStatusText returns empty for Idle state`() {
        assertEquals("", viewModel.getStatusText(CallState.Idle))
    }

    // ========== Peer Name Resolution Tests ==========

    @Test
    fun `peerName resolves from contact repository`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        val mockContact = mockk<ContactEntity> {
            every { customNickname } returns "Test User"
        }
        coEvery { mockContactRepository.getContact(testHash) } returns mockContact

        remoteIdentityFlow.value = testHash

        // Give time for the collector to process
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Test User", viewModel.peerName.value)
    }

    @Test
    fun `peerName falls back to formatted hash when contact not found`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        coEvery { mockContactRepository.getContact(testHash) } returns null

        remoteIdentityFlow.value = testHash
        testDispatcher.scheduler.advanceUntilIdle()

        val name = viewModel.peerName.value
        assertTrue(name?.contains("abc123") == true)
        assertTrue(name?.contains("...") == true)
    }

    @Test
    fun `peerName handles repository exception gracefully`() = runTest {
        val testHash = "abc123def456789012345678901234567890"
        coEvery { mockContactRepository.getContact(testHash) } throws RuntimeException("Database error")

        remoteIdentityFlow.value = testHash
        testDispatcher.scheduler.advanceUntilIdle()

        // Should fallback to formatted hash
        val name = viewModel.peerName.value
        assertTrue(name?.contains("abc123") == true)
    }
}
