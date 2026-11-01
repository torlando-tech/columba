package network.columba.app.data.config

import network.columba.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ConfigFileParser], focused on TCPClientInterface
 * `kiss_framing` round-trip fidelity when interfaces are read back from a
 * Reticulum config file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigFileParserTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun parse(config: String): List<InterfaceConfig> =
        ConfigFileParser.parseConfigFile(tempFolder.newFile("reticulum.conf").apply { writeText(config) })

    private fun tcpConfig(block: String): InterfaceConfig.TCPClient {
        val interfaces =
            parse(
                """
                [interfaces]
                    [[TCP KISS Interface]]
                    type = TCPClientInterface
                    enabled = yes
                    target_host = 127.0.0.1
                    target_port = 8001
                $block
                """.trimIndent(),
            )
        assertEquals(1, interfaces.size)
        return interfaces.single() as InterfaceConfig.TCPClient
    }

    @Test
    fun `kiss_framing True from upstream docs parses to kissFraming true`() {
        assertTrue(tcpConfig("                    kiss_framing = True").kissFraming)
    }

    @Test
    fun `kiss_framing true from app emitter parses to kissFraming true`() {
        assertTrue(tcpConfig("                    kiss_framing = true").kissFraming)
    }

    @Test
    fun `kiss_framing yes parses to kissFraming true`() {
        assertTrue(tcpConfig("                    kiss_framing = yes").kissFraming)
    }

    @Test
    fun `kiss_framing false parses to kissFraming false`() {
        assertFalse(tcpConfig("                    kiss_framing = false").kissFraming)
    }

    @Test
    fun `absent kiss_framing defaults to false`() {
        assertFalse(tcpConfig("").kissFraming)
    }

    @Test
    fun `emitted KISS interface block round-trips through parser`() {
        // Mirrors the exact block rns-backend-py RnsConfigFile emits for a
        // KISS-framed TCP client, so a config written by the app reads back
        // with the setting intact.
        val interfaces =
            parse(
                """
                [interfaces]
                    [[TCP KISS Interface]]
                    type = TCPClientInterface
                    enabled = yes
                    target_host = 127.0.0.1
                    target_port = 8001
                    kiss_framing = true
                    mode = full
                """.trimIndent(),
            )
        val config = interfaces.single() as InterfaceConfig.TCPClient
        assertTrue(config.kissFraming)
        assertEquals("127.0.0.1", config.targetHost)
        assertEquals(8001, config.targetPort)
    }
}
