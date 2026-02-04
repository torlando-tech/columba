package com.lxmf.messenger.reticulum.audio.bridge

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * High-performance audio filters implemented in Kotlin.
 *
 * These replace the LXST Python/CFFI filters to avoid the significant
 * Python↔C bridge overhead on Android (~20-50ms per frame down to <1ms).
 *
 * Filter types:
 * - HighPass: Removes low-frequency rumble/hum (DC offset, HVAC noise)
 * - LowPass: Removes high-frequency noise (aliasing, hiss)
 * - BandPass: Combines HighPass + LowPass for voice band (300Hz - 3400Hz)
 * - AGC: Automatic Gain Control for consistent volume levels
 */
object KotlinAudioFilters {
    private const val TAG = "Columba:KotlinFilters"

    /**
     * High-pass filter state (per channel).
     */
    class HighPassState(channels: Int) {
        var filterStates = FloatArray(channels) { 0f }
        var lastInputs = FloatArray(channels) { 0f }
        var alpha: Float = 0f
        var sampleRate: Int = 0
    }

    /**
     * Low-pass filter state (per channel).
     */
    class LowPassState(channels: Int) {
        var filterStates = FloatArray(channels) { 0f }
        var alpha: Float = 0f
        var sampleRate: Int = 0
    }

    /**
     * AGC (Automatic Gain Control) state (per channel).
     */
    class AGCState(channels: Int) {
        var currentGain = FloatArray(channels) { 1f }
        var holdCounter: Int = 0
        var sampleRate: Int = 0
        var attackCoeff: Float = 0f
        var releaseCoeff: Float = 0f
        var holdSamples: Int = 0
    }

    /**
     * Combined filter chain state for voice processing.
     */
    class VoiceFilterChain(
        val channels: Int = 1,
        val highPassCutoff: Float = 300f,   // Hz - removes rumble
        val lowPassCutoff: Float = 3400f,   // Hz - voice band limit
        val agcTargetDb: Float = -12f,      // Target level in dBFS
        val agcMaxGain: Float = 12f,        // Max gain in dB
    ) {
        val highPass = HighPassState(channels)
        val lowPass = LowPassState(channels)
        val agc = AGCState(channels)

        // Working buffers (reused to avoid allocations)
        private var workBuffer: FloatArray? = null

        /**
         * Process audio samples through the filter chain.
         *
         * @param samples Input/output samples as ShortArray (modified in place)
         * @param sampleRate Sample rate in Hz
         */
        fun process(samples: ShortArray, sampleRate: Int) {
            if (samples.isEmpty()) return

            val numSamples = samples.size / channels

            // Ensure work buffer is allocated
            if (workBuffer == null || workBuffer!!.size < samples.size) {
                workBuffer = FloatArray(samples.size)
            }
            val floatSamples = workBuffer!!

            // Convert short to float (-1.0 to 1.0)
            for (i in samples.indices) {
                floatSamples[i] = samples[i] / 32768f
            }

            // Apply filter chain
            applyHighPass(floatSamples, numSamples, channels, sampleRate, highPassCutoff, highPass)
            applyLowPass(floatSamples, numSamples, channels, sampleRate, lowPassCutoff, lowPass)
            applyAGC(floatSamples, numSamples, channels, sampleRate, agcTargetDb, agcMaxGain, agc)

            // Convert float back to short with clipping
            for (i in samples.indices) {
                val clamped = floatSamples[i].coerceIn(-1f, 1f)
                samples[i] = (clamped * 32767f).toInt().toShort()
            }
        }
    }

    /**
     * Apply high-pass filter to remove low frequencies.
     *
     * Uses a simple first-order RC high-pass filter:
     * y[n] = alpha * (y[n-1] + x[n] - x[n-1])
     */
    fun applyHighPass(
        samples: FloatArray,
        numSamples: Int,
        channels: Int,
        sampleRate: Int,
        cutoffHz: Float,
        state: HighPassState,
    ) {
        // Recalculate alpha if sample rate changed
        if (state.sampleRate != sampleRate) {
            state.sampleRate = sampleRate
            val dt = 1f / sampleRate
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            state.alpha = rc / (rc + dt)
        }

        val alpha = state.alpha

        // Process first sample of each channel
        for (ch in 0 until channels) {
            val inputDiff = samples[ch] - state.lastInputs[ch]
            samples[ch] = alpha * (state.filterStates[ch] + inputDiff)
        }

        // Process remaining samples
        for (i in 1 until numSamples) {
            for (ch in 0 until channels) {
                val idx = i * channels + ch
                val prevIdx = (i - 1) * channels + ch
                val inputDiff = samples[idx] - samples[prevIdx]
                samples[idx] = alpha * (samples[prevIdx] + inputDiff)
            }
        }

        // Save state for next frame
        for (ch in 0 until channels) {
            val lastIdx = (numSamples - 1) * channels + ch
            state.filterStates[ch] = samples[lastIdx]
            state.lastInputs[ch] = samples[lastIdx]
        }
    }

    /**
     * Apply low-pass filter to remove high frequencies.
     *
     * Uses a simple first-order RC low-pass filter:
     * y[n] = alpha * x[n] + (1 - alpha) * y[n-1]
     */
    fun applyLowPass(
        samples: FloatArray,
        numSamples: Int,
        channels: Int,
        sampleRate: Int,
        cutoffHz: Float,
        state: LowPassState,
    ) {
        // Recalculate alpha if sample rate changed
        if (state.sampleRate != sampleRate) {
            state.sampleRate = sampleRate
            val dt = 1f / sampleRate
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            state.alpha = dt / (rc + dt)
        }

        val alpha = state.alpha
        val oneMinusAlpha = 1f - alpha

        // Process first sample of each channel
        for (ch in 0 until channels) {
            samples[ch] = alpha * samples[ch] + oneMinusAlpha * state.filterStates[ch]
        }

        // Process remaining samples
        for (i in 1 until numSamples) {
            for (ch in 0 until channels) {
                val idx = i * channels + ch
                val prevIdx = (i - 1) * channels + ch
                samples[idx] = alpha * samples[idx] + oneMinusAlpha * samples[prevIdx]
            }
        }

        // Save state for next frame
        for (ch in 0 until channels) {
            val lastIdx = (numSamples - 1) * channels + ch
            state.filterStates[ch] = samples[lastIdx]
        }
    }

    /**
     * Apply Automatic Gain Control (AGC).
     *
     * Adjusts gain to maintain consistent output levels:
     * - Fast attack: quickly reduces gain on loud signals
     * - Slow release: gradually increases gain during quiet periods
     * - Hold time: prevents pumping artifacts
     */
    fun applyAGC(
        samples: FloatArray,
        numSamples: Int,
        channels: Int,
        sampleRate: Int,
        targetDb: Float,
        maxGainDb: Float,
        state: AGCState,
    ) {
        // Recalculate coefficients if sample rate changed
        if (state.sampleRate != sampleRate) {
            state.sampleRate = sampleRate
            val attackTime = 0.0001f
            val releaseTime = 0.002f
            val holdTime = 0.001f
            state.attackCoeff = 1f - exp(-1f / (attackTime * sampleRate))
            state.releaseCoeff = 1f - exp(-1f / (releaseTime * sampleRate))
            state.holdSamples = (holdTime * sampleRate).toInt()
        }

        val targetLinear = Math.pow(10.0, targetDb / 10.0).toFloat()
        val maxGainLinear = Math.pow(10.0, maxGainDb / 10.0).toFloat()
        val triggerLevel = 0.003f

        // Process in blocks for efficiency
        val blockTarget = 10
        val blockSize = maxOf(1, numSamples / blockTarget)

        for (block in 0 until blockTarget) {
            val blockStart = block * blockSize
            var blockEnd = (block + 1) * blockSize
            if (block == blockTarget - 1) blockEnd = numSamples
            if (blockEnd > numSamples) blockEnd = numSamples

            val blockSamples = blockEnd - blockStart
            if (blockSamples <= 0) continue

            for (ch in 0 until channels) {
                // Calculate RMS for this block
                var sumSquares = 0f
                for (i in blockStart until blockEnd) {
                    val idx = i * channels + ch
                    sumSquares += samples[idx] * samples[idx]
                }
                val rms = sqrt(sumSquares / blockSamples)

                // Calculate target gain
                var targetGain = if (rms > 1e-9f && rms > triggerLevel) {
                    (targetLinear / rms).coerceAtMost(maxGainLinear)
                } else {
                    state.currentGain[ch]
                }

                // Smooth gain changes
                if (targetGain < state.currentGain[ch]) {
                    // Attack: reduce gain quickly
                    state.currentGain[ch] = state.attackCoeff * targetGain +
                        (1f - state.attackCoeff) * state.currentGain[ch]
                    state.holdCounter = state.holdSamples
                } else {
                    // Release: increase gain slowly (with hold)
                    if (state.holdCounter > 0) {
                        state.holdCounter -= blockSamples
                    } else {
                        state.currentGain[ch] = state.releaseCoeff * targetGain +
                            (1f - state.releaseCoeff) * state.currentGain[ch]
                    }
                }

                // Apply gain to this block
                for (i in blockStart until blockEnd) {
                    val idx = i * channels + ch
                    samples[idx] *= state.currentGain[ch]
                }
            }
        }

        // Peak limiting to prevent clipping
        val peakLimit = 0.75f
        for (ch in 0 until channels) {
            var peak = 0f
            for (i in 0 until numSamples) {
                val idx = i * channels + ch
                val absVal = abs(samples[idx])
                if (absVal > peak) peak = absVal
            }

            if (peak > peakLimit) {
                val scale = peakLimit / peak
                for (i in 0 until numSamples) {
                    val idx = i * channels + ch
                    samples[idx] *= scale
                }
            }
        }
    }
}
