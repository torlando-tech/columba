package tech.torlando.lxst.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests verifying Opus decode at native sample rates.
 *
 * Uses libopus-android (built from source) with caller-allocated buffers.
 * No 1024-sample buffer limit — decodes at the profile's native rate.
 *
 * These tests exercise real JNI encode/decode on device hardware to verify
 * that the native library produces correct output at all supported rates.
 */
@RunWith(AndroidJUnit4::class)
class OpusDecodeRateInstrumentedTest {

    private var opus: Opus? = null

    @After
    fun cleanup() {
        opus?.release()
        opus = null
    }

    private fun generateTestTone(sampleRate: Int, channels: Int, durationMs: Int = 20): FloatArray {
        val samplesPerChannel = (sampleRate * durationMs / 1000)
        val totalSamples = samplesPerChannel * channels
        return FloatArray(totalSamples) { i ->
            val sampleIndex = i / channels
            val phase = (sampleIndex.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }
    }

    // ===== Verify native-rate decode for each voice profile =====

    @Test
    fun voiceHigh_decodesAtNative48kHz() {
        // VOICE_HIGH: 48kHz mono. With libopus-android, 60ms × 48kHz = 2880 samples
        // fits in a caller-allocated ShortArray(2880). No buffer overflow.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)

        assertEquals("VOICE_HIGH should use 48kHz", 48000, rate)

        val testAudio = generateTestTone(rate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        val expectedSamples = rate * 60 / 1000 // 2880
        assertEquals(
            "60ms at 48kHz = $expectedSamples samples (no decode rate cap)",
            expectedSamples,
            decoded.size
        )
    }

    @Test
    fun voiceMedium_decodesAtNative24kHz() {
        // VOICE_MEDIUM: 24kHz mono. 60ms = 1440 samples.
        opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_MEDIUM)

        assertEquals("VOICE_MEDIUM should use 24kHz", 24000, rate)

        val testAudio = generateTestTone(rate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        val expectedSamples = rate * 60 / 1000 // 1440
        assertEquals(
            "60ms at 24kHz = $expectedSamples samples (no decode rate cap)",
            expectedSamples,
            decoded.size
        )
    }

    @Test
    fun voiceLow_decodesAtNative8kHz() {
        // VOICE_LOW: 8kHz mono. 60ms = 480 samples.
        opus = Opus(Opus.PROFILE_VOICE_LOW)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_LOW)

        assertEquals("VOICE_LOW should use 8kHz", 8000, rate)

        val testAudio = generateTestTone(rate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        val expectedSamples = rate * 60 / 1000 // 480
        assertEquals(
            "60ms at 8kHz = $expectedSamples samples",
            expectedSamples,
            decoded.size
        )
    }

    // ===== Round-trip at 48kHz with 60ms frames (previously crashed wuqi-opus) =====

    @Test
    fun voiceHigh_roundTrip60ms_decodesWithoutCrash() {
        // This exact scenario caused heap corruption with wuqi-opus:
        // 48kHz × 60ms = 2880 samples overflowed the 1024-sample internal buffer.
        // With libopus-android's caller-allocated buffers, this works cleanly.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH) // 48000
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH) // 1

        val testAudio = generateTestTone(nativeRate, channels, durationMs = 60)
        assertEquals("60ms at 48kHz = 2880 samples", 2880, testAudio.size)

        val encoded = opus!!.encode(testAudio)
        assertTrue("Encoding should succeed", encoded.isNotEmpty())

        val decoded = opus!!.decode(encoded)
        assertTrue("Decoding should produce samples", decoded.isNotEmpty())

        assertEquals(
            "Decoded 60ms frame at 48kHz should have 2880 samples",
            2880,
            decoded.size
        )
    }

    @Test
    fun voiceHigh_sustainedDecode_noCorruptionAfter100Frames() {
        // With wuqi-opus, the first ~100 frames decoded with corrupted heap,
        // then decode failed entirely. With libopus-android, all frames should
        // decode cleanly at native 48kHz.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH)

        val testAudio = generateTestTone(nativeRate, channels, durationMs = 60)
        val encoded = opus!!.encode(testAudio)

        var successCount = 0
        var failCount = 0

        repeat(200) {
            try {
                val decoded = opus!!.decode(encoded)
                if (decoded.isNotEmpty()) successCount++ else failCount++
            } catch (e: Exception) {
                failCount++
            }
        }

        assertEquals(
            "All 200 frames should decode at native 48kHz (got $successCount OK, $failCount failed)",
            200,
            successCount
        )
    }

    // ===== Frame timing improvements =====

    @Test
    fun voiceHigh_frameTimeAt48kHz_is60ms() {
        // At native 48kHz, 60ms frames have 2880 samples.
        // Frame time: 2880 / 48000 * 1000 = 60ms (same duration, but at full quality).
        // AUTOSTART_MIN(5) × 60ms = 300ms pre-buffer (same as before).
        // BUT: audio bandwidth is now 24kHz Nyquist (vs 8kHz at 16kHz cap).
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH) // 48000

        val testAudio = generateTestTone(rate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        val frameTimeMs = ((decoded.size.toFloat() / rate) * 1000).toLong()

        assertEquals(
            "Frame time at native 48kHz = 60ms (same latency, but full audio bandwidth)",
            60L,
            frameTimeMs
        )
    }

    @Test
    fun voiceHigh_20msFrames_alsoWork() {
        // 20ms frames at 48kHz = 960 samples. This is the standard Opus frame size.
        // If Sideband ever switches to 20ms frames, latency drops to:
        // AUTOSTART_MIN(5) × 20ms = 100ms pre-buffer.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH) // 48000

        val testAudio = generateTestTone(rate, 1, durationMs = 20)
        assertEquals("20ms at 48kHz = 960 samples", 960, testAudio.size)

        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        assertEquals(
            "20ms frames at 48kHz should produce 960 samples",
            960,
            decoded.size
        )
    }

    // ===== Audio quality verification =====

    @Test
    fun voiceHigh_decodedAudioHasCorrectAmplitude() {
        // Verify decoded audio isn't silent or clipped — basic sanity check
        // that the encode/decode round-trip preserves the signal.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val rate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)

        val testAudio = generateTestTone(rate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        val maxAmplitude = decoded.maxOrNull() ?: 0f
        val minAmplitude = decoded.minOrNull() ?: 0f

        assertTrue(
            "Decoded audio should have peak > 0.1 (not silent), got max=$maxAmplitude",
            maxAmplitude > 0.1f
        )
        assertTrue(
            "Decoded audio should have negative samples (not DC offset), got min=$minAmplitude",
            minAmplitude < -0.1f
        )
    }
}
