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
        const val MAX_FRAMES = 8           // Queue depth (must be > AUTOSTART_MIN + 1)
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

        while (isRunningFlag.get()) {
            // Poll with frame-period timeout so we almost always find a frame in steady state
            val frame = frameQueue.poll(frameTimeMs, TimeUnit.MILLISECONDS)

            if (frame != null) {
                // Log underrun recovery
                if (underrunStartMs != null) {
                    val underrunMs = System.currentTimeMillis() - underrunStartMs
                    Log.d(TAG, "Underrun ended after ${underrunMs}ms")
                }
                underrunStartMs = null
                frameCount++

                // Calculate frame time from first frame
                if (frameCount == 1L && sampleRate > 0) {
                    frameTimeMs = ((frame.size.toFloat() / sampleRate) * 1000).toLong()
                    Log.d(TAG, "Frame time: ${frameTimeMs}ms (${frame.size} samples at ${sampleRate}Hz)")
                }

                // Periodic throughput log
                if (frameCount % 100L == 0L) {
                    Log.d(TAG, "Played $frameCount frames, queue=${frameQueue.size}")
                }

                // Convert float32 to int16 bytes
                val frameBytes = float32ToBytes(frame)

                // Write to AudioTrack via bridge, measuring write duration
                val writeStartNs = System.nanoTime()
                bridge.writeAudio(frameBytes)
                val writeElapsedMs = (System.nanoTime() - writeStartNs) / 1_000_000L

                // Pace output at frame rate to prevent burst-drain oscillation.
                // When AudioTrack buffer has room (after underrun), writeAudio returns
                // instantly. Without pacing, we'd drain the entire queue in microseconds,
                // causing another underrun. Pacing keeps one frame per period, turning
                // the queue into a proper jitter buffer.
                // Skip pacing during initial fill (first AUTOSTART_MIN frames) so the
                // AudioTrack buffer fills quickly.
                if (frameCount > AUTOSTART_MIN.toLong() && writeElapsedMs < frameTimeMs - 5) {
                    delay(frameTimeMs - writeElapsedMs - 2) // -2ms safety margin
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
