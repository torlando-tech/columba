"""Unit tests for event_bridge.install_rns_guards (busy AutoInterface data port).

Upstream RNS panics (os._exit) when AutoInterface.final_init raises; the guard
must turn EADDRINUSE into an offline AutoInterface and let anything else through.
"""
import errno
import importlib.util
import sys
import types
import unittest
from pathlib import Path

EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class _AutoInterface:
    """Stands in for RNS.Interfaces.AutoInterface.AutoInterface."""

    error = None

    def __init__(self):
        self.online = None
        self.detached = False
        self.data_port = 42671

    def final_init(self):
        if _AutoInterface.error is not None:
            self.detached = True  # upstream detaches before re-raising
            raise _AutoInterface.error
        self.online = True

    def __str__(self):
        return "AutoInterface[Auto Discovery]"


class RnsGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.logged = []
        rns = types.ModuleType("RNS")
        for name, level in (("LOG_DEBUG", 1), ("LOG_WARNING", 2), ("LOG_ERROR", 3), ("LOG_NOTICE", 4)):
            setattr(rns, name, level)
        setattr(rns, "log", lambda msg, level=None, **_: cls.logged.append((msg, level)))
        interfaces_pkg = types.ModuleType("RNS.Interfaces")
        auto_mod = types.ModuleType("RNS.Interfaces.AutoInterface")
        auto_mod.AutoInterface = _AutoInterface
        sys.modules["RNS"] = rns
        sys.modules["RNS.Interfaces"] = interfaces_pkg
        sys.modules["RNS.Interfaces.AutoInterface"] = auto_mod
        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", types.SimpleNamespace(set_external_generator=lambda *a: None))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location("event_bridge_rns_guards_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)
        cls.original_final_init = _AutoInterface.final_init
        cls.module.install_rns_guards()

    def setUp(self):
        _AutoInterface.error = None
        self.logged.clear()

    def test_busy_data_port_leaves_interface_offline_instead_of_raising(self):
        _AutoInterface.error = OSError(errno.EADDRINUSE, "Address already in use")
        iface = _AutoInterface()
        iface.final_init()  # must not raise (would reach RNS.panic upstream)
        self.assertFalse(iface.online)
        self.assertTrue(iface.detached)
        self.assertEqual(1, len(self.logged))
        self.assertIn("42671", self.logged[0][0])

    def test_other_os_errors_still_propagate(self):
        _AutoInterface.error = OSError(errno.EACCES, "Permission denied")
        with self.assertRaises(OSError):
            _AutoInterface().final_init()

    def test_non_os_errors_still_propagate(self):
        _AutoInterface.error = ValueError("bad config")
        with self.assertRaises(ValueError):
            _AutoInterface().final_init()

    def test_normal_start_is_untouched(self):
        iface = _AutoInterface()
        iface.final_init()
        self.assertTrue(iface.online)
        self.assertEqual([], self.logged)

    def test_install_is_idempotent(self):
        guarded = _AutoInterface.final_init
        self.module.install_rns_guards()
        self.assertIs(guarded, _AutoInterface.final_init)
        self.assertIsNot(self.original_final_init, _AutoInterface.final_init)


if __name__ == "__main__":
    unittest.main()
