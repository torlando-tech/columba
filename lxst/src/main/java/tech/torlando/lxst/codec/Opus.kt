package tech.torlando.lxst.codec

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

        // WORKAROUND: wuqi-opus (cn.entertech.android:wuqi-opus:1.0.3) hardcodes
        // a 1024-sample output buffer in native decode() (CodecOpus.cpp):
        //   opus_int16 *outBuffer = (opus_int16*) malloc(sizeof(opus_int16) * 1024);
        // If opus_decode() output exceeds 1024 samples, it writes past the buffer,
        // corrupting the native heap and crashing CPython in the same process.
        // We cap the decoder sample rate so max frame duration fits in 1024 samples.
        // TODO: Replace wuqi-opus with libopus-android (caller-allocates pattern)
        private const val WUQI_OPUS_MAX_DECODE_SAMPLES = 1024
        private val OPUS_SUPPORTED_DECODE_RATES = listOf(48000, 24000, 16000, 12000, 8000)
    }

    private val opusJni = OpusJni()
    private var profile: Int = 0
    private var channels: Int = 0
    private var samplerate: Int = 0
    private var bitrateCeiling: Int = 0
    private var encoderConfigured = false
    private var decoderConfigured = false

    // Decode rate may differ from encoder rate due to wuqi-opus buffer limit.
    // Opus decoders resample internally (RFC 6716 §4.3) so this is lossless
    // up to the capped rate's Nyquist frequency.
    private var decodeSamplerate: Int = 0

    /** Actual sample rate the decoder outputs at. May be lower than the profile's
     *  native rate due to wuqi-opus buffer constraint (see companion object). */
    val actualDecodeSamplerate: Int get() = decodeSamplerate

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
        this.decodeSamplerate = computeSafeDecodeSamplerate()

        // Reset configuration flags (will be set on first encode/decode)
        encoderConfigured = false
        decoderConfigured = false
    }

    /**
     * Compute the highest Opus-supported decode rate where the max frame
     * duration (60ms) fits within wuqi-opus's 1024-sample output buffer.
     *
     * The buffer holds 1024 total int16 samples (not per-channel), so for
     * stereo the per-channel limit is 512. opus_decode's frame_size param
     * is per-channel, so we divide by channels.
     *
     * NOTE: Sideband sends 60ms frames (not 20ms), so we MUST use FRAME_MAX_MS
     * here. Using 20ms would allow 48kHz decode, but 2880 samples (48kHz×60ms)
     * overflows wuqi-opus's 1024-sample buffer, causing "Opus decode failed"
     * after the native heap is corrupted.
     *
     * Examples:
     * - VOICE_HIGH (mono, 60ms): 48kHz → 2880 samples/ch > 1024 → cap to 16kHz (960)
     * - VOICE_MEDIUM (mono, 60ms): 24kHz → 1440 samples/ch > 1024 → cap to 16kHz (960)
     * - VOICE_MAX (stereo, 60ms): 48kHz → 2880 samples/ch > 512 → cap to 8kHz (480)
     *
     * TODO: Replace wuqi-opus with libopus-android (caller-allocates pattern)
     * to decode at native rate without buffer overflow risk.
     */
    private fun computeSafeDecodeSamplerate(): Int {
        val maxFrameMs = FRAME_MAX_MS
        val maxSamplesPerChannel = WUQI_OPUS_MAX_DECODE_SAMPLES / channels
        val maxSamplesAtNativeRate = (samplerate * maxFrameMs / 1000f).toInt()
        if (maxSamplesAtNativeRate <= maxSamplesPerChannel) return samplerate
        return OPUS_SUPPORTED_DECODE_RATES.first {
            (it * maxFrameMs / 1000f).toInt() <= maxSamplesPerChannel
        }
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

        // Convert float32 to int16 PCM bytes (little-endian) for byte[] API.
        // Using the byte[] overload produces wire-compatible Opus packets directly,
        // avoiding the short[] overload's internal packing format which differs
        // from standard Opus byte streams.
        val pcmBytes = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val sample = (frame[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcmBytes[i * 2] = (sample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = (sample shr 8).toByte()
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

        // Encode using byte[] API — returns wire-compatible Opus packet bytes
        val frameSize = getFrameSizeConstant(frame.size)

        val encodedBytes = opusJni.encode(pcmBytes, frameSize)
            ?: throw CodecError("Opus encode failed")

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
        // Configure decoder on first use — at the safe decode rate, not the
        // profile's native rate. Opus decoders resample internally (RFC 6716).
        if (!decoderConfigured) {
            val sampleRate = getSampleRateConstant(decodeSamplerate)
            val channelConfig = if (channels == 1) Constants.Channels.mono() else Constants.Channels.stereo()

            val result = opusJni.decoderInit(sampleRate, channelConfig)
            if (result != 0) {
                throw CodecError("Opus decoder init failed: $result")
            }

            decoderConfigured = true
        }

        // Decode using byte[] API — frameBytes is a standard Opus packet.
        // The byte[] overload passes raw bytes directly to opus_decode(),
        // unlike the short[] overload which uses an internal packing format
        // incompatible with standard Opus byte streams from other encoders.
        //
        // Frame size is capped to fit wuqi-opus's hardcoded 1024-sample buffer.
        // At decodeSamplerate (e.g. 16kHz for 60ms mono), max frame duration
        // of 60ms = 960 samples/ch ≤ 1024/1. For stereo, buffer is halved.
        val maxSamplesPerChannel = minOf(
            decodeSamplerate * 120 / 1000,
            WUQI_OPUS_MAX_DECODE_SAMPLES / channels
        )
        val decodedBytes = opusJni.decode(
            frameBytes,
            Constants.FrameSize._custom(maxSamplesPerChannel),
            0  // FEC disabled
        ) ?: throw CodecError("Opus decode failed")

        // Convert int16 PCM bytes (little-endian) to float32
        val sampleCount = decodedBytes.size / 2
        return FloatArray(sampleCount) { i ->
            val lo = decodedBytes[i * 2].toInt() and 0xFF
            val hi = decodedBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sample / 32768f
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
        val decodeInfo = if (decodeSamplerate != samplerate) ", decode@${decodeSamplerate}Hz" else ""
        return "Opus($profileName, ${channels}ch, ${samplerate}Hz, ${bitrateCeiling}bps$decodeInfo)"
    }
}
