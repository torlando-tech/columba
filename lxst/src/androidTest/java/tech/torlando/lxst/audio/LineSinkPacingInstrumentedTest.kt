package tech.torlando.lxst.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.bridge.KotlinAudioBridge

/**
 * Instrumented pacing test for LineSink with a real AudioTrack.
 *
 * Feeds synthetic sine wave frames to LineSink -> KotlinAudioBridge -> AudioTrack
 * and asserts zero underruns during sustained playback. This catches pacing bugs
 * that unit tests can't: real AudioTrack back-pressure, real Android scheduler
 * timing, and hardware underrun detection.
 *
 * Strategy: push frames continuously (no gaps), capture baseline underrun count
 * after a warmup period, then check underrun count immediately when pushing stops
 * (before AudioTrack buffer drains from test teardown).
 *
 * Runs on a single device — no second device or real call needed.
 */
@RunWith(AndroidJUnit4::class)
class LineSinkPacingInstrumentedTest {

    private lateinit var bridge: KotlinAudioBridge
    private lateinit var sink: LineSink

    // MQ profile: 48kHz mono, 20ms frames = 960 samples
    private val sampleRate = 48000
    private val channels = 1
    private val frameSize = 960 // 20ms at 48kHz

    // Warmup: 25 frames = 500ms — enough for AudioTrack to start and buffer to fill
    private val warmupFrames = 25

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bridge = KotlinAudioBridge(context)
        sinePhase = 0.0
    }

    @After
    fun cleanup() {
        if (::sink.isInitialized) {
            sink.stop()
            sink.release()
        }
        bridge.shutdown()
    }

    /**
     * Generate one frame of 440Hz sine wave as float32.
     * Phase advances across calls so consecutive frames are continuous.
     */
    private var sinePhase = 0.0
    private fun generateSineFrame(): FloatArray {
        val frame = FloatArray(frameSize)
        val phaseIncrement = 440.0 * 2.0 * Math.PI / sampleRate
        for (i in frame.indices) {
            frame[i] = (kotlin.math.sin(sinePhase) * 0.3).toFloat()
            sinePhase += phaseIncrement
        }
        return frame
    }

    @Test
    fun steadyStatePlayback_zeroUnderruns() {
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Push 175 frames (3.5s) at 20ms: capture baseline at frame 25
        var baseline = 0
        repeat(175) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < 174) Thread.sleep(20)
        }

        // Check immediately — before AudioTrack drains from test teardown
        val steadyStateUnderruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "Expected 0 underruns during steady-state playback (baseline=$baseline)",
            0, steadyStateUnderruns
        )
    }

    @Test
    fun burstThenSteady_zeroUnderruns() {
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Initial burst: push MAX_FRAMES instantly
        repeat(LineSink.MAX_FRAMES) {
            sink.handleFrame(generateSineFrame())
        }

        // Then steady for 2.5s, baseline after warmup
        var baseline = 0
        repeat(125) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < 124) Thread.sleep(20)
        }

        val steadyStateUnderruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "Expected 0 underruns after burst-then-steady (baseline=$baseline)",
            0, steadyStateUnderruns
        )
    }

    @Test
    fun fastProducer_zeroUnderruns() {
        // Frames arrive at 1.5x real-time (simulates Reticulum burst delivery).
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        var baseline = 0
        repeat(125) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < 124) Thread.sleep(13) // 1.5x faster than 20ms
        }

        val steadyStateUnderruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "Expected 0 underruns during fast-producer playback (baseline=$baseline)",
            0, steadyStateUnderruns
        )
    }

    @Test
    fun underrunRecovery_resumesCleanly() {
        // Play, starve, resume. Underruns during the gap are expected.
        // What matters is that recovery doesn't cause additional underruns.
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Phase 1: continuous playback for 1.5s
        repeat(75) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(20)
        }
        Thread.sleep(300) // drain + starve -> underrun expected

        val underrunsAfterGap = bridge.getUnderrunCount()

        // Phase 2: resume for 1.5s
        repeat(75) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(20)
        }

        // Check immediately after last push
        val recoveryUnderruns = bridge.getUnderrunCount() - underrunsAfterGap
        assertTrue(
            "Recovery should add at most 1 underrun (clock reset transient), got $recoveryUnderruns",
            recoveryUnderruns <= 1
        )
    }
}
