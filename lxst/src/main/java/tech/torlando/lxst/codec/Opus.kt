package tech.torlando.lxst.codec

import android.util.Log
import tech.torlando.lxst.codec.NativeOpus as OpusNative
import kotlin.math.ceil

/**
 * Opus codec implementation for LXST audio pipeline.
 *
 * Matches Python LXST Codecs/Opus.py structure for wire compatibility.
 * Supports 9 quality profiles from VOICE_LOW (6kbps mono) to AUDIO_MAX (128kbps stereo).
 *
 * Uses libopus-android (built from source) with caller-allocated buffers.
 * No sample-count limitations — decodes at the profile's native rate.
 *
 * Wire compatibility: Encoded packets are decode-compatible with Python pyogg decoder.
 * This means Kotlin-encoded packets can be decoded by Python and produce intelligible audio,
 * but byte-identical output is not guaranteed (different encoder implementations).
 */
class Opus(profile: Int = PROFILE_VOICE_LOW) : Codec() {

    // Profile constants (match Python LXST exactly)
    companion object {
        private const val TAG = "Columba:Opus"

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

        // Max encoded packet size (Opus spec maximum is ~1275 bytes, use margin)
        private const val MAX_ENCODED_BYTES = 4000

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
         * Get Opus application type constant for a profile.
         */
        fun profileApplication(profile: Int): Int = when (profile) {
            PROFILE_VOICE_LOW, PROFILE_VOICE_MEDIUM, PROFILE_VOICE_HIGH, PROFILE_VOICE_MAX ->
                OpusNative.OPUS_APPLICATION_VOIP
            PROFILE_AUDIO_MIN, PROFILE_AUDIO_LOW, PROFILE_AUDIO_MEDIUM, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX ->
                OpusNative.OPUS_APPLICATION_AUDIO
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

    private var profile: Int = 0
    private var channels: Int = 0
    private var samplerate: Int = 0
    private var bitrateCeiling: Int = 0

    override val codecChannels: Int get() = channels

    // Native handle (0 = not initialized). Holds both encoder and decoder.
    private var nativeHandle: Long = 0

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
        // Release previous handle if switching profiles
        if (nativeHandle != 0L) {
            OpusNative.destroy(nativeHandle)
            nativeHandle = 0
        }

        this.profile = profile
        this.channels = profileChannels(profile)
        this.samplerate = profileSamplerate(profile)
        this.bitrateCeiling = profileBitrateCeiling(profile)
        this.preferredSamplerate = samplerate
    }

    /**
     * Lazily initialize the native Opus encoder+decoder.
     * Called on first encode or decode.
     */
    private fun ensureInitialized() {
        if (nativeHandle != 0L) return

        nativeHandle = OpusNative.create(
            samplerate,
            channels,
            profileApplication(profile),
            bitrateCeiling,
            10 // complexity (max quality)
        )

        if (nativeHandle == 0L) {
            throw CodecError("Opus native create failed (rate=$samplerate, ch=$channels)")
        }

        Log.d(TAG, "Opus created: rate=$samplerate, ch=$channels, bitrate=$bitrateCeiling")
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

        // Mono→stereo upmix when codec expects stereo (matches Python LXST Opus.py:113-122).
        // Pipeline always captures mono from mic. When codec requires stereo (channels > 1),
        // duplicate each mono sample across all channels to create interleaved stereo.
        val processedFrame = if (channels > 1) {
            val monoDurationMs = (frame.size.toFloat() / samplerate) * 1000f
            if (monoDurationMs in VALID_FRAME_MS) {
                // Input is mono, upmix to interleaved stereo: [s0,s1,...] → [s0,s0,s1,s1,...]
                FloatArray(frame.size * channels) { i -> frame[i / channels] }
            } else {
                frame // Already has correct channel count
            }
        } else {
            frame
        }

        // Calculate frame duration
        val frameDurationMs = (processedFrame.size.toFloat() / (samplerate * channels)) * 1000f

        // Validate frame duration
        if (frameDurationMs !in VALID_FRAME_MS) {
            throw CodecError("Invalid frame duration: ${frameDurationMs}ms (valid: $VALID_FRAME_MS)")
        }

        ensureInitialized()

        // Convert float32 to int16 short array
        val pcmShorts = ShortArray(processedFrame.size)
        for (i in processedFrame.indices) {
            pcmShorts[i] = (processedFrame[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Encode: frames = samples per channel
        val framesPerChannel = processedFrame.size / channels
        val outBuffer = ByteArray(MAX_ENCODED_BYTES)
        val encodedLen = OpusNative.encode(nativeHandle, pcmShorts, framesPerChannel, outBuffer)

        if (encodedLen < 0) {
            throw CodecError("Opus encode failed: error=$encodedLen")
        }

        // Track statistics
        val encodedBytes = outBuffer.copyOf(encodedLen)
        outputBytes += encodedLen
        outputMs += frameDurationMs
        outputBitrate = (outputBytes * 8.0) / (outputMs / 1000.0)

        return encodedBytes
    }

    /**
     * Decode Opus compressed bytes to float32 audio samples.
     *
     * Wire-compatible with Python pyogg encoder.
     * Decodes at the profile's native sample rate (no artificial cap).
     *
     * @param frameBytes Opus-encoded byte array
     * @return Float32 samples in range [-1.0, 1.0]
     */
    override fun decode(frameBytes: ByteArray): FloatArray {
        ensureInitialized()

        // Max samples per channel for the largest possible frame (60ms)
        val maxSamplesPerChannel = samplerate * FRAME_MAX_MS.toInt() / 1000
        val outShorts = ShortArray(maxSamplesPerChannel * channels)

        val decodedSamples = OpusNative.decode(nativeHandle, frameBytes, outShorts, maxSamplesPerChannel)

        if (decodedSamples < 0) {
            throw CodecError("Opus decode failed: error=$decodedSamples")
        }

        // Convert int16 to float32
        val totalSamples = decodedSamples * channels
        return FloatArray(totalSamples) { i ->
            outShorts[i] / 32768f
        }
    }

    /**
     * Release native resources.
     * Call when codec is no longer needed.
     */
    fun release() {
        if (nativeHandle != 0L) {
            OpusNative.destroy(nativeHandle)
            nativeHandle = 0
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
