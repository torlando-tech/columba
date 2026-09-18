package network.columba.app.nomadnet

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based, LRU-capped cache for NomadNet page images (`/media/` fetches).
 *
 * Upstream NomadNet caches images at `storage/cache/images/<url_hash>` keyed
 * by URL (Browser.py `image_cache_path`); revisit cost is then zero requests.
 * Mobile adds a hard total-size cap (default 64 MB) with LRU eviction — the
 * cache dir is `context.cacheDir`, so the OS can reclaim it wholesale, but the
 * cap keeps a single media-heavy session from crowding out the page cache.
 *
 * Files are named `<sha256(resolvedKey)>.webp`. The magic-number sniff on
 * [put] enforces upstream's WebP-only rule for network-sourced media:
 * "For the sake of bandwidth efficiency, images sent over the network must be
 * in WebP format" (NomadNet guide, commit 7bd3a3a).
 */
@Singleton
class NomadNetImageCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        companion object {
            private const val TAG = "NomadNetImageCache"
            private const val CACHE_DIR_NAME = "nomadnet_images"
            const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

            /** WebP files start `RIFF????WEBP`. */
            fun isWebp(file: File): Boolean {
                if (!file.isFile || file.length() < 12) return false
                val header = ByteArray(12)
                return runCatching {
                    file.inputStream().use { it.read(header) }
                }.getOrDefault(0) == 12 &&
                    String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                    String(header, 8, 4, Charsets.ISO_8859_1) == "WEBP"
            }
        }

        private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)

        /**
         * Resolve a cached image. Returns the file if present (bumping its
         * mtime for LRU), null on miss.
         */
        @Synchronized
        fun get(resolvedKey: String): File? {
            val file = File(cacheDir, fileNameFor(resolvedKey))
            if (!file.isFile) return null
            // mtime touch = LRU recency signal.
            file.setLastModified(System.currentTimeMillis())
            return file
        }

        /**
         * Move a freshly fetched media file into the cache under
         * [resolvedKey], rejecting non-WebP payloads (upstream rule). Returns
         * the cached file, or null when rejected — [source] is deleted either
         * way.
         */
        @Synchronized
        fun put(
            resolvedKey: String,
            source: File,
        ): File? {
            if (!isWebp(source)) {
                Log.i(TAG, "Rejected non-WebP media for $resolvedKey (upstream webp-only rule)")
                source.delete()
                return null
            }
            cacheDir.mkdirs()
            val target = File(cacheDir, fileNameFor(resolvedKey))
            val moved =
                runCatching {
                    if (target.exists()) target.delete()
                    if (!source.renameTo(target)) {
                        // Cross-device (backend process staging dir may be a
                        // different mount): fall back to copy.
                        source.copyTo(target, overwrite = true)
                        source.delete()
                    }
                }.onFailure { Log.w(TAG, "Failed to cache image $resolvedKey", it) }.isSuccess
            if (!moved) return null
            target.setLastModified(System.currentTimeMillis())
            evictIfNeeded()
            return target
        }

        /** Delete a cached image (force-reload path, upstream Ctrl+X). */
        @Synchronized
        fun remove(resolvedKey: String) {
            File(cacheDir, fileNameFor(resolvedKey)).delete()
        }

        @Synchronized
        fun clear() {
            cacheDir.listFiles()?.forEach { it.delete() }
        }

        @Synchronized
        fun totalBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

        /** LRU eviction: drop oldest-touched files until under the cap. */
        private fun evictIfNeeded(maxBytes: Long = DEFAULT_MAX_BYTES) {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in files) {
                total -= f.length()
                f.delete()
                if (total <= maxBytes) break
            }
            Log.d(TAG, "Image cache evicted to $total bytes")
        }

        private fun fileNameFor(resolvedKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(resolvedKey.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + ".webp"
        }
    }
