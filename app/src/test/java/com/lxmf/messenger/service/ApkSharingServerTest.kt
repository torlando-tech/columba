package com.lxmf.messenger.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        server.stop()
        serverScope.cancel()
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
}
