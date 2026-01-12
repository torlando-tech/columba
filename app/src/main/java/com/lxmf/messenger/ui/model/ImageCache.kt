package com.lxmf.messenger.ui.model

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/**
 * In-memory LRU cache for decoded message images.
 *
 * This cache prevents expensive image decoding from happening on the main thread
 * during LazyColumn composition. Images are decoded asynchronously on IO threads
 * and stored here, then retrieved synchronously during composition.
 *
 * Thread-safe: LruCache is synchronized internally.
 */
object ImageCache {
    /**
     * Maximum number of images to keep in cache.
     * At ~100KB per typical message image, this is roughly 5MB of memory.
     */
    private const val MAX_CACHE_SIZE = 50

    private var cache = LruCache<String, ImageBitmap>(MAX_CACHE_SIZE)

    /**
     * Get a cached image by message ID.
     * @param messageId The unique message identifier (hash)
     * @return The cached ImageBitmap, or null if not cached
     */
    fun get(messageId: String): ImageBitmap? = cache.get(messageId)

    /**
     * Store an image in the cache.
     * @param messageId The unique message identifier (hash)
     * @param image The decoded ImageBitmap to cache
     */
    fun put(
        messageId: String,
        image: ImageBitmap,
    ) {
        cache.put(messageId, image)
    }

    /**
     * Check if an image is already cached.
     * Uses snapshot() to avoid affecting hit/miss statistics.
     *
     * @param messageId The unique message identifier (hash)
     * @return true if the image is in cache
     */
    fun contains(messageId: String): Boolean = cache.snapshot()?.containsKey(messageId) == true

    /**
     * Clear all cached images.
     * Call this when memory pressure is high or when the conversation changes.
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Get current cache statistics for debugging.
     * @return Pair of (hit count, miss count)
     */
    fun getStats(): Pair<Int, Int> = Pair(cache.hitCount(), cache.missCount())

    /**
     * Get the current number of cached items.
     */
    fun size(): Int = cache.size()

    /**
     * Reset cache completely, including hit/miss statistics.
     * Only for testing - creates a fresh cache instance.
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        cache = LruCache(MAX_CACHE_SIZE)
    }
}
