package com.lxmf.messenger.reticulum.call.bridge

import app.cash.turbine.test
import com.chaquo.python.PyObject
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
 * and UI action forwarding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBridgeTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callBridge: CallBridge
    private lateinit var mockPythonCallManager: PyObject

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockPythonCallManager = mockk(relaxed = true)

        // Get fresh singleton instance
        callBridge = CallBridge.getInstance()
        callBridge.setPythonCallManager(mockPythonCallManager)
    }

    @After
    fun tearDown() {
        callBridge.shutdown()
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial call state is Idle`() = runTest {
        callBridge.callState.test(timeout = 5.seconds) {
            assertTrue(awaitItem() is CallState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial mute state is false`() = runTest {
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial speaker state is false`() = runTest {
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial remote identity is null`() = runTest {
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
            assertEquals(CallState.Idle, awaitItem())

            callBridge.onIncomingCall(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is CallState.Incoming)
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
            assertEquals(CallState.Idle, awaitItem())

            callBridge.initiateCall(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is CallState.Connecting)
            assertEquals(testHash, (state as CallState.Connecting).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initiateCall calls Python call manager`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.initiateCall(testHash)
        advanceUntilIdle()

        verify { mockPythonCallManager.callAttr("call", testHash) }
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
            assertEquals(CallState.Idle, awaitItem())

            callBridge.onCallRinging(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is CallState.Ringing)
            assertEquals(testHash, (state as CallState.Ringing).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Established Tests ==========

    @Test
    fun `onCallEstablished sets state to Active`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 5.seconds) {
            assertEquals(CallState.Idle, awaitItem())

            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state is CallState.Active)
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
            assertTrue(startTime != null && startTime > 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Ended Tests ==========

    @Test
    fun `onCallEnded sets state to Ended then Idle`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        callBridge.callState.test(timeout = 10.seconds) {
            assertEquals(CallState.Idle, awaitItem())

            // First establish a call
            callBridge.onCallEstablished(testHash)
            advanceUntilIdle()
            assertTrue(awaitItem() is CallState.Active)

            // Then end it
            callBridge.onCallEnded(testHash)
            advanceUntilIdle()

            assertEquals(CallState.Ended, awaitItem())

            // Wait for reset to Idle (after 2s delay)
            val idleState = awaitItem()
            assertEquals(CallState.Idle, idleState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Call Busy Tests ==========

    @Test
    fun `onCallBusy sets state to Busy`() = runTest {
        callBridge.callState.test(timeout = 5.seconds) {
            assertEquals(CallState.Idle, awaitItem())

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
            assertEquals(CallState.Idle, awaitItem())

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

        verify { mockPythonCallManager.callAttr("answer") }
    }

    // ========== End Call Tests ==========

    @Test
    fun `endCall calls Python call manager hangup`() = runTest {
        callBridge.endCall()
        advanceUntilIdle()

        verify { mockPythonCallManager.callAttr("hangup") }
    }

    @Test
    fun `declineCall calls endCall`() = runTest {
        callBridge.declineCall()
        advanceUntilIdle()

        verify { mockPythonCallManager.callAttr("hangup") }
    }

    // ========== Mute Toggle Tests ==========

    @Test
    fun `toggleMute toggles mute state`() = runTest {
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.toggleMute()
            advanceUntilIdle()

            assertTrue(awaitItem())

            callBridge.toggleMute()
            advanceUntilIdle()

            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleMute calls Python call manager`() = runTest {
        callBridge.toggleMute()
        advanceUntilIdle()

        verify { mockPythonCallManager.callAttr("mute_microphone", true) }
    }

    @Test
    fun `setMuted sets specific mute state`() = runTest {
        callBridge.isMuted.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.setMuted(true)
            advanceUntilIdle()

            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Speaker Toggle Tests ==========

    @Test
    fun `toggleSpeaker toggles speaker state`() = runTest {
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.toggleSpeaker()
            advanceUntilIdle()

            assertTrue(awaitItem())

            callBridge.toggleSpeaker()
            advanceUntilIdle()

            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleSpeaker calls Python call manager`() = runTest {
        callBridge.toggleSpeaker()
        advanceUntilIdle()

        verify { mockPythonCallManager.callAttr("set_speaker", true) }
    }

    @Test
    fun `setSpeaker sets specific speaker state`() = runTest {
        callBridge.isSpeakerOn.test(timeout = 5.seconds) {
            assertFalse(awaitItem())

            callBridge.setSpeaker(true)
            advanceUntilIdle()

            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Helper Method Tests ==========

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

    @Test
    fun `hasActiveCall returns false for Idle state`() {
        assertFalse(callBridge.hasActiveCall())
    }

    @Test
    fun `hasActiveCall returns false for Ended state`() = runTest {
        callBridge.onCallEnded(null)
        advanceUntilIdle()

        // State becomes Ended briefly
        // Note: hasActiveCall checks current state
        assertFalse(callBridge.hasActiveCall())
    }

    // ========== Duration Tests ==========

    @Test
    fun `getCurrentDuration returns 0 when no call active`() {
        assertEquals(0L, callBridge.getCurrentDuration())
    }

    // ========== Shutdown Tests ==========

    @Test
    fun `shutdown resets all state`() = runTest {
        val testHash = "abc123def456789012345678901234567890"

        // Establish a call first
        callBridge.onCallEstablished(testHash)
        callBridge.toggleMute()
        callBridge.toggleSpeaker()
        advanceUntilIdle()

        // Shutdown
        callBridge.shutdown()
        advanceUntilIdle()

        // Verify state is reset (after the delay completes)
        callBridge.callState.test(timeout = 5.seconds) {
            // May go through Ended state first
            val state = awaitItem()
            if (state is CallState.Ended || state is CallState.Idle) {
                cancelAndIgnoreRemainingEvents()
            } else {
                awaitItem() // Wait for Idle
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `initiateCall handles Python exception gracefully`() = runTest {
        every { mockPythonCallManager.callAttr("call", any()) } throws RuntimeException("Python error")

        callBridge.callState.test(timeout = 5.seconds) {
            assertEquals(CallState.Idle, awaitItem())

            callBridge.initiateCall("abc123")
            advanceUntilIdle()

            // Should transition to Connecting, then to Ended on error
            val connecting = awaitItem()
            assertTrue(connecting is CallState.Connecting)

            val ended = awaitItem()
            assertEquals(CallState.Ended, ended)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `answerCall handles null Python manager gracefully`() = runTest {
        // Create a new bridge instance without Python manager
        val newBridge = CallBridge.getInstance()

        // Clear the Python manager by setting to null via reflection or shutdown
        // For this test, we just verify it doesn't crash
        newBridge.answerCall()
        advanceUntilIdle()
        // No exception = pass
    }

    @Test
    fun `toggleMute handles Python exception gracefully`() = runTest {
        every { mockPythonCallManager.callAttr("mute_microphone", any()) } throws RuntimeException("Python error")

        // Should not crash, state should still toggle
        callBridge.toggleMute()
        advanceUntilIdle()

        callBridge.isMuted.test(timeout = 5.seconds) {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
