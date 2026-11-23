"""event_bridge.reconnect_rnode_interfaces re-arms live Columba RNode interfaces.

The companion device-presence event reaches Python through this one helper. It
must re-arm the reconnect supervisor for every RNode adapter and leave unrelated
interfaces alone; a broken adapter must not stop the remaining ones.
"""

import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class ColumbaRNodeInterface:
    def __init__(self, fail=False):
        self.fail = fail
        self.rearmed = 0

    def restart_reconnect(self):
        if self.fail:
            raise RuntimeError("supervisor re-arm failed")
        self.rearmed += 1


class AutoInterface:
    def __init__(self):
        self.rearmed = 0

    def restart_reconnect(self):
        self.rearmed += 1


class RNodeReconnectHelperTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.logged = []
        rns = types.ModuleType("RNS")
        for name, level in (("LOG_DEBUG", 1), ("LOG_WARNING", 2), ("LOG_ERROR", 3), ("LOG_NOTICE", 4)):
            setattr(rns, name, level)
        rns.log = lambda message, level=None, **_: cls.logged.append((message, level))
        rns.Transport = types.SimpleNamespace(interfaces=[])
        sys.modules["RNS"] = rns
        sys.modules["LXMF"] = types.ModuleType("LXMF")

        spec = importlib.util.spec_from_file_location("event_bridge_rnode_reconnect_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)
        cls.rns = rns

    def setUp(self):
        self.logged.clear()

    def test_rearms_only_columba_rnode_interfaces(self):
        first = ColumbaRNodeInterface()
        second = ColumbaRNodeInterface()
        unrelated = AutoInterface()
        self.rns.Transport.interfaces = [first, unrelated, second]

        self.assertEqual(2, self.module.reconnect_rnode_interfaces())
        self.assertEqual(1, first.rearmed)
        self.assertEqual(1, second.rearmed)
        self.assertEqual(0, unrelated.rearmed)

    def test_one_failed_rearm_does_not_skip_the_rest(self):
        broken = ColumbaRNodeInterface(fail=True)
        healthy = ColumbaRNodeInterface()
        self.rns.Transport.interfaces = [broken, healthy]

        self.assertEqual(1, self.module.reconnect_rnode_interfaces())
        self.assertEqual(1, healthy.rearmed)
        self.assertEqual(1, len(self.logged))


if __name__ == "__main__":
    unittest.main()
