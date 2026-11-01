package network.columba.app.nomadnet

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based cache for NomadNet pages, matching NomadNet's reference caching behavior.
 *
 * Pages are cached in `context.cacheDir/nomadnet_pages/` with filenames encoding both
 * the content key (SHA-256 of "nodeHash:path") and the expiration timestamp. This allows
 * expiration checks without reading file contents and leverages Android's cache directory
 * (reclaimable under storage pressure).
 *
 * TTL is determined by the page's `#!c=N` directive:
 * - `null` (no directive) → DEFAULT_CACHE_SECONDS (12 hours)
 * - `0` → do not cache
 * - positive N → cache for N seconds
 */
@Singleton
class NomadNetPageCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        companion object {
            private const val TAG = "NomadNetPageCache"
            const val DEFAULT_CACHE_SECONDS = 12 * 60 * 60 // 12 hours
            private const val CACHE_DIR_NAME = "nomadnet_pages"
        }

        private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)

        init {
            // Clean expired entries off the main thread to avoid blocking UI
            Thread { cleanExpired() }.start()
        }

        /**
         * Retrieve a cached page by node hash and path.
         * Returns the raw markup string if a valid (non-expired) cache entry exists, null otherwise.
         */
        fun get(
            nodeHash: String,
            path: String,
        ): String? {
            val dir = cacheDir
            if (!dir.exists()) return null

            val key = cacheKey(nodeHash, path)
            val matchingFile =
                dir.listFiles()?.firstOrNull { it.name.startsWith("${key}_") }
                    ?: return null

            val expiresMs = extractExpiryMs(matchingFile.name)
            return if (expiresMs == null || System.currentTimeMillis() > expiresMs) {
                matchingFile.delete()
                null
            } else {
                try {
                    matchingFile.readText()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read cache file", e)
                    matchingFile.delete()
                    null
                }
            }
        }

        /**
         * Store a page in the cache.
         *
         * @param cacheSeconds TTL from the page's `#!c=N` directive.
         *   - `null` → use DEFAULT_CACHE_SECONDS
         *   - `0` → do not cache (returns immediately)
         *   - positive → cache for that many seconds
         */
        fun put(
            nodeHash: String,
            path: String,
            content: String,
            cacheSeconds: Int?,
        ) {
            val ttl = cacheSeconds ?: DEFAULT_CACHE_SECONDS
            if (ttl <= 0) return

            val key = cacheKey(nodeHash, path)

            try {
                cacheDir.mkdirs()

                // Remove any existing entries for this key
                removeByKey(key)

                val expiresMs = System.currentTimeMillis() + ttl * 1000L
                val file = File(cacheDir, "${key}_$expiresMs")
                file.writeText(content)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write cache file", e)
            }
        }

        /**
         * The last page the browser displayed, held in memory only.
         *
         * Reopening the NomadNet tab restores the session's page, and pages
         * marked `#!c=0` (dynamic; the majority of executable NomadNet pages)
         * are deliberately absent from the file cache - without this store,
         * every tab tap would re-fetch the page the user is already looking
         * at. It is consulted ONLY for a session restore, so ordinary
         * navigation (link taps, deep links, URL bar, pull-to-refresh) still
         * honours the site's freshness directive. Memory-only by design: a
         * process restart is a genuine cold start and fetches fresh, and no
         * `#!c=0` content is ever written to disk.
         */
        private data class SessionEntry(
            val nodeHash: String,
            val path: String,
            val content: String,
        )

        @Volatile
        private var sessionEntry: SessionEntry? = null

        /** Record the page the browser just displayed, keyed by full path. */
        fun putSession(
            nodeHash: String,
            path: String,
            content: String,
        ) {
            sessionEntry = SessionEntry(nodeHash, path, content)
        }

        /** The displayed page for exactly this node and full path, if any. */
        fun getSession(
            nodeHash: String,
            path: String,
        ): String? = sessionEntry?.takeIf { it.nodeHash == nodeHash && it.path == path }?.content

        /**
         * Remove a specific cache entry.
         */
        fun remove(
            nodeHash: String,
            path: String,
        ) {
            removeByKey(cacheKey(nodeHash, path))
        }

        /**
         * Delete all expired cache entries. Called once on initialization.
         */
        fun cleanExpired() {
            val dir = cacheDir
            if (!dir.exists()) return

            val now = System.currentTimeMillis()
            var removed = 0
            dir.listFiles()?.forEach { file ->
                val expiresMs = extractExpiryMs(file.name)
                if (expiresMs == null || now > expiresMs) {
                    file.delete()
                    removed++
                }
            }
            if (removed > 0) {
                Log.d(TAG, "Cleaned $removed expired cache entries")
            }
        }

        private fun removeByKey(key: String) {
            val prefix = "${key}_"
            cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }

        private fun extractExpiryMs(fileName: String): Long? {
            val underscoreIdx = fileName.lastIndexOf('_')
            if (underscoreIdx < 0) return null
            return fileName.substring(underscoreIdx + 1).toLongOrNull()
        }

        private fun cacheKey(
            nodeHash: String,
            path: String,
        ): String {
            val input = "$nodeHash:$path"
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
