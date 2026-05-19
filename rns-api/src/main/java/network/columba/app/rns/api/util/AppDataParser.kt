package network.columba.app.rns.api.util

import android.util.Log
import network.columba.app.rns.api.model.IconAppearance

/**
 * Backend-agnostic announce `app_data` parsing.
 *
 * Sits in `:rns-api` so both backends share one source of truth for
 * display-name + stamp-cost extraction: the kotlin native backend
 * (`:rns-backend-kt`) calls these from `NativeRnsBackendImpl`, and the
 * python flavor (`:rns-backend-py`) calls them from `PythonEventBridge`
 * with the raw `app_data` bytes the event_bridge.py hands over the JNI
 * seam. Keeping the parser here closes the drift gap that previously
 * required fixing the same NomadNet ":"-split bug in two files.
 *
 * The `app_data` formats parsed here are protocol-leaf — they're set by
 * upstream LXMF / NomadNet, not by Columba — so reuse across backends is
 * unambiguously correct and the upstream wire format is the spec.
 */
object AppDataParser {
    private const val TAG = "NativeReticulumProtocol"

    fun parseDisplayName(
        appData: ByteArray,
        aspect: String,
    ): String? =
        try {
            when {
                appData.isEmpty() -> null
                aspect == Aspects.LXMF_PROPAGATION -> parsePropagationNodeName(appData)
                aspect == Aspects.NOMADNET_NODE -> {
                    // NomadNet `Node.py`: `self.app_data = self.name.encode("utf-8")`
                    // — the configured node name, no field-delimiter format.
                    // A previous `split(":").firstOrNull()` stripped colons out
                    // of names like ".:FreeBSD 1st nomad node" and surfaced
                    // only "." in the UI.
                    String(appData, Charsets.UTF_8).takeIf { it.isNotBlank() }
                }
                else -> parsePeerDisplayName(appData)
            }
        } catch (e: Exception) {
            val preview = appData.take(16).joinToString(" ") { "%02x".format(it) }
            Log.w(TAG, "Failed to parse display name (aspect=$aspect, size=${appData.size}, first16=[$preview])", e)
            null
        }

    /**
     * Stamp-meta triple `(stampCost, flexibility, peering)` for an announce.
     *
     * Propagation-node announces carry the full triple in their msgpack
     * payload; peer announces (lxmf.delivery + any other aspect) only carry
     * a single stamp cost — flexibility / peering are always null for those.
     */
    fun parseStampMeta(
        appData: ByteArray?,
        aspect: String,
    ): Triple<Int?, Int?, Int?> =
        if (appData == null) {
            Triple(null, null, null)
        } else if (aspect == Aspects.LXMF_PROPAGATION) {
            parsePropagationStampMeta(appData) ?: Triple(null, null, null)
        } else {
            Triple(parsePeerStampCost(appData), null, null)
        }

    private fun parsePeerDisplayName(appData: ByteArray): String? {
        val firstByte = appData[0].toInt() and 0xFF
        if ((firstByte in 0x90..0x9f) || firstByte == 0xdc) {
            val unpacker =
                org.msgpack.core.MessagePack
                    .newDefaultUnpacker(appData)
            val arrayLen = unpacker.unpackArrayHeader()
            if (arrayLen < 1) return null
            val format = unpacker.nextFormat
            return when (format.valueType) {
                org.msgpack.value.ValueType.NIL -> {
                    unpacker.unpackNil()
                    null
                }
                org.msgpack.value.ValueType.BINARY -> {
                    val nameLen = unpacker.unpackBinaryHeader()
                    val nameBytes = unpacker.readPayload(nameLen)
                    String(nameBytes, Charsets.UTF_8)
                }
                org.msgpack.value.ValueType.STRING -> {
                    unpacker.unpackString()
                }
                else -> {
                    unpacker.skipValue()
                    null
                }
            }
        }
        return String(appData, Charsets.UTF_8)
    }

    private fun parsePropagationNodeName(appData: ByteArray): String? {
        val unpacker =
            org.msgpack.core.MessagePack
                .newDefaultUnpacker(appData)
        val arrayLen = unpacker.unpackArrayHeader()
        if (arrayLen < 7) return null
        repeat(6) { unpacker.skipValue() }
        val mapLen = unpacker.unpackMapHeader()
        repeat(mapLen) {
            val key = unpacker.unpackInt()
            if (key == 0x01) {
                val nameLen = unpacker.unpackBinaryHeader()
                val nameBytes = unpacker.readPayload(nameLen)
                return String(nameBytes, Charsets.UTF_8)
            } else {
                unpacker.skipValue()
            }
        }
        return null
    }

    fun parsePropagationStampMeta(appData: ByteArray): Triple<Int?, Int?, Int?>? =
        try {
            val unpacker =
                org.msgpack.core.MessagePack
                    .newDefaultUnpacker(appData)
            val arrayLen = unpacker.unpackArrayHeader()
            if (arrayLen < 6) {
                null
            } else {
                repeat(5) { unpacker.skipValue() }
                val costArrayLen = unpacker.unpackArrayHeader()
                val stampCost = if (costArrayLen >= 1) unpacker.unpackInt() else null
                val flexibility = if (costArrayLen >= 2) unpacker.unpackInt() else null
                val peering = if (costArrayLen >= 3) unpacker.unpackInt() else null
                Triple(stampCost, flexibility, peering)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse propagation stamp meta: ${e.message}")
            null
        }

    fun parsePeerStampCost(appData: ByteArray): Int? =
        try {
            val firstByte = appData[0].toInt() and 0xFF
            if ((firstByte in 0x90..0x9f) || firstByte == 0xdc) {
                val unpacker =
                    org.msgpack.core.MessagePack
                        .newDefaultUnpacker(appData)
                val arrayLen = unpacker.unpackArrayHeader()
                if (arrayLen < 2) {
                    null
                } else {
                    unpacker.skipValue()
                    val format = unpacker.nextFormat
                    if (format.valueType == org.msgpack.value.ValueType.NIL) {
                        unpacker.unpackNil()
                        null
                    } else {
                        unpacker.unpackInt()
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse peer stamp cost: ${e.message}")
            null
        }

    /**
     * Parse an LXMF Field 4 (icon appearance) tuple into a typed
     * [IconAppearance], or return null if the tuple is missing/malformed.
     *
     * Accepts the field in either shape both backends use:
     *  - msgpack-decoded `List<Any?>` of `[String|ByteArray, ByteArray, ByteArray]`
     *    (kotlin native backend path — the fg/bg ByteArrays are raw 3-byte RGB).
     *  - JSON-decoded `List<Any?>` of `[String, String, String]` (python backend
     *    path — `event_bridge.py` hex-encodes the fg/bg bytes before crossing
     *    JNI, so they arrive as already-hex strings).
     *
     * The kotlin-side `IconAppearance` value always carries fg/bg as
     * lowercase-hex strings; ByteArray inputs are converted with `%02x`.
     */
    fun parseIconAppearance(field: List<Any?>?): IconAppearance? {
        if (field == null || field.size < 3) return null
        val name = nameOrNull(field[0]) ?: return null
        val fg = hexOrStringOrNull(field[1]) ?: return null
        val bg = hexOrStringOrNull(field[2]) ?: return null
        return IconAppearance(iconName = name, foregroundColor = fg, backgroundColor = bg)
    }

    private fun nameOrNull(value: Any?): String? =
        when (value) {
            is String -> value.takeIf { it.isNotEmpty() }
            is ByteArray -> String(value, Charsets.UTF_8).takeIf { it.isNotEmpty() }
            else -> null
        }

    private fun hexOrStringOrNull(value: Any?): String? =
        when (value) {
            is ByteArray -> value.joinToString("") { "%02x".format(it) }.takeIf { it.isNotEmpty() }
            is String -> value.takeIf { it.isNotEmpty() }
            else -> null
        }

    fun serializeFieldsToJson(fields: Map<Int, Any>): String? =
        try {
            val json = org.json.JSONObject()
            for ((key, value) in fields) {
                json.put(key.toString(), serializeFieldValue(value))
            }
            json.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize fields to JSON", e)
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun serializeFieldValue(value: Any?): Any? =
        when (value) {
            null -> org.json.JSONObject.NULL
            is String -> value
            is Number -> value
            is Boolean -> value
            is ByteArray -> value.joinToString("") { "%02x".format(it) }
            is List<*> ->
                org.json.JSONArray().also { arr ->
                    for (item in value) arr.put(serializeFieldValue(item))
                }
            is Map<*, *> ->
                org.json.JSONObject().also { obj ->
                    for ((k, v) in value) obj.put(k.toString(), serializeFieldValue(v))
                }
            else -> value.toString()
        }
}
