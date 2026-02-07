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
import java.util.concurrent.atomic.AtomicLong

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

        // Time-based buffer targets — ensures all profiles get adequate jitter
        // absorption regardless of frame duration. With MQ (60ms) → 25 frames;
        // LL (20ms) → 75 frames; ULL (10ms) → 150 frames.
        const val BUFFER_CAPACITY_MS = 1500L  // Max queue depth in ms
        const val PREBUFFER_MS = 500L          // Pre-fill before playback in ms
        const val MAX_QUEUE_SLOTS = 150        // Physical queue capacity (1500ms / 10ms ULL)

        // Re-buffer policy: only sustained outages trigger re-buffer.
        // Brief jitter gaps (< 500ms) resume immediately — AudioTrack's 500ms
        // internal buffer absorbs the jitter without audible interruption.
        const val REBUFFER_TRIGGER_MS = 500L  // Underrun must last this long to trigger re-buffer
        const val REBUFFER_FRAMES = 5         // Frames needed to exit re-buffer state

        // Legacy constants for test backward compatibility and initial defaults.
        // Effective limits are recomputed from frame time in updateBufferLimits().
        const val MAX_FRAMES = 15
        const val AUTOSTART_MIN = 5
    }

    // Frame queue — generous physical capacity; effective limits enforce real depth.
    private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_QUEUE_SLOTS)

    // Effective limits recomputed when frame time is known (see updateBufferLimits).
    // Start with legacy values for backward compatibility with initial autostart.
    @Volatile private var effectiveMaxFrames: Int = MAX_FRAMES
    @Volatile private var effectiveAutostartMin: Int = AUTOSTART_MIN

    // Playback state
    private val isRunningFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Diagnostic counter for handleFrame calls (visible to digestJob for frame rate tracking)
    private val handleFrameCount = AtomicLong(0)

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
        return frameQueue.size < effectiveMaxFrames - 1
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

        // Diagnostic: log incoming frame rate (every 100 frames)
        val hfCount = handleFrameCount.incrementAndGet()
        if (hfCount % 100L == 0L) {
            Log.d(TAG, "handleFrame #$hfCount, queue=${frameQueue.size}, running=${isRunningFlag.get()}")
        }

        // Auto-start playback when buffer has minimum frames
        if (autodigest && !isRunningFlag.get() && frameQueue.size >= effectiveAutostartMin) {
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
            try {
                if (!isRunningFlag.get()) return@launch

                bridge.startPlayback(
                    sampleRate = sampleRate,
                    channels = channels,
                    lowLatency = lowLatency
                )

                // Drain excess frames that accumulated during AudioTrack creation (~500ms).
                // Keep only the most recent effectiveAutostartMin frames so playback starts
                // with a reasonable buffer, not 780ms+ of stale audio that causes permanent delay.
                val excess = frameQueue.size - effectiveAutostartMin
                if (excess > 0) {
                    repeat(excess) { frameQueue.poll() }
                    Log.i(TAG, "Drained $excess stale frames after AudioTrack init (kept $effectiveAutostartMin)")
                }

                if (isRunningFlag.get()) {
                    digestJob()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback coroutine crashed: ${e.message}", e)
            } finally {
                // CRITICAL: Reset isRunningFlag so autostart can recover from crashes.
                // Without this, a crashed digestJob leaves isRunningFlag=true permanently,
                // preventing handleFrame()'s autostart from ever restarting playback.
                isRunningFlag.set(false)
                Log.w(TAG, "Playback coroutine exited, isRunning=false (autostart can recover)")
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
        var rebufferWaitCount = 0

        while (isRunningFlag.get()) {
            // After sustained underrun (> REBUFFER_TRIGGER_MS): wait for queue to reach
            // REBUFFER_FRAMES before resuming. Only triggers for prolonged outages — brief
            // jitter gaps (< 500ms) resume immediately, letting AudioTrack absorb them.
            if (needsRebuffer) {
                if (frameQueue.size >= REBUFFER_FRAMES) {
                    needsRebuffer = false
                    rebufferWaitCount = 0
                    nextFrameNs = System.nanoTime()
                    Log.d(TAG, "Re-buffer complete, queue=${frameQueue.size}, resuming playback")
                } else {
                    rebufferWaitCount++
                    if (rebufferWaitCount % 50 == 1) {
                        Log.d(TAG, "Re-buffering: queue=${frameQueue.size}/$REBUFFER_FRAMES, " +
                            "waited ${rebufferWaitCount * frameTimeMs}ms, hfCount=${handleFrameCount.get()}")
                    }
                    delay(frameTimeMs)
                    continue
                }
            }

            // Poll with frame-period timeout so we almost always find a frame in steady state
            val frame = frameQueue.poll(frameTimeMs, TimeUnit.MILLISECONDS)

            if (frame != null) {
                // Underrun recovery: decide whether to re-buffer or resume immediately.
                // Brief gaps (< REBUFFER_TRIGGER_MS = 500ms) just reset the clock and
                // let AudioTrack's internal buffer absorb jitter. Only sustained outages
                // trigger re-buffer to prevent rapid play/underrun micro-cycles.
                if (underrunStartMs != null) {
                    val underrunMs = System.currentTimeMillis() - underrunStartMs
                    underrunStartMs = null
                    if (underrunMs >= REBUFFER_TRIGGER_MS) {
                        Log.d(TAG, "Underrun after ${underrunMs}ms, re-buffering to $REBUFFER_FRAMES frames")
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
                    updateBufferLimits(frameTimeMs)
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
                if (frameQueue.size > effectiveMaxFrames - 1) {
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
     * Recompute effective buffer limits from detected frame time.
     *
     * Converts the time-based constants (BUFFER_CAPACITY_MS, PREBUFFER_MS)
     * into frame counts for the current profile's frame duration.
     *
     * Examples:
     *   MQ  (60ms): maxFrames=25, autostartMin=8  → 1500ms/480ms
     *   LL  (20ms): maxFrames=75, autostartMin=25 → 1500ms/500ms
     *   ULL (10ms): maxFrames=150, autostartMin=50 → 1500ms/500ms
     */
    private fun updateBufferLimits(detectedFrameTimeMs: Long) {
        effectiveMaxFrames = (BUFFER_CAPACITY_MS / detectedFrameTimeMs).toInt()
            .coerceIn(MAX_FRAMES, MAX_QUEUE_SLOTS)
        effectiveAutostartMin = (PREBUFFER_MS / detectedFrameTimeMs).toInt()
            .coerceIn(AUTOSTART_MIN, effectiveMaxFrames / 2)
        Log.i(TAG, "Buffer limits: max=$effectiveMaxFrames, prebuffer=$effectiveAutostartMin " +
            "(${effectiveMaxFrames * detectedFrameTimeMs}ms/${effectiveAutostartMin * detectedFrameTimeMs}ms)")
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
