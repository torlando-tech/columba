package tech.torlando.lxst.telephone

import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Opus

/**
 * Quality profile definitions for LXST telephony.
 *
 * Matches Python LXST Telephony.py Profiles class exactly for wire compatibility.
 * Profile IDs are used in signalling and must match Python:
 * - ULBW=0x10, VLBW=0x20, LBW=0x30 (Codec2)
 * - MQ=0x40, HQ=0x50, SHQ=0x60 (Opus standard)
 * - LL=0x70, ULL=0x80 (Opus low-latency)
 *
 * Each profile encapsulates codec configuration and frame timing parameters.
 * Profile.createCodec() returns a properly configured codec instance.
 */
sealed class Profile(
    /** Profile ID byte (matches Python LXST) */
    val id: Int,
    /** Human-readable profile name */
    val name: String,
    /** Short abbreviation for UI display */
    val abbreviation: String,
    /** Target frame time in milliseconds */
    val frameTimeMs: Int
) {
    /**
     * Create a codec instance configured for this profile.
     *
     * @return Codec instance (Opus or Codec2) configured for this profile
     */
    abstract fun createCodec(): Codec

    /**
     * Create a codec configured for decoding received audio.
     *
     * For Opus profiles, the decoder outputs at 48000 Hz to match the
     * AudioTrack native output rate, regardless of the encoder's sample rate.
     * Opus decoders resample internally (RFC 6716 §4.3).
     *
     * For Codec2 profiles, uses the native codec rate.
     *
     * @return Codec instance configured for decode at the output sample rate
     */
    open fun createDecodeCodec(): Codec = createCodec()

    // ====== Codec2 Profiles (Low Bandwidth) ======

    /** Ultra Low Bandwidth - Codec2 700C (700 bps) */
    data object ULBW : Profile(0x10, "Ultra Low Bandwidth", "ULBW", 400) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_700C)
    }

    /** Very Low Bandwidth - Codec2 1600 (1600 bps) */
    data object VLBW : Profile(0x20, "Very Low Bandwidth", "VLBW", 320) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_1600)
    }

    /** Low Bandwidth - Codec2 3200 (3200 bps) */
    data object LBW : Profile(0x30, "Low Bandwidth", "LBW", 200) {
        override fun createCodec(): Codec = Codec2(mode = Codec2.CODEC2_3200)
    }

    // ====== Opus Profiles (Standard Quality) ======

    /** Medium Quality - Opus voice medium (8000 bps, 24kHz) */
    data object MQ : Profile(0x40, "Medium Quality", "MQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)
    }

    /** High Quality - Opus voice high (16000 bps, 48kHz) */
    data object HQ : Profile(0x50, "High Quality", "HQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)
    }

    /** Super High Quality - Opus voice max (32000 bps, 48kHz stereo) */
    data object SHQ : Profile(0x60, "Super High Quality", "SHQ", 60) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MAX)
    }

    // ====== Opus Profiles (Low Latency) ======

    /** Low Latency - Opus voice medium with 20ms frames */
    data object LL : Profile(0x70, "Low Latency", "LL", 20) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)
    }

    /** Ultra Low Latency - Opus voice medium with 10ms frames */
    data object ULL : Profile(0x80, "Ultra Low Latency", "ULL", 10) {
        override fun createCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
        override fun createDecodeCodec(): Codec = Opus(profile = Opus.PROFILE_VOICE_HIGH)
    }

    companion object {
        /** Default profile for new calls */
        val DEFAULT: Profile get() = MQ

        /** All profiles in order (low bandwidth to low latency) */
        val all: List<Profile> get() = listOf(ULBW, VLBW, LBW, MQ, HQ, SHQ, LL, ULL)

        /**
         * Look up profile by ID.
         *
         * @param id Profile ID byte
         * @return Profile or null if not found
         */
        fun fromId(id: Int): Profile? = all.find { it.id == id }

        /**
         * Get next profile in cycle (wraps around).
         *
         * @param profile Current profile
         * @return Next profile in the list
         */
        fun next(profile: Profile): Profile {
            val idx = all.indexOf(profile)
            return all[(idx + 1) % all.size]
        }
    }
}
