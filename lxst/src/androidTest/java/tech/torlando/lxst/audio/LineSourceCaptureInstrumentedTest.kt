package tech.torlando.lxst.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.bridge.KotlinAudioBridge
import tech.torlando.lxst.bridge.KotlinAudioFilters
import tech.torlando.lxst.codec.Opus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumented TX (capture) tests for the audio pipeline.
 *
 * Verifies that real AudioRecord hardware delivers frames at expected rate,
 * the Kotlin filter chain processes within budget, and LineSource delivers
 * frames end-to-end without drops under steady state.
 *
 * Requires RECORD_AUDIO permission (auto-granted by GrantPermissionRule).
 * Runs on a single device — no second device or real call needed.
 */
@RunWith(AndroidJUnit4::class)
class LineSourceCaptureInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var bridge: KotlinAudioBridge

    private val sampleRate = 48000
    private val channels = 1
    private val samplesPerFrame = 960 // 20ms at 48kHz

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bridge = KotlinAudioBridge(context)
    }

    @After
    fun cleanup() {
        bridge.shutdown()
    }

    @Test
    fun captureDelivers_framesAtExpectedRate() {
        bridge.startRecording(sampleRate, channels, samplesPerFrame)

        // Discard first few frames (AudioRecord warmup)
        repeat(5) { bridge.readAudio(samplesPerFrame) }

        // Measure 50 frame delivery intervals
        val timestamps = mutableListOf<Long>()
        repeat(50) {
            val data = bridge.readAudio(samplesPerFrame)
            if (data != null) timestamps.add(System.nanoTime())
        }

        bridge.stopRecording()

        val intervals = timestamps.zipWithNext { a, b -> (b - a) / 1_000_000L }
        assertTrue("Need >=30 intervals, got ${intervals.size}", intervals.size >= 30)

        val avgInterval = intervals.average()
        assertTrue(
            "Average capture interval ${avgInterval}ms should be 15-30ms (target 20ms)",
            avgInterval in 15.0..30.0
        )
    }

    @Test
    fun filterChain_processesUnderFramePeriod() {
        // Test filter chain latency with synthetic audio data.
        // No mic needed — just timing the DSP.
        val chain = KotlinAudioFilters.VoiceFilterChain(
            channels = 1,
            highPassCutoff = 300f,
            lowPassCutoff = 3400f,
            agcTargetDb = -12f,
            agcMaxGain = 12f
        )

        // Generate synthetic 20ms frame of 440Hz as int16
        val frame = ShortArray(samplesPerFrame) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            (kotlin.math.sin(phase) * 16000).toInt().toShort()
        }

        // Warmup
        repeat(10) { chain.process(frame.copyOf(), sampleRate) }

        // Measure 100 iterations
        val startNs = System.nanoTime()
        repeat(100) { chain.process(frame.copyOf(), sampleRate) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        val avgMs = elapsedMs / 100.0
        assertTrue(
            "Filter chain avg ${avgMs}ms should be <5ms per frame (budget: 20ms frame period)",
            avgMs < 5.0
        )
    }

    @Test
    fun endToEnd_lineSourceDeliversFramesToSink() {
        val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val receivedCount = AtomicInteger(0)
        val firstFrameNs = AtomicLong(0)
        val lastFrameNs = AtomicLong(0)

        // Test sink that counts received frames
        val testSink = object : LocalSink() {
            override fun canReceive(fromSource: Source?): Boolean = true
            override fun handleFrame(frame: FloatArray, source: Source?) {
                val now = System.nanoTime()
                firstFrameNs.compareAndSet(0, now)
                lastFrameNs.set(now)
                receivedCount.incrementAndGet()
            }
            override fun start() {}
            override fun stop() {}
            override fun isRunning(): Boolean = true
        }

        val source = LineSource(bridge, opus, targetFrameMs = 80, gain = 1.0f)
        source.sink = testSink
        source.start()

        // Capture for 3 seconds
        Thread.sleep(3000)

        source.stop()
        source.release()
        opus.release()

        val count = receivedCount.get()
        // At 80ms frames (LineSource default), expect ~37 frames in 3s.
        // Allow wide margin for startup latency and scheduling.
        assertTrue(
            "Should receive >=20 frames in 3s (got $count)",
            count >= 20
        )

        // Verify frames arrived over a reasonable time span (not all at once)
        val first = firstFrameNs.get()
        val last = lastFrameNs.get()
        if (first > 0 && last > first) {
            val spanMs = (last - first) / 1_000_000L
            assertTrue(
                "Frames should span >=1000ms (got ${spanMs}ms) — not burst-delivered",
                spanMs >= 1000
            )
        }
    }

    @Test
    fun endToEnd_backpressureHandledGracefully() {
        val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val receivedCount = AtomicInteger(0)
        val droppedSignal = AtomicInteger(0)

        // Slow sink: blocks for 100ms per frame (5x slower than 20ms frame period).
        // LineSource should detect canReceive() == false and drop, not crash.
        val slowSink = object : LocalSink() {
            private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

            override fun canReceive(fromSource: Source?): Boolean = !busy.get()
            override fun handleFrame(frame: FloatArray, source: Source?) {
                busy.set(true)
                receivedCount.incrementAndGet()
                Thread.sleep(100) // simulate slow consumer
                busy.set(false)
            }
            override fun start() {}
            override fun stop() {}
            override fun isRunning(): Boolean = true
        }

        val source = LineSource(bridge, opus, targetFrameMs = 80, gain = 1.0f)
        source.sink = slowSink
        source.start()

        // Run for 2 seconds — should not crash or OOM
        Thread.sleep(2000)

        source.stop()
        source.release()
        opus.release()

        // Some frames received (slow sink processes ~20 in 2s at 100ms each)
        val count = receivedCount.get()
        assertTrue(
            "Slow sink should still receive some frames (got $count)",
            count >= 5
        )
        // The key assertion: we got here without crashing
    }
}
