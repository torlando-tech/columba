"""Tests for event_bridge.install_tcp_reconnect_backoff.

A TCP gateway enforcing anti-flap policy refuses repeated connection
attempts; upstream RNS retries every 5 s forever, which is the traffic
shape such a host punishes. The guard paces failed connects with an
exponential backoff and clears it on success.
"""

import importlib.util
import os
import sys
import types
import unittest

EVENT_BRIDGE_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "main", "python", "event_bridge.py"
)


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


class TcpReconnectBackoffTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
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
        rns.Interfaces = interfaces
        sys.modules["RNS"] = rns
        sys.modules["RNS.Interfaces"] = interfaces
        sys.modules["RNS.Interfaces.TCPInterface"] = tcp_module
        cls.tcp_class = TCPClientInterface
        cls.original_connect = TCPClientInterface.connect

        lxmf = types.ModuleType("LXMF")
        lxmf.LXMFMessage = type("LXMFMessage", (), {})
        lxmf.LXMFIdentity = type("LXMFIdentity", (), {})
        lxmf.LXMFDelivery = type("LXMFDelivery", (), {})
        lxmf.LXMFLink = type("LXMFLink", (), {})
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_tcp_backoff_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.sleeps = []
        self._real_sleep = self.module.time.sleep
        self.module.time.sleep = lambda seconds: self.sleeps.append(seconds)
        self.module.install_tcp_reconnect_backoff()

    def tearDown(self):
        self.module.time.sleep = self._real_sleep
        type(self).tcp_class.connect = type(self).original_connect

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

    def test_success_clears_failure_counter(self):
        inst = make_instance([False, False, True, False])
        for _ in range(2):
            with self.assertRaises(OSError):
                self.tcp_class.connect(inst)
        self.assertTrue(self.tcp_class.connect(inst))
        # Counter reset: the next failure starts the ladder again at base.
        with self.assertRaises(OSError):
            self.tcp_class.connect(inst)
        self.assertEqual([5.0, 10.0, 5.0], self.sleeps)

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

    def test_install_is_idempotent(self):
        wrapped = self.tcp_class.connect
        self.module.install_tcp_reconnect_backoff()
        self.assertIs(wrapped, self.tcp_class.connect)


if __name__ == "__main__":
    unittest.main()
