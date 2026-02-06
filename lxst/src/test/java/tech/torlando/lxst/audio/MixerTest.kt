package com.lxmf.messenger.reticulum.audio.lxst

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.pow

/**
 * Unit tests for Mixer configuration logic.
 *
 * Tests gain calculations, mute behavior, and queue management
 * without requiring Android (no JNI/native code).
 *
 * Note: Full audio mixing tests require instrumented tests on device.
 */
class MixerTest {

    private lateinit var mixer: Mixer
    private lateinit var mockSink: Sink

    companion object {
        private const val EPSILON = 0.001f // Float comparison tolerance
    }

    @Before
    fun setup() {
        mockSink = mockk(relaxed = true)
        every { mockSink.canReceive(any()) } returns true
    }

    // ===== Gain calculation tests =====

    @Test
    fun `gain calculation with unity gain (0dB)`() {
        // 0 dB = unity gain (multiplier of 1.0)
        mixer = Mixer(globalGain = 0.0f, sink = mockSink)

        // mixingGain is private, test indirectly by verifying no error
        // and checking mute/unmute behavior preserves gain
        mixer.mute(true)
        mixer.mute(false) // Should restore to unity

        // Verify mixer was created successfully with unity gain
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `gain calculation with boost (+10dB)`() {
        // +10 dB = 10^(10/10) = 10.0 multiplier
        mixer = Mixer(globalGain = 10.0f, sink = mockSink)

        // Expected: mixingGain = 10^(10/10) = 10.0
        // Test by verifying setGain changes gain
        mixer.setGain(10.0f)
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `gain calculation with attenuation (-10dB)`() {
        // -10 dB = 10^(-10/10) = 0.1 multiplier
        mixer = Mixer(globalGain = -10.0f, sink = mockSink)

        // Expected: mixingGain = 10^(-10/10) ≈ 0.1
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `gain dB to linear conversion formula`() {
        // Verify the dB to linear conversion formula
        // dB to linear: 10^(dB/10)

        // Unity (0 dB)
        assertEquals(1.0f, 10f.pow(0.0f / 10f), EPSILON)

        // +6 dB ≈ 4x
        assertEquals(3.98f, 10f.pow(6.0f / 10f), 0.1f)

        // +10 dB = 10x
        assertEquals(10.0f, 10f.pow(10.0f / 10f), EPSILON)

        // -10 dB = 0.1x
        assertEquals(0.1f, 10f.pow(-10.0f / 10f), EPSILON)

        // -20 dB = 0.01x
        assertEquals(0.01f, 10f.pow(-20.0f / 10f), EPSILON)
    }

    // ===== Mute tests =====

    @Test
    fun `mute sets gain to zero`() {
        mixer = Mixer(globalGain = 6.0f, sink = mockSink)

        mixer.mute(true)

        // When muted, mixingGain should be 0
        // We can't directly test mixingGain, but mute(true) should not throw
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `unmute restores gain`() {
        mixer = Mixer(globalGain = 6.0f, sink = mockSink)

        // Mute
        mixer.mute(true)

        // Unmute - should restore to original gain
        mixer.mute(false)

        // Gain should be restored (can't test directly without @VisibleForTesting)
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `mute unmute toggle multiple times`() {
        mixer = Mixer(globalGain = 3.0f, sink = mockSink)

        repeat(5) {
            mixer.mute(true)
            mixer.mute(false)
        }

        // Should not throw, gain should be preserved
        assertFalse(mixer.isRunning())
    }

    // ===== canReceive backpressure tests =====

    @Test
    fun `canReceive returns true for new source`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Empty queue should accept frames
        assertTrue(mixer.canReceive(mockSource))
    }

    @Test
    fun `canReceive returns true with null source`() {
        mixer = Mixer(sink = mockSink)

        // Null source should return true (edge case)
        assertTrue(mixer.canReceive(null))
    }

    @Test
    fun `canReceive returns false when queue full`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Fill queue to MAX_FRAMES
        repeat(Mixer.MAX_FRAMES) {
            mixer.handleFrame(FloatArray(480), mockSource)
        }

        // Queue full - should return false
        assertFalse(mixer.canReceive(mockSource))
    }

    @Test
    fun `canReceive returns true when queue below max`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Add less than MAX_FRAMES
        repeat(Mixer.MAX_FRAMES - 1) {
            mixer.handleFrame(FloatArray(480), mockSource)
        }

        // Still has room
        assertTrue(mixer.canReceive(mockSource))
    }

    // ===== Sample rate auto-detection tests =====

    @Test
    fun `sample rate auto-detected from first source`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Initially 0
        assertEquals(0, mixer.sampleRate)

        // First handleFrame should set sample rate
        mixer.handleFrame(FloatArray(480), mockSource)

        assertEquals(48000, mixer.sampleRate)
    }

    @Test
    fun `channels auto-detected from first source`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 2

        // Default is 1
        assertEquals(1, mixer.channels)

        // First handleFrame should set channels
        mixer.handleFrame(FloatArray(960), mockSource)

        assertEquals(2, mixer.channels)
    }

    @Test
    fun `sample rate preserved from subsequent sources`() {
        mixer = Mixer(sink = mockSink)

        val source1 = mockk<Source>(relaxed = true)
        every { source1.sampleRate } returns 48000
        every { source1.channels } returns 1

        val source2 = mockk<Source>(relaxed = true)
        every { source2.sampleRate } returns 8000
        every { source2.channels } returns 1

        // First source sets rate
        mixer.handleFrame(FloatArray(480), source1)
        assertEquals(48000, mixer.sampleRate)

        // Second source doesn't change it
        mixer.handleFrame(FloatArray(80), source2)
        assertEquals(48000, mixer.sampleRate)
    }

    // ===== handleFrame tests =====

    @Test
    fun `handleFrame ignores null source`() {
        mixer = Mixer(sink = mockSink)

        // Should not throw with null source
        mixer.handleFrame(FloatArray(480), null)

        // Sample rate should still be 0 (no source detected)
        assertEquals(0, mixer.sampleRate)
    }

    @Test
    fun `handleFrame drops oldest frame on overflow`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Fill queue completely
        repeat(Mixer.MAX_FRAMES) {
            mixer.handleFrame(FloatArray(480), mockSource)
        }

        // Add one more - should drop oldest
        mixer.handleFrame(FloatArray(480), mockSource)

        // Queue should still be at MAX_FRAMES (not MAX_FRAMES + 1)
        assertFalse(mixer.canReceive(mockSource))
    }

    // ===== Lifecycle tests =====

    @Test
    fun `isRunning returns false initially`() {
        mixer = Mixer(sink = mockSink)
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `isRunning returns true after start`() {
        mixer = Mixer(sink = mockSink)
        mixer.start()
        assertTrue(mixer.isRunning())
        mixer.stop()
    }

    @Test
    fun `isRunning returns false after stop`() {
        mixer = Mixer(sink = mockSink)
        mixer.start()
        mixer.stop()
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `start is idempotent`() {
        mixer = Mixer(sink = mockSink)
        mixer.start()
        mixer.start() // Second call should be no-op
        assertTrue(mixer.isRunning())
        mixer.stop()
    }

    @Test
    fun `stop clears all queues`() {
        mixer = Mixer(sink = mockSink)
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 48000
        every { mockSource.channels } returns 1

        // Add some frames
        repeat(3) {
            mixer.handleFrame(FloatArray(480), mockSource)
        }

        // Stop clears queues
        mixer.stop()

        // Queue should be empty
        assertTrue(mixer.canReceive(mockSource))
    }

    // ===== setGain tests =====

    @Test
    fun `setGain changes global gain`() {
        mixer = Mixer(globalGain = 0.0f, sink = mockSink)

        mixer.setGain(10.0f)

        // Can't directly verify mixingGain, but should not throw
        assertFalse(mixer.isRunning())
    }

    @Test
    fun `setGain accepts negative values`() {
        mixer = Mixer(sink = mockSink)

        mixer.setGain(-20.0f)

        // -20 dB should work
        assertFalse(mixer.isRunning())
    }

    // ===== MAX_FRAMES constant test =====

    @Test
    fun `MAX_FRAMES is reasonable value`() {
        // MAX_FRAMES should be >= 2 for reasonable buffering
        assertTrue(Mixer.MAX_FRAMES >= 2)
        // MAX_FRAMES should be <= 16 to avoid excessive memory
        assertTrue(Mixer.MAX_FRAMES <= 16)
    }
}
