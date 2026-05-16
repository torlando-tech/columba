"""Pytest fixtures for the Columba ↔ Sideband interop suite.

Two session-scoped peers (`columba_peer`, `sideband_peer`) get spun up once;
the per-test `interop` fixture re-derives identity hashes + asserts paths
have resolved before yielding.

Configuration (all env-driven, all optional, sensible defaults baked in):

    COLUMBA_EMULATOR_SERIAL   adb serial of the Columba-side emulator
                              (defaults to first arm64 device seen).
    COLUMBA_RNSD_HOST         host running rnsd (default `10.0.0.145`).
    COLUMBA_RNSD_PORT         rnsd TCP port (default `4242`).
    COLUMBA_PROP_NODE_HEX     lxmd propagation node hash for propagation
                              tests (default matches the launch-agent
                              setup `33f2621f135146ce30f0767d811af2b6`).
    SIDEBAND_SRC              path to the Sideband checkout. Defaults to
                              `~/repos/Sideband`. SidebandCore is imported
                              from `<this>/sbapp/sideband/core.py`.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import pytest

# Local imports
sys.path.insert(0, str(Path(__file__).parent))
from peer_columba import ColumbaPeer  # noqa: E402
from peer_sideband import SidebandPeer  # noqa: E402


DEFAULT_RNSD_HOST = "10.0.0.145"
DEFAULT_RNSD_PORT = 4242
DEFAULT_PROP_NODE_HEX = "33f2621f135146ce30f0767d811af2b6"
DEFAULT_SIDEBAND_SRC = os.path.expanduser("~/repos/Sideband")


def pytest_addoption(parser):
    parser.addoption(
        "--no-prop",
        action="store_true",
        default=False,
        help="skip PROPAGATED-method tests (requires a live lxmd node)",
    )


@pytest.fixture(scope="session")
def emulator_serial() -> str:
    s = os.environ.get("COLUMBA_EMULATOR_SERIAL")
    if s:
        return s
    # Auto-detect the first emulator
    result = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, check=True,
        timeout=10,
    )
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device" and "emulator" in parts[0]:
            return parts[0]
    pytest.skip("No emulator detected and COLUMBA_EMULATOR_SERIAL unset")


@pytest.fixture(scope="session")
def rnsd_host() -> str:
    return os.environ.get("COLUMBA_RNSD_HOST", DEFAULT_RNSD_HOST)


@pytest.fixture(scope="session")
def rnsd_port() -> int:
    return int(os.environ.get("COLUMBA_RNSD_PORT", DEFAULT_RNSD_PORT))


@pytest.fixture(scope="session")
def prop_node_hex() -> str:
    return os.environ.get("COLUMBA_PROP_NODE_HEX", DEFAULT_PROP_NODE_HEX)


@pytest.fixture(scope="session")
def sideband_src() -> str:
    p = os.environ.get("SIDEBAND_SRC", DEFAULT_SIDEBAND_SRC)
    if not os.path.isfile(os.path.join(p, "sbapp", "sideband", "core.py")):
        pytest.skip(
            f"SidebandCore not found at {p}/sbapp/sideband/core.py. "
            "Set SIDEBAND_SRC to a Sideband checkout."
        )
    return p


@pytest.fixture(scope="session")
def columba_peer(
    emulator_serial: str, rnsd_host: str, rnsd_port: int, prop_node_hex: str,
):
    peer = ColumbaPeer(
        serial=emulator_serial,
        rnsd_host=rnsd_host,
        rnsd_port=rnsd_port,
        prop_node_hex=prop_node_hex,
    )
    # Cold-start budget: the emulator's Reticulum backend takes 20-40s to
    # bootstrap from a fresh install. Subsequent fixture invocations (same
    # app PID still alive) complete in under 5s because GET_DEST returns
    # immediately.
    peer.start(ready_timeout=120.0)
    yield peer
    peer.stop()


@pytest.fixture(scope="session")
def sideband_peer(sideband_src: str, prop_node_hex: str):
    peer = SidebandPeer(
        sideband_src=sideband_src,
        prop_node_hex=prop_node_hex,
    )
    peer.start()
    yield peer
    peer.stop()


@dataclass
class InteropPair:
    columba: ColumbaPeer
    sideband: SidebandPeer

    @property
    def columba_hex(self) -> str:
        return self.columba.identity_hex

    @property
    def sideband_hex(self) -> str:
        return self.sideband.identity_hex


def _ensure_sideband_knows_columba(
    columba_peer: "ColumbaPeer",
    sideband_peer: "SidebandPeer",
    timeout: float = 30.0,
) -> bool:
    """Re-announce Columba until Sideband's RNS Identity cache resolves
    its destination. Idempotent — returns immediately if the cache is
    already populated. Called both at session start and per-test as a
    safety net against the cache going stale between long-running tests."""
    import RNS
    columba_bytes = bytes.fromhex(columba_peer.identity_hex)
    if RNS.Identity.recall(columba_bytes) is not None:
        return True
    deadline = time.time() + timeout
    while time.time() < deadline:
        columba_peer.broadcast("ANNOUNCE")
        time.sleep(3)
        if RNS.Identity.recall(columba_bytes) is not None:
            return True
    return False


def _ensure_columba_knows_sideband(
    columba_peer: "ColumbaPeer",
    sideband_peer: "SidebandPeer",
    timeout: float = 30.0,
) -> bool:
    """Make Columba's RNS cache hold Sideband's identity so subsequent
    `sendLxmfMessageWithMethod` calls don't stall on `requesting path`.

    Symmetric to `_ensure_sideband_knows_columba` — without Sideband's
    identity in Columba's RNS, the LXMF outbound path enters a long
    'requesting path' wait before it can encrypt the payload.

    We trigger Sideband's announce, then sleep + ANNOUNCE on the Columba
    side to coax its rnsd-side path table to learn the destination.
    Columba doesn't expose a 'do you have this identity?' probe, so we
    use the only proxy available: a short SEND_DIRECT to the target.
    If the receiver replies (msg_sent), the identity is cached; if it
    times out, we re-announce and retry."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if hasattr(sideband_peer._core, "lxmf_announce"):
            sideband_peer._core.lxmf_announce()
        # ANNOUNCE on Columba triggers a path probe — its cumulative
        # effect is to populate the path table on rnsd, which feeds back
        # into Columba's local cache on subsequent destinations.
        columba_peer.broadcast("ANNOUNCE")
        time.sleep(4)
        # Cheap proxy: try a one-shot SEND_DIRECT and watch for
        # `msg_sent` vs `requesting path`. If msg_sent fires within ~5s
        # the identity is cached.
        probe = f"_probe_{int(time.time() * 1000)}"
        columba_peer.clear_logcat()
        columba_peer.send_text(sideband_peer.identity_hex, probe)
        time.sleep(5)
        for line in columba_peer._read_logcat_lines():
            if "msg_sent" in line:
                return True
            if "requesting path" in line:
                break
    return False


@pytest.fixture(scope="session")
def interop(columba_peer: ColumbaPeer, sideband_peer: SidebandPeer):
    """The paired fixture — ensures both peers have a path to the other
    before any test runs. RNS announces propagate via the shared rnsd
    instance both peers are connected to; the gate below blocks until
    Columba can resolve Sideband's hash."""
    pair = InteropPair(columba=columba_peer, sideband=sideband_peer)

    # First broker-announce both peers so the shared rnsd's path table
    # actually knows about them. Columba already announces on start; we
    # request a fresh one to bust any stale entries from prior runs.
    columba_peer.broadcast("ANNOUNCE")
    # Sideband announces automatically when LXMRouter spins up; if
    # paths still don't resolve, force a fresh announce via SidebandCore.
    if hasattr(sideband_peer._core, "lxmf_announce"):
        sideband_peer._core.lxmf_announce()

    # Two-sided routing gate (one-time session warm-up):
    #
    # 1) Columba must know how to reach Sideband (`has_path_to`). This
    #    confirms rnsd's path table has Sideband's destination.
    #
    # 2) Sideband must have Columba's identity cached (Identity.recall).
    #    Without it, `SidebandCore.send_message` short-circuits to False
    #    because it can't build a Destination for the unknown peer.
    col_has_sb_path = columba_peer.has_path_to(
        sideband_peer.identity_hex, timeout=30
    )
    sb_has_col = _ensure_sideband_knows_columba(
        columba_peer, sideband_peer, timeout=30
    )
    col_has_sb_identity = _ensure_columba_knows_sideband(
        columba_peer, sideband_peer, timeout=60
    )

    if not (col_has_sb_path and sb_has_col and col_has_sb_identity):
        pytest.fail(
            "Path-readiness gate timed out:\n"
            f"  Columba path to Sideband: {col_has_sb_path}\n"
            f"  Sideband knows Columba identity: {sb_has_col}\n"
            f"  Columba knows Sideband identity: {col_has_sb_identity}\n"
            f"  Columba hex: {columba_peer.identity_hex}\n"
            f"  Sideband hex: {sideband_peer.identity_hex}\n"
            "Check that the host rnsd is reachable from both peers and "
            "that the shared instance is healthy."
        )

    yield pair


@pytest.fixture(autouse=True)
def _refresh_peer_state(request, interop):
    """Per-test safety net: re-announce Columba if Sideband's identity
    cache has aged out between tests. RNS keeps cached identities for a
    bounded window; long test runs occasionally drop them.

    Autouse so every test pays the (cheap, usually zero) cost without
    having to opt in."""
    # Skip for tests that don't take `interop` (none currently, but
    # safe to gate so the fixture stays cheap).
    if "interop" not in request.fixturenames:
        return
    _ensure_sideband_knows_columba(
        interop.columba, interop.sideband, timeout=15
    )
