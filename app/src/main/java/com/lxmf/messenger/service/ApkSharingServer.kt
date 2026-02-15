package com.lxmf.messenger.service

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight HTTP server that serves the Columba APK file for local sharing.
 *
 * When started, it binds to a random available port and serves the APK file
 * at the path `/columba.apk`. The server runs on the caller's coroutine scope
 * and can be stopped by calling [stop].
 *
 * This is designed for sharing the APK over a local WiFi network. The receiver
 * scans a QR code containing the download URL, which opens in their browser
 * and downloads the APK.
 */
class ApkSharingServer {
    companion object {
        private const val TAG = "ApkSharingServer"
        private const val CLIENT_TIMEOUT_MS = 30_000

        /**
         * Get the device's local WiFi IP address.
         * Returns null if no suitable address is found.
         */
        fun getLocalIpAddress(): String? =
            try {
                findWifiAddress() ?: findAnyAddress()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting local IP address", e)
                null
            }

        private fun findWifiAddress(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (isUsableInterface(iface) && isWifiInterface(iface)) {
                    val addr = firstIpv4Address(iface)
                    if (addr != null) return addr
                }
            }
            return null
        }

        private fun findAnyAddress(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!isUsableInterface(iface)) continue
                val addr = firstIpv4Address(iface)
                if (addr != null) return addr
            }
            return null
        }

        private fun isUsableInterface(iface: NetworkInterface): Boolean = !iface.isLoopback && iface.isUp

        private fun isWifiInterface(iface: NetworkInterface): Boolean =
            iface.name.startsWith("wlan") ||
                iface.name.startsWith("ap") ||
                iface.name.startsWith("swlan")

        private fun firstIpv4Address(iface: NetworkInterface): String? {
            for (address in iface.inetAddresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
            return null
        }
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * Deferred that completes with the bound port once the server socket is ready,
     * or with 0 if binding failed. Callers obtain this via [prepareStart] to guarantee
     * they await on the same instance that [start] completes.
     */
    @Volatile
    private var portReady = CompletableDeferred<Int>()

    val port: Int
        get() = serverSocket?.localPort ?: 0

    /**
     * Prepare a fresh readiness signal **and** launch [start] in one atomic step,
     * returning the [CompletableDeferred] the caller should await. This eliminates
     * the race where [awaitPort] could observe a stale or reassigned deferred.
     */
    fun prepareStart(): CompletableDeferred<Int> {
        val deferred = CompletableDeferred<Int>()
        portReady = deferred
        return deferred
    }

    /**
     * Start the HTTP server and serve the given APK file.
     * This suspends and runs the accept loop until [stop] is called.
     *
     * Callers must call [prepareStart] before launching this method to obtain the
     * [CompletableDeferred] that will be completed with the bound port.
     *
     * @param apkFile The APK file to serve
     */
    suspend fun start(apkFile: File) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Server already running")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                serverSocket =
                    ServerSocket(0).also {
                        it.reuseAddress = true
                    }
                val boundPort = serverSocket?.localPort ?: 0
                Log.i(TAG, "APK sharing server started on port $boundPort")
                portReady.complete(boundPort)

                acceptLoop(apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                portReady.complete(0)
            } finally {
                isRunning.set(false)
                Log.i(TAG, "APK sharing server stopped")
            }
        }
    }

    private fun acceptLoop(apkFile: File) {
        while (isRunning.get()) {
            val clientSocket: Socket
            try {
                clientSocket = serverSocket?.accept() ?: return
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Socket error while accepting", e)
                }
                return
            }

            // Handle each client on its own thread so the accept loop is not blocked
            Thread {
                handleClient(clientSocket, apkFile)
            }.start()
        }
    }

    /**
     * Handle a single HTTP client connection.
     * Serves the APK file for GET /columba.apk,
     * and returns a simple HTML download page for the root path.
     */
    private fun handleClient(
        clientSocket: Socket,
        apkFile: File,
    ) {
        try {
            clientSocket.soTimeout = CLIENT_TIMEOUT_MS
            clientSocket.use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val output = BufferedOutputStream(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return
                Log.d(TAG, "Request: $requestLine")

                // Consume remaining headers
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    line = reader.readLine()
                }

                when {
                    requestLine.startsWith("GET /columba.apk") -> {
                        serveApkFile(output, apkFile)
                    }
                    requestLine.startsWith("GET / ") || requestLine.startsWith("GET / HTTP") -> {
                        serveDownloadPage(output)
                    }
                    else -> {
                        serveNotFound(output)
                    }
                }

                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun serveApkFile(
        output: BufferedOutputStream,
        apkFile: File,
    ) {
        if (!apkFile.exists()) {
            serveNotFound(output)
            return
        }

        val fileSize = apkFile.length()
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Disposition: attachment; filename=\"columba.apk\"\r\n")
                append("Content-Length: $fileSize\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

        output.write(headers.toByteArray())

        FileInputStream(apkFile).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
        }

        Log.i(TAG, "Served APK file ($fileSize bytes)")
    }

    private fun serveDownloadPage(output: BufferedOutputStream) {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Download Columba</title>
                <style>
                    body {
                        font-family: -apple-system, system-ui, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        margin: 0;
                        background: #1a1a2e;
                        color: #eee;
                    }
                    .card {
                        background: #16213e;
                        border-radius: 16px;
                        padding: 32px;
                        text-align: center;
                        max-width: 320px;
                    }
                    h1 { font-size: 24px; margin-bottom: 8px; }
                    p { color: #aaa; margin-bottom: 24px; }
                    a.btn {
                        display: inline-block;
                        background: #6c63ff;
                        color: white;
                        text-decoration: none;
                        padding: 14px 32px;
                        border-radius: 8px;
                        font-size: 18px;
                        font-weight: 600;
                    }
                    .note {
                        font-size: 12px;
                        color: #777;
                        margin-top: 20px;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>Columba</h1>
                    <p>Tap the button below to download the Columba messenger APK.</p>
                    <a class="btn" href="/columba.apk">Download APK</a>
                    <p class="note">After downloading, open the file to install.<br>
                    You may need to enable "Install from unknown sources".</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val body = html.toByteArray()
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

        output.write(headers.toByteArray())
        output.write(body)
    }

    private fun serveNotFound(output: BufferedOutputStream) {
        val body = "Not Found"
        val headers =
            buildString {
                append("HTTP/1.1 404 Not Found\r\n")
                append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.length}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

        output.write(headers.toByteArray())
        output.write(body.toByteArray())
    }

    /**
     * Stop the server. Safe to call even if not running.
     */
    fun stop() {
        if (isRunning.getAndSet(false)) {
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
            serverSocket = null
            Log.i(TAG, "Server stop requested")
        }
    }
}
