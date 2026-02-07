package tech.torlando.lxst.codec

import org.junit.Assert.*
import org.junit.Test

/**
 * Configuration tests for Opus codec.
 *
 * Tests verify profile parameters match Python LXST specification.
 * Note: Encode/decode tests require instrumented tests (actual device) due to native JNI.
 * These tests verify the profile configuration logic only.
 */
class OpusTest {

    @Test
    fun `VOICE_LOW profile has correct parameters`() {
        assertEquals(8000, Opus.profileSamplerate(Opus.PROFILE_VOICE_LOW))
        assertEquals(1, Opus.profileChannels(Opus.PROFILE_VOICE_LOW))
        assertEquals(6000, Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_LOW))
    }

    @Test
    fun `VOICE_MEDIUM profile has correct parameters`() {
        assertEquals(24000, Opus.profileSamplerate(Opus.PROFILE_VOICE_MEDIUM))
        assertEquals(1, Opus.profileChannels(Opus.PROFILE_VOICE_MEDIUM))
        assertEquals(8000, Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM))
    }

    @Test
    fun `VOICE_HIGH profile has correct parameters`() {
        assertEquals(48000, Opus.profileSamplerate(Opus.PROFILE_VOICE_HIGH))
        assertEquals(1, Opus.profileChannels(Opus.PROFILE_VOICE_HIGH))
        assertEquals(16000, Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_HIGH))
    }

    @Test
    fun `VOICE_MAX profile has correct parameters`() {
        assertEquals(48000, Opus.profileSamplerate(Opus.PROFILE_VOICE_MAX))
        assertEquals(2, Opus.profileChannels(Opus.PROFILE_VOICE_MAX))
        assertEquals(32000, Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MAX))
    }

    @Test
    fun `AUDIO_MIN profile has correct parameters`() {
        assertEquals(8000, Opus.profileSamplerate(Opus.PROFILE_AUDIO_MIN))
        assertEquals(1, Opus.profileChannels(Opus.PROFILE_AUDIO_MIN))
        assertEquals(14000, Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MIN))
    }

    @Test
    fun `AUDIO_LOW profile has correct parameters`() {
        assertEquals(12000, Opus.profileSamplerate(Opus.PROFILE_AUDIO_LOW))
        assertEquals(1, Opus.profileChannels(Opus.PROFILE_AUDIO_LOW))
        assertEquals(14000, Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_LOW))
    }

    @Test
    fun `AUDIO_MEDIUM profile has correct parameters`() {
        assertEquals(24000, Opus.profileSamplerate(Opus.PROFILE_AUDIO_MEDIUM))
        assertEquals(2, Opus.profileChannels(Opus.PROFILE_AUDIO_MEDIUM))
        assertEquals(28000, Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MEDIUM))
    }

    @Test
    fun `AUDIO_HIGH profile has correct parameters`() {
        assertEquals(48000, Opus.profileSamplerate(Opus.PROFILE_AUDIO_HIGH))
        assertEquals(2, Opus.profileChannels(Opus.PROFILE_AUDIO_HIGH))
        assertEquals(56000, Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_HIGH))
    }

    @Test
    fun `AUDIO_MAX profile has correct parameters`() {
        assertEquals(48000, Opus.profileSamplerate(Opus.PROFILE_AUDIO_MAX))
        assertEquals(2, Opus.profileChannels(Opus.PROFILE_AUDIO_MAX))
        assertEquals(128000, Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MAX))
    }

    @Test
    fun `maxBytesPerFrame calculates correctly for VOICE_LOW 20ms`() {
        // Bitrate ceiling: 6000 bps, Frame duration: 20ms
        // Expected: ceil((6000/8) * (20/1000)) = ceil(15) = 15 bytes
        val maxBytes = Opus.maxBytesPerFrame(6000, 20f)
        assertEquals(15, maxBytes)
    }

    @Test
    fun `maxBytesPerFrame calculates correctly for AUDIO_MAX 20ms`() {
        // Bitrate ceiling: 128000 bps, Frame duration: 20ms
        // Expected: ceil((128000/8) * (20/1000)) = ceil(320) = 320 bytes
        val maxBytes = Opus.maxBytesPerFrame(128000, 20f)
        assertEquals(320, maxBytes)
    }

    @Test
    fun `maxBytesPerFrame rounds up fractional bytes`() {
        // Test with values that produce fractional bytes
        val maxBytes = Opus.maxBytesPerFrame(5999, 20f)
        // (5999/8) * 0.02 = 14.9975 -> should round up to 15
        assertEquals(15, maxBytes)
    }

    @Test
    fun `valid frame durations match Python LXST`() {
        val expected = listOf(2.5f, 5f, 10f, 20f, 40f, 60f)
        assertEquals(expected, Opus.VALID_FRAME_MS)
    }

    @Test
    fun `frame quanta matches Python LXST`() {
        assertEquals(2.5f, Opus.FRAME_QUANTA_MS, 0.001f)
    }

    @Test
    fun `frame max matches Python LXST`() {
        assertEquals(60f, Opus.FRAME_MAX_MS, 0.001f)
    }

    @Test
    fun `profile constants match Python LXST values`() {
        assertEquals(0x00, Opus.PROFILE_VOICE_LOW)
        assertEquals(0x01, Opus.PROFILE_VOICE_MEDIUM)
        assertEquals(0x02, Opus.PROFILE_VOICE_HIGH)
        assertEquals(0x03, Opus.PROFILE_VOICE_MAX)
        assertEquals(0x04, Opus.PROFILE_AUDIO_MIN)
        assertEquals(0x05, Opus.PROFILE_AUDIO_LOW)
        assertEquals(0x06, Opus.PROFILE_AUDIO_MEDIUM)
        assertEquals(0x07, Opus.PROFILE_AUDIO_HIGH)
        assertEquals(0x08, Opus.PROFILE_AUDIO_MAX)
    }

    @Test
    fun `all voice profiles return application type`() {
        // Verify all voice profiles have a non-null application type
        val voiceProfiles = listOf(
            Opus.PROFILE_VOICE_LOW,
            Opus.PROFILE_VOICE_MEDIUM,
            Opus.PROFILE_VOICE_HIGH,
            Opus.PROFILE_VOICE_MAX
        )

        for (profile in voiceProfiles) {
            val app = Opus.profileApplication(profile)
            assertNotNull("Voice profile $profile should have application type", app)
        }
    }

    @Test
    fun `all audio profiles return application type`() {
        // Verify all audio profiles have a non-null application type
        val audioProfiles = listOf(
            Opus.PROFILE_AUDIO_MIN,
            Opus.PROFILE_AUDIO_LOW,
            Opus.PROFILE_AUDIO_MEDIUM,
            Opus.PROFILE_AUDIO_HIGH,
            Opus.PROFILE_AUDIO_MAX
        )

        for (profile in audioProfiles) {
            val app = Opus.profileApplication(profile)
            assertNotNull("Audio profile $profile should have application type", app)
        }
    }

    @Test
    fun `VOICE_MAX codecChannels returns 2 (stereo)`() {
        try {
            val opus = Opus(profile = Opus.PROFILE_VOICE_MAX)
            assertEquals(2, opus.codecChannels)
        } catch (_: UnsatisfiedLinkError) {
            // JNI not available in unit tests — verify via companion method instead
            assertEquals(2, Opus.profileChannels(Opus.PROFILE_VOICE_MAX))
        }
    }

    @Test
    fun `mono codec codecChannels returns 1`() {
        try {
            val opus = Opus(profile = Opus.PROFILE_VOICE_HIGH)
            assertEquals(1, opus.codecChannels)
        } catch (_: UnsatisfiedLinkError) {
            assertEquals(1, Opus.profileChannels(Opus.PROFILE_VOICE_HIGH))
        }
    }

    @Test
    fun `stereo profiles identified correctly`() {
        // These profiles should be stereo (2 channels)
        val stereoProfiles = listOf(
            Opus.PROFILE_VOICE_MAX,
            Opus.PROFILE_AUDIO_MEDIUM,
            Opus.PROFILE_AUDIO_HIGH,
            Opus.PROFILE_AUDIO_MAX
        )
        for (p in stereoProfiles) {
            assertEquals("Profile $p should be stereo", 2, Opus.profileChannels(p))
        }

        // These profiles should be mono (1 channel)
        val monoProfiles = listOf(
            Opus.PROFILE_VOICE_LOW,
            Opus.PROFILE_VOICE_MEDIUM,
            Opus.PROFILE_VOICE_HIGH,
            Opus.PROFILE_AUDIO_MIN,
            Opus.PROFILE_AUDIO_LOW
        )
        for (p in monoProfiles) {
            assertEquals("Profile $p should be mono", 1, Opus.profileChannels(p))
        }
    }

    @Test
    fun `mono frame for stereo codec would produce valid duration after upmix`() {
        // SHQ: 48kHz, stereo, 60ms frames
        // Mono frame: 2880 samples (60ms at 48kHz mono)
        // As-is duration: 2880 / (48000 * 2) * 1000 = 30ms → INVALID
        // Mono duration: 2880 / 48000 * 1000 = 60ms → VALID (triggers upmix)
        // After upmix: 5760 samples, duration = 5760 / (48000 * 2) * 1000 = 60ms → VALID
        val monoFrameSize = 2880 // 60ms at 48kHz mono
        val samplerate = 48000
        val channels = 2

        val asIsDuration = (monoFrameSize.toFloat() / (samplerate * channels)) * 1000f
        assertFalse("30ms should not be valid", asIsDuration in Opus.VALID_FRAME_MS)

        val monoDuration = (monoFrameSize.toFloat() / samplerate) * 1000f
        assertTrue("60ms should be valid", monoDuration in Opus.VALID_FRAME_MS)

        // After upmix
        val stereoFrameSize = monoFrameSize * channels
        val afterUpmixDuration = (stereoFrameSize.toFloat() / (samplerate * channels)) * 1000f
        assertTrue("60ms after upmix should be valid", afterUpmixDuration in Opus.VALID_FRAME_MS)
    }

    @Test
    fun `bitrate ceilings are correctly ordered`() {
        // Verify bitrate ceiling progression
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_LOW) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM))
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MEDIUM) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_HIGH))
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_HIGH) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_VOICE_MAX))

        // AUDIO profiles
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MIN) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MEDIUM))
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MEDIUM) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_HIGH))
        assertTrue(Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_HIGH) <
                   Opus.profileBitrateCeiling(Opus.PROFILE_AUDIO_MAX))
    }
}
