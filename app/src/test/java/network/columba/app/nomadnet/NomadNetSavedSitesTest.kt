package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the saved NomadNet pages storage rules: the JSON codec survives
 * round-trips and corrupt storage, and the add/remove transitions keep one
 * entry per node + path.
 */
class NomadNetSavedSitesTest {
    private val nodeA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val nodeB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val indexPath = "/page/index.mu"
    private val threadPath = "/page/forum/thread.mu`cat=help"

    private fun site(
        nodeHash: String = nodeA,
        persistPath: String = indexPath,
        label: String? = "Index",
        savedAt: Long = 1_000L,
    ) = NomadNetSavedSite(nodeHash, persistPath, label, savedAt)

    // ── normalizeNodeHash ──

    @Test
    fun `node hashes are lowercased and trimmed`() {
        assertEquals(nodeA, NomadNetSavedSites.normalizeNodeHash("  ${nodeA.uppercase()} "))
    }

    @Test
    fun `non-hex and wrong-length hashes are rejected`() {
        assertNull(NomadNetSavedSites.normalizeNodeHash("not-a-hash"))
        assertNull(NomadNetSavedSites.normalizeNodeHash("abc"))
        assertNull(NomadNetSavedSites.normalizeNodeHash(""))
        assertNull(NomadNetSavedSites.normalizeNodeHash("z".repeat(32)))
    }

    // ── encode / decode ──

    @Test
    fun `round trip preserves every field including the request field block`() {
        val sites = listOf(site(savedAt = 2_000L), site(nodeHash = nodeB, persistPath = threadPath, label = "Thread", savedAt = 1_000L))

        val decoded = NomadNetSavedSites.decode(NomadNetSavedSites.encode(sites))

        assertEquals(sites, decoded)
    }

    @Test
    fun `decode returns an empty list for missing or corrupt storage`() {
        assertTrue(NomadNetSavedSites.decode(null).isEmpty())
        assertTrue(NomadNetSavedSites.decode("").isEmpty())
        assertTrue(NomadNetSavedSites.decode("{not json}").isEmpty())
        assertTrue(NomadNetSavedSites.decode("[]").isEmpty())
    }

    @Test
    fun `decode drops entries that cannot identify a page`() {
        val json =
            """
            [
              {"h": "not-a-hash", "p": "$indexPath", "t": 5},
              {"h": "$nodeA", "p": "  ", "t": 4},
              {"h": "$nodeA", "p": "$indexPath", "t": 3}
            ]
            """.trimIndent()

        val decoded = NomadNetSavedSites.decode(json)

        assertEquals(listOf(site(label = null, savedAt = 3L)), decoded)
    }

    @Test
    fun `decode keeps the newest copy per node plus path and orders newest first`() {
        val json =
            """
            [
              {"h": "$nodeA", "p": "$indexPath", "l": "old", "t": 1},
              {"h": "$nodeA", "p": "$indexPath", "l": "new", "t": 2},
              {"h": "$nodeA", "p": "$threadPath", "t": 3},
              {"h": "$nodeB", "p": "$indexPath", "t": 4}
            ]
            """.trimIndent()

        val decoded = NomadNetSavedSites.decode(json)

        assertEquals(
            listOf(
                site(nodeHash = nodeB, label = null, savedAt = 4L),
                site(persistPath = threadPath, label = null, savedAt = 3L),
                site(label = "new", savedAt = 2L),
            ),
            decoded,
        )
    }

    // ── upsert / remove ──

    @Test
    fun `upsert puts a new page at the front`() {
        val existing = listOf(site(savedAt = 1L))

        val next = NomadNetSavedSites.upsert(existing, site(nodeHash = nodeB, savedAt = 2L))

        assertEquals(listOf(nodeB, nodeA), next.map { it.nodeHash })
    }

    @Test
    fun `re-saving an existing page keeps its position and refreshes the label`() {
        val existing =
            listOf(
                site(nodeHash = nodeB, savedAt = 2L),
                site(savedAt = 1L, label = "stale"),
            )

        val next = NomadNetSavedSites.upsert(existing, site(label = "fresh", savedAt = 99L))

        assertEquals(listOf(nodeB, nodeA), next.map { it.nodeHash })
        assertEquals("fresh", next.last().label)
        assertEquals("re-saving must not move the page to the top", 1L, next.last().savedAt)
    }

    @Test
    fun `a page with request fields is a separate entry from the bare path`() {
        val existing = listOf(site(savedAt = 1L))

        val next = NomadNetSavedSites.upsert(existing, site(persistPath = threadPath, savedAt = 2L))

        assertEquals(2, next.size)
        assertTrue(NomadNetSavedSites.contains(next, nodeA, threadPath))
        assertTrue(NomadNetSavedSites.contains(next, nodeA, indexPath))
    }

    @Test
    fun `remove drops exactly the requested page`() {
        val existing =
            listOf(
                site(savedAt = 2L),
                site(persistPath = threadPath, savedAt = 1L),
            )

        val next = NomadNetSavedSites.remove(existing, nodeA, indexPath)

        assertEquals(listOf(threadPath), next.map { it.persistPath })
        assertFalse(NomadNetSavedSites.contains(next, nodeA, indexPath))
    }
}
