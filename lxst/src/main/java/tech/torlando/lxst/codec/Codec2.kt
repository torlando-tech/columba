package tech.torlando.lxst.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Codec2 ultra-low-bitrate voice codec.
 *
 * Matches Python LXST Codecs/Codec2.py structure for wire compatibility.
 * Provides 7 modes from 700 bps to 3200 bps.
 *
 * Uses sh123/codec2_talkie JNI bindings to codec2 library.
 */
class Codec2(mode: Int = CODEC2_2400) : Codec() {
    companion object {
        // Mode constants (match Python LXST exactly)
        const val CODEC2_700C = 700
        const val CODEC2_1200 = 1200
        const val CODEC2_1300 = 1300
        const val CODEC2_1400 = 1400
        const val CODEC2_1600 = 1600
        const val CODEC2_2400 = 2400
        const val CODEC2_3200 = 3200

        const val INPUT_RATE = 8000
        const val OUTPUT_RATE = 8000
        const val FRAME_QUANTA_MS = 40f

        // Header byte mapping (critical for wire compatibility)
        val MODE_HEADERS = mapOf(
            CODEC2_700C to 0x00.toByte(),
            CODEC2_1200 to 0x01.toByte(),
            CODEC2_1300 to 0x02.toByte(),
            CODEC2_1400 to 0x03.toByte(),
            CODEC2_1600 to 0x04.toByte(),
            CODEC2_2400 to 0x05.toByte(),
            CODEC2_3200 to 0x06.toByte()
        )

        val HEADER_MODES = MODE_HEADERS.entries.associate { (k, v) -> v to k }

        // Map our mode constants to library mode constants
        // Based on com.ustadmobile.codec2.Codec2 constants
        private val MODE_TO_LIBRARY = mapOf(
            CODEC2_3200 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_3200, // 0
            CODEC2_2400 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_2400, // 1
            CODEC2_1600 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_1600, // 2
            CODEC2_1400 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_1400, // 3
            CODEC2_1300 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_1300, // 4
            CODEC2_1200 to com.ustadmobile.codec2.Codec2.CODEC2_MODE_1200, // 5
            CODEC2_700C to com.ustadmobile.codec2.Codec2.CODEC2_MODE_700C  // 8
        )
    }

    private var codec2Handle: Long = 0L
    var currentMode: Int = mode
        private set
    private var modeHeaderByte: Byte = 0x00

    init {
        frameQuantaMs = FRAME_QUANTA_MS
        preferredSamplerate = INPUT_RATE
        setMode(mode)
    }

    /**
     * Set codec mode (700C through 3200).
     *
     * Destroys existing codec instance and creates new one with specified mode.
     */
    fun setMode(mode: Int) {
        currentMode = mode
        modeHeaderByte = MODE_HEADERS[mode] ?: throw CodecError("Invalid Codec2 mode: $mode")

        // Destroy old codec instance if exists
        if (codec2Handle != 0L) {
            com.ustadmobile.codec2.Codec2.destroy(codec2Handle)
        }

        // Create new codec instance
        val libraryMode = MODE_TO_LIBRARY[mode] ?: throw CodecError("No library mode mapping for: $mode")
        codec2Handle = com.ustadmobile.codec2.Codec2.create(libraryMode)
        if (codec2Handle == 0L) {
            throw CodecError("Failed to create Codec2 instance for mode $mode")
        }
    }

    override fun encode(frame: FloatArray): ByteArray {
        if (codec2Handle == 0L) throw CodecError("Codec2 not initialized")

        // Convert float32 to int16 samples
        val int16Samples = ShortArray(frame.size) { i ->
            (frame[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Get codec parameters
        val samplesPerFrame = com.ustadmobile.codec2.Codec2.getSamplesPerFrame(codec2Handle)
        // Despite the name, getBitsSize() returns BYTES per frame (confirmed by
        // codec2_talkie source: "Codec2.getBitsSize(_codec2Con); // returns number of bytes")
        val bytesPerFrame = com.ustadmobile.codec2.Codec2.getBitsSize(codec2Handle).toInt()

        // Calculate number of frames
        val numFrames = floor(int16Samples.size.toDouble() / samplesPerFrame).toInt()

        // Encode each frame
        val encodedBuffer = ByteBuffer.allocate(1 + numFrames * bytesPerFrame)

        // Prepend mode header byte (critical for wire format)
        encodedBuffer.put(modeHeaderByte)

        for (i in 0 until numFrames) {
            val frameStart = i * samplesPerFrame
            val frameEnd = (i + 1) * samplesPerFrame
            val frameSamples = int16Samples.copyOfRange(frameStart, frameEnd)

            // Encode frame — JNI writes packed bytes into CharArray
            val encodedChars = CharArray(bytesPerFrame)
            com.ustadmobile.codec2.Codec2.encode(codec2Handle, frameSamples, encodedChars)

            // Convert CharArray to ByteArray (each char holds one packed byte)
            val encodedBytes = ByteArray(bytesPerFrame) { j ->
                (encodedChars[j].code and 0xFF).toByte()
            }

            encodedBuffer.put(encodedBytes)
        }

        return encodedBuffer.array().copyOfRange(0, encodedBuffer.position())
    }

    override fun decode(frameBytes: ByteArray): FloatArray {
        if (codec2Handle == 0L) throw CodecError("Codec2 not initialized")
        if (frameBytes.isEmpty()) throw CodecError("Empty frame")

        // Extract header byte and adjust mode if needed
        val frameHeader = frameBytes[0]
        val encodedData = frameBytes.copyOfRange(1, frameBytes.size)

        // Switch mode if header indicates different mode
        val frameMode = HEADER_MODES[frameHeader]
        if (frameMode != null && frameMode != currentMode) {
            setMode(frameMode)
        }

        // Get codec parameters
        val samplesPerFrame = com.ustadmobile.codec2.Codec2.getSamplesPerFrame(codec2Handle)
        val bytesPerFrame = com.ustadmobile.codec2.Codec2.getBitsSize(codec2Handle).toInt()

        // Calculate number of frames
        val numFrames = floor(encodedData.size.toDouble() / bytesPerFrame).toInt()

        // Decode each frame
        val decodedSamples = mutableListOf<Short>()

        for (i in 0 until numFrames) {
            val frameStart = i * bytesPerFrame
            val frameEnd = (i + 1) * bytesPerFrame
            // JNI decode() takes ByteArray of packed bytes directly
            val encodedBytes = encodedData.copyOfRange(frameStart, frameEnd)

            // Decode frame
            val frameSamples = ShortArray(samplesPerFrame)
            com.ustadmobile.codec2.Codec2.decode(codec2Handle, frameSamples, encodedBytes)

            decodedSamples.addAll(frameSamples.toList())
        }

        // Convert int16 to float32
        return FloatArray(decodedSamples.size) { i ->
            decodedSamples[i] / 32768f
        }
    }

    /**
     * Clean up native codec instance.
     */
    fun close() {
        if (codec2Handle != 0L) {
            com.ustadmobile.codec2.Codec2.destroy(codec2Handle)
            codec2Handle = 0L
        }
    }

    protected fun finalize() {
        close()
    }
}
