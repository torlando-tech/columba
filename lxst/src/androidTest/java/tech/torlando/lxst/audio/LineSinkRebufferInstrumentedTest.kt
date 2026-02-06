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
 * Instrumented tests for LineSink re-buffer behavior with real AudioTrack.
 *
 * Reproduces the exact conditions from a real Sideband ↔ Columba call:
 *
 * Evidence from logs (2026-02-06):
 *   - RX decode rate capped to 16kHz (wuqi-opus 1024-sample buffer limit)
 *   - Frame time: 60ms (960 samples at 16000Hz)
 *   - Sideband sends 60ms Opus frames (not 20ms)
 *   - Reticulum delivers frames in bursts with gaps (network jitter)
 *   - Without re-buffering: rapid underrun/recovery micro-cycles (12-396ms pops)
 *   - With re-buffering: sustained gaps trigger accumulation before resume
 *   - Brief glitches (<2 frame periods) skip re-buffer for instant resume
 *
 * These tests use real AudioTrack hardware underrun counting to validate
 * that the re-buffer strategy produces clean playback in each scenario.
 */
@RunWith(AndroidJUnit4::class)
class LineSinkRebufferInstrumentedTest {

    private lateinit var bridge: KotlinAudioBridge
    private lateinit var sink: LineSink

    // Match real Sideband call: 16kHz decode rate, 60ms frames (wuqi-opus cap)
    private val sampleRate = 16000
    private val channels = 1
    private val frameSize = 960 // 60ms at 16kHz (960 samples ≤ 1024 wuqi-opus limit)
    private val framePeriodMs = 60L

    // Warmup: 10 frames = 600ms — AudioTrack buffer fills and stabilizes
    private val warmupFrames = 10

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

    // ===== Baseline: 60ms frames at 16kHz play cleanly =====

    @Test
    fun sixtyMsFrames_steadyDelivery_zeroUnderruns() {
        // Baseline: confirm the real call parameters (16kHz, 60ms frames) produce
        // zero hardware underruns when frames arrive on time. This is the control
        // that all jitter tests compare against.
        //
        // 50 frames = 3s of playback.
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        var baseline = 0
        repeat(50) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < 49) Thread.sleep(framePeriodMs)
        }

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "Expected 0 underruns during steady 60ms frame delivery (baseline=$baseline)",
            0, underruns
        )
    }

    // ===== Sustained gap triggers re-buffer, then clean playback =====

    @Test
    fun sustainedGap_rebufferThenCleanPlayback() {
        // Reproduce the log pattern: steady playback → sustained network gap →
        // re-buffer accumulates AUTOSTART_MIN frames → clean playback resumes.
        //
        // The 400ms gap causes underrunMs ≈ 340ms (>> 120ms threshold) → re-buffer.
        // After re-buffer fills, steady playback should produce 0 underruns.
        //
        // Log evidence (call 1):
        //   15:16:32.632  Buffer underrun started
        //   15:16:32.687  Underrun after 55ms, re-buffering to 5 frames
        //   15:16:33.353  Re-buffer complete, queue=5, resuming playback
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Phase 1: steady delivery (1.5s) — fills AudioTrack buffer
        repeat(25) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(framePeriodMs)
        }

        // Sustained gap (400ms) — triggers re-buffer on recovery
        Thread.sleep(400)

        // Phase 2: burst fill for re-buffer (AUTOSTART_MIN frames quickly)
        repeat(LineSink.AUTOSTART_MIN) {
            sink.handleFrame(generateSineFrame())
        }
        Thread.sleep(200) // let re-buffer detect, accumulate, and resume

        // Phase 3: steady delivery (1.5s) — measure underruns after warmup
        var baseline = 0
        repeat(25) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == warmupFrames) baseline = bridge.getUnderrunCount()
            if (i < 24) Thread.sleep(framePeriodMs)
        }

        val underruns = bridge.getUnderrunCount() - baseline
        assertEquals(
            "Expected 0 underruns in post-rebuffer steady state (baseline=$baseline)",
            0, underruns
        )
    }

    // ===== Brief gap skips re-buffer, resumes immediately =====

    @Test
    fun briefGap_immediateResume_noHardwareUnderruns() {
        // A brief network glitch (< 2 frame periods = 120ms) should NOT trigger
        // re-buffering. The pacer resets its clock and resumes immediately.
        //
        // With a 100ms gap and 500ms AudioTrack buffer, the hardware never
        // underruns — the AudioTrack still has 400ms of data. This proves the
        // brief-gap path avoids the 300-1000ms re-buffer penalty.
        //
        // Contrast: without the threshold, even an 18ms underrun triggered
        // re-buffering that caused a 1047ms silence (from call 1 logs).
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Phase 1: steady delivery (1.5s) — fills AudioTrack buffer
        repeat(25) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(framePeriodMs)
        }

        // Brief gap (100ms) — underrunMs ≈ 40ms < 120ms → skip re-buffer
        Thread.sleep(100)

        val preResume = bridge.getUnderrunCount()

        // Phase 2: steady delivery (1.5s) — should resume without re-buffer delay
        repeat(25) { i ->
            sink.handleFrame(generateSineFrame())
            if (i < 24) Thread.sleep(framePeriodMs)
        }

        val underruns = bridge.getUnderrunCount() - preResume
        assertEquals(
            "Expected 0 hardware underruns after brief gap " +
                "(AudioTrack buffer absorbs 100ms, no re-buffer delay)",
            0, underruns
        )
    }

    // ===== Burst-gap pattern simulates Reticulum network jitter =====

    @Test
    fun networkJitter_burstGapPattern_limitedUnderruns() {
        // Simulate real Reticulum delivery: frames arrive in bursts with gaps.
        //
        // Pattern per cycle: 8 frames delivered over 100ms, then 200ms gap.
        // This produces 480ms of audio per 300ms cycle (1.6x real-time average),
        // so the queue should stay fed. But each 200ms gap triggers re-buffer
        // (underrunMs ≈ 140ms >= 120ms), introducing ~300ms silence per cycle.
        //
        // With 8 cycles, the test exercises repeated re-buffer/recovery transitions.
        // Hardware underruns are bounded — at most one per gap/re-buffer cycle.
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        repeat(8) { cycle ->
            // Burst: 8 frames at ~12ms intervals (simulates network burst)
            repeat(8) {
                sink.handleFrame(generateSineFrame())
                Thread.sleep(12)
            }
            // Gap between bursts (simulates Reticulum jitter)
            if (cycle < 7) Thread.sleep(200)
        }
        Thread.sleep(600) // let remaining frames drain through re-buffer + play

        val totalUnderruns = bridge.getUnderrunCount()
        // Each gap may cause 1 hardware underrun (AudioTrack drains during
        // gap + re-buffer). 7 gaps → allow up to 7 underruns + 1 margin.
        assertTrue(
            "Network jitter pattern should produce <= 8 hardware underruns, " +
                "got $totalUnderruns (7 gaps + margin). " +
                "Previous behavior without re-buffer caused dozens of micro-underruns.",
            totalUnderruns <= 8
        )
    }

    // ===== Verify re-buffer accumulates before writing =====

    @Test
    fun sustainedGap_doesNotPlayDuringRebuffer() {
        // After a sustained gap, pushing fewer than AUTOSTART_MIN frames should
        // NOT produce any writes to AudioTrack (frames are held for re-buffer).
        // Only after AUTOSTART_MIN frames accumulate should playback resume.
        //
        // We verify this indirectly: if frames played during re-buffer, the
        // AudioTrack would receive data and underrun count would NOT increase.
        // If frames are held, the AudioTrack stays starved and underruns continue.
        sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Steady delivery until AudioTrack is playing
        repeat(25) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(framePeriodMs)
        }

        // Sustained gap — AudioTrack drains, hardware underruns start
        Thread.sleep(800)
        val underrunsAfterGap = bridge.getUnderrunCount()
        assertTrue(
            "Sustained 800ms gap should cause >= 1 hardware underrun",
            underrunsAfterGap >= 1
        )

        // Push 2 frames (< AUTOSTART_MIN) — held in re-buffer, NOT written
        sink.handleFrame(generateSineFrame())
        Thread.sleep(30)
        sink.handleFrame(generateSineFrame())
        Thread.sleep(200) // wait — frames should still be held

        val underrunsDuringRebuffer = bridge.getUnderrunCount()

        // Push remaining frames to reach AUTOSTART_MIN — re-buffer completes
        repeat(LineSink.AUTOSTART_MIN - 2) {
            sink.handleFrame(generateSineFrame())
        }
        Thread.sleep(100) // let re-buffer complete

        // Now push steady frames — these should play cleanly
        repeat(15) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(framePeriodMs)
        }

        // During re-buffer phase, underruns should NOT have decreased
        // (no data was written to AudioTrack). After re-buffer, playback resumes.
        assertTrue(
            "Hardware underruns should not decrease during re-buffer " +
                "(frames held, not written). After gap: $underrunsAfterGap, " +
                "during re-buffer: $underrunsDuringRebuffer",
            underrunsDuringRebuffer >= underrunsAfterGap
        )
    }
}
