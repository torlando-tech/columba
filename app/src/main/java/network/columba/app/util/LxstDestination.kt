package network.columba.app.util

import java.security.MessageDigest

/**
 * Derivation of LXST telephony destination hashes from Reticulum identity hashes.
 *
 * Mirrors `RNS.Destination.hash(identity, "lxst", "telephony")` from the
 * Reticulum reference implementation:
 *
 *   name_hash = sha256("lxst.telephony")[:10]   (NAME_HASH_LENGTH = 80 bits)
 *   dest_hash = sha256(name_hash + identity_hash)[:16]  (TRUNCATED_HASHLENGTH = 128 bits)
 *
 * Note the name hash is computed over the bare "lxst.telephony" string WITHOUT
 * the identity appended (expand_name is called with identity = None), while the
 * identity hash bytes are mixed in at the destination-hash step.
 */
object LxstDestination {
    /** Reticulum hashes (identity and destination) are 16 bytes = 32 hex chars. */
    const val HASH_HEX_LENGTH = 32

    private const val NAME_HASH_BYTES = 10
    private const val DEST_HASH_BYTES = 16
    private const val LXST_TELEPHONY_NAME = "lxst.telephony"

    /**
     * Normalize a user-entered hash: trim, strip embedded spaces, lowercase.
     *
     * @return the normalized 32-char lowercase hex string, or null if the input
     *         is not a valid 16-byte hex hash.
     */
    fun normalizeHash(raw: String): String? {
        val cleaned = raw.trim().replace(" ", "").lowercase()
        if (cleaned.length != HASH_HEX_LENGTH) return null
        if (!cleaned.all { it in "0123456789abcdef" }) return null
        return cleaned
    }

    /**
     * Derive the `lxst.telephony` destination hash for a Reticulum identity hash.
     *
     * @param identityHashHex 32-char hex identity hash (spaces tolerated)
     * @return the derived 32-char lowercase hex destination hash, or null if the
     *         input is not a valid identity hash.
     */
    fun telephonyDestinationHashFromIdentity(identityHashHex: String): String? {
        val identityHash = normalizeHash(identityHashHex) ?: return null
        val nameHash = sha256(LXST_TELEPHONY_NAME.toByteArray(Charsets.UTF_8))
            .copyOf(NAME_HASH_BYTES)
        val destHash = sha256(nameHash + hexToBytes(identityHash))
            .copyOf(DEST_HASH_BYTES)
        return destHash.toHex()
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
