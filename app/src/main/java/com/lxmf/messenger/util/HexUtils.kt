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

    /**
     * Convert a hex string to a ByteArray, handling spaces and mixed case.
     * Spaces are removed and the string is lowercased before parsing.
     *
     * @return ByteArray parsed from the hex string
     * @throws IllegalArgumentException if string has odd length after removing spaces
     * @throws NumberFormatException if string contains invalid hex characters
     */
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").lowercase()
        require(cleanHex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
