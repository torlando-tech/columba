package com.lxmf.messenger.reticulum.flasher

/**
 * CRC16-CCITT implementation for Nordic DFU protocol.
 *
 * This implementation matches the CRC16 algorithm used by the nRF52 DFU bootloader,
 * specifically the CCITT variant with polynomial 0x1021 and initial value 0xFFFF.
 *
 * The algorithm is based on the Nordic nRF52 nrfutil Python implementation:
 * https://github.com/adafruit/Adafruit_nRF52_nrfutil/blob/master/nordicsemi/dfu/dfu_transport_serial.py
 */
@Suppress("MagicNumber")
object CRC16 {
    private const val INITIAL_VALUE = 0xFFFF

    /**
     * Calculate CRC16 for the given byte array.
     *
     * @param data Input bytes to calculate CRC for
     * @param initialCrc Starting CRC value (default: 0xFFFF)
     * @return Calculated 16-bit CRC value
     */
    fun calculate(
        data: ByteArray,
        initialCrc: Int = INITIAL_VALUE,
    ): Int {
        var crc = initialCrc

        for (byte in data) {
            val b = byte.toInt() and 0xFF

            // Swap high and low bytes
            crc = ((crc shr 8) and 0x00FF) or ((crc shl 8) and 0xFF00)

            // XOR with the byte
            crc = crc xor b

            // XOR low nibble shifted
            crc = crc xor ((crc and 0x00FF) shr 4)

            // XOR high byte shifted left 12
            crc = crc xor ((crc shl 8) shl 4)

            // XOR low byte shifted left 5
            crc = crc xor (((crc and 0x00FF) shl 4) shl 1)
        }

        return crc and 0xFFFF
    }

    /**
     * Calculate CRC16 for the given byte list.
     */
    fun calculate(
        data: List<Byte>,
        initialCrc: Int = INITIAL_VALUE,
    ): Int {
        return calculate(data.toByteArray(), initialCrc)
    }

    /**
     * Append CRC16 bytes (little-endian) to a byte list.
     *
     * @param data The data to append CRC to
     * @return New list with CRC appended
     */
    fun appendCrc(data: List<Byte>): List<Byte> {
        val crc = calculate(data)
        return data +
            listOf(
                (crc and 0xFF).toByte(),
                ((crc shr 8) and 0xFF).toByte(),
            )
    }
}
