package network.columba.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatLocalInterfaceLabel] — the pure `(name, type)` derivation for
 * RNS's runtime-spawned shared-instance interfaces (#921 follow-up). Exercises the
 * production function directly, no Python runtime required.
 */
class LocalInterfaceLabelTest {
    @Test
    fun `server with a TCP bind port`() {
        assertEquals(
            "Shared Instance @ TCP 37428" to "Shared Instance (host)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalServerInterface",
                bindPort = "37428",
                socketPath = null,
                targetIp = null,
                targetPort = null,
                rawName = "Reticulum",
            ),
        )
    }

    @Test
    fun `server falls back to the default port when bind port is unknown`() {
        assertEquals(
            "Shared Instance @ TCP 37428" to "Shared Instance (host)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalServerInterface",
                bindPort = "None",
                socketPath = null,
                targetIp = null,
                targetPort = null,
                rawName = "Reticulum",
            ),
        )
    }

    @Test
    fun `server AF_UNIX socket path has its leading null stripped`() {
        // RNS stores abstract-socket paths with a leading null byte; the value must not
        // leak into the displayed endpoint.
        assertEquals(
            "Shared Instance @ unix:rns/columba" to "Shared Instance (host)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalServerInterface",
                bindPort = null,
                socketPath = "\u0000rns/columba",
                targetIp = null,
                targetPort = null,
                rawName = "Reticulum",
            ),
        )
    }

    @Test
    fun `client with target ip and port`() {
        assertEquals(
            "Client @ 127.0.0.1:44910" to "Shared Instance (client)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalClientInterface",
                bindPort = null,
                socketPath = null,
                targetIp = "127.0.0.1",
                targetPort = "44910",
                rawName = "44910",
            ),
        )
    }

    @Test
    fun `client with only a port`() {
        assertEquals(
            "Client @ port 44910" to "Shared Instance (client)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalClientInterface",
                bindPort = null,
                socketPath = null,
                targetIp = null,
                targetPort = "44910",
                rawName = "44910",
            ),
        )
    }

    @Test
    fun `client falls back to the raw name when no socket coordinates are present`() {
        assertEquals(
            "Client @ LocalInterface[5]" to "Shared Instance (client)",
            formatLocalInterfaceLabel(
                pyClassName = "LocalClientInterface",
                bindPort = null,
                socketPath = null,
                targetIp = null,
                targetPort = null,
                rawName = "LocalInterface[5]",
            ),
        )
    }

    @Test
    fun `unknown interface class keeps the legacy name and type derivation`() {
        assertEquals(
            "MyTCP[host:4242]" to "MyTCP",
            formatLocalInterfaceLabel(
                pyClassName = "TCPClientInterface",
                bindPort = null,
                socketPath = null,
                targetIp = null,
                targetPort = null,
                rawName = "MyTCP[host:4242]",
            ),
        )
    }
}
