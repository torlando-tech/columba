package com.lxmf.messenger.util

/**
 * Minimal Base32 encoder/decoder (RFC 4648).
 *
 * Reticulum and Sideband use Base32 encoding for sharing identity keys as text.
 * Android doesn't include Base32 natively, so this avoids adding a dependency
 * for a simple codec.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PADDING = '='

    /**
     * Encode a byte array to a Base32 string (uppercase, with padding).
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val result = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                result.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }

        // Handle remaining bits
        if (bitsLeft > 0) {
            result.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }

        // Add padding to make length a multiple of 8
        while (result.length % 8 != 0) {
            result.append(PADDING)
        }

        return result.toString()
    }

    /**
     * Decode a Base32 string to a byte array.
     *
     * Accepts both uppercase and lowercase input. Padding is optional.
     *
     * @throws IllegalArgumentException if the string contains invalid characters.
     */
    fun decode(encoded: String): ByteArray {
        val input = encoded.trimEnd(PADDING).uppercase()
        if (input.isEmpty()) return ByteArray(0)

        val output = ByteArray(input.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var outputIndex = 0

        for (char in input) {
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "Invalid Base32 character: '$char'" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output[outputIndex++] = (buffer shr bitsLeft).toByte()
            }
        }

        return output.copyOf(outputIndex)
    }

    /**
     * Check if a string is a valid Base32-encoded identity key (decodes to exactly 64 bytes).
     *
     * Sideband shares identity keys as Base32 text via Android's share sheet.
     * A Reticulum identity is always exactly 64 bytes (32-byte signing key + 32-byte encryption key).
     */
    fun isIdentityKey(text: String): Boolean =
        try {
            val decoded = decode(text.trim())
            decoded.size == 64
        } catch (_: Exception) {
            false
        }
}
