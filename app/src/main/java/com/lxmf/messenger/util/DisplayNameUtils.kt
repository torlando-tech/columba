package com.lxmf.messenger.util

/**
 * Generates a default display name from an identity hash.
 * Format: "Peer A1B2C3D4" using the first 8 hex characters of the hash.
 *
 * @param hash The identity hash bytes
 * @return A formatted display name string (e.g., "Peer A1B2C3D4")
 */
fun generateDefaultDisplayName(hash: ByteArray): String {
    val hashHex =
        hash.joinToString("") { byte ->
            "%02X".format(byte)
        }
    val truncatedHash = hashHex.take(8)
    return "Peer $truncatedHash"
}
