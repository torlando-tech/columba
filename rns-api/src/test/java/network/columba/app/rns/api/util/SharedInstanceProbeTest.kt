package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class SharedInstanceProbeTest {
    private fun cfg(preferOwn: Boolean) =
        ReticulumConfig(
            storagePath = "/tmp/test",
            enabledInterfaces = emptyList(),
            preferOwnInstance = preferOwn,
        )

    @Test
    fun `isAvailable returns true when something is listening`() {
        ServerSocket(0).use { server ->
            assertTrue(SharedInstanceProbe.isAvailable(port = server.localPort, timeoutMs = 500))
        }
    }

    @Test
    fun `isAvailable returns false when nothing is listening`() {
        // Bind+close yields a port that's almost certainly free for the
        // duration of the next system call.
        val freePort = ServerSocket(0).use { it.localPort }
        assertFalse(SharedInstanceProbe.isAvailable(port = freePort, timeoutMs = 200))
    }

    @Test
    fun `shouldShareInstance is true when listener exists and preferOwnInstance is false`() {
        ServerSocket(0).use { server ->
            assertTrue(
                SharedInstanceProbe.shouldShareInstance(
                    config = cfg(preferOwn = false),
                    port = server.localPort,
                    timeoutMs = 500,
                ),
            )
        }
    }

    @Test
    fun `shouldShareInstance short-circuits to false when preferOwnInstance is true`() {
        ServerSocket(0).use { server ->
            assertFalse(
                SharedInstanceProbe.shouldShareInstance(
                    config = cfg(preferOwn = true),
                    port = server.localPort,
                    timeoutMs = 500,
                ),
            )
        }
    }

    @Test
    fun `shouldShareInstance is false when no listener and preferOwnInstance is false`() {
        val freePort = ServerSocket(0).use { it.localPort }
        assertFalse(
            SharedInstanceProbe.shouldShareInstance(
                config = cfg(preferOwn = false),
                port = freePort,
                timeoutMs = 200,
            ),
        )
    }
}
