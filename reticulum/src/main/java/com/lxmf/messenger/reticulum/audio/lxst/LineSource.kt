package com.lxmf.messenger.reticulum.audio.lxst

import android.util.Log
import com.lxmf.messenger.reticulum.audio.bridge.KotlinAudioBridge
import com.lxmf.messenger.reticulum.audio.codec.Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * LineSource - Microphone capture for LXST audio pipeline.
 *
 * Wraps KotlinAudioBridge to provide LXST-compatible source interface.
 * Captures audio in a coroutine, applies gain, encodes with codec,
 * and pushes frames to sink.
 *
 * Matches Python LXST Sources.py LineSource class (lines 159-265).
 *
 * Phase 8 boundary: For local loopback testing, this source encodes then
 * immediately decodes before pushing to sink. In production (Phase 9-10),
 * encoded bytes go over network and decode happens on remote side.
 *
 * IMPORTANT: KotlinAudioBridge.readAudio() returns FILTERED audio.
 * Bridge applies BPF/LPF internally (<1ms overhead). LineSource only adds gain.
 *
 * @param bridge KotlinAudioBridge instance for audio capture
 * @param codec Codec instance for encoding (determines sample rate, frame size)
 * @param targetFrameMs Target frame duration in milliseconds (default 80ms)
 * @param gain Audio gain multiplier (1.0 = unity, >1.0 = boost, <1.0 = attenuate)
 */
class LineSource(
    private val bridge: KotlinAudioBridge,
    private val codec: Codec,
    targetFrameMs: Int = 80,
    private val gain: Float = 1.0f
) : LocalSource() {

    companion object {
        private const val TAG = "Columba:LineSource"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_CHANNELS = 1
    }

    /** Sink to push encoded frames to (set by Pipeline) */
    var sink: Sink? = null

    // Audio configuration (derived from codec)
    override var sampleRate: Int = DEFAULT_SAMPLE_RATE
    override var channels: Int = DEFAULT_CHANNELS

    private val samplesPerFrame: Int
    private val frameTimeMs: Int

    // Coroutine management
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunningFlag = AtomicBoolean(false)

    init {
        // Query codec for preferred sample rate (Python LXST Sources.py:202-204)
        sampleRate = codec.preferredSamplerate ?: DEFAULT_SAMPLE_RATE

        // Adjust frame time based on codec constraints (Python LXST Sources.py:196-208)
        frameTimeMs = adjustFrameTime(targetFrameMs, codec)

        // Calculate samples per frame
        samplesPerFrame = ((frameTimeMs / 1000f) * sampleRate).toInt()

        Log.d(TAG, "LineSource initialized: rate=$sampleRate, frameMs=$frameTimeMs, samples=$samplesPerFrame, gain=$gain")
    }

    /**
     * Adjust frame time to match codec constraints.
     *
     * Matches Python LXST Sources.py:196-208 logic:
     * - Quantize to codec.frameQuantaMs (e.g., Opus requires 2.5ms multiples)
     * - Clamp to codec.frameMaxMs (e.g., Opus max 120ms)
     * - Snap to codec.validFrameMs if specified
     */
    private fun adjustFrameTime(targetMs: Int, codec: Codec): Int {
        var adjusted = targetMs

        // Quantize to codec frame quanta (e.g., Opus requires 2.5ms multiples)
        codec.frameQuantaMs?.let { quanta ->
            if (adjusted % quanta.toInt() != 0) {
                adjusted = (ceil(adjusted / quanta) * quanta).toInt()
                Log.d(TAG, "Frame time quantized to ${adjusted}ms for codec")
            }
        }

        // Clamp to codec max frame size (e.g., Opus max 120ms)
        codec.frameMaxMs?.let { maxMs ->
            if (adjusted > maxMs.toInt()) {
                adjusted = maxMs.toInt()
                Log.d(TAG, "Frame time clamped to ${adjusted}ms for codec")
            }
        }

        // Snap to valid frame sizes if codec specifies them
        codec.validFrameMs?.let { validSizes ->
            val closest = validSizes.minByOrNull { kotlin.math.abs(it - adjusted) }
            if (closest != null && closest.toInt() != adjusted) {
                adjusted = closest.toInt()
                Log.d(TAG, "Frame time snapped to ${adjusted}ms (codec valid sizes)")
            }
        }

        return adjusted
    }

    override fun start() {
        if (isRunningFlag.getAndSet(true)) {
            Log.w(TAG, "LineSource already running")
            return
        }

        Log.i(TAG, "Starting LineSource: rate=$sampleRate, samples=$samplesPerFrame")

        // Start bridge recording
        bridge.startRecording(
            sampleRate = sampleRate,
            channels = channels,
            samplesPerFrame = samplesPerFrame
        )

        // Launch capture coroutine
        scope.launch { ingestJob() }
    }

    override fun stop() {
        if (!isRunningFlag.getAndSet(false)) {
            Log.w(TAG, "LineSource not running")
            return
        }

        Log.i(TAG, "Stopping LineSource")
        bridge.stopRecording()
    }

    override fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * Main capture loop - runs in coroutine.
     *
     * Matches Python LXST Sources.py:235-265 __ingest_job pattern:
     * 1. Read frame from bridge (int16 bytes, already filtered)
     * 2. Convert to float32
     * 3. Apply gain
     * 4. Encode with codec
     * 5. Decode back to float32 (Phase 8 loopback testing)
     * 6. Push decoded float32 to sink
     *
     * NOTE: In production (Phase 9), encoded bytes go over network and
     * decode happens on remote side. Phase 8 uses local loopback for testing.
     */
    private suspend fun ingestJob() {
        Log.d(TAG, "Ingest job started")
        var frameCount = 0L

        while (isRunningFlag.get()) {
            // Read raw audio from bridge (int16 bytes, already filtered)
            val frameBytes = bridge.readAudio(samplesPerFrame)
            if (frameBytes == null) {
                delay(10) // Brief pause if no data
                continue
            }

            frameCount++

            // Convert int16 bytes to float32 samples
            val frameSamples = bytesToFloat32(frameBytes)

            // Apply gain if not unity (Python LXST Sources.py:256-258)
            val gained = if (gain != 1.0f) {
                FloatArray(frameSamples.size) { i -> frameSamples[i] * gain }
            } else {
                frameSamples
            }

            // Push float32 to sink (Mixer → Packetizer handles encoding)
            val currentSink = sink
            if (currentSink != null && currentSink.canReceive(this)) {
                currentSink.handleFrame(gained, this)
            } else if (currentSink != null) {
                // Sink can't receive - drop frame (backpressure)
                if (frameCount % 50L == 0L) {
                    Log.w(TAG, "Sink backpressure, dropping frames")
                }
            }
        }

        Log.d(TAG, "Ingest job ended, captured $frameCount frames")
    }

    /**
     * Release resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }
}
