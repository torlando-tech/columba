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
        _emit(_on_announce, payload)


def _lxmf_delivery_callback(message):
    """LXMRouter delivery callback. `message` is an upstream LXMessage."""
    try:
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
