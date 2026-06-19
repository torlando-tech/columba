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
 * **Relay attribution override.** A re-originating group relay (e.g.
 * reticulum-forwarding-service) re-signs each reaction as itself, so the
 * carrying source hash is the relay, not the reactor. Such a relay stamps
 * the reactor's source hash into top-level custom fields —
 * `FIELD_CUSTOM_TYPE (0xFB) = "originator-identity"`,
 * `FIELD_CUSTOM_DATA (0xFC) = <reactor source_hash>` (its destination hash,
 * the same value a direct reaction carries and what contacts key by). When
 * that stamp is present, the canonical path uses it as `sender` instead of
 * the relay's source hash. Purely additive and backward compatible: direct
 * reactions carry no stamp and fall back to source hash.
 * (Convention: `reticulum-forwarding-service/docs/reaction-attribution.md`.)
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

    // Reactor-attribution stamp set by a re-originating group relay (see
    // FIELD_CUSTOM_TYPE / FIELD_CUSTOM_DATA on LxmfFields). The relay
    // re-signs each reaction as itself, so the carrying source_hash is the
    // relay; when these top-level fields are present, the reactor identity
    // hash in FIELD_CUSTOM_DATA is authoritative over the source hash.
    private val FIELD_CUSTOM_TYPE_KEY = LxmfFields.FIELD_CUSTOM_TYPE.toString()
    private val FIELD_CUSTOM_DATA_KEY = LxmfFields.FIELD_CUSTOM_DATA.toString()
    private const val ORIGINATOR_IDENTITY_TYPE = "originator-identity"

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
        // Standard: the reactor is the message source. But a re-originating
        // group relay re-signs each reaction as itself, so source_hash would
        // attribute every relayed reaction to the relay. When the relay
        // stamped its reactor-attribution custom fields, the reactor
        // source_hash in FIELD_CUSTOM_DATA is authoritative. Falls back to
        // source_hash for direct (non-relayed) reactions, which carry no stamp.
        //
        // ⚠️ TRUST: the stamp is unauthenticated (see originatorIdentityHex).
        // It is only safe to honor when this reaction provably came via a
        // trusted relay; a direct peer could otherwise forge attribution to a
        // third party. This codec has no DB/conversation context, so the
        // final trust decision belongs to the caller
        // (MessagingViewModel.handleIncomingReaction), which should ignore the
        // override unless the carrying source_hash matches the relay/group
        // that delivered the reacted-to message. `source_hash` (the real
        // carrier) is preserved in the normalized output for exactly that.
        val sender = originatorIdentityHex(fields) ?: sourceHashHex
        return normalized(
            reactionTo = reactionTo.lowercase(),
            emoji = emoji,
            sender = sender,
            sourceHashHex = sourceHashHex,
            timestamp = timestamp,
        )
    }

    /**
     * Read a re-originating relay's reactor-attribution stamp, returning the
     * reactor's source-hash hex when present and **well-formed**, else null.
     * Requires `FIELD_CUSTOM_TYPE == "originator-identity"` and a
     * `FIELD_CUSTOM_DATA` that is exactly a 32-hex-char destination hash (the
     * serializer hex-encodes the raw 16-byte source hash). A blank, wrong-
     * length, or non-hex value is rejected so it can never be stored as an
     * unresolvable key in `reactionsJson`. Both fields sit at the top level
     * of the field map, alongside `fields[0x40]`.
     *
     * ⚠️ SECURITY — this stamp is an **unauthenticated** assertion. It is
     * cryptographically sound only when the carrying message demonstrably
     * arrived via a **trusted relay** (the re-originating relay verified the
     * reactor's signature before stamping). A direct, non-relay peer can set
     * these same fields to attribute a reaction to an arbitrary third party.
     * Callers MUST therefore only trust the override when the reaction
     * arrived from a trusted relay context — e.g. gate it on the carrying
     * message's `source_hash` matching the relay/group source that delivered
     * the target message being reacted to (see the call site in
     * `parseCanonical` and `MessagingViewModel.handleIncomingReaction`).
     */
    private fun originatorIdentityHex(fields: JSONObject): String? {
        if (fields.optString(FIELD_CUSTOM_TYPE_KEY) != ORIGINATOR_IDENTITY_TYPE) return null
        return fields
            .optString(FIELD_CUSTOM_DATA_KEY)
            .takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Legacy `fields[0x10] = {reaction_to, emoji, sender}` (string-keyed). */
    private fun parseLegacy(fields: JSONObject, sourceHashHex: String, timestamp: Long): String? {
        val dict = fields.optJSONObject(FIELD_REACTION_LEGACY_KEY) ?: return null
        val reactionTo = dict.optString("reaction_to").takeIf { it.isNotBlank() }
        // Guard a blank/absent emoji (optString returns "" for a missing or null key),
        // matching parseCanonical — otherwise an empty "" reaction reaches the UI map.
        val emoji = dict.optString("emoji").takeIf { it.isNotBlank() }
        if (reactionTo == null || emoji == null) return null
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
