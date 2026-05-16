package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.SharedFlow
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.toHex
import java.util.concurrent.ConcurrentHashMap

/**
 * `RnsTelemetry` over upstream Python LXMF, driven through Chaquopy.
 *
 * Follows the [PythonRnsCore] pattern: every `suspend` method routes through
 * [pyResult] / [pyCall]; live upstream objects are resolved out of
 * [PythonRnsRuntime]'s registries; the observable flow is sourced from
 * [PythonEventBridge]; on-device-iteration-shaped gaps are honest minimal
 * impls with `TODO(on-device)` markers — never silent fakes.
 *
 * Telemetry split (mirrors `NativeRnsBackendImpl`):
 *  - **send paths** ([sendLocationTelemetry] / [sendTelemetryRequest]) build an
 *    `LXMF.LXMessage` carrying the relevant field and route it through
 *    `LXMRouter.handle_outbound` — a real best-effort PyObject impl;
 *  - **collector-host-mode state** ([setTelemetryCollectorMode] /
 *    [storeOwnTelemetry] / [setTelemetryAllowedRequesters]) is held backend-side
 *    so a restart re-applies it. Full upstream LXMF `FIELD_TELEMETRY_STREAM`
 *    responder wiring genuinely needs device iteration to verify — the state is
 *    tracked honestly, not faked, and the responder hookup is `TODO(on-device)`.
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
    // Held backend-side so an "Apply & Restart" re-applies it. The upstream
    // LXMF FIELD_TELEMETRY_STREAM responder that consumes this state is
    // on-device follow-up — see the TODO(on-device) notes on the setters.

    @Volatile
    private var collectorHostModeEnabled: Boolean = false

    /** hex identity hash -> last-known own telemetry JSON, for STREAM responses. */
    private val ownTelemetry = ConcurrentHashMap<String, String>()

    /** Identity hashes (32-char hex) allowed to request telemetry in host mode. */
    private val allowedRequesters = ConcurrentHashMap.newKeySet<String>()

    // ==================== Telemetry send paths ====================

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> =
        pyResult {
            runtime.requireRunning()
            // FIELD_TELEMETRY carries the location JSON payload; FIELD_ICON_APPEARANCE
            // optionally rides along (Sideband/MeshChat interop, LXMF Field 4).
            val fields = buildFieldsDict {
                put(LxmfFields.FIELD_TELEMETRY, locationJson)
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
            // replies with FIELD_TELEMETRY_STREAM since `timebase`.
            // TODO(on-device): the exact FIELD_COMMANDS command-dict shape
            // (Sideband uses {0x01: timebase} for "telemetry request") needs
            // verification against a live collector — the structural cut sends
            // a single timebase-keyed command entry.
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
            // Honest minimal impl: track the mode flag backend-side so a restart
            // re-applies it. Wiring the upstream LXMF FIELD_TELEMETRY_STREAM
            // responder (the LXMRouter delivery callback that inspects inbound
            // FIELD_COMMANDS and replies with the stored stream) is on-device
            // follow-up — it needs the upstream responder path verified live.
            // TODO(on-device): full collector-host wiring against upstream LXMF.
            collectorHostModeEnabled = enabled
            Log.i(TAG, "telemetry collector host mode -> $enabled (responder wiring is on-device follow-up)")
        }

    override suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
    ): Result<Unit> =
        pyResult {
            // Honest minimal impl: keep the host's own latest telemetry so it can
            // be folded into FIELD_TELEMETRY_STREAM responses once the responder
            // is wired. Keyed by the local delivery identity hash.
            // TODO(on-device): full collector-host wiring against upstream LXMF.
            val key = runtime.localIdentity
                ?.get("hash")
                ?.toJava(ByteArray::class.java)
                ?.toHex()
                ?: "local"
            ownTelemetry[key] = locationJson
            Log.d(TAG, "stored own telemetry for $key (${locationJson.length} chars)")
        }

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> =
        pyResult {
            // Honest minimal impl: maintain the allow-set backend-side so a
            // restart re-applies it. The responder that actually filters inbound
            // FIELD_COMMANDS requests against this set is on-device follow-up.
            // TODO(on-device): full collector-host wiring against upstream LXMF.
            allowedRequesters.clear()
            allowedRequesters.addAll(allowedHashes)
            Log.i(TAG, "telemetry allowed-requesters set updated: ${allowedRequesters.size} entries")
        }

    // ==================== Observable flow ====================

    override val locationTelemetryFlow: SharedFlow<String> = events.locationTelemetry

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

/**
 * Kotlin `Map` -> real Python `dict`. Mirrors [toPyList]'s footgun guard: a raw
 * Kotlin map handed into a `callAttr` dict parameter is not seen as a `dict`
 * upstream. Values that are already [PyObject]s are passed through; everything
 * else crosses as Chaquopy's default marshalling.
 */
private fun Map<*, *>.toPyDict(): PyObject {
    val builtins = com.chaquo.python.Python.getInstance().builtins
    val dict = builtins.callAttr("dict")
    forEach { (k, v) -> dict.callAttr("__setitem__", k, v) }
    return dict
}
