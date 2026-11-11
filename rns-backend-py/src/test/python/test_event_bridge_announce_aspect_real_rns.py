"""Real-RNS regression for per-aspect announce filtering (PR #1264).

The stub-RNS sibling (``test_event_bridge_announce_aspect_filter.py``) proves
the module-side contract, but a stubbed ``register_announce_handler`` never
exercises RNS's own name-hash dispatch. This test drives real announces for
tracked, unrelated, path-response and discovery aspects through
``Transport._inbound`` on the pinned fork and pins the four properties the
stub cannot:

  1. a tracked announce reaches the dispatch queue with its aspect,
  2. an untracked aspect never enters the queue,
  3. path responses are delivered flagged, not dropped,
  4. ``deregister_callbacks()`` removes exactly event_bridge's handlers —
     RNS's own discovery registration (``rnstransport.discovery.interface``,
     the class Discovery.py registers) survives and keeps receiving.

Run via ``run_python_tests.py`` against the pinned wheels.
"""
import subprocess
import sys
import unittest
from pathlib import Path

PYTHON_ROOT = Path(__file__).resolve().parents[2] / "main/python"


class AnnounceAspectRealRnsTests(unittest.TestCase):
    def test_announce_aspect_filtering_with_real_rns(self):
        # Sibling tests replace RNS in sys.modules. A clean interpreter
        # prevents those stand-ins from silently weakening this one.
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--real-aspect"],
            capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


def _run_real_aspect_tests():
    import tempfile
    import threading
    import time
    from pathlib import Path

    import RNS
    import RNS.Discovery

    sys.path.insert(0, str(PYTHON_ROOT))
    from importlib import import_module
    bridge = import_module("event_bridge")

    DISCOVERY_ASPECT = "rnstransport.discovery.interface"

    class FakeIface:
        # Only what Transport._inbound touches on the announce path; the
        # values are upstream defaults (no stamp cost, no rate limiting).
        name = "real-aspect"
        online = True
        reports_phy_stats = False
        destination_cost = 0
        announce_rate_target = None
        announce_rate_timeout = 300
        announce_rate_average = 0
        announce_rate_consecutive = 0
        gravity = None

        def get_hash(self):
            return b"fake-interface-hash"

        def __str__(self):
            return "FakeIface[real-aspect]"

    class Cb:
        def __init__(self):
            self.payloads = []

        def onEvent(self, payload):
            self.payloads.append(payload)

    class SpyDiscoveryHandler(RNS.Discovery.InterfaceAnnounceHandler):
        # Exactly the object RNS.Discovery registers itself; the spy only
        # records so the test can observe coexistence.
        def __init__(self):
            super().__init__(callback=lambda *args, **kwargs: None)
            self.seen = []

        def received_announce(self, destination_hash, announced_identity, app_data):
            self.seen.append(destination_hash)

    class RealAspectTests(unittest.TestCase):
        @classmethod
        def setUpClass(cls):
            cls.directory = tempfile.TemporaryDirectory()
            Path(cls.directory.name, "config").write_text(
                "[reticulum]\nshare_instance = no\nenable_transport = no\n"
                "\n[interfaces]\n",
                encoding="utf-8",
            )
            cls.reticulum = RNS.Reticulum(
                configdir=cls.directory.name, loglevel=RNS.LOG_ERROR
            )
            # Stands in for RNS's own AutoInterface discovery registration,
            # registered before event_bridge — teardown must not touch it.
            cls.spy = SpyDiscoveryHandler()
            RNS.Transport.register_announce_handler(cls.spy)

        @classmethod
        def tearDownClass(cls):
            RNS.Transport.deregister_announce_handler(cls.spy)
            RNS.Transport._should_run = False
            RNS.Reticulum.exit_handler()
            cls.directory.cleanup()

        def setUp(self):
            self.spy.seen.clear()
            self.cb = Cb()
            bridge.register_callbacks(
                RNS.Transport, None, self.cb, None, None, None, None,
            )

        def tearDown(self):
            bridge.deregister_callbacks()

        def drain(self):
            # inbound_job drains Transport.pending_callbacks on the RNS
            # thread; run the same jobs synchronously, then let the
            # event_bridge dispatch worker emit.
            deadline = time.time() + 2.0
            while time.time() < deadline:
                while RNS.Transport.pending_callbacks:
                    job = RNS.Transport.pending_callbacks.pop(0)
                    job.start()
                    job.join()
                time.sleep(0.02)
                if self.cb.payloads or self.spy.seen:
                    return
            self._settle(0.3)

        def _settle(self, seconds):
            time.sleep(seconds)

        def feed(self, aspects, app_data=b"appdata", path_response=False):
            identity = RNS.Identity()
            dest = RNS.Destination(
                identity, RNS.Destination.IN, RNS.Destination.SINGLE, *aspects
            )
            packet = dest.announce(
                app_data=app_data, path_response=path_response, send=False
            )
            # The announce is for a remote node: unregister the IN
            # destination so Transport processes it as an inbound announce.
            RNS.Transport.deregister_destination(dest)
            packet.pack()
            rx = RNS.Packet(None, packet.raw)
            self.assertTrue(rx.unpack())
            rx.receiving_interface = FakeIface()
            RNS.Transport._inbound(rx)
            return dest

        def test_tracked_announce_delivers_with_aspect(self):
            dest = self.feed(("lxmf", "delivery"))
            self.drain()
            hits = [p for p in self.cb.payloads if p["aspect"] == "lxmf.delivery"]
            self.assertEqual(1, len(hits), f"expected one lxmf.delivery payload, got {self.cb.payloads}")
            self.assertEqual(dest.hash.hex(), hits[0]["destination_hash"])
            self.assertFalse(hits[0]["is_path_response"])

        def test_unrelated_aspect_never_reaches_queue(self):
            baseline = len(self.cb.payloads)
            self.feed(("nomadnetwork", "repeater"))
            self.drain()
            self._settle(0.5)
            self.assertEqual(baseline, len(self.cb.payloads),
                             "an untracked aspect entered the dispatch queue")

        def test_path_response_flagged_and_delivered(self):
            baseline = len(self.cb.payloads)
            self.feed(("lxmf", "propagation"), path_response=True)
            self.drain()
            new = self.cb.payloads[baseline:]
            hits = [p for p in new if p["is_path_response"]]
            self.assertEqual(1, len(hits),
                             "path response was dropped or not flagged")
            self.assertEqual("lxmf.propagation", hits[0]["aspect"])

        def test_discovery_announce_goes_to_rns_handler_not_bridge(self):
            baseline = len(self.cb.payloads)
            dest = self.feed(("rnstransport", "discovery", "interface"))
            self.drain()
            self.assertEqual([dest.hash], self.spy.seen,
                             "event_bridge's registration starved RNS's own discovery handler")
            self.assertEqual(baseline, len(self.cb.payloads),
                             "a discovery announce entered event_bridge")

        def test_deregister_leaves_foreign_discovery_handler(self):
            bridge.deregister_callbacks()
            key = RNS.Destination.name_hash(DISCOVERY_ASPECT)
            self.assertIn(self.spy, RNS.Transport.announce_handlers.get(key, []),
                          "deregister_callbacks evicted another owner's handler")
            mine = [
                handler
                for handlers in RNS.Transport.announce_handlers.values()
                for handler in handlers
                if isinstance(handler, bridge._AnnounceHandler)
            ]
            self.assertEqual([], mine, "teardown left event_bridge handlers registered")

            # And the surviving registration still works after teardown.
            dest = self.feed(("rnstransport", "discovery", "interface"))
            self.drain()
            self.assertEqual([dest.hash], self.spy.seen)

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RealAspectTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    if "--real-aspect" in sys.argv:
        raise SystemExit(_run_real_aspect_tests())
    unittest.main()
