package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PartialManager.resolveNomadNetUrl] — the pure function that resolves
 * NomadNet link destinations into (nodeHash, path) pairs.
 *
 * This is the most critical piece of link navigation logic shared between
 * PartialManager and NomadNetBrowserViewModel.
 */
class ResolveNomadNetUrlTest {
    private val currentNode = "abcdef01234567890abcdef012345678"

    private fun resolve(destination: String) = PartialManager.resolveNomadNetUrl(destination, currentNode)

    // ── Absolute paths (stay on current node) ──

    @Test
    fun `absolute path stays on current node`() {
        assertEquals(currentNode to "/page/about.mu", resolve("/page/about.mu"))
    }

    @Test
    fun `root path stays on current node`() {
        assertEquals(currentNode to "/", resolve("/"))
    }

    // ── Colon-prefixed paths (stay on current node) ──

    @Test
    fun `colon prefix resolves to current node with path`() {
        assertEquals(currentNode to "/page/info.mu", resolve(":/page/info.mu"))
    }

    @Test
    fun `bare colon resolves to default path on current node`() {
        assertEquals(currentNode to "/page/index.mu", resolve(":"))
    }

    // ── Full 32-char hex hash (navigate to different node) ──

    @Test
    fun `bare 32-char hex hash navigates to that node default path`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        assertEquals(otherNode to "/page/index.mu", resolve(otherNode))
    }

    @Test
    fun `uppercase hex hash is lowercased`() {
        val upperNode = "1234567890ABCDEF1234567890ABCDEF"
        assertEquals(upperNode.lowercase() to "/page/index.mu", resolve(upperNode))
    }

    // ── hash:path format ──

    @Test
    fun `hash colon path navigates to other node with path`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        assertEquals(otherNode to "/page/about.mu", resolve("$otherNode:/page/about.mu"))
    }

    @Test
    fun `hash colon empty path uses default`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        assertEquals(otherNode to "/page/index.mu", resolve("$otherNode:"))
    }

    // ── @ format (cross-node links) ──

    @Test
    fun `at-sign with hash and colon path`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        val result = resolve("@$otherNode:/page/test.mu")
        assertEquals(otherNode to "/page/test.mu", result)
    }

    @Test
    fun `at-sign with hash only uses default path`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        val result = resolve("@$otherNode")
        assertEquals(otherNode to "/page/index.mu", result)
    }

    @Test
    fun `at-sign with hash and empty colon uses default path`() {
        val otherNode = "1234567890abcdef1234567890abcdef"
        val result = resolve("@$otherNode:")
        assertEquals(otherNode to "/page/index.mu", result)
    }

    // ── Relative / fallback paths ──

    @Test
    fun `relative path stays on current node`() {
        assertEquals(currentNode to "page/info.mu", resolve("page/info.mu"))
    }

    @Test
    fun `short non-hex string treated as relative path`() {
        assertEquals(currentNode to "hello", resolve("hello"))
    }

    @Test
    fun `non-hex 32-char string treated as relative path`() {
        // Contains non-hex chars (g, z)
        val nonHex = "1234567890abcdefg234567890abcdez"
        assertEquals(currentNode to nonHex, resolve(nonHex))
    }

    // ── Edge cases ──

    @Test
    fun `colon in relative path with non-hex prefix stays on current node`() {
        assertEquals(currentNode to "foo:bar", resolve("foo:bar"))
    }

    @Test
    fun `empty string stays on current node`() {
        assertEquals(currentNode to "", resolve(""))
    }
}
