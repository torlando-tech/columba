#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
event_bridge.py — the ONE Columba-authored Python module with logic.
====================================================================

Upstream RNS/LXMF fire their callbacks on internal threads that already hold
the GIL. Doing the event fan-out Python-side — translating each event object
into a flat dict of primitives *before* crossing JNI — means exactly one
JNI hop per event instead of one hop per attribute read.

Everything else in this module's package is either upstream wheels or
architecturally-forced RNS.Interface adapters. There is deliberately NO
`rns_*.py` facade: the Kotlin sub-impls in `:rns-backend-py` call upstream
RNS/LXMF directly via PyObject. See this module's CLAUDE.md.

Contract with Kotlin
--------------------
`register_callbacks(...)` is invoked once by `PythonRnsRuntime.wireEventBridge`
after the RNS `Reticulum` instance and `LXMRouter` are live. The `on_*`
arguments are Kotlin objects exposing a single `onEvent(payload)` method
(see `PyEventCallback` on the Kotlin side). Each payload is a `dict` of
JSON-primitive values — bytes are hex-encoded strings so the Kotlin side
never has to reason about jarray vs bytes.

The three per-object callbacks (`on_packet`, `on_link_event`,
`on_lxmf_failure`) are stored and exposed via accessors rather than
registered globally: RNS packet/link callbacks are set per-Destination /
per-Link, and LXMF delivery failure is a per-LXMessage `failed_callback`.
The Kotlin sub-impls attach them at the point they create those objects.
"""

import json
import signal

import RNS

# `_LXMF_METHOD_PROPAGATED` upstream — the int the failure-callback retry
# path assigns to `desired_method` when escalating a failed DIRECT/OPPORTUNISTIC
# send to propagation. Mirrors `LXMF/LXMessage.py` PROPAGATED = 0x03. Inlined
# (rather than `import LXMF`) so this module's slim-Python budget stays tight.
_LXMF_METHOD_PROPAGATED = 0x03

# Telemeter encode/decode used to live here (~190 lines of Columba-
# authored Python). It moved to `rns-api/.../util/TelemeterCodec.kt`
# so both backends — Kotlin-native and Python-Chaquopy — share one
# implementation of the Sideband-interop bit-format. The Python tree
# now stays at "ONE Columba-authored file with logic" per
# rns-backend-py's CLAUDE.md slim-Python rule.


# ----------------------------------------------------------------------------
# Android/Chaquopy environment patches. Applied once by PythonRnsRuntime before
# the first RNS.Reticulum() is constructed — see apply_android_env_patches().
# ----------------------------------------------------------------------------

def apply_android_env_patches():
    """Make upstream RNS/LXMF safe to construct off Python's main thread.

    `RNS.Reticulum.__init__` ends by registering SIGINT/SIGTERM handlers via
    `signal.signal()`. Under Chaquopy every RNS call runs on a `Dispatchers.IO`
    thread, and CPython's `signal.signal()` raises
    `ValueError: signal only works in main thread of the main interpreter`
    off the main thread — which aborts `Reticulum.__init__` *after* Transport
    and the interfaces are already up but *before* it returns, so the Kotlin
    side never sees a constructed instance.

    On Android those handlers are dead weight anyway: the process lifecycle is
    owned by the foreground `ReticulumService`, not POSIX signals to the
    embedded interpreter. Shutdown goes through `PythonRnsRuntime.stop()` ->
    `Reticulum.exit_handler()`. So wrap `signal.signal()` to no-op when it
    would raise off-thread, while still honouring it if ever called on the
    main thread. Idempotent.
    """
    if getattr(signal, "_columba_android_patched", False):
        return
    _real_signal = signal.signal

    def _safe_signal(sig, handler):
        try:
            return _real_signal(sig, handler)
        except ValueError:
            # Off the main thread — the handler can't be registered, and
            # SIGINT/SIGTERM are a desktop-shutdown concept that does not
            # apply to an Android foreground-service-hosted interpreter.
            RNS.log(
                "event_bridge: skipped off-main-thread signal handler "
                f"registration for {sig}",
                RNS.LOG_DEBUG,
            )
            return None

    signal.signal = _safe_signal
    signal._columba_android_patched = True


def reset_reticulum_for_restart():
    """Reset RNS.Reticulum + RNS.Transport process-global state so a fresh
    Reticulum() can be constructed after a stop — Columba's "Apply & Restart".

    RNS is built as a desktop daemon: Reticulum and Transport keep singleton +
    global state in class attributes and never reset them (the OS process is
    expected to exit). Columba restarts the RNS stack in-process, so without
    this:
      - the second `Reticulum()` raises
        `OSError("Attempt to reinitialise Reticulum, when it was already
        running")` because `Reticulum.__instance` is still set;
      - `LXMRouter.register_delivery_identity()` raises
        `KeyError("Attempt to register an already registered destination")`
        because `Transport.destinations` still holds the first run's delivery
        destination.

    This ports the RNS-state-clearing step of `release/v0.10.x`'s
    `reticulum_wrapper.shutdown()` — the validated reference. Every attribute is
    `hasattr`/`getattr`-guarded so it stays correct across RNS versions. Call
    from `PythonRnsRuntime.stop()` AFTER `Reticulum.exit_handler()` (which has
    already detached interfaces + persisted path data). RNS daemon threads are
    not stopped here — they exit their loops once `Transport._should_run` is
    False (set by `exit_handler()`); a fresh `Reticulum()` starts new ones.
    """
    reticulum = RNS.Reticulum
    transport = RNS.Transport

    # Reticulum singleton + one-shot exit guards. Without the __instance reset
    # the next Reticulum() raises OSError; without the *_ran resets a second
    # exit_handler() would no-op and skip interface detach + persist.
    reticulum._Reticulum__instance = None
    reticulum._Reticulum__exit_handler_ran = False
    reticulum._Reticulum__interface_detach_ran = False

    # Transport global state. `owner` + the registries below are what make a
    # second Reticulum() / register_delivery_identity() fail if left stale.
    if hasattr(transport, "owner"):
        transport.owner = None
    for attr in (
        "interfaces",
        "local_client_interfaces",
        "local_client_rssi_cache",
        "local_client_snr_cache",
        "local_client_q_cache",
        "destinations",
        "destination_table",
        "announce_table",
        "held_announces",
        "announce_handlers",
    ):
        container = getattr(transport, attr, None)
        if container is not None:
            try:
                container.clear()
            except Exception as e:  # noqa: BLE001 — best-effort per-attr
                RNS.log(
                    f"event_bridge: couldn't clear Transport.{attr}: {e}",
                    RNS.LOG_DEBUG,
                )

    import gc

    gc.collect()
    RNS.log("event_bridge: reset Reticulum + Transport state for restart", RNS.LOG_DEBUG)


# ----------------------------------------------------------------------------
# Module-level callback storage. Populated by register_callbacks().
# ----------------------------------------------------------------------------
_on_announce = None
_on_packet = None
_on_link_event = None
_on_lxmf_delivery = None
_on_lxmf_failure = None

_rns_transport = None
_lxmf_router = None
_announce_handler = None


def _hex(b):
    """bytes / Chaquopy jarray -> lowercase hex str. None passes through."""
    if b is None:
        return None
    if isinstance(b, str):
        return b
    return bytes(b).hex()


# The Telemeter codec (pack_telemetry_location, pack_columba_meta,
# unpack_telemetry_location, unpack_columba_meta,
# _assemble_location_telemetry_json, _format_icon_appearance) was
# removed when the codec moved to
# `rns-api/.../util/TelemeterCodec.kt`. Both backends share that one
# implementation now — see the rationale block above this section.
# Inbound `FIELD_TELEMETRY` bytes ride through `_jsonable` (hex-encoded)
# and `PythonEventBridge.assembleLocationTelemetry` calls the shared
# Kotlin codec to decode them; outbound `FIELD_TELEMETRY` arrives
# already-packed from `PythonRnsTelemetry.sendLocationTelemetry`.

def _jsonable(v):
    """Recursively coerce an LXMF field value into a JSON-encodable form.

    bytes / Chaquopy jarray -> hex str; containers recurse; primitives pass
    through; anything else falls back to str().
    """
    if isinstance(v, (bytes, bytearray)):
        return v.hex()
    if v is None or isinstance(v, (str, int, float, bool)):
        return v
    if isinstance(v, dict):
        return {str(k): _jsonable(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_jsonable(x) for x in v]
    try:
        return bytes(v).hex()  # Chaquopy jarray('B') from a Kotlin ByteArray
    except Exception:  # noqa: BLE001
        return str(v)


def _emit(callback, payload):
    """Invoke a Kotlin onEvent callback, swallowing exceptions.

    A raised exception here would propagate up an RNS/LXMF internal thread
    and can wedge the stack — the Kotlin side is responsible for its own
    error handling, so a failure to dispatch is logged and dropped.
    """
    if callback is None:
        return
    try:
        callback.onEvent(payload)
    except Exception as e:  # noqa: BLE001 — must not escape onto the RNS thread
        RNS.log(f"event_bridge: onEvent dispatch failed: {e}", RNS.LOG_ERROR)


# Aspects Columba tracks. RNS's announce handler with `aspect_filter = None`
# receives every announce but is not told which aspect matched, so we resolve
# it by recomputing the destination hash for each known aspect — pure RNS
# protocol code, no Columba app-logic.
_KNOWN_ASPECTS = ("lxmf.delivery", "lxmf.propagation", "nomadnetwork.node", "lxst.telephony")


def _resolve_aspect(destination_hash, identity):
    """Which known aspect produced this announce, or None if not one we track."""
    if identity is None:
        return None
    for aspect in _KNOWN_ASPECTS:
        try:
            if RNS.Destination.hash_from_name_and_identity(aspect, identity) == destination_hash:
                return aspect
        except Exception:  # noqa: BLE001
            continue
    return None


def _announce_enrichment(destination_hash, identity, app_data):
    """Python-only enrichment for an announce: matched aspect + current hops.

    The kotlin event-bridge side derives display name + stamp costs from the
    raw `app_data` bytes via the shared `network.columba.app.rns.api.util.
    AppDataParser` (same parser the native kotlin backend uses), so neither
    field is computed here — keeping the parsing in one place means the two
    backends cannot drift on its rules.

    What stays Python-side: `aspect` requires `RNS.Destination.
    hash_from_name_and_identity` (which needs the Python identity object),
    and `hops` requires `RNS.Transport.hops_to` (Python-only Transport state).
    `app_data` is unused here but kept on the parameter list so callers don't
    need to special-case unrelated aspects.
    """
    enrichment = {
        "aspect": None,
        "hops": 0,
    }
    try:
        enrichment["aspect"] = _resolve_aspect(destination_hash, identity)

        hops = RNS.Transport.hops_to(destination_hash)
        # PATHFINDER_M is RNS's "hop count unknown" sentinel — surface 0 instead.
        enrichment["hops"] = 0 if hops == RNS.Transport.PATHFINDER_M else hops
    except Exception as e:  # noqa: BLE001 — enrichment is best-effort, never fatal
        RNS.log(f"event_bridge: announce enrichment failed: {e}", RNS.LOG_DEBUG)
    return enrichment


class _AnnounceHandler:
    """RNS announce handler — `aspect_filter = None` catches every aspect.

    RNS calls `received_announce` on the Transport thread; we resolve the
    aspect, and — for the four aspects Columba tracks — flatten and hand the
    announce off to Kotlin. Announces for any other aspect are dropped (see
    `received_announce`).
    """

    aspect_filter = None
    receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash=None):
        enrichment = _announce_enrichment(destination_hash, announced_identity, app_data)
        # Only surface announces for aspects Columba tracks. This matches the
        # kotlin backend's RichAnnounceHandler, which returns False (drops the
        # announce) when no known aspect matches. `aspect_filter = None` above
        # means RNS hands us *every* announce, including ones from unrelated
        # RNS apps — without this drop those leak into the announce stream with
        # a junk display name (whatever their app_data decodes to) and an
        # "unknown" aspect, and collide into the "Site" filter.
        if enrichment.get("aspect") is None:
            return
        payload = {
            "destination_hash": _hex(destination_hash),
            "identity_hash": _hex(announced_identity.hash) if announced_identity is not None else None,
            "public_key": _hex(announced_identity.get_public_key()) if announced_identity is not None else None,
            "app_data": _hex(app_data),
            "announce_packet_hash": _hex(announce_packet_hash),
        }
        payload.update(enrichment)
        _emit(_on_announce, payload)


# Inbound LXMF message-size cap (KB; 0 = unlimited). Set from Kotlin via
# set_incoming_message_size_limit(); enforced post-reassembly in
# _lxmf_delivery_callback().
_incoming_message_size_limit_kb = 0


def set_incoming_message_size_limit(limit_kb):
    """Set the inbound LXMF message-size cap (KB; 0 = unlimited).

    Upstream LXMF has no inbound size limit of its own — `message_storage_limit`
    bounds a propagation *node's* served store, not inbound delivery, so calling
    it for this would be wrong. The lxmf-kt port (kotlin backend) has a real
    `incomingMessageSizeLimitKb`; this is the Python-backend equivalent.

    Enforcement is a *post-reassembly* drop in `_lxmf_delivery_callback`: LXMF
    fully reassembles a message before invoking its delivery callback, so an
    oversized message is rejected before it reaches the Columba UI / storage,
    but the bandwidth + CPU of receiving it cannot be saved — upstream LXMF
    exposes no pre-reassembly hook. This degradation-vs-kotlin is recorded in
    the RNS dual-build handoff doc.
    """
    global _incoming_message_size_limit_kb
    _incoming_message_size_limit_kb = max(0, int(limit_kb))
    RNS.log(
        "event_bridge: incoming message size limit set to "
        f"{_incoming_message_size_limit_kb or 'unlimited'} KB",
        RNS.LOG_DEBUG,
    )


def _signal_metrics(interface_obj):
    """Extract (rssi, snr) from a receiving `RNS.Interface` at delivery time.

    Ports release/v0.10.x's `signal_quality.extract_signal_metrics` — RNode-class
    interfaces (`RNodeInterface`, `RNodeMultiInterface`) expose `get_rssi()` +
    `get_snr()`; TCP / Auto / Backbone interfaces don't, so the result is
    `(None, None)` for those (same null-when-unavailable shape the kotlin
    backend produces for non-RNode paths).

    BLE per-peer RSSI lookup (v0.10.x's `BLEPeerInterface` -> `parent.driver.
    get_peer_rssi(peer_address)` special case) is intentionally NOT ported here:
    BLE-on-Python interface deployment is a separate task — once a Columba
    BLE driver lands Python-side, this helper grows the `BLEPeerInterface`
    branch then.

    Best-effort: any attribute-access / call exception falls back to `None`,
    because a signal-metrics failure must not wedge inbound message delivery.
    """
    rssi = None
    snr = None
    if interface_obj is None:
        return rssi, snr
    try:
        if hasattr(interface_obj, "get_rssi"):
            v = interface_obj.get_rssi()
            if v is not None:
                rssi = int(v)
    except Exception as e:  # noqa: BLE001
        RNS.log(f"event_bridge: get_rssi failed: {e}", RNS.LOG_DEBUG)
    try:
        if hasattr(interface_obj, "get_snr"):
            v = interface_obj.get_snr()
            if v is not None:
                snr = float(v)
    except Exception as e:  # noqa: BLE001
        RNS.log(f"event_bridge: get_snr failed: {e}", RNS.LOG_DEBUG)
    return rssi, snr


def _lxmf_delivery_callback(message):
    """LXMRouter delivery callback. `message` is an upstream LXMessage."""
    try:
        # Post-reassembly inbound size cap — see set_incoming_message_size_limit().
        if _incoming_message_size_limit_kb > 0:
            packed = getattr(message, "packed", None)
            size = len(packed) if packed else len(getattr(message, "content", b"") or b"")
            if size > _incoming_message_size_limit_kb * 1024:
                RNS.log(
                    f"event_bridge: dropping inbound LXMF message ({size} B > "
                    f"{_incoming_message_size_limit_kb} KB limit)",
                    RNS.LOG_WARNING,
                )
                return

        # Field keys are ints (LXMF FIELD_* constants). JSON-encode the whole
        # field map Python-side — bytes become hex strings — so the Kotlin
        # side gets one `fields_json` string instead of a JNI hop per value.
        #
        # FIELD_TELEMETRY (0x02) used to get pre-assembled into a
        # Columba JSON shape here; that work moved to
        # `rns-api/.../util/TelemeterCodec.kt` and
        # `PythonEventBridge.assembleLocationTelemetry` decodes the
        # raw msgpack bytes Kotlin-side. The Python tree now passes
        # FIELD_TELEMETRY through `_jsonable` like any other binary
        # field — hex-encoded for the JNI hop, no Telemeter awareness.
        fields_json = None
        if getattr(message, "fields", None):
            fields_json = json.dumps(
                {str(k): _jsonable(v) for k, v in message.fields.items()}
            )
        # Receiving-interface annotation + signal metrics. Two sources, in
        # priority order (mirrors release/v0.10.x's _on_lxmf_delivery):
        #   1. torlando-tech LXMF fork (branch feature/receiving-interface-capture)
        #      sets `message.receiving_interface` (RNS.Interface) +
        #      `message.receiving_hops` on inbound opportunistic messages.
        #      Upstream LXMF leaves these off entirely, so getattr-guard.
        #   2. Fallback to `RNS.Transport.path_table[source_hash][5]` for
        #      link-based deliveries (DIRECT method) and for any case where
        #      the LXMF annotation didn't fire — every reachable destination
        #      has a path_table entry whose index 5 is the receiving interface
        #      object. Hops independently fall back to `RNS.Transport.hops_to`.
        recv_iface = getattr(message, "receiving_interface", None)
        recv_hops = getattr(message, "receiving_hops", None)
        source_hash_bytes = getattr(message, "source_hash", None)
        if (recv_iface is None or recv_hops is None) and source_hash_bytes is not None:
            try:
                if recv_iface is None:
                    path_entry = RNS.Transport.path_table.get(source_hash_bytes)
                    if path_entry is not None and len(path_entry) > 5:
                        recv_iface = path_entry[5]
                if recv_hops is None:
                    h = RNS.Transport.hops_to(source_hash_bytes)
                    if h != RNS.Transport.PATHFINDER_M:
                        recv_hops = h
            except Exception as e:  # noqa: BLE001 — fallback is best-effort
                RNS.log(
                    f"event_bridge: path_table fallback failed: {e}",
                    RNS.LOG_DEBUG,
                )

        # Resolve the interface object to its configured short name for
        # parity with `PythonRnsTransportAdmin` (which keys by `iface["name"]`)
        # and with the kotlin backend's `Transport.getInterfaces()...name`.
        recv_iface_name = None
        if recv_iface is not None:
            recv_iface_name = getattr(recv_iface, "name", None) or str(recv_iface)

        rssi, snr = _signal_metrics(recv_iface)

        payload = {
            "hash": _hex(getattr(message, "hash", None)),
            "source_hash": _hex(getattr(message, "source_hash", None)),
            "destination_hash": _hex(getattr(message, "destination_hash", None)),
            "title": message.title.decode("utf-8", "replace") if getattr(message, "title", None) else "",
            "content": message.content.decode("utf-8", "replace") if getattr(message, "content", None) else "",
            "timestamp": getattr(message, "timestamp", None),
            "signature_validated": bool(getattr(message, "signature_validated", False)),
            "stamp_valid": bool(getattr(message, "stamp_valid", False)),
            "method": getattr(message, "method", None),
            "fields_json": fields_json,
            "receiving_interface": recv_iface_name,
            "receiving_hops": recv_hops,
            "rssi": rssi,
            "snr": snr,
        }
        _emit(_on_lxmf_delivery, payload)
    except Exception as e:  # noqa: BLE001
        RNS.log(f"event_bridge: lxmf delivery translation failed: {e}", RNS.LOG_ERROR)


def register_callbacks(
    rns_transport,
    lxmf_router,
    on_announce,
    on_packet,
    on_link_event,
    on_lxmf_delivery,
    on_lxmf_failure,
):
    """Wire the Kotlin event sinks into upstream RNS/LXMF.

    Called once at backend init. `rns_transport` is `RNS.Transport` (the
    class, used statically by RNS) and `lxmf_router` is the live LXMRouter.
    """
    global _on_announce, _on_packet, _on_link_event, _on_lxmf_delivery, _on_lxmf_failure
    global _rns_transport, _lxmf_router, _announce_handler

    _on_announce = on_announce
    _on_packet = on_packet
    _on_link_event = on_link_event
    _on_lxmf_delivery = on_lxmf_delivery
    _on_lxmf_failure = on_lxmf_failure
    _rns_transport = rns_transport
    _lxmf_router = lxmf_router

    # Global registrations: announce handler + LXMF delivery callback.
    _announce_handler = _AnnounceHandler()
    RNS.Transport.register_announce_handler(_announce_handler)

    if lxmf_router is not None:
        lxmf_router.register_delivery_callback(_lxmf_delivery_callback)

    RNS.log("event_bridge: callbacks registered", RNS.LOG_DEBUG)


def deregister_callbacks():
    """Tear-down counterpart to register_callbacks(). Best-effort."""
    global _announce_handler
    try:
        if _announce_handler is not None and _rns_transport is not None:
            RNS.Transport.deregister_announce_handler(_announce_handler)
    except Exception as e:  # noqa: BLE001
        RNS.log(f"event_bridge: deregister failed: {e}", RNS.LOG_ERROR)
    _announce_handler = None


# --- LXST telephony callback bridges ---------------------------------------
# Per-Destination / per-Link callbacks that upstream RNS invokes on its
# internal threads. Kotlin objects aren't directly callable from Python, so
# these wrap a Kotlin callback as a Python closure with the signature
# upstream expects (same pattern as make_link_packet_handler above).


def make_link_established_handler(on_established):
    """`RNS.Destination.set_link_established_callback(cb)` — `cb(link)`."""
    def _handler(link):
        try:
            on_established.onEvent(link)
        except Exception as e:  # noqa: BLE001 — must not escape onto the RNS thread
            RNS.log(f"event_bridge: link-established dispatch failed: {e}", RNS.LOG_ERROR)
    return _handler


def make_remote_identified_handler(on_identified):
    """`RNS.Link.set_remote_identified_callback(cb)` — `cb(link, identity)`."""
    def _handler(link, identity):
        try:
            on_identified.onEvent(link, identity)
        except Exception as e:  # noqa: BLE001 — must not escape onto the RNS thread
            RNS.log(f"event_bridge: remote-identified dispatch failed: {e}", RNS.LOG_ERROR)
    return _handler


def make_link_closed_handler(on_closed):
    """`RNS.Link.set_link_closed_callback(cb)` — `cb(link)`."""
    def _handler(link):
        try:
            on_closed.onEvent(link)
        except Exception as e:  # noqa: BLE001 — must not escape onto the RNS thread
            RNS.log(f"event_bridge: link-closed dispatch failed: {e}", RNS.LOG_ERROR)
    return _handler


# --- LXMF external stamp generator bridge ----------------------------------

def install_external_stamp_generator(java_callback):
    """Register a Java callback as upstream LXMF's external stamp generator.

    Bypasses Python LXMF's `multiprocessing.Manager` based stamp pipeline
    which hangs on Android (Chaquopy lacks `sem_open`, Android kills idle
    helper processes, the Manager deadlocks waiting on the dead worker).
    The torlando-tech LXMF fork exposes `LXStamper.set_external_generator`
    for exactly this — when set, `LXStamper.generate_stamp` calls
    `external_generator(workblock, stamp_cost)` and gets `(stamp_bytes, rounds)`
    back.

    Why this wrapper instead of registering the Kotlin callback directly:
    Chaquopy's `JavaObject.__call__` dispatcher on `BiFunction.apply`
    boxes the args as `Object[]` instead of typed `byte[] / int` —
    LXStamper line 111 then throws
    `ClassCastException: Object[] cannot be cast to byte[]` inside the
    synthetic apply, and the deferred-stamp worker thread dies. By going
    through a Python closure that calls `java_callback.generate(...)`
    via Chaquopy's typed-method path (not the SAM-callable path),
    Chaquopy applies the proper `bytes → byte[]` / `int → int`
    conversion before dispatching.

    Kotlin contract: `java_callback` exposes a method named `generate`
    with signature `(workblock: byte[], stampCost: int) -> List` where
    the returned list is `[stamp_bytes, rounds]`. See
    `PythonRnsRuntime.kt` `StampGeneratorCallback` for the implementation.
    """
    try:
        from LXMF import LXStamper
    except ImportError:
        RNS.log(
            "event_bridge: install_external_stamp_generator failed — "
            "LXMF.LXStamper not importable",
            RNS.LOG_ERROR,
        )
        return

    def _wrapper(workblock, stamp_cost):
        result = java_callback.generate(workblock, stamp_cost)
        # `result` is a Java List exposed as a Python sequence; index access
        # returns Python bytes for index 0 and Python int for index 1
        # (Chaquopy auto-converts via the typed method's return signature).
        stamp = result[0]
        rounds = result[1]
        return stamp, rounds

    LXStamper.set_external_generator(_wrapper)


# --- Per-Link packet bridge ------------------------------------------------

def make_link_packet_handler(on_packet):
    """Wrap a Kotlin `PyEventCallback` as an `RNS.Link` packet callback.

    `RNS.Link.set_packet_callback` expects a `callback(message, packet)`
    callable and invokes it on a fresh thread with `message` = the decrypted
    plaintext bytes. Kotlin objects are not directly callable from Python, so
    this returns a Python closure that forwards `message` to
    `on_packet.onEvent(message)`. The Kotlin side (`PythonNetworkTransport`)
    demuxes single-byte LXST signalling frames from multi-byte audio frames.

    This is the LXST-voice sibling of `register_callbacks`' per-object
    callbacks — used by `:rns-host`'s `PythonNetworkTransport` to bridge an
    `RNS.Link`'s inbound packets into lxst-kt's `PacketRouter`.
    """
    def _handler(message, packet):  # noqa: ARG001 — `packet` unused; RNS passes it
        _emit(on_packet, message)
    return _handler


# --- Per-LXMessage outbound delivery / failure -----------------------------

def attach_lxmessage_callbacks(
    lxmessage,
    on_delivered,
    on_failed,
    on_retrying_propagated=None,
    try_propagation_on_fail=False,
):
    """Wire per-LXMessage delivery + failure callbacks for an outbound message.

    LXMF tracks outbound delivery and failure *per-LXMessage*, not router-wide:
    `LXMessage.register_delivery_callback` fires when the message's delivery
    proof arrives (the packet proof for an OPPORTUNISTIC send, the link ack for
    a DIRECT send), `register_failed_callback` when it fails. Both are invoked
    as `callback(lxmessage)`.

    `PythonRnsLxmf` calls this right before `handle_outbound()` for every
    message it sends — without it the Kotlin side never learns a sent message
    was delivered or failed, so the delivery-status flow stays silent (no
    delivery proofs surface in the UI). Success vs failure is implied by which
    Kotlin `PyEventCallback` fires; the payload is a flat `{hash}` dict.

    **try_propagation_on_fail (Sideband pattern)** — when set, the LXMessage
    is tagged so the failed-callback rebuilds it as PROPAGATED and re-routes
    it through `LXMRouter.handle_outbound` instead of reporting failure. This
    mirrors Sideband's `core.message_notification` retry block (see
    `Sideband/sbapp/sideband/core.py:4440`) and the `release/v0.10.x`
    `reticulum_wrapper._on_message_failed` port. The retry fires
    `on_retrying_propagated` (so the UI can show "retrying via propagation"
    status); the second failure — after the propagation retry attempt — fires
    `on_failed` for real, mirroring upstream's "give up after one retry"
    behaviour. Requires a configured outbound propagation node; falls through
    to plain failure when none is set.
    """
    def _delivered(msg):
        msg_hash_hex = _hex(getattr(msg, "hash", None))
        RNS.log(
            f"event_bridge: _delivered fired for {msg_hash_hex}",
            RNS.LOG_DEBUG,
        )
        _emit(on_delivered, {"hash": msg_hash_hex})

    def _failed(msg):
        # Sideband pattern: if try_propagation_on_fail was set on the message
        # AND we haven't already retried AND a propagation node is configured,
        # rebuild as PROPAGATED and re-submit through the router. Otherwise
        # report failure to Kotlin.
        if (
            getattr(msg, "try_propagation_on_fail", False)
            and not getattr(msg, "_columba_propagation_retry_attempted", False)
            and _lxmf_router is not None
            and getattr(_lxmf_router, "outbound_propagation_node", None) is not None
            and getattr(msg, "desired_method", None) != _LXMF_METHOD_PROPAGATED
        ):
            msg._columba_propagation_retry_attempted = True
            # Clear retry flag so a second failure doesn't loop.
            msg.try_propagation_on_fail = False
            # Sideband resets the upstream-LXMF send-state for a fresh try as
            # PROPAGATED. Skipping any of these wedges the send: stale packed
            # bytes, stale propagation stamp, or a non-zero delivery_attempts
            # count would short-circuit upstream's outbound state machine.
            msg.delivery_attempts = 0
            msg.packed = None
            msg.propagation_packed = None
            msg.propagation_stamp = None
            msg.defer_propagation_stamp = True
            msg.desired_method = _LXMF_METHOD_PROPAGATED
            try:
                _lxmf_router.handle_outbound(msg)
                _emit(on_retrying_propagated, {"hash": _hex(getattr(msg, "hash", None))})
                RNS.log(
                    "event_bridge: DIRECT delivery failed, retrying via propagation node",
                    RNS.LOG_DEBUG,
                )
                return
            except Exception as e:  # noqa: BLE001
                RNS.log(
                    f"event_bridge: propagation retry failed: {e}",
                    RNS.LOG_ERROR,
                )
                # Fall through to plain failure reporting.

        _emit(on_failed, {"hash": _hex(getattr(msg, "hash", None))})

    # Tag the LXMessage so the failed callback can see the intent. Upstream
    # Sideband uses this same attribute; LXMF does not read it itself, so the
    # tag is benign on stock upstream and only consumed by this callback.
    if try_propagation_on_fail:
        try:
            lxmessage.try_propagation_on_fail = True
        except Exception as e:  # noqa: BLE001
            RNS.log(
                f"event_bridge: could not tag try_propagation_on_fail: {e}",
                RNS.LOG_DEBUG,
            )

    lxmessage.register_delivery_callback(_delivered)
    lxmessage.register_failed_callback(_failed)


# --- Accessors for the per-object callbacks -------------------------------
# RNS packet/link callbacks are set per-Destination / per-Link, and LXMF
# delivery failure is a per-LXMessage `failed_callback`. The Kotlin sub-impls
# fetch these and attach them where they construct the relevant objects.

def packet_callback():
    return _on_packet


def link_event_callback():
    return _on_link_event


def lxmf_failure_callback():
    return _on_lxmf_failure


# --- NomadNet request-response capture ------------------------------------
# Upstream `Resource.assemble` closes the temp file backing
# `RequestReceipt.response` synchronously after the response callback returns
# — a later read from Kotlin's poll loop sees
# `ValueError: I/O operation on closed file`. The fix has to be Python-side
# because `receipt.response` for a file-response is a Python file object, and
# only Python can call `.read()` on it before upstream closes it.
#
# Everything else (state, polling, error mapping) lives in
# `PythonRnsNomadnet.kt`. This helper is the minimum-viable Python:
# a dict holding the snapshot + two closures suitable for
# `Link.request(response_callback=..., failed_callback=...)`.

def make_nomadnet_response_capture():
    # SimpleNamespace exposes `cap.done`-style attribute access — Chaquopy's
    # `pyObject["key"]` from Kotlin maps to `__getattr__`, NOT `__getitem__`.
    # A plain dict here would silently read null on every Kotlin-side lookup
    # and the poll loop would never see completion.
    from types import SimpleNamespace
    cap = SimpleNamespace(
        done=False,
        response_bytes=None,
        metadata_bytes=None,
        error=None,
    )

    def _on_response(receipt):
        try:
            r = getattr(receipt, "response", None)
            if hasattr(r, "read"):
                cap.response_bytes = r.read()
            elif isinstance(r, (bytes, bytearray)):
                cap.response_bytes = bytes(r)
            else:
                cap.response_bytes = b"" if r is None else bytes(str(r), "utf-8")
            m = getattr(receipt, "metadata", None)
            if isinstance(m, (bytes, bytearray)):
                cap.metadata_bytes = bytes(m)
            elif m is not None:
                import umsgpack
                cap.metadata_bytes = umsgpack.packb(m)
        except Exception as e:  # noqa: BLE001
            cap.error = str(e)
        finally:
            cap.done = True

    def _on_failed(receipt):
        cap.error = "request_failed"
        cap.done = True

    cap.on_response = _on_response
    cap.on_failed = _on_failed
    return cap
