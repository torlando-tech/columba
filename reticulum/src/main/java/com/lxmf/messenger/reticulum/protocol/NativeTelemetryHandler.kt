package com.lxmf.messenger.reticulum.protocol

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.json.JSONObject
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer

internal class NativeTelemetryHandler(
    private val scopeProvider: () -> CoroutineScope,
    private val locationTelemetryFlow: MutableSharedFlow<String>,
    private val deliveryIdentityProvider: () -> NativeIdentity?,
    private val sendMessageFn: suspend (ByteArray, String, DeliveryMethod, Map<Int, Any>?) -> Unit,
    private val storedTelemetry: java.util.concurrent.ConcurrentHashMap<String, StoredTelemetryEntry>,
    private val telemetryCollectorEnabledProvider: () -> Boolean,
    private val telemetryAllowedRequestersProvider: () -> Set<String>,
) {
    companion object {
        private const val TAG = "NativeReticulumProtocol"
        private const val LOCATION_SHARE_TYPE = "location_share"
        private const val FIELD_COLUMBA_META = 0x70
        private const val LEGACY_LOCATION_FIELD = 7
    }

    data class StoredTelemetryEntry(
        val timestampSeconds: Long,
        val packedTelemetry: ByteArray,
        val appearanceField: List<Any>? = null,
        val receivedAtMillis: Long = System.currentTimeMillis(),
    )

    fun handleIncomingTelemetry(
        message: LXMessage,
        timestamp: Long,
    ): Boolean {
        val fields = message.fields
        val hasTextContent = message.content.isNotBlank()
        var isLocationOnlyMessage = false
        var locationEvent: JSONObject? = null

        locationEvent = processFieldTelemetry(fields, message.sourceHash, timestamp, locationEvent)

        val telemetryStream = fields[LXMFConstants.FIELD_TELEMETRY_STREAM] as? List<*>
        if (telemetryStream != null) {
            if (!hasTextContent) isLocationOnlyMessage = true
            val streamEntries =
                telemetryStream
                    .mapNotNull { it as? List<*> }
                    .mapNotNull { entry -> unpackTelemetryStreamEntry(entry)?.toString() }
            Log.d(TAG, "Telemetry stream received with ${streamEntries.size} entries")
            streamEntries.forEach { entryJson -> locationTelemetryFlow.tryEmit(entryJson) }
        }

        locationEvent = processMetaField(fields, timestamp, locationEvent)
        if (locationEvent == null) {
            locationEvent = parseJsonField(fields[LEGACY_LOCATION_FIELD])
        }

        if (locationEvent != null) {
            if (!hasTextContent) {
                isLocationOnlyMessage = true
            }
            locationEvent.put("source_hash", message.sourceHash.toHex())
            extractIconAppearance(fields)?.let { appearance ->
                locationEvent.put(
                    "appearance",
                    JSONObject()
                        .put("icon_name", appearance.iconName)
                        .put("foreground_color", appearance.foregroundColor)
                        .put("background_color", appearance.backgroundColor),
                )
            }
            Log.d(TAG, "Emitting location telemetry: $locationEvent")
            locationTelemetryFlow.tryEmit(locationEvent.toString())
        }

        return isLocationOnlyMessage
    }

    private fun processFieldTelemetry(
        fields: Map<Int, Any>,
        sourceHash: ByteArray,
        timestamp: Long,
        existing: JSONObject?,
    ): JSONObject? {
        val telemetryField = fields[LXMFConstants.FIELD_TELEMETRY] ?: return existing
        val locationEvent = unpackLocationTelemetryField(telemetryField) ?: return existing
        Log.d(TAG, "Telemetry received in FIELD_TELEMETRY from ${sourceHash.toHex().take(16)}")

        if (telemetryCollectorEnabledProvider()) {
            val packedTelemetry = telemetryField as? ByteArray
            if (packedTelemetry != null) {
                storeTelemetryForCollector(
                    sourceHashHex = sourceHash.toHex(),
                    packedTelemetry = packedTelemetry,
                    timestampSeconds = (locationEvent.optLong("ts", timestamp) / 1000L),
                    appearanceField = sanitizeAppearanceField(fields[LXMFConstants.FIELD_ICON_APPEARANCE]),
                )
            }
        }
        return locationEvent
    }

    private fun processMetaField(
        fields: Map<Int, Any>,
        timestamp: Long,
        existing: JSONObject?,
    ): JSONObject? {
        val metaField = fields[FIELD_COLUMBA_META]
        val metaJson = parseJsonField(metaField) ?: return existing

        if (metaJson.optBoolean("cease", false)) {
            return JSONObject()
                .put("type", LOCATION_SHARE_TYPE)
                .put("cease", true)
                .put("ts", metaJson.optLong("ts", timestamp))
        }
        if (existing != null) {
            if (metaJson.has("expires") && !metaJson.isNull("expires")) {
                existing.put("expires", metaJson.getLong("expires"))
            }
            if (metaJson.has("approxRadius") && !metaJson.isNull("approxRadius")) {
                existing.put("approxRadius", metaJson.getInt("approxRadius"))
            }
        }
        return existing
    }

    fun handleTelemetryCommands(message: LXMessage) {
        val commandList =
            (message.fields[LXMFConstants.FIELD_COMMANDS] as? List<*>)
                ?.takeIf { telemetryCollectorEnabledProvider() }
                ?: return
        var hasTelemetryRequest = false
        var timebaseMillis: Long? = null

        commandList.forEach { entry ->
            when (entry) {
                is Map<*, *> -> {
                    when {
                        entry["cmd"] == "get_telemetry" -> {
                            hasTelemetryRequest = true
                        }
                        entry["cmd"] == "set_timebase" -> {
                            timebaseMillis = (entry["timebase"] as? Number)?.toLong()
                        }
                        entry.keys.any { (it as? Number)?.toInt() == 0x01 } -> {
                            hasTelemetryRequest = true
                            val args = entry.entries.firstOrNull { (it.key as? Number)?.toInt() == 0x01 }?.value as? List<*>
                            timebaseMillis = (args?.getOrNull(0) as? Number)?.toLong()
                        }
                    }
                }
            }
        }

        val senderHex = message.sourceHash.toHex()
        if (!hasTelemetryRequest || senderHex !in telemetryAllowedRequestersProvider()) {
            if (hasTelemetryRequest) {
                Log.d(TAG, "Telemetry request from $senderHex denied — not in allowed requesters")
            }
            return
        }

        val entriesToSend =
            storedTelemetry
                .filterValues { entry -> timebaseMillis == null || entry.receivedAtMillis >= timebaseMillis!! }
                .map { (sourceHashHex, entry) ->
                    val row = mutableListOf<Any>(sourceHashHex.hexToBytes(), entry.timestampSeconds, entry.packedTelemetry)
                    entry.appearanceField?.let { row.add(it) }
                    row
                }

        Log.d(TAG, "Responding to telemetry request from $senderHex with ${entriesToSend.size} entries")
        scopeProvider().launch {
            try {
                if (deliveryIdentityProvider() == null) return@launch

                sendMessageFn(
                    message.sourceHash,
                    "",
                    DeliveryMethod.DIRECT,
                    mapOf(LXMFConstants.FIELD_TELEMETRY_STREAM to entriesToSend),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send telemetry response: ${e.message}")
            }
        }
    }

    fun unpackLocationTelemetryField(field: Any): JSONObject? =
        when (field) {
            is String -> runCatching { JSONObject(field) }.getOrNull()
            is ByteArray -> unpackLocationFromMsgpack(field)
            else -> null
        }

    private fun unpackLocationFromMsgpack(data: ByteArray): JSONObject? =
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val mapSize = unpacker.unpackMapHeader()
            var locationList: List<*>? = null

            repeat(mapSize) {
                val key = unpacker.unpackInt()
                val value = MsgpackHelper.unpackValue(unpacker)
                if (key == 0x02) {
                    locationList = value as? List<*>
                }
            }

            val loc = locationList
            if (loc == null || loc.size < 7) {
                null
            } else {
                val lastUpdateSeconds = (loc[6] as? Number)?.toLong()
                if (lastUpdateSeconds == null) {
                    null
                } else {
                    JSONObject()
                        .put("type", LOCATION_SHARE_TYPE)
                        .put("lat", ByteBuffer.wrap(loc[0] as ByteArray).int / 1_000_000.0)
                        .put("lng", ByteBuffer.wrap(loc[1] as ByteArray).int / 1_000_000.0)
                        .put("acc", (ByteBuffer.wrap(loc[5] as ByteArray).short.toInt() and 0xFFFF) / 100.0)
                        .put("ts", lastUpdateSeconds * 1000L)
                        .put("altitude", ByteBuffer.wrap(loc[2] as ByteArray).int / 100.0)
                        .put("speed", (ByteBuffer.wrap(loc[3] as ByteArray).int.toLong() and 0xFFFFFFFFL) / 100.0)
                        .put("bearing", ByteBuffer.wrap(loc[4] as ByteArray).int / 100.0)
                }
            }
        } catch (_: Exception) {
            runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        }

    private fun unpackTelemetryStreamEntry(entry: List<*>): JSONObject? {
        if (entry.size < 3) return null

        val sourceHash =
            when (val source = entry[0]) {
                is ByteArray -> source.toHex()
                is String -> source
                else -> null
            }
        val telemetryField = entry[2]
        val telemetry = telemetryField?.let { unpackLocationTelemetryField(it) }
        if (sourceHash == null || telemetry == null) return null

        val entryTimestampSeconds = (entry[1] as? Number)?.toLong()
        if (entryTimestampSeconds != null && entryTimestampSeconds > 0) {
            telemetry.put("ts", entryTimestampSeconds * 1000L)
        }
        telemetry.put("source_hash", sourceHash.lowercase())

        val appearance = sanitizeAppearanceField(entry.getOrNull(3))
        if (appearance != null) {
            telemetry.put(
                "appearance",
                JSONObject()
                    .put("icon_name", appearance[0] as String)
                    .put("foreground_color", (appearance[1] as ByteArray).toHex())
                    .put("background_color", (appearance[2] as ByteArray).toHex()),
            )
        }

        return telemetry
    }

    @Suppress("UNCHECKED_CAST")
    fun sanitizeAppearanceField(field: Any?): List<Any>? {
        val list = field as? List<*>
        if (list == null || list.size < 3) return null
        val name = list[0] as? String
        val fg = list[1] as? ByteArray
        val bg = list[2] as? ByteArray
        return if (name != null && fg != null && bg != null) listOf(name, fg, bg) else null
    }

    fun extractIconAppearance(fields: Map<Int, Any>): IconAppearance? {
        val iconField = fields[LXMFConstants.FIELD_ICON_APPEARANCE] as? List<*>
        if (iconField == null || iconField.size < 3) return null
        val name =
            (iconField[0] as? String)
                ?: (iconField[0] as? ByteArray)?.let { String(it, Charsets.UTF_8) }
        val fg = (iconField[1] as? ByteArray)?.toHex()
        val bg = (iconField[2] as? ByteArray)?.toHex()
        return if (name != null && fg != null && bg != null) {
            IconAppearance(iconName = name, foregroundColor = fg, backgroundColor = bg)
        } else {
            null
        }
    }

    fun storeTelemetryForCollector(
        sourceHashHex: String,
        packedTelemetry: ByteArray,
        timestampSeconds: Long,
        appearanceField: List<Any>? = null,
    ) {
        storedTelemetry[sourceHashHex] =
            StoredTelemetryEntry(
                timestampSeconds = timestampSeconds,
                packedTelemetry = packedTelemetry,
                appearanceField = appearanceField,
                receivedAtMillis = System.currentTimeMillis(),
            )
        Log.d(TAG, "Stored telemetry for collector from ${sourceHashHex.take(16)}")
    }

    fun packLocationTelemetry(locationData: JSONObject): ByteArray {
        val lat = locationData.getDouble("lat")
        val lng = locationData.getDouble("lng")
        val accuracy = locationData.optDouble("acc", 0.0)
        val altitude = locationData.optDouble("altitude", 0.0)
        val speed = locationData.optDouble("speed", 0.0)
        val bearing = locationData.optDouble("bearing", 0.0)
        val timestampMillis = locationData.optLong("ts", System.currentTimeMillis())
        val timestampSeconds = timestampMillis / 1000L

        val locationPacked =
            listOf(
                ByteBuffer.allocate(4).putInt(kotlin.math.round(lat * 1_000_000.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(kotlin.math.round(lng * 1_000_000.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(kotlin.math.round(altitude * 100.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(kotlin.math.round(maxOf(speed, 0.0) * 100.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(kotlin.math.round(bearing * 100.0).toInt()).array(),
                ByteBuffer
                    .allocate(2)
                    .putShort(minOf(kotlin.math.round(maxOf(accuracy, 0.0) * 100.0).toInt(), 65535).toShort())
                    .array(),
                timestampSeconds,
            )

        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packInt(0x01)
        packer.packLong(timestampSeconds)
        packer.packInt(0x02)
        MsgpackHelper.packValue(packer, locationPacked)
        packer.close()
        return packer.toByteArray()
    }

    private fun parseJsonField(field: Any?): JSONObject? =
        try {
            when (field) {
                is String -> JSONObject(field)
                is ByteArray -> JSONObject(String(field, Charsets.UTF_8))
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
