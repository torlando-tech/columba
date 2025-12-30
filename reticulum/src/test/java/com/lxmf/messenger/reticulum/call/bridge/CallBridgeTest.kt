package com.lxmf.messenger.reticulum.call.bridge

import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for CallBridge.
 *
 * Tests call state management, Python callback integration,
 * and UI action forwarding using a mockable interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBridgeTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callBridge: CallBridge
    private lateinit var mockCallManager: PythonCallManagerInterface

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Reset the singleton to get a fresh instance
        CallBridge.resetInstance()

        // Create mock call manager
        mockCallManager = mockk(relaxed = true)

        // Get the singleton instance with test dispatcher and inject mock
        callBridge = CallBridge.getInstance(testDispatcher)
        callBridge.setCallManagerInterface(mockCallManager)
    }

    @After
    fun tearDown() {
        CallBridge.resetInstance()
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial call state is Idle`() = runTest {
        advanceUntilIdle()
        callBridge.callState.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertTrue("Expected Idle, got $state", state is CallState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial mute state is false`() = runTest {
        advanceUntilIdle()
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial speaker state is false`() = runTest {
        advanceUntilIdle()
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial remote identity is null`() = runTest {
        advanceUntilIdle()
        callBridge.remoteIdentity.test(timeout = 5.seconds) {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Incoming Call Tests ==========

    @Test
    fun `onIncomingCall sets state to Incoming`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.onIncomingCall(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue("Expected Incoming, got $state", state is CallState.Incoming)
            assertEquals(testHash, (state as CallState.Incoming).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onIncomingCall sets remote identity`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.remoteIdentity.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            callBridge.onIncomingCall(testHash)
            advanceUntilIdle()

            assertEquals(testHash, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Outgoing Call Tests ==========

    @Test
    fun `initiateCall sets state to Connecting`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.initiateCall(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue("Expected Connecting, got $state", state is CallState.Connecting)
            assertEquals(testHash, (state as CallState.Connecting).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initiateCall calls Python call manager`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.initiateCall(testHash)
        advanceUntilIdle()

        verify { mockCallManager.call(testHash) }
    }

    @Test
    fun `initiateCall sets remote identity`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.remoteIdentity.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            callBridge.initiateCall(testHash)
            advanceUntilIdle()

            assertEquals(testHash, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Ringing Tests ==========

    @Test
    fun `onCallRinging sets state to Ringing`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.onCallRinging(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue("Expected Ringing, got $state", state is CallState.Ringing)
            assertEquals(testHash, (state as CallState.Ringing).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Established Tests ==========

    @Test
    fun `onCallEstablished sets state to Active`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue("Expected Active, got $state", state is CallState.Active)
            assertEquals(testHash, (state as CallState.Active).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCallEstablished sets call start time`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callStartTime.test(timeout = 5.seconds) {
            assertNull(awaitItem())

            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()

            val startTime = awaitItem()
            assertTrue("Start time should be positive", startTime != null && startTime > 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Ended Tests ==========

    @Test
    fun `onCallEnded sets state to Ended`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 10.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            // First establish a call
            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()
            val active = awaitItem()
            assertTrue("Expected Active, got $active", active is CallState.Active)

            // Then end it
            callBridge.onCallEnded(testHash)
            advanceUntilIdle()

            val ended = awaitItem()
            assertEquals(CallState.Ended, ended)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Busy Tests ==========

    @Test
    fun `onCallBusy sets state to Busy`() = runTest {
        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.onCallBusy()
            advanceUntilIdle()

            assertEquals(CallState.Busy, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Rejected Tests ==========

    @Test
    fun `onCallRejected sets state to Rejected`() = runTest {
        callBridge.callState.test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertTrue("Expected Idle, got $initial", initial is CallState.Idle)

            callBridge.onCallRejected()
            advanceUntilIdle()

            assertEquals(CallState.Rejected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Answer Call Tests ==========

    @Test
    fun `answerCall calls Python call manager`() = runTest {
        callBridge.answerCall()
        advanceUntilIdle()

        verify { mockCallManager.answer() }
    }

    // ========== End Call Tests ==========

    @Test
    fun `endCall calls Python call manager hangup`() = runTest {
        callBridge.endCall()
        advanceUntilIdle()

        verify { mockCallManager.hangup() }
    }

    @Test
    fun `declineCall calls endCall which calls hangup`() = runTest {
        callBridge.declineCall()
        advanceUntilIdle()

        verify { mockCallManager.hangup() }
    }

    // ========== Mute Toggle Tests ==========

    @Test
    fun `toggleMute toggles mute state and calls Python`() = runTest {
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.toggleMute()
            advanceUntilIdle()

            assertTrue(awaitItem())
            verify { mockCallManager.muteMicrophone(true) }

            callBridge.toggleMute()
            advanceUntilIdle()

            assertFalse(awaitItem())
            verify { mockCallManager.muteMicrophone(false) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setMuted sets specific mute state and calls Python`() = runTest {
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.setMuted(true)
            advanceUntilIdle()

            assertTrue(awaitItem())
            verify { mockCallManager.muteMicrophone(true) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Speaker Toggle Tests ==========

    @Test
    fun `toggleSpeaker toggles speaker state and calls Python`() = runTest {
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.toggleSpeaker()
            advanceUntilIdle()

            assertTrue(awaitItem())
            verify { mockCallManager.setSpeaker(true) }

            callBridge.toggleSpeaker()
            advanceUntilIdle()

            assertFalse(awaitItem())
            verify { mockCallManager.setSpeaker(false) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSpeaker sets specific speaker state and calls Python`() = runTest {
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.setSpeaker(true)
            advanceUntilIdle()

            assertTrue(awaitItem())
            verify { mockCallManager.setSpeaker(true) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Helper Method Tests ==========

    @Test
    fun `hasActiveCall returns false for Idle state`() = runTest {
        advanceUntilIdle()
        assertFalse(callBridge.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true for Connecting state`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.initiateCall(testHash)
        advanceUntilIdle()

        assertTrue(callBridge.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true for Ringing state`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.onCallRinging(testHash)
        advanceUntilIdle()

        assertTrue(callBridge.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true for Incoming state`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.onIncomingCall(testHash)
        advanceUntilIdle()

        assertTrue(callBridge.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns true for Active state`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.onCallEstablished(testHash)
        advanceUntilIdle()

        assertTrue(callBridge.hasActiveCall())
    }

    // ========== Duration Tests ==========

    @Test
    fun `getCurrentDuration returns 0 when no call active`() = runTest {
        advanceUntilIdle()
        assertEquals(0L, callBridge.getCurrentDuration())
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `initiateCall handles Python exception gracefully`() = runTest {
        every { mockCallManager.call(any()) } throws RuntimeException("Python error")

        callBridge.callState.test(timeout = 5.seconds) {
            val idle = awaitItem()
            assertTrue("Expected Idle, got $idle", idle is CallState.Idle)

            callBridge.initiateCall("abc123")
            advanceUntilIdle()

            // Should transition to Connecting, then to Ended on error
            val connecting = awaitItem()
            assertTrue("Expected Connecting, got $connecting", connecting is CallState.Connecting)

            val ended = awaitItem()
            assertEquals(CallState.Ended, ended)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleMute handles Python exception gracefully`() = runTest {
        every { mockCallManager.muteMicrophone(any()) } throws RuntimeException("Python error")

        // Should not crash, state should still toggle
        callBridge.toggleMute()
        advanceUntilIdle()

        callBridge.isMuted.test(timeout = 5.seconds) {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== State Transition Tests ==========

    @Test
    fun `full call lifecycle Ringing to Active to Ended`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 10.seconds) {
            val idle = awaitItem()
            assertTrue("Expected Idle, got $idle", idle is CallState.Idle)

            // Simulate ringing
            callBridge.onCallRinging(testHash)
            advanceUntilIdle()
            val ringing = awaitItem()
            assertTrue("Expected Ringing, got $ringing", ringing is CallState.Ringing)

            // Ringing -> Active
            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()
            val active = awaitItem()
            assertTrue("Expected Active, got $active", active is CallState.Active)

            // Active -> Ended
            callBridge.onCallEnded(testHash)
            advanceUntilIdle()
            val ended = awaitItem()
            assertEquals(CallState.Ended, ended)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `incoming call lifecycle Incoming to Active to Ended`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 10.seconds) {
            val idle = awaitItem()
            assertTrue("Expected Idle, got $idle", idle is CallState.Idle)

            // Incoming call
            callBridge.onIncomingCall(testHash)
            advanceUntilIdle()
            val incoming = awaitItem()
            assertTrue("Expected Incoming, got $incoming", incoming is CallState.Incoming)

            // Answered -> Active
            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()
            val active = awaitItem()
            assertTrue("Expected Active, got $active", active is CallState.Active)

            // Hang up -> Ended
            callBridge.onCallEnded(testHash)
            advanceUntilIdle()
            val ended = awaitItem()
            assertEquals(CallState.Ended, ended)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
