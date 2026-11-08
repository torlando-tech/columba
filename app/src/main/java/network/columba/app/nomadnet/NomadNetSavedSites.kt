package network.columba.app.nomadnet

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

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
 *
 * [requestData] is the JSON the node was asked with when the page was reached
 * through a form submission (a search, a filtered forum index). Reopening the
 * page replays it, because [persistPath] only names the submitted fields - the
 * values are not in the path and would otherwise be lost. Null for plain pages,
 * which re-fetch with no request data.
 *
 * Two saved pages of one path with different [requestData] are different
 * pages: bookmarking one must not light the star on the other, and removing
 * one must not remove the other.
 */
data class NomadNetSavedSite(
    val nodeHash: String,
    val persistPath: String,
    val label: String?,
    val savedAt: Long,
    val requestData: String? = null,
) {
    /**
     * Identity of a saved page: the full node + path pair (incl. the field
     * block), plus the request it was fetched with. The pair is compared
     * directly, so no hash collision can merge two plain pages; the request is
     * digested because submitted values must be part of the identity too.
     */
    val key: String get() = NomadNetSavedSites.key(nodeHash, persistPath, requestData)
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
    /**
     * Stored identity of a page: the full node + path pair, field block
     * included. Compared as plain strings rather than a hash digest, so two
     * pages can never collide.
     *
     * A form-result page also carries its request: [persistPath] only names the
     * submitted fields, so two searches of one path with different values would
     * otherwise share a bookmark. The request is digested (rather than stored
     * whole) so the identity stays a short, separator-free string; plain pages
     * keep the bare pair, so storage written before requests existed keeps its
     * identity.
     */
    fun key(
        nodeHash: String,
        persistPath: String,
        requestData: String? = null,
    ): String {
        val base = "$nodeHash:$persistPath"
        val request = normalizeRequestData(requestData) ?: return base
        val digest = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(REQUEST_DIGEST_BYTES).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "$base#$suffix"
    }

    /** Trims [raw]; blank means "no request", i.e. a plain page fetch. */
    fun normalizeRequestData(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private const val KEY_NODE = "h"
    private const val KEY_PATH = "p"
    private const val KEY_LABEL = "l"
    private const val KEY_SAVED_AT = "t"
    private const val KEY_REQUEST_DATA = "r"
    private const val REQUEST_DIGEST_BYTES = 16

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
                    .apply { site.label?.let { put(KEY_LABEL, it) } }
                    .apply { site.requestData?.let { put(KEY_REQUEST_DATA, it) } },
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
        requestData: String? = null,
    ): List<NomadNetSavedSite> {
        val key = key(nodeHash, persistPath, requestData)
        return sites.filterNot { site -> site.key == key }
    }

    fun contains(
        sites: List<NomadNetSavedSite>,
        nodeHash: String,
        persistPath: String,
        requestData: String? = null,
    ): Boolean {
        val key = key(nodeHash, persistPath, requestData)
        return sites.any { site -> site.key == key }
    }

    private fun JSONObject.toSavedSite(): NomadNetSavedSite? {
        val nodeHash = normalizeNodeHash(optString(KEY_NODE)) ?: return null
        val persistPath = optString(KEY_PATH).trim()
        if (persistPath.isEmpty()) return null
        val label = optString(KEY_LABEL).trim().takeIf { it.isNotEmpty() }
        val requestData = normalizeRequestData(optString(KEY_REQUEST_DATA))
        return NomadNetSavedSite(
            nodeHash = nodeHash,
            persistPath = persistPath,
            label = label,
            savedAt = optLong(KEY_SAVED_AT),
            requestData = requestData,
        )
    }
}
