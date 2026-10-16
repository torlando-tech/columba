package network.columba.app.util

import java.security.MessageDigest

/**
 * Derivation of Reticulum destination hashes from identity hashes, for the
 * aspects this app dials: `lxst.telephony` and `lxmf.delivery`.
 *
 * Mirrors `RNS.Destination.hash(identity, app, aspect)` from the Reticulum
 * reference implementation:
 *
 *   name_hash = sha256("app.aspect")[:10]   (NAME_HASH_LENGTH = 80 bits)
 *   dest_hash = sha256(name_hash + identity_hash)[:16]  (TRUNCATED_HASHLENGTH = 128 bits)
 *
 * Note the name hash is computed over the bare "app.aspect" string WITHOUT
 * the identity appended (expand_name is called with identity = None), while the
 * identity hash bytes are mixed in at the destination-hash step.
 */
object LxstDestination {
    /** Reticulum hashes (identity and destination) are 16 bytes = 32 hex chars. */
    const val HASH_HEX_LENGTH = 32

    private const val NAME_HASH_BYTES = 10
    private const val DEST_HASH_BYTES = 16
    private const val LXST_TELEPHONY_NAME = "lxst.telephony"
    private const val LXMF_DELIVERY_NAME = "lxmf.delivery"

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
        return telephonyDestinationHashFromNormalizedIdentity(identityHash)
    }

    /**
     * Derive the `lxst.telephony` destination hash from an already-normalized
     * identity hash (the output of [normalizeHash]). Derivation from a valid
     * normalized hash cannot fail, so this never returns null.
     *
     * @param normalizedIdentityHashHex lowercase 32-char hex identity hash
     * @return the derived 32-char lowercase hex destination hash
     */
    fun telephonyDestinationHashFromNormalizedIdentity(normalizedIdentityHashHex: String): String =
        destinationHashFromName(LXST_TELEPHONY_NAME, normalizedIdentityHashHex)

    /**
     * Derive the `lxmf.delivery` destination hash for a Reticulum identity hash.
     *
     * @param identityHashHex 32-char hex identity hash (spaces tolerated)
     * @return the derived 32-char lowercase hex destination hash, or null if the
     *         input is not a valid identity hash.
     */
    fun deliveryDestinationHashFromIdentity(identityHashHex: String): String? {
        val identityHash = normalizeHash(identityHashHex) ?: return null
        return destinationHashFromName(LXMF_DELIVERY_NAME, identityHash)
    }

    /**
     * Derive the `lxmf.delivery` destination hash from an already-normalized
     * identity hash. See [telephonyDestinationHashFromNormalizedIdentity].
     */
    fun deliveryDestinationHashFromNormalizedIdentity(normalizedIdentityHashHex: String): String =
        destinationHashFromName(LXMF_DELIVERY_NAME, normalizedIdentityHashHex)

    private fun destinationHashFromName(name: String, normalizedIdentityHashHex: String): String {
        val nameHash = sha256(name.toByteArray(Charsets.UTF_8))
            .copyOf(NAME_HASH_BYTES)
        val destHash = sha256(nameHash + hexToBytes(normalizedIdentityHashHex))
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
