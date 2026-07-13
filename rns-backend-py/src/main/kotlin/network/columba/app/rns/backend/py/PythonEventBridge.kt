package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import network.columba.app.rns.api.annotation.ReflectivelyKept
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.DeliveryState
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.model.NodeType
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.util.AppDataParser
import network.columba.app.rns.api.util.isUserVisibleChatMessage
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.ReactionWireCodec
import network.columba.app.rns.api.util.TelemeterCodec
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex
import org.json.JSONArray
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
@ReflectivelyKept // Python (event_bridge.py) calls this via Chaquopy reflection — R8 must not strip/rename it
class PythonEventBridge {
    private companion object {
        const val TAG = "PythonEventBridge"

        /**
         * Upstream `LXMF.LXMessage.PROPAGATED` = 0x03. Inlined (rather than
         * importing LXMF Kotlin bindings) because this bridge only needs the
         * integer for the delivery-status branch — distinguishing "stored on
         * the relay" from "ack'd by recipient". Matches the matching
         * `_LXMF_METHOD_PROPAGATED` constant in `event_bridge.py`.
         */
        const val LXMF_METHOD_OPPORTUNISTIC = 0x01
        const val LXMF_METHOD_DIRECT = 0x02
        const val LXMF_METHOD_PROPAGATED = 0x03
        const val LXMF_METHOD_PAPER = 0x04

        /**
         * Map an upstream `LXMessage.method` int to the lowercase-string
         * vocabulary the UI + persistence layer already use for outbound
         * messages (see `MessageEntity.deliveryMethod`,
         * `MessageDetailScreen.getDeliveryMethodInfo`). Keeps inbound and
         * outbound rendering paths sharing one set of labels — null when the
         * method is unknown so the UI's null-guarded card disappears
         * gracefully rather than rendering "Unknown".
         */
        fun lxmfMethodName(method: Int?): String? =
            when (method) {
                LXMF_METHOD_OPPORTUNISTIC -> "opportunistic"
                LXMF_METHOD_DIRECT -> "direct"
                LXMF_METHOD_PROPAGATED -> "propagated"
                LXMF_METHOD_PAPER -> "paper"
                else -> null
            }

        // event_bridge.py emits the field map as `json.dumps({str(k): ...})`,
        // so JSON-keyed lookups need the stringified form of every LXMF field
        // ID. Derived from `LxmfFields` so the source-of-truth values live in
        // one place across both backends.
        val FIELD_TELEMETRY_KEY = LxmfFields.FIELD_TELEMETRY.toString()
        val FIELD_TELEMETRY_STREAM_KEY = LxmfFields.FIELD_TELEMETRY_STREAM.toString()
        val FIELD_ICON_APPEARANCE_KEY = LxmfFields.FIELD_ICON_APPEARANCE.toString()
        val FIELD_CUSTOM_META_KEY = LxmfFields.FIELD_CUSTOM_META.toString()
    }

    private val _announces = MutableSharedFlow<AnnounceEvent>(extraBufferCapacity = 64)
    private val _messages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    // `replay = 8` holds the last few delivery proofs so an IPC subscriber
    // that finishes attaching slightly after a delivery event fires (the
    // post-cold-start window where LXMF may flush queued-message acks
    // before MessagingViewModel re-subscribes through the AIDL pipe) still
    // sees the event. Observed once today: a stamped message sat
    // "pending" in the UI because the proof arrived during the
    // subscribe-gap; the next fresh send hit a fully-subscribed pipe and
    // updated correctly. A small replay window catches that race without
    // adding noise — IPC clients drain it once on attach and ignore
    // already-applied ids via the existing `isTerminalSuccessStatus`
    // guard in `handleDeliveryStatusUpdate`.
    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusUpdate>(
        replay = 8,
        extraBufferCapacity = 64,
    )
    private val _locationTelemetry = MutableSharedFlow<LocationTelemetry>(extraBufferCapacity = 64)
    private val _reactionReceived = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 16)
    private val _links = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)

    val announces: SharedFlow<AnnounceEvent> = _announces.asSharedFlow()
    val messages: SharedFlow<ReceivedMessage> = _messages.asSharedFlow()
    val deliveryStatus: SharedFlow<DeliveryStatusUpdate> = _deliveryStatus.asSharedFlow()
    val locationTelemetry: SharedFlow<LocationTelemetry> = _locationTelemetry.asSharedFlow()
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
     * Outbound try-propagation-on-fail retry sink: `attach_lxmessage_callbacks`
     * fires this when a DIRECT/OPPORTUNISTIC send fails AND
     * `try_propagation_on_fail` was set on the message AND a propagation node
     * is configured — the Sideband pattern that escalates the message to a
     * PROPAGATED re-`handle_outbound` instead of reporting failure. Surfaces
     * as `DeliveryState.RetryingViaPropagation` on the delivery-status flow,
     * matching `NativeMessageSender.installDeliveryCallbacks` on the kotlin
     * backend so the UI need not branch on backend.
     */
    val onLxmfRetryingPropagated = PyEventCallback { payload -> handleLxmfRetryingPropagated(payload) }

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
            // event_bridge._AnnounceHandler already drops announces whose
            // aspect doesn't resolve to one Columba tracks (matching the
            // kotlin backend's RichAnnounceHandler). Re-guard here so the
            // downstream `NodeType.fromAspect` only ever sees known aspects;
            // it falls back to `NodeType.UNKNOWN` for anything else, and
            // those would never match a `Site` / `Peer` / etc. filter chip.
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
                nodeType = NodeType.fromAspect(aspect),
                displayName = displayName,
                stampCost = stampCost,
                stampCostFlexibility = stampFlex,
                peeringCost = peeringCost,
                receivingInterface = payload.dictStr("receiving_interface"),
            )
            _announces.tryEmit(event)
        }.onFailure { Log.e(TAG, "announce translation failed", it) }
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
                deliveryMethod = lxmfMethodName(payload.dictInt("method")),
            )

            // Side-channels always route — independent of the chat-emit
            // decision below. Reaction / telemetry-only frames still
            // need their dedicated flows to fire so the UI's reaction
            // store and location map update.
            if (fieldsJson != null) {
                assembleLocationTelemetry(fieldsJson, sourceHash, message.iconAppearance)
                routeReactionSideChannel(fieldsJson, sourceHash, message.timestamp)
            }

            // Chat emit is gated by the shared predicate
            // `isUserVisibleChatMessage()` in :rns-api/util. Same
            // implementation the Kotlin backend
            // (NativeRnsBackendImpl) calls — adding a new
            // user-visible field touches one place.
            if (message.isUserVisibleChatMessage()) {
                _messages.tryEmit(message)
            } else {
                Log.d(
                    TAG,
                    "Side-channel-only LXMessage from ${sourceHash.joinToString("") { "%02x".format(it) }.take(16)} " +
                        "— skipping chat emission (content blank, no image/file/audio)",
                )
            }
        }.onFailure { Log.e(TAG, "lxmf delivery translation failed", it) }
    }

    /**
     * Decode `FIELD_TELEMETRY` (Telemeter msgpack) + optional
     * `FIELD_CUSTOM_META` (Columba extras msgpack) into a typed
     * `LocationTelemetry` via the shared `TelemeterCodec`. The Python
     * side just hex-encodes the raw bytes through `_jsonable`; all
     * codec work happens Kotlin-side — one implementation shared with
     * `NativeRnsBackendImpl`, no drift between the two backends.
     */
    private fun assembleLocationTelemetry(
        fieldsJson: String,
        sourceHash: ByteArray,
        iconAppearance: IconAppearance?,
    ) {
        runCatching {
            val fields = JSONObject(fieldsJson)

            // FIELD_TELEMETRY_STREAM (0x03) — collector-host response with
            // a list of `[source_hash_bytes, timestamp, packed_telemetry,
            // appearance]` entries. Sent by a group-tracker host in
            // response to a FIELD_COMMANDS telemetry request. Each entry
            // gets its OWN source hash (the original telemetry author),
            // NOT the LXMessage sender (which is the collector relaying
            // for everyone). Re-emit each entry as a separate
            // LocationTelemetry so the UI's per-peer marker logic stays
            // simple — one entry, one peer pin.
            handleTelemetryStream(fields)

            val telemetryHex = fields.optString(FIELD_TELEMETRY_KEY, "")
            if (telemetryHex.isBlank()) return@runCatching
            val telemetryBytes = telemetryHex.hexToBytes() ?: return@runCatching
            val decoded = TelemeterCodec.unpackLocationTelemetry(telemetryBytes)
                ?: return@runCatching
            val meta = fields.optString(FIELD_CUSTOM_META_KEY, "").takeIf { it.isNotBlank() }
                ?.let { it.hexToBytes() }
                ?.let { TelemeterCodec.unpackColumbaMeta(it) }

            // Cease frame short-circuits: the recipient deletes the
            // sender's location regardless of lat/lng (which the cease
            // sender zeros out). Preserve that contract.
            if (meta?.cease == true) {
                _locationTelemetry.tryEmit(
                    LocationTelemetry(
                        type = LocationTelemetry.TYPE_LOCATION_SHARE,
                        lat = 0.0,
                        lng = 0.0,
                        acc = 0f,
                        ts = meta.tsMillis ?: decoded.ts,
                        cease = true,
                        sourceHash = sourceHash.takeIf { it.isNotEmpty() }?.let {
                            // `toHex()` lives in api.util; inline here to avoid
                            // an extra import — sourceHash is just bytes -> hex.
                            it.joinToString("") { b -> "%02x".format(b) }
                        },
                    ),
                )
                return@runCatching
            }

            _locationTelemetry.tryEmit(
                decoded.copy(
                    ts = meta?.tsMillis ?: decoded.ts,
                    expires = meta?.expires,
                    approxRadius = meta?.approxRadius ?: 0,
                    sourceHash = sourceHash.takeIf { it.isNotEmpty() }?.let {
                        it.joinToString("") { b -> "%02x".format(b) }
                    },
                    appearance = iconAppearance,
                ),
            )
        }.onFailure { Log.w(TAG, "location telemetry assembly failed", it) }
    }

    /**
     * Unpack a FIELD_TELEMETRY_STREAM payload into individual
     * [LocationTelemetry] emissions, one per entry. The stream wire
     * format (defined by Sideband, re-used by Columba) is:
     *
     *   [
     *     [source_hash: bytes, timestamp: int, packed_telemetry: bytes,
     *      appearance: [icon_name: str, fg: bytes, bg: bytes] | null],
     *     ...
     *   ]
     *
     * `event_bridge.py:_jsonable` hex-encodes the bytes fields before
     * passing through JSON, so this reads everything as JSON strings /
     * arrays and decodes the hex back out per-entry.
     *
     * Per-entry source_hash is what the map / DB key on, NOT the
     * LXMessage sender (which is the collector relaying everyone's
     * positions). Without this de-aliasing, every received stream entry
     * would overwrite the collector's pin instead of populating each
     * group member's.
     */
    private fun handleTelemetryStream(fields: JSONObject) {
        val streamArr = fields.optJSONArray(FIELD_TELEMETRY_STREAM_KEY) ?: return
        for (i in 0 until streamArr.length()) {
            val entry = streamArr.optJSONArray(i) ?: continue
            parseTelemetryStreamEntry(entry)?.let { _locationTelemetry.tryEmit(it) }
        }
    }

    // Entry layout from event_bridge.py's collector relay:
    //   [0] source_hash (hex-encoded bytes from _jsonable)
    //   [1] timestamp in seconds (Telemeter convention is seconds; we multiply
    //       to ms for the LocationTelemetry.ts contract)
    //   [2] packed Telemeter bytes (hex)
    //   [3] appearance (optional; null or [name, fg_hex, bg_hex])
    private fun parseTelemetryStreamEntry(entry: JSONArray): LocationTelemetry? {
        if (entry.length() < 3) return null
        val entrySourceHex = entry.optString(0, "").takeIf { it.isNotBlank() } ?: return null
        val packedBytes =
            entry.optString(2, "").takeIf { it.isNotBlank() }?.hexToBytes()
        val decoded = packedBytes?.let { TelemeterCodec.unpackLocationTelemetry(it) }
        return decoded?.copy(
            ts = entry.optLong(1, 0L).takeIf { it > 0 }?.let { it * 1000L } ?: decoded.ts,
            sourceHash = entrySourceHex.lowercase(),
            appearance = parseTelemetryStreamAppearance(entry.opt(3)),
        )
    }

    private fun parseTelemetryStreamAppearance(raw: Any?): IconAppearance? =
        when (raw) {
            null, JSONObject.NULL -> null
            is JSONArray -> {
                val parts = (0 until raw.length()).map { raw.opt(it) }
                AppDataParser.parseIconAppearance(parts)
            }
            else -> null
        }

    /**
     * Reaction-channel routing — decodes the canonical `fields[0x40]` (or the
     * legacy `fields[0x10]` fallback) into the normalized reaction JSON via the
     * shared `ReactionWireCodec`, then emits it on `_reactionReceived`.
     *
     * The reactor (`sender`) is derived from the inbound message's
     * [sourceHash] on the canonical path — the standard does not carry it on
     * the wire. Using the shared codec keeps this byte-for-byte identical to
     * the kotlin-native backend's `routeReactionSideChannel`.
     */
    private fun routeReactionSideChannel(fieldsJson: String, sourceHash: ByteArray, timestamp: Long) {
        runCatching {
            ReactionWireCodec.parseInboundReaction(fieldsJson, sourceHash.toHex(), timestamp)
                ?.let { _reactionReceived.tryEmit(it) }
        }.onFailure { Log.w(TAG, "reaction side-channel routing failed", it) }
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
            val arr = JSONObject(fieldsJson).optJSONArray(FIELD_ICON_APPEARANCE_KEY) ?: return@runCatching null
            val list = (0 until arr.length()).map { arr.opt(it) }
            AppDataParser.parseIconAppearance(list)
        }.getOrNull()

    // `routeFieldSideChannels` + `parseLocationTelemetry` were removed
    // when the Telemeter codec consolidated into
    // `network.columba.app.rns.api.util.TelemeterCodec`. Inbound
    // location telemetry now goes through `assembleLocationTelemetry`
    // (called from `handleLxmfDelivery`), which decodes the
    // FIELD_TELEMETRY + FIELD_CUSTOM_META bytes directly via the
    // shared codec — no JSON intermediate, no `event_bridge.py` side
    // assembly. Reactions still go through `routeReactionSideChannel`.

    private fun handleLxmfFailure(payload: PyObject) {
        runCatching {
            _deliveryStatus.tryEmit(
                DeliveryStatusUpdate(
                    messageHash = payload.dictStr("hash").orEmpty(),
                    state = DeliveryState.Failed,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "lxmf failure translation failed", it) }
    }

    private fun handleLxmfDelivered(payload: PyObject) {
        runCatching {
            // Mirrors `NativeMessageSender.installDeliveryCallbacks`: same
            // upstream-LXMF callback fires for both PROPAGATED (success ==
            // "stored on the relay node") and DIRECT/OPPORTUNISTIC (success
            // == "ack from recipient"). The split is by `method`, not state.
            // Without this distinction the UI would render ✓✓ ("delivered")
            // for every PROPAGATED send the moment it lands on the relay,
            // misrepresenting the actual delivery promise.
            val method = payload.dictInt("method") ?: -1
            val desired = payload.dictInt("desired_method") ?: -1
            val state =
                if (method == LXMF_METHOD_PROPAGATED || desired == LXMF_METHOD_PROPAGATED) {
                    DeliveryState.Propagated
                } else {
                    DeliveryState.Delivered
                }
            _deliveryStatus.tryEmit(
                DeliveryStatusUpdate(
                    messageHash = payload.dictStr("hash").orEmpty(),
                    state = state,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "lxmf delivered translation failed", it) }
    }

    private fun handleLxmfRetryingPropagated(payload: PyObject) {
        runCatching {
            // Mirrors NativeMessageSender.installDeliveryCallbacks — same
            // state the kotlin backend emits when a DIRECT send fails and
            // falls back to PROPAGATED.
            _deliveryStatus.tryEmit(
                DeliveryStatusUpdate(
                    messageHash = payload.dictStr("hash").orEmpty(),
                    state = DeliveryState.RetryingViaPropagation,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.e(TAG, "lxmf retrying-propagated translation failed", it) }
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
