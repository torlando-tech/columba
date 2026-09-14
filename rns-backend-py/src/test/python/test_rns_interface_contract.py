"""Contract test: ColumbaRNodeInterface stays wire-compatible with the pinned RNS.

This is a *higher-level* regression test. It does NOT name the exact RNS
`Interface` callback signature that changed between 1.4.x and 1.5.x
(`sent_announce`/`received_announce`/`*_path_request` gained a `size=` keyword).
Instead it drives real announce traffic through RNS's OWN transport entry points
and only observes the adapter's own bookkeeping advance:

  * receive: ``RNS.Transport.inbound(raw, iface)`` — the same call RNS uses for
    every frame that arrives on an interface. RNS invokes the interface's
    ``received_announce`` here (with the 1.5.x ``size=`` keyword). If the
    adapter's contract drifts, RNS raises while processing the frame, drops the
    announce, and the adapter's incoming-announce deque never advances. THIS is
    the direction that discriminates the RNS 1.5.x breaking change: an RNode
    that receives no accounted announces cannot discover peers.
  * send:    ``iface.process_announce_queue()`` — RNS's own announce-queue drain
    (what transmits queued announces). It calls ``sent_announce`` positionally,
    so this is a wire-compatibility health check rather than the discriminator
    for the 1.5.x change specifically; it still guards the send path end to end.

Together the two directions prove the adapter is wire-compatible with the RNS we
actually ship, not with a remembered signature — regardless of *which* upstream
release changed the contract.

The test requires the real pinned RNS to be importable (see
``run_python_tests.py``, which installs the exact SHAs from build.gradle.kts).
In an environment without the pinned RNS it skips rather than fails, so the
stub-based unit suite keeps running anywhere.
"""

import importlib.util
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


MODULE_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/columba_rnode_interface.py"
)


def _real_rns_available():
    """True when the real (non-stub) RNS package can be imported."""
    try:
        import RNS  # noqa: F401
        # A stubbed RNS (used by other tests) has no ``_version``; the real one does.
        import RNS._version  # noqa: F401
        return True
    except Exception:
        return False


@unittest.skipUnless(_real_rns_available(), "real pinned RNS not importable in this environment")
class RnsInterfaceContractTests(unittest.TestCase):
    """Drive announces through RNS's own transport; the adapter must keep up."""

    @classmethod
    def setUpClass(cls):
        import RNS  # noqa: F401

        cls.rns = RNS
        # Isolated Reticulum instance so get_instance() is populated before any
        # Interface.__init__ runs (same ordering as the app: Reticulum() then
        # interfaces are discovered/constructed).
        cls._configdir = tempfile.mkdtemp(prefix="rns_iface_contract_")
        cls.rns.Reticulum(configdir=cls._configdir)
        time.sleep(0.3)

        # Load the REAL adapter module (no stubs — we want the live class).
        spec = importlib.util.spec_from_file_location(
            "columba_rnode_interface_contract", MODULE_PATH
        )
        cls.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.mod)

        from RNS.Interfaces.Interface import Interface
        cls._Interface = Interface

    @classmethod
    def tearDownClass(cls):
        # Deliberately do NOT call ``RNS.exit()`` here: it does ``os._exit(0)``,
        # which kills the process with code 0 *before* unittest can propagate a
        # non-zero exit code on failure — that would make the CI gate silently
        # green on a real regression. Instead we let the interpreter exit
        # naturally; RNS registered ``Reticulum.exit_handler`` on ``atexit``
        # during ``Reticulum.__init__``, so the graceful teardown (interface
        # detach, thread shutdown) still runs without the hard os._exit.
        pass

    def _make_interface(self):
        """Build the adapter through RNS's real base __init__ (as the app does
        via ``super().__init__()``), then set exactly the attributes RNS's
        ``interface_post_init`` and the announce path depend on. No BLE/USB
        bridge is touched.

        ``_read_lock`` + ``online=False`` are app-level attributes the live
        ``process_outgoing`` reads; with the interface offline it returns early
        ("Cannot send - interface is offline") without touching a bridge, so
        RNS's ``Transport.transmit`` -> ``process_outgoing`` chain runs cleanly
        and RNS still accounts the send via ``sent_announce``.
        """
        iface = self.mod.ColumbaRNodeInterface.__new__(self.mod.ColumbaRNodeInterface)
        self._Interface.__init__(iface)
        # interface_post_init assignments the announce path depends on:
        iface.ifac_size = 8
        iface.HW_MTU = 3072
        iface.mode = self._Interface.MODE_FULL
        iface.announce_cap = 10000
        iface.bitrate = 10000
        # app-level state so process_outgoing() takes the clean offline path:
        iface._read_lock = threading.Lock()
        iface.online = False
        return iface

    def _signed_announce_raw(self):
        """A valid, signed ANNOUNCE packet as bytes — built by RNS itself."""
        RNS = self.rns
        ident = RNS.Identity()
        remote = RNS.Destination(
            ident, RNS.Destination.IN, RNS.Destination.SINGLE, "contract_probe"
        )
        pkt = remote.announce(send=False)
        pkt.pack()
        self.assertTrue(pkt.raw and len(pkt.raw) > 0, "RNS produced no announce bytes")
        return pkt.raw

    def test_receive_announce_through_rns_inbound(self):
        """RNS's real inbound() must reach the adapter's received_announce path.

        Contract-agnostic: we only observe that the adapter accounted an
        announce (its incoming-announce frequency deque advanced). We never
        call received_announce ourselves and never reference its parameter list.
        """
        RNS = self.rns
        iface = self._make_interface()
        raw = self._signed_announce_raw()

        before = len(iface.ia_freq_deque)
        RNS.Transport.inbound(raw, iface)
        after = len(iface.ia_freq_deque)

        self.assertGreater(
            after,
            before,
            "RNS.Transport.inbound() did not account a received announce on the "
            "adapter — the receive callback contract has drifted from the pinned RNS.",
        )

    def test_send_announce_through_rns_queue(self):
        """RNS's own announce-queue drain must reach the adapter's
        sent_announce path.

        We seed the queue with one real announce entry (the same dict shape RNS
        appends) and let RNS's ``process_announce_queue()`` transmit + account it.
        Contract-agnostic: we observe the adapter's outgoing-announce deque
        advancing, not a direct call to sent_announce.
        """
        RNS = self.rns
        iface = self._make_interface()
        raw = self._signed_announce_raw()

        # RNS's own queue-entry shape (Transport.announce enqueue).
        iface.announce_queue = [{
            "destination": b"\x00" * 32,
            "time": time.time(),
            "hops": 1,
            "emitted": time.time(),
            "raw": raw,
        }]

        before = len(iface.oa_freq_deque)
        iface.process_announce_queue()
        after = len(iface.oa_freq_deque)

        self.assertGreater(
            after,
            before,
            "RNS announce-queue drain did not account a sent announce on the "
            "adapter — the send callback contract has drifted from the pinned RNS.",
        )

    def test_adapter_survives_real_rns_bootstrap(self):
        """The adapter class must be a valid RNS.Interface under the pinned
        version: the real base __init__ must run without raising, and the
        adapter must expose the announce bookkeeping state RNS relies on."""
        iface = self._make_interface()
        for attr in ("ia_freq_deque", "oa_freq_deque", "ifac_size", "HW_MTU",
                     "mode", "announce_cap"):
            self.assertTrue(
                hasattr(iface, attr),
                f"adapter missing RNS-required attribute {attr!r} under pinned RNS",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
