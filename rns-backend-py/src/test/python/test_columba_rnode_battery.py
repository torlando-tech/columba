"""Unit tests for RNode KISS battery (CMD_STAT_BAT 0x27) parsing.

Loads the real columba_rnode_interface module with a stubbed RNS base and
drives a single battery frame through the real _read_loop parse path.
"""
import importlib.util
import sys
import threading
import types
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[2] / "main/python/columba_rnode_interface.py"


def _load_module():
    # Stub RNS + the Interface base so the module imports without the full stack.
    rns = types.ModuleType("RNS")
    rns.LOG_ERROR = 3
    rns.LOG_DEBUG = 1
    rns.LOG_WARNING = 2
    rns.log = lambda *a, **k: None
    interfaces_pkg = types.ModuleType("RNS.Interfaces")
    iface_mod = types.ModuleType("RNS.Interfaces.Interface")
    iface_mod.Interface = object  # base-class stand-in
    sys.modules["RNS"] = rns
    sys.modules["RNS.Interfaces"] = interfaces_pkg
    sys.modules["RNS.Interfaces.Interface"] = iface_mod
    spec = importlib.util.spec_from_file_location("columba_rnode_interface_battery_test", MODULE_PATH)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class _FakeBridge:
    """Yields one KISS frame, then signals the read loop to stop."""

    def __init__(self, frame, iface):
        self._frame = frame
        self._iface = iface
        self._reads = 0

    def read(self):
        self._reads += 1
        if self._reads == 1:
            return self._frame
        self._iface._running.clear()
        return b""


class RNodeBatteryParseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load_module()

    def _make_iface(self):
        # Skip the heavy __init__; _read_loop only touches the fields we set here.
        iface = object.__new__(self.mod.ColumbaRNodeInterface)
        iface._read_lock = threading.Lock()
        iface._running = threading.Event()
        iface.r_stat_bat = None
        iface.r_stat_rssi = None
        iface.r_stat_snr = None
        # A battery frame can only be parsed while connected, so a frame-driven
        # interface is online.
        iface.online = True
        return iface

    def test_kiss_stat_bat_command_is_0x27(self):
        self.assertEqual(self.mod.KISS.CMD_STAT_BAT, 0x27)

    def test_battery_frame_updates_r_stat_bat_and_get_battery(self):
        iface = self._make_iface()
        # KISS frame: FEND(0xC0) CMD_STAT_BAT(0x27) value(0x52=82) FEND(0xC0)
        iface.kotlin_bridge = _FakeBridge(bytes([0xC0, 0x27, 0x52, 0xC0]), iface)
        iface._running.set()
        iface._read_loop()  # one iteration parses the frame; the fake bridge then clears _running
        self.assertEqual(iface.get_battery(), 82)

    def test_get_battery_defaults_to_none(self):
        iface = self._make_iface()
        self.assertIsNone(iface.get_battery())

    def test_get_battery_returns_none_when_offline(self):
        # Regression: on a transient BLE/USB drop the interface stays registered
        # but goes offline. A cached battery frame must NOT keep being reported -
        # get_battery() has to return None so the AIDL layer maps it to the -1
        # absent sentinel (and the notification poller stops re-posting the stale
        # percentage on a dead RNode).
        iface = self._make_iface()
        iface.r_stat_bat = 82
        iface.online = False
        self.assertIsNone(iface.get_battery())


if __name__ == "__main__":
    unittest.main()
