package com.lxmf.messenger.service.coordinator

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AudioCoordinator.
 *
 * Tests audio focus management, wake lock handling,
 * and speaker/microphone routing.
 */
class AudioCoordinatorTest {
    private lateinit var mockContext: Context
    private lateinit var mockAudioManager: AudioManager
    private lateinit var mockPowerManager: PowerManager
    private lateinit var mockCpuWakeLock: PowerManager.WakeLock
    private lateinit var mockProximityWakeLock: PowerManager.WakeLock
    private lateinit var audioCoordinator: AudioCoordinator

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockAudioManager = mockk(relaxed = true)
        mockPowerManager = mockk(relaxed = true)
        mockCpuWakeLock = mockk(relaxed = true)
        mockProximityWakeLock = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager
        every { mockContext.getSystemService(Context.POWER_SERVICE) } returns mockPowerManager

        // Default: wake locks are not held
        every { mockCpuWakeLock.isHeld } returns false
        every { mockProximityWakeLock.isHeld } returns false

        // Proximity wake lock is supported
        every { mockPowerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns true
        every {
            mockPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                any(),
            )
        } returns mockCpuWakeLock
        every {
            mockPowerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                any(),
            )
        } returns mockProximityWakeLock

        audioCoordinator = AudioCoordinator(mockContext)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== startCall Tests ==========

    @Test
    fun `startCall requests audio focus`() {
        every { mockAudioManager.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()

        verify { mockAudioManager.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) }
    }

    @Test
    fun `startCall sets audio mode to IN_COMMUNICATION`() {
        audioCoordinator.startCall()

        verify { mockAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    @Test
    fun `startCall acquires CPU wake lock`() {
        audioCoordinator.startCall()

        verify { mockCpuWakeLock.acquire(any<Long>()) }
    }

    @Test
    fun `startCall acquires proximity wake lock`() {
        audioCoordinator.startCall()

        verify { mockProximityWakeLock.acquire(any<Long>()) }
    }

    @Test
    fun `startCall does not acquire proximity wake lock when unsupported`() {
        every { mockPowerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } returns false

        audioCoordinator.startCall()

        verify(exactly = 0) { mockProximityWakeLock.acquire(any<Long>()) }
    }

    // ========== stopCall Tests ==========

    @Test
    fun `stopCall abandons audio focus`() {
        every { mockAudioManager.abandonAudioFocus(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.stopCall()

        verify { mockAudioManager.abandonAudioFocus(any()) }
    }

    @Test
    fun `stopCall restores original audio mode`() {
        every { mockAudioManager.mode } returns AudioManager.MODE_NORMAL

        audioCoordinator.startCall()
        audioCoordinator.stopCall()

        verifyOrder {
            mockAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            mockAudioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    @Test
    fun `stopCall releases CPU wake lock when held`() {
        every { mockCpuWakeLock.isHeld } returns true

        audioCoordinator.startCall()
        audioCoordinator.stopCall()

        verify { mockCpuWakeLock.release() }
    }

    @Test
    fun `stopCall releases proximity wake lock when held`() {
        every { mockProximityWakeLock.isHeld } returns true

        audioCoordinator.startCall()
        audioCoordinator.stopCall()

        verify { mockProximityWakeLock.release() }
    }

    @Test
    fun `stopCall does not release wake lock when not held`() {
        every { mockCpuWakeLock.isHeld } returns false
        every { mockProximityWakeLock.isHeld } returns false

        audioCoordinator.stopCall()

        verify(exactly = 0) { mockCpuWakeLock.release() }
        verify(exactly = 0) { mockProximityWakeLock.release() }
    }

    // ========== setSpeakerphoneOn Tests ==========

    @Test
    fun `setSpeakerphoneOn enables speakerphone`() {
        audioCoordinator.setSpeakerphoneOn(true)

        verify { mockAudioManager.isSpeakerphoneOn = true }
    }

    @Test
    fun `setSpeakerphoneOn disables speakerphone`() {
        audioCoordinator.setSpeakerphoneOn(false)

        verify { mockAudioManager.isSpeakerphoneOn = false }
    }

    @Test
    fun `setSpeakerphoneOn releases proximity wake lock when enabling speaker`() {
        every { mockProximityWakeLock.isHeld } returns true

        audioCoordinator.startCall()
        audioCoordinator.setSpeakerphoneOn(true)

        verify { mockProximityWakeLock.release() }
    }

    @Test
    fun `setSpeakerphoneOn acquires proximity wake lock when disabling speaker`() {
        // First enable speakerphone to release lock
        audioCoordinator.startCall()
        audioCoordinator.setSpeakerphoneOn(true)

        // Then disable to re-acquire
        audioCoordinator.setSpeakerphoneOn(false)

        // Verify proximity wake lock is acquired (second call after startCall)
        verify(atLeast = 1) { mockProximityWakeLock.acquire(any<Long>()) }
    }

    // ========== setMicrophoneMute Tests ==========

    @Test
    fun `setMicrophoneMute mutes microphone`() {
        audioCoordinator.setMicrophoneMute(true)

        verify { mockAudioManager.isMicrophoneMute = true }
    }

    @Test
    fun `setMicrophoneMute unmutes microphone`() {
        audioCoordinator.setMicrophoneMute(false)

        verify { mockAudioManager.isMicrophoneMute = false }
    }

    // ========== hasAudioFocus Tests ==========

    @Test
    fun `hasAudioFocus returns true when focus granted`() {
        every { mockAudioManager.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()

        assertTrue(audioCoordinator.hasAudioFocus())
    }

    @Test
    fun `hasAudioFocus returns false when focus not granted`() {
        every { mockAudioManager.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns AudioManager.AUDIOFOCUS_REQUEST_FAILED

        audioCoordinator.startCall()

        assertFalse(audioCoordinator.hasAudioFocus())
    }

    @Test
    fun `hasAudioFocus returns false initially`() {
        assertFalse(audioCoordinator.hasAudioFocus())
    }

    @Test
    fun `hasAudioFocus returns false after stopCall`() {
        every { mockAudioManager.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()
        audioCoordinator.stopCall()

        assertFalse(audioCoordinator.hasAudioFocus())
    }

    // ========== Audio Focus Listener Tests ==========

    @Test
    fun `audio focus listener updates hasAudioFocus on GAIN`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            mockAudioManager.requestAudioFocus(
                capture(listenerSlot),
                any(),
                any(),
            )
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()
        assertTrue(audioCoordinator.hasAudioFocus())

        // Simulate focus loss
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(audioCoordinator.hasAudioFocus())

        // Simulate focus regain
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(audioCoordinator.hasAudioFocus())
    }

    @Test
    fun `audio focus listener updates hasAudioFocus on LOSS`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            mockAudioManager.requestAudioFocus(
                capture(listenerSlot),
                any(),
                any(),
            )
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()
        assertTrue(audioCoordinator.hasAudioFocus())

        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(audioCoordinator.hasAudioFocus())
    }

    @Test
    fun `audio focus listener updates hasAudioFocus on LOSS_TRANSIENT`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            mockAudioManager.requestAudioFocus(
                capture(listenerSlot),
                any(),
                any(),
            )
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        audioCoordinator.startCall()
        assertTrue(audioCoordinator.hasAudioFocus())

        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(audioCoordinator.hasAudioFocus())
    }

    // ========== Wake Lock Edge Cases ==========

    @Test
    fun `does not acquire CPU wake lock if already held`() {
        every { mockCpuWakeLock.isHeld } returns true

        audioCoordinator.startCall()

        verify(exactly = 0) { mockCpuWakeLock.acquire(any<Long>()) }
    }

    @Test
    fun `does not acquire proximity wake lock if already held`() {
        every { mockProximityWakeLock.isHeld } returns true

        audioCoordinator.startCall()

        verify(exactly = 0) { mockProximityWakeLock.acquire(any<Long>()) }
    }
}
