package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.TelemeterCodec
import network.columba.app.rns.api.util.toHex
import java.util.concurrent.ConcurrentHashMap

/**
 * `RnsTelemetry` over upstream Python LXMF, driven through Chaquopy.
 *
 * Follows the [PythonRnsCore] pattern: every `suspend` method routes through
 * [pyResult] / [pyCall]; live upstream objects are resolved out of
 * [PythonRnsRuntime]'s registries; the observable flow is sourced from
 * [PythonEventBridge].
 *
 * Telemetry split (mirrors `NativeRnsBackendImpl`):
 *  - **send paths** ([sendLocationTelemetry] / [sendTelemetryRequest]) build an
 *    `LXMF.LXMessage` carrying the relevant field and route it through
 *    `LXMRouter.handle_outbound`;
 *  - **collector-host-mode state** ([setTelemetryCollectorMode] /
 *    [storeOwnTelemetry] / [setTelemetryAllowedRequesters]) is held backend-side
 *    so a restart re-applies it, and pushed into `event_bridge.py` where the
 *    upstream LXMF `FIELD_COMMANDS` responder + `FIELD_TELEMETRY_STREAM` reply
 *    path lives (Python-side because the response touches `RNS.Identity.recall`
 *    / `RNS.Destination` / `router.handle_outbound`). Verified E2E against a
 *    Fold 7 host ↔ S21 member pair in commit `61598cf1`.
 */
@Suppress("TooManyFunctions") // Mirrors the RnsTelemetry contract surface 1:1.
class PythonRnsTelemetry(
    private val runtime: PythonRnsRuntime,
    private val events: PythonEventBridge,
) : RnsTelemetry {
    private companion object {
        const val TAG = "PythonRnsTelemetry"

        /** `LXMF.LXMessage.OPPORTUNISTIC` — single encrypted packet, no link.
         *  Matches Sideband's telemetry send (`core.py` always emits
         *  telemetry as opportunistic; link-based would block on per-peer
         *  establishment for what is fundamentally a fire-and-forget update). */
        const val LXMF_METHOD_OPPORTUNISTIC = 0x01
    }

    // ==================== Collector-host-mode state ====================
    // Held backend-side so an "Apply & Restart" re-applies it. The setters
    // also push this state into `event_bridge.py` where the upstream LXMF
    // FIELD_COMMANDS responder + FIELD_TELEMETRY_STREAM reply path lives
    // (wired and verified live in commit `61598cf1`).

    @Volatile
    private var collectorHostModeEnabled: Boolean = false

    /** hex identity hash -> last-known own telemetry JSON, for STREAM responses. */
    private val ownTelemetry = ConcurrentHashMap<String, String>()

    /** Identity hashes (32-char hex) allowed to request telemetry in host mode. */
    private val allowedRequesters = ConcurrentHashMap.newKeySet<String>()

    // ==================== Telemetry send paths ====================

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        telemetry: LocationTelemetry,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            // Wire format (Sideband-interop, paramount):
            //   FIELD_TELEMETRY (0x02)   = upstream Telemeter msgpack
            //                              ({SID_TIME, SID_LOCATION: [...]})
            //   FIELD_CUSTOM_META (0xFD) = Columba's cease/expires/approxRadius
            //                              msgpack — only attached when non-empty;
            //                              Sideband ignores entirely.
            //   FIELD_ICON_APPEARANCE (0x04) = sender chrome (Sideband interop).
            //
            // Encoding happens Kotlin-side via the shared
            // `TelemeterCodec` — one implementation of the bit-format
            // both backends interop over. The Python side just gets
            // the resulting bytes; `event_bridge.py` no longer carries
            // any Telemeter-encoding logic.
            val telemetryBytes = TelemeterCodec.packLocationTelemetry(telemetry)
            val metaBytes = TelemeterCodec.packColumbaMeta(telemetry)
            val fields = buildFieldsDict {
                putRaw(LxmfFields.FIELD_TELEMETRY, telemetryBytes.toPyBytes())
                metaBytes?.let { putRaw(LxmfFields.FIELD_CUSTOM_META, it.toPyBytes()) }
                iconAppearance?.let { put(LxmfFields.FIELD_ICON_APPEARANCE, it.toPyField()) }
            }
            sendLxmfWithFields(destinationHash, fields)
        }

    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            // A telemetry request is an LXMF FIELD_COMMANDS frame. Upstream
            // Sideband encodes this as a list of command dicts; the collector
            // replies with FIELD_TELEMETRY_STREAM since `timebase`. The
            // command-dict shape `{0x01: timebase}` (Sideband's
            // `_COMMAND_TELEMETRY_REQUEST`) was verified live against a
            // Fold 7 host ↔ S21 member pair in commit `61598cf1`.
            val commandList = listOf(
                mapOf(0x01 to (timebase ?: 0L)).toPyDict(),
            ).toPyList()
            val fields = buildFieldsDict {
                putRaw(LxmfFields.FIELD_COMMANDS, commandList)
            }
            sendLxmfWithFields(destinationHash, fields)
        }

    // ==================== Collector host mode ====================

    override suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit> =
        pyResult {
            // Push state into event_bridge — the actual LXMF FIELD_COMMANDS
            // responder + collected-telemetry store live there (Python-side
            // because the response path needs `RNS.Identity.recall` +
            // `RNS.Destination` + `router.handle_outbound`, all native
            // Python objects). Kotlin retains a mirror flag for the
            // `storeOwnTelemetry` re-sync check on send paths.
            collectorHostModeEnabled = enabled
            runtime.eventBridge.callAttr("set_collector_enabled", enabled)
            Log.i(TAG, "telemetry collector host mode -> $enabled")
        }

    override suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
    ): Result<Unit> =
        pyResult {
            // Pack the JSON into a Sideband-compatible FIELD_TELEMETRY
            // blob via the shared `TelemeterCodec`, then hand it to
            // `event_bridge.store_own_telemetry` so it lands in the same
            // `_collected_telemetry` dict the FIELD_COMMANDS responder
            // serves. Keyed in event_bridge by the local delivery
            // destination hash — no need to pass the key from here.
            val parsed = Json.parseToJsonElement(locationJson).jsonObject
            val telemetry =
                LocationTelemetry(
                    lat = parsed["lat"]?.jsonPrimitive?.double ?: 0.0,
                    lng = parsed["lng"]?.jsonPrimitive?.double ?: 0.0,
                    acc = parsed["acc"]?.jsonPrimitive?.float ?: 0f,
                    ts = parsed["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                    altitude = parsed["altitude"]?.jsonPrimitive?.double ?: 0.0,
                    speed = parsed["speed"]?.jsonPrimitive?.double ?: 0.0,
                    bearing = parsed["bearing"]?.jsonPrimitive?.double ?: 0.0,
                )
            val packedBytes = TelemeterCodec.packLocationTelemetry(telemetry)
            val tsSeconds = telemetry.ts / 1000L
            val appearancePy =
                iconAppearance?.let { it.toPyField() }
                    ?: runtime.python.getBuiltins().get("None")
            runtime.eventBridge.callAttr(
                "store_own_telemetry",
                packedBytes.toPyBytes(),
                tsSeconds,
                appearancePy,
            )
            // Keep the local mirror so a debug `ownTelemetry` inspection
            // still shows what was last stored — the canonical store now
            // lives Python-side.
            val key = runtime.localIdentity
                ?.get("hash")
                ?.toJava(ByteArray::class.java)
                ?.toHex()
                ?: "local"
            ownTelemetry[key] = locationJson
            Log.d(TAG, "stored own telemetry for $key (${packedBytes.size} packed bytes)")
        }

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> =
        pyResult {
            // Push allow-set into event_bridge so the responder's
            // permission check (run on the LXMRouter delivery thread) sees
            // it without a JNI hop per request. Kotlin retains a mirror
            // for state inspection.
            allowedRequesters.clear()
            allowedRequesters.addAll(allowedHashes)
            val pySet = runtime.python.getBuiltins().callAttr("set", allowedHashes.toTypedArray())
            runtime.eventBridge.callAttr("set_collector_allowed_requesters", pySet)
            Log.i(TAG, "telemetry allowed-requesters set updated: ${allowedRequesters.size} entries")
        }

    // ==================== Observable flow ====================

    override val locationTelemetryFlow: SharedFlow<LocationTelemetry> = events.locationTelemetry

    // ==================== Internal helpers ====================

    /**
     * Build an `LXMF.LXMessage` to [destinationHash] carrying [fields], route it
     * through `LXMRouter.handle_outbound`, and return a [MessageReceipt].
     *
     * `handle_outbound` calls `lxmessage.pack()` internally, which populates
     * `lxmessage.hash` — we read it back afterwards for the receipt.
     */
    private fun sendLxmfWithFields(destinationHash: ByteArray, fields: PyObject): MessageReceipt {
        val router = runtime.lxmRouter
            ?: throw RnsException(RnsError.BackendNotReady)
        val destDest = resolveDeliveryDestination(destinationHash)
        val sourceDest = runtime.localDestination
            ?: throw RnsException(RnsError.BackendNotReady)

        // LXMessage(destination, source, content="", title="", fields=fields,
        //           desired_method=OPPORTUNISTIC).
        // Content/title are empty: a telemetry frame is field-only.
        // OPPORTUNISTIC = single encrypted packet, no link establishment —
        // matches Sideband's telemetry send (`Sideband/.../core.py` always
        // emits telemetry as opportunistic). Without an explicit
        // `desired_method` upstream LXMF defaults to DIRECT (link-based)
        // which fails for telemetry to peers we haven't linked to before.
        // `lxmfModule["LXMessage"]` IS the class; `.call(...)` constructs it
        // (Python: `LXMF.LXMessage(...)`). Using `.callAttr("LXMessage", ...)`
        // would look up an attribute named "LXMessage" on the class itself,
        // which doesn't exist.
        val lxMessageClass = runtime.lxmfModule["LXMessage"] ?: error("LXMF.LXMessage missing")
        val lxMessage = lxMessageClass.call(
            destDest,
            sourceDest,
            "", // content
            "", // title
            fields,
            null, // app_data
            LXMF_METHOD_OPPORTUNISTIC,
        )

        router.callAttr("handle_outbound", lxMessage)

        // pack() ran inside handle_outbound, so .hash is populated. message_id
        // aliases .hash upstream — fall back through both.
        val hashBytes = (lxMessage["hash"] ?: lxMessage["message_id"])
            ?.toJava(ByteArray::class.java)
            ?: ByteArray(0)
        return MessageReceipt(
            messageHash = hashBytes,
            timestamp = System.currentTimeMillis(),
            destinationHash = destinationHash,
        )
    }

    /**
     * Resolve a 16-byte LXMF delivery destination hash to a live OUT
     * `RNS.Destination`. Prefers the runtime registry, else reconstructs it
     * from a recalled identity the way upstream LXMRouter does:
     * `RNS.Destination(identity, OUT, SINGLE, "lxmf", "delivery")`.
     */
    private fun resolveDeliveryDestination(destinationHash: ByteArray): PyObject {
        val hex = destinationHash.toHex()
        runtime.destinations[hex]?.let { return it }

        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        val recalled = identityClass.callAttr("recall", destinationHash.toPyBytes())
            ?: throw RnsException(RnsError.IdentityNotFound(hex))

        val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
        val pyDest = runtime.rnsModule.callAttr(
            "Destination",
            recalled,
            destClass["OUT"] ?: error("RNS.Destination.OUT missing"),
            destClass["SINGLE"] ?: error("RNS.Destination.SINGLE missing"),
            LxmfFields.APP_NAME,
            LxmfFields.DELIVERY_ASPECT,
        )
        runtime.destinations[hex] = pyDest
        return pyDest
    }

    /** `IconAppearance` -> the LXMF Field 4 value (a `[name, fg, bg]` list upstream). */
    private fun IconAppearance.toPyField(): PyObject =
        listOf(iconName, foregroundColor, backgroundColor).toPyList()

    /**
     * Build a Python `dict` of LXMF fields. Keys are the int FIELD_* numbers;
     * string values are passed through, pre-built PyObjects via [putRaw].
     */
    private fun buildFieldsDict(block: FieldsBuilder.() -> Unit): PyObject {
        val builder = FieldsBuilder().apply(block)
        return builder.entries.toPyDict()
    }

    /** Tiny DSL for [buildFieldsDict] — keeps the int-keyed field map readable. */
    private class FieldsBuilder {
        val entries = mutableMapOf<Int, Any>()
        fun put(field: Int, value: String) { entries[field] = value }
        fun put(field: Int, value: PyObject) { entries[field] = value }
        fun putRaw(field: Int, value: PyObject) { entries[field] = value }
    }
}

// `toPyDict()` lives in `PythonExt.kt` — promoted from this file when
// `PythonRnsNomadnet.parseFormData` hit the same `'HashMap' object is not
// iterable` crash this helper was built to dodge.
