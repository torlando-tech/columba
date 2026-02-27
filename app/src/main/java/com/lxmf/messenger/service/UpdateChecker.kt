package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class AppUpdateResult {
    data object Idle : AppUpdateResult()

    data object Checking : AppUpdateResult()

    data class UpToDate(
        val currentVersion: String,
    ) : AppUpdateResult()

    data class UpdateAvailable(
        val currentVersion: String,
        val tagName: String,
        val htmlUrl: String,
        val isPrerelease: Boolean,
    ) : AppUpdateResult()

    data class Error(
        val message: String,
    ) : AppUpdateResult()
}

@Singleton
class UpdateChecker
    @Inject
    constructor() {
        companion object {
            private const val TAG = "UpdateChecker"
            private const val OWNER = "torlando-tech"
            private const val REPO = "columba"
            private const val TIMEOUT_MS = 10_000
        }

        suspend fun check(includePrerelease: Boolean): AppUpdateResult =
            withContext(Dispatchers.IO) {
                try {
                    val apiUrl =
                        if (includePrerelease) {
                            "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=1"
                        } else {
                            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
                        }

                    val json = fetchJson(apiUrl)

                    // When includePrerelease, API returns an array — unwrap first element
                    val releaseJson =
                        if (includePrerelease) {
                            unwrapFirstArrayElement(json) ?: return@withContext AppUpdateResult.Error("No releases found")
                        } else {
                            json
                        }

                    val tagName = parseStringField(releaseJson, "tag_name") ?: return@withContext AppUpdateResult.Error("Could not parse release info")
                    val htmlUrl = parseStringField(releaseJson, "html_url") ?: return@withContext AppUpdateResult.Error("Could not parse release info")
                    val isPrerelease = parseBooleanField(releaseJson, "prerelease") ?: false

                    val currentVersion = parseVersionTriple(BuildConfig.VERSION_NAME)
                    val remoteVersion = parseVersionTriple(tagName.removePrefix("v"))

                    if (currentVersion == null || remoteVersion == null) {
                        return@withContext AppUpdateResult.Error("Could not parse version numbers")
                    }

                    Log.d(TAG, "Current: $currentVersion, Remote: $remoteVersion (tag=$tagName)")

                    if (remoteVersion > currentVersion) {
                        AppUpdateResult.UpdateAvailable(
                            currentVersion = formatVersionTriple(currentVersion),
                            tagName = tagName,
                            htmlUrl = htmlUrl,
                            isPrerelease = isPrerelease,
                        )
                    } else {
                        AppUpdateResult.UpToDate(currentVersion = formatVersionTriple(currentVersion))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed", e)
                    AppUpdateResult.Error(e.message ?: "Network error")
                }
            }

        private fun fetchJson(apiUrl: String): String {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Columba/${BuildConfig.VERSION_NAME}")
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $responseCode")
                }
                return connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        }

        /**
         * Unwraps the first element of a JSON array string, e.g. "[{...}, ...]" -> "{...}".
         * Handles simple cases without a full JSON parser.
         */
        private fun unwrapFirstArrayElement(json: String): String? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return null
            val start = trimmed.indexOf('{')
            var result: String? = null
            if (start >= 0) {
                var depth = 0
                var inString = false
                var escape = false
                for (i in start until trimmed.length) {
                    val c = trimmed[i]
                    when {
                        escape -> escape = false
                        c == '\\' && inString -> escape = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> {
                            depth--
                            if (depth == 0) {
                                result = trimmed.substring(start, i + 1)
                                break
                            }
                        }
                    }
                }
            }
            return result
        }

        private fun parseStringField(
            json: String,
            field: String,
        ): String? {
            val pattern = Regex(""""$field"\s*:\s*"([^"]+)"""")
            return pattern.find(json)?.groupValues?.get(1)
        }

        private fun parseBooleanField(
            json: String,
            field: String,
        ): Boolean? {
            val pattern = Regex(""""$field"\s*:\s*(true|false)""")
            return pattern
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toBooleanStrictOrNull()
        }

        /**
         * Extracts X.Y.Z triple from a version string like "1.2.3", "1.2.3-beta", "1.2.3.0045-dev".
         * Returns null if not parseable.
         */
        private fun parseVersionTriple(version: String): Triple<Int, Int, Int>? {
            val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(version) ?: return null
            val (major, minor, patch) = match.destructured
            return Triple(major.toInt(), minor.toInt(), patch.toInt())
        }

        private fun formatVersionTriple(v: Triple<Int, Int, Int>): String = "${v.first}.${v.second}.${v.third}"

        private operator fun Triple<Int, Int, Int>.compareTo(other: Triple<Int, Int, Int>): Int =
            compareValuesBy(this, other, {
                it.first
            }, { it.second }, { it.third })
    }
