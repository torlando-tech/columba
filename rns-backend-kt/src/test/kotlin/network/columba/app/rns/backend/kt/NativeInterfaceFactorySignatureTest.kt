package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `NativeInterfaceFactory.syncInterfaces` restarts a running interface only when
 * its config signature changed, because the diff-based sync otherwise never
 * re-applies an edit to an already-running object (issue #1169 / PR #1188 P1).
 * A TCP client's KISS framing selects the packet codec at construction, so a
 * framing flip MUST change the signature; forgetting it silently drops the
 * toggle until the app restarts (PR #1226 P1).
 */
class NativeInterfaceFactorySignatureTest {
    private fun tcpClient(mode: String, kissFraming: Boolean) =
        InterfaceConfig.TCPClient(
            name = "tcp-$mode",
            enabled = true,
            targetHost = "127.0.0.1",
            targetPort = 4242,
            mode = mode,
            kissFraming = kissFraming,
        )

    @Test
    fun `kiss framing flip changes the TCP client signature`() {
        assertNotEquals(
            NativeInterfaceFactory.configSignatureOf(tcpClient("full", kissFraming = false)),
            NativeInterfaceFactory.configSignatureOf(tcpClient("full", kissFraming = true)),
        )
    }

    @Test
    fun `mode edit changes the TCP client signature`() {
        assertNotEquals(
            NativeInterfaceFactory.configSignatureOf(tcpClient("full", kissFraming = true)),
            NativeInterfaceFactory.configSignatureOf(tcpClient("gateway", kissFraming = true)),
        )
    }

    @Test
    fun `unchanged TCP client config yields a stable signature`() {
        assertEquals(
            NativeInterfaceFactory.configSignatureOf(tcpClient("roaming", kissFraming = false)),
            NativeInterfaceFactory.configSignatureOf(tcpClient("roaming", kissFraming = false)),
        )
    }

    @Test
    fun `mode edit changes the signature for every other interface type`() {
        val configs =
            listOf(
                InterfaceConfig.AutoInterface(name = "auto", enabled = true, mode = "full"),
                InterfaceConfig.TCPServer(name = "server", enabled = true, mode = "full"),
                InterfaceConfig.UDP(name = "udp", enabled = true, mode = "full"),
                InterfaceConfig.RNode(name = "rnode", enabled = true, mode = "full"),
            )

        for (config in configs) {
            val edited =
                when (config) {
                    is InterfaceConfig.AutoInterface -> config.copy(mode = "boundary")
                    is InterfaceConfig.TCPServer -> config.copy(mode = "boundary")
                    is InterfaceConfig.UDP -> config.copy(mode = "boundary")
                    is InterfaceConfig.RNode -> config.copy(mode = "boundary")
                    is InterfaceConfig.TCPClient,
                    is InterfaceConfig.AndroidBLE,
                    -> error("not built in this list")
                }
            assertNotEquals(
                "mode edit must restart ${config.name}",
                NativeInterfaceFactory.configSignatureOf(config),
                NativeInterfaceFactory.configSignatureOf(edited),
            )
        }
    }

    @Test
    fun `kiss framing is not part of the signature for non-TCP interfaces`() {
        // Only a TCP client bakes framing into the running object; the helper must
        // not invent a framing axis for the others.
        val udp = InterfaceConfig.UDP(name = "udp", enabled = true, mode = "full")
        assertEquals(udp.mode, NativeInterfaceFactory.configSignatureOf(udp))
    }
}
