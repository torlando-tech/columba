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
import RNS.vendor.umsgpack as msgpack
import LXMF


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
    """RNS/LXMF protocol-leaf enrichment for an announce.

    Returns the matched aspect, current hop count, and the upstream-LXMF-parsed
    display name + stamp costs. All of this is upstream RNS/LXMF protocol code
    (`LXMF.display_name_from_app_data` / `stamp_cost_from_app_data` /
    `pn_*_from_app_data`, `RNS.Transport.hops_to`) — no Columba app-logic — done
    Python-side so the Kotlin event bridge gets one flat dict instead of a JNI
    hop per derived field. `NodeType` is derived Kotlin-side from `aspect`.
    """
    enrichment = {
        "aspect": None,
        "hops": 0,
        "display_name": None,
        "stamp_cost": None,
        "stamp_cost_flexibility": None,
        "peering_cost": None,
    }
    try:
        aspect = _resolve_aspect(destination_hash, identity)
        enrichment["aspect"] = aspect

        hops = RNS.Transport.hops_to(destination_hash)
        # PATHFINDER_M is RNS's "hop count unknown" sentinel — surface 0 instead.
        enrichment["hops"] = 0 if hops == RNS.Transport.PATHFINDER_M else hops

        if app_data:
            if aspect == "lxmf.propagation":
                enrichment["display_name"] = LXMF.pn_name_from_app_data(app_data)
                enrichment["stamp_cost"] = LXMF.pn_stamp_cost_from_app_data(app_data)
                # data[5] is [target_cost, flexibility, peering]; upstream LXMF
                # only exposes [0] via pn_stamp_cost_from_app_data — read the
                # rest off the same validated structure for kotlin-backend parity.
                if LXMF.pn_announce_data_is_valid(app_data):
                    costs = msgpack.unpackb(app_data)[5]
                    if isinstance(costs, list):
                        if len(costs) > 1:
                            enrichment["stamp_cost_flexibility"] = costs[1]
                        if len(costs) > 2:
                            enrichment["peering_cost"] = costs[2]
            elif aspect == "nomadnetwork.node":
                # NomadNet node app_data is "name:..." UTF-8.
                name = app_data.decode("utf-8", "replace").split(":", 1)[0]
                enrichment["display_name"] = name or None
            else:
                # lxmf.delivery + any unresolved aspect: peer announce format.
                enrichment["display_name"] = LXMF.display_name_from_app_data(app_data)
                enrichment["stamp_cost"] = LXMF.stamp_cost_from_app_data(app_data)
    except Exception as e:  # noqa: BLE001 — enrichment is best-effort, never fatal
        RNS.log(f"event_bridge: announce enrichment failed: {e}", RNS.LOG_DEBUG)
    return enrichment


class _AnnounceHandler:
    """RNS announce handler — `aspect_filter = None` catches every aspect.

    RNS calls `received_announce` on the Transport thread; we flatten and
    hand off to Kotlin immediately.
    """

    aspect_filter = None
    receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash=None):
        payload = {
            "destination_hash": _hex(destination_hash),
            "identity_hash": _hex(announced_identity.hash) if announced_identity is not None else None,
            "public_key": _hex(announced_identity.get_public_key()) if announced_identity is not None else None,
            "app_data": _hex(app_data),
            "announce_packet_hash": _hex(announce_packet_hash),
        }
        payload.update(_announce_enrichment(destination_hash, announced_identity, app_data))
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
        fields_json = None
        if getattr(message, "fields", None):
            fields_json = json.dumps(
                {str(k): _jsonable(v) for k, v in message.fields.items()}
            )
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
