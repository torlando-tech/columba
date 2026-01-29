package com.lxmf.messenger.reticulum.flasher

/**
 * KISS (Keep It Simple, Stupid) frame encoder/decoder for RNode communication.
 *
 * KISS is a protocol for communicating with packet radio TNCs. RNode uses KISS
 * framing for all its serial commands. The format is:
 * - Frame starts and ends with FEND (0xC0)
 * - 0xC0 within data is escaped as 0xDB 0xDC
 * - 0xDB within data is escaped as 0xDB 0xDD
 * - First byte after FEND is the command byte
 */
@Suppress("MagicNumber")
object KISSCodec {
    private const val FEND: Byte = 0xC0.toByte() // Frame End
    private const val FESC: Byte = 0xDB.toByte() // Frame Escape
    private const val TFEND: Byte = 0xDC.toByte() // Transposed Frame End
    private const val TFESC: Byte = 0xDD.toByte() // Transposed Frame Escape

    /**
     * Create a KISS frame for the given command and data.
     *
     * @param command The command byte
     * @param data The data bytes (may be empty)
     * @return Complete KISS frame
     */
    fun createFrame(
        command: Byte,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val frame = mutableListOf<Byte>()
        frame.add(FEND)

        // Escape and add command
        addEscaped(frame, command)

        // Escape and add data
        for (byte in data) {
            addEscaped(frame, byte)
        }

        frame.add(FEND)
        return frame.toByteArray()
    }

    /**
     * Create a KISS frame for the given command and data.
     */
    fun createFrame(
        command: Byte,
        data: List<Byte>,
    ): ByteArray = createFrame(command, data.toByteArray())

    private fun addEscaped(
        frame: MutableList<Byte>,
        byte: Byte,
    ) {
        when (byte) {
            FEND -> {
                frame.add(FESC)
                frame.add(TFEND)
            }
            FESC -> {
                frame.add(FESC)
                frame.add(TFESC)
            }
            else -> frame.add(byte)
        }
    }

    /**
     * Decode a KISS frame.
     *
     * @param frame The raw frame bytes (may include FEND delimiters)
     * @return Pair of (command, data) or null if invalid
     */
    fun decodeFrame(frame: ByteArray): Pair<Byte, ByteArray>? {
        val decoded = mutableListOf<Byte>()
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
                        TFEND -> decoded.add(FEND)
                        TFESC -> decoded.add(FESC)
                        else -> return null // Invalid escape
                    }
                }
                byte == FESC -> {
                    escaping = true
                }
                else -> {
                    decoded.add(byte)
                }
            }
        }

        if (escaping || decoded.isEmpty()) return null

        val command = decoded[0]
        val data = decoded.drop(1).toByteArray()
        return Pair(command, data)
    }

    /**
     * Get the frame delimiter byte.
     */
    fun getFrameEnd(): Byte = FEND
}

/**
 * State machine for parsing KISS frames from a stream.
 */
class KISSFrameParser {
    private var inFrame = false
    private var escaping = false
    private val buffer = mutableListOf<Byte>()

    /**
     * Parsed KISS frame with command and data.
     */
    data class KISSFrame(
        val command: Byte,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KISSFrame) return false
            return command == other.command && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = command.toInt()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Process a single byte and return a frame if one is completed.
     */
    @Suppress("MagicNumber", "ReturnCount")
    fun processByte(byte: Byte): KISSFrame? {
        when {
            byte == 0xC0.toByte() -> {
                // FEND
                return if (inFrame && buffer.isNotEmpty()) {
                    val command = buffer[0]
                    val data = buffer.drop(1).toByteArray()
                    buffer.clear()
                    inFrame = false
                    escaping = false
                    KISSFrame(command, data)
                } else {
                    buffer.clear()
                    inFrame = true
                    escaping = false
                    null
                }
            }
            !inFrame -> return null
            escaping -> {
                escaping = false
                when (byte) {
                    0xDC.toByte() -> buffer.add(0xC0.toByte())
                    0xDD.toByte() -> buffer.add(0xDB.toByte())
                    else -> {
                        // Invalid escape
                        buffer.clear()
                        inFrame = false
                    }
                }
                return null
            }
            byte == 0xDB.toByte() -> {
                escaping = true
                return null
            }
            else -> {
                buffer.add(byte)
                return null
            }
        }
    }

    /**
     * Process multiple bytes and return all completed frames.
     */
    fun processBytes(bytes: ByteArray): List<KISSFrame> {
        val frames = mutableListOf<KISSFrame>()
        for (byte in bytes) {
            processByte(byte)?.let { frames.add(it) }
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
