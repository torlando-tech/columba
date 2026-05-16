package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.LocationTelemetry
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import java.nio.ByteBuffer
import kotlin.math.round

/**
 * Single source of truth for the Sideband-compatible Telemeter wire
 * format (LXMF `FIELD_TELEMETRY` payload) + Columba's `FIELD_CUSTOM_META`
 * extras. Both backends — Kotlin-native (`rns-backend-kt`) and Python-
 * Chaquopy (`rns-backend-py`) — route through this codec so there's
 * exactly one implementation of the bit-format both apps interoperate
 * over.
 *
 * Wire-format reference: `Sideband/sbapp/sideband/sense.py`'s
 * `Telemeter.packed()` / `Telemeter.from_packed()`:
 *
 * ```
 * FIELD_TELEMETRY (0x02) =
 *   umsgpack.packb({
 *     SID_TIME (0x01)     : <int seconds>,
 *     SID_LOCATION (0x02) : [
 *       struct.pack("!i", int(lat * 1e6)),       # 4 bytes signed
 *       struct.pack("!i", int(lon * 1e6)),
 *       struct.pack("!i", int(altitude_m * 1e2)),
 *       struct.pack("!I", int(speed_mps * 1e2)),  # 4 bytes unsigned
 *       struct.pack("!i", int(bearing_deg * 1e2)),
 *       struct.pack("!H", int(accuracy_m * 1e2)), # 2 bytes unsigned
 *       last_update_unix_seconds,                 # raw int
 *     ],
 *   })
 *
 * FIELD_CUSTOM_META (0xFD) =
 *   umsgpack.packb({ "cease": bool?, "expires": long?,
 *                    "approxRadius": int?, "ts": long? })
 * ```
 *
 * Both Python backends previously had ~190 lines of Columba-custom
 * Telemeter encoding in `event_bridge.py`; consolidating here means
 * the Python tree stays slim (per the rns-backend-py CLAUDE.md
 * "ONE Columba-authored Python file" rule) AND drift between two
 * codec implementations of the same Sideband-interop wire format
 * is impossible — they're the same code.
 */
object TelemeterCodec {
    private const val SID_TIME = 0x01
    private const val SID_LOCATION = 0x02

    /**
     * Pack a [LocationTelemetry] into `FIELD_TELEMETRY` bytes the
     * receiving peer (Sideband, MeshChat, another Columba) decodes via
     * upstream `Telemeter.from_packed`. Cease frames still get a
     * Telemeter blob — the cease signal travels in
     * [packColumbaMeta] / `FIELD_CUSTOM_META`, but the location body
     * present here keeps Sideband-side decoders from rejecting the
     * envelope.
     */
    fun packLocationTelemetry(telemetry: LocationTelemetry): ByteArray {
        val ts = if (telemetry.ts > 0) telemetry.ts else System.currentTimeMillis()
        val tsSeconds = ts / 1000L
        val locationPacked =
            listOf(
                ByteBuffer.allocate(4).putInt(round(telemetry.lat * 1_000_000.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(round(telemetry.lng * 1_000_000.0).toInt()).array(),
                ByteBuffer.allocate(4).putInt(round(telemetry.altitude * 100.0).toInt()).array(),
                ByteBuffer.allocate(4)
                    .putInt(round(maxOf(telemetry.speed, 0.0) * 100.0).toInt())
                    .array(),
                ByteBuffer.allocate(4).putInt(round(telemetry.bearing * 100.0).toInt()).array(),
                ByteBuffer
                    .allocate(2)
                    .putShort(
                        minOf(round(maxOf(telemetry.acc.toDouble(), 0.0) * 100.0).toInt(), 0xFFFF)
                            .toShort(),
                    ).array(),
                tsSeconds,
            )

        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packInt(SID_TIME)
        packer.packLong(tsSeconds)
        packer.packInt(SID_LOCATION)
        packValue(packer, locationPacked)
        packer.close()
        return packer.toByteArray()
    }

    /**
     * Unpack a `FIELD_TELEMETRY` Telemeter blob into the location fields
     * of a [LocationTelemetry]. Returns null when the bytes aren't a
     * Telemeter-shape payload (e.g. a legacy Columba JSON-in-bytes or a
     * malformed peer message) — caller falls back to a JSON parse there.
     *
     * The returned [LocationTelemetry] has [LocationTelemetry.sourceHash]
     * and [LocationTelemetry.appearance] set to null because those come
     * from the LXMessage envelope and `FIELD_ICON_APPEARANCE` (separate
     * fields, not part of Telemeter) — the caller injects them.
     */
    fun unpackLocationTelemetry(data: ByteArray): LocationTelemetry? =
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val mapSize = unpacker.unpackMapHeader()
            var locationList: List<*>? = null

            repeat(mapSize) {
                val key = unpacker.unpackInt()
                val value = unpackValue(unpacker)
                if (key == SID_LOCATION) {
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
                    LocationTelemetry(
                        lat = ByteBuffer.wrap(loc[0] as ByteArray).int / 1_000_000.0,
                        lng = ByteBuffer.wrap(loc[1] as ByteArray).int / 1_000_000.0,
                        acc = ((ByteBuffer.wrap(loc[5] as ByteArray).short.toInt() and 0xFFFF) / 100.0)
                            .toFloat(),
                        ts = lastUpdateSeconds * 1000L,
                        altitude = ByteBuffer.wrap(loc[2] as ByteArray).int / 100.0,
                        speed = (ByteBuffer.wrap(loc[3] as ByteArray).int.toLong() and 0xFFFFFFFFL) /
                            100.0,
                        bearing = ByteBuffer.wrap(loc[4] as ByteArray).int / 100.0,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Pack Columba's location-share extras into the `FIELD_CUSTOM_META`
     * payload. Returns null when the [telemetry] carries no extras
     * worth sending (no cease, no expiry, no coarsening radius, no
     * ms-precision timestamp Telemeter's seconds-precision drops) — the
     * caller should then OMIT the field from the outbound LXMessage so
     * Sideband peers see a clean Telemeter-only payload.
     *
     * Wire (msgpack): `{cease?, expires?, approxRadius?, ts?}`.
     */
    fun packColumbaMeta(telemetry: LocationTelemetry): ByteArray? {
        val meta = mutableMapOf<String, Any>()
        if (telemetry.cease) meta["cease"] = true
        telemetry.expires?.let { meta["expires"] = it }
        if (telemetry.approxRadius > 0) meta["approxRadius"] = telemetry.approxRadius
        // ms-precision ts is worth carrying when it's NOT a clean second
        // boundary (Telemeter's `last_update` quantizes to seconds; if
        // the caller has ms precision the meta block preserves it).
        if (telemetry.ts > 0 && telemetry.ts % 1000L != 0L) {
            meta["ts"] = telemetry.ts
        }
        if (meta.isEmpty()) return null

        val packer = MessagePack.newDefaultBufferPacker()
        packValue(packer, meta)
        packer.close()
        return packer.toByteArray()
    }

    /** Unpack a `FIELD_CUSTOM_META` blob into a Columba-extras dataclass. */
    data class ColumbaMeta(
        val cease: Boolean = false,
        val expires: Long? = null,
        val approxRadius: Int = 0,
        /** ms-precision timestamp the sender carried alongside the
         *  seconds-precision Telemeter last_update. Null when absent. */
        val tsMillis: Long? = null,
    )

    fun unpackColumbaMeta(data: ByteArray): ColumbaMeta? =
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val unpacked = unpackValue(unpacker) as? Map<*, *> ?: return null
            ColumbaMeta(
                cease = (unpacked["cease"] as? Boolean) == true,
                expires = (unpacked["expires"] as? Number)?.toLong(),
                approxRadius = (unpacked["approxRadius"] as? Number)?.toInt() ?: 0,
                tsMillis = (unpacked["ts"] as? Number)?.toLong(),
            )
        } catch (_: Exception) {
            null
        }

    // ---- msgpack value helpers ---------------------------------------
    // Type-routing packer/unpacker for the polymorphic SID_LOCATION list
    // (contains ByteArray, Long, ...). Lifted from
    // `rns-backend-kt/.../MsgpackHelper.kt` so the shared codec doesn't
    // pull in that module.

    private fun packValue(
        packer: MessagePacker,
        value: Any?,
    ) {
        when (value) {
            null -> packer.packNil()
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is String -> packer.packString(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Float -> packer.packFloat(value)
            is Double -> packer.packDouble(value)
            is Boolean -> packer.packBoolean(value)
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { packValue(packer, it) }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packValue(packer, k)
                    packValue(packer, v)
                }
            }
            is Number -> packer.packLong(value.toLong())
            else -> packer.packString(value.toString())
        }
    }

    private fun unpackValue(unpacker: MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType.name) {
            "NIL" -> {
                unpacker.unpackNil()
                null
            }
            "BOOLEAN" -> unpacker.unpackBoolean()
            "INTEGER" -> unpacker.unpackLong()
            "FLOAT" -> unpacker.unpackDouble()
            "STRING" -> unpacker.unpackString()
            "BINARY" -> {
                val len = unpacker.unpackBinaryHeader()
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes
            }
            "ARRAY" -> {
                val size = unpacker.unpackArrayHeader()
                MutableList(size) { unpackValue(unpacker) }
            }
            "MAP" -> {
                val size = unpacker.unpackMapHeader()
                mutableMapOf<Any?, Any?>().also { map ->
                    repeat(size) {
                        map[unpackValue(unpacker)] = unpackValue(unpacker)
                    }
                }
            }
            else -> {
                unpacker.skipValue()
                null
            }
        }
    }
}
