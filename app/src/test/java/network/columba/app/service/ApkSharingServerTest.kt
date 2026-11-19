package network.columba.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Tests for ApkSharingServer.
 *
 * This test does NOT use Robolectric — the server is pure Java networking
 * (ServerSocket, HttpURLConnection) with no Android framework dependencies
 * beyond android.util.Log (which returns defaults via isReturnDefaultValues).
 * Running outside Robolectric avoids polluting the shared Robolectric
 * environment with leaked server threads/sockets.
 */
class ApkSharingServerTest {
    private lateinit var server: ApkSharingServer
    private lateinit var apkFile: File
    private lateinit var serverScope: CoroutineScope

    @Before
    fun setup() {
        server = ApkSharingServer()
        apkFile =
            File.createTempFile("test", ".apk").apply {
                writeText("fake-apk-content-for-testing")
                deleteOnExit()
            }
        serverScope = CoroutineScope(Dispatchers.IO)
    }

    @After
    fun teardown() {
        // Cancel the scope FIRST to prevent any pending (not-yet-started) coroutines
        // from executing. This fixes a race in "start rejects duplicate launch" where
        // the second launch's coroutine could start AFTER stop() sets isRunning=false,
        // causing it to create a new server that nobody stops — hanging the JVM forever.
        serverScope.cancel()
        server.stop()
        // Wait for running coroutines to finish (with a safety timeout to prevent CI hangs).
        runBlocking {
            withTimeout(5_000) {
                serverScope.coroutineContext.job.children
                    .forEach { it.join() }
            }
        }
        apkFile.delete()
    }

    /** Starts the server and returns (port, serverJob). */
    private suspend fun startAndAwaitPort(): Pair<Int, Job> {
        val deferred = server.prepareStart()
        val job = serverScope.launch { server.start(apkFile) }
        val port = deferred.await()
        return port to job
    }

    @Test
    fun `server binds to a port and reports it via prepareStart`() =
        runTest {
            val (port) = startAndAwaitPort()

            assertTrue("Port should be positive", port > 0)
            assertEquals(port, server.port)
        }

    @Test
    fun `stop makes port return 0`() =
        runTest {
            startAndAwaitPort()

            server.stop()
            assertEquals(0, server.port)
        }

    @Test
    fun `stop is safe to call when not running`() {
        server.stop()
        server.stop()
    }

    @Test
    fun `start rejects duplicate launch`() =
        runTest {
            val (port) = startAndAwaitPort()
            assertTrue(port > 0)

            // Second start is a no-op because isRunning is already true
            server.prepareStart()
            serverScope.launch { server.start(apkFile) }
            assertEquals(port, server.port)
        }

    @Test
    fun `GET root returns download page with 200`() =
        runTest {
            val (port) = startAndAwaitPort()

            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            try {
                assertEquals(200, conn.responseCode)
                assertEquals("text/html; charset=utf-8", conn.contentType)
                val body = conn.inputStream.bufferedReader().readText()
                assertTrue("Page should contain download link", body.contains("/columba.apk"))
                assertTrue("Page should contain Columba title", body.contains("Columba"))
            } finally {
                conn.disconnect()
            }
        }

    @Test
    fun `GET columba apk returns the file with correct headers`() =
        runTest {
            server.downloadFileName = "columba-1.2.3.apk"
            val (port) = startAndAwaitPort()

            val conn = URL("http://127.0.0.1:$port/columba.apk").openConnection() as HttpURLConnection
            try {
                assertEquals(200, conn.responseCode)
                assertEquals("application/vnd.android.package-archive", conn.contentType)
                assertEquals(apkFile.length().toInt(), conn.contentLength)
                val disposition = conn.getHeaderField("Content-Disposition")
                assertTrue(
                    "Content-Disposition should use downloadFileName",
                    disposition.contains("columba-1.2.3.apk"),
                )
                val body = conn.inputStream.bufferedReader().readText()
                assertEquals(apkFile.readText(), body)
            } finally {
                conn.disconnect()
            }
        }

    @Test
    fun `GET unknown path returns 404`() =
        runTest {
            val (port) = startAndAwaitPort()

            val conn = URL("http://127.0.0.1:$port/nope").openConnection() as HttpURLConnection
            try {
                assertEquals(404, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }

    @Test
    fun `serves APK to multiple sequential clients`() =
        runTest {
            val (port) = startAndAwaitPort()

            repeat(3) {
                val conn = URL("http://127.0.0.1:$port/columba.apk").openConnection() as HttpURLConnection
                try {
                    assertEquals(200, conn.responseCode)
                    val body = conn.inputStream.bufferedReader().readText()
                    assertEquals(apkFile.readText(), body)
                } finally {
                    conn.disconnect()
                }
            }
        }

    @Test
    fun `connections beyond the in-flight cap are closed instead of queued`() =
        runTest {
            // A fixed thread pool bounds workers, not its queue. With a cap of two,
            // the third and fourth sockets must be closed by the accept loop rather
            // than queued behind the busy workers.
            val capped = ApkSharingServer(maxInFlightConnections = 2)
            val portDeferred = capped.prepareStart()
            val job = serverScope.launch { capped.start(apkFile) }
            val sockets = mutableListOf<Socket>()
            try {
                val port = portDeferred.await()

                repeat(4) { sockets += Socket("127.0.0.1", port) }

                sockets.drop(2).forEach { socket ->
                    assertTrue(
                        "connection beyond the cap was queued instead of closed",
                        awaitEof(socket),
                    )
                }
                assertEquals(
                    "a connection within the cap must stay open",
                    READ_TIMED_OUT,
                    readWithin(sockets.first(), 500),
                )
            } finally {
                sockets.forEach { runCatching { it.close() } }
                capped.stop()
                job.cancel()
            }
        }

    @Test
    fun `returns 404 when APK file is deleted after server start`() =
        runTest {
            val (port) = startAndAwaitPort()

            apkFile.delete()

            val conn = URL("http://127.0.0.1:$port/columba.apk").openConnection() as HttpURLConnection
            try {
                assertEquals(404, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }

    @Test
    fun `server can be restarted after stopping`() =
        runTest {
            val (port1, job1) = startAndAwaitPort()
            assertTrue(port1 > 0)

            server.stop()
            // Wait for the server coroutine to actually finish
            job1.join()

            val (port2) = startAndAwaitPort()
            assertTrue("Restarted server should bind to a port", port2 > 0)
        }

    @Test
    fun `getLocalIpAddress returns null or valid IP`() {
        val ip = ApkSharingServer.getLocalIpAddress()
        if (ip != null) {
            assertTrue(
                "IP should match IPv4 pattern",
                ip.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")),
            )
            assertFalse("Should not be loopback", ip.startsWith("127."))
        }
    }

    @Test
    fun `port is 0 before server starts`() {
        assertEquals(0, server.port)
    }

    @Test
    fun `download page contains install instructions`() =
        runTest {
            val (port) = startAndAwaitPort()

            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            try {
                val body = conn.inputStream.bufferedReader().readText()
                assertTrue(
                    "Page should mention unknown sources",
                    body.contains("unknown sources"),
                )
            } finally {
                conn.disconnect()
            }
        }

    private companion object {
        /** Returned by [readWithin] when the socket stayed open without sending. */
        const val READ_TIMED_OUT = 0
    }

    /**
     * Read one byte from [socket], capping the wait at [timeoutMs].
     * Returns -1 on EOF/close, [READ_TIMED_OUT] when the connection stayed open
     * without sending anything, and the byte value otherwise.
     */
    private fun readWithin(
        socket: Socket,
        timeoutMs: Int,
    ): Int =
        try {
            socket.soTimeout = timeoutMs
            socket.getInputStream().read()
        } catch (_: SocketTimeoutException) {
            READ_TIMED_OUT
        } catch (_: IOException) {
            -1
        }

    /** Polls until [socket] observes EOF (the server closed it) or the deadline passes. */
    private fun awaitEof(
        socket: Socket,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (readWithin(socket, 200)) {
                -1 -> return true
                READ_TIMED_OUT -> continue
                else -> return false
            }
        }
        return false
    }
}
