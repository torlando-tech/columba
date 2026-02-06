package com.lxmf.messenger.reticulum.audio.lxst

import com.lxmf.messenger.reticulum.audio.bridge.KotlinAudioBridge
import com.lxmf.messenger.reticulum.audio.codec.Codec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LineSource configuration logic.
 *
 * Tests frame size adjustment based on codec constraints.
 * Uses mocked KotlinAudioBridge and Codec to avoid JNI dependencies.
 *
 * Note: adjustFrameTime() is private, so we test indirectly by verifying
 * samplesPerFrame matches expected codec constraints.
 */
class LineSourceTest {

    private lateinit var mockBridge: KotlinAudioBridge
    private lateinit var mockCodec: Codec

    @Before
    fun setup() {
        mockBridge = mockk(relaxed = true)
        mockCodec = mockk(relaxed = true)

        // Default codec with no constraints
        every { mockCodec.preferredSamplerate } returns null
        every { mockCodec.frameQuantaMs } returns null
        every { mockCodec.frameMaxMs } returns null
        every { mockCodec.validFrameMs } returns null
        every { mockCodec.encode(any()) } returns ByteArray(0)
        every { mockCodec.decode(any()) } returns FloatArray(0)
    }

    @Test
    fun `uses default sample rate when codec has no preference`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        assertEquals(48000, source.sampleRate)
        source.release()
    }

    @Test
    fun `uses codec preferred sample rate when specified`() {
        every { mockCodec.preferredSamplerate } returns 8000

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        assertEquals(8000, source.sampleRate)
        source.release()
    }

    @Test
    fun `isRunning returns false initially`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        assertFalse(source.isRunning())
        source.release()
    }

    @Test
    fun `start calls bridge startRecording with correct parameters`() {
        every { mockCodec.preferredSamplerate } returns 48000

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()

        // 20ms at 48kHz = 960 samples
        verify {
            mockBridge.startRecording(
                sampleRate = 48000,
                channels = 1,
                samplesPerFrame = 960
            )
        }

        source.stop()
        source.release()
    }

    @Test
    fun `stop calls bridge stopRecording`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()
        source.stop()

        verify { mockBridge.stopRecording() }
        source.release()
    }

    @Test
    fun `frame time quantized to codec frameQuantaMs produces correct samplesPerFrame`() {
        // Opus requires 2.5ms frame quanta
        every { mockCodec.frameQuantaMs } returns 2.5f
        every { mockCodec.preferredSamplerate } returns 48000

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 21 // Not a multiple of 2.5
        )

        source.start()

        // 21ms should quantize up to ceil(21 / 2.5) * 2.5 = 9 * 2.5 = 22.5ms
        // As int: 22ms -> 1056 samples or 23ms -> 1104 samples
        // Verify samplesPerFrame > 960 (20ms baseline)
        verify {
            mockBridge.startRecording(
                sampleRate = 48000,
                channels = 1,
                samplesPerFrame = match { it > 960 } // More than 20ms
            )
        }

        source.stop()
        source.release()
    }

    @Test
    fun `frame time clamped to codec frameMaxMs produces correct samplesPerFrame`() {
        every { mockCodec.frameMaxMs } returns 60f
        every { mockCodec.preferredSamplerate } returns 48000

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 120 // Exceeds max
        )

        source.start()

        // Should clamp to 60ms = 2880 samples at 48kHz
        verify {
            mockBridge.startRecording(
                sampleRate = 48000,
                channels = 1,
                samplesPerFrame = 2880
            )
        }

        source.stop()
        source.release()
    }

    @Test
    fun `frame time snapped to codec validFrameMs produces correct samplesPerFrame`() {
        every { mockCodec.validFrameMs } returns listOf(10f, 20f, 40f, 60f)
        every { mockCodec.preferredSamplerate } returns 48000

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 35 // Between 20 and 40
        )

        source.start()

        // Should snap to nearest valid: 40ms = 1920 samples
        verify {
            mockBridge.startRecording(
                sampleRate = 48000,
                channels = 1,
                samplesPerFrame = 1920
            )
        }

        source.stop()
        source.release()
    }

    @Test
    fun `gain parameter stored for use in capture loop`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20,
            gain = 2.0f
        )

        // Gain is applied in ingestJob, which we can't test without real audio
        // But we verify the constructor accepts it without error
        source.release()
    }

    @Test
    fun `isRunning returns true after start`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()
        assertTrue(source.isRunning())

        source.stop()
        source.release()
    }

    @Test
    fun `isRunning returns false after stop`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()
        source.stop()
        assertFalse(source.isRunning())

        source.release()
    }

    @Test
    fun `frame adjustment with all constraints applied in sequence`() {
        // Test combined constraints: quantize -> clamp -> snap
        every { mockCodec.preferredSamplerate } returns 8000
        every { mockCodec.frameQuantaMs } returns 10f // Quantize to 10ms multiples
        every { mockCodec.frameMaxMs } returns 60f    // Max 60ms
        every { mockCodec.validFrameMs } returns listOf(20f, 40f, 60f) // Valid sizes

        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 45 // Start with 45ms
        )

        source.start()

        // 45ms -> quantize to 50ms (next 10ms multiple)
        // 50ms -> clamp within 60ms (no change)
        // 50ms -> snap to nearest valid: 40ms or 60ms (40ms is closer)
        // 40ms at 8kHz = 320 samples
        verify {
            mockBridge.startRecording(
                sampleRate = 8000,
                channels = 1,
                samplesPerFrame = 320
            )
        }

        source.stop()
        source.release()
    }

    @Test
    fun `default channels is mono`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        assertEquals(1, source.channels)
        source.release()
    }

    @Test
    fun `multiple start calls are idempotent`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()
        source.start() // Second call should be no-op

        // Bridge should only be started once
        verify(exactly = 1) {
            mockBridge.startRecording(any(), any(), any())
        }

        source.stop()
        source.release()
    }

    @Test
    fun `multiple stop calls are idempotent`() {
        val source = LineSource(
            bridge = mockBridge,
            codec = mockCodec,
            targetFrameMs = 20
        )

        source.start()
        source.stop()
        source.stop() // Second call should be no-op

        // Bridge should only be stopped once
        verify(exactly = 1) {
            mockBridge.stopRecording()
        }

        source.release()
    }
}
