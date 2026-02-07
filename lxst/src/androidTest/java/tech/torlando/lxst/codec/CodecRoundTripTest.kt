package tech.torlando.lxst.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Round-trip fidelity and bitrate ceiling tests for audio codecs.
 *
 * These tests run on Android device/emulator where JNI libraries can load.
 *
 * Fidelity verification approach:
 * - Lossy codecs (Opus, Codec2) won't produce identical output
 * - Instead, verify decoded audio is non-silent and has similar energy to input
 * - Energy ratio should be within reasonable bounds (0.1 to 10.0)
 */
@RunWith(AndroidJUnit4::class)
class CodecRoundTripTest {

    private var opus: Opus? = null
    private var codec2: Codec2? = null

    @After
    fun cleanup() {
        opus?.release()
        opus = null
        codec2?.close()
        codec2 = null
    }

    /**
     * Generate test audio: 20ms of 440Hz sine wave at given sample rate.
     */
    private fun generateTestTone(sampleRate: Int, channels: Int): FloatArray {
        val samplesPerChannel = (sampleRate * 0.02).toInt() // 20ms frame
        val totalSamples = samplesPerChannel * channels
        return FloatArray(totalSamples) { i ->
            val sampleIndex = i / channels
            val phase = (sampleIndex.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }
    }

    /**
     * Calculate RMS energy of audio samples.
     */
    private fun calculateEnergy(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        val sumSquares = samples.sumOf { (it * it).toDouble() }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }

    /**
     * Calculate peak absolute value in audio samples.
     */
    private fun calculatePeak(samples: FloatArray): Float {
        return samples.maxOfOrNull { abs(it) } ?: 0f
    }

    // ===== OPUS ROUND-TRIP FIDELITY =====

    @Test
    fun opusVoiceLow_roundTripPreservesFidelity() {
        opus = Opus(Opus.PROFILE_VOICE_LOW)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_LOW)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_LOW)
        val original = generateTestTone(sampleRate, channels)

        val originalEnergy = calculateEnergy(original)

        val encoded = opus!!.encode(original)
        val decoded = opus!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        // Decoded should not be silent
        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.01)

        // Energy ratio should be reasonable (lossy codec may change amplitude)
        val energyRatio = decodedEnergy / originalEnergy
        assertTrue(
            "Energy ratio should be between 0.1 and 10.0, was $energyRatio",
            energyRatio in 0.1..10.0
        )

        println("VOICE_LOW round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy, ratio=$energyRatio")
    }

    @Test
    fun opusVoiceHigh_roundTripPreservesFidelity() {
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH)
        val original = generateTestTone(sampleRate, channels)

        val originalEnergy = calculateEnergy(original)

        val encoded = opus!!.encode(original)
        val decoded = opus!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.01)

        val energyRatio = decodedEnergy / originalEnergy
        assertTrue(
            "Energy ratio should be between 0.1 and 10.0, was $energyRatio",
            energyRatio in 0.1..10.0
        )

        println("VOICE_HIGH round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy, ratio=$energyRatio")
    }

    @Test
    fun opusAudioMax_roundTripPreservesFidelity() {
        opus = Opus(Opus.PROFILE_AUDIO_MAX)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_MAX)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_MAX)
        val original = generateTestTone(sampleRate, channels)

        val originalEnergy = calculateEnergy(original)

        val encoded = opus!!.encode(original)
        val decoded = opus!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.01)

        val energyRatio = decodedEnergy / originalEnergy
        assertTrue(
            "Energy ratio should be between 0.1 and 10.0, was $energyRatio",
            energyRatio in 0.1..10.0
        )

        println("AUDIO_MAX round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy, ratio=$energyRatio")
    }

    // ===== OPUS AVERAGE BITRATE VALIDATION =====
    //
    // Opus VBR is a long-term average — individual frames can exceed the target.
    // The first frame is typically the largest (no encoder history to reference).
    // We encode 50 frames and check the average stays within the ceiling.

    private val BITRATE_FRAMES = 50
    // Opus VBR targets bitrate over time, not per-frame. At very low bitrates
    // (< 10 kbps), relative variance is higher, so we allow 50% headroom.
    private val BITRATE_TOLERANCE = 1.50

    /**
     * Encode [frameCount] frames and return average bytes per frame.
     * Discards the first frame to avoid measuring startup transient.
     *
     * Always generates mono input — Opus.encode() handles mono→stereo upmix
     * internally for stereo profiles (matching Python LXST behavior).
     */
    private fun averageBytesPerFrame(profile: Int, frameCount: Int): Double {
        val sampleRate = Opus.profileSamplerate(profile)
        var totalBytes = 0L
        var counted = 0

        for (i in 0 until frameCount) {
            // Always mono — encode() upmixes to stereo when needed
            val testAudio = generateTestTone(sampleRate, 1)
            val encoded = opus!!.encode(testAudio)
            if (i > 0) { // skip first frame (startup transient)
                totalBytes += encoded.size
                counted++
            }
        }
        return totalBytes.toDouble() / counted
    }

    @Test
    fun opusVoiceLow_averageBitrateWithinCeiling() {
        opus = Opus(Opus.PROFILE_VOICE_LOW)
        val bitrateCeiling = Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_LOW)
        val maxAvgBytes = Opus.maxBytesPerFrame(bitrateCeiling, 20f) * BITRATE_TOLERANCE

        val avgBytes = averageBytesPerFrame(Opus.PROFILE_VOICE_LOW, BITRATE_FRAMES)

        assertTrue(
            "VOICE_LOW avg bytes/frame (${"%.1f".format(avgBytes)}) should be <= ${"%.1f".format(maxAvgBytes)}",
            avgBytes <= maxAvgBytes
        )
        println("VOICE_LOW: avg ${"%.1f".format(avgBytes)} bytes/frame, ceiling ${"%.1f".format(maxAvgBytes)}")
    }

    @Test
    fun opusVoiceMedium_averageBitrateWithinCeiling() {
        opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val bitrateCeiling = Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM)
        val maxAvgBytes = Opus.maxBytesPerFrame(bitrateCeiling, 20f) * BITRATE_TOLERANCE

        val avgBytes = averageBytesPerFrame(Opus.PROFILE_VOICE_MEDIUM, BITRATE_FRAMES)

        assertTrue(
            "VOICE_MEDIUM avg bytes/frame (${"%.1f".format(avgBytes)}) should be <= ${"%.1f".format(maxAvgBytes)}",
            avgBytes <= maxAvgBytes
        )
        println("VOICE_MEDIUM: avg ${"%.1f".format(avgBytes)} bytes/frame, ceiling ${"%.1f".format(maxAvgBytes)}")
    }

    @Test
    fun opusVoiceHigh_averageBitrateWithinCeiling() {
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val bitrateCeiling = Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_HIGH)
        val maxAvgBytes = Opus.maxBytesPerFrame(bitrateCeiling, 20f) * BITRATE_TOLERANCE

        val avgBytes = averageBytesPerFrame(Opus.PROFILE_VOICE_HIGH, BITRATE_FRAMES)

        assertTrue(
            "VOICE_HIGH avg bytes/frame (${"%.1f".format(avgBytes)}) should be <= ${"%.1f".format(maxAvgBytes)}",
            avgBytes <= maxAvgBytes
        )
        println("VOICE_HIGH: avg ${"%.1f".format(avgBytes)} bytes/frame, ceiling ${"%.1f".format(maxAvgBytes)}")
    }

    @Test
    fun opusAudioMax_averageBitrateWithinCeiling() {
        opus = Opus(Opus.PROFILE_AUDIO_MAX)
        val bitrateCeiling = Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MAX)
        val maxAvgBytes = Opus.maxBytesPerFrame(bitrateCeiling, 20f) * BITRATE_TOLERANCE

        val avgBytes = averageBytesPerFrame(Opus.PROFILE_AUDIO_MAX, BITRATE_FRAMES)

        assertTrue(
            "AUDIO_MAX avg bytes/frame (${"%.1f".format(avgBytes)}) should be <= ${"%.1f".format(maxAvgBytes)}",
            avgBytes <= maxAvgBytes
        )
        println("AUDIO_MAX: avg ${"%.1f".format(avgBytes)} bytes/frame, ceiling ${"%.1f".format(maxAvgBytes)}")
    }

    @Test
    fun allOpusProfiles_averageBitrateWithinCeilings() {
        val profiles = listOf(
            Opus.PROFILE_VOICE_LOW to "VOICE_LOW",
            Opus.PROFILE_VOICE_MEDIUM to "VOICE_MEDIUM",
            Opus.PROFILE_VOICE_HIGH to "VOICE_HIGH",
            Opus.PROFILE_VOICE_MAX to "VOICE_MAX",
            Opus.PROFILE_AUDIO_MIN to "AUDIO_MIN",
            Opus.PROFILE_AUDIO_LOW to "AUDIO_LOW",
            Opus.PROFILE_AUDIO_MEDIUM to "AUDIO_MEDIUM",
            Opus.PROFILE_AUDIO_HIGH to "AUDIO_HIGH",
            Opus.PROFILE_AUDIO_MAX to "AUDIO_MAX"
        )

        val results = mutableListOf<String>()

        for ((profile, name) in profiles) {
            opus?.release()
            opus = Opus(profile)

            val bitrateCeiling = Opus.profileBitrateCeiling(profile)
            val maxAvgBytes = Opus.maxBytesPerFrame(bitrateCeiling, 20f) * BITRATE_TOLERANCE

            val avgBytes = averageBytesPerFrame(profile, BITRATE_FRAMES)

            assertTrue(
                "$name avg bytes/frame (${"%.1f".format(avgBytes)}) should be <= ${"%.1f".format(maxAvgBytes)}",
                avgBytes <= maxAvgBytes
            )

            val pct = (avgBytes / (Opus.maxBytesPerFrame(bitrateCeiling, 20f))).times(100)
            results.add("$name: avg ${"%.1f".format(avgBytes)} bytes (${"%.0f".format(pct)}% of ceiling)")
        }

        println("All Opus profiles average bitrate compliance:")
        results.forEach { println("  $it") }
    }

    // ===== CODEC2 ROUND-TRIP FIDELITY =====

    @Test
    fun codec2Mode2400_roundTripPreservesFidelity() {
        codec2 = Codec2(Codec2.CODEC2_2400)
        val sampleRate = 8000
        val samples = (sampleRate * 0.04).toInt() // 40ms at 8kHz
        val original = FloatArray(samples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }

        val originalEnergy = calculateEnergy(original)

        val encoded = codec2!!.encode(original)
        val decoded = codec2!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        // Codec2 is very lossy but should not be silent
        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.01)

        println("Codec2 2400 round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy")
    }

    @Test
    fun codec2Mode3200_roundTripPreservesFidelity() {
        codec2 = Codec2(Codec2.CODEC2_3200)
        val sampleRate = 8000
        val samples = (sampleRate * 0.04).toInt() // 40ms at 8kHz
        val original = FloatArray(samples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }

        val originalEnergy = calculateEnergy(original)

        val encoded = codec2!!.encode(original)
        val decoded = codec2!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.01)

        println("Codec2 3200 round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy")
    }

    @Test
    fun codec2Mode700c_roundTripPreservesFidelity() {
        codec2 = Codec2(Codec2.CODEC2_700C)
        val sampleRate = 8000
        val samples = (sampleRate * 0.04).toInt() // 40ms at 8kHz
        val original = FloatArray(samples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }

        val originalEnergy = calculateEnergy(original)

        val encoded = codec2!!.encode(original)
        val decoded = codec2!!.decode(encoded)

        val decodedEnergy = calculateEnergy(decoded)

        // 700C is extremely lossy but should not be completely silent
        assertTrue("Decoded audio should not be silent", decodedEnergy > 0.001)

        println("Codec2 700C round-trip: original energy=$originalEnergy, decoded energy=$decodedEnergy")
    }
}
