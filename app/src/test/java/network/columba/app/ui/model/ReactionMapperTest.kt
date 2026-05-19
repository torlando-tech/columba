package network.columba.app.ui.model

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [parseReactionsJson] — the per-target-message reactions
 * aggregation parser that reads the `reactionsJson` DB column.
 *
 * Shape under test: flat `{emoji: [senderHex, ...]}`. The previous
 * `parseReactionsFromField16` overload-on-fieldsJson form was retired
 * by DB migration v1→v2 (see `ColumbaDatabase.MIGRATION_1_2`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReactionMapperTest {
    @Test
    fun `null input returns empty list`() {
        assertTrue(parseReactionsJson(null).isEmpty())
    }

    @Test
    fun `empty string input returns empty list`() {
        assertTrue(parseReactionsJson("").isEmpty())
    }

    @Test
    fun `invalid JSON returns empty list`() {
        assertTrue(parseReactionsJson("not valid json").isEmpty())
    }

    @Test
    fun `empty JSON object returns empty list`() {
        assertTrue(parseReactionsJson("{}").isEmpty())
    }

    @Test
    fun `single reaction with single sender`() {
        val result = parseReactionsJson("""{"👍": ["sender_hash_1"]}""")

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(listOf("sender_hash_1"), result[0].senderHashes)
        assertEquals(1, result[0].count)
    }

    @Test
    fun `single reaction with multiple senders preserves order`() {
        val result = parseReactionsJson("""{"👍": ["sender1", "sender2", "sender3"]}""")

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(listOf("sender1", "sender2", "sender3"), result[0].senderHashes)
        assertEquals(3, result[0].count)
    }

    @Test
    fun `multiple reactions surface all emojis`() {
        val result =
            parseReactionsJson(
                """{"👍": ["sender1"], "❤️": ["sender2", "sender3"], "😂": ["sender4"]}""",
            )

        assertEquals(3, result.size)
        val byEmoji = result.associateBy { it.emoji }
        assertEquals(listOf("sender1"), byEmoji["👍"]?.senderHashes)
        assertEquals(setOf("sender2", "sender3"), byEmoji["❤️"]?.senderHashes?.toSet())
        assertEquals(listOf("sender4"), byEmoji["😂"]?.senderHashes)
    }

    @Test
    fun `emoji with empty sender array is skipped`() {
        val result = parseReactionsJson("""{"👍": [], "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `non-array sender value is skipped`() {
        val result = parseReactionsJson("""{"👍": "not an array", "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `null sender value is skipped`() {
        val result = parseReactionsJson("""{"👍": null, "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `boolean sender value is skipped`() {
        val result = parseReactionsJson("""{"👍": true, "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `object sender value is skipped`() {
        val result = parseReactionsJson("""{"👍": {"nested": true}, "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `empty sender strings are filtered out`() {
        val result = parseReactionsJson("""{"👍": ["sender1", "", "sender2"]}""")

        assertEquals(1, result.size)
        assertEquals(listOf("sender1", "sender2"), result[0].senderHashes)
    }

    @Test
    fun `entry containing only empty senders is skipped`() {
        val result = parseReactionsJson("""{"👍": ["", ""], "❤️": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `null entries in sender array are dropped`() {
        val result = parseReactionsJson("""{"👍": ["sender1", null, "sender2"]}""")

        assertEquals(1, result.size)
        assertEquals(listOf("sender1", "sender2"), result[0].senderHashes)
    }

    @Test
    fun `numbers in sender array are coerced to strings`() {
        val result = parseReactionsJson("""{"👍": [123, 456]}""")

        assertEquals(1, result.size)
        assertEquals(listOf("123", "456"), result[0].senderHashes)
    }

    @Test
    fun `mixed-type sender array filters empties and coerces numbers`() {
        val result = parseReactionsJson("""{"👍": ["sender1", 123, "", "sender2"]}""")

        assertEquals(1, result.size)
        assertEquals(setOf("sender1", "123", "sender2"), result[0].senderHashes.toSet())
    }

    @Test
    fun `whitespace inside sender strings is preserved`() {
        val result = parseReactionsJson("""{"👍": ["  sender1  ", " ", "sender2"]}""")

        assertEquals(1, result.size)
        assertEquals(
            listOf("  sender1  ", " ", "sender2"),
            result[0].senderHashes,
        )
    }

    @Test
    fun `unicode emojis are surfaced verbatim`() {
        val result = parseReactionsJson("""{"🎉": ["sender1"], "🔥": ["sender2"]}""")

        val emojis = result.map { it.emoji }.toSet()
        assertEquals(setOf("🎉", "🔥"), emojis)
    }

    @Test
    fun `multi-codepoint emojis (skin-tones, ZWJ sequences) survive a round-trip`() {
        val result = parseReactionsJson("""{"👍🏽": ["sender1"], "👨‍👩‍👧": ["sender2"]}""")

        val emojis = result.map { it.emoji }.toSet()
        assertEquals(setOf("👍🏽", "👨‍👩‍👧"), emojis)
    }

    @Test
    fun `standard reaction set parses all six emojis`() {
        val result =
            parseReactionsJson(
                """{"👍": ["s1"], "❤️": ["s2"], "😂": ["s3"], "😮": ["s4"], "😢": ["s5"], "😡": ["s6"]}""",
            )

        assertEquals(6, result.size)
        assertEquals(
            setOf("👍", "❤️", "😂", "😮", "😢", "😡"),
            result.map { it.emoji }.toSet(),
        )
    }

    @Test
    fun `64-char sender hashes pass through unchanged`() {
        val longHash = "f4a3b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"
        val result = parseReactionsJson("""{"👍": ["$longHash"]}""")

        assertEquals(1, result.size)
        assertEquals(listOf(longHash), result[0].senderHashes)
    }

    @Test
    fun `large sender count is preserved`() {
        val senders = (1..50).map { "sender$it" }
        val sendersJson = senders.joinToString("\",\"", "[\"", "\"]")
        val result = parseReactionsJson("""{"👍": $sendersJson}""")

        assertEquals(1, result.size)
        assertEquals(50, result[0].count)
    }

    @Test
    fun `truncated input returns empty list`() {
        val result = parseReactionsJson("""{"👍": ["sender1"""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty-string emoji key is skipped alongside valid one`() {
        val result = parseReactionsJson("""{"": ["sender1"], "👍": ["sender2"]}""")

        val thumbsUp = result.find { it.emoji == "👍" }
        assertTrue(thumbsUp != null)
        assertEquals(listOf("sender2"), thumbsUp!!.senderHashes)
    }

    @Test
    fun `family ZWJ sequence works as emoji key`() {
        val familyEmoji = "👨‍👩‍👧‍👦"
        val result = parseReactionsJson("""{"$familyEmoji": ["sender1"]}""")

        assertEquals(1, result.size)
        assertEquals(familyEmoji, result[0].emoji)
    }

    @Test
    fun `ReactionUi exposes emoji, senderHashes, and count consistently`() {
        val result = parseReactionsJson("""{"👍": ["sender1", "sender2"]}""")

        assertEquals(1, result.size)
        val reaction = result[0]
        assertEquals("👍", reaction.emoji)
        assertEquals(listOf("sender1", "sender2"), reaction.senderHashes)
        assertEquals(reaction.senderHashes.size, reaction.count)
    }
}
