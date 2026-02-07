package tech.torlando.lxst.codec

/**
 * MIT-licensed JNI bridge to libopus (BSD-3-Clause).
 *
 * Only exposes the 4 functions lxst needs.
 * Uses opus_encoder_destroy()/opus_decoder_destroy() (not free()).
 * Skips redundant opus_encoder_init() after opus_encoder_create().
 */
object NativeOpus {
    init {
        System.loadLibrary("opus")
        System.loadLibrary("lxst_opus_jni")
    }

    const val OPUS_APPLICATION_VOIP = 2048
    const val OPUS_APPLICATION_AUDIO = 2049

    /**
     * Create an encoder+decoder pair. Returns opaque handle (0 on failure).
     */
    @JvmStatic external fun create(
        sampleRate: Int,
        channels: Int,
        application: Int,
        bitrate: Int,
        complexity: Int,
    ): Long

    /** Destroy encoder+decoder pair. */
    @JvmStatic external fun destroy(handle: Long)

    /** Encode PCM to Opus. Returns encoded byte count (negative = error). */
    @JvmStatic external fun encode(
        handle: Long,
        pcm: ShortArray,
        framesPerChannel: Int,
        out: ByteArray,
    ): Int

    /** Decode Opus to PCM. Returns decoded samples per channel (negative = error). */
    @JvmStatic external fun decode(
        handle: Long,
        encoded: ByteArray,
        pcmOut: ShortArray,
        maxFramesPerChannel: Int,
    ): Int
}
