package tech.torlando.lxst.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * ToneSource - generates dial tones with smooth fade in/out.
 *
 * Matches Python LXST Generators.py ToneSource (lines 11-134).
 * Produces continuous sine wave at specified frequency.
 * Smooth easing prevents audible clicks when starting/stopping tones.
 *
 * Usage:
 *   val tone = ToneSource(frequency = 382f, targetGain = 0.1f)
 *   tone.sink = myLineSink
 *   tone.start()  // Fades in smoothly
 *   // ... later ...
 *   tone.stop()   // Fades out smoothly, then stops
 */
class ToneSource(
    private val frequency: Float = DEFAULT_FREQUENCY,
    private var targetGain: Float = 0.1f,
    private val ease: Boolean = true,
    private val easeTimeMs: Float = EASE_TIME_MS,
    private val targetFrameMs: Int = DEFAULT_FRAME_MS,
    initialChannels: Int = 1
) : LocalSource() {

    override var channels: Int = initialChannels

    companion object {
        private const val TAG = "Columba:ToneSource"
        const val DEFAULT_FRAME_MS = 80
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_FREQUENCY = 382f  // Hz, matches Python LXST (not 440Hz ITU-T)
        const val EASE_TIME_MS = 20f        // Fade duration in ms
    }

    override var sampleRate: Int = DEFAULT_SAMPLE_RATE

    /** Sink to push audio frames to */
    var sink: Sink? = null

    // Phase accumulator - MUST persist across frames for smooth tone (use Double to avoid drift)
    private var theta: Double = 0.0

    // Easing/gain state
    private var easeGain: Float = 0f        // Fade multiplier (0.0 to 1.0)
    private var currentGain: Float = 0f     // Internal gain converging to targetGain
    private var easeStep: Float = 0f        // Per-sample fade increment
    private var gainStep: Float = 0f        // Per-sample gain change increment
    private var easingOut: Boolean = false  // True when fading out before stop

    // Frame parameters
    private var samplesPerFrame: Int = 0
    private var frameTimeMs: Long = 0

    // Coroutine state
    private val scope = CoroutineScope(Dispatchers.Default)
    private var generateJob: Job? = null
    private val shouldRun = AtomicBoolean(false)

    init {
        calculateParameters()
    }

    /**
     * Calculate frame and easing parameters based on sample rate.
     */
    private fun calculateParameters() {
        samplesPerFrame = ((targetFrameMs / 1000f) * sampleRate).toInt()
        frameTimeMs = ((samplesPerFrame.toFloat() / sampleRate) * 1000).toLong()
        easeStep = 1.0f / (sampleRate * (easeTimeMs / 1000f))
        gainStep = 0.02f / (sampleRate * (easeTimeMs / 1000f))

        Log.d(TAG, "ToneSource configured: freq=${frequency}Hz, sampleRate=$sampleRate, " +
                "samplesPerFrame=$samplesPerFrame, frameTimeMs=$frameTimeMs")
    }

    /**
     * Start generating tone (fades in if ease enabled).
     */
    override fun start() {
        if (shouldRun.get()) {
            Log.d(TAG, "ToneSource already running")
            return
        }

        shouldRun.set(true)
        easeGain = if (ease) 0f else 1f  // Start silent if easing
        currentGain = 0f
        easingOut = false

        generateJob = scope.launch {
            generateLoop()
        }

        Log.d(TAG, "ToneSource started: freq=${frequency}Hz, ease=$ease")
    }

    /**
     * Stop generating tone (fades out if ease enabled).
     */
    override fun stop() {
        if (!shouldRun.get()) {
            return
        }

        if (!ease) {
            // No easing - stop immediately
            shouldRun.set(false)
            generateJob?.cancel()
            generateJob = null
            Log.d(TAG, "ToneSource stopped immediately (no ease)")
        } else {
            // Trigger fade out - job stops when fade completes
            easingOut = true
            Log.d(TAG, "ToneSource fading out")
        }
    }

    /**
     * Check if source is currently generating (not including fade-out period).
     */
    override fun isRunning(): Boolean {
        return shouldRun.get() && !easingOut
    }

    /**
     * Set target gain (will converge smoothly).
     *
     * @param gain Target gain level (0.0 to 1.0)
     */
    fun setGain(gain: Float) {
        targetGain = gain.coerceIn(0f, 1f)
    }

    /**
     * Release resources.
     */
    fun release() {
        stop()
        scope.cancel()
        Log.d(TAG, "ToneSource released")
    }

    /**
     * Generate a single frame of sine wave samples.
     *
     * Matches Python LXST Generators.py lines 95-123.
     *
     * @return FloatArray of samples for one frame
     */
    private fun generateFrame(): FloatArray {
        val frame = FloatArray(samplesPerFrame * channels)
        val step = (frequency * 2.0 * PI) / sampleRate

        for (n in 0 until samplesPerFrame) {
            // Accumulate phase (persists across frames for continuous tone)
            theta += step

            // Generate sample with gain and easing applied
            val amplitude = (sin(theta) * currentGain * easeGain).toFloat()

            // Write to all channels
            for (c in 0 until channels) {
                frame[n * channels + c] = amplitude
            }

            // Smooth gain transition toward target
            if (targetGain > currentGain) {
                currentGain = (currentGain + gainStep).coerceAtMost(targetGain)
            } else if (targetGain < currentGain) {
                currentGain = (currentGain - gainStep).coerceAtLeast(targetGain)
            }

            // Ease in/out
            if (ease) {
                if (easingOut) {
                    // Fading out
                    easeGain -= easeStep
                    if (easeGain <= 0f) {
                        easeGain = 0f
                        easingOut = false
                        shouldRun.set(false)
                        Log.d(TAG, "ToneSource fade-out complete")
                    }
                } else if (easeGain < 1.0f) {
                    // Fading in
                    easeGain = (easeGain + easeStep).coerceAtMost(1.0f)
                }
            }
        }

        return frame
    }

    /**
     * Main generation loop - runs in coroutine.
     *
     * Matches Python LXST Generators.py lines 125-133.
     */
    private suspend fun generateLoop() {
        while (shouldRun.get()) {
            val currentSink = sink
            if (currentSink != null && currentSink.canReceive(this)) {
                val frameSamples = generateFrame()
                // Push float32 directly to sink (local playback path)
                // Encoding happens in transmit path (Mixer -> network), not here
                currentSink.handleFrame(frameSamples, this)
            }
            delay(frameTimeMs / 10)
        }

        generateJob = null
        Log.d(TAG, "ToneSource generation loop ended")
    }
}
