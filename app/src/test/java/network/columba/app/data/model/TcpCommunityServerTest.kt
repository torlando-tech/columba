package network.columba.app.data.model

import org.junit.Assert.assertTrue
import org.junit.Test

class TcpCommunityServerTest {

    @Test
    fun `randomServer returns a known server`() {
        repeat(100) {
            val random = TcpCommunityServers.randomServer()
            assertTrue(
                "randomServer() returned an unknown server: ${random.name}",
                TcpCommunityServers.servers.any { it.name == random.name },
            )
        }
    }
}
