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

import LXMF
import RNS

# Sideband FIELD_COMMANDS sub-command IDs. NOT in upstream LXMF — these
# are Sideband-specific command identifiers carried inside an LXMF
# FIELD_COMMANDS frame; the wire format is `[{cmd_id: [args...]}, ...]`.
_COMMAND_TELEMETRY_REQUEST = 0x01

# How long collected telemetry entries are kept on the host before
# eviction. Mirrors `release/v0.10.x`'s `telemetry_retention_seconds`
# (24h) — long enough to survive infrequent group-tracker sync cycles,
# short enough that stale fixes don't accumulate forever.
_COLLECTOR_RETENTION_SECONDS = 24 * 60 * 60

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

    # Bump TCPConnection.CONNECT_TIMEOUT for the Android RNodeInterface.
    # Upstream hardcodes both to 5.0s as a class constant
    # (RNS/Interfaces/Android/RNodeInterface.py:1871-1872). T-Beam-class
    # RNodes (e.g. T-Beam Supreme on firmware 1.85) running their own
    # WiFi-hosted TCP listener can take 6-10s to SYN+ACK on first
    # connect — slower than upstream's desktop-tuned default, fast
    # enough that 15s is plenty. Without this bump the phone gives up
    # before the T-Beam responds and the interface logs
    # "TCP connection ... could not be established: timed out" on a
    # reachable + listening RNode. Idempotent: re-imports of the
    # interface module reuse the patched class.
    try:
        import RNS.Interfaces.Android.RNodeInterface as _android_rnode_iface
        _android_rnode_iface.TCPConnection.CONNECT_TIMEOUT = 15.0
        _android_rnode_iface.TCPConnection.INITIAL_CONNECT_TIMEOUT = 15.0
    except Exception as e:  # noqa: BLE001
        RNS.log(
            f"event_bridge: failed to bump Android RNodeInterface TCP "
            f"connect timeout (non-fatal): {e}",
            RNS.LOG_WARNING,
        )


# Slot for the KotlinBLEBridge instance. Populated by Kotlin via
# `set_ble_bridge(...)` after the bridge is constructed; consulted by
# `ble_modules.android_ble_driver._get_kotlin_bridge()` at driver start.
# Single-process state, so a module-level reference is sufficient.
_ble_bridge = None


def set_ble_bridge(bridge):
    """Hand a KotlinBLEBridge instance to the Python-side BLE driver.

    Replaces the legacy v0.10.x `reticulum_wrapper.set_ble_bridge` accessor.
    The bridge must be set before the AndroidBLE interface initialises (i.e.
    before `Reticulum()` is constructed) or the driver start path will fail
    with `Failed to get KotlinBLEBridge`.
    """
    global _ble_bridge
    _ble_bridge = bridge


def get_ble_bridge():
    return _ble_bridge


# Slot for the KotlinRNodeBridge instance (Classic SPP / BLE GATT to RNode
# hardware). Populated by Kotlin via `set_rnode_bridge(...)` at runtime
# start, consulted by `columba_rnode_interface.ColumbaRNodeInterface.
# _get_kotlin_bridge()` when the interface's connection initialiser runs.
# Replaces the legacy v0.10.x `reticulum_wrapper.kotlin_rnode_bridge`
# accessor — the dual-build's slim-Python rule has no reticulum_wrapper to
# hold instance state.
#
# USB-serial RNode connections use a separate slot in `usb_bridge.py`
# (set_usb_bridge / get_usb_bridge) — the two bridge surfaces have different
# constructor / method shapes so they're kept separate rather than wrapped.
_rnode_bridge = None


def set_rnode_bridge(bridge):
    """Hand a `KotlinRNodeBridge` (or compatible duck-typed bridge exposing
    `connect(deviceName, mode)` / `disconnect()` / `writeSync(bytes)` /
    `read(): ByteArray` / `getRssi()` / `isConnected()`) to the Python-side
    RNode interface."""
    global _rnode_bridge
    _rnode_bridge = bridge


def get_rnode_bridge():
    return _rnode_bridge


def deploy_bundled_interfaces(storage_path):
    """Materialise bundled custom-interface .py files into RNS's configdir
    `interfaces/` (and `interfaces/drivers/`) before `Reticulum()` is built.

    `RNS.Transport.find_interfaces()` discovers external interface modules by
    scanning the filesystem under `<configdir>/interfaces/<type>.py`. Under
    Chaquopy the BLE adapter sources live inside the APK (as `ble_modules` and
    `ble_reticulum` packages), so RNS can't see them unless we extract bytes
    with `pkgutil.get_data` and write them to the configdir first.

    Ports the v0.10.x `_deploy_*` block from `reticulum_wrapper.initialize()`.
    Idempotent — safe to call on every start. Failures are logged but
    non-fatal: if BLE can't be deployed, the user's other interfaces still
    come up.
    """
    import os
    import pkgutil
    import sys

    interfaces_dir = os.path.join(storage_path, "interfaces")
    drivers_dir = os.path.join(interfaces_dir, "drivers")
    os.makedirs(drivers_dir, exist_ok=True)

    # RNS loads external interface modules via `exec()` against a synthesised
    # namespace, so they rely on `sys.path` to resolve sibling imports like
    # `from BLEInterface import BLEInterface` in AndroidBLE.py. Add the
    # interfaces dir up-front rather than relying on AndroidBLE.py's
    # self-bootstrap (which has a HOME-env fallback hardcoded to the
    # legacy v0.10.x package name).
    if interfaces_dir not in sys.path:
        sys.path.insert(0, interfaces_dir)

    # (package, resource, dest_filename). The resource → dest rename for
    # android_ble_interface.py → AndroidBLE.py matches the config-file
    # `type = AndroidBLE` line (see RnsConfigFile.kt) — RNS resolves the
    # type to a file of that name and the matching class inside it.
    plan = [
        ("ble_reticulum", "bluetooth_driver.py", interfaces_dir, "bluetooth_driver.py"),
        ("ble_reticulum", "linux_bluetooth_driver.py", interfaces_dir, "linux_bluetooth_driver.py"),
        ("ble_reticulum", "BLEFragmentation.py", interfaces_dir, "BLEFragmentation.py"),
        ("ble_reticulum", "BLEGATTServer.py", interfaces_dir, "BLEGATTServer.py"),
        ("ble_reticulum", "BLEInterface.py", interfaces_dir, "BLEInterface.py"),
        ("ble_modules", "android_ble_interface.py", interfaces_dir, "AndroidBLE.py"),
        ("ble_modules", "android_ble_driver.py", drivers_dir, "android_ble_driver.py"),
        # ColumbaRNodeInterface deployment is handled separately below the
        # loop — it lives at the top level of the slim Python tree (not
        # inside a package) so `pkgutil.get_data` can't reach it. We read
        # the module's __file__ post-import instead.
    ]

    # Drivers package marker — AndroidBLE.py imports from drivers.android_ble_driver.
    init_path = os.path.join(drivers_dir, "__init__.py")
    if not os.path.exists(init_path):
        with open(init_path, "w") as f:
            f.write("# Drivers package\n")

    for package, resource, dest_dir, dest_name in plan:
        dest = os.path.join(dest_dir, dest_name)
        try:
            data = pkgutil.get_data(package, resource)
            if data is None:
                RNS.log(
                    f"event_bridge: pkgutil.get_data({package!r}, {resource!r}) returned None — skipping",
                    RNS.LOG_WARNING,
                )
                continue
            with open(dest, "wb") as f:
                f.write(data)
        except Exception as e:  # noqa: BLE001
            RNS.log(
                f"event_bridge: failed to deploy {package}/{resource} → {dest}: {e}",
                RNS.LOG_ERROR,
            )

    # ColumbaRNodeInterface — Columba-authored RNS.Interface subclass that
    # speaks KISS to RNode LoRa hardware over Bluetooth Classic/BLE/USB.
    # The interface module sits at the top level of the slim Python tree
    # rather than inside a package because it doesn't depend on a sibling
    # package (unlike ble_modules.android_ble_driver), so `pkgutil.get_data`
    # (which needs a package as its first arg) can't reach it.
    #
    # Use `inspect.getsource()` which delegates to the module's loader's
    # `get_source()` (PEP 302) — works for both filesystem-imported modules
    # and Chaquopy's in-zip AssetFinder loader (whose __file__ points into
    # the app.imy archive and is not open()-able with the regular file API).
    # An earlier attempt to use open(__file__) silently failed because the
    # zip path is a synthetic location; the .pyc gets cached on disk but the
    # source never does. Stick with the loader API.
    #
    # The destination is renamed snake_case → PascalCase
    # (columba_rnode_interface.py → ColumbaRNodeInterface.py) so it matches
    # the `type = ColumbaRNodeInterface` directive emitted by RnsConfigFile.
    # Failure is logged but non-fatal: BLE/USB RNode support is degraded,
    # the rest of the stack still comes up. Bumped to LOG_NOTICE +
    # unconditional print so the success/failure is visible in `python.
    # stdout` even before RNS's logfile machinery is up (deploy runs before
    # Reticulum()).
    import inspect
    import traceback
    try:
        import columba_rnode_interface as _crni_mod
        src = inspect.getsource(_crni_mod)
        dest = os.path.join(interfaces_dir, "ColumbaRNodeInterface.py")
        with open(dest, "w") as f:
            f.write(src)
        RNS.log(
            f"event_bridge: deployed ColumbaRNodeInterface.py "
            f"({len(src)} bytes) to {dest}",
            RNS.LOG_NOTICE,
        )
        print(
            f"event_bridge: deployed ColumbaRNodeInterface.py "
            f"({len(src)} bytes)",
            flush=True,
        )
    except Exception as e:  # noqa: BLE001
        RNS.log(
            f"event_bridge: failed to deploy ColumbaRNodeInterface.py: {e}",
            RNS.LOG_ERROR,
        )
        print(
            f"event_bridge: FAILED to deploy ColumbaRNodeInterface.py: {e}",
            flush=True,
        )
        traceback.print_exc()


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
    # Use raw print + sys.stdout flush so the diagnostic always reaches
    # logcat even if RNS.log was monkey-patched or the loglevel got reset.
    import sys
    print("event_bridge.reset: ENTER", flush=True)
    sys.stdout.flush()

    # Parked outbound messages reference the dying router — a resubmit after
    # restart would re-route through dead state, so drop them.
    _outbound_messages.clear()

    reticulum = RNS.Reticulum
    transport = RNS.Transport

    iface_count = len(getattr(transport, "interfaces", []) or [])
    print(f"event_bridge.reset: transport.interfaces has {iface_count} entries", flush=True)
    sys.stdout.flush()

    # Explicit interface socket close BEFORE clearing the registry.
    # Upstream RNS's AutoInterface.detach() only flips boolean flags
    # (AutoInterface.py:644) — UDPServer sockets keep the IPv6
    # link-local multicast bind. Without this, the next Reticulum()
    # hits `OSError: [Errno 98] Address already in use` and the whole
    # service crash-loops. Best-effort per interface; iterates a snapshot
    # of the current registry so a half-broken interface can't take down
    # the cleanup loop.
    interfaces_snapshot = list(getattr(transport, "interfaces", []) or [])
    print(f"event_bridge.reset: iterating {len(interfaces_snapshot)} interface(s)", flush=True)
    for idx, iface in enumerate(interfaces_snapshot):
        attrs = [
            a for a in (
                "interface_servers", "discovery_socket",
                "unicast_discovery_socket", "announce_socket",
                "socket", "server",
            ) if getattr(iface, a, None) is not None
        ]
        print(
            f"event_bridge.reset: [{idx}] {iface.__class__.__name__} "
            f"detached={getattr(iface, 'detached', None)} online={getattr(iface, 'online', None)} "
            f"socket_attrs={attrs}",
            flush=True,
        )
        # AutoInterface: dict of ifname -> socketserver.UDPServer
        servers = getattr(iface, "interface_servers", None)
        if isinstance(servers, dict):
            print(f"event_bridge.reset: [{idx}] interface_servers has {len(servers)} entries: {list(servers.keys())}", flush=True)
            for ifname, server in list(servers.items()):
                try:
                    server.shutdown()
                    print(f"event_bridge.reset: [{idx}] shutdown UDPServer on {ifname}", flush=True)
                except Exception as e:  # noqa: BLE001
                    print(f"event_bridge.reset: [{idx}] UDPServer.shutdown on {ifname} FAILED: {type(e).__name__}: {e}", flush=True)
                try:
                    server.server_close()
                    print(f"event_bridge.reset: [{idx}] server_close UDPServer on {ifname} (closed socket)", flush=True)
                except Exception as e:  # noqa: BLE001
                    print(f"event_bridge.reset: [{idx}] UDPServer.server_close on {ifname} FAILED: {type(e).__name__}: {e}", flush=True)
            servers.clear()
        # AutoInterface also opens a peer-discovery socket on most platforms.
        for sock_attr in ("discovery_socket", "unicast_discovery_socket", "announce_socket"):
            sock = getattr(iface, sock_attr, None)
            if sock is not None:
                try:
                    sock.close()
                    print(f"event_bridge.reset: [{idx}] closed {sock_attr}", flush=True)
                except Exception as e:  # noqa: BLE001
                    print(f"event_bridge.reset: [{idx}] {sock_attr}.close FAILED: {type(e).__name__}: {e}", flush=True)
        # Generic interface socket-close — TCP/UDP interfaces expose
        # `.socket` (single socket) or `.server` (socketserver). Defensive
        # close so a future interface type doesn't reintroduce this bug.
        for sock_attr in ("socket", "server"):
            sock = getattr(iface, sock_attr, None)
            if sock is None:
                continue
            close = getattr(sock, "close", None) or getattr(sock, "server_close", None)
            if callable(close):
                try:
                    close()
                except Exception as e:  # noqa: BLE001
                    RNS.log(
                        f"event_bridge: {sock_attr}.close failed: {e}",
                        RNS.LOG_DEBUG,
                    )

    # Shared-instance TCP listener (port 37428). When share_instance=yes,
    # upstream's LocalServerInterface binds via BackboneInterface.add_listener,
    # which stashes the listening socket in the *global*
    # BackboneInterface.listener_filenos dict — NOT in an interface's .socket /
    # .server attribute, so the per-interface close loop above never sees it.
    # That socket is normally closed by exit_handler() ->
    # Transport.detach_interfaces() -> BackboneInterface.deregister_listeners();
    # but PythonRnsRuntime.stop() swallows exit_handler() failures, and if it
    # threw before detach the 37428 listener leaks. A leaked listener makes the
    # NEXT in-process start()'s SharedInstanceProbe connect to our OWN socket
    # and silently demote us from host to client (self-detection). Close it here
    # unconditionally — reset_reticulum_for_restart() runs in stop()'s own
    # runCatching, independent of exit_handler(). Idempotent: deregister_listeners()
    # no-ops once listener_filenos is already cleared (the normal exit_handler()
    # path), so this only has an effect when exit_handler() failed to run it.
    try:
        import RNS.Interfaces.BackboneInterface as _backbone
        _backbone.BackboneInterface.deregister_listeners()
        print("event_bridge.reset: deregistered BackboneInterface listeners (shared-instance 37428)", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"event_bridge.reset: BackboneInterface.deregister_listeners FAILED: {type(e).__name__}: {e}", flush=True)

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


def _format_iface_for_emit(iface):
    """Resolve an RNS Interface object to a structured display string.

    Goal: every emitted `receiving_interface` carries a class signal the
    kotlin `InterfaceType.fromName` classifier can pattern-match (otherwise
    rows store as `UNKNOWN` and the announce-card icon disappears — the
    recurring regression this helper exists to prevent).

    Strategy:
      1. If `__str__` already returns a structured `"AnyClass[content]"`
         shape — true for AutoInterface, AutoInterfacePeer, TCPInterface,
         BackboneInterface, LocalServerInterface, RNodeInterface and most
         other upstream subclasses — use it verbatim. This preserves
         per-peer detail like `AutoInterfacePeer[wlan0/fe80::…]` (the
         `ifname/addr` joined form, NOT just `.name` which is unset on
         that subclass) so the kotlin `extractFriendlyName` parser still
         derives the right display label.
      2. Otherwise, `__str__` returned a bare name (a subclass that
         doesn't override, or one that returns just `.name`). Wrap it
         with the actual `type(iface).__name__` so the classifier still
         has a class token to match — e.g. `"homelab"` becomes
         `"TCPClientInterface[homelab]"`.
    """
    raw_str = str(iface) if iface is not None else ""
    if "[" in raw_str and raw_str.endswith("]"):
        return raw_str
    cls_name = type(iface).__name__ if iface is not None else ""
    return f"{cls_name}[{raw_str}]"


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
        # Receiving interface annotation. RNS doesn't surface a per-announce
        # `received_on` interface on `received_announce(...)` — we have to
        # look it up from `Transport.path_table[destination_hash][5]` (the
        # IDX_PT_RVCD_IF slot). Same fallback the LXMF delivery handler
        # uses below. Without this every announce shipped to Kotlin had
        # `receiving_interface = ""`, so 99% of `announces` rows landed
        # with `receivingInterfaceType = "UNKNOWN"` and the announce-stream
        # interface-type icon never rendered.
        recv_iface_name = None
        try:
            path_entry = RNS.Transport.path_table.get(destination_hash)
            if path_entry is not None and len(path_entry) > 5:
                iface = path_entry[5]
                if iface is not None:
                    recv_iface_name = _format_iface_for_emit(iface)
        except Exception as e:  # noqa: BLE001 — annotation is best-effort, never fatal
            RNS.log(
                f"event_bridge: receiving_interface lookup failed: {e}",
                RNS.LOG_DEBUG,
            )

        payload = {
            "destination_hash": _hex(destination_hash),
            "identity_hash": _hex(announced_identity.hash) if announced_identity is not None else None,
            "public_key": _hex(announced_identity.get_public_key()) if announced_identity is not None else None,
            "app_data": _hex(app_data),
            "announce_packet_hash": _hex(announce_packet_hash),
            "receiving_interface": recv_iface_name,
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


# ----------------------------------------------------------------------------
# Telemetry collector / host-mode state. When `_collector_enabled` is True the
# inbound delivery callback below mirrors any FIELD_TELEMETRY message into
# `_collected_telemetry`, and responds to FIELD_COMMANDS requests from
# allow-listed identities with FIELD_TELEMETRY_STREAM. State held module-level
# so an "Apply & Restart" lifecycle re-applies via Kotlin's
# `PythonRnsTelemetry` re-calling the setters at init.
#
# Mirrors `release/v0.10.x`'s
# `reticulum_wrapper.set_telemetry_collector_enabled / store_own_telemetry /
# set_telemetry_allowed_requesters` exactly. The dual-build hosts the same
# responder logic in this Python module rather than in a Kotlin Chaquopy
# bridge because the LXMF response (`router.handle_outbound`) needs the
# live `RNS.Destination` / `RNS.Identity.recall` Python objects — staying
# on the Python side avoids a JNI round-trip per request.
# ----------------------------------------------------------------------------
_collector_enabled = False
# {hex_source_hash: {"timestamp": int, "packed_telemetry": bytes,
#                    "appearance": list_or_None, "received_at": float}}
_collected_telemetry = {}
# Set of lowercase 32-char hex identity hashes allowed to request the
# stream. Empty set means "block all requests" (matches Sideband + the UI
# warning in LocationSharingCard's AllowedRequestersSection).
_collector_allowed_requesters = set()


def _local_lxmf_destination():
    """Return the host's local LXMF delivery `RNS.Destination`, or None.

    `LXMRouter` exposes registered delivery destinations as a
    `delivery_destinations` dict (keyed by destination hash, value is the
    `RNS.Destination` instance). Columba registers exactly one identity
    per process via `LXMRouter.register_delivery_identity` — we want that
    sole destination as both the `source` for outbound LXMessages and the
    key (its `hexhash`) for storing the host's own telemetry. Returns the
    first registered destination, or None when the router hasn't built
    one yet (early-init race).
    """
    if _lxmf_router is None:
        return None
    try:
        dests = getattr(_lxmf_router, "delivery_destinations", None)
        if not dests:
            return None
        # `delivery_destinations` is a dict — values() handles both
        # legacy list shape (defensive) and the documented dict shape.
        for d in dests.values() if isinstance(dests, dict) else dests:
            return d
        return None
    except Exception as e:  # noqa: BLE001 — defensive; never wedge on a missing attr
        RNS.log(f"event_bridge: _local_lxmf_destination failed: {e}", RNS.LOG_DEBUG)
        return None


def set_collector_enabled(enabled):
    """Toggle telemetry-host mode. Called by `PythonRnsTelemetry.setTelemetryCollectorMode`.

    On disable, drop any collected telemetry so a stale set doesn't get
    served the next time the host re-enables. Idempotent.
    """
    global _collector_enabled
    _collector_enabled = bool(enabled)
    if not _collector_enabled:
        _collected_telemetry.clear()
    RNS.log(
        f"event_bridge: telemetry collector mode -> {_collector_enabled}",
        RNS.LOG_DEBUG,
    )


def set_collector_allowed_requesters(hashes_iter):
    """Replace the allow-list of requester identity hashes. Lowercased here
    so the inbound-command check can match without re-normalising.

    Empty set is allowed and means "block all" — matches Sideband.
    """
    global _collector_allowed_requesters
    _collector_allowed_requesters = {str(h).lower() for h in hashes_iter if h}
    RNS.log(
        f"event_bridge: telemetry allowed-requesters -> "
        f"{len(_collector_allowed_requesters)} entries",
        RNS.LOG_DEBUG,
    )


def store_own_telemetry(packed_bytes, timestamp_seconds, appearance):
    """Fold the host's own latest telemetry into the collected set so it
    rides out in FIELD_TELEMETRY_STREAM responses to group members.

    Called by `PythonRnsTelemetry.storeOwnTelemetry` whenever the host
    runs its periodic / Send-Now path with `collectorAddress == self`.
    Keyed by the host's local LXMF destination hash.

    `appearance` is the FIELD_ICON_APPEARANCE list `[icon_name, fg_bytes,
    bg_bytes]` or None.
    """
    if not _collector_enabled:
        # Host mode flipped off between the Kotlin send and this call —
        # silently ignore. The Kotlin side already gates this earlier
        # but the race exists at process startup.
        return
    delivery_dest = _local_lxmf_destination()
    if delivery_dest is None:
        RNS.log(
            "event_bridge: store_own_telemetry skipped — no local LXMF delivery destination",
            RNS.LOG_DEBUG,
        )
        return
    own_hash_hex = delivery_dest.hexhash
    packed = bytes(packed_bytes) if not isinstance(packed_bytes, (bytes, bytearray)) else bytes(packed_bytes)
    appearance_list = None
    if appearance is not None:
        try:
            # Chaquopy may hand over a List<Object>; normalise to a real Python list
            # so the eventual msgpack encode doesn't choke on jvm types.
            appearance_list = list(appearance)
            # bytes fields inside the appearance list (fg + bg color bytes) likewise.
            appearance_list = [
                bytes(v) if isinstance(v, (bytes, bytearray)) or hasattr(v, "__iter__") and not isinstance(v, str) else v
                for v in appearance_list
            ]
        except Exception as e:  # noqa: BLE001 — appearance is optional
            RNS.log(f"event_bridge: appearance normalisation failed: {e}", RNS.LOG_DEBUG)
            appearance_list = None
    _store_telemetry_for_collector(own_hash_hex, packed, int(timestamp_seconds), appearance_list)


def _store_telemetry_for_collector(source_hash_hex, packed_telemetry, timestamp, appearance):
    """Internal: insert/update one entry in the collected set.

    Called from `store_own_telemetry` for the host's own location AND
    from `_lxmf_delivery_callback` for inbound member telemetry while
    host mode is on.
    """
    import time as _time
    _collected_telemetry[source_hash_hex] = {
        "timestamp": int(timestamp),
        "packed_telemetry": packed_telemetry,
        "appearance": appearance,
        "received_at": _time.time(),
    }


def _cleanup_expired_collected_telemetry():
    """Evict collector entries older than `_COLLECTOR_RETENTION_SECONDS`.
    Called immediately before building a stream response so the bytes on
    the wire stay bounded.
    """
    import time as _time
    now = _time.time()
    expired = [k for k, v in _collected_telemetry.items()
               if now - v.get("received_at", 0) > _COLLECTOR_RETENTION_SECONDS]
    for k in expired:
        _collected_telemetry.pop(k, None)


def _send_telemetry_stream_response(requester_hash_bytes, requester_identity, timebase):
    """Build + handoff a FIELD_TELEMETRY_STREAM LXMessage to the requester.

    `requester_hash_bytes` is the source_hash of the inbound request
    (the requester's LXMF delivery hash). `requester_identity` is the
    `RNS.Identity` instance, looked up via `RNS.Identity.recall`.
    `timebase` is the request's timebase int — entries with
    `received_at >= timebase` are included (0 / None means "all").

    Mirrors `release/v0.10.x`'s shape so a Columba <-> Columba host /
    member pair on either branch interops over the same wire format.
    """
    delivery_dest = _local_lxmf_destination()
    if delivery_dest is None:
        RNS.log(
            "event_bridge: stream response skipped — no local LXMF delivery destination",
            RNS.LOG_WARNING,
        )
        return
    try:
        _cleanup_expired_collected_telemetry()
        entries = []
        for source_hash_hex, entry in _collected_telemetry.items():
            received_at = entry.get("received_at", 0)
            if timebase and received_at < timebase:
                continue
            entries.append([
                bytes.fromhex(source_hash_hex),
                int(entry.get("timestamp", 0)),
                entry.get("packed_telemetry", b""),
                entry.get("appearance"),
            ])

        RNS.log(
            f"event_bridge: telemetry stream response to "
            f"{requester_hash_bytes.hex()[:16]} — {len(entries)} entries (timebase={timebase})",
            RNS.LOG_DEBUG,
        )

        destination = RNS.Destination(
            requester_identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf",
            "delivery",
        )
        fields = {LXMF.FIELD_TELEMETRY_STREAM: entries}
        lxmessage = LXMF.LXMessage(
            destination,
            delivery_dest,
            "",
            fields=fields,
            desired_method=LXMF.LXMessage.DIRECT,
        )
        _lxmf_router.handle_outbound(lxmessage)
    except Exception as e:  # noqa: BLE001 — never wedge the delivery thread
        RNS.log(f"event_bridge: telemetry stream response failed: {e}", RNS.LOG_ERROR)


def _maybe_handle_telemetry_request(message):
    """Inspect an inbound LXMessage for a Sideband telemetry request command.

    Returns True if the message was consumed by the collector responder
    (no further processing needed — the delivery callback should skip
    chat emission). Returns False otherwise.
    """
    if not _collector_enabled:
        return False
    fields = getattr(message, "fields", None)
    if not fields or LXMF.FIELD_COMMANDS not in fields:
        return False
    commands = fields[LXMF.FIELD_COMMANDS]
    if not isinstance(commands, list):
        return False
    handled = False
    for command_dict in commands:
        if not isinstance(command_dict, dict):
            continue
        if _COMMAND_TELEMETRY_REQUEST not in command_dict:
            continue
        args = command_dict[_COMMAND_TELEMETRY_REQUEST]
        timebase = args[0] if isinstance(args, (list, tuple)) and len(args) > 0 else 0
        # Sideband sometimes packs args[1] = is_collector_request; we
        # respond to any telemetry request from an allow-listed requester.
        requester_hash = _hex(getattr(message, "source_hash", None))
        if not requester_hash:
            continue
        if requester_hash not in _collector_allowed_requesters:
            RNS.log(
                f"event_bridge: telemetry request BLOCKED from "
                f"{requester_hash[:16]} (not in allow-list; have "
                f"{len(_collector_allowed_requesters)} entries)",
                RNS.LOG_DEBUG,
            )
            handled = True
            continue
        requester_identity = RNS.Identity.recall(message.source_hash) or \
            RNS.Identity.recall(message.source_hash, from_identity_hash=True)
        if requester_identity is None:
            RNS.log(
                f"event_bridge: telemetry request from {requester_hash[:16]} "
                "had no recallable identity — dropping",
                RNS.LOG_WARNING,
            )
            handled = True
            continue
        _send_telemetry_stream_response(message.source_hash, requester_identity, timebase)
        handled = True
    return handled


def _maybe_collect_inbound_telemetry(message):
    """Mirror an inbound member's FIELD_TELEMETRY into `_collected_telemetry`
    while host mode is on, so subsequent FIELD_COMMANDS requests see it.
    """
    if not _collector_enabled:
        return
    fields = getattr(message, "fields", None)
    if not fields or LXMF.FIELD_TELEMETRY not in fields:
        return
    source_hash = getattr(message, "source_hash", None)
    if source_hash is None:
        return
    source_hash_hex = _hex(source_hash)
    packed = fields[LXMF.FIELD_TELEMETRY]
    if not isinstance(packed, (bytes, bytearray)):
        return
    timestamp = getattr(message, "timestamp", None) or 0
    appearance = fields.get(LXMF.FIELD_ICON_APPEARANCE)
    _store_telemetry_for_collector(source_hash_hex, bytes(packed), int(timestamp), appearance)


def _lxmf_delivery_callback(message):
    """LXMRouter delivery callback. `message` is an upstream LXMessage."""
    try:
        # Telemetry collector / host-mode hooks. Run BEFORE the usual
        # message-emit path so a telemetry-stream request is consumed
        # silently (no chat row) and so an inbound FIELD_TELEMETRY from a
        # group member is folded into our collected set in time to be
        # served by the next FIELD_COMMANDS response.
        _maybe_collect_inbound_telemetry(message)
        if _maybe_handle_telemetry_request(message):
            # Pure command-frame request — Sideband never carries chat
            # content alongside one. Skip the rest of the delivery handler
            # so this never appears as a user-visible message.
            return

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

        # Resolve the interface object to a structured
        # `"ClassName[content]"` string — same format the announce-receive
        # path emits, so the kotlin classifier's pattern match is
        # deterministic regardless of what user-given `.name` an interface
        # carries. See `_format_iface_for_emit` for the rationale.
        recv_iface_name = None
        if recv_iface is not None:
            recv_iface_name = _format_iface_for_emit(recv_iface)

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

# Outbound LXMessages awaiting a Kotlin retry decision, keyed by hex hash.
# `_failed` registers the live message here before flattening the failure to
# Kotlin; `PythonEventBridge.handleLxmfFailure` applies the shared
# `DeliveryRetryPolicy` and calls back into `resubmit_as_propagated` (retry)
# or `discard_outbound` (final failure), both of which pop the entry.
# `_delivered` pops defensively in case a spurious failure registered first.
_outbound_messages = {}


def attach_lxmessage_callbacks(
    lxmessage,
    on_delivered,
    on_failed,
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
    Kotlin `PyEventCallback` fires.

    **try_propagation_on_fail (Sideband pattern)** — the *decision* to retry
    a failed DIRECT send via the propagation node lives in shared Kotlin
    (`DeliveryRetryPolicy` in `:rns-api`, applied by
    `PythonEventBridge.handleLxmfFailure` — the same predicate
    `NativeMessageSender` consults on the kotlin flavor). This side only
    flattens the failure with the state the policy needs and parks the live
    LXMessage in `_outbound_messages` so a Kotlin retry decision can invoke
    `resubmit_as_propagated`. The *mechanism* stays here because it
    manipulates the live upstream LXMessage/LXMRouter.
    """
    def _delivered(msg):
        msg_hash_hex = _hex(getattr(msg, "hash", None))
        _outbound_messages.pop(msg_hash_hex, None)
        # Carry method + desired_method through so the Kotlin side can
        # distinguish PROPAGATED (success means "stored on the relay")
        # from DIRECT/OPPORTUNISTIC (success means "ack from recipient").
        # Without these fields a PROPAGATED success gets stamped "delivered"
        # in the UI (✓✓) instead of "propagated" (✓).
        method = getattr(msg, "method", None)
        desired = getattr(msg, "desired_method", None)
        payload = {
            "hash": msg_hash_hex,
            "method": method if method is not None else -1,
            "desired_method": desired if desired is not None else -1,
        }
        RNS.log(
            f"event_bridge: _delivered fired for {msg_hash_hex} "
            f"(method={method}, desired={desired})",
            RNS.LOG_DEBUG,
        )
        _emit(on_delivered, payload)

    def _failed(msg):
        # Flatten the failure with everything DeliveryRetryPolicy needs and
        # park the live message for a possible resubmit. No decision here.
        msg_hash_hex = _hex(getattr(msg, "hash", None))
        _outbound_messages[msg_hash_hex] = msg
        payload = {
            "hash": msg_hash_hex,
            "try_propagation_on_fail": bool(getattr(msg, "try_propagation_on_fail", False)),
            "desired_method_is_propagated": (
                getattr(msg, "desired_method", None) == LXMF.LXMessage.PROPAGATED
            ),
            "propagation_node_configured": (
                _lxmf_router is not None
                and getattr(_lxmf_router, "outbound_propagation_node", None) is not None
            ),
        }
        _emit(on_failed, payload)

    # Tag the LXMessage so the failure payload can carry the intent. Upstream
    # Sideband uses this same attribute; LXMF does not read it itself, so the
    # tag is benign on stock upstream.
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


def resubmit_as_propagated(msg_hash_hex):
    """Rebuild a failed outbound LXMessage as PROPAGATED and re-route it.

    Mechanism half of the Sideband try-propagation-on-fail pattern — the
    decision half is `DeliveryRetryPolicy` in `:rns-api`, applied by
    `PythonEventBridge.handleLxmfFailure`, which calls this when it decides
    to retry. Returns True when the message was re-submitted (the caller
    then emits `RetryingViaPropagation`), False when it wasn't (unknown
    hash, router gone, already retried, or `handle_outbound` raised — the
    caller emits `Failed`).
    """
    msg = _outbound_messages.pop(msg_hash_hex, None)
    if msg is None or _lxmf_router is None:
        return False
    if getattr(msg, "_columba_propagation_retry_attempted", False):
        return False
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
    msg.desired_method = LXMF.LXMessage.PROPAGATED
    try:
        _lxmf_router.handle_outbound(msg)
        RNS.log(
            "event_bridge: DIRECT delivery failed, retrying via propagation node",
            RNS.LOG_DEBUG,
        )
        return True
    except Exception as e:  # noqa: BLE001
        RNS.log(
            f"event_bridge: propagation retry failed: {e}",
            RNS.LOG_ERROR,
        )
        return False


def discard_outbound(msg_hash_hex):
    """Drop a parked outbound LXMessage after a final (no-retry) failure.

    Called by `PythonEventBridge.handleLxmfFailure` when `DeliveryRetryPolicy`
    decides against a propagation retry, so `_outbound_messages` doesn't leak
    references to dead messages.
    """
    _outbound_messages.pop(msg_hash_hex, None)


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
        # True iff the upstream `RequestReceipt.metadata` was non-None — the
        # "/file/ response" signal. NomadNet sets metadata on file responses
        # only (NomadNet node.py `Request.file_handler`); `/page/` responses
        # leave it None. The Kotlin consumer keys "render as file" on this
        # flag, independent of whether a `"name"` key was present.
        has_metadata=False,
        # Server-advertised file name bytes from the metadata dict's `"name"`
        # key (utf-8). Empty bytes when the metadata dict has no `"name"` key,
        # or when `has_metadata` is False. Replaces the previous
        # `metadata_bytes` (a msgpack-roundtripped blob) — upstream
        # `RequestReceipt.metadata` is already a Python dict at the property
        # accessor, so packing it just to make Kotlin unpack it on-device was
        # unnecessary work. The Kotlin consumer
        # (`PythonRnsNomadnet.buildPageResult`) only ever needed the name.
        metadata_name_bytes=b"",
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
            # Extract just the server-advertised file name from the metadata
            # dict. Upstream RNS exposes `receipt.metadata` as the unpacked
            # dict (msgpack happens lazily inside the property accessor), so
            # we read `m["name"]` directly. v0.10.x reference behavior:
            # python/rns_api.py:_save_file_response — name defaults to
            # b"download" when the key is absent; bytes are decoded
            # utf-8/errors="replace" on the Kotlin side.
            m = getattr(receipt, "metadata", None)
            if m is not None:
                cap.has_metadata = True
            if isinstance(m, dict):
                name_raw = m.get("name", b"")
                if isinstance(name_raw, bytes):
                    cap.metadata_name_bytes = bytes(name_raw)
                elif isinstance(name_raw, str):
                    cap.metadata_name_bytes = name_raw.encode("utf-8")
                else:
                    cap.metadata_name_bytes = str(name_raw).encode("utf-8")
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
