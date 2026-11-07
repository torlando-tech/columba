package network.columba.app.nomadnet

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One bookmarked NomadNet page.
 *
 * [persistPath] keeps the trailing backtick field block when the page carried
 * request variables, so reopening a saved forum thread re-submits the same
 * fields instead of fetching the bare path (same rule as
 * [NomadNetShortcuts.spec]).
 *
 * [label] is the page's own heading captured at save time; it is only used
 * until an announce/contact name for [nodeHash] is known, and survives for
 * nodes that never announce a name.
 */
data class NomadNetSavedSite(
    val nodeHash: String,
    val persistPath: String,
    val label: String?,
    val savedAt: Long,
) {
    /** Identity of a saved page: one entry per node + path (incl. field block). */
    val key: String get() = NomadNetShortcuts.shortcutId(nodeHash, persistPath)
}

/**
 * Storage rules for the saved NomadNet pages list: a stable JSON codec plus
 * the add/remove transitions.
 *
 * Kept free of Android types so ordering, de-duplication and malformed-storage
 * recovery are plain unit tests. The list persists as a single JSON string in
 * DataStore ([network.columba.app.repository.SettingsRepository]); every write
 * is a read-modify-write inside one `edit` block, so a concurrent save/remove
 * can never drop an entry.
 */
object NomadNetSavedSites {
    private const val KEY_NODE = "h"
    private const val KEY_PATH = "p"
    private const val KEY_LABEL = "l"
    private const val KEY_SAVED_AT = "t"

    /** Destination hashes are 32 hex chars; anything else is not browsable. */
    private val NODE_HASH = Regex("^[0-9a-f]{32}$")

    /**
     * Canonical node hash for storage, or null when [nodeHash] cannot identify
     * a node. Lowercased because deep links and announces disagree on case.
     */
    fun normalizeNodeHash(nodeHash: String): String? {
        val normalized = nodeHash.trim().lowercase()
        return normalized.takeIf { NODE_HASH.matches(it) }
    }

    /**
     * Most recently saved first; blank/unparseable entries are dropped.
     *
     * Corrupt JSON is swallowed deliberately: an empty list loses the
     * bookmarks but must not take the tab down with it, and nothing usable can
     * be recovered from bytes that do not parse.
     */
    @Suppress("SwallowedException")
    fun decode(json: String?): List<NomadNetSavedSite> {
        if (json.isNullOrBlank()) return emptyList()
        val array =
            try {
                JSONArray(json)
            } catch (e: JSONException) {
                return emptyList()
            }
        val decoded =
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.toSavedSite() }
        // Writes already de-duplicate; storage hand-edited or merged from two
        // devices may not. Keep the newest copy of each page.
        val byKey = LinkedHashMap<String, NomadNetSavedSite>()
        for (site in decoded) {
            val current = byKey[site.key]
            if (current == null || site.savedAt > current.savedAt) byKey[site.key] = site
        }
        return byKey.values.sortedByDescending { it.savedAt }
    }

    fun encode(sites: List<NomadNetSavedSite>): String {
        val array = JSONArray()
        for (site in sites) {
            array.put(
                JSONObject()
                    .put(KEY_NODE, site.nodeHash)
                    .put(KEY_PATH, site.persistPath)
                    .put(KEY_SAVED_AT, site.savedAt)
                    .apply { site.label?.let { put(KEY_LABEL, it) } },
            )
        }
        return array.toString()
    }

    /**
     * Adds [site], or refreshes its label/path if that page is already saved.
     * The original [NomadNetSavedSite.savedAt] is kept so re-saving a page does
     * not shuffle the list under the user.
     */
    fun upsert(
        sites: List<NomadNetSavedSite>,
        site: NomadNetSavedSite,
    ): List<NomadNetSavedSite> {
        val existing = sites.firstOrNull { it.key == site.key }
        val merged = if (existing == null) site else site.copy(savedAt = existing.savedAt)
        return (listOf(merged) + sites.filterNot { it.key == site.key }).sortedByDescending { it.savedAt }
    }

    fun remove(
        sites: List<NomadNetSavedSite>,
        nodeHash: String,
        persistPath: String,
    ): List<NomadNetSavedSite> {
        val key = NomadNetShortcuts.shortcutId(nodeHash, persistPath)
        return sites.filterNot { it.key == key }
    }

    fun contains(
        sites: List<NomadNetSavedSite>,
        nodeHash: String,
        persistPath: String,
    ): Boolean {
        val key = NomadNetShortcuts.shortcutId(nodeHash, persistPath)
        return sites.any { it.key == key }
    }

    private fun JSONObject.toSavedSite(): NomadNetSavedSite? {
        val nodeHash = normalizeNodeHash(optString(KEY_NODE)) ?: return null
        val persistPath = optString(KEY_PATH).trim()
        if (persistPath.isEmpty()) return null
        val label = optString(KEY_LABEL).trim().takeIf { it.isNotEmpty() }
        return NomadNetSavedSite(
            nodeHash = nodeHash,
            persistPath = persistPath,
            label = label,
            savedAt = optLong(KEY_SAVED_AT),
        )
    }
}
