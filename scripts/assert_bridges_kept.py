#!/usr/bin/env python3
"""Assert R8 kept the Python<->Kotlin bridge surface un-renamed.

Chaquopy resolves the bridge classes/methods by name from Python, so R8
obfuscation silently breaks the pythonBackend release at runtime. This reads
R8's own mapping.txt (the authoritative rename table) and fails the build if any
bridge class was renamed/stripped, any host-bridge method Python calls was
renamed, or a callback SAM (`onEvent`) was renamed.

The defense is `@ReflectivelyKept` on each class (kept via a single proguard
rule); this is the regression gate that proves R8 honored it.

Usage: assert_bridges_kept.py <path-to-mapping.txt>
Exit: 0 = all kept, 1 = something obfuscated, 2 = usage/IO error.
"""
import re
import sys
from pathlib import Path

# Every bridge class Chaquopy touches must keep its name (proves @ReflectivelyKept fired).
BRIDGE_CLASSES = [
    "network.columba.app.rns.host.rnode.KotlinRNodeBridge",
    "network.columba.app.rns.host.ble.bridge.KotlinBLEBridge",
    "network.columba.app.rns.host.usb.KotlinUSBBridge",
    "network.columba.app.rns.backend.py.PythonEventBridge",
    "network.columba.app.rns.backend.py.PyEventCallback",
    "network.columba.app.rns.backend.py.PyTwoArgCallback",
    "network.columba.app.rns.backend.py.StampGeneratorCallback",
]

# Method names Python calls on each host bridge (from the call sites in
# rns-backend-py/src/main/python). Asserted per class so a name stripped from one
# bridge isn't masked by the same name surviving on another.
BRIDGE_METHODS = {
    "network.columba.app.rns.host.rnode.KotlinRNodeBridge": {
        "connect", "disconnect", "getConnectedDeviceName", "isConnected",
        "notifyOnlineStatusChanged", "read", "setOnConnectionStateChanged",
    },
    "network.columba.app.rns.host.ble.bridge.KotlinBLEBridge": {
        "configurePower", "connect", "connectAsync", "disconnect", "disconnectAsync",
        "disconnectCentralAsync", "disconnectPeripheralAsync", "ensureAdvertising",
        "getPeerRssi", "requestIdentityResync", "sendAsync", "setIdentity",
        "setOnAddressChanged", "setOnConnected", "setOnDataReceived", "setOnDeviceDiscovered",
        "setOnDisconnected", "setOnDuplicateIdentityDetected", "setOnIdentityReceived",
        "setOnMtuNegotiated", "shouldConnect", "startAdvertisingAsync", "startAsync",
        "startScanningAsync", "stopAdvertisingAsync", "stopAsync", "stopScanningAsync",
    },
    "network.columba.app.rns.host.usb.KotlinUSBBridge": {
        "connect", "disconnect", "findDeviceByVidPid", "isConnected", "notifyBluetoothPin", "read",
    },
    "network.columba.app.rns.backend.py.StampGeneratorCallback": {
        # event_bridge.install_external_stamp_generator calls generate(workblock, cost) by name.
        "generate",
    },
}

# Chaquopy callback SAMs Python invokes by name:
#   PyEventCallback.onEvent(PyObject)             — 1 arg (register_callbacks sinks)
#   PyTwoArgCallback.onEvent(PyObject, PyObject)  — 2 args (set_remote_identified_callback)
# Abstract interface methods get no mapping line, so we match a surviving
# *implementation* mapped to `onEvent` that carries that many PyObject params.
# Supplementary to the class-level checks above (which, with the
# `-keep @ReflectivelyKept class * { *; }` rule, already keep these members).
SAM_PATTERNS = {
    "PyEventCallback.onEvent(PyObject)":
        # Anchor the open paren so a single-PyObject signature can't be matched by
        # a two-PyObject line (which also ends `…PyObject) -> onEvent`) — otherwise
        # a stripped PyEventCallback could pass on a surviving PyTwoArgCallback line.
        re.compile(r"\(com\.chaquo\.python\.PyObject\).* -> onEvent$"),
    "PyTwoArgCallback.onEvent(PyObject, PyObject)":
        re.compile(r"com\.chaquo\.python\.PyObject,com\.chaquo\.python\.PyObject\).* -> onEvent$"),
}


def parse_blocks(lines):
    """original_class -> {'mapped': str, 'members': [str]}."""
    blocks = {}
    cur = None
    for ln in lines:
        if ln and not ln[0].isspace() and " -> " in ln and ln.rstrip().endswith(":"):
            left, right = ln.split(" -> ", 1)
            cur = left.strip()
            blocks[cur] = {"mapped": right.rstrip()[:-1], "members": []}
        elif cur is not None and ln[:1].isspace():
            blocks[cur]["members"].append(ln)
    return blocks


def kept_method_names(members):
    """Method names in a class block that R8 left un-renamed (mapped == original)."""
    kept = set()
    for ln in members:
        if " -> " not in ln or "(" not in ln:
            continue  # field or non-method line
        left, mapped = ln.rsplit(" -> ", 1)
        name = left.split("(", 1)[0].split()[-1].split(".")[-1]
        if name == mapped.strip():
            kept.add(name)
    return kept


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <mapping.txt>", file=sys.stderr)
        sys.exit(2)
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"ERROR: mapping not found: {path}", file=sys.stderr)
        sys.exit(2)

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    blocks = parse_blocks(lines)
    failures = []

    for cls in BRIDGE_CLASSES:
        b = blocks.get(cls)
        if b is None:
            failures.append(f"class stripped entirely (not in mapping): {cls}")
        elif b["mapped"] != cls:
            failures.append(f"class RENAMED by R8: {cls} -> {b['mapped']}  (add @ReflectivelyKept)")

    for cls, expected in BRIDGE_METHODS.items():
        b = blocks.get(cls)
        if b is None:
            continue  # already reported as stripped
        missing = expected - kept_method_names(b["members"])
        if missing:
            failures.append(f"methods renamed/stripped on {cls}: {sorted(missing)}  (ensure @ReflectivelyKept)")

    for label, sam_re in SAM_PATTERNS.items():
        if not any(sam_re.search(ln) for ln in lines):
            failures.append(
                f"{label} SAM was renamed/stripped  "
                "(ensure @ReflectivelyKept on the SAM — Python calls it by name)",
            )

    if failures:
        print("✗ R8 bridge keep-check FAILED:")
        for f in failures:
            print(f"    {f}")
        print("\nChaquopy calls these by name; renaming breaks the pythonBackend release at runtime.")
        sys.exit(1)

    print("✓ R8 kept all Python<->Kotlin bridge classes/methods un-renamed.")


if __name__ == "__main__":
    main()
