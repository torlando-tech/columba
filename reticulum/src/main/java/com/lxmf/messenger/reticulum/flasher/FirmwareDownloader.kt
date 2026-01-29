package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads RNode firmware from GitHub releases.
 *
 * Firmware is hosted at: https://github.com/markqvist/RNode_Firmware/releases
 */
class FirmwareDownloader {
    companion object {
        private const val TAG = "Columba:FirmwareDownload"
        private const val GITHUB_API_BASE = "https://api.github.com/repos/markqvist/RNode_Firmware"
        private const val GITHUB_RELEASES = "$GITHUB_API_BASE/releases"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 60000

        // User agent for GitHub API
        private const val USER_AGENT = "Columba-RNode-Flasher/1.0"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Progress callback for downloads.
     */
    interface DownloadCallback {
        fun onProgress(
            bytesDownloaded: Long,
            totalBytes: Long,
        )

        fun onComplete(data: ByteArray)

        fun onError(error: String)
    }

    /**
     * Get available firmware releases from GitHub.
     */
    suspend fun getAvailableReleases(): List<GitHubRelease>? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_RELEASES)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "GitHub API returned ${connection.responseCode}")
                    return@withContext null
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<List<GitHubRelease>>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch releases", e)
                null
            }
        }

    /**
     * Get the latest release.
     */
    suspend fun getLatestRelease(): GitHubRelease? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$GITHUB_RELEASES/latest")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "GitHub API returned ${connection.responseCode}")
                    return@withContext null
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<GitHubRelease>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch latest release", e)
                null
            }
        }

    /**
     * Find firmware asset for a specific board and frequency band.
     *
     * Some firmware files are "unified" and support multiple frequency bands
     * (configured at runtime), so they don't have a band suffix in the filename.
     * This function first tries to find a band-specific file, then falls back
     * to a unified firmware file.
     */
    fun findFirmwareAsset(
        release: GitHubRelease,
        board: RNodeBoard,
        frequencyBand: FrequencyBand,
    ): GitHubAsset? {
        val prefix = board.firmwarePrefix
        val bandSuffix = frequencyBand.modelSuffix

        // First, try to find a band-specific firmware file
        val bandSpecific =
            release.assets.find { asset ->
                val name = asset.name.lowercase()
                name.startsWith(prefix.lowercase()) &&
                    bandSuffix.isNotEmpty() &&
                    name.contains(bandSuffix.lowercase()) &&
                    name.endsWith(".zip")
            }

        if (bandSpecific != null) {
            return bandSpecific
        }

        // Fall back to unified firmware (no band suffix)
        // Match exactly: {prefix}.zip (not {prefix}_something.zip)
        return release.assets.find { asset ->
            val name = asset.name.lowercase()
            name == "${prefix.lowercase()}.zip"
        }
    }

    /**
     * Download a firmware asset.
     */
    suspend fun downloadFirmware(
        asset: GitHubAsset,
        callback: DownloadCallback,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading firmware: ${asset.name} (${asset.size} bytes)")

                val url = URL(asset.browserDownloadUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed: HTTP ${connection.responseCode}")
                    return@withContext null
                }

                val totalBytes = connection.contentLengthLong
                val data = downloadWithProgress(connection.inputStream, totalBytes, callback)

                if (data != null) {
                    Log.d(TAG, "Download complete: ${data.size} bytes")
                    callback.onComplete(data)
                }

                data
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                callback.onError("Download failed: ${e.message}")
                null
            }
        }

    /**
     * Download firmware directly from URL.
     */
    suspend fun downloadFromUrl(
        downloadUrl: String,
        callback: DownloadCallback,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading from: $downloadUrl")

                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed: HTTP ${connection.responseCode}")
                    return@withContext null
                }

                val totalBytes = connection.contentLengthLong
                val data = downloadWithProgress(connection.inputStream, totalBytes, callback)

                if (data != null) {
                    Log.d(TAG, "Download complete: ${data.size} bytes")
                    callback.onComplete(data)
                }

                data
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                callback.onError("Download failed: ${e.message}")
                null
            }
        }

    private fun downloadWithProgress(
        inputStream: InputStream,
        totalBytes: Long,
        callback: DownloadCallback,
    ): ByteArray? {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        var bytesRead: Long = 0

        inputStream.use { input ->
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                output.write(buffer, 0, count)
                bytesRead += count
                callback.onProgress(bytesRead, totalBytes)
            }
        }

        return output.toByteArray()
    }
}

/**
 * GitHub Release API response.
 */
@Serializable
data class GitHubRelease(
    val id: Long,
    @kotlinx.serialization.SerialName("tag_name")
    val tagName: String,
    val name: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String,
    @kotlinx.serialization.SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
    @kotlinx.serialization.SerialName("html_url")
    val htmlUrl: String,
) {
    /**
     * Extract version number from tag name.
     */
    val version: String
        get() = tagName.removePrefix("v").removePrefix("V")
}

/**
 * GitHub Asset API response.
 */
@Serializable
data class GitHubAsset(
    val id: Long,
    val name: String,
    val size: Long,
    @kotlinx.serialization.SerialName("download_count")
    val downloadCount: Int = 0,
    @kotlinx.serialization.SerialName("browser_download_url")
    val browserDownloadUrl: String,
    @kotlinx.serialization.SerialName("content_type")
    val contentType: String? = null,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")
    val updatedAt: String,
)
