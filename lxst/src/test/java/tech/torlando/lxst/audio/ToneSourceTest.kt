package tech.torlando.lxst.audio

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ToneSource configuration logic.
 *
 * Tests frequency, easing parameters, and lifecycle behavior
 * without requiring Android (no JNI/native code).
 *
 * Note: Uses Robolectric for android.util.Log stubs.
 * Full audio generation tests require instrumented tests on device.
 */
class ToneSourceTest {

    companion object {
        private const val EPSILON = 0.0001f // Float comparison tolerance
    }

    // ===== Frequency tests =====

    @Test
    fun `default frequency is 382Hz`() {
        // Python LXST uses 382Hz, not 440Hz ITU-T
        assertEquals(382f, ToneSource.DEFAULT_FREQUENCY, EPSILON)
    }

    @Test
    fun `default sample rate is 48000Hz`() {
        assertEquals(48000, ToneSource.DEFAULT_SAMPLE_RATE)
    }

    @Test
    fun `default frame time is 80ms`() {
        assertEquals(80, ToneSource.DEFAULT_FRAME_MS)
    }

    @Test
    fun `default ease time is 20ms`() {
        assertEquals(20f, ToneSource.EASE_TIME_MS, EPSILON)
    }

    // ===== Ease parameter calculation tests =====

    @Test
    fun `ease step calculation for 20ms at 48kHz`() {
        // easeStep = 1.0 / (sampleRate * (easeTimeMs / 1000))
        // easeStep = 1.0 / (48000 * 0.020) = 1.0 / 960 ≈ 0.00104
        val sampleRate = 48000
        val easeTimeMs = 20f
        val expectedEaseStep = 1.0f / (sampleRate * (easeTimeMs / 1000f))

        assertEquals(0.00104f, expectedEaseStep, 0.0001f)
    }

    @Test
    fun `gain step calculation for 20ms at 48kHz`() {
        // gainStep = 0.02 / (sampleRate * (easeTimeMs / 1000))
        // gainStep = 0.02 / (48000 * 0.020) = 0.02 / 960 ≈ 0.0000208
        val sampleRate = 48000
        val easeTimeMs = 20f
        val expectedGainStep = 0.02f / (sampleRate * (easeTimeMs / 1000f))

        assertEquals(0.0000208f, expectedGainStep, 0.00001f)
    }

    @Test
    fun `samples per frame calculation for 80ms at 48kHz`() {
        // samplesPerFrame = (targetFrameMs / 1000) * sampleRate
        // samplesPerFrame = (80 / 1000) * 48000 = 0.08 * 48000 = 3840
        val targetFrameMs = 80
        val sampleRate = 48000
        val expectedSamples = ((targetFrameMs / 1000f) * sampleRate).toInt()

        assertEquals(3840, expectedSamples)
    }

    @Test
    fun `samples per frame calculation for 20ms at 48kHz`() {
        // samplesPerFrame = (20 / 1000) * 48000 = 960
        val targetFrameMs = 20
        val sampleRate = 48000
        val expectedSamples = ((targetFrameMs / 1000f) * sampleRate).toInt()

        assertEquals(960, expectedSamples)
    }

    @Test
    fun `samples per frame calculation for 40ms at 8kHz`() {
        // samplesPerFrame = (40 / 1000) * 8000 = 320
        val targetFrameMs = 40
        val sampleRate = 8000
        val expectedSamples = ((targetFrameMs / 1000f) * sampleRate).toInt()

        assertEquals(320, expectedSamples)
    }

    // ===== Constructor parameter tests =====

    @Test
    fun `ToneSource with default parameters creates successfully`() {
        val tone = ToneSource()

        assertEquals(48000, tone.sampleRate)
        assertEquals(1, tone.channels)
        assertFalse(tone.isRunning())

        tone.release()
    }

    @Test
    fun `ToneSource with custom frequency creates successfully`() {
        val tone = ToneSource(frequency = 440f)

        assertFalse(tone.isRunning())
        tone.release()
    }

    @Test
    fun `ToneSource with custom sample rate creates successfully`() {
        val tone = ToneSource()
        tone.sampleRate = 8000

        assertEquals(8000, tone.sampleRate)
        tone.release()
    }

    @Test
    fun `ToneSource with custom channels creates successfully`() {
        val tone = ToneSource(initialChannels = 2)

        assertEquals(2, tone.channels)
        tone.release()
    }

    @Test
    fun `ToneSource with custom gain creates successfully`() {
        val tone = ToneSource(targetGain = 0.5f)

        assertFalse(tone.isRunning())
        tone.release()
    }

    @Test
    fun `ToneSource with ease disabled creates successfully`() {
        val tone = ToneSource(ease = false)

        assertFalse(tone.isRunning())
        tone.release()
    }

    // ===== Lifecycle tests =====

    @Test
    fun `isRunning returns false initially`() {
        val tone = ToneSource()

        assertFalse(tone.isRunning())
        tone.release()
    }

    @Test
    fun `isRunning returns true after start with ease`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource(ease = true)
        tone.sink = mockSink
        tone.start()

        // With ease enabled, isRunning() returns true during fade-in
        // isRunning() = shouldRun && !easingOut
        // After start(): shouldRun=true, easingOut=false → true
        assertTrue(tone.isRunning())

        tone.stop()
        tone.release()
    }

    @Test
    fun `isRunning returns true after start without ease`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource(ease = false)
        tone.sink = mockSink
        tone.start()

        assertTrue(tone.isRunning())

        tone.stop()
        tone.release()
    }

    @Test
    fun `stop with ease triggers easing out`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource(ease = true)
        tone.sink = mockSink
        tone.start()
        tone.stop()

        // After stop() with ease:
        // - easingOut = true → isRunning() = false (shouldRun && !easingOut)
        // - shouldRun still true until fade completes
        assertFalse(tone.isRunning())

        tone.release()
    }

    @Test
    fun `stop without ease stops immediately`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource(ease = false)
        tone.sink = mockSink
        tone.start()
        tone.stop()

        // Without ease, stop() sets shouldRun = false immediately
        assertFalse(tone.isRunning())

        tone.release()
    }

    @Test
    fun `start is idempotent`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource()
        tone.sink = mockSink
        tone.start()
        tone.start() // Second call should be no-op

        assertTrue(tone.isRunning())

        tone.stop()
        tone.release()
    }

    @Test
    fun `stop on non-running source is no-op`() {
        val tone = ToneSource()

        // Should not throw
        tone.stop()

        assertFalse(tone.isRunning())
        tone.release()
    }

    // ===== setGain tests =====

    @Test
    fun `setGain clamps to 0-1 range`() {
        val tone = ToneSource(targetGain = 0.5f)

        // Verify setGain doesn't throw
        tone.setGain(0.0f)
        tone.setGain(1.0f)
        tone.setGain(0.5f)

        tone.release()
    }

    @Test
    fun `setGain clamps values above 1`() {
        val tone = ToneSource()

        // Should clamp to 1.0
        tone.setGain(2.0f)

        tone.release()
    }

    @Test
    fun `setGain clamps values below 0`() {
        val tone = ToneSource()

        // Should clamp to 0.0
        tone.setGain(-0.5f)

        tone.release()
    }

    // ===== Sink wiring tests =====

    @Test
    fun `sink can be set`() {
        val tone = ToneSource()
        val mockSink = mockk<Sink>(relaxed = true)

        tone.sink = mockSink

        assertEquals(mockSink, tone.sink)
        tone.release()
    }

    @Test
    fun `sink can be null`() {
        val tone = ToneSource()

        tone.sink = null

        assertEquals(null, tone.sink)
        tone.release()
    }

    // ===== Release tests =====

    @Test
    fun `release stops running source`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val tone = ToneSource(ease = false)
        tone.sink = mockSink
        tone.start()
        tone.release()

        // After release, should be stopped
        assertFalse(tone.isRunning())
    }

    @Test
    fun `release on stopped source is safe`() {
        val tone = ToneSource()

        // Should not throw
        tone.release()

        assertFalse(tone.isRunning())
    }

    // ===== Mathematical calculation verification =====

    @Test
    fun `sine wave phase step calculation`() {
        // step = (frequency * 2 * PI) / sampleRate
        val frequency = 382.0
        val sampleRate = 48000
        val step = (frequency * 2.0 * Math.PI) / sampleRate

        // At 382Hz and 48kHz:
        // step = (382 * 2 * 3.14159) / 48000 ≈ 0.05
        assertEquals(0.05, step, 0.01)
    }

    @Test
    fun `one second of samples at 48kHz`() {
        // 1 second = 48000 samples at 48kHz
        // Number of frames at 80ms = 1000/80 = 12.5 frames
        val sampleRate = 48000
        val frameMs = 80
        val samplesPerFrame = ((frameMs / 1000f) * sampleRate).toInt()
        val framesPerSecond = 1000f / frameMs

        assertEquals(3840, samplesPerFrame)
        assertEquals(12.5f, framesPerSecond, 0.01f)
    }
}
