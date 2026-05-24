package network.columba.app.rns.api.util

import org.json.JSONObject

/**
 * Encodes and decodes LXMF tap-back reactions on the wire, shared by both
 * backends (kotlin-native + python-flavor) so the two can never drift —
 * same pattern as [TelemeterCodec].
 *
 * **Canonical wire shape** (upstream LXMF standard, `LXMF.py` commit
 * `764758d`, "to be finalized in 1.0.0"):
 * ```
 * fields[0x40] = { 0x00: <target LXMessage.hash bytes>,
 *                  0x01: <reaction content UTF-8 bytes> }
 * ```
 * The reacting user is NOT carried on the wire — upstream derives it from
 * the LXMF message's source hash, so [parseInboundReaction] sets `sender`
 * to the inbound source hash on the canonical path.
 *
 * **Legacy wire shape** (parse-only fallback, pre-standard Columba/MeshChatX):
 * ```
 * fields[0x10] = { "reaction_to": <hex>, "emoji": <unicode>, "sender": <hex> }
 * ```
 * Outbound never writes `0x10` anymore (clean cutover, mirrors the reply
 * `0x30`/`0x31` migration); it is kept on the inbound path so reactions from
 * un-upgraded Columba peers still resolve. See [LxmfFields.FIELD_REACTION] /
 * [LxmfFields.FIELD_REACTION_LEGACY].
 *
 * **Normalized form** (what both backends emit downstream to
 * `reactionReceived` / `MessagingViewModel.handleIncomingReaction`):
 * ```
 * {"reaction_to": <hex>, "emoji": <unicode>, "sender": <hex>,
 *  "source_hash": <hex>, "timestamp": <ms>}
 * ```
 * The aggregation + DB storage (`reactionsJson` column) is unchanged by the
 * wire migration — only the encode/decode here moved.
 */
object ReactionWireCodec {
    // Field-map JSON keys are the decimal string of the integer field id
    // (serializeFieldsToJson uses `key.toString()`; event_bridge.py uses
    // `str(k)`). 0x40 -> "64"; nested dict keys 0x00 -> "0", 0x01 -> "1".
    private val FIELD_REACTION_KEY = LxmfFields.FIELD_REACTION.toString()
    private val REACTION_TO_KEY = LxmfFields.REACTION_TO.toString()
    private val REACTION_CONTENT_KEY = LxmfFields.REACTION_CONTENT.toString()
    private val FIELD_REACTION_LEGACY_KEY = LxmfFields.FIELD_REACTION_LEGACY.toString()

    /**
     * Build the canonical `fields[0x40]` map for an outbound reaction.
     *
     * Returns a field map ready to merge into an LXMessage's fields — native
     * passes it straight through as `extraFields`; the python flavor wraps it
     * with `pyDict(...)`. `ByteArray` values are packed as msgpack `bin` by
     * both backends (and hex-encoded back across JNI by `event_bridge.py`'s
     * `_jsonable` on the python receive side).
     *
     * @param targetMessageIdHex hex of the target `LXMessage.hash`
     * @param emoji the reaction content (typically one Unicode emoji)
     * @return `{0x40: {0x00: hashBytes, 0x01: emojiUtf8Bytes}}`, or null if
     *   [targetMessageIdHex] is blank / not valid hex.
     */
    fun encodeReactionFields(targetMessageIdHex: String, emoji: String): Map<Int, Any>? {
        val targetBytes =
            runCatching { targetMessageIdHex.hexToBytes() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return mapOf(
            LxmfFields.FIELD_REACTION to
                mapOf(
                    LxmfFields.REACTION_TO to targetBytes,
                    LxmfFields.REACTION_CONTENT to emoji.toByteArray(Charsets.UTF_8),
                ),
        )
    }

    /**
     * Parse an inbound message's serialized `fieldsJson` into the normalized
     * reaction JSON, trying the canonical `0x40` shape first and falling back
     * to the legacy `0x10` shape.
     *
     * @param fieldsJson the message's fields serialized by
     *   `AppDataParser.serializeFieldsToJson` (kotlin backend) or
     *   `event_bridge.py` (python flavor) — byte values are already hex.
     * @param sourceHashHex hex of the inbound LXMF message's source hash;
     *   used as `sender` on the canonical path (per the upstream standard).
     * @param timestamp inbound message timestamp (ms), echoed into the output.
     * @return normalized reaction JSON, or null when no reaction field present
     *   (or the field is malformed).
     */
    fun parseInboundReaction(fieldsJson: String?, sourceHashHex: String, timestamp: Long): String? {
        if (fieldsJson.isNullOrEmpty()) return null
        return try {
            val fields = JSONObject(fieldsJson)
            parseCanonical(fields, sourceHashHex, timestamp)
                ?: parseLegacy(fields, sourceHashHex, timestamp)
        } catch (_: Exception) {
            // Malformed JSON — not a reaction we can route.
            null
        }
    }

    /** Canonical `fields[0x40] = {0x00: bytes, 0x01: bytes}` (bytes arrive hex). */
    private fun parseCanonical(fields: JSONObject, sourceHashHex: String, timestamp: Long): String? {
        val dict = fields.optJSONObject(FIELD_REACTION_KEY) ?: return null
        val reactionTo = dict.optString(REACTION_TO_KEY).takeIf { it.isNotBlank() }
        val emoji =
            dict.optString(REACTION_CONTENT_KEY).takeIf { it.isNotBlank() }?.let { decodeUtf8Hex(it) }
        if (reactionTo == null || emoji == null) return null
        // Standard: the reactor is the message source, not a wire field.
        return normalized(
            reactionTo = reactionTo.lowercase(),
            emoji = emoji,
            sender = sourceHashHex,
            sourceHashHex = sourceHashHex,
            timestamp = timestamp,
        )
    }

    /** Legacy `fields[0x10] = {reaction_to, emoji, sender}` (string-keyed). */
    private fun parseLegacy(fields: JSONObject, sourceHashHex: String, timestamp: Long): String? {
        val dict = fields.optJSONObject(FIELD_REACTION_LEGACY_KEY) ?: return null
        val reactionTo = dict.optString("reaction_to").takeIf { it.isNotBlank() } ?: return null
        val emoji = dict.optString("emoji")
        // Legacy carried the reactor explicitly; fall back to the source hash
        // if an old peer ever omitted it.
        val sender = dict.optString("sender").takeIf { it.isNotBlank() } ?: sourceHashHex
        return normalized(
            reactionTo = reactionTo,
            emoji = emoji,
            sender = sender,
            sourceHashHex = sourceHashHex,
            timestamp = timestamp,
        )
    }

    private fun normalized(
        reactionTo: String,
        emoji: String,
        sender: String,
        sourceHashHex: String,
        timestamp: Long,
    ): String =
        JSONObject()
            .put("reaction_to", reactionTo)
            .put("emoji", emoji)
            .put("sender", sender)
            .put("source_hash", sourceHashHex)
            .put("timestamp", timestamp)
            .toString()

    /** Hex string -> UTF-8 decoded text, or null if not valid hex. */
    private fun decodeUtf8Hex(hex: String): String? =
        runCatching { String(hex.hexToBytes(), Charsets.UTF_8) }.getOrNull()
}
