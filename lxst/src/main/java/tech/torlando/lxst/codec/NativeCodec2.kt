package tech.torlando.lxst.codec

/**
 * MIT-licensed JNI bridge to libcodec2 (LGPL-2.1).
 *
 * Only exposes the 6 functions lxst needs — no FSK/FreeDV.
 * encode() writes directly to ByteArray (no CharArray quirk).
 * decode() uses codec2_decode() (not codec2_decode_ber with BER=0.0).
 */
object NativeCodec2 {
    init {
        System.loadLibrary("codec2")
        System.loadLibrary("lxst_codec2_jni")
    }

    const val MODE_3200 = 0
    const val MODE_2400 = 1
    const val MODE_1600 = 2
    const val MODE_1400 = 3
    const val MODE_1300 = 4
    const val MODE_1200 = 5
    const val MODE_700C = 8

    /** Create a codec2 instance. Returns opaque handle (0 on failure). */
    @JvmStatic external fun create(mode: Int): Long

    /** Destroy a codec2 instance. */
    @JvmStatic external fun destroy(handle: Long)

    /** Number of PCM samples per codec frame. */
    @JvmStatic external fun getSamplesPerFrame(handle: Long): Int

    /** Number of bytes per encoded codec frame. */
    @JvmStatic external fun getFrameBytes(handle: Long): Int

    /** Encode one frame. Returns bytes written to [out]. */
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, out: ByteArray): Int

    /** Decode one frame. Returns samples written to [pcm]. */
    @JvmStatic external fun decode(handle: Long, encoded: ByteArray, pcm: ShortArray): Int
}
