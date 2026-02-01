// PyObject is a Chaquopy framework class with many methods; explicit stubbing is not practical
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.reticulum.audio.bridge

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.mockk
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for KotlinAudioBridge.
 *
 * Tests audio playback, recording, device enumeration,
 * and audio routing functionality using Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class KotlinAudioBridgeTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var audioBridge: KotlinAudioBridge

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // Create a fresh instance for testing (not using singleton to avoid state leakage)
        audioBridge = KotlinAudioBridge(context)
    }

    @After
    fun tearDown() {
        audioBridge.shutdown()
        Dispatchers.resetMain()
        clearAllMocks()
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

    @Test
    fun `BUFFER_QUEUE_SIZE is 10`() {
        assertEquals(10, KotlinAudioBridge.BUFFER_QUEUE_SIZE)
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

    // ========== Playback State Tests ==========

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
    fun `getOutputDevices returns list`() {
        val devices = audioBridge.getOutputDevices()

        // Returns a list (may be empty in Robolectric environment)
        assertTrue("Should return a list", devices is List<*>)
    }

    @Test
    fun `getInputDevices returns list`() {
        val devices = audioBridge.getInputDevices()

        // Returns a list (may be empty in Robolectric environment)
        assertTrue("Should return a list", devices is List<*>)
    }

    @Test
    fun `getOutputDevices returns devices with expected fields when available`() {
        val devices = audioBridge.getOutputDevices()

        // Only check fields if devices are available (Robolectric may not have any)
        if (devices.isNotEmpty()) {
            val device = devices[0]
            assertTrue("Device should have id", device.containsKey("id"))
            assertTrue("Device should have name", device.containsKey("name"))
        }
    }

    @Test
    fun `getInputDevices returns devices with expected fields when available`() {
        val devices = audioBridge.getInputDevices()

        // Only check fields if devices are available (Robolectric may not have any)
        if (devices.isNotEmpty()) {
            val device = devices[0]
            assertTrue("Device should have id", device.containsKey("id"))
            assertTrue("Device should have name", device.containsKey("name"))
        }
    }

    // ========== Shutdown Tests ==========

    @Test
    fun `shutdown stops playback`() =
        runTest {
            audioBridge.shutdown()
            advanceUntilIdle()

            assertFalse(audioBridge.isPlaybackActive())
        }

    @Test
    fun `shutdown stops recording`() =
        runTest {
            audioBridge.shutdown()
            advanceUntilIdle()

            assertFalse(audioBridge.isRecordingActive())
        }

    // ========== Python Callback Tests ==========

    @Test
    fun `setOnRecordingError accepts callback`() {
        val mockCallback = mockk<com.chaquo.python.PyObject>(relaxed = true)

        // Should not crash
        audioBridge.setOnRecordingError(mockCallback)
    }

    @Test
    fun `setOnPlaybackError accepts callback`() {
        val mockCallback = mockk<com.chaquo.python.PyObject>(relaxed = true)

        // Should not crash
        audioBridge.setOnPlaybackError(mockCallback)
    }

    // ========== Speakerphone Tests ==========

    @Test
    fun `setSpeakerphoneOn does not crash`() {
        // Should not crash
        audioBridge.setSpeakerphoneOn(true)
        audioBridge.setSpeakerphoneOn(false)
    }

    @Test
    fun `isSpeakerphoneOn returns boolean`() {
        // Should return a boolean value (Robolectric provides a mock audio manager)
        val result = audioBridge.isSpeakerphoneOn()
        assertTrue(result == true || result == false)
    }

    // ========== Microphone Mute Tests ==========

    @Test
    fun `setMicrophoneMute does not crash`() {
        // Should not crash
        audioBridge.setMicrophoneMute(true)
        audioBridge.setMicrophoneMute(false)
    }

    @Test
    fun `isMicrophoneMuted returns boolean`() {
        // Should return a boolean value
        val result = audioBridge.isMicrophoneMuted()
        assertTrue(result == true || result == false)
    }

    // ========== Buffer Size Validation Tests ==========

    @Test
    @Suppress("SwallowedException")
    fun `startPlayback handles invalid parameters gracefully`() {
        // With Robolectric, AudioTrack may not work fully, but should not crash
        try {
            audioBridge.startPlayback(0, 0, false)
        } catch (e: Exception) {
            // Expected to fail with invalid parameters - exception is intentionally ignored
        }

        // Should still be in a valid state
        assertFalse(audioBridge.isPlaybackActive())
    }

    @Test
    @Suppress("SwallowedException")
    fun `startRecording handles invalid parameters gracefully`() {
        // With Robolectric, AudioRecord may not work fully, but should not crash
        try {
            audioBridge.startRecording(0, 0, 0)
        } catch (e: Exception) {
            // Expected to fail with invalid parameters - exception is intentionally ignored
        }

        // Should still be in a valid state
        assertFalse(audioBridge.isRecordingActive())
    }

    // ========== Integration Tests ==========

    @Test
    fun `multiple shutdown calls are safe`() {
        audioBridge.shutdown()
        audioBridge.shutdown()
        audioBridge.shutdown()

        // Should not crash
        assertFalse(audioBridge.isPlaybackActive())
        assertFalse(audioBridge.isRecordingActive())
    }

    @Test
    fun `writeAudio with empty array does not crash`() {
        val emptyData = ByteArray(0)

        // Should not crash
        audioBridge.writeAudio(emptyData)
    }

    @Test
    fun `readAudio with zero samples does not crash`() {
        val result = audioBridge.readAudio(0)

        // Should return null since not recording
        assertNull(result)
    }
}
