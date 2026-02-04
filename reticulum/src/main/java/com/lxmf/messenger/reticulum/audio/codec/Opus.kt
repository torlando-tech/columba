package com.lxmf.messenger.reticulum.audio.codec

import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus as OpusJni
import kotlin.math.ceil

/**
 * Opus codec implementation for LXST audio pipeline.
 *
 * Matches Python LXST Codecs/Opus.py structure for wire compatibility.
 * Supports 9 quality profiles from VOICE_LOW (6kbps mono) to AUDIO_MAX (128kbps stereo).
 *
 * Wire compatibility: Encoded packets are decode-compatible with Python pyogg decoder.
 * This means Kotlin-encoded packets can be decoded by Python and produce intelligible audio,
 * but byte-identical output is not guaranteed (different encoder implementations).
 */
class Opus(profile: Int = PROFILE_VOICE_LOW) : Codec() {

    // Profile constants (match Python LXST exactly)
    companion object {
        const val PROFILE_VOICE_LOW = 0x00
        const val PROFILE_VOICE_MEDIUM = 0x01
        const val PROFILE_VOICE_HIGH = 0x02
        const val PROFILE_VOICE_MAX = 0x03
        const val PROFILE_AUDIO_MIN = 0x04
        const val PROFILE_AUDIO_LOW = 0x05
        const val PROFILE_AUDIO_MEDIUM = 0x06
        const val PROFILE_AUDIO_HIGH = 0x07
        const val PROFILE_AUDIO_MAX = 0x08

        const val FRAME_QUANTA_MS = 2.5f
        const val FRAME_MAX_MS = 60f
        val VALID_FRAME_MS = listOf(2.5f, 5f, 10f, 20f, 40f, 60f)

        /**
         * Get number of channels for a profile.
         */
        fun profileChannels(profile: Int): Int = when (profile) {
            PROFILE_VOICE_LOW, PROFILE_VOICE_MEDIUM, PROFILE_VOICE_HIGH -> 1
            PROFILE_VOICE_MAX -> 2
            PROFILE_AUDIO_MIN, PROFILE_AUDIO_LOW -> 1
            PROFILE_AUDIO_MEDIUM, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX -> 2
            else -> throw CodecError("Unsupported profile: $profile")
        }

        /**
         * Get sample rate for a profile.
         */
        fun profileSamplerate(profile: Int): Int = when (profile) {
            PROFILE_VOICE_LOW, PROFILE_AUDIO_MIN -> 8000
            PROFILE_AUDIO_LOW -> 12000
            PROFILE_VOICE_MEDIUM, PROFILE_AUDIO_MEDIUM -> 24000
            PROFILE_VOICE_HIGH, PROFILE_VOICE_MAX, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX -> 48000
            else -> throw CodecError("Unsupported profile: $profile")
        }

        /**
         * Get application type for a profile.
         */
        fun profileApplication(profile: Int): Constants.Application = when (profile) {
            PROFILE_VOICE_LOW, PROFILE_VOICE_MEDIUM, PROFILE_VOICE_HIGH, PROFILE_VOICE_MAX ->
                Constants.Application.voip()
            PROFILE_AUDIO_MIN, PROFILE_AUDIO_LOW, PROFILE_AUDIO_MEDIUM, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX ->
                Constants.Application.audio()
            else -> throw CodecError("Unsupported profile: $profile")
        }

        /**
         * Get bitrate ceiling for a profile (in bits per second).
         */
        fun profileBitrateCeiling(profile: Int): Int = when (profile) {
            PROFILE_VOICE_LOW -> 6000
            PROFILE_VOICE_MEDIUM -> 8000
            PROFILE_AUDIO_MIN, PROFILE_AUDIO_LOW -> 14000
            PROFILE_VOICE_HIGH -> 16000
            PROFILE_AUDIO_MEDIUM -> 28000
            PROFILE_VOICE_MAX -> 32000
            PROFILE_AUDIO_HIGH -> 56000
            PROFILE_AUDIO_MAX -> 128000
            else -> throw CodecError("Unsupported profile: $profile")
        }

        /**
         * Calculate max bytes per frame based on bitrate ceiling and frame duration.
         */
        fun maxBytesPerFrame(bitrateCeiling: Int, frameDurationMs: Float): Int {
            return ceil((bitrateCeiling / 8.0) * (frameDurationMs / 1000.0)).toInt()
        }
    }

    private val opusJni = OpusJni()
    private var profile: Int = 0
    private var channels: Int = 0
    private var samplerate: Int = 0
    private var bitrateCeiling: Int = 0
    private var encoderConfigured = false
    private var decoderConfigured = false

    // Track output statistics (matches Python)
    private var outputBytes = 0L
    private var outputMs = 0.0
    var outputBitrate: Double = 0.0
        private set

    init {
        frameQuantaMs = FRAME_QUANTA_MS
        frameMaxMs = FRAME_MAX_MS
        validFrameMs = VALID_FRAME_MS
        setProfile(profile)
    }

    /**
     * Configure codec for a specific quality profile.
     */
    fun setProfile(profile: Int) {
        this.profile = profile
        this.channels = profileChannels(profile)
        this.samplerate = profileSamplerate(profile)
        this.bitrateCeiling = profileBitrateCeiling(profile)
        this.preferredSamplerate = samplerate

        // Reset configuration flags (will be set on first encode/decode)
        encoderConfigured = false
        decoderConfigured = false
    }

    /**
     * Configure encoder bitrate based on frame duration.
     * Called before each encode to set max bytes per frame.
     */
    private fun updateBitrate(frameDurationMs: Float) {
        val maxBytes = maxBytesPerFrame(bitrateCeiling, frameDurationMs)
        // OpusJni doesn't expose maxBytesPerFrame directly, but we can set bitrate
        // The encoder will automatically limit output size based on bitrate
        val configuredBitrate = (maxBytes * 8.0 / (frameDurationMs / 1000.0)).toInt()

        // Use auto bitrate if within range, otherwise set explicit bitrate
        if (configuredBitrate <= bitrateCeiling) {
            opusJni.encoderSetBitrate(Constants.Bitrate.auto())
        } else {
            opusJni.encoderSetBitrate(Constants.Bitrate.instance(bitrateCeiling))
        }
    }

    /**
     * Convert sample rate to Constants.SampleRate.
     */
    private fun getSampleRateConstant(samplerate: Int): Constants.SampleRate = when (samplerate) {
        8000 -> Constants.SampleRate._8000()
        12000 -> Constants.SampleRate._12000()
        16000 -> Constants.SampleRate._16000()
        24000 -> Constants.SampleRate._24000()
        48000 -> Constants.SampleRate._48000()
        else -> throw CodecError("Unsupported sample rate: $samplerate")
    }

    /**
     * Convert frame sample count to Constants.FrameSize.
     * Frame sizes are in samples (not milliseconds).
     */
    private fun getFrameSizeConstant(sampleCount: Int): Constants.FrameSize = when (sampleCount) {
        120 -> Constants.FrameSize._120()
        160 -> Constants.FrameSize._160()
        240 -> Constants.FrameSize._240()
        320 -> Constants.FrameSize._320()
        480 -> Constants.FrameSize._480()
        640 -> Constants.FrameSize._640()
        960 -> Constants.FrameSize._960()
        1280 -> Constants.FrameSize._1280()
        1920 -> Constants.FrameSize._1920()
        2560 -> Constants.FrameSize._2560()
        2880 -> Constants.FrameSize._2880()
        else -> Constants.FrameSize._custom(sampleCount)
    }

    /**
     * Encode float32 audio samples to Opus compressed bytes.
     *
     * Wire-compatible with Python pyogg decoder.
     *
     * @param frame Float32 samples in range [-1.0, 1.0]
     * @return Opus-encoded byte array
     */
    override fun encode(frame: FloatArray): ByteArray {
        if (frame.isEmpty()) {
            throw CodecError("Cannot encode empty frame")
        }

        // Convert float32 to int16 (Opus encoder expects int16 input)
        val int16Samples = ShortArray(frame.size) { i ->
            (frame[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Calculate frame duration
        val frameDurationMs = (frame.size.toFloat() / samplerate) * 1000f

        // Validate frame duration
        if (frameDurationMs !in VALID_FRAME_MS) {
            throw CodecError("Invalid frame duration: ${frameDurationMs}ms (valid: $VALID_FRAME_MS)")
        }

        // Configure encoder on first use
        if (!encoderConfigured) {
            val sampleRate = getSampleRateConstant(samplerate)
            val channelConfig = if (channels == 1) Constants.Channels.mono() else Constants.Channels.stereo()
            val application = profileApplication(profile)

            val result = opusJni.encoderInit(sampleRate, channelConfig, application)
            if (result != 0) {
                throw CodecError("Opus encoder init failed: $result")
            }

            // Set complexity to 10 (best quality)
            opusJni.encoderSetComplexity(Constants.Complexity.instance(10))

            encoderConfigured = true
        }

        // Update bitrate for this frame
        updateBitrate(frameDurationMs)

        // Encode
        val frameSize = getFrameSizeConstant(frame.size)

        val encoded = opusJni.encode(int16Samples, frameSize)
            ?: throw CodecError("Opus encode failed")

        // Convert ShortArray to ByteArray for wire compatibility
        val encodedBytes = ByteArray(encoded.size) { encoded[it].toByte() }

        // Track statistics
        outputBytes += encodedBytes.size
        outputMs += frameDurationMs
        outputBitrate = (outputBytes * 8.0) / (outputMs / 1000.0)

        return encodedBytes
    }

    /**
     * Decode Opus compressed bytes to float32 audio samples.
     *
     * Wire-compatible with Python pyogg encoder.
     *
     * @param frameBytes Opus-encoded byte array
     * @return Float32 samples in range [-1.0, 1.0]
     */
    override fun decode(frameBytes: ByteArray): FloatArray {
        // Configure decoder on first use
        if (!decoderConfigured) {
            val sampleRate = getSampleRateConstant(samplerate)
            val channelConfig = if (channels == 1) Constants.Channels.mono() else Constants.Channels.stereo()

            val result = opusJni.decoderInit(sampleRate, channelConfig)
            if (result != 0) {
                throw CodecError("Opus decoder init failed: $result")
            }

            decoderConfigured = true
        }

        // Convert ByteArray to ShortArray for Opus decoder
        val frameShorts = ShortArray(frameBytes.size) { frameBytes[it].toShort() }

        // Decode (frame size is just a hint, decoder determines actual size from packet)
        // Use FEC disabled (0) for standard decoding
        val decoded = opusJni.decode(frameShorts, Constants.FrameSize._960(), 0)
            ?: throw CodecError("Opus decode failed")

        // Convert int16 to float32
        return FloatArray(decoded.size) { i ->
            decoded[i] / 32768f
        }
    }

    /**
     * Release native resources.
     * Call when codec is no longer needed.
     */
    fun release() {
        if (encoderConfigured) {
            opusJni.encoderRelease()
            encoderConfigured = false
        }
        if (decoderConfigured) {
            opusJni.decoderRelease()
            decoderConfigured = false
        }
    }

    override fun toString(): String {
        val profileName = when (profile) {
            PROFILE_VOICE_LOW -> "VOICE_LOW"
            PROFILE_VOICE_MEDIUM -> "VOICE_MEDIUM"
            PROFILE_VOICE_HIGH -> "VOICE_HIGH"
            PROFILE_VOICE_MAX -> "VOICE_MAX"
            PROFILE_AUDIO_MIN -> "AUDIO_MIN"
            PROFILE_AUDIO_LOW -> "AUDIO_LOW"
            PROFILE_AUDIO_MEDIUM -> "AUDIO_MEDIUM"
            PROFILE_AUDIO_HIGH -> "AUDIO_HIGH"
            PROFILE_AUDIO_MAX -> "AUDIO_MAX"
            else -> "UNKNOWN"
        }
        return "Opus($profileName, ${channels}ch, ${samplerate}Hz, ${bitrateCeiling}bps)"
    }
}
