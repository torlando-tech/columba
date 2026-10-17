import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


def make_fake_transport():
    """Fresh Transport stand-in with recording inbound/outbound.

    Mirrors the real `RNS.Transport` shape the hook sees: module-level
    callables invoked as `RNS.Transport.inbound(raw, interface)` /
    `RNS.Transport.outbound(packet)`.
    """

    class FakeTransport:
        def __init__(self):
            self.incoming = []
            self.outgoing = []

        def inbound(self, raw, interface=None):
            self.incoming.append(raw)

        def outbound(self, packet):
            self.outgoing.append(packet)

    return FakeTransport()


class TrafficActivityHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Transport", make_fake_transport())
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
        # Fresh Transport stand-in + module globals per test: the patch
        # guard rides the wrapper functions, so a new Transport re-triggers
        # install cleanly.
        self.transport = make_fake_transport()
        sys.modules["RNS"].Transport = self.transport

        self.module._traffic_activity_callback = None
        self.module._traffic_activity_last_emit = 0.0
        self.signals = []
        self.callback = types.SimpleNamespace(
            onEvent=lambda payload: self.signals.append(payload)
        )

    def tearDown(self):
        self.module._traffic_activity_callback = None
        self.module._traffic_activity_last_emit = 0.0

    def test_inbound_is_wrapped_and_signals(self):
        """Regression: interfaces that call RNS.Transport.inbound directly
        (ColumbaRNodeInterface._process_incoming, the announce path) must
        request the traffic lock — patching base Interface.process_incoming
        alone missed them."""
        self.module.install_traffic_activity_callback(self.callback)

        self.transport.inbound(b"packet", interface=object())

        self.assertEqual([b"packet"], self.transport.incoming)
        self.assertEqual(1, len(self.signals))

    def test_outbound_is_wrapped_and_signals(self):
        """Regression: ColumbaRNodeInterface.process_outgoing never calls
        the base method, so only the Transport.outbound choke point sees
        its TX."""
        self.module.install_traffic_activity_callback(self.callback)

        self.transport.outbound(b"packet")

        self.assertEqual([b"packet"], self.transport.outgoing)
        self.assertEqual(1, len(self.signals))

    def test_signal_is_throttled(self):
        self.module.install_traffic_activity_callback(self.callback)

        self.transport.inbound(b"one")
        self.transport.inbound(b"two")
        self.transport.outbound(b"three")

        # All packets still traverse the original bodies...
        self.assertEqual([b"one", b"two"], self.transport.incoming)
        self.assertEqual([b"three"], self.transport.outgoing)
        # ...but the Kotlin hop fires once per throttle window.
        self.assertEqual(1, len(self.signals))

        # Simulate the window elapsing: the next burst signals again.
        self.module._traffic_activity_last_emit = (
            self.module._monotonic() - self.module.TRAFFIC_ACTIVITY_MIN_INTERVAL_S - 1
        )
        self.transport.inbound(b"four")
        self.assertEqual(2, len(self.signals))

    def test_no_callback_means_no_signal_but_original_runs(self):
        self.module.install_traffic_activity_callback(None)

        self.transport.inbound(b"packet")

        self.assertEqual([b"packet"], self.transport.incoming)
        self.assertEqual([], self.signals)

    def test_install_is_idempotent(self):
        self.module.install_traffic_activity_callback(self.callback)
        wrapped_in = self.transport.inbound
        wrapped_out = self.transport.outbound

        self.module.install_traffic_activity_callback(self.callback)

        # A second install must leave the wrappers untouched — identity,
        # not behavior: nested wrappers would still run the original once
        # and the throttle would mask the extra signal attempts.
        self.assertIs(wrapped_in, self.transport.inbound)
        self.assertIs(wrapped_out, self.transport.outbound)

        self.transport.inbound(b"packet")
        self.assertEqual([b"packet"], self.transport.incoming)
        self.assertEqual(1, len(self.signals))


if __name__ == "__main__":
    unittest.main()
