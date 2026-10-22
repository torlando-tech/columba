package network.columba.app.data.util

import java.security.MessageDigest

/**
 * Centralized hash computation and formatting utilities.
 *
 * This utility provides a single source of truth for:
 * - Computing identity hashes from public keys (Reticulum protocol)
 * - Converting byte arrays to hex strings
 *
 * Used by both repositories (app process) and ServicePersistenceManager (service process).
 */
object HashUtils {
    /**
     * Compute identity hash from a public key.
     *
     * In Reticulum, identity hash = first 16 bytes of SHA256(public_key) as hex string.
     * This is used to identify peers by their cryptographic identity.
     *
     * @param publicKey The public key bytes
     * @return The identity hash as a lowercase hex string (32 characters)
     */
    fun computeIdentityHash(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        // Take first 16 bytes and convert to hex
        return hash.copyOf(16).toHexString()
    }

    /**
     * Convert a byte array to a lowercase hex string.
     *
     * @return Lowercase hex string representation
     */
    fun ByteArray.toHexString(): String {
        val hexChars = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_CHARS[v ushr 4]
            hexChars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Convert a list of bytes to a lowercase hex string.
     *
     * @return Lowercase hex string representation
     */
    fun List<Byte>.toHexString(): String {
        val hexChars = CharArray(size * 2)
        var i = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            hexChars[i++] = HEX_CHARS[v ushr 4]
            hexChars[i++] = HEX_CHARS[v and 0x0F]
        }
        return String(hexChars)
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
