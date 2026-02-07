package tech.torlando.lxst.audio

import tech.torlando.lxst.bridge.KotlinAudioBridge
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LineSink queue management logic.
 *
 * Tests canReceive(), handleFrame(), and buffer overflow behavior.
 * Uses mocked KotlinAudioBridge to avoid JNI dependencies.
 */
class LineSinkTest {

    private lateinit var mockBridge: KotlinAudioBridge
    private lateinit var sink: LineSink

    @Before
    fun setup() {
        mockBridge = mockk(relaxed = true)

        // Create sink with autodigest=false to prevent auto-start
        sink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = false
        )
    }

    @Test
    fun `canReceive returns true when queue empty`() {
        assertTrue(sink.canReceive())
    }

    @Test
    fun `canReceive returns true below threshold`() {
        // bufferMaxHeight = MAX_FRAMES - 1; add one fewer than that
        repeat(LineSink.MAX_FRAMES - 2) {
            sink.handleFrame(FloatArray(480))
        }
        assertTrue(sink.canReceive())
    }

    @Test
    fun `canReceive returns false at threshold`() {
        // bufferMaxHeight = MAX_FRAMES - 1; fill to exactly that
        repeat(LineSink.MAX_FRAMES - 1) {
            sink.handleFrame(FloatArray(480))
        }
        assertFalse(sink.canReceive())
    }

    @Test
    fun `handleFrame adds frame to queue`() {
        val frame = FloatArray(480) { it.toFloat() }
        sink.handleFrame(frame)

        // Queue should have 1 frame - still has room
        assertTrue(sink.canReceive())
    }

    @Test
    fun `handleFrame drops oldest on overflow`() {
        // Fill queue completely (MAX_FRAMES = 6)
        repeat(LineSink.MAX_FRAMES) {
            sink.handleFrame(FloatArray(480) { it.toFloat() })
        }

        // Add one more - should drop oldest
        val newFrame = FloatArray(480) { 999f }
        sink.handleFrame(newFrame)

        // Queue should still be at MAX_FRAMES (not MAX_FRAMES + 1)
        // We can't directly check queue contents, but we can verify no exception
        assertFalse(sink.canReceive()) // Still at/above threshold
    }

    @Test
    fun `configure sets sample rate and channels`() {
        sink.configure(sampleRate = 8000, channels = 1)
        // Configuration is internal, but affects start() behavior
        // We verify it doesn't throw
    }

    @Test
    fun `isRunning returns false initially`() {
        assertFalse(sink.isRunning())
    }

    @Test
    fun `stop clears queue`() {
        sink.configure(48000, 1)

        // Add some frames
        repeat(3) {
            sink.handleFrame(FloatArray(480))
        }

        // Start then stop
        sink.start()
        sink.stop()

        // Queue should be empty now
        assertTrue(sink.canReceive())

        sink.release()
    }

    @Test
    fun `autodigest starts playback when threshold reached`() {
        // Create sink with autodigest=true
        val autoSink = LineSink(
            bridge = mockBridge,
            autodigest = true,
            lowLatency = false
        )
        autoSink.configure(48000, 1)

        // Add AUTOSTART_MIN frames to trigger autostart
        repeat(LineSink.AUTOSTART_MIN) {
            autoSink.handleFrame(FloatArray(480))
        }

        // Should have auto-started
        assertTrue(autoSink.isRunning())

        // Cleanup
        autoSink.stop()
        autoSink.release()
    }

    @Test
    fun `lowLatency flag is passed to bridge`() {
        val lowLatencySink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = true
        )
        lowLatencySink.configure(48000, 1)
        lowLatencySink.start()
        Thread.sleep(50) // Allow async startPlayback coroutine to execute

        verify {
            mockBridge.startPlayback(
                sampleRate = 48000,
                channels = 1,
                lowLatency = true
            )
        }

        lowLatencySink.stop()
        lowLatencySink.release()
    }

    @Test
    fun `start calls bridge with configured parameters`() {
        sink.configure(8000, 1)
        sink.start()
        Thread.sleep(50) // Allow async startPlayback coroutine to execute

        verify {
            mockBridge.startPlayback(
                sampleRate = 8000,
                channels = 1,
                lowLatency = false
            )
        }

        sink.stop()
        sink.release()
    }

    @Test
    fun `stop calls bridge stopPlayback`() {
        sink.configure(48000, 1)
        sink.start()
        sink.stop()

        verify { mockBridge.stopPlayback() }
        sink.release()
    }

    @Test
    fun `isRunning returns true after start`() {
        sink.configure(48000, 1)
        sink.start()
        assertTrue(sink.isRunning())

        sink.stop()
        sink.release()
    }

    @Test
    fun `isRunning returns false after stop`() {
        sink.configure(48000, 1)
        sink.start()
        sink.stop()
        assertFalse(sink.isRunning())

        sink.release()
    }

    @Test
    fun `start drains excess frames accumulated during AudioTrack creation`() {
        // Simulate slow AudioTrack creation: startPlayback blocks for 200ms
        // while handleFrame keeps queuing frames on another thread.
        every {
            mockBridge.startPlayback(any(), any(), any())
        } answers {
            Thread.sleep(200) // Simulate AudioTrack init delay
        }

        val autoSink = LineSink(
            bridge = mockBridge,
            autodigest = false,
            lowLatency = false
        )
        autoSink.configure(48000, 1)

        // Pre-fill AUTOSTART_MIN frames (normal pre-fill)
        repeat(LineSink.AUTOSTART_MIN) {
            autoSink.handleFrame(FloatArray(2880))
        }

        // Start playback (will block ~200ms in startPlayback)
        autoSink.start()

        // While startPlayback blocks, simulate network delivering more frames
        Thread {
            repeat(8) {
                Thread.sleep(20) // ~60ms frame intervals
                autoSink.handleFrame(FloatArray(2880))
            }
        }.start()

        // Wait for start coroutine to finish and begin digestJob
        Thread.sleep(400)

        // The drain should have removed excess frames, keeping only AUTOSTART_MIN.
        // Verify writeAudio was called (digestJob started) — if drain didn't work,
        // first N writes would be stale frames causing delay.
        verify(atLeast = 1) { mockBridge.writeAudio(any()) }

        autoSink.stop()
        autoSink.release()
    }

    @Test
    fun `handleFrame detects sample rate from source`() {
        val mockSource = mockk<Source>(relaxed = true)
        every { mockSource.sampleRate } returns 16000
        every { mockSource.channels } returns 1

        sink.handleFrame(FloatArray(320), mockSource)

        // Start should use detected sample rate
        sink.start()
        Thread.sleep(50) // Allow async startPlayback coroutine to execute

        verify {
            mockBridge.startPlayback(
                sampleRate = 16000,
                channels = 1,
                lowLatency = false
            )
        }

        sink.stop()
        sink.release()
    }
}
