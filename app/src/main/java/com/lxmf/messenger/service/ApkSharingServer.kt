package com.lxmf.messenger.service

import android.util.Log
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

        /**
         * Get the device's local WiFi IP address.
         * Returns null if no suitable address is found.
         */
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (networkInterface in interfaces) {
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue

                    val isWifi = networkInterface.name.startsWith("wlan") ||
                        networkInterface.name.startsWith("ap") ||
                        networkInterface.name.startsWith("swlan")

                    for (address in networkInterface.inetAddresses) {
                        if (address.isLoopbackAddress) continue
                        if (address !is Inet4Address) continue

                        val hostAddress = address.hostAddress ?: continue
                        if (isWifi) return hostAddress
                    }
                }

                // Fallback: return any non-loopback IPv4 address
                val fallbackInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (networkInterface in fallbackInterfaces) {
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    for (address in networkInterface.inetAddresses) {
                        if (address.isLoopbackAddress) continue
                        if (address !is Inet4Address) continue
                        return address.hostAddress
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting local IP address", e)
            }
            return null
        }
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    val port: Int
        get() = serverSocket?.localPort ?: 0

    /**
     * Start the HTTP server and serve the given APK file.
     * This suspends and runs the accept loop until [stop] is called.
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
                serverSocket = ServerSocket(0).also {
                    it.reuseAddress = true
                }
                Log.i(TAG, "APK sharing server started on port ${serverSocket?.localPort}")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleClient(clientSocket, apkFile)
                    } catch (e: SocketException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Socket error while accepting", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            } finally {
                isRunning.set(false)
                Log.i(TAG, "APK sharing server stopped")
            }
        }
    }

    /**
     * Handle a single HTTP client connection.
     * Serves the APK file for GET /columba.apk,
     * and returns a simple HTML download page for the root path.
     */
    private fun handleClient(clientSocket: Socket, apkFile: File) {
        try {
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

    private fun serveApkFile(output: BufferedOutputStream, apkFile: File) {
        if (!apkFile.exists()) {
            serveNotFound(output)
            return
        }

        val fileSize = apkFile.length()
        val headers = buildString {
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
        val html = """
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
        val headers = buildString {
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
        val headers = buildString {
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
