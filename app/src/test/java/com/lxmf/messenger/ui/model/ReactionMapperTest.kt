package com.lxmf.messenger.ui.model

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReactionMapperTest {
    // ========== parseReactionsFromField16() TESTS ==========

    @Test
    fun `parseReactionsFromField16 returns empty list for null fieldsJson`() {
        val result = parseReactionsFromField16(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list for empty fieldsJson`() {
        val result = parseReactionsFromField16("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list for invalid JSON`() {
        val result = parseReactionsFromField16("not valid json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when field 16 is missing`() {
        val fieldsJson = """{"1": "some text", "6": "image_hex"}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when field 16 is not an object`() {
        val fieldsJson = """{"16": "not an object"}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when field 16 is a number`() {
        val fieldsJson = """{"16": 12345}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when field 16 is null`() {
        val fieldsJson = """{"16": null}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when reactions key is missing`() {
        val fieldsJson = """{"16": {"reply_to": "message_id"}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when reactions is not an object`() {
        val fieldsJson = """{"16": {"reactions": "not an object"}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when reactions is a number`() {
        val fieldsJson = """{"16": {"reactions": 12345}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when reactions is null`() {
        val fieldsJson = """{"16": {"reactions": null}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 returns empty list when reactions is an empty object`() {
        val fieldsJson = """{"16": {"reactions": {}}}"""
        val result = parseReactionsFromField16(fieldsJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 parses single reaction with single sender`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender_hash_1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(1, result[0].senderHashes.size)
        assertEquals("sender_hash_1", result[0].senderHashes[0])
        assertEquals(1, result[0].count)
    }

    @Test
    fun `parseReactionsFromField16 parses single reaction with multiple senders`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", "sender2", "sender3"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(3, result[0].senderHashes.size)
        assertEquals("sender1", result[0].senderHashes[0])
        assertEquals("sender2", result[0].senderHashes[1])
        assertEquals("sender3", result[0].senderHashes[2])
        assertEquals(3, result[0].count)
    }

    @Test
    fun `parseReactionsFromField16 parses multiple reactions`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1"], "❤️": ["sender2", "sender3"], "😂": ["sender4"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(3, result.size)

        // Find each reaction (order may not be guaranteed)
        val thumbsUp = result.find { it.emoji == "👍" }
        val heart = result.find { it.emoji == "❤️" }
        val laugh = result.find { it.emoji == "😂" }

        assertTrue(thumbsUp != null)
        assertEquals(1, thumbsUp!!.senderHashes.size)
        assertEquals("sender1", thumbsUp.senderHashes[0])

        assertTrue(heart != null)
        assertEquals(2, heart!!.senderHashes.size)
        assertTrue(heart.senderHashes.contains("sender2"))
        assertTrue(heart.senderHashes.contains("sender3"))

        assertTrue(laugh != null)
        assertEquals(1, laugh!!.senderHashes.size)
        assertEquals("sender4", laugh.senderHashes[0])
    }

    @Test
    fun `parseReactionsFromField16 skips reactions with empty sender array`() {
        val fieldsJson = """{"16": {"reactions": {"👍": [], "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
        assertEquals(1, result[0].senderHashes.size)
    }

    @Test
    fun `parseReactionsFromField16 skips reactions where sender array is not array`() {
        val fieldsJson = """{"16": {"reactions": {"👍": "not an array", "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 skips empty sender strings in array`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", "", "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(2, result[0].senderHashes.size)
        assertEquals("sender1", result[0].senderHashes[0])
        assertEquals("sender2", result[0].senderHashes[1])
    }

    @Test
    fun `parseReactionsFromField16 skips reactions with only empty sender strings`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["", ""], "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 handles reactions alongside reply_to`() {
        val fieldsJson = """{"16": {"reply_to": "original_msg_id", "reactions": {"👍": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        assertEquals(1, result[0].senderHashes.size)
    }

    @Test
    fun `parseReactionsFromField16 handles reactions with other field 16 properties`() {
        val fieldsJson = """{
            "16": {
                "reply_to": "message_hash_123",
                "reactions": {"👍": ["user1"], "❤️": ["user2"]},
                "mentions": ["user3", "user4"]
            }
        }"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(2, result.size)
        val emojis = result.map { it.emoji }
        assertTrue(emojis.contains("👍"))
        assertTrue(emojis.contains("❤️"))
    }

    @Test
    fun `parseReactionsFromField16 handles unicode emoji correctly`() {
        val fieldsJson = """{"16": {"reactions": {"🎉": ["sender1"], "🔥": ["sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(2, result.size)
        val emojis = result.map { it.emoji }
        assertTrue(emojis.contains("🎉"))
        assertTrue(emojis.contains("🔥"))
    }

    @Test
    fun `parseReactionsFromField16 handles complex emoji correctly`() {
        // Testing multi-codepoint emojis (skin tones, ZWJ sequences)
        val fieldsJson = """{"16": {"reactions": {"👍🏽": ["sender1"], "👨‍👩‍👧": ["sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(2, result.size)
        val emojis = result.map { it.emoji }
        assertTrue(emojis.contains("👍🏽"))
        assertTrue(emojis.contains("👨‍👩‍👧"))
    }

    @Test
    fun `parseReactionsFromField16 handles all standard reaction emoji set`() {
        // Testing the standard reaction set from spec: 👍 ❤️ 😂 😮 😢 😡
        val fieldsJson = """{"16": {"reactions": {
            "👍": ["s1"],
            "❤️": ["s2"],
            "😂": ["s3"],
            "😮": ["s4"],
            "😢": ["s5"],
            "😡": ["s6"]
        }}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(6, result.size)
        val emojis = result.map { it.emoji }
        assertTrue(emojis.contains("👍"))
        assertTrue(emojis.contains("❤️"))
        assertTrue(emojis.contains("😂"))
        assertTrue(emojis.contains("😮"))
        assertTrue(emojis.contains("😢"))
        assertTrue(emojis.contains("😡"))
    }

    @Test
    fun `parseReactionsFromField16 handles message with other LXMF fields`() {
        val fieldsJson = """{
            "1": "text content",
            "6": "image_hex_data",
            "16": {"reactions": {"👍": ["sender1"]}}
        }"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 handles long sender hashes`() {
        val longHash = "f4a3b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"
        val fieldsJson = """{"16": {"reactions": {"👍": ["$longHash"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(1, result[0].senderHashes.size)
        assertEquals(longHash, result[0].senderHashes[0])
    }

    @Test
    fun `parseReactionsFromField16 handles many senders for single reaction`() {
        val senders = (1..50).map { "sender$it" }
        val sendersJson = senders.joinToString("\",\"", "[\"", "\"]")
        val fieldsJson = """{"16": {"reactions": {"👍": $sendersJson}}}"""

        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(50, result[0].senderHashes.size)
        assertEquals(50, result[0].count)
    }

    @Test
    fun `parseReactionsFromField16 handles deeply nested JSON without crashing`() {
        val fieldsJson = """{
            "1": {"nested": {"deep": "value"}},
            "16": {
                "reactions": {"👍": ["sender1"]},
                "metadata": {"nested": {"more": "data"}}
            }
        }"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 handles malformed JSON gracefully`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1"}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 handles reactions with null values in sender array`() {
        // JSON null values in the array should be handled gracefully
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", null, "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("👍", result[0].emoji)
        // optString returns "" for null, which is then filtered out as empty
        assertEquals(2, result[0].senderHashes.size)
        assertEquals("sender1", result[0].senderHashes[0])
        assertEquals("sender2", result[0].senderHashes[1])
    }

    @Test
    fun `parseReactionsFromField16 handles sender array with numbers gracefully`() {
        // Numbers in sender array should be converted to strings
        val fieldsJson = """{"16": {"reactions": {"👍": [123, 456]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(2, result[0].senderHashes.size)
        assertEquals("123", result[0].senderHashes[0])
        assertEquals("456", result[0].senderHashes[1])
    }

    @Test
    fun `parseReactionsFromField16 handles sender array with mixed types`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", 123, "", "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        // Empty strings are filtered, numbers converted to strings
        assertEquals(3, result[0].senderHashes.size)
        assertTrue(result[0].senderHashes.contains("sender1"))
        assertTrue(result[0].senderHashes.contains("123"))
        assertTrue(result[0].senderHashes.contains("sender2"))
    }

    @Test
    fun `parseReactionsFromField16 count property equals senderHashes size`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["s1", "s2", "s3", "s4", "s5"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(5, result[0].senderHashes.size)
        assertEquals(result[0].senderHashes.size, result[0].count)
    }

    @Test
    fun `parseReactionsFromField16 handles empty string emoji key`() {
        // Edge case: empty string as emoji key
        val fieldsJson = """{"16": {"reactions": {"": ["sender1"], "👍": ["sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        // Should still parse the non-empty emoji
        val thumbsUp = result.find { it.emoji == "👍" }
        assertTrue(thumbsUp != null)
        assertEquals(1, thumbsUp!!.senderHashes.size)
    }

    @Test
    fun `parseReactionsFromField16 handles whitespace in sender strings`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["  sender1  ", " ", "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        // Note: whitespace is not trimmed, only empty strings are filtered
        assertEquals(3, result[0].senderHashes.size)
        assertEquals("  sender1  ", result[0].senderHashes[0])
        assertEquals(" ", result[0].senderHashes[1])
        assertEquals("sender2", result[0].senderHashes[2])
    }

    @Test
    fun `parseReactionsFromField16 handles reactions object with null sender array`() {
        val fieldsJson = """{"16": {"reactions": {"👍": null, "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 returns ReactionUi with correct data class structure`() {
        val fieldsJson = """{"16": {"reactions": {"👍": ["sender1", "sender2"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        val reaction = result[0]

        // Verify ReactionUi data class properties
        assertEquals("👍", reaction.emoji)
        assertEquals(listOf("sender1", "sender2"), reaction.senderHashes)
        assertEquals(2, reaction.count)
    }

    @Test
    fun `parseReactionsFromField16 handles empty JSON object`() {
        val result = parseReactionsFromField16("{}")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseReactionsFromField16 handles very long emoji key`() {
        // Some emoji sequences can be quite long
        val longEmoji = "👨‍👩‍👧‍👦" // Family emoji (ZWJ sequence)
        val fieldsJson = """{"16": {"reactions": {"$longEmoji": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        assertEquals(1, result.size)
        assertEquals(longEmoji, result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 handles reactions with boolean values gracefully`() {
        val fieldsJson = """{"16": {"reactions": {"👍": true, "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        // Boolean value for 👍 should be skipped
        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }

    @Test
    fun `parseReactionsFromField16 handles reactions with object value gracefully`() {
        val fieldsJson = """{"16": {"reactions": {"👍": {"nested": "object"}, "❤️": ["sender1"]}}}"""
        val result = parseReactionsFromField16(fieldsJson)

        // Object value for 👍 should be skipped
        assertEquals(1, result.size)
        assertEquals("❤️", result[0].emoji)
    }
}
