package com.lxmf.messenger.nomadnet

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for [NomadNetPageCache] — file-based page cache with TTL expiration.
 *
 * Uses Robolectric for real file I/O via Android's Context.cacheDir.
 */
@RunWith(RobolectricTestRunner::class)
class NomadNetPageCacheTest {
    private lateinit var context: Context
    private lateinit var cache: NomadNetPageCache

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = NomadNetPageCache(context)
    }

    @After
    fun tearDown() {
        // Clean up cache directory
        val cacheDir = java.io.File(context.cacheDir, "nomadnet_pages")
        cacheDir.deleteRecursively()
    }

    // ── Basic put/get ──

    @Test
    fun `put and get returns cached content`() {
        cache.put("abc123", "/page/index.mu", "Hello World", cacheSeconds = 3600)
        val result = cache.get("abc123", "/page/index.mu")
        assertEquals("Hello World", result)
    }

    @Test
    fun `get returns null for uncached page`() {
        assertNull(cache.get("nonexistent", "/page/index.mu"))
    }

    @Test
    fun `different paths are cached separately`() {
        cache.put("abc123", "/page/one.mu", "Page One", cacheSeconds = 3600)
        cache.put("abc123", "/page/two.mu", "Page Two", cacheSeconds = 3600)

        assertEquals("Page One", cache.get("abc123", "/page/one.mu"))
        assertEquals("Page Two", cache.get("abc123", "/page/two.mu"))
    }

    @Test
    fun `different nodes are cached separately`() {
        cache.put("node1", "/page/index.mu", "Node One", cacheSeconds = 3600)
        cache.put("node2", "/page/index.mu", "Node Two", cacheSeconds = 3600)

        assertEquals("Node One", cache.get("node1", "/page/index.mu"))
        assertEquals("Node Two", cache.get("node2", "/page/index.mu"))
    }

    // ── TTL / expiration ──

    @Test
    fun `cache with zero TTL does not store`() {
        cache.put("abc123", "/page/index.mu", "Transient", cacheSeconds = 0)
        assertNull(cache.get("abc123", "/page/index.mu"))
    }

    @Test
    fun `cache with null TTL uses default (stores successfully)`() {
        cache.put("abc123", "/page/index.mu", "Default TTL", cacheSeconds = null)
        assertNotNull(cache.get("abc123", "/page/index.mu"))
    }

    @Test
    fun `cache with negative TTL does not store`() {
        cache.put("abc123", "/page/index.mu", "Negative", cacheSeconds = -1)
        assertNull(cache.get("abc123", "/page/index.mu"))
    }

    @Test
    fun `expired entry returns null`() {
        // Put with 1-second TTL, then manipulate file timestamp to simulate expiry
        cache.put("abc123", "/page/index.mu", "Expired", cacheSeconds = 1)

        // Rename the file to have an expired timestamp
        val cacheDir = java.io.File(context.cacheDir, "nomadnet_pages")
        val file = cacheDir.listFiles()?.firstOrNull()
        assertNotNull("Cache file should exist", file)

        val expiredName = file!!.name.substringBeforeLast('_') + "_1000" // epoch + 1s
        file.renameTo(java.io.File(cacheDir, expiredName))

        assertNull(cache.get("abc123", "/page/index.mu"))
    }

    // ── Overwrite ──

    @Test
    fun `put overwrites previous entry for same key`() {
        cache.put("abc123", "/page/index.mu", "Version 1", cacheSeconds = 3600)
        cache.put("abc123", "/page/index.mu", "Version 2", cacheSeconds = 3600)

        assertEquals("Version 2", cache.get("abc123", "/page/index.mu"))

        // Should only have one file for this key
        val cacheDir = java.io.File(context.cacheDir, "nomadnet_pages")
        val files = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Should have exactly one cache file", 1, files.size)
    }

    // ── Remove ──

    @Test
    fun `remove deletes cached entry`() {
        cache.put("abc123", "/page/index.mu", "To Remove", cacheSeconds = 3600)
        cache.remove("abc123", "/page/index.mu")
        assertNull(cache.get("abc123", "/page/index.mu"))
    }

    @Test
    fun `remove on non-existent entry is no-op`() {
        cache.remove("nonexistent", "/page/index.mu") // Should not throw
    }

    // ── cleanExpired ──

    @Test
    fun `cleanExpired removes only expired files`() {
        cache.put("node1", "/page/one.mu", "Valid", cacheSeconds = 3600)
        cache.put("node2", "/page/two.mu", "Also Valid", cacheSeconds = 3600)

        // Manually create an expired file
        val cacheDir = java.io.File(context.cacheDir, "nomadnet_pages")
        java.io.File(cacheDir, "fakehash_1000").writeText("Expired content")

        cache.cleanExpired()

        // Valid entries still exist
        assertEquals("Valid", cache.get("node1", "/page/one.mu"))
        assertEquals("Also Valid", cache.get("node2", "/page/two.mu"))

        // Expired file is gone
        val remaining = cacheDir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue("Expired file should be removed", remaining.none { it == "fakehash_1000" })
    }

    // ── Content preservation ──

    @Test
    fun `cache preserves Micron markup with special characters`() {
        val content =
            """
            #!c=0
            >Heading
            `F00fHello `B0f0World
            ``[ Link `destination ]
            """.trimIndent()

        cache.put("abc123", "/page/styled.mu", content, cacheSeconds = 3600)
        assertEquals(content, cache.get("abc123", "/page/styled.mu"))
    }

    @Test
    fun `cache preserves Unicode block characters`() {
        val content = "▄▄▄▄▄▄▄▄▄▄"
        cache.put("abc123", "/page/art.mu", content, cacheSeconds = 3600)
        assertEquals(content, cache.get("abc123", "/page/art.mu"))
    }
}
