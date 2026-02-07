package tech.torlando.lxst.audio

import tech.torlando.lxst.bridge.KotlinAudioBridge
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Pacing tests for LineSink's monotonic clock pacer.
 *
 * Uses real wall-clock time with short frame periods (10ms at 16kHz)
 * to verify that the digest loop paces output correctly. MockK stubs
 * writeAudio to capture timestamps without touching real AudioTrack.
 *
 * Note: With time-based buffer sizing, effectiveAutostartMin becomes 50
 * for 10ms frames (PREBUFFER_MS/frameTimeMs = 500/10). Tests push frames
 * fast enough (5ms intervals = 2x real-time) to keep the queue above
 * the re-buffer threshold and avoid re-buffer stalls.
 */
class LineSinkPacingTest {

    private val writeTimestampsNs = mutableListOf<Long>()
    private lateinit var mockBridge: KotlinAudioBridge

    @Before
    fun setup() {
        writeTimestampsNs.clear()
        mockBridge = mockk(relaxed = true)
        every { mockBridge.writeAudio(any()) } answers {
            writeTimestampsNs.add(System.nanoTime())
        }
    }

    /**
     * Push N frames to sink at [intervalMs] apart, starting after [initialDelayMs].
     * Runs on a background thread to avoid blocking the test thread.
     */
    private fun pushFrames(
        sink: LineSink,
        count: Int,
        intervalMs: Long,
        frameSize: Int,
        initialDelayMs: Long = 0
    ): Thread {
        return Thread {
            if (initialDelayMs > 0) Thread.sleep(initialDelayMs)
            repeat(count) {
                sink.handleFrame(FloatArray(frameSize))
                if (it < count - 1 && intervalMs > 0) Thread.sleep(intervalMs)
            }
        }.also { it.start() }
    }

    @Test
    fun `steady state writes are paced at frame rate`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate = 16000, channels = 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push at 5ms (2x faster than consumption) to keep queue full and avoid
        // re-buffer stalls (effectiveAutostartMin = 50 for 10ms frames).
        val pusher = pushFrames(sink, count = 80, intervalMs = 5, frameSize = frameSize)
        pusher.join()
        Thread.sleep(600) // drain remaining queued frames

        sink.stop(); sink.release()

        // Steady-state intervals (after AUTOSTART_MIN initial fill)
        val intervals = writeTimestampsNs
            .zipWithNext { a, b -> (b - a) / 1_000_000L }
            .drop(LineSink.AUTOSTART_MIN)

        assertTrue("Need >=10 steady-state intervals, got ${intervals.size}", intervals.size >= 10)
        val avgInterval = intervals.average()
        assertTrue(
            "Average interval ${avgInterval}ms should be 7-13ms (target 10ms)",
            avgInterval in 7.0..13.0
        )
    }

    @Test
    fun `startup drain removes excess burst frames`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push burst: AUTOSTART_MIN + 5 frames instantly (simulates frames
        // accumulating during slow AudioTrack creation)
        repeat(LineSink.AUTOSTART_MIN + 5) {
            sink.handleFrame(FloatArray(frameSize))
        }
        Thread.sleep(200) // let digest process

        sink.stop(); sink.release()

        // Drain should have removed excess, so total writes should be
        // close to AUTOSTART_MIN (not AUTOSTART_MIN + 5). Allow some
        // tolerance for race between handleFrame and drain.
        assertTrue(
            "Writes (${writeTimestampsNs.size}) should be <= AUTOSTART_MIN + 2 " +
                "(drain removes excess), not ${LineSink.AUTOSTART_MIN + 5}",
            writeTimestampsNs.size <= LineSink.AUTOSTART_MIN + 2
        )
    }

    @Test
    fun `no cumulative drift over many frames`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push at 5ms (2x real-time) to prevent underruns
        val pusher = pushFrames(sink, count = 100, intervalMs = 5, frameSize = frameSize)
        pusher.join()
        Thread.sleep(800) // drain

        sink.stop(); sink.release()

        // Compare expected vs actual total playback time
        val steadyWrites = writeTimestampsNs.drop(LineSink.AUTOSTART_MIN)
        if (steadyWrites.size >= 2) {
            val actualSpanMs = (steadyWrites.last() - steadyWrites.first()) / 1_000_000L
            val expectedSpanMs = (steadyWrites.size - 1) * 10L
            val driftMs = actualSpanMs - expectedSpanMs
            assertTrue(
                "Drift ${driftMs}ms should be within +/-50ms over ${steadyWrites.size} frames",
                kotlin.math.abs(driftMs) < 50
            )
        }
    }

    @Test
    fun `underrun recovery resets clock and paces correctly`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // First batch triggers autostart
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(150) // let digest run + underrun

        // Second batch after gap — push enough for re-buffer threshold
        // (effectiveAutostartMin = 50 for 10ms frames after updateBufferLimits)
        val preRecoveryCount = writeTimestampsNs.size
        repeat(55) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(500) // drain

        sink.stop(); sink.release()

        // Recovery writes should be paced, not instant
        val recoveryWrites = writeTimestampsNs.drop(preRecoveryCount)
        if (recoveryWrites.size >= 3) {
            val spanMs = (recoveryWrites.last() - recoveryWrites.first()) / 1_000_000L
            assertTrue(
                "Recovery span ${spanMs}ms should be >=15ms (paced, not burst-drained)",
                spanMs >= 15
            )
        }
    }

    @Test
    fun `individual intervals stay within jitter bounds`() {
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate = 16000, channels = 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push at 5ms (2x real-time) to prevent underruns
        val pusher = pushFrames(sink, count = 80, intervalMs = 5, frameSize = frameSize)
        pusher.join()
        Thread.sleep(600) // drain

        sink.stop(); sink.release()

        // Check individual intervals — a pacer that alternates 2ms/18ms averages
        // 10ms but sounds choppy. No single interval should exceed 2x frame period.
        val intervals = writeTimestampsNs
            .zipWithNext { a, b -> (b - a) / 1_000_000L }
            .drop(LineSink.AUTOSTART_MIN)

        assertTrue("Need >=10 steady-state intervals", intervals.size >= 10)
        val outliers = intervals.filter { it > 20 }
        assertTrue(
            "At most 10% of intervals should exceed 2x frame period (20ms), " +
                "got ${outliers.size}/${intervals.size}: $outliers",
            outliers.size <= intervals.size / 10
        )
    }

    @Test
    fun `fast producer does not cause instant drain`() {
        // Simulates Reticulum network burst: frames arrive at 2x real-time.
        // The pacer must still output at ~10ms intervals, not drain at arrival rate.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate = 16000, channels = 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push 20 frames at 5ms intervals (2x faster than real-time)
        val pusher = pushFrames(sink, count = 20, intervalMs = 5, frameSize = frameSize)
        pusher.join()
        Thread.sleep(300) // let digest drain everything

        sink.stop(); sink.release()

        // Steady-state writes should still be paced at ~10ms, not ~5ms
        val intervals = writeTimestampsNs
            .zipWithNext { a, b -> (b - a) / 1_000_000L }
            .drop(LineSink.AUTOSTART_MIN)

        assertTrue("Need >=5 steady-state intervals", intervals.size >= 5)
        val avgInterval = intervals.average()
        assertTrue(
            "Average interval ${avgInterval}ms should be >=7ms (frame-rate paced, not arrival-rate)",
            avgInterval >= 7.0
        )
    }

    @Test
    fun `variable write latency is compensated by monotonic clock`() {
        // Simulates AudioTrack back-pressure: writeAudio() blocks for 0-8ms
        // depending on buffer fullness. The monotonic clock should compensate
        // so that frame-to-frame intervals stay near 10ms.
        val writeCount = AtomicLong(0)
        val variableBridge = mockk<KotlinAudioBridge>(relaxed = true)
        every { variableBridge.writeAudio(any()) } answers {
            writeTimestampsNs.add(System.nanoTime())
            val n = writeCount.incrementAndGet()
            // Alternate: even frames take 0ms, odd frames take ~6ms
            if (n % 2 == 1L) Thread.sleep(6)
        }

        val sink = LineSink(variableBridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate = 16000, channels = 1)
        val frameSize = 160 // 10ms at 16kHz

        // Push at 5ms (2x real-time) to prevent underruns
        val pusher = pushFrames(sink, count = 80, intervalMs = 5, frameSize = frameSize)
        pusher.join()
        Thread.sleep(600) // drain

        sink.stop(); sink.release()

        // Despite variable write latency, overall pacing should stay near 10ms/frame.
        // The monotonic clock computes delay from absolute target, not from write end,
        // so a slow write on frame N means less delay after it, self-correcting.
        val intervals = writeTimestampsNs
            .zipWithNext { a, b -> (b - a) / 1_000_000L }
            .drop(LineSink.AUTOSTART_MIN)

        if (intervals.size >= 10) {
            val avgInterval = intervals.average()
            assertTrue(
                "Average interval ${avgInterval}ms should be 7-15ms despite variable write latency",
                avgInterval in 7.0..15.0
            )
        }
    }

    @Test
    fun `brief underrun resumes immediately without rebuffer`() {
        // Reproduces observed issue: on high-jitter Reticulum links, LL profile
        // (20ms/50fps expected) receives frames at ~20fps. Tiny 40-70ms underrun
        // gaps triggered full re-buffer requiring 25+ frames, causing 1.25+ second
        // silence per event. In a 14.6s call, 6 such events caused ~7.6s of silence.
        //
        // Fix: REBUFFER_TRIGGER_MS (500ms) ensures only sustained outages re-buffer.
        // Brief gaps let AudioTrack's 500ms internal buffer absorb the jitter.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // Phase 1: Bootstrap — trigger autostart, let frames play + enter underrun
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(100) // play 5 frames (50ms) + underrun starts (~50ms gap)

        val preRecovery = writeTimestampsNs.size

        // Phase 2: Brief recovery — push frames after short gap (~50-100ms).
        // This simulates a Reticulum jitter gap that is NOT a sustained outage.
        repeat(8) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(12)
        }
        Thread.sleep(200) // let writes drain

        val afterRecovery = writeTimestampsNs.size
        val newWrites = afterRecovery - preRecovery

        // Assert: frames should play through without re-buffer stall.
        // Old behavior (bug): re-buffer threshold was frameTimeMs*2 (20ms for 10ms frames),
        // so a ~50ms underrun triggered re-buffer waiting for effectiveAutostartMin (50)
        // frames that never arrived → 0 new writes → silence.
        // New behavior: underrun < REBUFFER_TRIGGER_MS (500ms) → immediate resume → writes.
        assertTrue(
            "Brief underrun (~50ms) should NOT trigger re-buffer. " +
                "Expected >= 5 writes, got $newWrites. " +
                "If 0, the re-buffer threshold is too aggressive.",
            newWrites >= 5
        )

        sink.stop(); sink.release()
    }

    @Test
    fun `sustained underrun triggers rebuffer not immediate resume`() {
        // After a SUSTAINED underrun (> REBUFFER_TRIGGER_MS = 500ms), frames should
        // accumulate to REBUFFER_FRAMES before resuming — prevents rapid play/underrun
        // micro-cycles on links with prolonged outages.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // First batch triggers autostart and plays
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(650) // play (50ms) + sustained underrun (~600ms > REBUFFER_TRIGGER_MS)

        val postFirstBatch = writeTimestampsNs.size

        // Push 2 frames (< REBUFFER_FRAMES = 5) — should NOT write yet
        sink.handleFrame(FloatArray(frameSize))
        Thread.sleep(20)
        sink.handleFrame(FloatArray(frameSize))
        Thread.sleep(100) // wait for re-buffer check cycles

        val afterTwoFrames = writeTimestampsNs.size

        // Verify: no new writes yet (re-buffering, waiting for REBUFFER_FRAMES)
        assertTrue(
            "No writes should occur during re-buffer (got ${afterTwoFrames - postFirstBatch} extra writes, " +
                "expected 0). Queue has 2 frames but REBUFFER_FRAMES=${LineSink.REBUFFER_FRAMES}.",
            afterTwoFrames == postFirstBatch
        )

        // Push enough frames to reach REBUFFER_FRAMES (5)
        repeat(LineSink.REBUFFER_FRAMES) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(300) // let re-buffer complete and frames play

        val afterRebuffer = writeTimestampsNs.size

        // Verify: writes resumed after re-buffer completed
        assertTrue(
            "Writes should resume after re-buffer " +
                "(got ${afterRebuffer - postFirstBatch} writes, expected >= ${LineSink.REBUFFER_FRAMES})",
            afterRebuffer - postFirstBatch >= LineSink.REBUFFER_FRAMES
        )

        sink.stop(); sink.release()
    }

    @Test
    fun `repeated sustained underruns each trigger rebuffer`() {
        // Simulates prolonged Reticulum outages: multiple sustained underrun/recovery
        // cycles. Each recovery should re-buffer (wait for REBUFFER_FRAMES), not just
        // the first one.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // Cycle 1: fill and play (initial autostart at AUTOSTART_MIN=5)
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(650) // play (50ms) + sustained underrun (~600ms)
        val afterCycle1 = writeTimestampsNs.size

        // Cycle 2: fill past REBUFFER_FRAMES and play
        repeat(15) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(800) // re-buffer + play + sustained underrun (~600ms)
        val afterCycle2 = writeTimestampsNs.size

        // Cycle 3: fill past REBUFFER_FRAMES again
        repeat(15) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(400) // re-buffer + play

        val afterCycle3 = writeTimestampsNs.size

        // Each cycle should have written frames after re-buffer completes
        assertTrue(
            "Cycle 2 should write >= 10 frames (got ${afterCycle2 - afterCycle1})",
            afterCycle2 - afterCycle1 >= 10
        )
        assertTrue(
            "Cycle 3 should write >= 10 frames (got ${afterCycle3 - afterCycle2})",
            afterCycle3 - afterCycle2 >= 10
        )

        sink.stop(); sink.release()
    }
}
