package tech.torlando.lxst.audio

import tech.torlando.lxst.codec.Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * Mixer - Combines multiple audio sources with gain control.
 *
 * Mixer acts as BOTH a Source (output mixed audio) AND a Sink (receive input frames).
 * Since Kotlin only allows single class inheritance, we:
 * - Extend LocalSource for Source behavior (sampleRate, channels, start, stop, isRunning)
 * - Implement Sink methods directly (canReceive, handleFrame) without extending Sink
 *
 * This matches Python LXST Mixer.py (lines 14-177) where Mixer inherits from both
 * LocalSource and LocalSink.
 *
 * Usage:
 * - Sources push frames to mixer via handleFrame()
 * - Mixer combines frames in background coroutine
 * - Mixed output pushed to downstream sink
 *
 * @param targetFrameMs Target frame time in milliseconds (default 40ms)
 * @param codec Optional codec for encoding mixed output before pushing to sink
 * @param sink Downstream sink to receive mixed frames
 * @param globalGain Global gain in dB (0.0 = unity gain, negative = attenuate)
 */
class Mixer(
    private val targetFrameMs: Int = 40,
    var codec: Codec? = null,
    var sink: Sink? = null,
    private var globalGain: Float = 0.0f
) : LocalSource() {

    companion object {
        private const val TAG = "Columba:Mixer"

        /** Maximum frames per source queue (backpressure threshold) */
        const val MAX_FRAMES = 8
    }

    // Source properties (from LocalSource)
    override var sampleRate: Int = 0  // Auto-detected from first source
    override var channels: Int = 1

    // Per-source frame queues
    private val incomingFrames = mutableMapOf<Source, ArrayDeque<FloatArray>>()
    private val insertLock = Any()

    // Mixer thread state
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shouldRun = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)

    // TEMP: Diagnostic counter for frames pushed to downstream sink
    private var mixedFrameCount = 0L

    // ===== Sink-like methods (implemented directly, not via inheritance) =====

    /**
     * Check if mixer can accept more frames from a source.
     *
     * Used for backpressure - source checks before pushing.
     * Returns false when source's queue is full.
     *
     * @param fromSource Source requesting to push a frame
     * @return true if mixer can accept a frame, false if backpressure active
     */
    fun canReceive(fromSource: Source? = null): Boolean {
        fromSource ?: return true
        synchronized(insertLock) {
            val queue = incomingFrames[fromSource] ?: return true
            return queue.size < MAX_FRAMES
        }
    }

    /**
     * Handle an incoming audio frame from a source.
     *
     * Frames are stored in per-source queues for mixing by background coroutine.
     * Frame format is float32 samples in range [-1.0, 1.0] (already decoded).
     *
     * Auto-detects sample rate and channels from first source.
     *
     * @param frame Float32 audio samples (already decoded)
     * @param source Source that produced this frame
     */
    fun handleFrame(frame: FloatArray, source: Source? = null) {
        source ?: return
        synchronized(insertLock) {
            // Create queue if first frame from this source
            if (!incomingFrames.containsKey(source)) {
                incomingFrames[source] = ArrayDeque(MAX_FRAMES)
                // Auto-detect sample rate from first source
                if (sampleRate == 0) {
                    sampleRate = source.sampleRate
                    channels = source.channels
                }
            }

            val queue = incomingFrames[source]!!
            // Add to queue (drop oldest if full)
            if (queue.size >= MAX_FRAMES) {
                queue.removeFirst()
            }
            queue.addLast(frame)
        }
    }

    // ===== Source methods (from LocalSource) =====

    /**
     * Start the mixer background coroutine.
     */
    override fun start() {
        if (shouldRun.getAndSet(true)) return
        scope.launch { mixerJob() }
    }

    /**
     * Stop the mixer and clear all queues.
     */
    override fun stop() {
        shouldRun.set(false)
        synchronized(insertLock) {
            incomingFrames.clear()
        }
    }

    /**
     * Check if mixer is currently running.
     */
    override fun isRunning(): Boolean = shouldRun.get()

    // ===== Gain control =====

    /**
     * Get the current mixing gain multiplier.
     *
     * - If muted: returns 0.0 (silence)
     * - If globalGain == 0.0: returns 1.0 (unity)
     * - Otherwise: converts dB to linear (10^(gain/10))
     */
    private val mixingGain: Float
        get() = when {
            muted.get() -> 0.0f
            globalGain == 0.0f -> 1.0f
            else -> 10f.pow(globalGain / 10f)
        }

    /**
     * Set the global gain in dB.
     *
     * @param gain Gain in dB (0.0 = unity, negative = attenuate)
     */
    fun setGain(gain: Float) {
        this.globalGain = gain
    }

    /**
     * Mute or unmute the mixer output.
     *
     * When muted, mixingGain returns 0.0 (complete silence).
     *
     * @param mute true to mute, false to unmute
     */
    fun mute(mute: Boolean = true) {
        this.muted.set(mute)
    }

    // ===== Background mixing coroutine =====

    /**
     * Background coroutine that pulls frames from all source queues,
     * mixes them together with gain, clips to [-1.0, 1.0], and pushes
     * to downstream sink.
     *
     * Matches Python LXST Mixer.py lines 101-139.
     */
    private suspend fun mixerJob() {
        while (shouldRun.get()) {
            val currentSink = sink
            if (currentSink != null && currentSink.canReceive(this)) {
                // Pull one frame from each source (fast, inside lock)
                val pulledFrames: ArrayList<FloatArray>
                synchronized(insertLock) {
                    pulledFrames = ArrayList(incomingFrames.size)
                    for ((_, queue) in incomingFrames) {
                        if (queue.isNotEmpty()) {
                            pulledFrames.add(queue.removeFirst())
                        }
                    }
                }

                if (pulledFrames.isNotEmpty()) {
                    // Mix outside lock — doesn't block handleFrame() from pushing frames
                    val gain = mixingGain
                    val mixedFrame = FloatArray(pulledFrames[0].size) { i ->
                        pulledFrames[0][i] * gain
                    }
                    for (f in 1 until pulledFrames.size) {
                        val frame = pulledFrames[f]
                        for (i in mixedFrame.indices) {
                            mixedFrame[i] += frame[i] * gain
                        }
                    }

                    // Clip in-place to prevent overflow
                    for (i in mixedFrame.indices) {
                        mixedFrame[i] = mixedFrame[i].coerceIn(-1.0f, 1.0f)
                    }

                    // Push float32 to sink
                    currentSink.handleFrame(mixedFrame, this)
                    mixedFrameCount++
                    if (mixedFrameCount % 100L == 0L) {
                        Log.d(TAG, "Mixed #$mixedFrameCount → sink, sources=${pulledFrames.size}")
                    }
                } else {
                    // No frames available, sleep briefly
                    delay((targetFrameMs / 10).toLong())
                }
            } else {
                // Sink can't receive, sleep briefly
                delay((targetFrameMs / 10).toLong())
            }
        }
    }
}
