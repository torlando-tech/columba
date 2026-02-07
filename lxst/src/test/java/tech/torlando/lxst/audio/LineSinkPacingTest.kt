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

        val pusher = pushFrames(sink, count = 30, intervalMs = 10, frameSize = frameSize)
        pusher.join()
        Thread.sleep(200) // drain remaining

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

        val pusher = pushFrames(sink, count = 50, intervalMs = 10, frameSize = frameSize)
        pusher.join()
        Thread.sleep(300) // drain

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
        val frameSize = 160

        // First batch triggers autostart
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(100) // let digest run + underrun

        // Second batch after gap
        val preRecoveryCount = writeTimestampsNs.size
        repeat(5) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(200) // drain

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

        val pusher = pushFrames(sink, count = 40, intervalMs = 10, frameSize = frameSize)
        pusher.join()
        Thread.sleep(200) // drain

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

        val pusher = pushFrames(sink, count = 30, intervalMs = 10, frameSize = frameSize)
        pusher.join()
        Thread.sleep(300) // drain

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
    fun `underrun triggers rebuffer not immediate resume`() {
        // Simulates Reticulum network jitter: after underrun, frames trickle in
        // one at a time. Without re-buffering, each frame plays immediately then
        // underruns again (rapid micro-cycles causing pops). With re-buffering,
        // frames accumulate to AUTOSTART_MIN before playback resumes.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // First batch triggers autostart and plays
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(150) // let all play + enter underrun

        val postFirstBatch = writeTimestampsNs.size

        // Push 2 frames (less than AUTOSTART_MIN) — should NOT write immediately
        sink.handleFrame(FloatArray(frameSize))
        Thread.sleep(20)
        sink.handleFrame(FloatArray(frameSize))
        Thread.sleep(100) // wait for re-buffer check cycles

        val afterTwoFrames = writeTimestampsNs.size

        // Verify: no new writes yet (re-buffering, waiting for AUTOSTART_MIN)
        assertTrue(
            "No writes should occur during re-buffer (got ${afterTwoFrames - postFirstBatch} extra writes, " +
                "expected 0). Queue has 2 frames but AUTOSTART_MIN is ${LineSink.AUTOSTART_MIN}.",
            afterTwoFrames == postFirstBatch
        )

        // Push remaining frames to reach AUTOSTART_MIN
        repeat(LineSink.AUTOSTART_MIN - 2) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(200) // let re-buffer complete and frames play

        val afterRebuffer = writeTimestampsNs.size

        // Verify: writes resumed after re-buffer completed
        assertTrue(
            "Writes should resume after re-buffer (got ${afterRebuffer - postFirstBatch} writes, " +
                "expected >= ${LineSink.AUTOSTART_MIN})",
            afterRebuffer - postFirstBatch >= LineSink.AUTOSTART_MIN
        )

        sink.stop(); sink.release()
    }

    @Test
    fun `repeated underruns each trigger rebuffer`() {
        // Simulates sustained Reticulum jitter: multiple underrun/recovery cycles.
        // Each recovery should re-buffer, not just the first one.
        val sink = LineSink(mockBridge, autodigest = true, lowLatency = false)
        sink.configure(16000, 1)
        val frameSize = 160 // 10ms at 16kHz

        // Cycle 1: fill and play
        repeat(LineSink.AUTOSTART_MIN) { sink.handleFrame(FloatArray(frameSize)) }
        Thread.sleep(150) // play + underrun
        val afterCycle1 = writeTimestampsNs.size

        // Cycle 2: fill (re-buffer) and play
        repeat(LineSink.AUTOSTART_MIN) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(200) // re-buffer + play + underrun
        val afterCycle2 = writeTimestampsNs.size

        // Cycle 3: fill (re-buffer) and play
        repeat(LineSink.AUTOSTART_MIN) {
            sink.handleFrame(FloatArray(frameSize))
            Thread.sleep(10)
        }
        Thread.sleep(200) // re-buffer + play

        val afterCycle3 = writeTimestampsNs.size

        // Each cycle should have written AUTOSTART_MIN frames
        assertTrue(
            "Cycle 2 should write >= ${LineSink.AUTOSTART_MIN} frames " +
                "(got ${afterCycle2 - afterCycle1})",
            afterCycle2 - afterCycle1 >= LineSink.AUTOSTART_MIN
        )
        assertTrue(
            "Cycle 3 should write >= ${LineSink.AUTOSTART_MIN} frames " +
                "(got ${afterCycle3 - afterCycle2})",
            afterCycle3 - afterCycle2 >= LineSink.AUTOSTART_MIN
        )

        sink.stop(); sink.release()
    }
}
