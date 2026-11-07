package network.columba.app.nomadnet

import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronLine
import network.columba.app.micron.MicronStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Label/id/deep-link rules for NomadNet home-screen shortcuts.
 *
 * These are pinned because a shortcut is stored by the launcher, outliving the
 * app process: a changed id would duplicate instead of updating an existing
 * pinned shortcut, and a changed link format would strand every pinned shortcut
 * on a dead deep link.
 */
class NomadNetShortcutsTest {
    private val node = "9ce92808be498e9e05590ff27cbfdfe4"

    @Test
    fun `deep link keeps the persist path verbatim`() {
        assertEquals(
            "nomadnetwork://$node:/page/forum/thread.mu`cat=help|thread=how-to-rngit",
            NomadNetShortcuts.deepLink(node, "/page/forum/thread.mu`cat=help|thread=how-to-rngit"),
        )
    }

    @Test
    fun `shortcut id is stable for the same page`() {
        assertEquals(
            NomadNetShortcuts.shortcutId(node, "/page/index.mu"),
            NomadNetShortcuts.shortcutId(node, "/page/index.mu"),
        )
    }

    @Test
    fun `shortcut id differs per node and per path`() {
        val base = NomadNetShortcuts.shortcutId(node, "/page/index.mu")
        assertNotEquals(base, NomadNetShortcuts.shortcutId("0d1ddfcc8a820d2fe2fe1d4c895e18fc", "/page/index.mu"))
        assertNotEquals(base, NomadNetShortcuts.shortcutId(node, "/page/other.mu"))
        // Same path, different link fields: a different page, so a different shortcut.
        assertNotEquals(
            NomadNetShortcuts.shortcutId(node, "/page/forum/thread.mu`thread=a"),
            NomadNetShortcuts.shortcutId(node, "/page/forum/thread.mu`thread=b"),
        )
    }

    @Test
    fun `spec uses the page heading as the short label`() {
        val spec =
            NomadNetShortcuts.spec(
                nodeHash = node,
                persistPath = "/page/index.mu",
                pageTitle = "Welcome to   my node",
            )
        assertEquals("Welcome to my node", spec.shortLabel)
        assertTrue(spec.longLabel.startsWith("Welcome to my node — ${node.take(8)}:/page/index.mu"))
    }

    @Test
    fun `spec falls back to the path when there is no heading`() {
        assertEquals("thread", NomadNetShortcuts.spec(node, "/page/forum/thread.mu", null).shortLabel)
        // "index" is a node's root page name, not a subject: use its parent.
        assertEquals("forum", NomadNetShortcuts.spec(node, "/page/forum/index.mu", null).shortLabel)
        assertEquals("NomadNet page", NomadNetShortcuts.spec(node, "", null).shortLabel)
    }

    @Test
    fun `spec ignores blank headings`() {
        assertEquals("forum", NomadNetShortcuts.spec(node, "/page/forum/index.mu", "   ").shortLabel)
    }

    @Test
    fun `spec keeps link fields in the deep link but not in the long label`() {
        val spec = NomadNetShortcuts.spec(node, "/page/forum/thread.mu`cat=help|thread=x", null)
        assertEquals("nomadnetwork://$node:/page/forum/thread.mu`cat=help|thread=x", spec.deepLink)
        assertTrue(spec.longLabel.endsWith("${node.take(8)}:/page/forum/thread.mu"))
    }

    @Test
    fun `labels are length capped`() {
        val spec =
            NomadNetShortcuts.spec(
                nodeHash = node,
                persistPath = "/page/index.mu",
                pageTitle = "A very long page title that no launcher would ever display in full",
            )
        assertEquals(24, spec.shortLabel.length)
        assertTrue(spec.longLabel.length <= 64)
    }

    @Test
    fun `pageTitle reads the first heading line`() {
        val document =
            MicronDocument(
                lines =
                    listOf(
                        MicronLine(elements = listOf(MicronElement.Text("Navigation", MicronStyle()))),
                        MicronLine(
                            elements =
                                listOf(
                                    MicronElement.Text("My ", MicronStyle()),
                                    MicronElement.Link("Node", "/page/index.mu", emptyList(), MicronStyle()),
                                ),
                            isHeading = true,
                        ),
                    ),
            )
        assertEquals("My Node", NomadNetShortcuts.pageTitle(document))
    }

    @Test
    fun `pageTitle is null without a heading`() {
        val document = MicronDocument(lines = listOf(MicronLine(elements = listOf(MicronElement.Text("plain", MicronStyle())))))
        assertEquals(null, NomadNetShortcuts.pageTitle(document))
    }
}
