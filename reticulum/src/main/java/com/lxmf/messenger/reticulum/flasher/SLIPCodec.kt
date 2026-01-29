package com.lxmf.messenger.reticulum.flasher

/**
 * SLIP (Serial Line Internet Protocol) encoder/decoder for Nordic DFU.
 *
 * SLIP is used to frame packets over serial connections. The Nordic DFU protocol
 * uses SLIP encoding with the following escape sequences:
 * - 0xC0 (FEND - Frame End) is encoded as 0xDB 0xDC
 * - 0xDB (FESC - Frame Escape) is encoded as 0xDB 0xDD
 *
 * Each packet is framed with FEND (0xC0) at both start and end.
 */
@Suppress("MagicNumber")
object SLIPCodec {
    // SLIP special characters
    private const val FEND: Byte = 0xC0.toByte() // Frame End
    private const val FESC: Byte = 0xDB.toByte() // Frame Escape
    private const val TFEND: Byte = 0xDC.toByte() // Transposed Frame End (follows FESC)
    private const val TFESC: Byte = 0xDD.toByte() // Transposed Frame Escape (follows FESC)

    /**
     * Encode data bytes with SLIP escaping (without framing).
     * This only escapes special characters, it does not add FEND delimiters.
     *
     * @param data Input bytes to escape
     * @return Escaped byte list
     */
    fun encodeEscapeChars(data: ByteArray): List<Byte> {
        val result = mutableListOf<Byte>()

        for (byte in data) {
            when (byte) {
                FEND -> {
                    result.add(FESC)
                    result.add(TFEND)
                }
                FESC -> {
                    result.add(FESC)
                    result.add(TFESC)
                }
                else -> result.add(byte)
            }
        }

        return result
    }

    /**
     * Encode data bytes with SLIP escaping (without framing).
     */
    fun encodeEscapeChars(data: List<Byte>): List<Byte> = encodeEscapeChars(data.toByteArray())

    /**
     * Encode a complete SLIP frame with start and end delimiters.
     *
     * @param data Input bytes to frame
     * @return Complete SLIP frame
     */
    fun encodeFrame(data: ByteArray): ByteArray {
        val escaped = encodeEscapeChars(data)
        return (listOf(FEND) + escaped + listOf(FEND)).toByteArray()
    }

    /**
     * Encode a complete SLIP frame with start and end delimiters.
     */
    fun encodeFrame(data: List<Byte>): ByteArray = encodeFrame(data.toByteArray())

    /**
     * Decode a SLIP frame, removing escaping.
     *
     * @param frame SLIP-encoded frame (may or may not include FEND delimiters)
     * @return Decoded data bytes, or null if invalid escape sequence found
     */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        val result = mutableListOf<Byte>()
        var escaping = false

        for (byte in frame) {
            when {
                byte == FEND -> {
                    // Skip frame delimiters
                    continue
                }
                escaping -> {
                    escaping = false
                    when (byte) {
                        TFEND -> result.add(FEND)
                        TFESC -> result.add(FESC)
                        else -> return null // Invalid escape sequence
                    }
                }
                byte == FESC -> {
                    escaping = true
                }
                else -> {
                    result.add(byte)
                }
            }
        }

        // If we ended while escaping, the frame is incomplete
        if (escaping) return null

        return result.toByteArray()
    }

    /**
     * Frame delimiter constant for external use.
     */
    fun getFrameEnd(): Byte = FEND

    /**
     * Check if a byte is the frame delimiter.
     */
    fun isFrameEnd(byte: Byte): Boolean = byte == FEND
}

/**
 * State machine for parsing SLIP frames from a stream of bytes.
 */
class SLIPFrameParser {
    private var inFrame = false
    private var escaping = false
    private val buffer = mutableListOf<Byte>()

    /**
     * Result of processing bytes through the parser.
     */
    sealed class Result {
        data class Frame(
            val data: ByteArray,
        ) : Result() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Frame) return false
                return data.contentEquals(other.data)
            }

            override fun hashCode(): Int = data.contentHashCode()
        }

        data object NoFrame : Result()

        data object InvalidEscape : Result()
    }

    /**
     * Process a single byte.
     *
     * @param byte The byte to process
     * @return Result indicating if a frame was completed
     */
    @Suppress("MagicNumber", "ReturnCount")
    fun processByte(byte: Byte): Result {
        when {
            byte == 0xC0.toByte() -> {
                // FEND - Frame delimiter
                return if (inFrame && buffer.isNotEmpty()) {
                    // End of frame
                    val frame = buffer.toByteArray()
                    buffer.clear()
                    inFrame = false
                    escaping = false
                    Result.Frame(frame)
                } else {
                    // Start of frame (or empty frame, which we skip)
                    buffer.clear()
                    inFrame = true
                    escaping = false
                    Result.NoFrame
                }
            }
            !inFrame -> {
                // Ignore bytes outside of frames
                return Result.NoFrame
            }
            escaping -> {
                escaping = false
                when (byte) {
                    0xDC.toByte() -> buffer.add(0xC0.toByte()) // TFEND -> FEND
                    0xDD.toByte() -> buffer.add(0xDB.toByte()) // TFESC -> FESC
                    else -> {
                        // Invalid escape sequence
                        buffer.clear()
                        inFrame = false
                        return Result.InvalidEscape
                    }
                }
                return Result.NoFrame
            }
            byte == 0xDB.toByte() -> {
                // FESC - Start escape sequence
                escaping = true
                return Result.NoFrame
            }
            else -> {
                buffer.add(byte)
                return Result.NoFrame
            }
        }
    }

    /**
     * Process multiple bytes and return all completed frames.
     *
     * @param bytes The bytes to process
     * @return List of completed frames
     */
    fun processBytes(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()

        for (byte in bytes) {
            when (val result = processByte(byte)) {
                is Result.Frame -> frames.add(result.data)
                else -> { /* continue */ }
            }
        }

        return frames
    }

    /**
     * Reset the parser state.
     */
    fun reset() {
        inFrame = false
        escaping = false
        buffer.clear()
    }
}
