package com.lxmf.messenger.util

/**
 * Utility functions for hex string conversion.
 * Provides extension functions for converting between ByteArray and hex strings.
 */
object HexUtils {
    /**
     * Convert a ByteArray to a lowercase hex string.
     * Each byte is converted to a 2-character hex representation.
     *
     * @return Lowercase hex string representation
     */
    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * Convert a hex string to a ByteArray.
     * The string must have an even number of characters.
     *
     * @return ByteArray parsed from the hex string
     * @throws NumberFormatException if string contains invalid hex characters
     */
    fun String.hexStringToByteArray(): ByteArray =
        chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
