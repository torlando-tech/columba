"""Tests for event_bridge.install_tcp_reconnect_backoff.

BackboneInterface's anti-flap protection (on by default: connections
torn down inside 20 s count as flaps, >5 flaps ignores the client for
12 h) accepts the TCP handshake and closes the socket immediately, so a
blocked client's connect() *succeeds* and only the short connection
lifetime reveals the rejection. Upstream retries every 5 s forever in
both shapes. The guard paces refused connects and short-lived
connection cycles with an exponential backoff.
"""

import importlib.util
import os
import sys
import types
import unittest

EVENT_BRIDGE_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "main", "python", "event_bridge.py"
)


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def time(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def make_instance(outcomes):
    """Interface stand-in whose scripted connect runs the given outcomes.

    ``outcomes`` is a list of bools consumed per attempt (the last entry
    repeats). True returns success, False raises like a refused connect.
    """
    inst = types.SimpleNamespace(
        target_ip="10.0.0.1", target_port=4224, outcomes=list(outcomes), attempts=0
    )

    def scripted(self, *args, **kwargs):
        self.attempts += 1
        outcome = self.outcomes[min(self.attempts, len(self.outcomes)) - 1]
        if not outcome:
            raise OSError("connection refused")
        return True

    inst.scripted = types.MethodType(scripted, inst)
    return inst


def _fake_rns_and_lxmf():
    rns = types.ModuleType("RNS")
    rns.log = staticmethod(lambda *a, **k: None)
    rns.LOG_DEBUG = 5
    rns.LOG_INFO = 6
    rns.LOG_WARNING = 7
    rns.LOG_ERROR = 3
    interfaces = types.ModuleType("RNS.Interfaces")

    tcp_module = types.ModuleType("RNS.Interfaces.TCPInterface")

    class TCPClientInterface:
        @staticmethod
        def connect(self, *args, **kwargs):
            return self.scripted(*args, **kwargs)

    tcp_module.TCPClientInterface = TCPClientInterface

    backbone_module = types.ModuleType("RNS.Interfaces.BackboneInterface")

    class BackboneClientInterface:
        @staticmethod
        def connect(self, *args, **kwargs):
            return self.scripted(*args, **kwargs)

    backbone_module.BackboneClientInterface = BackboneClientInterface

    rns.Interfaces = interfaces
    sys.modules["RNS"] = rns
    sys.modules["RNS.Interfaces"] = interfaces
    sys.modules["RNS.Interfaces.TCPInterface"] = tcp_module
    sys.modules["RNS.Interfaces.BackboneInterface"] = backbone_module

    lxmf = types.ModuleType("LXMF")
    lxmf.LXMFMessage = type("LXMFMessage", (), {})
    lxmf.LXMFIdentity = type("LXMFIdentity", (), {})
    lxmf.LXMFDelivery = type("LXMFDelivery", (), {})
    lxmf.LXMFLink = type("LXMFLink", (), {})
    sys.modules["LXMF"] = lxmf
    return TCPClientInterface, BackboneClientInterface


class TcpReconnectBackoffTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tcp_class, cls.backbone_class = _fake_rns_and_lxmf()
        cls.original_tcp_connect = cls.tcp_class.connect
        cls.original_backbone_connect = cls.backbone_class.connect

        spec = importlib.util.spec_from_file_location(
            "event_bridge_tcp_backoff_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.sleeps = []
        self.clock = FakeClock()
        self._real_sleep = self.module.time.sleep
        self._real_time = self.module.time.time
        self.module.time.sleep = lambda seconds: self.sleeps.append(seconds)
        self.module.time.time = self.clock.time
        self.module.install_tcp_reconnect_backoff()

    def tearDown(self):
        self.module.time.sleep = self._real_sleep
        self.module.time.time = self._real_time
        type(self).tcp_class.connect = type(self).original_tcp_connect
        type(self).backbone_class.connect = type(self).original_backbone_connect

    def test_failures_back_off_exponentially_and_reraise(self):
        inst = make_instance([False] * 6)
        for _ in range(4):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertEqual([5.0, 10.0, 20.0, 40.0], self.sleeps)

    def test_delay_caps_at_max(self):
        inst = make_instance([False] * 10)
        for _ in range(8):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertTrue(all(d <= 300.0 for d in self.sleeps))
        self.assertEqual(300.0, self.sleeps[-1])

    def test_marathon_outage_does_not_overflow(self):
        # 2**n overflows float past n=1023; the exponent cap must keep the
        # delay arithmetic safe for arbitrarily long failure streaks.
        inst = make_instance([False] * 2000)
        for _ in range(1100):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertEqual(300.0, self.sleeps[-1])

    def test_healthy_gap_clears_failure_counter(self):
        inst = make_instance([False, False, True, False])
        for _ in range(2):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertTrue(self.tcp_class.connect(inst))
        # The connection lives past the healthy threshold, then dies; the
        # next failure starts the ladder again at base.
        self.clock.advance(self.module.TCP_CONNECT_HEALTHY_LIFETIME + 1)
        with self.assertRaises(OSError):
            self.tcp_class.connect(inst)
        self.assertEqual([5.0, 10.0, 5.0], self.sleeps)

    def test_short_lived_cycles_are_paced_without_exceptions(self):
        # Blocking-backbone shape: connect() always succeeds (kernel
        # accept) but the connection dies immediately; only the short
        # lifetime may slow the redial.
        inst = make_instance([True])
        for _ in range(4):
            self.assertTrue(self.tcp_class.connect(inst))
            self.clock.advance(5.0)  # loop wait + instant close
        self.assertEqual([5.0, 10.0, 20.0], self.sleeps)

    def test_long_lived_connection_redials_immediately(self):
        inst = make_instance([True])
        self.assertTrue(self.tcp_class.connect(inst))
        self.clock.advance(3600.0)  # a night of sleep, then a real drop
        self.assertTrue(self.tcp_class.connect(inst))
        self.assertEqual([], self.sleeps)

    def test_outage_after_healthy_connection_backs_off(self):
        # A long-lived connection drops and the server is then unreachable:
        # the previous-success state must be judged once, so the retry
        # episode accumulates toward the cap instead of resetting per try.
        inst = make_instance([True] + [False] * 10)
        self.assertTrue(self.tcp_class.connect(inst))
        self.clock.advance(3600.0)
        for _ in range(4):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertEqual([5.0, 10.0, 20.0, 40.0], self.sleeps)

    def test_counters_are_per_interface(self):
        a = make_instance([False])
        b = make_instance([False])
        for _ in range(2):
            with self.assertRaises(OSError):
                self.tcp_class.connect(a)
        with self.assertRaises(OSError):
            self.tcp_class.connect(b)
        # b's first failure pays the base wait, unaffected by a's ladder.
        self.assertEqual([5.0, 10.0, 5.0], self.sleeps)

    def test_backbone_client_is_patched_too(self):
        inst = make_instance([False, False, False])
        for _ in range(3):
            with self.assertRaises(OSError):
                self.backbone_class.connect(inst)
        self.assertEqual([5.0, 10.0, 20.0], self.sleeps)

    def test_install_is_idempotent(self):
        wrapped = self.tcp_class.connect
        self.module.install_tcp_reconnect_backoff()
        self.assertIs(wrapped, self.tcp_class.connect)


if __name__ == "__main__":
    unittest.main()
