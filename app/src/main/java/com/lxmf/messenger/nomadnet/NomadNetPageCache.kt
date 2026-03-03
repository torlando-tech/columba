package com.lxmf.messenger.nomadnet

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
            cleanExpired()
        }

        /**
         * Retrieve a cached page by node hash and path.
         * Returns the raw markup string if a valid (non-expired) cache entry exists, null otherwise.
         */
        fun get(
            nodeHash: String,
            path: String,
        ): String? {
            val key = cacheKey(nodeHash, path)
            val dir = cacheDir
            if (!dir.exists()) return null

            val prefix = "${key}_"
            val matchingFile =
                dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) }
                    ?: return null

            val expiresMs = extractExpiryMs(matchingFile.name) ?: return null
            if (System.currentTimeMillis() > expiresMs) {
                matchingFile.delete()
                return null
            }

            return try {
                matchingFile.readText()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cache file", e)
                matchingFile.delete()
                null
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
