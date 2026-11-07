package network.columba.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locked-decision tests: 16B CSPRNG IDs, 14-day dormancy window, 120-char excerpt cap. */
class ThreadPolicyTest {
    @Test
    fun `new thread ids are 16 bytes and fresh`() {
        val ids = List(64) { ThreadPolicy.newThreadId() }
        ids.forEach { assertEquals(ThreadPolicy.THREAD_ID_SIZE_BYTES, it.size) }
        // Birthday-wise a collision among 64 draws from 2^128 is ~0; a repeat
        // means the generator is broken (constant / seeded).
        assertEquals(64, ids.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet().size)
        // And not all-zero (a plausible SecureRandom misuse failure).
        assertTrue(ids.any { id -> id.any { it != 0.toByte() } })
    }


    @Test
    fun `sanitize thread title flattens caps and rejects blank`() {
        assertEquals("Trip planning", ThreadPolicy.sanitizeThreadTitle("  Trip   planning  "))
        assertEquals("Build notes from the mesh", ThreadPolicy.sanitizeThreadTitle("Build\nnotes\tfrom\u0007 the mesh"))
        assertEquals(null, ThreadPolicy.sanitizeThreadTitle(null))
        assertEquals(null, ThreadPolicy.sanitizeThreadTitle("   "))
        assertEquals(null, ThreadPolicy.sanitizeThreadTitle("\n\u0000"))
        assertEquals(ThreadPolicy.MAX_THREAD_TITLE_LENGTH, 64)
        assertEquals(64, ThreadPolicy.sanitizeThreadTitle("y".repeat(500))?.length)
        // No ellipsis: a user-typed title is hard-capped, not abbreviated.
        assertEquals("y".repeat(64), ThreadPolicy.sanitizeThreadTitle("y".repeat(500)))
    }

    @Test
    fun `dormancy boundary is exactly 14 days`() {
        val now = 1_730_000_000_000L
        val window = 14L * 24 * 60 * 60 * 1000
        assertEquals(window, ThreadPolicy.DORMANCY_WINDOW_MS)

        assertFalse(ThreadPolicy.isDormant(now - window, now)) // exactly at window: active
        assertTrue(ThreadPolicy.isDormant(now - window - 1, now)) // one ms past: dormant
        assertFalse(ThreadPolicy.isDormant(now, now))
    }

    @Test
    fun `quote excerpt caps at 120 chars without ellipsis`() {
        val short = "hello"
        assertEquals(short, ThreadPolicy.quoteExcerpt(short))
        val long = "x".repeat(500)
        assertEquals(120, ThreadPolicy.quoteExcerpt(long).length)
        assertEquals("x".repeat(120), ThreadPolicy.quoteExcerpt(long))
        assertEquals(120, ThreadPolicy.QUOTE_EXCERPT_MAX_CHARS)
    }

    @Test
    fun `title excerpt flattens whitespace and truncates with ellipsis inside the cap`() {
        assertEquals("first line second line", ThreadPolicy.threadTitleExcerpt("first\n\nline   second\tline "))
        val long = "a".repeat(300)
        val title = ThreadPolicy.threadTitleExcerpt(long)
        assertEquals(ThreadPolicy.QUOTE_EXCERPT_MAX_CHARS, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals("", ThreadPolicy.threadTitleExcerpt("   \n  "))
    }

    @Test
    fun `distinct calls never return the same id`() {
        assertNotEquals(
            ThreadPolicy.newThreadId().toList(),
            ThreadPolicy.newThreadId().toList(),
        )
    }
}
