package com.lxmf.messenger.reticulum.audio.lxst

import android.util.Log
import com.lxmf.messenger.reticulum.audio.bridge.KotlinAudioBridge
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
        const val MAX_FRAMES = 6           // Queue depth
        const val AUTOSTART_MIN = 1        // Start playback when 1 frame ready
        const val FRAME_TIMEOUT_FRAMES = 8 // Stop after 8 frame times of underrun
    }

    // Frame queue (lock-free, thread-safe)
    private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_FRAMES)
    private val bufferMaxHeight = MAX_FRAMES - 3 // Backpressure threshold

    // Playback state
    private val isRunningFlag = AtomicBoolean(false)
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

        // Start bridge playback
        bridge.startPlayback(
            sampleRate = sampleRate,
            channels = channels,
            lowLatency = lowLatency
        )

        // Launch digest coroutine
        scope.launch { digestJob() }
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
     * 3. If no frame: track underrun, stop after timeout
     */
    private suspend fun digestJob() {
        Log.d(TAG, "Digest job started")
        var frameCount = 0L
        var underrunStartMs: Long? = null

        while (isRunningFlag.get()) {
            // Poll with timeout (10ms to stay responsive)
            val frame = frameQueue.poll(10, TimeUnit.MILLISECONDS)

            if (frame != null) {
                // Clear underrun state
                underrunStartMs = null
                frameCount++

                // Calculate frame time from first frame
                if (frameCount == 1L && sampleRate > 0) {
                    frameTimeMs = ((frame.size.toFloat() / sampleRate) * 1000).toLong()
                    Log.d(TAG, "Frame time: ${frameTimeMs}ms (${frame.size} samples at ${sampleRate}Hz)")
                }

                // Convert float32 to int16 bytes
                val frameBytes = float32ToBytes(frame)

                // Write to AudioTrack via bridge
                bridge.writeAudio(frameBytes)

                // Drop oldest if buffer is lagging (prevents increasing delay)
                if (frameQueue.size > bufferMaxHeight) {
                    frameQueue.poll()
                    Log.w(TAG, "Buffer lag, dropped oldest frame (height=${frameQueue.size})")
                }
            } else {
                // Underrun: no frames available
                if (underrunStartMs == null) {
                    underrunStartMs = System.currentTimeMillis()
                    Log.d(TAG, "Buffer underrun started")
                } else {
                    // Check timeout (stop playback after FRAME_TIMEOUT_FRAMES of silence)
                    val underrunDurationMs = System.currentTimeMillis() - underrunStartMs
                    val timeoutMs = frameTimeMs * FRAME_TIMEOUT_FRAMES

                    if (underrunDurationMs > timeoutMs) {
                        Log.i(TAG, "No frames for ${underrunDurationMs}ms, stopping playback")
                        isRunningFlag.set(false)
                    } else {
                        // Brief sleep during underrun (Python LXST Sinks.py:214-215)
                        delay(frameTimeMs / 10)
                    }
                }
            }
        }

        Log.d(TAG, "Digest job ended, played $frameCount frames")
        bridge.stopPlayback()
    }

    /**
     * Release resources.
     */
    fun release() {
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
