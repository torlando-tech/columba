package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.model.InterfaceConfig
import network.reticulum.interfaces.tcp.TCPClientInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * GitHub #965: a TCP interface configured with a SOCKS5 proxy (Orbot/Tor) must
 * route through it. A `.onion` target cannot be resolved locally, so without the
 * proxy the interface dies on "No address associated with hostname" exactly as
 * the issue logs show.
 *
 * Pre-fix, `NativeInterfaceFactory` dropped `socksProxyEnabled/Host/Port` when
 * building `TCPClientInterface` (and `TCPClientInterface` had no proxy support at
 * all), so the configured proxy never saw a CONNECT.
 */
class TorSocksProxyTest {
    private lateinit var proxy: FakeSocks5Proxy

    @Before
    fun setup() {
        proxy = FakeSocks5Proxy().also { it.start() }
    }

    @After
    fun teardown() {
        proxy.stop()
    }

    @Test
    fun `tcp client with a socks proxy connects through it for an onion target`() {
        val iface =
            NativeInterfaceFactory.createInterface(
                InterfaceConfig.TCPClient(
                    name = "tor",
                    enabled = true,
                    targetHost = ONION_HOST,
                    targetPort = ONION_PORT,
                    socksProxyEnabled = true,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = proxy.port,
                ),
            )
        assertNotNull("factory must build a TCP client", iface)
        val client = iface as TCPClientInterface
        client.start()
        try {
            val target = proxy.awaitConnect(timeoutMs = 10_000)
            assertEquals("the proxy must be asked for the onion host", ONION_HOST, target.host)
            assertEquals("and for the configured port", ONION_PORT, target.port)
        } finally {
            client.stop()
        }
    }

    private data class Target(
        val host: String,
        val port: Int,
    )

    /** Minimal SOCKS5 CONNECT recorder: no-auth greeting, then one CONNECT reply. */
    private class FakeSocks5Proxy {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val connections = LinkedBlockingQueue<Target>()
        private val thread =
            Thread({ acceptLoop() }, "fake-socks5").apply {
                isDaemon = true
            }
        private var live: Socket? = null

        val port: Int get() = server.localPort

        fun start() = thread.start()

        fun stop() {
            runCatching { live?.close() }
            runCatching { server.close() }
        }

        fun awaitConnect(timeoutMs: Long): Target =
            connections.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("the SOCKS5 proxy never received a CONNECT within ${timeoutMs}ms")

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket =
                    runCatching { server.accept() }.getOrNull() ?: return
                live = socket
                runCatching { serveOne(socket) }
            }
        }

        private fun serveOne(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 10_000
                val input = DataInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                // Greeting: VER, NMETHODS, METHODS.
                val ver = input.readUnsignedByte()
                val methodCount = input.readUnsignedByte()
                repeat(methodCount) { input.readUnsignedByte() }
                if (ver != 5) throw IllegalStateException("not SOCKS5: ver=$ver")
                out.write(byteArrayOf(5, 0))
                out.flush()

                // Request: VER, CMD, RSV, ATYP.
                input.readUnsignedByte()
                val cmd = input.readUnsignedByte()
                input.readUnsignedByte()
                val atyp = input.readUnsignedByte()
                if (cmd != 1) throw IllegalStateException("not CONNECT: cmd=$cmd")
                val host =
                    when (atyp) {
                        1 -> ByteArray(4).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
                        4 -> ByteArray(16).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                        else -> throw IllegalStateException("bad atyp=$atyp")
                    }
                val port = input.readUnsignedShort()
                connections.put(Target(host, port))

                // Success reply with a dummy bound address, then keep the pipe open.
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                out.flush()
                runCatching { input.read() }
            }
        }
    }

    private companion object {
        const val ONION_HOST = "ympr5d6kpga2xhfpm5h4uxltvddckarpjae7cq6rnkqp6vy4rj6gziqd.onion"
        const val ONION_PORT = 4245
    }
}
