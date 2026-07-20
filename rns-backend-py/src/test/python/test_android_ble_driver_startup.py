import importlib.util
import sys
import types
import unittest
from enum import Enum
from pathlib import Path


DRIVER_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/ble_modules/android_ble_driver.py"
)


class DriverState(Enum):
    IDLE = "idle"
    SCANNING = "scanning"
    ADVERTISING = "advertising"


class BLEDriverInterface:
    def __init__(self):
        self.on_error = None


class FakeBridge:
    def __init__(self):
        self.calls = []

    def setIdentity(self, identity):
        self.calls.append(("identity", bytes(identity)))

    def startScanningAsync(self):
        self.calls.append(("scan",))

    def stopScanningAsync(self):
        self.calls.append(("stop_scan",))

    def startAdvertisingAsync(self, name):
        self.calls.append(("advertise", name))

    def connectAsync(self, address):
        self.calls.append(("connect", address))


class AndroidBLEDriverStartupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 7)
        setattr(rns, "LOG_INFO", 6)
        setattr(rns, "LOG_WARNING", 4)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_EXTREME", 8)
        setattr(rns, "log", lambda *args, **kwargs: None)
        sys.modules["RNS"] = rns

        bluetooth_driver = types.ModuleType("bluetooth_driver")
        setattr(bluetooth_driver, "BLEDriverInterface", BLEDriverInterface)
        setattr(bluetooth_driver, "BLEDevice", object)
        setattr(bluetooth_driver, "DriverState", DriverState)
        sys.modules["bluetooth_driver"] = bluetooth_driver

        spec = importlib.util.spec_from_file_location(
            "android_ble_driver_startup_test", DRIVER_PATH
        )
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.Driver = module.AndroidBLEDriver

    def new_driver(self):
        driver = self.Driver()
        driver.kotlin_bridge = FakeBridge()
        return driver

    def test_scan_waits_for_identity_then_starts_in_order(self):
        driver = self.new_driver()

        driver.start_scanning()

        self.assertEqual([], driver.kotlin_bridge.calls)
        self.assertTrue(driver._deferred_scan_requested)
        self.assertEqual(DriverState.SCANNING, driver.state)

        identity = bytes(range(16))
        driver.set_identity(identity)

        self.assertEqual(
            [("identity", identity), ("scan",)], driver.kotlin_bridge.calls
        )
        self.assertFalse(driver._deferred_scan_requested)

    def test_stop_scanning_cancels_deferred_request(self):
        driver = self.new_driver()
        driver.start_scanning()

        driver.stop_scanning()
        driver.set_identity(b"x" * 16)

        self.assertEqual(
            [("stop_scan",), ("identity", b"x" * 16)],
            driver.kotlin_bridge.calls,
        )

    def test_connect_waits_for_identity_then_replays_once(self):
        driver = self.new_driver()
        address = "00:11:22:33:44:55"

        driver.connect(address)
        driver.connect(address)

        self.assertEqual([], driver.kotlin_bridge.calls)
        self.assertEqual({address}, driver._deferred_connection_addresses)

        identity = b"z" * 16
        driver.set_identity(identity)

        self.assertEqual(
            [("identity", identity), ("connect", address)],
            driver.kotlin_bridge.calls,
        )
        self.assertEqual(set(), driver._deferred_connection_addresses)

    def test_advertising_installs_identity_before_start(self):
        driver = self.new_driver()
        identity = b"y" * 16

        driver.start_advertising("Reticulum-Android", identity)

        self.assertEqual(
            [("identity", identity), ("advertise", "Reticulum-Android")],
            driver.kotlin_bridge.calls,
        )


if __name__ == "__main__":
    unittest.main()
