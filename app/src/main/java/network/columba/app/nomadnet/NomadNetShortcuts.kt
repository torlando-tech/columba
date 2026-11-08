package network.columba.app.nomadnet

import java.security.MessageDigest
import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement

/**
 * Everything needed to publish one launcher shortcut for a NomadNet page.
 *
 * Kept platform-free so the id/label/deep-link rules (the parts that must stay
 * stable across app restarts, so re-pinning a page updates its shortcut instead
 * of duplicating it) are unit-testable without Robolectric. The Android side
 * lives in [NomadNetShortcutPinner].
 */
data class NomadNetShortcutSpec(
    val id: String,
    val nodeHash: String,
    val shortLabel: String,
    val longLabel: String,
    val deepLink: String,
    /**
     * Request data (JSON) that produced the page, for pages reached through a
     * form submission; null for plain pages. Travels as an intent extra so the
     * pinned shortcut reproduces the same request instead of re-fetching with
     * empty form values.
     */
    val requestData: String? = null,
)

object NomadNetShortcuts {
    /** Fallback label when neither a page heading nor a usable path segment exists. */
    private const val FALLBACK_LABEL = "NomadNet page"

    /**
     * Short labels are shown under the icon; launchers truncate them further on
     * their own, so keep well under any launcher limit rather than emit
     * something the user never sees in full.
     */
    private const val MAX_SHORT_LABEL = 24

    /** Long label surfaces in the pin confirmation / shortcut details. */
    private const val MAX_LONG_LABEL = 64

    /** Destination hashes are 32 hex chars; 8 is enough to identify a node by eye. */
    private const val HASH_PREFIX = 8

    /** Digest bytes kept from the page identity; 16 bytes = 32 hex chars, collision-free in practice. */
    private const val ID_DIGEST_BYTES = 16

    /**
     * Intent extra carrying [NomadNetShortcutSpec.requestData] to
     * [network.columba.app.MainActivity]. Kept out of the deep-link URI: the
     * `nomadnetwork://hash:/path` shape is parsed by hand and has no room for
     * an encoded JSON payload.
     */
    const val EXTRA_REQUEST_DATA = "nomadnet_request_data"

    /**
     * "nomadnetwork://nodeHash:/path" — the exact shape
     * [network.columba.app.MainActivityIntentHandler] parses back into
     * `PendingNavigation.NomadNetBrowser`, so a shortcut tap lands on the page.
     */
    fun deepLink(
        nodeHash: String,
        persistPath: String,
    ): String = "nomadnetwork://$nodeHash:$persistPath"

    /**
     * Launcher shortcut id for a page. Deterministic (same page -> same id, so
     * `requestPinShortcut` updates instead of duplicating) and distinct per
     * node + path, including the backtick field block and the submitted
     * request: two saved searches of one path are two shortcuts.
     */
    fun shortcutId(
        nodeHash: String,
        persistPath: String,
        requestData: String? = null,
    ): String {
        // SHA-256, not String.hashCode(): "Aa" and "BB" (and other pairs) share
        // a hashCode, so two different pages of one node could collide and the
        // launcher - and the saved-sites list - would treat them as the same
        // page. The digest is of the full node + path, so identity is the pair.
        val identity = buildString {
            append(nodeHash).append(':').append(persistPath)
            // A form-result page is identified by its request as well; the NUL
            // separator keeps "path + request" unambiguous.
            NomadNetSavedSites.normalizeRequestData(requestData)?.let { append('\u0000').append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(ID_DIGEST_BYTES).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "nomadnet-${nodeHash.take(HASH_PREFIX)}-$suffix"
    }

    /**
     * Builds the label/deep-link triple for the currently displayed page.
     *
     * [pageTitle] is the page's own heading (see [pageTitle]); it wins over a
     * generic path segment like "index" because that is what the user actually
     * sees on the page. [persistPath] keeps its backtick field block so the
     * shortcut re-submits the same request variables (forum threads and other
     * var-bearing pages) instead of reopening as a bare-path fetch.
     */
    fun spec(
        nodeHash: String,
        persistPath: String,
        pageTitle: String? = null,
        requestData: String? = null,
    ): NomadNetShortcutSpec {
        val shortLabel = cleanLabel(pageTitle) ?: labelFromPath(persistPath)
        val barePath = persistPath.substringBefore('`')
        val longLabel = truncate("$shortLabel — ${nodeHash.take(HASH_PREFIX)}:$barePath", MAX_LONG_LABEL)
        return NomadNetShortcutSpec(
            id = shortcutId(nodeHash, persistPath, requestData),
            nodeHash = nodeHash,
            shortLabel = truncate(shortLabel, MAX_SHORT_LABEL),
            longLabel = longLabel,
            deepLink = deepLink(nodeHash, persistPath),
            requestData = requestData,
        )
    }

    /**
     * The page's display title: its first heading line rendered as plain text.
     *
     * NomadNet pages open with a heading (`>Heading`); taking that gives the
     * shortcut a recognizable name without needing an announce lookup.
     * Heading-less pages (raw `#!c=0` text, partials) return null and fall back
     * to the path.
     */
    fun pageTitle(document: MicronDocument): String? {
        val heading = document.lines.firstOrNull { it.isHeading } ?: return null
        val text =
            heading.elements
                .mapNotNull { element ->
                    when (element) {
                        is MicronElement.Text -> element.content
                        is MicronElement.Link -> element.label
                        else -> null
                    }
                }.joinToString("")
        return cleanLabel(text)
    }

    /** Collapses whitespace; null for blank/missing input. */
    private fun cleanLabel(raw: String?): String? {
        val collapsed = raw?.replace(WHITESPACE, " ")?.trim()
        return collapsed?.takeIf { it.isNotEmpty() }
    }

    /**
     * Last meaningful path segment with its extension dropped:
     * `/page/forum/thread.mu` -> "thread", `/page/index.mu` -> "page",
     * no path -> [FALLBACK_LABEL].
     */
    private fun labelFromPath(persistPath: String): String {
        val segments =
            persistPath
                .substringBefore('`')
                .split('/')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('.') }
                .filter { it.isNotEmpty() }
        val last = segments.lastOrNull() ?: return FALLBACK_LABEL
        // "index" names a node's root page, not the page's subject: use its
        // parent segment ("page", "forum") instead of shipping "index".
        if (last.equals("index", ignoreCase = true) && segments.size >= 2) {
            return segments[segments.size - 2]
        }
        return last
    }

    private fun truncate(
        value: String,
        max: Int,
    ): String = if (value.length <= max) value else value.take(max).trimEnd()

    private val WHITESPACE = Regex("\\s+")
}
