package network.columba.app.data.config

import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [InterfaceConfigCodec]: the clipboard/message config format, the
 * legacy config-file import contract, rendering, and duplicate/name resolution.
 */
class InterfaceConfigCodecTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ==================== parseSnippet ====================

    @Test
    fun `parses the rns fyi Backbone snippet from issue 1114`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Central Oklahoma Reticulum Hub]]
                  type = BackboneInterface
                  enabled = yes
                  remote = reticulum.mcswain.dev
                  target_port = 4242
                  transport_identity = 72d389bca0703e185155f2d2c3eace57
                """.trimIndent(),
            )

        val parsed = result.interfaces.single()
        assertEquals("Central Oklahoma Reticulum Hub", parsed.config.name)
        assertEquals("Central Oklahoma Reticulum Hub", parsed.sectionName)
        assertTrue(parsed.config.enabled)
        val tcp = parsed.config as InterfaceConfig.TCPClient
        assertEquals("reticulum.mcswain.dev", tcp.targetHost)
        assertEquals(4242, tcp.targetPort)
        assertTrue(parsed.warnings.any { it.contains("transport_identity") })
        assertFalse(result.hasErrors)
    }

    @Test
    fun `parses multiple sections with prose and markdown fences around them`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                Here are two nodes I use:

                ```
                [[Node One]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = node1.example.com
                  target_port = 4242

                [[Node Two]]
                  type = TCPServerInterface
                  enabled = yes
                  listen_ip = 0.0.0.0
                  listen_port = 5000
                ```

                Enjoy!
                """.trimIndent(),
            )

        assertEquals(listOf("Node One", "Node Two"), result.interfaces.map { it.config.name })
        assertTrue(result.interfaces[0].config is InterfaceConfig.TCPClient)
        assertTrue(result.interfaces[1].config is InterfaceConfig.TCPServer)
    }

    @Test
    fun `snippet without enabled key defaults to enabled`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[No Enabled Key]]
                  type = TCPClientInterface
                  target_host = 10.0.0.1
                  target_port = 4242
                """.trimIndent(),
            )

        assertTrue(result.interfaces.single().config.enabled)
    }

    @Test
    fun `disabled snippet section is preserved as disabled`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Paused Node]]
                  type = TCPClientInterface
                  enabled = no
                  target_host = 10.0.0.2
                  target_port = 4242
                """.trimIndent(),
            )

        assertFalse(result.interfaces.single().config.enabled)
    }

    @Test
    fun `comments blank lines and semicolons are ignored`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                # copied from rns.recipes
                [[Commented Node]]
                  ; still a comment
                  type = TCPClientInterface

                  enabled = yes # inline text is part of the value and makes enabled invalid
                  target_host = 10.0.0.3
                  target_port = 4242
                """.trimIndent(),
            )

        // "yes # ..." is not a valid boolean; default for snippets is enabled.
        assertTrue(result.interfaces.single().config.enabled)
    }

    @Test
    fun `quoted passphrase survives parsing`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[IFAC Node]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.4
                  target_port = 4242
                  network_name = "My mesh network"
                  passphrase = "my secret passphrase"
                """.trimIndent(),
            )

        val tcp = result.interfaces.single().config as InterfaceConfig.TCPClient
        assertEquals("My mesh network", tcp.networkName)
        assertEquals("my secret passphrase", tcp.passphrase)
    }

    @Test
    fun `TCP client scheme prefix is stripped from hostname`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Node]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = https://node.example.com/
                  target_port = 4242
                """.trimIndent(),
            )

        assertEquals("node.example.com", (result.interfaces.single().config as InterfaceConfig.TCPClient).targetHost)
    }

    @Test
    fun `missing target host is an error`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Broken]]
                  type = TCPClientInterface
                  enabled = yes
                  target_port = 4242
                """.trimIndent(),
            )

        assertTrue(result.interfaces.isEmpty())
        assertTrue(result.hasErrors)
        assertTrue(result.issues.single { it.isError }.message.contains("target_host"))
    }

    @Test
    fun `out of range port is an error`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Broken]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.5
                  target_port = 70000
                """.trimIndent(),
            )

        assertTrue(result.interfaces.isEmpty())
        assertTrue(result.issues.single { it.isError }.message.contains("target_port"))
    }

    @Test
    fun `unsupported interface type is reported without fabricating a config`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Some BLE]]
                  type = AndroidBLE
                  enabled = yes
                  device_name = phone
                """.trimIndent(),
            )

        assertTrue(result.interfaces.isEmpty())
        assertTrue(result.issues.single { it.isError }.message.contains("Unsupported interface type"))
    }

    @Test
    fun `Backbone listener maps to TCPServer`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Backbone Listener]]
                  type = BackboneInterface
                  enabled = yes
                  listen_ip = 0.0.0.0
                  listen_port = 4242
                """.trimIndent(),
            )

        val server = result.interfaces.single().config as InterfaceConfig.TCPServer
        assertEquals(4242, server.listenPort)
        assertEquals("0.0.0.0", server.listenIp)
    }

    @Test
    fun `Backbone kernel-interface listener is rejected with a clear error`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Yggdrasil Backbone]]
                  type = BackboneInterface
                  enabled = yes
                  device = tun0
                  port = 4242
                """.trimIndent(),
            )

        assertTrue(result.interfaces.isEmpty())
        assertTrue(result.issues.single { it.isError }.message.contains("kernel interface"))
    }

    @Test
    fun `UDP interface parses listen and forward endpoints`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[UDP Node]]
                  type = UDPInterface
                  enabled = yes
                  listen_ip = 0.0.0.0
                  listen_port = 4242
                  forward_ip = 255.255.255.255
                  forward_port = 4243
                """.trimIndent(),
            )

        val udp = result.interfaces.single().config as InterfaceConfig.UDP
        assertEquals(4242, udp.listenPort)
        assertEquals(4243, udp.forwardPort)
    }

    @Test
    fun `AutoInterface parses optional fields and defaults to link scope`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Local]]
                  type = AutoInterface
                  enabled = yes
                """.trimIndent(),
            )

        val auto = result.interfaces.single().config as InterfaceConfig.AutoInterface
        assertEquals("", auto.groupId)
        assertEquals("link", auto.discoveryScope)
        assertEquals(NetworkRestriction.WIFI_ONLY, auto.networkRestriction)
    }

    @Test
    fun `RNode TCP interface parses radio fields`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[LoRa Node]]
                  type = RNodeInterface
                  enabled = yes
                  tcp_host = 10.0.0.9
                  frequency = 868000000
                  bandwidth = 125000
                  txpower = 14
                  spreadingfactor = 9
                  codingrate = 5
                  mode = access_point
                """.trimIndent(),
            )

        val rnode = result.interfaces.single().config as InterfaceConfig.RNode
        assertEquals("tcp", rnode.connectionMode)
        assertEquals("10.0.0.9", rnode.tcpHost)
        assertEquals(868_000_000L, rnode.frequency)
        assertEquals(14, rnode.txPower)
        assertEquals(9, rnode.spreadingFactor)
    }

    @Test
    fun `RNode serial interface is importable but warned as device-specific`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Serial RNode]]
                  type = RNodeInterface
                  enabled = yes
                  port = /dev/ttyUSB0
                """.trimIndent(),
            )

        val parsed = result.interfaces.single()
        val rnode = parsed.config as InterfaceConfig.RNode
        assertEquals("/dev/ttyUSB0", rnode.targetDeviceName)
        assertEquals("classic", rnode.connectionMode)
        assertTrue(parsed.warnings.any { it.contains("device-specific") })
    }

    @Test
    fun `unknown setting is warned not dropped`() {
        val result =
            InterfaceConfigCodec.parseSnippet(
                """
                [[Node]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.6
                  target_port = 4242
                  made_up_key = 1
                """.trimIndent(),
            )

        assertEquals(1, result.interfaces.size)
        assertTrue(result.interfaces.single().warnings.any { it.contains("made_up_key") })
    }

    @Test
    fun `text without an interface section yields nothing`() {
        val result = InterfaceConfigCodec.parseSnippet("Hey, are you on the mesh?\nSee [[README]] for details.")
        assertTrue(result.interfaces.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    // ==================== parseConfigFile ====================

    @Test
    fun `config file imports only enabled interfaces inside interfaces section`() {
        val file =
            tempFolder.newFile("reticulum.conf").apply {
                writeText(
                    """
                    [reticulum]
                      enable_transport = yes

                    [interfaces]
                      [[Enabled TCP]]
                        type = TCPClientInterface
                        enabled = yes
                        target_host = 10.0.0.7
                        target_port = 4242

                      [[Disabled TCP]]
                        type = TCPClientInterface
                        enabled = no
                        target_host = 10.0.0.8
                        target_port = 4242

                      [[Enabled Server]]
                        type = TCPServerInterface
                        enabled = yes
                        listen_ip = 0.0.0.0
                        listen_port = 5000
                    """.trimIndent(),
                )
            }

        val configs = InterfaceConfigCodec.parseConfigFile(file)

        assertEquals(listOf("Enabled TCP", "Enabled Server"), configs.map { it.name })
        assertEquals(5000, (configs[1] as InterfaceConfig.TCPServer).listenPort)
    }

    @Test
    fun `config file preserves legacy kiss framing spellings`() {
        val file =
            tempFolder.newFile("reticulum.conf").apply {
                writeText(
                    """
                    [interfaces]
                      [[TCP KISS]]
                        type = TCPClientInterface
                        enabled = yes
                        target_host = 127.0.0.1
                        target_port = 8001
                        kiss_framing = True
                    """.trimIndent(),
                )
            }

        val tcp = InterfaceConfigCodec.parseConfigFile(file).single() as InterfaceConfig.TCPClient
        assertTrue(tcp.kissFraming)
    }

    @Test
    fun `missing config file returns empty list`() {
        assertTrue(InterfaceConfigCodec.parseConfigFile(tempFolder.newFile("missing.conf").let { it.delete(); it }).isEmpty())
    }

    // ==================== Rendering ====================

    @Test
    fun `TCP client round-trips through render and parse`() {
        val config =
            InterfaceConfig.TCPClient(
                name = "Shared Node",
                enabled = true,
                targetHost = "node.example.com",
                targetPort = 4242,
                kissFraming = true,
                mode = "full",
                networkName = "mesh",
                passphrase = "secret",
            )

        val rendered = InterfaceConfigCodec.renderSnippet(config)
        assertNotNull(rendered)
        val parsed = InterfaceConfigCodec.parseSnippet(rendered!!).interfaces.single().config
        assertEquals(config, parsed)
    }

    @Test
    fun `TCP server round-trips through render and parse`() {
        val config =
            InterfaceConfig.TCPServer(
                name = "My Server",
                enabled = false,
                listenIp = "0.0.0.0",
                listenPort = 5000,
                mode = "gateway",
            )

        val parsed =
            InterfaceConfigCodec
                .parseSnippet(InterfaceConfigCodec.renderSnippet(config)!!)
                .interfaces
                .single()
                .config
        assertEquals(config, parsed)
    }

    @Test
    fun `UDP and AutoInterface round-trip through render and parse`() {
        val udp =
            InterfaceConfig.UDP(
                name = "UDP Link",
                enabled = true,
                listenIp = "0.0.0.0",
                listenPort = 4242,
                forwardIp = "255.255.255.255",
                forwardPort = 4243,
                mode = "full",
            )
        val auto =
            InterfaceConfig.AutoInterface(
                name = "Local Discovery",
                enabled = true,
                groupId = "group",
                discoveryScope = "site",
                discoveryPort = 29716,
                dataPort = 42671,
                mode = "full",
            )

        assertEquals(udp, parseRendered(udp))
        assertEquals(auto, parseRendered(auto))
    }

    @Test
    fun `RNode TCP round-trips through render and parse`() {
        val config =
            InterfaceConfig.RNode(
                name = "WiFi RNode",
                enabled = true,
                connectionMode = "tcp",
                tcpHost = "10.0.0.10",
                tcpPort = 7633,
                frequency = 868_000_000L,
                bandwidth = 125_000,
                txPower = 14,
                spreadingFactor = 9,
                codingRate = 5,
                mode = "access_point",
            )

        assertEquals(config, parseRendered(config))
    }

    @Test
    fun `unportable interface types have no snippet`() {
        assertNull(InterfaceConfigCodec.renderSnippet(InterfaceConfig.AndroidBLE(name = "BLE")))
        assertNull(
            InterfaceConfigCodec.renderSnippet(
                InterfaceConfig.RNode(name = "BT RNode", connectionMode = "ble", targetDeviceName = "RNode"),
            ),
        )
    }

    @Test
    fun `passphrase with whitespace and quotes round-trips`() {
        val config =
            InterfaceConfig.TCPClient(
                name = "Quoted",
                targetHost = "10.0.0.11",
                targetPort = 4242,
                passphrase = "a \"quoted\" pass phrase",
            )

        assertEquals(config, parseRendered(config))
    }

    @Test
    fun `renderSnippets skips unportable entries and separates the rest`() {
        val text =
            InterfaceConfigCodec.renderSnippets(
                listOf(
                    InterfaceConfig.TCPClient(name = "One", targetHost = "10.0.0.12", targetPort = 4242),
                    InterfaceConfig.AndroidBLE(name = "BLE"),
                    InterfaceConfig.TCPClient(name = "Two", targetHost = "10.0.0.13", targetPort = 4242),
                ),
            )

        assertEquals(2, InterfaceConfigCodec.parseSnippet(text).interfaces.size)
    }

    @Test
    fun `section names with brackets are sanitised for rendering`() {
        val rendered = InterfaceConfigCodec.renderSnippet(InterfaceConfig.TCPClient(name = "bad]]name", targetHost = "h", targetPort = 1))
        assertNotNull(rendered)
        // The parser must still recover exactly one interface even with a hostile name.
        assertEquals(1, InterfaceConfigCodec.parseSnippet(rendered!!).interfaces.size)
    }

    // ==================== Duplicate and name resolution ====================

    @Test
    fun `existing endpoint is marked duplicate`() {
        val existing = InterfaceConfig.TCPClient(name = "Old", targetHost = "node.example.com", targetPort = 4242)
        val parsed =
            InterfaceConfigCodec
                .parseSnippet(
                    """
                    [[New Name]]
                      type = TCPClientInterface
                      enabled = yes
                      target_host = node.example.com
                      target_port = 4242
                    """.trimIndent(),
                ).interfaces

        val resolved = InterfaceConfigCodec.resolveImports(parsed, listOf(existing)).single()
        assertTrue(resolved.duplicate)
        assertEquals("New Name", resolved.config.name)
    }

    @Test
    fun `duplicate within the same paste is marked as duplicate`() {
        val parsed =
            InterfaceConfigCodec.parseSnippet(
                """
                [[First]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.14
                  target_port = 4242

                [[Second]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.14
                  target_port = 4242
                """.trimIndent(),
            ).interfaces

        val resolved = InterfaceConfigCodec.resolveImports(parsed, emptyList())
        assertFalse(resolved[0].duplicate)
        assertTrue(resolved[1].duplicate)
    }

    @Test
    fun `name collision with a different endpoint gets a numeric suffix`() {
        val existing = InterfaceConfig.TCPClient(name = "Node", targetHost = "old.example.com", targetPort = 1)
        val parsed =
            InterfaceConfigCodec
                .parseSnippet(
                    """
                    [[node]]
                      type = TCPClientInterface
                      enabled = yes
                      target_host = new.example.com
                      target_port = 4242
                    """.trimIndent(),
                ).interfaces

        val resolved = InterfaceConfigCodec.resolveImports(parsed, listOf(existing)).single()
        assertFalse(resolved.duplicate)
        assertEquals("node (2)", resolved.config.name)
        assertEquals("node", resolved.originalName)
    }

    @Test
    fun `name suffix keeps interface name within the limit`() {
        val longName = "N".repeat(50)
        val existing = InterfaceConfig.TCPClient(name = longName, targetHost = "old.example.com", targetPort = 1)
        val parsed =
            InterfaceConfigCodec
                .parseSnippet(
                    """
                    [[$longName]]
                      type = TCPClientInterface
                      enabled = yes
                      target_host = new.example.com
                      target_port = 4242
                    """.trimIndent(),
                ).interfaces

        val resolved = InterfaceConfigCodec.resolveImports(parsed, listOf(existing)).single()
        assertEquals(50, resolved.config.name.length)
        assertTrue(resolved.config.name.endsWith(" (2)"))
    }

    private fun parseRendered(config: InterfaceConfig): InterfaceConfig =
        InterfaceConfigCodec.parseSnippet(InterfaceConfigCodec.renderSnippet(config)!!).interfaces.single().config
}
