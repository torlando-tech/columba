package network.columba.app.util

/**
 * The canonical display name for a peer whose name is cleared or unset.
 *
 * The rest of the app already uses this string for an empty display name
 * (onboarding default, welcome/profile placeholder, share text), so a cleared
 * name must be announced under the same value to keep the name a peer sees
 * consistent no matter how the announce was triggered.
 */
const val ANONYMOUS_DISPLAY_NAME = "Anonymous Peer"

/**
 * Resolves the display name to put on the wire for an announce.
 *
 * A nonblank name is used as-is; a null or blank name (the value the edit
 * path stores when a user clears their display name) maps to
 * [ANONYMOUS_DISPLAY_NAME] instead of an empty string. Without this, a
 * cleared name would be announced as empty, and receivers would fall back to
 * the hash-based "Peer XXXX" name - while a peer that had not cleared their
 * name would still show "Anonymous Peer". Normalising here makes the visible
 * name independent of the announce trigger (manual vs. automatic tick).
 *
 * @param name The current display name (may be null or blank).
 * @return A nonblank display name safe to announce.
 */
fun displayNameForAnnounce(name: String?): String =
    name?.takeIf { it.isNotBlank() } ?: ANONYMOUS_DISPLAY_NAME

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
