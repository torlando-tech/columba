package network.columba.app.rns.api.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [ReactionWireCodec] — the shared reaction encode/decode both backends
 * delegate to.
 *
 * The canonical-path round-trips go through [AppDataParser.serializeFieldsToJson],
 * which is byte-for-byte what the kotlin-native backend puts on the JNI/UI
 * boundary AND what the python flavor's `event_bridge.py` `_jsonable` produces
 * (`{str(k): hex(bytes)}`), so one round-trip exercises both backends' wire
 * shape. Robolectric is present because `AppDataParser` logs via
 * `android.util.Log` on its malformed-input branches (mirrors `UtilTest`).
 */
@RunWith(RobolectricTestRunner::class)
class ReactionWireCodecTest {
    private val targetHash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    private val sourceHash = "00112233445566778899aabbccddeeff"

    // ============================ encode ============================

    @Test
    fun `encode builds canonical 0x40 nested dict of raw bytes with no sender`() {
        val fields = ReactionWireCodec.encodeReactionFields(targetHash, "👍")
        requireNotNull(fields)

        // Top-level key is FIELD_REACTION (0x40), not the legacy 0x10.
        assertTrue(fields.containsKey(LxmfFields.FIELD_REACTION))
        assertFalse(fields.containsKey(LxmfFields.FIELD_REACTION_LEGACY))

        @Suppress("UNCHECKED_CAST")
        val dict = fields[LxmfFields.FIELD_REACTION] as Map<Int, Any>
        // Integer-keyed: 0x00 = target hash bytes, 0x01 = emoji UTF-8 bytes.
        assertTrue(
            (dict[LxmfFields.REACTION_TO] as ByteArray).contentEquals(targetHash.hexToBytes()),
        )
        assertTrue(
            (dict[LxmfFields.REACTION_CONTENT] as ByteArray)
                .contentEquals("👍".toByteArray(Charsets.UTF_8)),
        )
        // The reactor is NOT carried on the wire.
        assertEquals(2, dict.size)
    }

    @Test
    fun `encode returns null for blank or invalid target hash`() {
        assertNull(ReactionWireCodec.encodeReactionFields("", "👍"))
        assertNull(ReactionWireCodec.encodeReactionFields("xyz", "👍")) // non-hex
        assertNull(ReactionWireCodec.encodeReactionFields("abc", "👍")) // odd length
    }

    // ===================== canonical round-trip =====================

    @Test
    fun `canonical encode-serialize-parse round-trips and derives sender from source`() {
        val parsed = roundTrip(targetHash, "👍", sourceHash, timestamp = 1_700_000_000_000L)
        requireNotNull(parsed)
        assertEquals(targetHash, parsed.getString("reaction_to"))
        assertEquals("👍", parsed.getString("emoji"))
        // Standard: the reactor is the message source, not a wire field.
        assertEquals(sourceHash, parsed.getString("sender"))
        assertEquals(sourceHash, parsed.getString("source_hash"))
        assertEquals(1_700_000_000_000L, parsed.getLong("timestamp"))
    }

    @Test
    fun `canonical reaction_to is lowercased`() {
        val parsed = roundTrip(targetHash.uppercase(), "🎉", sourceHash, timestamp = 1L)
        requireNotNull(parsed)
        assertEquals(targetHash, parsed.getString("reaction_to"))
    }

    @Test
    fun `multi-codepoint emoji survive the bytes hex UTF-8 round-trip`() {
        // Variation selector, skin-tone modifier, and a ZWJ family sequence —
        // reaction content is arbitrary UTF-8 bytes, never assumed single-char.
        for (emoji in listOf("❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇺🇳")) {
            val parsed = roundTrip(targetHash, emoji, sourceHash, timestamp = 1L)
            requireNotNull(parsed) { "round-trip failed for $emoji" }
            assertEquals(emoji, parsed.getString("emoji"))
        }
    }

    // ================== relay attribution stamp ==================

    // The reactor's 16-byte source_hash (its lxmf.delivery destination hash)
    // — distinct from the relay's source hash on the carrying message.
    private val reactorIdentity = "fbaa52ed547644cfffe48ecf1ae1c355"

    @Test
    fun `relay originator-identity stamp overrides source hash as sender`() {
        // What a re-originating relay puts on the wire: canonical 0x40 plus the
        // top-level FIELD_CUSTOM_TYPE/DATA stamp. Serialized through the real
        // AppDataParser so the 0xFB string + 0xFC bytes hex-encode exactly as
        // on the JNI/UI boundary.
        val fields: Map<Int, Any> =
            ReactionWireCodec.encodeReactionFields(targetHash, "👍")!! +
                mapOf(
                    LxmfFields.FIELD_CUSTOM_TYPE to "originator-identity",
                    LxmfFields.FIELD_CUSTOM_DATA to reactorIdentity.hexToBytes(),
                )
        val fieldsJson = AppDataParser.serializeFieldsToJson(fields)
        val parsed =
            ReactionWireCodec.parseInboundReaction(fieldsJson, sourceHash, 7L)?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(targetHash, parsed.getString("reaction_to"))
        assertEquals("👍", parsed.getString("emoji"))
        // Stamp wins: attribute to the reactor identity, NOT the relay source.
        assertEquals(reactorIdentity, parsed.getString("sender"))
        // source_hash still records the carrying (relay) source.
        assertEquals(sourceHash, parsed.getString("source_hash"))
    }

    @Test
    fun `stamp with a non-matching type tag is ignored and falls back to source hash`() {
        val fields: Map<Int, Any> =
            ReactionWireCodec.encodeReactionFields(targetHash, "👍")!! +
                mapOf(
                    LxmfFields.FIELD_CUSTOM_TYPE to "something-else",
                    LxmfFields.FIELD_CUSTOM_DATA to reactorIdentity.hexToBytes(),
                )
        val fieldsJson = AppDataParser.serializeFieldsToJson(fields)
        val parsed =
            ReactionWireCodec.parseInboundReaction(fieldsJson, sourceHash, 7L)?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(sourceHash, parsed.getString("sender"))
    }

    @Test
    fun `direct reaction with no stamp still derives sender from source`() {
        // Backward compatibility: the unstamped canonical path is unchanged.
        val parsed = roundTrip(targetHash, "👍", sourceHash, timestamp = 7L)
        requireNotNull(parsed)
        assertEquals(sourceHash, parsed.getString("sender"))
    }

    @Test
    fun `stamp type present but data blank falls back to source hash`() {
        val fields: Map<Int, Any> =
            ReactionWireCodec.encodeReactionFields(targetHash, "👍")!! +
                mapOf(LxmfFields.FIELD_CUSTOM_TYPE to "originator-identity")
        // FIELD_CUSTOM_DATA absent entirely.
        val parsed =
            ReactionWireCodec.parseInboundReaction(
                AppDataParser.serializeFieldsToJson(fields),
                sourceHash,
                7L,
            )?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(sourceHash, parsed.getString("sender"))
    }

    @Test
    fun `malformed stamp data is rejected and falls back to source hash`() {
        // An attacker controls the wire bytes; FIELD_CUSTOM_DATA can arrive as
        // a str of any shape. Wrong length / non-hex must never become a
        // reactionsJson sender key — fall back to source_hash. (A String value
        // serializes through AppDataParser as-is, unlike a ByteArray which is
        // always hex-encoded, so this exercises the non-hex guard too.)
        val badValues =
            listOf(
                "deadbeef", // too short (8)
                "${reactorIdentity}ff", // too long (34)
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", // 32 chars, non-hex
                "abcdef00112233445566778899aabbgg", // 32 chars, trailing non-hex
            )
        for (bad in badValues) {
            val fields: Map<Int, Any> =
                ReactionWireCodec.encodeReactionFields(targetHash, "👍")!! +
                    mapOf(
                        LxmfFields.FIELD_CUSTOM_TYPE to "originator-identity",
                        LxmfFields.FIELD_CUSTOM_DATA to bad, // String → serialized verbatim
                    )
            val parsed =
                ReactionWireCodec.parseInboundReaction(
                    AppDataParser.serializeFieldsToJson(fields),
                    sourceHash,
                    7L,
                )?.let { JSONObject(it) }
            requireNotNull(parsed) { "round-trip failed for bad data $bad" }
            assertEquals("malformed stamp '$bad' should fall back", sourceHash, parsed.getString("sender"))
        }
    }

    // ===================== legacy 0x10 fallback =====================

    @Test
    fun `legacy 0x10 dict is parsed when no canonical field present`() {
        val fieldsJson =
            """{"16":{"reaction_to":"$targetHash","emoji":"😂","sender":"deadbeef"}}"""
        val parsed =
            ReactionWireCodec.parseInboundReaction(fieldsJson, sourceHash, 5L)?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(targetHash, parsed.getString("reaction_to"))
        assertEquals("😂", parsed.getString("emoji"))
        // Legacy carried the reactor explicitly — preserve it.
        assertEquals("deadbeef", parsed.getString("sender"))
        assertEquals(sourceHash, parsed.getString("source_hash"))
    }

    @Test
    fun `legacy 0x10 without sender falls back to source hash`() {
        val fieldsJson = """{"16":{"reaction_to":"$targetHash","emoji":"😂"}}"""
        val parsed =
            ReactionWireCodec.parseInboundReaction(fieldsJson, sourceHash, 5L)?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(sourceHash, parsed.getString("sender"))
    }

    @Test
    fun `legacy 0x10 with blank or absent emoji is rejected`() {
        // optString returns "" for a missing or null key; a blank emoji must not
        // become an invisible "" reaction (parity with the canonical path).
        assertNull(
            ReactionWireCodec.parseInboundReaction(
                """{"16":{"reaction_to":"$targetHash","sender":"deadbeef"}}""", sourceHash, 1L,
            ),
        )
        assertNull(
            ReactionWireCodec.parseInboundReaction(
                """{"16":{"reaction_to":"$targetHash","emoji":"","sender":"deadbeef"}}""", sourceHash, 1L,
            ),
        )
    }

    @Test
    fun `canonical 0x40 takes precedence over legacy 0x10 when both present`() {
        // Carrier with BOTH a canonical and a (different) legacy reaction.
        val canonical = ReactionWireCodec.encodeReactionFields(targetHash, "🔥")!!
        val serialized = JSONObject(AppDataParser.serializeFieldsToJson(canonical)!!)
        serialized.put(
            "16",
            JSONObject("""{"reaction_to":"ffff","emoji":"👎","sender":"cafef00d"}"""),
        )

        val parsed =
            ReactionWireCodec.parseInboundReaction(serialized.toString(), sourceHash, 1L)
                ?.let { JSONObject(it) }
        requireNotNull(parsed)
        assertEquals(targetHash, parsed.getString("reaction_to"))
        assertEquals("🔥", parsed.getString("emoji"))
        assertEquals(sourceHash, parsed.getString("sender")) // canonical → from source
    }

    // ============================ no-op / malformed ============================

    @Test
    fun `parse returns null for absent reaction field`() {
        assertNull(ReactionWireCodec.parseInboundReaction(null, sourceHash, 1L))
        assertNull(ReactionWireCodec.parseInboundReaction("", sourceHash, 1L))
        assertNull(ReactionWireCodec.parseInboundReaction("not json", sourceHash, 1L))
        // A non-reaction field map (e.g. an image) is not a reaction.
        assertNull(ReactionWireCodec.parseInboundReaction("""{"6":"deadbeef"}""", sourceHash, 1L))
    }

    @Test
    fun `canonical with missing target or content is rejected`() {
        assertNull(
            ReactionWireCodec.parseInboundReaction("""{"64":{"1":"f09f918d"}}""", sourceHash, 1L),
        )
        assertNull(
            ReactionWireCodec.parseInboundReaction("""{"64":{"0":"$targetHash"}}""", sourceHash, 1L),
        )
        assertNull(
            ReactionWireCodec.parseInboundReaction("""{"64":{"0":"","1":""}}""", sourceHash, 1L),
        )
    }

    /** encode → serialize (native/python-equivalent JSON) → parse. */
    private fun roundTrip(target: String, emoji: String, source: String, timestamp: Long): JSONObject? {
        val fields = ReactionWireCodec.encodeReactionFields(target, emoji) ?: return null
        val fieldsJson = AppDataParser.serializeFieldsToJson(fields) ?: return null
        return ReactionWireCodec.parseInboundReaction(fieldsJson, source, timestamp)?.let { JSONObject(it) }
    }
}
