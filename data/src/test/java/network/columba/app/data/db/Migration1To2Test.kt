package network.columba.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin unit tests for the json-level helper that
 * `ColumbaDatabase.MIGRATION_1_2` uses to lift reactions out of the
 * legacy `fields[16].reactions` overload into the dedicated
 * `reactionsJson` column.
 *
 * Runs without Robolectric / Room — exercises [ColumbaDatabase.splitReactionsOutOfFieldsJson]
 * directly so a regression here surfaces fast.
 */
class Migration1To2Test {
    @Test
    fun `splits reactions blob and leaves remaining field16 keys in place`() {
        val input =
            """{
                "16": {"reply_to": "abc", "reactions": {"👍": ["s1", "s2"]}},
                "1": "hello"
            }"""

        val split = ColumbaDatabase.splitReactionsOutOfFieldsJson(input)
        val (newFieldsJson, reactionsJson) = requireNotNull(split)

        val fields = JSONObject(newFieldsJson)
        assertTrue(fields.has("16"))
        val field16 = fields.getJSONObject("16")
        assertEquals("abc", field16.getString("reply_to"))
        assertTrue("reactions key must be stripped", !field16.has("reactions"))

        val reactions = JSONObject(reactionsJson)
        assertEquals(listOf("s1", "s2"), (0 until reactions.getJSONArray("👍").length()).map {
            reactions.getJSONArray("👍").getString(it)
        })
    }

    @Test
    fun `removes field16 entirely when reactions was its only key`() {
        val input = """{"16": {"reactions": {"❤️": ["s1"]}}, "1": "text"}"""

        val (newFieldsJson, _) = requireNotNull(ColumbaDatabase.splitReactionsOutOfFieldsJson(input))

        val fields = JSONObject(newFieldsJson)
        assertTrue("field16 should be gone after stripping its only key", !fields.has("16"))
        assertEquals("text", fields.getString("1"))
    }

    @Test
    fun `returns null when there are no reactions to lift`() {
        val withoutReactions = """{"16": {"reply_to": "abc"}, "1": "text"}"""
        assertNull(ColumbaDatabase.splitReactionsOutOfFieldsJson(withoutReactions))
    }

    @Test
    fun `returns null when field16 is missing`() {
        val noField16 = """{"1": "text", "6": "image_hex"}"""
        assertNull(ColumbaDatabase.splitReactionsOutOfFieldsJson(noField16))
    }

    @Test
    fun `returns null on unparseable input`() {
        assertNull(ColumbaDatabase.splitReactionsOutOfFieldsJson("not valid json {"))
    }

    @Test
    fun `multiple emojis survive the round-trip`() {
        val input =
            """{"16": {"reactions": {"👍": ["s1"], "❤️": ["s2", "s3"], "😂": ["s4"]}}}"""

        val (newFieldsJson, reactionsJson) =
            requireNotNull(ColumbaDatabase.splitReactionsOutOfFieldsJson(input))

        // After stripping reactions, field16 had no other keys → removed.
        assertEquals("{}", JSONObject(newFieldsJson).toString())

        val reactions = JSONObject(reactionsJson)
        assertEquals(setOf("👍", "❤️", "😂"), reactions.keys().asSequence().toSet())
        assertEquals(2, reactions.getJSONArray("❤️").length())
    }
}
