package tech.torlando.lxst.telephone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tech.torlando.lxst.audio.LineSink
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus

/**
 * Comprehensive instrumented tests for all 8 telephony profiles.
 *
 * Tests the full Profile → Codec pipeline that Telephone.kt uses:
 *   Profile.createCodec()       → encode outgoing audio
 *   Profile.createDecodeCodec() → decode incoming audio
 *
 * Verifies for each profile:
 *   - Frame size math: frameTimeMs × sampleRate / 1000 = expected samples
 *   - Encode round-trip: encode → decode produces correct sample count
 *   - Asymmetric codecs: MQ/LL/ULL encode at 24kHz, decode at 48kHz
 *   - Bitrate ceiling: encoded bytes fit within bandwidth budget
 *   - Pre-buffer timing: AUTOSTART_MIN × frameTimeMs = expected latency
 *   - Sustained operation: 50+ frames without crash or degradation
 *
 * Profiles under test:
 *   ULBW (0x10) — Codec2 700C, 400ms frames, 0.7 kbps
 *   VLBW (0x20) — Codec2 1600, 320ms frames, 1.6 kbps
 *   LBW  (0x30) — Codec2 3200, 200ms frames, 3.2 kbps
 *   MQ   (0x40) — Opus VOICE_MEDIUM→HIGH, 60ms frames, 8 kbps
 *   HQ   (0x50) — Opus VOICE_HIGH, 60ms frames, 16 kbps
 *   SHQ  (0x60) — Opus VOICE_MAX (stereo), 60ms frames, 32 kbps
 *   LL   (0x70) — Opus VOICE_MEDIUM→HIGH, 20ms frames, 8 kbps
 *   ULL  (0x80) — Opus VOICE_MEDIUM→HIGH, 10ms frames, 8 kbps
 */
@RunWith(AndroidJUnit4::class)
class ProfileInstrumentedTest {

    // -- Helpers --

    private val codecs = mutableListOf<Codec>()

    private fun trackCodec(codec: Codec): Codec {
        codecs.add(codec)
        return codec
    }

    private fun releaseAll() {
        codecs.forEach { codec ->
            when (codec) {
                is Opus -> codec.release()
                is Codec2 -> codec.close()
            }
        }
        codecs.clear()
    }

    private fun generateTone(sampleRate: Int, channels: Int, durationMs: Int): FloatArray {
        val samplesPerChannel = sampleRate * durationMs / 1000
        val totalSamples = samplesPerChannel * channels
        return FloatArray(totalSamples) { i ->
            val sampleIndex = i / channels
            val phase = (sampleIndex.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f
        }
    }

    private fun rmsEnergy(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        return kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
    }

    // =====================================================================
    //  1. PROFILE METADATA CONSISTENCY
    // =====================================================================

    @Test
    fun allProfiles_haveUniqueIds() {
        val ids = Profile.all.map { it.id }
        assertEquals("All 8 profile IDs should be unique", 8, ids.toSet().size)
    }

    @Test
    fun allProfiles_idsMatchPythonLXST() {
        // These IDs are baked into the wire protocol — changing them breaks interop
        assertEquals(0x10, Profile.ULBW.id)
        assertEquals(0x20, Profile.VLBW.id)
        assertEquals(0x30, Profile.LBW.id)
        assertEquals(0x40, Profile.MQ.id)
        assertEquals(0x50, Profile.HQ.id)
        assertEquals(0x60, Profile.SHQ.id)
        assertEquals(0x70, Profile.LL.id)
        assertEquals(0x80, Profile.ULL.id)
    }

    @Test
    fun allProfiles_frameTimesArePositive() {
        Profile.all.forEach { profile ->
            assertTrue(
                "${profile.abbreviation} frameTimeMs should be > 0, was ${profile.frameTimeMs}",
                profile.frameTimeMs > 0
            )
        }
    }

    @Test
    fun allProfiles_roundTripFromId() {
        Profile.all.forEach { profile ->
            val resolved = Profile.fromId(profile.id)
            assertNotNull("Profile.fromId(0x${profile.id.toString(16)}) should resolve", resolved)
            assertEquals(
                "fromId should return same profile for ${profile.abbreviation}",
                profile, resolved
            )
        }
    }

    // =====================================================================
    //  2. PRE-BUFFER TIMING (AUTOSTART_MIN × frameTimeMs)
    // =====================================================================

    @Test
    fun mq_preBufferIs300ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.MQ.frameTimeMs
        assertEquals("MQ pre-buffer: 5 × 60ms = 300ms", 300, preBufferMs)
    }

    @Test
    fun hq_preBufferIs300ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.HQ.frameTimeMs
        assertEquals("HQ pre-buffer: 5 × 60ms = 300ms", 300, preBufferMs)
    }

    @Test
    fun shq_preBufferIs300ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.SHQ.frameTimeMs
        assertEquals("SHQ pre-buffer: 5 × 60ms = 300ms", 300, preBufferMs)
    }

    @Test
    fun ll_preBufferIs100ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.LL.frameTimeMs
        assertEquals("LL pre-buffer: 5 × 20ms = 100ms", 100, preBufferMs)
    }

    @Test
    fun ull_preBufferIs50ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.ULL.frameTimeMs
        assertEquals("ULL pre-buffer: 5 × 10ms = 50ms", 50, preBufferMs)
    }

    @Test
    fun ulbw_preBufferIs2000ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.ULBW.frameTimeMs
        assertEquals("ULBW pre-buffer: 5 × 400ms = 2000ms", 2000, preBufferMs)
    }

    @Test
    fun vlbw_preBufferIs1600ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.VLBW.frameTimeMs
        assertEquals("VLBW pre-buffer: 5 × 320ms = 1600ms", 1600, preBufferMs)
    }

    @Test
    fun lbw_preBufferIs1000ms() {
        val preBufferMs = LineSink.AUTOSTART_MIN * Profile.LBW.frameTimeMs
        assertEquals("LBW pre-buffer: 5 × 200ms = 1000ms", 1000, preBufferMs)
    }

    // =====================================================================
    //  3. MQ (0x40) — Opus VOICE_MEDIUM encode / VOICE_HIGH decode, 60ms
    // =====================================================================

    @Test
    fun mq_encodeFrameSize_is1440samples() {
        // MQ encodes at VOICE_MEDIUM = 24kHz mono, 60ms → 24000 × 0.060 = 1440
        val codec = trackCodec(Profile.MQ.createCodec()) as Opus
        val rate = codec.preferredSamplerate!!
        assertEquals("MQ encode rate should be 24kHz", 24000, rate)

        val expectedSamples = rate * Profile.MQ.frameTimeMs / 1000
        assertEquals("MQ encode frame: 60ms at 24kHz = 1440 samples", 1440, expectedSamples)
        releaseAll()
    }

    @Test
    fun mq_decodeFrameSize_is2880samples() {
        // MQ decodes at VOICE_HIGH = 48kHz mono, 60ms → 48000 × 0.060 = 2880
        val codec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
        val rate = codec.preferredSamplerate!!
        assertEquals("MQ decode rate should be 48kHz", 48000, rate)

        val expectedSamples = rate * Profile.MQ.frameTimeMs / 1000
        assertEquals("MQ decode frame: 60ms at 48kHz = 2880 samples", 2880, expectedSamples)
        releaseAll()
    }

    @Test
    fun mq_asymmetricRoundTrip_encodeLowDecodesHigh() {
        // Encode at 24kHz, decode the same packet at 48kHz.
        // Opus handles this: the packet is bandwidth-agnostic, decoder upsamples.
        val encCodec = trackCodec(Profile.MQ.createCodec()) as Opus
        val decCodec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus

        val encRate = encCodec.preferredSamplerate!!  // 24000
        val decRate = decCodec.preferredSamplerate!!  // 48000

        val input = generateTone(encRate, 1, Profile.MQ.frameTimeMs)
        assertEquals("Input: 60ms at 24kHz = 1440", 1440, input.size)

        val encoded = encCodec.encode(input)
        assertTrue("Encoded packet should not be empty", encoded.isNotEmpty())

        val decoded = decCodec.decode(encoded)
        val expectedDecoded = decRate * Profile.MQ.frameTimeMs / 1000
        assertEquals(
            "Decoded at 48kHz should be $expectedDecoded samples (2× input)",
            expectedDecoded, decoded.size
        )

        assertTrue("Decoded audio should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun mq_averageBitrateWithinCeiling() {
        // Opus VBR may exceed the ceiling on individual frames due to packet overhead.
        // Check average over 20 frames — the ceiling is an average target, not per-packet.
        val codec = trackCodec(Profile.MQ.createCodec()) as Opus
        val rate = codec.preferredSamplerate!!
        val input = generateTone(rate, 1, Profile.MQ.frameTimeMs)

        val totalBytes = (1..20).sumOf { codec.encode(input).size }
        val avgBytes = totalBytes / 20.0
        val maxBytes = Opus.maxBytesPerFrame(
            Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM),
            Profile.MQ.frameTimeMs.toFloat()
        )

        assertTrue(
            "MQ avg ${avgBytes} bytes/frame should be near $maxBytes ceiling (±20%)",
            avgBytes <= maxBytes * 1.2
        )
        releaseAll()
    }

    @Test
    fun mq_sustained50Frames_noCorruption() {
        val encCodec = trackCodec(Profile.MQ.createCodec()) as Opus
        val decCodec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
        val input = generateTone(encCodec.preferredSamplerate!!, 1, Profile.MQ.frameTimeMs)

        var successCount = 0
        repeat(50) {
            val encoded = encCodec.encode(input)
            val decoded = decCodec.decode(encoded)
            if (decoded.isNotEmpty()) successCount++
        }

        assertEquals("All 50 MQ frames should round-trip successfully", 50, successCount)
        releaseAll()
    }

    // =====================================================================
    //  4. HQ (0x50) — Opus VOICE_HIGH encode/decode, 60ms
    // =====================================================================

    @Test
    fun hq_symmetric_bothAt48kHz() {
        val encCodec = trackCodec(Profile.HQ.createCodec()) as Opus
        val decCodec = trackCodec(Profile.HQ.createDecodeCodec()) as Opus

        assertEquals("HQ encode rate = 48kHz", 48000, encCodec.preferredSamplerate)
        assertEquals("HQ decode rate = 48kHz", 48000, decCodec.preferredSamplerate)
        releaseAll()
    }

    @Test
    fun hq_frameSize_is2880samples() {
        val expectedSamples = 48000 * Profile.HQ.frameTimeMs / 1000
        assertEquals("HQ frame: 60ms at 48kHz = 2880 samples", 2880, expectedSamples)
    }

    @Test
    fun hq_roundTrip_produces2880samples() {
        val codec = trackCodec(Profile.HQ.createCodec()) as Opus
        val input = generateTone(48000, 1, Profile.HQ.frameTimeMs)
        assertEquals(2880, input.size)

        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)

        assertEquals("HQ round-trip should produce 2880 samples", 2880, decoded.size)
        assertTrue("HQ decoded audio should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun hq_bitrateWithinCeiling() {
        val codec = trackCodec(Profile.HQ.createCodec()) as Opus
        val input = generateTone(48000, 1, Profile.HQ.frameTimeMs)

        val encoded = codec.encode(input)
        val maxBytes = Opus.maxBytesPerFrame(
            Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_HIGH),
            Profile.HQ.frameTimeMs.toFloat()
        )

        assertTrue(
            "HQ encoded ${encoded.size} bytes should fit in $maxBytes ceiling",
            encoded.size <= maxBytes
        )
        releaseAll()
    }

    @Test
    fun hq_sustained50Frames_noCorruption() {
        val codec = trackCodec(Profile.HQ.createCodec()) as Opus
        val input = generateTone(48000, 1, Profile.HQ.frameTimeMs)

        var successCount = 0
        repeat(50) {
            val encoded = codec.encode(input)
            val decoded = codec.decode(encoded)
            if (decoded.size == 2880) successCount++
        }

        assertEquals("All 50 HQ frames should produce 2880 samples", 50, successCount)
        releaseAll()
    }

    // =====================================================================
    //  5. SHQ (0x60) — Opus VOICE_MAX (stereo), 60ms
    // =====================================================================

    @Test
    fun shq_isStereo48kHz() {
        val encCodec = trackCodec(Profile.SHQ.createCodec()) as Opus
        assertEquals("SHQ rate = 48kHz", 48000, encCodec.preferredSamplerate)
        // VOICE_MAX is stereo (2 channels)
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MAX)
        assertEquals("SHQ channels = 2 (stereo)", 2, channels)
        releaseAll()
    }

    @Test
    fun shq_frameSize_is5760samples() {
        // 48kHz × 2ch × 60ms = 5760 total samples
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MAX)
        val expectedSamples = 48000 * Profile.SHQ.frameTimeMs / 1000 * channels
        assertEquals("SHQ frame: 60ms at 48kHz stereo = 5760 samples", 5760, expectedSamples)
    }

    @Test
    fun shq_roundTrip_produces5760samples() {
        val codec = trackCodec(Profile.SHQ.createCodec()) as Opus
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MAX)
        val input = generateTone(48000, channels, Profile.SHQ.frameTimeMs)
        assertEquals(5760, input.size)

        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)

        assertEquals("SHQ round-trip should produce 5760 samples", 5760, decoded.size)
        assertTrue("SHQ decoded audio should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun shq_bitrateWithinCeiling() {
        val codec = trackCodec(Profile.SHQ.createCodec()) as Opus
        val channels = Opus.profileChannels(Opus.PROFILE_VOICE_MAX)
        val input = generateTone(48000, channels, Profile.SHQ.frameTimeMs)

        val encoded = codec.encode(input)
        val maxBytes = Opus.maxBytesPerFrame(
            Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MAX),
            Profile.SHQ.frameTimeMs.toFloat()
        )

        assertTrue(
            "SHQ encoded ${encoded.size} bytes should fit in $maxBytes ceiling",
            encoded.size <= maxBytes
        )
        releaseAll()
    }

    @Test
    fun shq_monoInput_encodesWithUpmix() {
        // This is the actual pipeline scenario: mic captures mono, SHQ codec is stereo.
        // Before the fix, this threw CodecError("Invalid frame duration: 30.0ms").
        val codec = trackCodec(Profile.SHQ.createCodec()) as Opus
        val monoInput = generateTone(48000, 1, Profile.SHQ.frameTimeMs) // 2880 mono samples
        assertEquals("Mono input should be 2880 samples", 2880, monoInput.size)

        val encoded = codec.encode(monoInput)
        assertTrue("SHQ mono→stereo encode should succeed", encoded.isNotEmpty())

        val decoded = codec.decode(encoded)
        assertEquals("SHQ decode should produce 5760 stereo samples", 5760, decoded.size)
        assertTrue("SHQ decoded should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun shq_monoInput_sustained50Frames_noCorruption() {
        // Sustained mono→stereo encode simulating a real SHQ call
        val encCodec = trackCodec(Profile.SHQ.createCodec()) as Opus
        val decCodec = trackCodec(Profile.SHQ.createDecodeCodec()) as Opus
        val monoInput = generateTone(48000, 1, Profile.SHQ.frameTimeMs) // 2880 mono

        var successCount = 0
        repeat(50) {
            val encoded = encCodec.encode(monoInput)
            val decoded = decCodec.decode(encoded)
            if (decoded.size == 5760) successCount++
        }

        assertEquals("All 50 SHQ mono frames should round-trip to 5760 stereo samples", 50, successCount)
        releaseAll()
    }

    @Test
    fun shq_codecChannels_is2() {
        val codec = trackCodec(Profile.SHQ.createCodec())
        assertEquals("SHQ codec should report 2 channels", 2, codec.codecChannels)
        releaseAll()
    }

    // =====================================================================
    //  6. LL (0x70) — Opus VOICE_MEDIUM→HIGH, 20ms frames
    // =====================================================================

    @Test
    fun ll_encodeFrameSize_is480samples() {
        // LL encodes at VOICE_MEDIUM = 24kHz mono, 20ms → 480 samples
        val codec = trackCodec(Profile.LL.createCodec()) as Opus
        assertEquals("LL encode rate = 24kHz", 24000, codec.preferredSamplerate)

        val expectedSamples = 24000 * Profile.LL.frameTimeMs / 1000
        assertEquals("LL encode frame: 20ms at 24kHz = 480 samples", 480, expectedSamples)
        releaseAll()
    }

    @Test
    fun ll_decodeFrameSize_is960samples() {
        // LL decodes at VOICE_HIGH = 48kHz mono, 20ms → 960 samples
        val codec = trackCodec(Profile.LL.createDecodeCodec()) as Opus
        assertEquals("LL decode rate = 48kHz", 48000, codec.preferredSamplerate)

        val expectedSamples = 48000 * Profile.LL.frameTimeMs / 1000
        assertEquals("LL decode frame: 20ms at 48kHz = 960 samples", 960, expectedSamples)
        releaseAll()
    }

    @Test
    fun ll_asymmetricRoundTrip_encodeLowDecodesHigh() {
        val encCodec = trackCodec(Profile.LL.createCodec()) as Opus
        val decCodec = trackCodec(Profile.LL.createDecodeCodec()) as Opus

        val input = generateTone(24000, 1, Profile.LL.frameTimeMs) // 480 samples
        assertEquals(480, input.size)

        val encoded = encCodec.encode(input)
        val decoded = decCodec.decode(encoded)

        assertEquals(
            "LL decoded at 48kHz should be 960 samples (2× encode rate)",
            960, decoded.size
        )
        assertTrue("LL decoded audio should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun ll_averageBitrateWithinCeiling() {
        // 20ms frames have higher per-packet overhead ratio. Check average over 50 frames.
        val codec = trackCodec(Profile.LL.createCodec()) as Opus
        val input = generateTone(24000, 1, Profile.LL.frameTimeMs)

        val totalBytes = (1..50).sumOf { codec.encode(input).size }
        val avgBytes = totalBytes / 50.0
        val maxBytes = Opus.maxBytesPerFrame(
            Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM),
            Profile.LL.frameTimeMs.toFloat()
        )

        assertTrue(
            "LL avg ${avgBytes} bytes/frame should be near $maxBytes ceiling (±25%)",
            avgBytes <= maxBytes * 1.25
        )
        releaseAll()
    }

    @Test
    fun ll_sustained50Frames_noCorruption() {
        val encCodec = trackCodec(Profile.LL.createCodec()) as Opus
        val decCodec = trackCodec(Profile.LL.createDecodeCodec()) as Opus
        val input = generateTone(24000, 1, Profile.LL.frameTimeMs)

        var successCount = 0
        repeat(50) {
            val encoded = encCodec.encode(input)
            val decoded = decCodec.decode(encoded)
            if (decoded.size == 960) successCount++
        }

        assertEquals("All 50 LL frames should decode to 960 samples", 50, successCount)
        releaseAll()
    }

    // =====================================================================
    //  7. ULL (0x80) — Opus VOICE_MEDIUM→HIGH, 10ms frames
    // =====================================================================

    @Test
    fun ull_encodeFrameSize_is240samples() {
        // ULL encodes at VOICE_MEDIUM = 24kHz mono, 10ms → 240 samples
        val codec = trackCodec(Profile.ULL.createCodec()) as Opus
        assertEquals("ULL encode rate = 24kHz", 24000, codec.preferredSamplerate)

        val expectedSamples = 24000 * Profile.ULL.frameTimeMs / 1000
        assertEquals("ULL encode frame: 10ms at 24kHz = 240 samples", 240, expectedSamples)
        releaseAll()
    }

    @Test
    fun ull_decodeFrameSize_is480samples() {
        // ULL decodes at VOICE_HIGH = 48kHz mono, 10ms → 480 samples
        val codec = trackCodec(Profile.ULL.createDecodeCodec()) as Opus
        assertEquals("ULL decode rate = 48kHz", 48000, codec.preferredSamplerate)

        val expectedSamples = 48000 * Profile.ULL.frameTimeMs / 1000
        assertEquals("ULL decode frame: 10ms at 48kHz = 480 samples", 480, expectedSamples)
        releaseAll()
    }

    @Test
    fun ull_asymmetricRoundTrip_encodeLowDecodesHigh() {
        val encCodec = trackCodec(Profile.ULL.createCodec()) as Opus
        val decCodec = trackCodec(Profile.ULL.createDecodeCodec()) as Opus

        val input = generateTone(24000, 1, Profile.ULL.frameTimeMs) // 240 samples
        assertEquals(240, input.size)

        val encoded = encCodec.encode(input)
        val decoded = decCodec.decode(encoded)

        assertEquals(
            "ULL decoded at 48kHz should be 480 samples (2× encode rate)",
            480, decoded.size
        )
        assertTrue("ULL decoded audio should not be silent", rmsEnergy(decoded) > 0.01)
        releaseAll()
    }

    @Test
    fun ull_bitrateWithinCeiling() {
        val codec = trackCodec(Profile.ULL.createCodec()) as Opus
        val input = generateTone(24000, 1, Profile.ULL.frameTimeMs)

        val encoded = codec.encode(input)
        val maxBytes = Opus.maxBytesPerFrame(
            Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM),
            Profile.ULL.frameTimeMs.toFloat()
        )

        assertTrue(
            "ULL encoded ${encoded.size} bytes should fit in $maxBytes ceiling",
            encoded.size <= maxBytes
        )
        releaseAll()
    }

    @Test
    fun ull_sustained50Frames_noCorruption() {
        val encCodec = trackCodec(Profile.ULL.createCodec()) as Opus
        val decCodec = trackCodec(Profile.ULL.createDecodeCodec()) as Opus
        val input = generateTone(24000, 1, Profile.ULL.frameTimeMs)

        var successCount = 0
        repeat(50) {
            val encoded = encCodec.encode(input)
            val decoded = decCodec.decode(encoded)
            if (decoded.size == 480) successCount++
        }

        assertEquals("All 50 ULL frames should decode to 480 samples", 50, successCount)
        releaseAll()
    }

    // =====================================================================
    //  8. ULBW (0x10) — Codec2 700C, 400ms frames
    // =====================================================================

    @Test
    fun ulbw_frameSize_is3200samples() {
        // 8kHz × 400ms = 3200 samples
        val expectedSamples = Codec2.INPUT_RATE * Profile.ULBW.frameTimeMs / 1000
        assertEquals("ULBW frame: 400ms at 8kHz = 3200 samples", 3200, expectedSamples)
    }

    @Test
    fun ulbw_roundTrip_producesCorrectSampleCount() {
        val codec = trackCodec(Profile.ULBW.createCodec()) as Codec2
        val frameSize = Codec2.INPUT_RATE * Profile.ULBW.frameTimeMs / 1000
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.ULBW.frameTimeMs)
        assertEquals(frameSize, input.size)

        val encoded = codec.encode(input)
        assertTrue("ULBW encoded should not be empty", encoded.isNotEmpty())

        // First byte is the mode header
        assertEquals(
            "ULBW header byte should be 0x00 (700C)",
            Codec2.MODE_HEADERS[Codec2.CODEC2_700C], encoded[0]
        )

        val decoded = codec.decode(encoded)
        assertEquals(
            "ULBW round-trip: 3200 samples in → 3200 samples out",
            frameSize, decoded.size
        )
        releaseAll()
    }

    @Test
    fun ulbw_sustained20Frames_noCorruption() {
        val codec = trackCodec(Profile.ULBW.createCodec()) as Codec2
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.ULBW.frameTimeMs)
        val expectedSize = input.size

        var successCount = 0
        repeat(20) {
            val encoded = codec.encode(input)
            val decoded = codec.decode(encoded)
            if (decoded.size == expectedSize) successCount++
        }

        assertEquals("All 20 ULBW frames should round-trip correctly", 20, successCount)
        releaseAll()
    }

    // =====================================================================
    //  9. VLBW (0x20) — Codec2 1600, 320ms frames
    // =====================================================================

    @Test
    fun vlbw_frameSize_is2560samples() {
        val expectedSamples = Codec2.INPUT_RATE * Profile.VLBW.frameTimeMs / 1000
        assertEquals("VLBW frame: 320ms at 8kHz = 2560 samples", 2560, expectedSamples)
    }

    @Test
    fun vlbw_roundTrip_producesCorrectSampleCount() {
        val codec = trackCodec(Profile.VLBW.createCodec()) as Codec2
        val frameSize = Codec2.INPUT_RATE * Profile.VLBW.frameTimeMs / 1000
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.VLBW.frameTimeMs)

        val encoded = codec.encode(input)
        assertEquals(
            "VLBW header byte should be 0x04 (1600)",
            Codec2.MODE_HEADERS[Codec2.CODEC2_1600], encoded[0]
        )

        val decoded = codec.decode(encoded)
        assertEquals(
            "VLBW round-trip: 2560 samples in → 2560 samples out",
            frameSize, decoded.size
        )
        releaseAll()
    }

    @Test
    fun vlbw_sustained20Frames_noCorruption() {
        val codec = trackCodec(Profile.VLBW.createCodec()) as Codec2
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.VLBW.frameTimeMs)
        val expectedSize = input.size

        var successCount = 0
        repeat(20) {
            val encoded = codec.encode(input)
            val decoded = codec.decode(encoded)
            if (decoded.size == expectedSize) successCount++
        }

        assertEquals("All 20 VLBW frames should round-trip correctly", 20, successCount)
        releaseAll()
    }

    // =====================================================================
    //  10. LBW (0x30) — Codec2 3200, 200ms frames
    // =====================================================================

    @Test
    fun lbw_frameSize_is1600samples() {
        val expectedSamples = Codec2.INPUT_RATE * Profile.LBW.frameTimeMs / 1000
        assertEquals("LBW frame: 200ms at 8kHz = 1600 samples", 1600, expectedSamples)
    }

    @Test
    fun lbw_roundTrip_producesCorrectSampleCount() {
        val codec = trackCodec(Profile.LBW.createCodec()) as Codec2
        val frameSize = Codec2.INPUT_RATE * Profile.LBW.frameTimeMs / 1000
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.LBW.frameTimeMs)

        val encoded = codec.encode(input)
        assertEquals(
            "LBW header byte should be 0x06 (3200)",
            Codec2.MODE_HEADERS[Codec2.CODEC2_3200], encoded[0]
        )

        val decoded = codec.decode(encoded)
        assertEquals(
            "LBW round-trip: 1600 samples in → 1600 samples out",
            frameSize, decoded.size
        )
        releaseAll()
    }

    @Test
    fun lbw_sustained20Frames_noCorruption() {
        val codec = trackCodec(Profile.LBW.createCodec()) as Codec2
        val input = generateTone(Codec2.INPUT_RATE, 1, Profile.LBW.frameTimeMs)
        val expectedSize = input.size

        var successCount = 0
        repeat(20) {
            val encoded = codec.encode(input)
            val decoded = codec.decode(encoded)
            if (decoded.size == expectedSize) successCount++
        }

        assertEquals("All 20 LBW frames should round-trip correctly", 20, successCount)
        releaseAll()
    }

    // =====================================================================
    //  11. CROSS-PROFILE: Asymmetric encode/decode interop
    // =====================================================================

    @Test
    fun asymmetricProfiles_encodeMediumDecodeHigh_allFrameTimes() {
        // MQ (60ms), LL (20ms), ULL (10ms) all share the same asymmetry:
        // encode at VOICE_MEDIUM (24kHz), decode at VOICE_HIGH (48kHz).
        // Output samples should be exactly 2× input samples (rate doubling).
        val asymmetricProfiles = listOf(
            Profile.MQ to "MQ",
            Profile.LL to "LL",
            Profile.ULL to "ULL"
        )

        for ((profile, name) in asymmetricProfiles) {
            val encCodec = trackCodec(profile.createCodec()) as Opus
            val decCodec = trackCodec(profile.createDecodeCodec()) as Opus

            val encRate = encCodec.preferredSamplerate!!
            val decRate = decCodec.preferredSamplerate!!

            assertEquals("$name encode rate should be 24kHz", 24000, encRate)
            assertEquals("$name decode rate should be 48kHz", 48000, decRate)

            val inputSamples = encRate * profile.frameTimeMs / 1000
            val expectedOutput = decRate * profile.frameTimeMs / 1000

            val input = generateTone(encRate, 1, profile.frameTimeMs)
            assertEquals("$name input sample count", inputSamples, input.size)

            val encoded = encCodec.encode(input)
            val decoded = decCodec.decode(encoded)

            assertEquals(
                "$name: encode ${inputSamples} @ 24kHz → decode ${expectedOutput} @ 48kHz",
                expectedOutput, decoded.size
            )

            assertTrue("$name decoded should not be silent", rmsEnergy(decoded) > 0.01)
        }
        releaseAll()
    }

    @Test
    fun symmetricProfiles_sameRateEncodeAndDecode() {
        // HQ uses the same codec for both encode and decode (VOICE_HIGH 48kHz)
        val encCodec = trackCodec(Profile.HQ.createCodec()) as Opus
        val decCodec = trackCodec(Profile.HQ.createDecodeCodec()) as Opus

        assertEquals(
            "HQ encode and decode should use same rate",
            encCodec.preferredSamplerate, decCodec.preferredSamplerate
        )

        // SHQ uses VOICE_MAX for both (48kHz stereo)
        val shqEnc = trackCodec(Profile.SHQ.createCodec()) as Opus
        val shqDec = trackCodec(Profile.SHQ.createDecodeCodec()) as Opus

        assertEquals(
            "SHQ encode and decode should use same rate",
            shqEnc.preferredSamplerate, shqDec.preferredSamplerate
        )
        releaseAll()
    }

    // =====================================================================
    //  12. CROSS-PROFILE: Queue depth and jitter budget
    // =====================================================================

    @Test
    fun allProfiles_maxQueueCoversJitter() {
        // MAX_FRAMES × frameTimeMs = total jitter absorption budget.
        // Opus profiles (60/20/10ms) have 900/300/150ms budget.
        // Codec2 profiles (400/320/200ms) have 6000/4800/3000ms budget.
        // All should be > 100ms (minimum for network jitter).
        Profile.all.forEach { profile ->
            val budgetMs = LineSink.MAX_FRAMES * profile.frameTimeMs
            assertTrue(
                "${profile.abbreviation} jitter budget ${budgetMs}ms should be > 100ms",
                budgetMs > 100
            )
        }
    }

    // =====================================================================
    //  13. CROSS-PROFILE: Full cycle through all 8 profiles
    // =====================================================================

    @Test
    fun allProfiles_createCodec_andEncodeDecodeSucceeds() {
        val results = mutableListOf<String>()

        for (profile in Profile.all) {
            val encCodec = trackCodec(profile.createCodec())
            val decCodec = trackCodec(profile.createDecodeCodec())

            val sampleRate = encCodec.preferredSamplerate!!
            val channels = when (encCodec) {
                is Opus -> Opus.profileChannels(
                    when (profile) {
                        Profile.MQ, Profile.LL, Profile.ULL -> Opus.PROFILE_VOICE_MEDIUM
                        Profile.HQ -> Opus.PROFILE_VOICE_HIGH
                        Profile.SHQ -> Opus.PROFILE_VOICE_MAX
                        else -> error("Not Opus")
                    }
                )
                is Codec2 -> 1
                else -> 1
            }

            val input = generateTone(sampleRate, channels, profile.frameTimeMs)
            val encoded = encCodec.encode(input)
            assertTrue("${profile.abbreviation} encode should produce bytes", encoded.isNotEmpty())

            val decoded = decCodec.decode(encoded)
            assertTrue("${profile.abbreviation} decode should produce samples", decoded.isNotEmpty())

            val decRate = decCodec.preferredSamplerate!!
            val decChannels = when (decCodec) {
                is Opus -> {
                    val p = when (profile) {
                        Profile.MQ, Profile.LL, Profile.ULL -> Opus.PROFILE_VOICE_HIGH
                        Profile.HQ -> Opus.PROFILE_VOICE_HIGH
                        Profile.SHQ -> Opus.PROFILE_VOICE_MAX
                        else -> error("Not Opus")
                    }
                    Opus.profileChannels(p)
                }
                is Codec2 -> 1
                else -> 1
            }
            val expectedDecoded = decRate * profile.frameTimeMs / 1000 * decChannels

            assertEquals(
                "${profile.abbreviation} decoded sample count",
                expectedDecoded, decoded.size
            )

            results.add(
                "${profile.abbreviation} (0x${profile.id.toString(16)}): " +
                "${input.size} in → ${encoded.size} bytes → ${decoded.size} out " +
                "(${profile.frameTimeMs}ms, enc=${sampleRate}Hz, dec=${decRate}Hz)"
            )
        }

        println("Profile encode/decode summary:")
        results.forEach { println("  $it") }
        releaseAll()
    }

    // =====================================================================
    //  14. CROSS-PROFILE: Rapid profile switching (simulates mid-call switch)
    // =====================================================================

    @Test
    fun opusProfileSwitch_mqToHq_noCorruption() {
        // Simulate mid-call profile switch: encode 5 MQ frames, switch to HQ, encode 5 more
        val mqEnc = trackCodec(Profile.MQ.createCodec()) as Opus
        val mqDec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
        val mqInput = generateTone(24000, 1, Profile.MQ.frameTimeMs)

        repeat(5) {
            val encoded = mqEnc.encode(mqInput)
            val decoded = mqDec.decode(encoded)
            assertEquals("Pre-switch MQ frame $it", 2880, decoded.size)
        }

        // Switch to HQ
        val hqEnc = trackCodec(Profile.HQ.createCodec()) as Opus
        val hqDec = trackCodec(Profile.HQ.createDecodeCodec()) as Opus
        val hqInput = generateTone(48000, 1, Profile.HQ.frameTimeMs)

        repeat(5) {
            val encoded = hqEnc.encode(hqInput)
            val decoded = hqDec.decode(encoded)
            assertEquals("Post-switch HQ frame $it", 2880, decoded.size)
        }
        releaseAll()
    }

    @Test
    fun opusProfileSwitch_mqToLl_frameTimeDroppingWorks() {
        // MQ (60ms) → LL (20ms): frame time drops 3×, pre-buffer drops from 300ms to 100ms
        val mqEnc = trackCodec(Profile.MQ.createCodec()) as Opus
        val mqDec = trackCodec(Profile.MQ.createDecodeCodec()) as Opus
        val mqInput = generateTone(24000, 1, 60) // 1440 samples

        repeat(3) {
            val enc = mqEnc.encode(mqInput)
            val dec = mqDec.decode(enc)
            assertEquals("MQ frame $it should decode to 2880", 2880, dec.size)
        }

        // Switch to LL (same codec params, shorter frames)
        val llEnc = trackCodec(Profile.LL.createCodec()) as Opus
        val llDec = trackCodec(Profile.LL.createDecodeCodec()) as Opus
        val llInput = generateTone(24000, 1, 20) // 480 samples

        repeat(10) {
            val enc = llEnc.encode(llInput)
            val dec = llDec.decode(enc)
            assertEquals("LL frame $it should decode to 960", 960, dec.size)
        }
        releaseAll()
    }

    @Test
    fun codec2ProfileSwitch_ulbwToLbw_headerBytesCorrect() {
        val ulbwCodec = trackCodec(Profile.ULBW.createCodec()) as Codec2
        val ulbwInput = generateTone(8000, 1, Profile.ULBW.frameTimeMs)
        val ulbwEncoded = ulbwCodec.encode(ulbwInput)
        assertEquals("ULBW header = 0x00", 0x00.toByte(), ulbwEncoded[0])

        val lbwCodec = trackCodec(Profile.LBW.createCodec()) as Codec2
        val lbwInput = generateTone(8000, 1, Profile.LBW.frameTimeMs)
        val lbwEncoded = lbwCodec.encode(lbwInput)
        assertEquals("LBW header = 0x06", 0x06.toByte(), lbwEncoded[0])

        // Cross-decode: LBW codec decodes ULBW packet (header-based mode switch)
        val crossDecoded = lbwCodec.decode(ulbwEncoded)
        assertEquals(
            "Cross-decode should switch mode and produce ULBW frame size",
            ulbwInput.size, crossDecoded.size
        )
        releaseAll()
    }

    // =====================================================================
    //  15. EDGE CASES: Opus frame time validation
    // =====================================================================

    @Test
    fun opus_allValidFrameTimes_encodeSucceeds() {
        // Opus supports 2.5, 5, 10, 20, 40, 60ms frames.
        // Verify the three frame times used by profiles (10, 20, 60) all work.
        val frameTimes = listOf(10, 20, 60)
        val codec = trackCodec(Opus(Opus.PROFILE_VOICE_HIGH)) as Opus
        val rate = 48000

        for (ms in frameTimes) {
            val samples = rate * ms / 1000
            val input = generateTone(rate, 1, ms)
            assertEquals("$ms ms at 48kHz = $samples samples", samples, input.size)

            val encoded = codec.encode(input)
            assertTrue("${ms}ms frame should encode successfully", encoded.isNotEmpty())

            val decoded = codec.decode(encoded)
            assertEquals("${ms}ms frame round-trip sample count", samples, decoded.size)
        }
        releaseAll()
    }
}
