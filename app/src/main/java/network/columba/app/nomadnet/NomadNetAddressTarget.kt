package network.columba.app.nomadnet

/**
 * What a typed NomadNet address points at.
 */
sealed interface NomadNetAddressTarget {
    /** A page to open in the browser. [nodeHash] is lowercased for lookup. */
    data class Page(
        val nodeHash: String,
        val path: String,
    ) : NomadNetAddressTarget

    /** An LXMF conversation (`lxmf@hash`) to open in Chats. */
    data class Conversation(
        val nodeHash: String,
    ) : NomadNetAddressTarget
}

/**
 * Parses what the user typed (or pasted) into a NomadNet address field.
 *
 * Accepted shapes:
 * - `hash:/path` or bare `hash` (page; bare hash opens the node's index page)
 * - `nomadnetwork://hash:/path` (scheme stripped, page)
 * - `lxmf@hash` (conversation)
 *
 * Shared by the URL bar in the browser ([network.columba.app.viewmodel.NomadNetBrowserViewModel])
 * and the address field on the NomadNet tab home, so both accept exactly the
 * same input. Returns null for blank input.
 */
fun parseNomadNetAddress(input: String): NomadNetAddressTarget? {
    val raw = input.trim().removePrefix("nomadnetwork://")
    if (raw.isEmpty()) return null

    if (raw.startsWith(LXMF_PREFIX)) {
        return raw
            .removePrefix(LXMF_PREFIX)
            .takeIf { it.isNotBlank() }
            ?.let { NomadNetAddressTarget.Conversation(it) }
    }

    // Split on the first colon: "hash:/path" or just "hash". The colon form is
    // what nomadnetwork:// links use (`hash:/page/index.mu`).
    val colonIdx = raw.indexOf(':')
    val nodeHash = if (colonIdx > 0) raw.substring(0, colonIdx) else raw
    // "hash:" with nothing after the colon means the index page, same as a
    // bare hash.
    val path = if (colonIdx > 0) raw.substring(colonIdx + 1).ifEmpty { DEFAULT_PAGE_PATH } else DEFAULT_PAGE_PATH

    return nodeHash
        .takeIf { it.isNotBlank() }
        ?.let { NomadNetAddressTarget.Page(it.lowercase(), path) }
}

/** `lxmf@hash` addresses point at a conversation rather than a page. */
private const val LXMF_PREFIX = "lxmf@"

/** NomadNet's index page; the default for an address without a path. */
const val DEFAULT_PAGE_PATH = "/page/index.mu"
