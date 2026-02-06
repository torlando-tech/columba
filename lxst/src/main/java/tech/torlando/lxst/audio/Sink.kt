package com.lxmf.messenger.reticulum.audio.lxst

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base Sink interface for LXST audio pipeline.
 *
 * Matches Python LXST Sinks.py structure. Sinks receive decoded
 * audio frames and play them through audio hardware.
 */
abstract class Sink {
    /**
     * Check if sink can accept more frames.
     *
     * Used for backpressure - source checks before pushing.
     * Returns false when internal buffer is near full.
     *
     * @param fromSource Optional source reference for multi-source scenarios
     * @return true if sink can accept a frame, false if backpressure active
     */
    abstract fun canReceive(fromSource: Source? = null): Boolean

    /**
     * Handle an incoming audio frame.
     *
     * Called by source to push decoded audio. Frame format is float32
     * samples in range [-1.0, 1.0].
     *
     * @param frame Float32 audio samples
     * @param source Optional source reference
     */
    abstract fun handleFrame(frame: FloatArray, source: Source? = null)

    /** Start playback (if not auto-started) */
    abstract fun start()

    /** Stop playback and clear buffers */
    abstract fun stop()

    /** Check if sink is currently playing */
    abstract fun isRunning(): Boolean
}

/**
 * LocalSink - base class for local audio playback (speaker).
 *
 * Subclasses: LineSink (speaker)
 * Future: OpusFileSink (file recording) - out of scope for Phase 8
 */
abstract class LocalSink : Sink()

/**
 * RemoteSink - base class for network audio (future).
 *
 * Used for sending audio to remote peers via Reticulum links.
 * Out of scope for Phase 8.
 */
abstract class RemoteSink : Sink()

// ===== Data Conversion Utilities =====
// These convert between KotlinAudioBridge format (int16 bytes) and
// LXST pipeline format (float32 arrays).

/**
 * Convert int16 PCM bytes (little-endian) to float32 samples.
 *
 * Used by LineSource to convert AudioRecord output to pipeline format.
 *
 * @param bytes Raw int16 PCM bytes from AudioRecord
 * @return Float32 samples in range [-1.0, 1.0]
 */
fun bytesToFloat32(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) { buffer.short / 32768f }
}

/**
 * Convert float32 samples to int16 PCM bytes (little-endian).
 *
 * Used by LineSink to convert pipeline format for AudioTrack.
 *
 * @param samples Float32 samples in range [-1.0, 1.0]
 * @return Raw int16 PCM bytes for AudioTrack
 */
fun float32ToBytes(samples: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val clamped = sample.coerceIn(-1f, 1f)
        val int16 = (clamped * 32767f).toInt().toShort()
        buffer.putShort(int16)
    }
    return buffer.array()
}
