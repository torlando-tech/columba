package tech.torlando.lxst.audio

import android.util.Log
import tech.torlando.lxst.bridge.KotlinAudioBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LineSink - Speaker playback for LXST audio pipeline.
 *
 * Wraps KotlinAudioBridge to provide LXST-compatible sink interface.
 * Uses queue-based buffering with autostart and underrun handling.
 *
 * Matches Python LXST Sinks.py LineSink class (lines 118-219).
 *
 * @param bridge KotlinAudioBridge instance for audio playback
 * @param autodigest Auto-start playback when buffer reaches minimum (default true)
 * @param lowLatency Enable low-latency AudioTrack mode (default false)
 */
class LineSink(
    private val bridge: KotlinAudioBridge,
    private val autodigest: Boolean = true,
    private val lowLatency: Boolean = false
) : LocalSink() {

    companion object {
        private const val TAG = "Columba:LineSink"

        // Buffer configuration (matches Python LXST Sinks.py:119-121)
        const val MAX_FRAMES = 15          // Queue depth: 900ms at 60ms/frame for network jitter absorption
        const val AUTOSTART_MIN = 5        // Pre-fill 5 frames (300ms at MQ) before playback
    }

    // Frame queue (lock-free, thread-safe)
    private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_FRAMES)
    private val bufferMaxHeight = MAX_FRAMES - 1 // Backpressure: block 1 slot before queue full

    // Playback state
    private val isRunningFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio configuration (detected from first frame or set explicitly)
    private var sampleRate: Int = 0
    private var channels: Int = 1
    private var frameTimeMs: Long = 20 // Default, updated from frame size

    /**
     * Check if sink can accept more frames.
     *
     * Matches Python LXST Sinks.py:129-134 pattern.
     * Uses lock-free queue size check for performance.
     *
     * @param fromSource Optional source reference (unused, for interface compatibility)
     * @return true if queue has room, false if backpressure active
     */
    override fun canReceive(fromSource: Source?): Boolean {
        return frameQueue.size < bufferMaxHeight
    }

    /**
     * Handle an incoming audio frame.
     *
     * Matches Python LXST Sinks.py:136-146 pattern:
     * 1. Add frame to queue (drop oldest if full)
     * 2. Auto-start playback if threshold reached
     *
     * @param frame Float32 audio samples (decoded)
     * @param source Optional source reference for sample rate detection
     */
    override fun handleFrame(frame: FloatArray, source: Source?) {
        // Prevent stale frames from auto-restarting a released sink
        if (releasedFlag.get()) return

        // Detect sample rate from source on first frame
        if (sampleRate == 0) {
            sampleRate = source?.sampleRate ?: KotlinAudioBridge.DEFAULT_SAMPLE_RATE
            channels = source?.channels ?: 1
            Log.i(TAG, "LineSink detected: rate=$sampleRate, channels=$channels")
        }

        // Non-blocking offer, drop oldest if full (prevents blocking source)
        if (!frameQueue.offer(frame)) {
            frameQueue.poll() // Remove oldest frame
            frameQueue.offer(frame)
            Log.w(TAG, "Buffer overflow, dropped oldest frame")
        }

        // Auto-start playback when buffer has minimum frames
        if (autodigest && !isRunningFlag.get() && frameQueue.size >= AUTOSTART_MIN) {
            start()
        }
    }

    /**
     * Start playback.
     *
     * Matches Python LXST Sinks.py:148-155 pattern.
     */
    override fun start() {
        if (isRunningFlag.getAndSet(true)) {
            Log.w(TAG, "LineSink already running")
            return
        }

        Log.i(TAG, "Starting LineSink: rate=$sampleRate, channels=$channels, lowLatency=$lowLatency")

        // Launch async: AudioTrack setup takes ~500ms and must not block the Mixer thread
        scope.launch {
            if (!isRunningFlag.get()) return@launch

            bridge.startPlayback(
                sampleRate = sampleRate,
                channels = channels,
                lowLatency = lowLatency
            )

            // Drain excess frames that accumulated during AudioTrack creation (~500ms).
            // Keep only the most recent AUTOSTART_MIN frames so playback starts with
            // a reasonable buffer, not 780ms+ of stale audio that causes permanent delay.
            val excess = frameQueue.size - AUTOSTART_MIN
            if (excess > 0) {
                repeat(excess) { frameQueue.poll() }
                Log.i(TAG, "Drained $excess stale frames after AudioTrack init (kept $AUTOSTART_MIN)")
            }

            if (isRunningFlag.get()) {
                digestJob()
            }
        }
    }

    /**
     * Stop playback and clear buffers.
     *
     * Matches Python LXST Sinks.py:157-162 pattern.
     */
    override fun stop() {
        if (!isRunningFlag.getAndSet(false)) {
            Log.w(TAG, "LineSink not running")
            return
        }

        Log.i(TAG, "Stopping LineSink")
        frameQueue.clear()
        bridge.stopPlayback()
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * Main playback loop - runs in coroutine.
     *
     * Matches Python LXST Sinks.py:178-217 __digest_job pattern:
     * 1. Poll frame from queue (with timeout)
     * 2. If frame available: convert to bytes, write to bridge, drop if lagging
     * 3. If no frame: track underrun (never stop — call lifecycle handles shutdown)
     */
    private suspend fun digestJob() {
        Log.d(TAG, "Digest job started")
        var frameCount = 0L
        var underrunStartMs: Long? = null
        var nextFrameNs = 0L
        var framePeriodNs = frameTimeMs * 1_000_000L
        var needsRebuffer = false

        while (isRunningFlag.get()) {
            // After underrun: wait for queue to reach AUTOSTART_MIN before resuming.
            // This prevents rapid underrun/recovery micro-cycles (12-18ms) that cause
            // audible pops. A deliberate re-buffer silence is less noticeable.
            if (needsRebuffer) {
                if (frameQueue.size >= AUTOSTART_MIN) {
                    needsRebuffer = false
                    nextFrameNs = System.nanoTime()
                    Log.d(TAG, "Re-buffer complete, queue=${frameQueue.size}, resuming playback")
                } else {
                    delay(frameTimeMs)
                    continue
                }
            }

            // Poll with frame-period timeout so we almost always find a frame in steady state
            val frame = frameQueue.poll(frameTimeMs, TimeUnit.MILLISECONDS)

            if (frame != null) {
                // Underrun recovery: decide whether to re-buffer or resume immediately.
                // Brief glitches (<2 frame periods) just reset the clock — re-buffering
                // would cause a 300-1000ms silence for a minor hiccup. Sustained gaps
                // (>=2 frame periods) need re-buffering to absorb continued jitter.
                if (underrunStartMs != null) {
                    val underrunMs = System.currentTimeMillis() - underrunStartMs
                    underrunStartMs = null
                    if (underrunMs >= frameTimeMs * 2) {
                        Log.d(TAG, "Underrun after ${underrunMs}ms, re-buffering to $AUTOSTART_MIN frames")
                        needsRebuffer = true
                        frameQueue.offer(frame) // Put back (queue was empty, order preserved)
                        continue
                    } else {
                        Log.d(TAG, "Brief underrun ended after ${underrunMs}ms, resuming")
                        nextFrameNs = System.nanoTime() // Reset clock
                    }
                }
                frameCount++

                // Calculate frame time from frame size and update pacer.
                // Divides by (sampleRate * channels) because frame.size includes all channels.
                // Recalculates on every frame size change to handle mid-call profile switches
                // (e.g., MQ 60ms → LL 20ms) without stale pacing causing underruns.
                val currentFrameTimeMs = ((frame.size.toFloat() / (sampleRate * channels)) * 1000).toLong()
                if (frameCount == 1L || currentFrameTimeMs != frameTimeMs) {
                    if (frameCount > 1L) {
                        Log.i(TAG, "Frame size changed: ${frameTimeMs}ms → ${currentFrameTimeMs}ms")
                    }
                    frameTimeMs = currentFrameTimeMs
                    framePeriodNs = frameTimeMs * 1_000_000L
                    nextFrameNs = System.nanoTime()
                    Log.d(TAG, "Frame time: ${frameTimeMs}ms (${frame.size} samples, ${sampleRate}Hz, ${channels}ch)")
                }

                // Periodic throughput log
                if (frameCount % 100L == 0L) {
                    Log.d(TAG, "Played $frameCount frames, queue=${frameQueue.size}")
                }

                // Convert float32 to int16 bytes
                val frameBytes = float32ToBytes(frame)

                // Write to AudioTrack via bridge
                bridge.writeAudio(frameBytes)

                // Monotonic clock pacing: target absolute time for next frame.
                // Unlike relative delay(), this self-corrects jitter and has no
                // cumulative drift from safety margins.
                nextFrameNs += framePeriodNs
                val remainingNs = nextFrameNs - System.nanoTime()

                when {
                    // On schedule or slightly ahead: delay until target
                    remainingNs > 1_000_000L ->
                        delay(remainingNs / 1_000_000L)
                    // Fell behind by >3 frame periods (underrun recovery): reset clock
                    // instead of trying to catch up (which would drain the burst instantly)
                    remainingNs < -framePeriodNs * 3 ->
                        nextFrameNs = System.nanoTime()
                    // Slightly behind (<3 frames): skip delay, will self-correct
                }

                // Drop oldest if buffer is lagging (prevents increasing delay)
                if (frameQueue.size > bufferMaxHeight) {
                    frameQueue.poll()
                    Log.w(TAG, "Buffer lag, dropped oldest frame (height=${frameQueue.size})")
                }
            } else {
                // Underrun: no frames available. Never stop AudioTrack — teardown/rebuild
                // causes ~880ms gap (480ms timeout + 400ms AudioTrack creation) with
                // catastrophic frame loss. Keep polling; stop() handles shutdown.
                if (underrunStartMs == null) {
                    underrunStartMs = System.currentTimeMillis()
                    Log.d(TAG, "Buffer underrun started")
                }
                // poll(frameTimeMs) already provides efficient blocking wait
            }
        }

        Log.d(TAG, "Digest job ended, played $frameCount frames")
        bridge.stopPlayback()
    }

    /**
     * Release resources.
     */
    fun release() {
        releasedFlag.set(true)
        stop()
        scope.cancel()
    }

    /**
     * Configure sample rate explicitly (if not auto-detected from source).
     *
     * Call before start() if not using auto-detection.
     */
    fun configure(sampleRate: Int, channels: Int = 1) {
        this.sampleRate = sampleRate
        this.channels = channels
        Log.d(TAG, "LineSink configured: rate=$sampleRate, channels=$channels")
    }
}
