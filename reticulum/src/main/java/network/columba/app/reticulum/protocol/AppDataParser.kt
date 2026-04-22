package network.columba.app.reticulum.protocol

import android.util.Log

internal object AppDataParser {
    private const val TAG = "NativeReticulumProtocol"

    fun parseDisplayName(
        appData: ByteArray,
        aspect: String,
    ): String? =
        try {
            when {
                appData.isEmpty() -> null
                aspect == "lxmf.propagation" -> parsePropagationNodeName(appData)
                aspect == "nomadnetwork.node" -> {
                    val raw = String(appData, Charsets.UTF_8)
                    raw.split(":").firstOrNull()?.takeIf { it.isNotBlank() }
                }
                else -> parsePeerDisplayName(appData)
            }
        } catch (e: Exception) {
            val preview = appData.take(16).joinToString(" ") { "%02x".format(it) }
            Log.w(TAG, "Failed to parse display name (aspect=$aspect, size=${appData.size}, first16=[$preview])", e)
            null
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
