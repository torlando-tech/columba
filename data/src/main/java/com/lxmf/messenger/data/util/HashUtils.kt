package com.lxmf.messenger.data.util

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
        return hash.take(16).toHexString()
    }

    /**
     * Convert a byte array to a lowercase hex string.
     *
     * @return Lowercase hex string representation
     */
    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * Convert a list of bytes to a lowercase hex string.
     *
     * @return Lowercase hex string representation
     */
    fun List<Byte>.toHexString(): String = joinToString("") { "%02x".format(it) }
}
