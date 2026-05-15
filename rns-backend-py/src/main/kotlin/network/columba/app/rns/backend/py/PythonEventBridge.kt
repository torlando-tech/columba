package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.NodeType
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.util.AppDataParser
import org.json.JSONObject

/**
 * Kotlin side of the event bridge.
 *
 * Owns the `MutableSharedFlow`s that the `PythonRns*` sub-impls expose
 * through `observeAnnounces()` / `observeMessages()` / etc., and the five
 * [PyEventCallback]s that `event_bridge.py` invokes on RNS/LXMF internal
 * threads. Each callback translates the flattened Python dict into the
 * `:rns-api` model type and `tryEmit`s onto the matching flow.
 *
 * **These are the same flow shapes `NativeRnsBackendImpl` exposes** — the
 * `:rns-host` app-logic consumers (MessageCollector, telemetry collection,
 * blocking, …) attach to `observeMessages()` etc. without caring which
 * backend produced the events. That backend-agnostic seam is the whole
 * point of the dual build.
 *
 * `tryEmit` is used rather than a `scope.launch { emit() }` because it is
 * non-suspending and thread-safe — the callbacks fire on Python's GIL
 * threads and must return fast. The buffer capacities mirror
 * `NativeRnsBackendImpl`.
 */
class PythonEventBridge {
    private companion object {
        const val TAG = "PythonEventBridge"

        // LXMF FIELD_* numbers, stringified to match event_bridge.py's
        // json.dumps({str(k): ...}) keys.
        const val FIELD_TELEMETRY = "2"
        const val FIELD_TELEMETRY_STREAM = "3"
        const val FIELD_ICON_APPEARANCE = "4"

        /** Columba's custom reaction field (LXMF Field 16 = 0x10). */
        const val FIELD_REACTION = "16"
    }

    private val _announces = MutableSharedFlow<AnnounceEvent>(extraBufferCapacity = 64)
    private val _messages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusUpdate>(extraBufferCapacity = 64)
    private val _locationTelemetry = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _reactionReceived = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 16)
    private val _links = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)

    val announces: SharedFlow<AnnounceEvent> = _announces.asSharedFlow()
    val messages: SharedFlow<ReceivedMessage> = _messages.asSharedFlow()
    val deliveryStatus: SharedFlow<DeliveryStatusUpdate> = _deliveryStatus.asSharedFlow()
    val locationTelemetry: SharedFlow<String> = _locationTelemetry.asSharedFlow()
    val reactionReceived: SharedFlow<String> = _reactionReceived.asSharedFlow()
    val packets: SharedFlow<ReceivedPacket> = _packets.asSharedFlow()
    val links: SharedFlow<LinkEvent> = _links.asSharedFlow()

    // --- The five sinks event_bridge.py drives -----------------------------

    val onAnnounce = PyEventCallback { payload -> handleAnnounce(payload) }
    val onLxmfDelivery = PyEventCallback { payload -> handleLxmfDelivery(payload) }
    val onLxmfFailure = PyEventCallback { payload -> handleLxmfFailure(payload) }

    /**
     * Outbound-delivery sink: `event_bridge.attach_lxmessage_callbacks` fires
     * this when a message Columba *sent* receives its delivery proof — the
     * packet proof for an OPPORTUNISTIC send, the link ack for a DIRECT send.
     * Attached per-LXMessage by [PythonRnsLxmf], not via `register_callbacks`.
     */
    val onLxmfDelivered = PyEventCallback { payload -> handleLxmfDelivered(payload) }

    /**
     * Packet observation is a low-traffic diagnostic surface; upstream RNS
     * delivers raw packets per-Destination, so wiring this fully is on-device
     * integration work. The sink is present so `event_bridge.py`'s
     * `register_callbacks` signature is satisfied today.
     */
    val onPacket = PyEventCallback { payload -> handlePacket(payload) }

    /**
     * Link events are per-`RNS.Link` upstream (no global stream). Wiring this
     * fully needs the link callbacks attached where links are created — also
     * on-device integration work. Sink present to satisfy the signature.
     */
    val onLinkEvent = PyEventCallback { payload -> handleLinkEvent(payload) }

    private fun handleAnnounce(payload: PyObject) {
        runCatching {
            val destHash = payload.dictBytes("destination_hash") ?: return
            // event_bridge._AnnounceHandler already drops announces whose aspect
            // doesn't resolve to one Columba tracks (matching the kotlin
            // backend's RichAnnounceHandler). Re-guard here so a null aspect
            // can never reach nodeTypeForAspect — its `else` would map to
            // NodeType.NODE and collide unknown-aspect junk into the "Site"
            // announce-stream filter alongside real nomadnetwork.node entries.
            val aspect = payload.dictStr("aspect") ?: return
            // Display name + stamp meta are derived kotlin-side from the raw
            // app_data via the shared `AppDataParser` — same code path the
            // native kotlin backend (`NativeRnsBackendImpl`) uses — so the
            // two backends can never drift on the parsing rules. event_bridge.py
            // only does Python-only enrichment (aspect resolution via
            // `RNS.Destination.hash_from_name_and_identity` and hops via
            // `RNS.Transport.hops_to`) and hands the raw app_data bytes over.
            val appData = payload.dictBytes("app_data")
            val displayName = appData?.let { AppDataParser.parseDisplayName(it, aspect) }
            val (stampCost, stampFlex, peeringCost) =
                AppDataParser.parseStampMeta(appData, aspect)
            val event = AnnounceEvent(
                destinationHash = destHash,
                identity = Identity(
                    hash = payload.dictBytes("identity_hash") ?: ByteArray(0),
                    publicKey = payload.dictBytes("public_key") ?: ByteArray(0),
                    privateKey = null,
                ),
                appData = appData,
                hops = payload.dictInt("hops") ?: 0,
                timestamp = System.currentTimeMillis(),
                aspect = aspect,
                nodeType = nodeTypeForAspect(aspect),
                displayName = displayName,
                stampCost = stampCost,
                stampCostFlexibility = stampFlex,
                peeringCost = peeringCost,
            )
            _announces.tryEmit(event)
        }.onFailure { Log.e(TAG, "announce translation failed", it) }
    }

    /**
     * Mirrors `NativeRnsBackendImpl.resolveNodeType` — derived from the announce
     * aspect. Only ever called with one of the four aspects Columba tracks:
     * `event_bridge._AnnounceHandler` drops unresolved-aspect announces and
     * `handleAnnounce` re-guards, so `aspect` is never null/unknown here. The
     * `else` is a defensive default only — it must NOT be reached, because
     * mapping anything-but-`nomadnetwork.node` to `NODE` would collide junk
     * into the "Site" announce-stream filter.
     */
    private fun nodeTypeForAspect(aspect: String?): NodeType =
        when (aspect) {
            "lxmf.propagation" -> NodeType.PROPAGATION_NODE
            "nomadnetwork.node" -> NodeType.NODE
            "lxst.telephony" -> NodeType.PHONE
            "lxmf.delivery" -> NodeType.PEER
            else -> NodeType.NODE
        }

    private fun handleLxmfDelivery(payload: PyObject) {
        runCatching {
            val sourceHash = payload.dictBytes("source_hash") ?: ByteArray(0)
            val destHash = payload.dictBytes("destination_hash") ?: ByteArray(0)
            val fieldsJson = payload.dictStr("fields_json")
            val message = ReceivedMessage(
                messageHash = payload.dictStr("hash").orEmpty(),
                content = payload.dictStr("content").orEmpty(),
                sourceHash = sourceHash,
                destinationHash = destHash,
                timestamp = (payload.dictDouble("timestamp")
                    ?: (System.currentTimeMillis() / 1000.0)).let { (it * 1000).toLong() },
                fieldsJson = fieldsJson,
                // Icon appearance (LXMF Field 4) — parse from fieldsJson so the
                // Python side stays thin. Matches NativeTelemetryHandler's
                // extractIconAppearance on the kotlin backend.
                iconAppearance = fieldsJson?.let { extractIconAppearance(it) },
                // Receiving-interface, hops, and signal metrics. Upstream
                // Python LXMF does not annotate any of these; `event_bridge.py`
                // sources them at delivery time from (a) the torlando-tech
                // LXMF fork's `message.receiving_interface` / `receiving_hops`
                // (set on opportunistic deliveries), (b) `RNS.Transport.
                // path_table` as a fallback for link-based deliveries, and
                // (c) `interface.get_rssi()` / `get_snr()` on the receiving
                // RNode interface for the signal metrics — mirrors
                // `release/v0.10.x`'s `signal_quality.extract_signal_metrics`.
                // Non-RNode paths (TCP/Auto/Backbone) leave rssi/snr null,
                // matching kotlin's null-when-unavailable shape.
                receivedHopCount = payload.dictInt("receiving_hops"),
                receivedInterface = payload.dictStr("receiving_interface"),
                receivedRssi = payload.dictInt("rssi"),
                receivedSnr = payload.dictDouble("snr")?.toFloat(),
            )
            _messages.tryEmit(message)

            // Derive the telemetry / reaction side-channels from the field map —
            // same split NativeRnsBackendImpl makes, just sourced from JSON.
            if (fieldsJson != null) {
                routeFieldSideChannels(fieldsJson)
            }
        }.onFailure { Log.e(TAG, "lxmf delivery translation failed", it) }
    }

    /**
     * Parse LXMF Field 4 (icon appearance) out of the JSON-encoded field map.
     *
     * Mirrors `NativeTelemetryHandler.extractIconAppearance` but reads from
     * the JSON form `event_bridge.py` emits: `_jsonable` hex-encodes ByteArray
     * field values, so the fg / bg entries here are already lowercase hex
     * strings — same shape `ByteArray.toHex()` produces on the kotlin path.
     */
    private fun extractIconAppearance(fieldsJson: String): IconAppearance? =
        runCatching {
            val fields = JSONObject(fieldsJson)
            val arr = fields.optJSONArray(FIELD_ICON_APPEARANCE) ?: return@runCatching null
            if (arr.length() < 3) return@runCatching null
            val name = arr.optString(0, "").takeIf { it.isNotEmpty() } ?: return@runCatching null
            val fg = arr.optString(1, "").takeIf { it.isNotEmpty() } ?: return@runCatching null
            val bg = arr.optString(2, "").takeIf { it.isNotEmpty() } ?: return@runCatching null
            IconAppearance(iconName = name, foregroundColor = fg, backgroundColor = bg)
        }.getOrNull()

    private fun routeFieldSideChannels(fieldsJson: String) {
        runCatching {
            val fields = JSONObject(fieldsJson)
            // Telemetry: FIELD_TELEMETRY (single) or FIELD_TELEMETRY_STREAM (batch).
            if (fields.has(FIELD_TELEMETRY)) {
                _locationTelemetry.tryEmit(fields.get(FIELD_TELEMETRY).toString())
            }
            if (fields.has(FIELD_TELEMETRY_STREAM)) {
                _locationTelemetry.tryEmit(fields.get(FIELD_TELEMETRY_STREAM).toString())
            }
            // Reaction: Columba's custom Field 16.
            if (fields.has(FIELD_REACTION)) {
                _reactionReceived.tryEmit(fields.get(FIELD_REACTION).toString())
            }
        }.onFailure { Log.w(TAG, "field side-channel routing failed", it) }
    }

    private fun handleLxmfFailure(payload: PyObject) {
        runCatching {
            _deliveryStatus.tryEmit(
                DeliveryStatusUpdate(
                    messageHash = payload.dictStr("hash").orEmpty(),
                    status = "failed",
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "lxmf failure translation failed", it) }
    }

    private fun handleLxmfDelivered(payload: PyObject) {
        runCatching {
            // Mirrors NativeMessageSender — "delivered" is the same status
            // string the kotlin backend emits on a delivery proof.
            _deliveryStatus.tryEmit(
                DeliveryStatusUpdate(
                    messageHash = payload.dictStr("hash").orEmpty(),
                    status = "delivered",
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "lxmf delivered translation failed", it) }
    }

    private fun handlePacket(payload: PyObject) {
        // See onPacket kdoc — structural placeholder until per-Destination
        // packet callbacks are wired on-device.
        Log.v(TAG, "packet event received (not yet wired): ${payload.dictStr("destination_hash")}")
    }

    private fun handleLinkEvent(payload: PyObject) {
        // See onLinkEvent kdoc — structural placeholder until per-Link
        // callbacks are wired on-device.
        Log.v(TAG, "link event received (not yet wired): ${payload.dictStr("link_id")}")
    }
}
