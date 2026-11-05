package network.columba.app.rns.api.util

import org.json.JSONObject

/**
 * Codec for the LXMF thread field ([LxmfFields.FIELD_THREAD], `0x08`) as it
 * crosses the Python bridge: byte fields arrive hex-encoded inside
 * `fieldsJson` keyed by the *decimal* field id — field `0x08` is JSON key
 * `"8"`, exactly like `0x30` is key `"48"` (see `MessageMapper.parseReplyToFromFields`).
 *
 * Reception is opaque per threads-spec §4.1: any non-empty byte length is
 * accepted (the ID is just a grouping key); malformed or absent values parse
 * to `null` (= Main) and never drop the message.
 */
object ThreadFieldCodec {
    /** `fieldsJson` key for [LxmfFields.FIELD_THREAD] (decimal form of 0x08). */
    const val FIELDS_JSON_KEY = "8"

    /** `fieldsJson` key for [LxmfFields.FIELD_REPLY_HASH] (decimal form of 0x30). */
    const val REPLY_HASH_FIELDS_JSON_KEY = "48"

    /**
     * Extract the thread ID from a received message's `fieldsJson`, or null
     * when the message carries no `0x08` field (Main conversation), the
     * value is not a hex string, or the JSON is malformed.
     */
    @Suppress("SwallowedException") // Malformed fieldsJson is expected from legacy peers; fail closed to Main.
    fun threadIdFromFieldsJson(fieldsJson: String?): ByteArray? {
        if (fieldsJson == null) return null
        return try {
            val value = JSONObject(fieldsJson).opt(FIELDS_JSON_KEY)
            (value as? String)
                ?.takeIf { it.isNotEmpty() && it.length % 2 == 0 && it.isHex() }
                ?.hexToBytes()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Canonical reply-target hash (`fields[0x30]`, JSON key `"48"`) as a
     * lowercase hex string, or null. Canonical shape only — the legacy
     * `0x10` overload never carries a thread root (cases 2/4 always write
     * `0x30`), so no fallback is needed here.
     */
    @Suppress("SwallowedException")
    fun replyHashFromFieldsJson(fieldsJson: String?): String? {
        if (fieldsJson == null) return null
        return try {
            val value = JSONObject(fieldsJson).opt(REPLY_HASH_FIELDS_JSON_KEY)
            (value as? String)?.takeIf { it.isNotEmpty() }?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /** Serialize a thread ID into the `fieldsJson` hex representation (round-trip partner of [threadIdFromFieldsJson]). */
    fun threadIdToFieldsJson(threadId: ByteArray?): String? =
        threadId?.takeIf { it.isNotEmpty() }?.let { JSONObject().put(FIELDS_JSON_KEY, it.toHex()).toString() }

    private fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
