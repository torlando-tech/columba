package com.lxmf.messenger.util

/**
 * Utility functions for encoding and decoding Reticulum identity information in QR codes.
 *
 * QR Code Format: "lxma://<destination_hash_hex>:<public_key_hex>"
 * - destination_hash: 16 bytes (32 hex characters)
 * - public_key: 64 bytes (128 hex characters)
 * Total: ~165 characters (fits easily in QR code)
 */
object IdentityQrCodeUtils {
    private const val QR_PREFIX = "lxma://"
    private const val DESTINATION_HASH_LENGTH = 16 // bytes
    private const val PUBLIC_KEY_LENGTH = 64 // bytes

    /**
     * Encoded identity data for QR codes.
     *
     * @property destinationHash The 16-byte destination hash
     * @property publicKey The 64-byte public key
     */
    data class IdentityData(
        val destinationHash: ByteArray,
        val publicKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as IdentityData

            if (!destinationHash.contentEquals(other.destinationHash)) return false
            if (!publicKey.contentEquals(other.publicKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = destinationHash.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    /**
     * Encodes destination hash and public key into a QR code string.
     *
     * Format: "lxma://<destination_hash_hex>:<public_key_hex>"
     *
     * @param destinationHash The 16-byte destination hash
     * @param publicKey The 64-byte public key
     * @return QR code string, or null if inputs are invalid
     */
    fun encodeToQrString(
        destinationHash: ByteArray,
        publicKey: ByteArray,
    ): String? {
        if (destinationHash.size != DESTINATION_HASH_LENGTH) {
            return null
        }
        if (publicKey.size != PUBLIC_KEY_LENGTH) {
            return null
        }

        val destHashHex = destinationHash.toHexString()
        val publicKeyHex = publicKey.toHexString()

        return "$QR_PREFIX$destHashHex:$publicKeyHex"
    }

    /**
     * Decodes a QR code string into destination hash and public key.
     *
     * @param qrString The scanned QR code string
     * @return IdentityData if valid, null if invalid format
     */
    fun decodeFromQrString(qrString: String): IdentityData? {
        if (!qrString.startsWith(QR_PREFIX)) {
            return null
        }

        val data = qrString.removePrefix(QR_PREFIX)
        val parts = data.split(":")

        if (parts.size != 2) {
            return null
        }

        val destHashHex = parts[0]
        val publicKeyHex = parts[1]

        // Validate lengths
        if (destHashHex.length != DESTINATION_HASH_LENGTH * 2) { // 32 hex chars
            return null
        }
        if (publicKeyHex.length != PUBLIC_KEY_LENGTH * 2) { // 128 hex chars
            return null
        }

        val destinationHash =
            try {
                destHashHex.hexStringToByteArray()
            } catch (e: Exception) {
                return null
            }

        val publicKey =
            try {
                publicKeyHex.hexStringToByteArray()
            } catch (e: Exception) {
                return null
            }

        return IdentityData(destinationHash, publicKey)
    }

    /**
     * Generates a shareable text string containing identity information.
     * Suitable for sharing via messaging apps, email, etc.
     *
     * @param displayName The user's display name
     * @param destinationHash The 16-byte destination hash
     * @param publicKey The 64-byte public key
     * @return Formatted share text
     */
    fun generateShareText(
        displayName: String,
        destinationHash: ByteArray,
        publicKey: ByteArray,
    ): String {
        val destHashHex = destinationHash.toHexString()
        val publicKeyHex = publicKey.toHexString()

        return buildString {
            appendLine("Reticulum/LXMF Identity")
            appendLine()
            appendLine("Display Name: $displayName")
            appendLine()
            appendLine("Destination Hash:")
            appendLine(destHashHex)
            appendLine()
            appendLine("Public Key:")
            appendLine(publicKeyHex)
            appendLine()
            appendLine("Add this contact by importing: lxma://$destHashHex:$publicKeyHex")
        }
    }

    /**
     * Converts a byte array to a hexadecimal string.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    /**
     * Converts a hexadecimal string to a byte array.
     * @throws IllegalArgumentException if the string is not valid hex
     */
    private fun String.hexStringToByteArray(): ByteArray {
        if (length % 2 != 0) {
            throw IllegalArgumentException("Hex string must have even length")
        }

        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Formats a hash for display with ellipsis in the middle.
     *
     * @param hash The hash bytes
     * @param prefixLength Number of hex characters to show at start (default: 8)
     * @param suffixLength Number of hex characters to show at end (default: 8)
     * @return Formatted string like "abc12345...6789def0"
     */
    fun formatHashForDisplay(
        hash: ByteArray,
        prefixLength: Int = 8,
        suffixLength: Int = 8,
    ): String {
        val hexString = hash.toHexString()
        if (hexString.length <= prefixLength + suffixLength) {
            return hexString
        }

        val prefix = hexString.take(prefixLength)
        val suffix = hexString.takeLast(suffixLength)
        return "$prefix...$suffix"
    }
}
