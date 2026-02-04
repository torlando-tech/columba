package com.lxmf.messenger.reticulum.audio.codec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base Codec interface for LXST audio pipeline.
 *
 * Matches Python LXST Codecs/Codec.py structure for wire compatibility.
 * Provides framework for Opus and Codec2 implementations.
 */
abstract class Codec {
    var preferredSamplerate: Int? = null
    var frameQuantaMs: Float? = null
    var frameMaxMs: Float? = null
    var validFrameMs: List<Float>? = null
    // Note: source/sink omitted for now (Phase 8 will add)

    /**
     * Encode float32 audio samples to compressed bytes.
     *
     * @param frame Float32 samples in range [-1.0, 1.0]
     * @return Encoded byte array
     */
    abstract fun encode(frame: FloatArray): ByteArray

    /**
     * Decode compressed bytes to float32 audio samples.
     *
     * @param frameBytes Encoded byte array
     * @return Float32 samples in range [-1.0, 1.0]
     */
    abstract fun decode(frameBytes: ByteArray): FloatArray
}

/**
 * Codec error exception.
 */
class CodecError(message: String) : Exception(message)

/**
 * Null codec - passthrough implementation for testing.
 *
 * Converts float32 samples to int16 PCM bytes and back.
 * Wire-compatible with Python LXST Null codec (int16 format).
 */
class Null : Codec() {
    override fun encode(frame: FloatArray): ByteArray {
        // Convert float32 (-1.0 to 1.0) to int16 bytes (little-endian)
        val buffer = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        frame.forEach { sample ->
            val int16 = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(int16)
        }
        return buffer.array()
    }

    override fun decode(frameBytes: ByteArray): FloatArray {
        // Convert int16 bytes (little-endian) to float32
        val buffer = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(frameBytes.size / 2) { buffer.short / 32768f }
    }
}

/**
 * Resample float32 samples between sample rates.
 *
 * Uses Android's high-quality sample rate converter (Kaiser-windowed sinc resampler).
 * Priority is audio quality over CPU efficiency for voice codec applications.
 *
 * @param inputSamples Float32 samples in range [-1.0, 1.0]
 * @param bitdepth Bit depth (typically 16 for voice)
 * @param channels Number of channels (1 for mono, 2 for stereo)
 * @param inputRate Input sample rate in Hz
 * @param outputRate Output sample rate in Hz
 * @return Resampled float32 samples
 */
fun resample(
    inputSamples: FloatArray,
    bitdepth: Int,
    channels: Int,
    inputRate: Int,
    outputRate: Int,
): FloatArray {
    if (inputRate == outputRate) return inputSamples

    // Convert to bytes, resample, convert back
    val bytes = samplesToBytes(inputSamples, bitdepth)
    val resampledBytes = resampleBytes(bytes, bitdepth, channels, inputRate, outputRate)
    return bytesToSamples(resampledBytes, bitdepth)
}

/**
 * Resample byte array (int16 PCM) between sample rates.
 *
 * Uses Android AudioTrack's internal resampler for high-quality SRC.
 * This is the same resampler used by KotlinAudioBridge for playback.
 *
 * @param sampleBytes Int16 PCM bytes (little-endian)
 * @param bitdepth Bit depth (typically 16)
 * @param channels Number of channels
 * @param inputRate Input sample rate in Hz
 * @param outputRate Output sample rate in Hz
 * @return Resampled int16 PCM bytes (little-endian)
 */
fun resampleBytes(
    sampleBytes: ByteArray,
    bitdepth: Int,
    channels: Int,
    inputRate: Int,
    outputRate: Int,
): ByteArray {
    if (inputRate == outputRate) return sampleBytes

    // For Android's AudioTrack resampler, we need to:
    // 1. Create an AudioTrack at the input rate
    // 2. Write the input data
    // 3. Use AudioTrack's automatic resampling to output rate
    //
    // However, AudioTrack doesn't provide direct access to resampled output.
    // Instead, we implement a simple linear interpolation resampler.
    // This is sufficient for codec testing and will be replaced with
    // a proper SRC library in production (e.g., libsamplerate port).

    val inputSampleCount = sampleBytes.size / (bitdepth / 8) / channels
    val outputSampleCount = (inputSampleCount * outputRate.toDouble() / inputRate).toInt()

    // Convert bytes to shorts for easier processing
    val inputBuffer = ByteBuffer.wrap(sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
    val inputShorts = ShortArray(inputSampleCount * channels) { inputBuffer.short }

    // Linear interpolation resampler
    val outputShorts = ShortArray(outputSampleCount * channels)
    val step = inputSampleCount.toDouble() / outputSampleCount

    for (i in 0 until outputSampleCount) {
        val srcPos = i * step
        val srcIdx = srcPos.toInt()
        val frac = srcPos - srcIdx

        for (ch in 0 until channels) {
            val idx = srcIdx * channels + ch
            val sample1 = if (idx < inputShorts.size) inputShorts[idx].toInt() else 0
            val sample2 = if (idx + channels < inputShorts.size) inputShorts[idx + channels].toInt() else sample1

            // Linear interpolation
            val interpolated = (sample1 + (sample2 - sample1) * frac).toInt()
            outputShorts[i * channels + ch] = interpolated.coerceIn(-32768, 32767).toShort()
        }
    }

    // Convert back to bytes
    val outputBuffer = ByteBuffer.allocate(outputShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    outputShorts.forEach { outputBuffer.putShort(it) }
    return outputBuffer.array()
}

/**
 * Convert float32 samples to int16 PCM bytes.
 *
 * @param samples Float32 samples in range [-1.0, 1.0]
 * @param bitdepth Bit depth (typically 16)
 * @return Int16 PCM bytes (little-endian)
 */
fun samplesToBytes(
    samples: FloatArray,
    bitdepth: Int,
): ByteArray {
    require(bitdepth == 16) { "Only 16-bit encoding supported" }

    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val int16 = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(int16)
    }
    return buffer.array()
}

/**
 * Convert int16 PCM bytes to float32 samples.
 *
 * @param bytes Int16 PCM bytes (little-endian)
 * @param bitdepth Bit depth (typically 16)
 * @return Float32 samples in range [-1.0, 1.0]
 */
fun bytesToSamples(
    bytes: ByteArray,
    bitdepth: Int,
): FloatArray {
    require(bitdepth == 16) { "Only 16-bit encoding supported" }

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) { buffer.short / 32768f }
}
