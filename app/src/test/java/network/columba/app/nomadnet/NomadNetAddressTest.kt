package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the NomadNet address field parser shared by the browser's URL bar
 * and the tab home, covering every accepted input shape.
 */
class NomadNetAddressTest {
    private val node = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun `bare node hash opens the index page`() {
        assertEquals(
            NomadNetAddressTarget.Page(node, "/page/index.mu"),
            parseNomadNetAddress(node),
        )
    }

    @Test
    fun `node hash plus path keeps the path including its field block`() {
        val path = "/page/forum/thread.mu`cat=help|thread=how-to"

        assertEquals(
            NomadNetAddressTarget.Page(node, path),
            parseNomadNetAddress("$node:$path"),
        )
    }

    @Test
    fun `nomadnetwork scheme is stripped`() {
        assertEquals(
            NomadNetAddressTarget.Page(node, "/page/forum/index.mu"),
            parseNomadNetAddress("nomadnetwork://$node:/page/forum/index.mu"),
        )
    }

    @Test
    fun `page hashes are lowercased for lookup`() {
        assertEquals(
            NomadNetAddressTarget.Page(node, "/page/index.mu"),
            parseNomadNetAddress("${node.uppercase()}:"),
        )
    }

    @Test
    fun `lxmf prefix is a conversation, not a page`() {
        assertEquals(
            NomadNetAddressTarget.Conversation(node),
            parseNomadNetAddress("lxmf@$node"),
        )
    }

    @Test
    fun `lxmf conversation survives the deep-link scheme`() {
        assertEquals(
            NomadNetAddressTarget.Conversation(node),
            parseNomadNetAddress("nomadnetwork://lxmf@$node"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            NomadNetAddressTarget.Page(node, "/page/index.mu"),
            parseNomadNetAddress("   $node   "),
        )
    }

    @Test
    fun `blank input is not a target`() {
        assertNull(parseNomadNetAddress(""))
        assertNull(parseNomadNetAddress("   "))
        assertNull(parseNomadNetAddress("nomadnetwork://"))
        assertNull(parseNomadNetAddress("lxmf@"))
    }
}
