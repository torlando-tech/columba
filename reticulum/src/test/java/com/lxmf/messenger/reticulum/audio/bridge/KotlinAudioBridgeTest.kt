package com.lxmf.messenger.reticulum.audio.bridge

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for KotlinAudioBridge.
 *
 * Tests audio playback, recording, device enumeration,
 * and audio routing functionality.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KotlinAudioBridgeTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockContext: Context
    private lateinit var mockAudioManager: AudioManager
    private lateinit var mockAudioTrack: AudioTrack
    private lateinit var mockAudioRecord: AudioRecord
    private lateinit var audioBridge: KotlinAudioBridge

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mockk(relaxed = true)
        mockAudioManager = mockk(relaxed = true)
        mockAudioTrack = mockk(relaxed = true)
        mockAudioRecord = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager
        every { mockContext.applicationContext } returns mockContext

        // Mock static AudioTrack.getMinBufferSize
        mockkStatic(AudioTrack::class)
        every {
            AudioTrack.getMinBufferSize(
                any(),
                any(),
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } returns 1024

        // Mock static AudioRecord.getMinBufferSize
        mockkStatic(AudioRecord::class)
        every {
            AudioRecord.getMinBufferSize(
                any(),
                any(),
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } returns 1024

        audioBridge = KotlinAudioBridge.getInstance(mockContext)
    }

    @After
    fun tearDown() {
        audioBridge.shutdown()
        Dispatchers.resetMain()
        unmockkAll()
        clearAllMocks()
    }

    // ========== Singleton Tests ==========

    @Test
    fun `getInstance returns same instance for same context`() {
        val instance1 = KotlinAudioBridge.getInstance(mockContext)
        val instance2 = KotlinAudioBridge.getInstance(mockContext)

        assertEquals(instance1, instance2)
    }

    // ========== Constants Tests ==========

    @Test
    fun `DEFAULT_SAMPLE_RATE is 48000`() {
        assertEquals(48000, KotlinAudioBridge.DEFAULT_SAMPLE_RATE)
    }

    @Test
    fun `DEFAULT_CHANNELS is 1`() {
        assertEquals(1, KotlinAudioBridge.DEFAULT_CHANNELS)
    }

    @Test
    fun `DEFAULT_FRAME_SIZE_MS is 20`() {
        assertEquals(20, KotlinAudioBridge.DEFAULT_FRAME_SIZE_MS)
    }

    // ========== Initial State Tests ==========

    @Test
    fun `isPlaybackActive returns false initially`() {
        assertFalse(audioBridge.isPlaybackActive())
    }

    @Test
    fun `isRecordingActive returns false initially`() {
        assertFalse(audioBridge.isRecordingActive())
    }

    // ========== Speaker Phone Tests ==========

    @Test
    fun `setSpeakerphoneOn enables speakerphone`() {
        audioBridge.setSpeakerphoneOn(true)

        verify { mockAudioManager.isSpeakerphoneOn = true }
    }

    @Test
    fun `setSpeakerphoneOn disables speakerphone`() {
        audioBridge.setSpeakerphoneOn(false)

        verify { mockAudioManager.isSpeakerphoneOn = false }
    }

    @Test
    fun `isSpeakerphoneOn returns AudioManager state`() {
        every { mockAudioManager.isSpeakerphoneOn } returns true

        assertTrue(audioBridge.isSpeakerphoneOn())
    }

    // ========== Microphone Mute Tests ==========

    @Test
    fun `setMicrophoneMute mutes microphone`() {
        audioBridge.setMicrophoneMute(true)

        verify { mockAudioManager.isMicrophoneMute = true }
    }

    @Test
    fun `setMicrophoneMute unmutes microphone`() {
        audioBridge.setMicrophoneMute(false)

        verify { mockAudioManager.isMicrophoneMute = false }
    }

    @Test
    fun `isMicrophoneMuted returns AudioManager state`() {
        every { mockAudioManager.isMicrophoneMute } returns true

        assertTrue(audioBridge.isMicrophoneMuted())
    }

    // ========== Playback State Tests ==========

    @Test
    fun `startPlayback with already playing does not restart`() {
        // Simulate playback already started
        audioBridge.startPlayback(48000, 1, false)

        // Try to start again (should log warning, not crash)
        audioBridge.startPlayback(48000, 1, false)

        // No assertion needed - just verify no crash
    }

    @Test
    fun `stopPlayback when not playing does nothing`() {
        // Should not crash when stopping without starting
        audioBridge.stopPlayback()

        assertFalse(audioBridge.isPlaybackActive())
    }

    @Test
    fun `writeAudio when not playing does nothing`() {
        val testData = ByteArray(100) { it.toByte() }

        // Should not crash
        audioBridge.writeAudio(testData)
    }

    // ========== Recording State Tests ==========

    @Test
    fun `startRecording with already recording does not restart`() {
        // Try to start twice (second should log warning)
        audioBridge.startRecording(48000, 1, 960)
        audioBridge.startRecording(48000, 1, 960)

        // No assertion needed - just verify no crash
    }

    @Test
    fun `stopRecording when not recording does nothing`() {
        // Should not crash when stopping without starting
        audioBridge.stopRecording()

        assertFalse(audioBridge.isRecordingActive())
    }

    @Test
    fun `readAudio when not recording returns null`() {
        val result = audioBridge.readAudio(960)

        assertNull(result)
    }

    // ========== Device Enumeration Tests ==========

    @Test
    fun `getOutputDevices returns fallback for older API`() {
        // For older API, should return default device
        val devices = audioBridge.getOutputDevices()

        assertTrue(devices.isNotEmpty())
        assertTrue(devices[0].containsKey("id"))
        assertTrue(devices[0].containsKey("name"))
    }

    @Test
    fun `getInputDevices returns fallback for older API`() {
        val devices = audioBridge.getInputDevices()

        assertTrue(devices.isNotEmpty())
        assertTrue(devices[0].containsKey("id"))
        assertTrue(devices[0].containsKey("name"))
    }

    // ========== Audio Mode Tests ==========

    @Test
    fun `startPlayback sets audio mode to IN_COMMUNICATION`() {
        // Mock AudioTrack constructor
        mockkConstructor(AudioTrack.Builder::class)
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } returns mockk(relaxed = true) {
            every { setAudioFormat(any()) } returns this
            every { setBufferSizeInBytes(any()) } returns this
            every { setTransferMode(any()) } returns this
            every { setPerformanceMode(any()) } returns this
            every { build() } returns mockAudioTrack
        }

        audioBridge.startPlayback(48000, 1, false)

        verify { mockAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    @Test
    fun `stopPlayback resets audio mode to NORMAL`() {
        // First start playback
        mockkConstructor(AudioTrack.Builder::class)
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } returns mockk(relaxed = true) {
            every { setAudioFormat(any()) } returns this
            every { setBufferSizeInBytes(any()) } returns this
            every { setTransferMode(any()) } returns this
            every { setPerformanceMode(any()) } returns this
            every { build() } returns mockAudioTrack
        }

        audioBridge.startPlayback(48000, 1, false)
        audioBridge.stopPlayback()

        verify { mockAudioManager.mode = AudioManager.MODE_NORMAL }
    }

    // ========== Buffer Size Validation Tests ==========

    @Test
    fun `startPlayback handles invalid buffer size`() {
        every {
            AudioTrack.getMinBufferSize(
                any(),
                any(),
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } returns AudioTrack.ERROR_BAD_VALUE

        // Should not crash, just log error
        audioBridge.startPlayback(48000, 1, false)

        assertFalse(audioBridge.isPlaybackActive())
    }

    @Test
    fun `startRecording handles invalid buffer size`() {
        every {
            AudioRecord.getMinBufferSize(
                any(),
                any(),
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } returns AudioRecord.ERROR_BAD_VALUE

        // Should not crash, just log error
        audioBridge.startRecording(48000, 1, 960)

        assertFalse(audioBridge.isRecordingActive())
    }

    // ========== Channel Configuration Tests ==========

    @Test
    fun `startPlayback with mono uses CHANNEL_OUT_MONO`() {
        audioBridge.startPlayback(48000, 1, false)

        verify {
            AudioTrack.getMinBufferSize(
                any(),
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }
    }

    @Test
    fun `startPlayback with stereo uses CHANNEL_OUT_STEREO`() {
        audioBridge.startPlayback(48000, 2, false)

        verify {
            AudioTrack.getMinBufferSize(
                any(),
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }
    }

    @Test
    fun `startRecording with mono uses CHANNEL_IN_MONO`() {
        audioBridge.startRecording(48000, 1, 960)

        verify {
            AudioRecord.getMinBufferSize(
                any(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }
    }

    @Test
    fun `startRecording with stereo uses CHANNEL_IN_STEREO`() {
        audioBridge.startRecording(48000, 2, 960)

        verify {
            AudioRecord.getMinBufferSize(
                any(),
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }
    }

    // ========== Shutdown Tests ==========

    @Test
    fun `shutdown stops playback`() = runTest {
        audioBridge.shutdown()
        advanceUntilIdle()

        assertFalse(audioBridge.isPlaybackActive())
    }

    @Test
    fun `shutdown stops recording`() = runTest {
        audioBridge.shutdown()
        advanceUntilIdle()

        assertFalse(audioBridge.isRecordingActive())
    }

    // ========== Python Callback Tests ==========

    @Test
    fun `setOnRecordingError sets callback`() {
        val mockCallback = mockk<com.chaquo.python.PyObject>(relaxed = true)

        // Should not crash
        audioBridge.setOnRecordingError(mockCallback)
    }

    @Test
    fun `setOnPlaybackError sets callback`() {
        val mockCallback = mockk<com.chaquo.python.PyObject>(relaxed = true)

        // Should not crash
        audioBridge.setOnPlaybackError(mockCallback)
    }

    // ========== Sample Rate Tests ==========

    @Test
    fun `startPlayback uses provided sample rate`() {
        val sampleRate = 44100

        audioBridge.startPlayback(sampleRate, 1, false)

        verify {
            AudioTrack.getMinBufferSize(
                sampleRate,
                any(),
                any(),
            )
        }
    }

    @Test
    fun `startRecording uses provided sample rate`() {
        val sampleRate = 44100

        audioBridge.startRecording(sampleRate, 1, 960)

        verify {
            AudioRecord.getMinBufferSize(
                sampleRate,
                any(),
                any(),
            )
        }
    }
}
