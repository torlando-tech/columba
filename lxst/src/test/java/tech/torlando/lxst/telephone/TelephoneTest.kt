package tech.torlando.lxst.telephone

import android.content.Context
import tech.torlando.lxst.bridge.KotlinAudioBridge
import tech.torlando.lxst.bridge.NetworkPacketBridge
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.bridge.CallBridge
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Telephone configuration and state logic.
 *
 * Tests mute controls, profile constants, transport callback registration,
 * and call method signatures without requiring device or network.
 *
 * Uses MockK to mock all hardware dependencies (NetworkTransport, KotlinAudioBridge, etc.).
 *
 * Note: Tests that require Android ringtone system are excluded (use instrumented tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelephoneTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockTransport: NetworkTransport
    private lateinit var mockAudioBridge: KotlinAudioBridge
    private lateinit var mockNetworkPacketBridge: NetworkPacketBridge
    private lateinit var mockCallBridge: CallBridge
    private lateinit var telephone: Telephone

    private var signalCallback: ((Int) -> Unit)? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockTransport = mockk(relaxed = true)
        mockAudioBridge = mockk(relaxed = true)
        mockNetworkPacketBridge = mockk(relaxed = true)
        mockCallBridge = mockk(relaxed = true)

        // Capture signal callback when registered
        val signalSlot = slot<(Int) -> Unit>()
        every { mockTransport.setSignalCallback(capture(signalSlot)) } answers {
            signalCallback = signalSlot.captured
        }

        every { mockTransport.isLinkActive } returns false

        telephone = Telephone(
            context = mockContext,
            networkTransport = mockTransport,
            audioBridge = mockAudioBridge,
            networkPacketBridge = mockNetworkPacketBridge,
            callBridge = mockCallBridge
        )
    }

    @After
    fun teardown() {
        telephone.shutdown()
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ===== Initial State =====

    @Test
    fun `initial call status is AVAILABLE`() {
        assertEquals(Signalling.STATUS_AVAILABLE, telephone.callStatus)
    }

    @Test
    fun `initial profile is DEFAULT (MQ)`() {
        assertEquals(Profile.DEFAULT, telephone.activeProfile)
    }

    @Test
    fun `initial profile is MQ`() {
        assertEquals(Profile.MQ, telephone.activeProfile)
    }

    @Test
    fun `isCallActive returns false initially`() {
        assertFalse(telephone.isCallActive())
    }

    @Test
    fun `isTransmitMuted returns false initially`() {
        assertFalse(telephone.isTransmitMuted())
    }

    @Test
    fun `isReceiveMuted returns false initially`() {
        assertFalse(telephone.isReceiveMuted())
    }

    // ===== Transmit Mute Controls =====

    @Test
    fun `muteTransmit true sets transmit muted`() {
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())
    }

    @Test
    fun `muteTransmit false clears transmit muted`() {
        telephone.muteTransmit(true)
        telephone.muteTransmit(false)
        assertFalse(telephone.isTransmitMuted())
    }

    @Test
    fun `muteTransmit toggle multiple times`() {
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())
        telephone.muteTransmit(false)
        assertFalse(telephone.isTransmitMuted())
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())
    }

    @Test
    fun `muteTransmit default parameter is true`() {
        telephone.muteTransmit()
        assertTrue(telephone.isTransmitMuted())
    }

    @Test
    fun `mute state persists across multiple calls`() {
        // Set mute
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())

        // Toggle and check
        telephone.muteTransmit(false)
        assertFalse(telephone.isTransmitMuted())

        // Re-enable and verify
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())
    }

    // ===== Receive Mute Controls =====

    @Test
    fun `muteReceive true sets receive muted`() {
        telephone.muteReceive(true)
        assertTrue(telephone.isReceiveMuted())
    }

    @Test
    fun `muteReceive false clears receive muted`() {
        telephone.muteReceive(true)
        telephone.muteReceive(false)
        assertFalse(telephone.isReceiveMuted())
    }

    @Test
    fun `muteReceive default parameter is true`() {
        telephone.muteReceive()
        assertTrue(telephone.isReceiveMuted())
    }

    @Test
    fun `transmit and receive mute are independent`() {
        telephone.muteTransmit(true)
        telephone.muteReceive(false)

        assertTrue(telephone.isTransmitMuted())
        assertFalse(telephone.isReceiveMuted())

        telephone.muteReceive(true)
        assertTrue(telephone.isTransmitMuted())
        assertTrue(telephone.isReceiveMuted())
    }

    // ===== Constants (must match Python LXST) =====

    @Test
    fun `DIAL_TONE_FREQUENCY is 382 Hz`() {
        assertEquals(382f, Telephone.DIAL_TONE_FREQUENCY)
    }

    @Test
    fun `BUSY_TONE_SECONDS is 4_25 seconds`() {
        assertEquals(4.25f, Telephone.BUSY_TONE_SECONDS)
    }

    @Test
    fun `RING_TIME_MS is 60 seconds`() {
        assertEquals(60_000L, Telephone.RING_TIME_MS)
    }

    @Test
    fun `WAIT_TIME_MS is 70 seconds`() {
        assertEquals(70_000L, Telephone.WAIT_TIME_MS)
    }

    @Test
    fun `CONNECT_TIME_MS is 5 seconds`() {
        assertEquals(5_000L, Telephone.CONNECT_TIME_MS)
    }

    @Test
    fun `DIAL_TONE_EASE_MS is pi (3_14159)`() {
        assertEquals(3.14159f, Telephone.DIAL_TONE_EASE_MS, 0.00001f)
    }

    @Test
    fun `DIAL_TONE_GAIN is 0_04`() {
        assertEquals(0.04f, Telephone.DIAL_TONE_GAIN)
    }

    // ===== Network Transport Callback Registration =====

    @Test
    fun `constructor registers signal callback`() {
        verify { mockTransport.setSignalCallback(any()) }
    }

    @Test
    fun `signal callback is set and captured`() {
        assertNotNull(signalCallback)
    }

    // ===== Call Initiation =====

    @Test
    fun `call method calls establishLink on transport`() = runTest {
        val destHash = ByteArray(32) { 0x00 }

        // Mock link establishment to fail (so we don't go further into call setup)
        coEvery { mockTransport.establishLink(any()) } returns false

        telephone.call(destHash)

        coVerify { mockTransport.establishLink(destHash) }
    }

    @Test
    fun `call method accepts profile parameter`() = runTest {
        val destHash = ByteArray(32) { 0x00 }

        coEvery { mockTransport.establishLink(any()) } coAnswers {
            // Profile should be set during call setup
            assertEquals(Profile.HQ, telephone.activeProfile)
            true
        }

        telephone.call(destHash, Profile.HQ)
        advanceUntilIdle()

        // Profile persists after successful link establishment
        assertEquals(Profile.HQ, telephone.activeProfile)
    }

    @Test
    fun `call sets status to CALLING`() = runTest {
        val destHash = ByteArray(32) { 0x00 }

        coEvery { mockTransport.establishLink(any()) } coAnswers {
            // Check status during link establishment
            assertEquals(Signalling.STATUS_CALLING, telephone.callStatus)
            false
        }

        telephone.call(destHash)
    }

    @Test
    fun `call notifies call bridge of connecting state`() = runTest {
        val destHash = ByteArray(32) { 0x00 }

        coEvery { mockTransport.establishLink(any()) } returns false

        telephone.call(destHash)

        verify { mockCallBridge.setConnecting(any()) }
    }

    // ===== Hangup =====

    @Test
    fun `hangup calls teardownLink`() {
        telephone.hangup()
        verify { mockTransport.teardownLink() }
    }

    @Test
    fun `hangup resets call status to AVAILABLE`() = runTest {
        // Start a call first
        coEvery { mockTransport.establishLink(any()) } returns false
        telephone.call(ByteArray(32))
        advanceUntilIdle()

        telephone.hangup()

        assertEquals(Signalling.STATUS_AVAILABLE, telephone.callStatus)
    }

    @Test
    fun `hangup notifies call bridge`() {
        telephone.hangup()
        verify { mockCallBridge.onCallEnded(any()) }
    }

    @Test
    fun `hangup resets mute state`() = runTest {
        telephone.muteTransmit(true)
        assertTrue(telephone.isTransmitMuted())

        telephone.hangup()

        assertFalse(telephone.isTransmitMuted())
    }

    @Test
    fun `hangup resets activeProfile to DEFAULT`() = runTest {
        // Start a call with non-default profile (link succeeds so call stays active)
        coEvery { mockTransport.establishLink(any()) } returns true
        telephone.call(ByteArray(32), Profile.SHQ)
        advanceUntilIdle()
        assertEquals(Profile.SHQ, telephone.activeProfile)

        telephone.hangup()

        assertEquals(Profile.DEFAULT, telephone.activeProfile)
    }

    // ===== Answer =====

    @Test
    fun `answer when not ringing returns false`() {
        val result = telephone.answer()

        assertFalse(result)
        // Should not send established signal since not in ringing state
        verify(exactly = 0) { mockTransport.sendSignal(Signalling.STATUS_ESTABLISHED) }
    }

    @Test
    fun `answer when ringing returns true and establishes call`() = runTest {
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()

        val result = telephone.answer()

        assertTrue(result)
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)
        verify { mockTransport.sendSignal(Signalling.STATUS_ESTABLISHED) }
        verify { mockCallBridge.onCallEstablished("abcd1234") }
    }

    @Test
    fun `answer sends profile preference before established`() = runTest {
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()

        telephone.answer()

        // Profile preference should be sent BEFORE STATUS_ESTABLISHED
        val expectedProfileSignal = Signalling.PREFERRED_PROFILE + Profile.DEFAULT.id
        verifyOrder {
            mockTransport.sendSignal(expectedProfileSignal)
            mockTransport.sendSignal(Signalling.STATUS_ESTABLISHED)
        }
    }

    // ===== prepareForAnswer (JIT state setup) =====

    @Test
    fun `prepareForAnswer sets state for answering`() {
        telephone.prepareForAnswer("abcd1234")
        // answer() should now succeed
        val result = telephone.answer()
        assertTrue(result)
        assertEquals(Signalling.STATUS_ESTABLISHED, telephone.callStatus)
    }

    @Test
    fun `prepareForAnswer does not activate ringtone or notify bridge`() {
        telephone.prepareForAnswer("abcd1234")
        // Should NOT trigger callBridge.onIncomingCall (that already happened via Python)
        verify(exactly = 0) { mockCallBridge.onIncomingCall(any()) }
    }

    @Test
    fun `prepareForAnswer when already in call is ignored`() = runTest {
        telephone.onIncomingCall("first1234")
        advanceUntilIdle()

        telephone.prepareForAnswer("second5678")
        // Should still be on first call
        assertEquals(Signalling.STATUS_RINGING, telephone.callStatus)
    }

    // ===== Incoming Call =====

    @Test
    fun `onIncomingCall sets status to RINGING`() = runTest {
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()
        assertEquals(Signalling.STATUS_RINGING, telephone.callStatus)
    }

    @Test
    fun `onIncomingCall notifies call bridge`() = runTest {
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()
        verify { mockCallBridge.onIncomingCall("abcd1234") }
    }

    @Test
    fun `onIncomingCall does not send RINGING to transport (Python handles it)`() = runTest {
        telephone.onIncomingCall("abcd1234")
        advanceUntilIdle()
        // Python call_manager sends STATUS_RINGING to remote before calling
        // Telephone.onIncomingCall(), so Kotlin should not send it again
        verify(exactly = 0) { mockTransport.sendSignal(Signalling.STATUS_RINGING) }
    }

    @Test
    fun `onIncomingCall when already in call signals BUSY`() = runTest {
        // First incoming call
        telephone.onIncomingCall("first1234")
        advanceUntilIdle()

        // Second incoming call while first is active
        telephone.onIncomingCall("second5678")
        advanceUntilIdle()

        // Should signal busy to second caller
        verify { mockTransport.sendSignal(Signalling.STATUS_BUSY) }
    }

    // ===== Signal Handling =====

    @Test
    fun `STATUS_AVAILABLE signal updates call status`() {
        signalCallback?.invoke(Signalling.STATUS_AVAILABLE)
        assertEquals(Signalling.STATUS_AVAILABLE, telephone.callStatus)
    }

    @Test
    fun `STATUS_RINGING signal updates call status`() = runTest {
        signalCallback?.invoke(Signalling.STATUS_RINGING)
        advanceUntilIdle()
        assertEquals(Signalling.STATUS_RINGING, telephone.callStatus)
    }

    @Test
    fun `STATUS_CONNECTING signal updates call status`() {
        // STATUS_CONNECTING triggers openPipelines() which requires JNI
        // Catch UnsatisfiedLinkError since codec creation can't run in unit tests
        try {
            signalCallback?.invoke(Signalling.STATUS_CONNECTING)
            assertEquals(Signalling.STATUS_CONNECTING, telephone.callStatus)
        } catch (e: UnsatisfiedLinkError) {
            // Status is set before openPipelines() is called, so this is expected
            // Pipeline creation tests require instrumented tests with actual device
            assertEquals(Signalling.STATUS_CONNECTING, telephone.callStatus)
        }
    }

    // ===== Profile Switch =====

    @Test
    fun `switchProfile when not established is ignored`() {
        telephone.switchProfile(Profile.HQ)

        // Should not send profile signal since not in established state
        verify(exactly = 0) { mockTransport.sendSignal(any()) }
    }

    @Test
    fun `switchProfile to same profile is ignored`() {
        // Default is MQ
        telephone.switchProfile(Profile.MQ)

        // Should not send anything since already MQ
        verify(exactly = 0) { mockTransport.sendSignal(any()) }
    }

    // ===== Ringtone Configuration =====

    @Test
    fun `setRingtone accepts null for default`() {
        telephone.setRingtone(null)
        // Should not throw
    }

    @Test
    fun `setRingtone accepts custom path`() {
        telephone.setRingtone("/path/to/custom.mp3")
        // Should not throw
    }

    @Test
    fun `setUseSystemRingtone true`() {
        telephone.setUseSystemRingtone(true)
        // Should not throw
    }

    @Test
    fun `setUseSystemRingtone false`() {
        telephone.setUseSystemRingtone(false)
        // Should not throw
    }

    // ===== Shutdown =====

    @Test
    fun `shutdown tears down link`() {
        telephone.shutdown()
        verify { mockTransport.teardownLink() }
    }

    @Test
    fun `shutdown is idempotent`() {
        telephone.shutdown()
        telephone.shutdown()
        // Second call should not throw
    }

    // ===== Signalling Constants Reference =====

    @Test
    fun `Signalling STATUS_BUSY is 0x00`() {
        assertEquals(0x00, Signalling.STATUS_BUSY)
    }

    @Test
    fun `Signalling STATUS_REJECTED is 0x01`() {
        assertEquals(0x01, Signalling.STATUS_REJECTED)
    }

    @Test
    fun `Signalling STATUS_CALLING is 0x02`() {
        assertEquals(0x02, Signalling.STATUS_CALLING)
    }

    @Test
    fun `Signalling STATUS_AVAILABLE is 0x03`() {
        assertEquals(0x03, Signalling.STATUS_AVAILABLE)
    }

    @Test
    fun `Signalling STATUS_RINGING is 0x04`() {
        assertEquals(0x04, Signalling.STATUS_RINGING)
    }

    @Test
    fun `Signalling STATUS_CONNECTING is 0x05`() {
        assertEquals(0x05, Signalling.STATUS_CONNECTING)
    }

    @Test
    fun `Signalling STATUS_ESTABLISHED is 0x06`() {
        assertEquals(0x06, Signalling.STATUS_ESTABLISHED)
    }

    @Test
    fun `Signalling PREFERRED_PROFILE is 0xFF`() {
        assertEquals(0xFF, Signalling.PREFERRED_PROFILE)
    }

    // ===== isCallActive logic =====

    @Test
    fun `isCallActive returns true when status is CALLING`() = runTest {
        coEvery { mockTransport.establishLink(any()) } coAnswers {
            // During link establishment, status should be CALLING
            assertTrue(telephone.isCallActive())
            false
        }

        telephone.call(ByteArray(32))
    }

    @Test
    fun `isCallActive returns true when status is RINGING`() = runTest {
        telephone.onIncomingCall("test1234")
        advanceUntilIdle()
        assertTrue(telephone.isCallActive())
    }

    @Test
    fun `isCallActive returns false after hangup`() = runTest {
        telephone.onIncomingCall("test1234")
        advanceUntilIdle()
        assertTrue(telephone.isCallActive())

        telephone.hangup()

        assertFalse(telephone.isCallActive())
    }
}
