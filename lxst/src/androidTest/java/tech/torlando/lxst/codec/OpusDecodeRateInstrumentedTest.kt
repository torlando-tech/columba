package tech.torlando.lxst.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests verifying Opus decode behavior with wuqi-opus buffer limits.
 *
 * Reproduces the real-world failure observed in Sideband ↔ Columba calls:
 *
 * Evidence from logs (2026-02-06):
 *   - Sideband sends 60ms frames: "Frame time: 60ms (2880 samples at 48000Hz)"
 *   - Codec: Opus(VOICE_HIGH, 1ch, 48000Hz, 16000bps)
 *   - wuqi-opus has a hardcoded 1024-sample output buffer (CodecOpus.cpp)
 *   - At 48kHz, 60ms = 2880 samples → overflows 1024 buffer → native heap corruption
 *   - First ~100 frames decode (corrupted heap not yet fatal), then "Opus decode failed"
 *
 * The current workaround caps decode rate to 16kHz for profiles where 60ms frames
 * would overflow. This trades quality (8kHz Nyquist) and increases latency (60ms
 * frame period at 16kHz → 300ms pre-buffer). The real fix is replacing wuqi-opus
 * with a library that uses caller-allocated buffers (e.g., libopus-android).
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

    // ===== Document the wuqi-opus buffer constraint =====

    @Test
    fun wuqiOpusBufferLimit_is1024samples() {
        // This is the fundamental constraint. wuqi-opus (cn.entertech.android:wuqi-opus:1.0.3)
        // hardcodes: opus_int16 *outBuffer = (opus_int16*) malloc(sizeof(opus_int16) * 1024);
        // Any decode producing > 1024 samples writes past this buffer.
        //
        // At 48kHz mono, 60ms = 2880 samples → 2.8x the buffer → heap corruption
        // At 16kHz mono, 60ms = 960 samples → fits (960 ≤ 1024)

        val samplesAt48k60ms = (48000 * 60 / 1000)
        val samplesAt16k60ms = (16000 * 60 / 1000)

        assertTrue(
            "48kHz × 60ms = $samplesAt48k60ms samples, exceeds 1024-sample wuqi-opus buffer",
            samplesAt48k60ms > 1024
        )
        assertTrue(
            "16kHz × 60ms = $samplesAt16k60ms samples, fits within 1024-sample wuqi-opus buffer",
            samplesAt16k60ms <= 1024
        )
    }

    // ===== Verify decode rate capping is correct for each profile =====

    @Test
    fun voiceHigh_decodeCappedTo16kHz_because60msAt48kHzOverflows() {
        // VOICE_HIGH: 48kHz mono. Sideband sends 60ms frames.
        // 48kHz × 60ms = 2880 samples > 1024 → must cap.
        // 16kHz × 60ms = 960 samples ≤ 1024 → safe.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)

        assertEquals(
            "VOICE_HIGH decode rate should be capped to 16kHz " +
                "(48kHz × 60ms = 2880 samples > wuqi-opus 1024 buffer limit)",
            16000,
            opus!!.actualDecodeSamplerate
        )
    }

    @Test
    fun voiceMedium_decodeCappedTo16kHz_because60msAt24kHzOverflows() {
        // VOICE_MEDIUM: 24kHz mono. 24kHz × 60ms = 1440 samples > 1024 → must cap.
        opus = Opus(Opus.PROFILE_VOICE_MEDIUM)

        assertEquals(
            "VOICE_MEDIUM decode rate should be capped to 16kHz " +
                "(24kHz × 60ms = 1440 samples > wuqi-opus 1024 buffer limit)",
            16000,
            opus!!.actualDecodeSamplerate
        )
    }

    @Test
    fun voiceLow_decodeAtNativeRate_because60msAt8kHzFits() {
        // VOICE_LOW: 8kHz mono. 8kHz × 60ms = 480 samples ≤ 1024 → no cap needed.
        opus = Opus(Opus.PROFILE_VOICE_LOW)

        assertEquals(
            "VOICE_LOW should decode at native 8kHz (480 samples for 60ms ≤ 1024)",
            8000,
            opus!!.actualDecodeSamplerate
        )
    }

    // ===== Verify actual JNI decode works at capped rate =====

    @Test
    fun voiceHigh_roundTrip60ms_decodesWithoutCrash() {
        // Reproduce the exact scenario: encode 60ms at 48kHz, decode at capped 16kHz.
        // This is what happens in a real Sideband call.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH) // 48000
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH) // 1
        val decodeRate = opus!!.actualDecodeSamplerate // 16000

        // Encode 60ms at native rate (this is what Sideband sends)
        val testAudio = generateTestTone(nativeRate, channels, durationMs = 60)
        assertEquals("60ms at 48kHz = 2880 samples", 2880, testAudio.size)

        val encoded = opus!!.encode(testAudio)
        assertTrue("Encoding should succeed", encoded.isNotEmpty())

        // Decode at capped rate — should NOT crash or corrupt heap
        val decoded = opus!!.decode(encoded)
        assertTrue("Decoding should produce samples", decoded.isNotEmpty())

        // At 16kHz decode, 60ms → 960 samples (not 2880)
        val expectedSamples = decodeRate * 60 / 1000
        assertEquals(
            "Decoded 60ms frame at ${decodeRate}Hz should have $expectedSamples samples",
            expectedSamples,
            decoded.size
        )
    }

    @Test
    fun voiceHigh_sustainedDecode_noCorruptionAfter100Frames() {
        // In the failing call, the first ~100 frames decoded OK then started failing.
        // This suggests heap corruption from buffer overflow is cumulative.
        // At the capped rate, 200 frames should all decode cleanly.
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH)

        // Encode 200 × 60ms frames
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
            "All 200 frames should decode successfully at capped rate (got $successCount OK, $failCount failed)",
            200,
            successCount
        )
    }

    // ===== Verify 48kHz decode of 60ms frames WOULD overflow =====

    @Test
    fun voiceHigh_60msAt48kHz_exceedsWuqiBuffer() {
        // Document that the uncapped rate IS dangerous for 60ms frames.
        // This is why we cap — not theoretical, but proven by the call failure.
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH) // 48000
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH) // 1
        val samplesFor60ms = nativeRate * 60 / 1000 * channels

        assertTrue(
            "48kHz × 60ms × 1ch = $samplesFor60ms samples, which is ${samplesFor60ms - 1024} " +
                "over the wuqi-opus 1024-sample buffer limit. " +
                "This overflow caused 'Opus decode failed' after ~100 frames in real calls.",
            samplesFor60ms > 1024
        )
    }

    // ===== Frame timing impact on latency =====

    @Test
    fun voiceHigh_frameTimeAt16kHz_is60ms() {
        // Document the latency cost of the 16kHz cap.
        // LineSink calculates: frameTimeMs = (frame.size / sampleRate) * 1000
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val decodeRate = opus!!.actualDecodeSamplerate // 16000

        // Decode a 60ms frame
        val nativeRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)
        val testAudio = generateTestTone(nativeRate, 1, durationMs = 60)
        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        // LineSink's frame time calculation
        val frameTimeMs = ((decoded.size.toFloat() / decodeRate) * 1000).toLong()

        assertEquals(
            "Frame time at capped 16kHz = 60ms. This means AUTOSTART_MIN(5) × 60ms = 300ms " +
                "pre-buffer latency. Replacing wuqi-opus would allow 48kHz decode with 20ms " +
                "frame time and only 100ms pre-buffer.",
            60L,
            frameTimeMs
        )
    }
}
