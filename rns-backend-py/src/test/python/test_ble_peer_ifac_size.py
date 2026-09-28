"""Regression test: the pinned ble-reticulum BLEPeerInterface carries ifac_size.

Companion to the pin bump that moves ble-reticulum to the ifac_size fix
(torlando-tech/ble-reticulum#45). RNS 1.5.x's ``Transport.preprocess_inbound``
reads ``interface.ifac_size`` on EVERY inbound packet. The base ``Interface``
does not set it; RNS assigns it on top-level interfaces and every other
spawned-interface pattern copies it from the parent (AutoInterface,
TCPInterface, I2P, Weave, Backbone). The spawned ``BLEPeerInterface``
previously skipped it, so every inbound packet on a BLE peer raised
``AttributeError`` and no peer announce was ever ingested ("peer interfaces
spawn but neither sees the other's announces").

This test drives the REAL pinned ``BLEPeerInterface.__init__`` (not a mock)
against the wheel ``run_python_tests.py`` installs from build.gradle.kts, and
asserts ``ifac_size`` is inherited from the parent. It therefore fails against
a future pin that regresses the fix, even though the one-line change lives in
the ble-reticulum wheel. It is contract-agnostic: it names no RNS version and
no specific RNS read site, so it stays valid across RNS upgrades.

Requires the real pinned RNS + ble-reticulum to be importable (the runner
installs the exact SHAs). In an environment without them it skips rather than
fails, matching the stub-based unit suite's portability.
"""

import shutil
import tempfile
import time
import unittest
from unittest.mock import Mock


def _real_ble_available():
    """True when the real (non-stub) RNS and ble_reticulum packages import.

    Matches the import style of ``test_rns_interface_contract.py``: a stubbed
    RNS (used by the unit tests) has no ``_version`` submodule, so its presence
    is the reliable "real RNS" signal.
    """
    try:
        import RNS  # noqa: F401
        import RNS._version  # noqa: F401  (a stubbed RNS has no _version)
        from ble_reticulum.BLEInterface import BLEPeerInterface  # noqa: F401
        return True
    except Exception:
        return False


@unittest.skipUnless(
    _real_ble_available(),
    "real pinned RNS/ble-reticulum not importable in this environment",
)
class BlePeerIfacSizeTests(unittest.TestCase):
    """The pinned BLEPeerInterface must inherit ifac_size from its parent."""

    @classmethod
    def setUpClass(cls):
        import RNS

        cls.rns = RNS
        # Isolated Reticulum instance so get_instance() is populated before any
        # Interface.__init__ runs (the RNS base __init__ reads ingress-control
        # defaults off the singleton). Same ordering as the app.
        cls._configdir = tempfile.mkdtemp(prefix="ble_peer_ifac_")
        cls.rns.Reticulum(configdir=cls._configdir)
        time.sleep(0.3)

        from ble_reticulum.BLEInterface import BLEPeerInterface

        cls._BLEPeerInterface = BLEPeerInterface

    @classmethod
    def tearDownClass(cls):
        # Deliberately NOT calling RNS.exit(): it does os._exit(0), which kills
        # the process with code 0 before unittest can propagate a non-zero exit
        # on failure. Let the interpreter exit naturally; the graceful teardown
        # still runs via RNS's atexit handler.
        if getattr(cls, "_configdir", None):
            shutil.rmtree(cls._configdir, ignore_errors=True)
            cls._configdir = None

    def _make_parent(self, ifac_size):
        parent = Mock()
        parent.HW_MTU = 512
        parent.bitrate = 700000
        parent.ifac_size = ifac_size
        return parent

    def test_ifac_size_inherited_from_parent(self):
        """A spawned BLE peer interface copies ifac_size from the parent.

        This is the exact invariant RNS's inbound path relies on: without it,
        every inbound packet on a BLE peer raises AttributeError and announces
        are never ingested.
        """
        parent = self._make_parent(ifac_size=1500)
        peer = self._BLEPeerInterface(parent, "AA:BB:CC:DD:EE:FF", "TestPeer")
        self.assertEqual(peer.ifac_size, 1500)

    def test_ifac_size_tracks_parent_value(self):
        """Different parent ifac_size values propagate unchanged."""
        for expected in (0, 1, 128, 1500, 4096):
            with self.subTest(ifac_size=expected):
                peer = self._BLEPeerInterface(
                    self._make_parent(ifac_size=expected),
                    "AA:BB:CC:DD:EE:FF",
                    "TestPeer",
                )
                self.assertEqual(peer.ifac_size, expected)

    def test_ifac_size_present_even_when_parent_has_none(self):
        """Construction must not crash when the parent lacks ifac_size.

        A parent without the attribute (e.g. a non-IFAC config) must still
        produce a peer interface with ifac_size defined (None), so the inbound
        path never hits a missing attribute.
        """
        parent = Mock(spec=["HW_MTU", "bitrate"])  # no ifac_size attribute
        parent.HW_MTU = 512
        parent.bitrate = 700000
        peer = self._BLEPeerInterface(parent, "AA:BB:CC:DD:EE:FF", "TestPeer")
        self.assertTrue(hasattr(peer, "ifac_size"))
        self.assertIsNone(peer.ifac_size)


if __name__ == "__main__":
    unittest.main()
