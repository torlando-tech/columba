package network.columba.app.nomadnet

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
     * node + path, including the backtick field block.
     */
    fun shortcutId(
        nodeHash: String,
        persistPath: String,
    ): String {
        val suffix =
            deepLink(nodeHash, persistPath)
                .hashCode()
                .toLong()
                .and(0xFFFF_FFFFL)
                .toString(16)
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
    ): NomadNetShortcutSpec {
        val shortLabel = cleanLabel(pageTitle) ?: labelFromPath(persistPath)
        val barePath = persistPath.substringBefore('`')
        val longLabel = truncate("$shortLabel — ${nodeHash.take(HASH_PREFIX)}:$barePath", MAX_LONG_LABEL)
        return NomadNetShortcutSpec(
            id = shortcutId(nodeHash, persistPath),
            nodeHash = nodeHash,
            shortLabel = truncate(shortLabel, MAX_SHORT_LABEL),
            longLabel = longLabel,
            deepLink = deepLink(nodeHash, persistPath),
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
