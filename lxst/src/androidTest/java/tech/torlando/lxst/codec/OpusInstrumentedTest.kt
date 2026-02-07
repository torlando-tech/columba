package tech.torlando.lxst.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Opus codec.
 *
 * These tests run on Android device/emulator where JNI libraries can load.
 * Verifies actual encode/decode functionality for all 9 profiles.
 */
@RunWith(AndroidJUnit4::class)
class OpusInstrumentedTest {

    private var opus: Opus? = null

    @After
    fun cleanup() {
        opus?.release()
        opus = null
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
            kotlin.math.sin(phase) * 0.5f // 50% amplitude
        }
    }

    @Test
    fun voiceLow_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_VOICE_LOW)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_LOW) // 8000
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_LOW) // 1
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("VOICE_LOW encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun voiceMedium_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_MEDIUM)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MEDIUM)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("VOICE_MEDIUM encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun voiceHigh_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_VOICE_HIGH)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_HIGH)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("VOICE_HIGH encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun voiceMax_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_VOICE_MAX)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_MAX)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MAX)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("VOICE_MAX encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun audioMin_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_AUDIO_MIN)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_MIN)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_MIN)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("AUDIO_MIN encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun audioLow_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_AUDIO_LOW)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_LOW)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_LOW)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("AUDIO_LOW encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun audioMedium_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_AUDIO_MEDIUM)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_MEDIUM)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_MEDIUM)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("AUDIO_MEDIUM encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun audioHigh_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_AUDIO_HIGH)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_HIGH)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_HIGH)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("AUDIO_HIGH encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun audioMax_encodesSuccessfully() {
        opus = Opus(Opus.PROFILE_AUDIO_MAX)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_MAX)
        val channels = Opus.profileChannels(Opus.PROFILE_AUDIO_MAX)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("AUDIO_MAX encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun voiceLow_decodesSuccessfully() {
        opus = Opus(Opus.PROFILE_VOICE_LOW)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_LOW)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_LOW)
        val testAudio = generateTestTone(sampleRate, channels)

        val encoded = opus!!.encode(testAudio)
        val decoded = opus!!.decode(encoded)

        assertTrue("Decoded output should not be empty", decoded.isNotEmpty())
        println("VOICE_LOW round-trip: ${testAudio.size} -> ${encoded.size} bytes -> ${decoded.size} samples")
    }

    // ===== MONO→STEREO UPMIX TESTS =====
    // These test the actual bug scenario: pipeline feeds mono frames to stereo codec.
    // Before the fix, this threw CodecError("Invalid frame duration: 30.0ms").

    @Test
    fun voiceMax_monoInput_encodesSuccessfully() {
        // Simulate pipeline: mic captures mono (2880 samples = 60ms at 48kHz mono)
        // Codec expects stereo — upmix should handle this transparently
        opus = Opus(Opus.PROFILE_VOICE_MAX)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_VOICE_MAX) // 48000
        val monoSamples = (sampleRate * 0.06).toInt() // 2880 mono samples (60ms)
        val monoFrame = FloatArray(monoSamples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }

        val encoded = opus!!.encode(monoFrame)
        assertTrue("Mono input to stereo codec should encode successfully", encoded.isNotEmpty())
        println("VOICE_MAX mono→stereo encode: ${monoFrame.size} mono samples → ${encoded.size} bytes")
    }

    @Test
    fun voiceMax_monoInput_roundTripProducesStereo() {
        // Encode mono, decode should produce stereo (5760 interleaved samples)
        opus = Opus(Opus.PROFILE_VOICE_MAX)
        val sampleRate = 48000
        val monoSamples = (sampleRate * 0.06).toInt() // 2880
        val monoFrame = FloatArray(monoSamples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }

        val encoded = opus!!.encode(monoFrame)
        val decoded = opus!!.decode(encoded)

        // Decode always outputs stereo for VOICE_MAX: 2880 samples/ch × 2ch = 5760
        assertEquals(
            "Mono encode → stereo decode should produce 5760 samples",
            5760, decoded.size
        )
        assertTrue("Decoded audio should not be silent", decoded.any { it != 0f })
        println("VOICE_MAX mono→stereo round-trip: ${monoFrame.size} → ${encoded.size} bytes → ${decoded.size}")
    }

    @Test
    fun voiceMax_monoInput_sustained50Frames() {
        // Pipeline sends 50 consecutive mono frames — simulates a real call
        opus = Opus(Opus.PROFILE_VOICE_MAX)
        val sampleRate = 48000
        val monoFrame = FloatArray((sampleRate * 0.06).toInt()) { i ->
            kotlin.math.sin((i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()) * 0.5f
        }

        var successCount = 0
        repeat(50) {
            try {
                val encoded = opus!!.encode(monoFrame)
                if (encoded.isNotEmpty()) successCount++
            } catch (_: Exception) { /* count failures */ }
        }

        assertEquals("All 50 mono frames should encode to stereo successfully", 50, successCount)
    }

    @Test
    fun audioMedium_monoInput_encodesSuccessfully() {
        // AUDIO_MEDIUM is also stereo (24kHz, 2ch) — verify upmix works there too
        opus = Opus(Opus.PROFILE_AUDIO_MEDIUM)
        val sampleRate = Opus.profileSamplerate(Opus.PROFILE_AUDIO_MEDIUM) // 24000
        val monoSamples = (sampleRate * 0.02).toInt() // 480 mono samples (20ms)
        val monoFrame = FloatArray(monoSamples) { i ->
            kotlin.math.sin((i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()) * 0.5f
        }

        val encoded = opus!!.encode(monoFrame)
        assertTrue("AUDIO_MEDIUM mono input should encode successfully", encoded.isNotEmpty())
        println("AUDIO_MEDIUM mono→stereo encode: ${monoFrame.size} mono → ${encoded.size} bytes")
    }

    @Test
    fun codecChannels_returnsCorrectValues() {
        // Verify the new codecChannels property on actual Opus instances
        val mono = Opus(Opus.PROFILE_VOICE_HIGH)
        assertEquals("VOICE_HIGH codecChannels should be 1", 1, mono.codecChannels)
        mono.release()

        val stereo = Opus(Opus.PROFILE_VOICE_MAX)
        assertEquals("VOICE_MAX codecChannels should be 2", 2, stereo.codecChannels)
        stereo.release()
    }

    @Test
    fun allProfiles_encodeWithoutException() {
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

            val sampleRate = Opus.profileSamplerate(profile)
            val channels = Opus.profileChannels(profile)
            val testAudio = generateTestTone(sampleRate, channels)

            try {
                val encoded = opus!!.encode(testAudio)
                assertTrue("$name should produce non-empty output", encoded.isNotEmpty())
                results.add("$name: OK (${encoded.size} bytes)")
            } catch (e: Exception) {
                fail("$name failed to encode: ${e.message}")
            }
        }

        println("All profiles encode summary:")
        results.forEach { println("  $it") }
    }
}
