import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


def make_fake_interface_class():
    """Fresh Interface stand-in with recording process_incoming/outgoing."""

    class FakeInterface:
        def __init__(self):
            self.incoming = []
            self.outgoing = []

        def process_incoming(self, data):
            self.incoming.append(data)

        def process_outgoing(self, data):
            self.outgoing.append(data)

    return FakeInterface


class TrafficActivityHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Transport", types.SimpleNamespace())
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(
            lxmf,
            "LXStamper",
            types.SimpleNamespace(set_external_generator=lambda *args: None),
        )
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_traffic_activity_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        # Fresh Interface class + module globals per test: the patch guard
        # lives on the class, so a new class re-triggers install cleanly.
        self.interface_cls = make_fake_interface_class()
        iface_mod = types.ModuleType("RNS.Interfaces.Interface")
        setattr(iface_mod, "Interface", self.interface_cls)
        sys.modules["RNS.Interfaces"] = types.ModuleType("RNS.Interfaces")
        sys.modules["RNS.Interfaces.Interface"] = iface_mod
        # Mirror the parent-attribute wiring a real package import performs,
        # so `from RNS.Interfaces.Interface import Interface` resolves.
        sys.modules["RNS"].Interfaces = sys.modules["RNS.Interfaces"]
        sys.modules["RNS.Interfaces"].Interface = iface_mod

        self.module._traffic_activity_callback = None
        self.module._traffic_activity_last_emit = 0.0
        self.signals = []
        self.callback = types.SimpleNamespace(
            onEvent=lambda payload: self.signals.append(payload)
        )

    def tearDown(self):
        self.module._traffic_activity_callback = None
        self.module._traffic_activity_last_emit = 0.0

    def test_install_wraps_process_incoming_and_signals(self):
        self.module.install_traffic_activity_callback(self.callback)

        iface = self.interface_cls()
        iface.process_incoming(b"packet")

        self.assertEqual([b"packet"], iface.incoming)
        self.assertEqual(1, len(self.signals))

    def test_install_wraps_process_outgoing_and_signals(self):
        self.module.install_traffic_activity_callback(self.callback)

        iface = self.interface_cls()
        iface.process_outgoing(b"packet")

        self.assertEqual([b"packet"], iface.outgoing)
        self.assertEqual(1, len(self.signals))

    def test_signal_is_throttled(self):
        self.module.install_traffic_activity_callback(self.callback)

        iface = self.interface_cls()
        iface.process_incoming(b"one")
        iface.process_incoming(b"two")
        iface.process_outgoing(b"three")

        # All packets still traverse the original bodies...
        self.assertEqual([b"one", b"two"], iface.incoming)
        self.assertEqual([b"three"], iface.outgoing)
        # ...but the Kotlin hop fires once per throttle window.
        self.assertEqual(1, len(self.signals))

        # Simulate the window elapsing: the next burst signals again.
        self.module._traffic_activity_last_emit = (
            self.module._monotonic() - self.module.TRAFFIC_ACTIVITY_MIN_INTERVAL_S - 1
        )
        iface.process_incoming(b"four")
        self.assertEqual(2, len(self.signals))

    def test_no_callback_means_no_signal_but_original_runs(self):
        self.module.install_traffic_activity_callback(None)

        iface = self.interface_cls()
        iface.process_incoming(b"packet")

        self.assertEqual([b"packet"], iface.incoming)
        self.assertEqual([], self.signals)

    def test_install_is_idempotent(self):
        self.module.install_traffic_activity_callback(self.callback)
        self.module.install_traffic_activity_callback(self.callback)

        iface = self.interface_cls()
        iface.process_incoming(b"packet")

        # Double-wrapping would record the packet twice (original runs once
        # per wrapper layer) — one recording proves a single wrap.
        self.assertEqual([b"packet"], iface.incoming)
        self.assertEqual(1, len(self.signals))


if __name__ == "__main__":
    unittest.main()
