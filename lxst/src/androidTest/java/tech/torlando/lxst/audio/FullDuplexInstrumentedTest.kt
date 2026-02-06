package tech.torlando.lxst.audio

import android.Manifest
import android.media.AudioManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.bridge.KotlinAudioBridge
import tech.torlando.lxst.codec.Opus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-duplex instrumented tests: TX and RX running simultaneously.
 *
 * Verifies that concurrent playback and recording work correctly on real
 * hardware, and that AudioManager mode lifecycle doesn't break one path
 * when the other stops.
 *
 * These tests catch cross-cutting issues that isolated TX/RX tests miss:
 * resource contention, AudioManager mode races, and concurrent I/O.
 */
@RunWith(AndroidJUnit4::class)
class FullDuplexInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var bridge: KotlinAudioBridge
    private lateinit var audioManager: AudioManager

    private val sampleRate = 48000
    private val channels = 1
    private val frameSize = 960 // 20ms at 48kHz

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bridge = KotlinAudioBridge(context)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @After
    fun cleanup() {
        bridge.shutdown()
        // Ensure mode is reset even if test fails
        audioManager.mode = AudioManager.MODE_NORMAL
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

    @Test
    fun simultaneousTxRx_bothPathsDeliver() {
        val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val txFrameCount = AtomicInteger(0)

        // TX: LineSource captures from mic, pushes to counting sink
        val txSink = object : LocalSink() {
            override fun canReceive(fromSource: Source?): Boolean = true
            override fun handleFrame(frame: FloatArray, source: Source?) {
                txFrameCount.incrementAndGet()
            }
            override fun start() {}
            override fun stop() {}
            override fun isRunning(): Boolean = true
        }

        val source = LineSource(bridge, opus, targetFrameMs = 80, gain = 1.0f)
        source.sink = txSink

        // RX: LineSink plays synthetic audio through real AudioTrack
        val sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Start both paths
        source.start()

        // Push RX frames: 25 warmup + 125 measured = 3s total
        var rxBaseline = 0
        repeat(150) { i ->
            sink.handleFrame(generateSineFrame())
            if (i == 25) rxBaseline = bridge.getUnderrunCount()
            if (i < 149) Thread.sleep(20)
        }

        val rxUnderruns = bridge.getUnderrunCount() - rxBaseline
        val txCount = txFrameCount.get()

        // Stop both
        source.stop()
        source.release()
        sink.stop()
        sink.release()
        opus.release()

        // RX: zero underruns during concurrent operation
        assertEquals(
            "Expected 0 RX underruns during full-duplex (baseline=$rxBaseline)",
            0, rxUnderruns
        )

        // TX: should have received frames during the ~3s window
        // At 80ms frames, expect ~37; allow margin for startup
        assertTrue(
            "TX should receive >=15 frames during full-duplex (got $txCount)",
            txCount >= 15
        )
    }

    @Test
    fun stopPlayback_whileRecordingActive_recordingContinues() {
        // This test exposes the AudioManager MODE lifecycle bug:
        // stopPlayback() resets MODE_NORMAL unconditionally, which can
        // break recording that depends on MODE_IN_COMMUNICATION.

        // Start recording first (sets MODE_IN_COMMUNICATION)
        bridge.startRecording(sampleRate, channels, frameSize)
        Thread.sleep(200) // let recording stabilize

        // Verify recording works
        val preFrames = mutableListOf<ByteArray?>()
        repeat(5) { preFrames.add(bridge.readAudio(frameSize)) }
        val preCount = preFrames.count { it != null }
        assertTrue("Recording should deliver frames before playback starts (got $preCount)", preCount >= 2)

        // Start playback (MODE_IN_COMMUNICATION already set, no-op on mode)
        bridge.startPlayback(sampleRate, channels, lowLatency = false)
        Thread.sleep(100)

        // Stop playback — this is where the bug lives:
        // stopPlayback() resets audioManager.mode = MODE_NORMAL
        // while recording still needs MODE_IN_COMMUNICATION
        bridge.stopPlayback()
        Thread.sleep(200) // let mode change propagate

        // Recording should STILL deliver frames after playback stops
        val postFrames = mutableListOf<ByteArray?>()
        repeat(10) { postFrames.add(bridge.readAudio(frameSize)) }
        val postCount = postFrames.count { it != null }

        bridge.stopRecording()

        assertTrue(
            "Recording should still deliver frames after playback stops (got $postCount/10)",
            postCount >= 5
        )
    }

    @Test
    fun stopRecording_whilePlaybackActive_playbackContinues() {
        // Mirror test: stop recording first, verify playback still works.
        val sink = LineSink(bridge, autodigest = true, lowLatency = false)
        sink.configure(sampleRate, channels)

        // Start both paths
        bridge.startRecording(sampleRate, channels, frameSize)
        // Push enough frames to start playback
        repeat(LineSink.AUTOSTART_MIN + 3) {
            sink.handleFrame(generateSineFrame())
        }
        Thread.sleep(300) // let both stabilize

        val baselineUnderruns = bridge.getUnderrunCount()

        // Stop recording while playback continues
        bridge.stopRecording()
        Thread.sleep(100)

        // Continue pushing RX frames for 1 second
        repeat(50) {
            sink.handleFrame(generateSineFrame())
            Thread.sleep(20)
        }

        val postUnderruns = bridge.getUnderrunCount() - baselineUnderruns

        sink.stop()
        sink.release()

        assertEquals(
            "Playback should have 0 underruns after recording stops (baseline=$baselineUnderruns)",
            0, postUnderruns
        )
    }
}
