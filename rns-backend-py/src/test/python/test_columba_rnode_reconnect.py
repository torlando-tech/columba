import importlib.util
import sys
import threading
import time
import types
import unittest
from pathlib import Path
from unittest import mock


INTERFACE_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/columba_rnode_interface.py"
)


class BaseInterface:
    MODE_FULL = 0
    MODE_GATEWAY = 1
    MODE_ACCESS_POINT = 2
    MODE_POINT_TO_POINT = 3
    MODE_ROAMING = 4
    MODE_BOUNDARY = 5

    def __init__(self):
        pass


class ScriptedBleBridge:
    """Mock the Kotlin BLE bridge across successive transport attempts."""

    def __init__(self, interface, connect_results):
        self.interface = interface
        self.connect_results = iter(connect_results)
        self.connect_calls = 0
        self.disconnect_calls = 0
        self.online_notifications = []
        self.callback_registered_at_connect = []
        self.connected = False
        self.connection_callback = None

    def isConnected(self):
        return self.connected

    def getConnectedDeviceName(self):
        return "Test RNode" if self.connected else None

    def connect(self, device_name, mode):
        self.connect_calls += 1
        self.callback_registered_at_connect.append(self.connection_callback is not None)
        self.connected = next(self.connect_results)
        if self.connected and self.connection_callback is not None:
            # Kotlin reports GATT transport readiness before Python finishes
            # RNode detection and radio configuration.
            self.connection_callback(True, device_name)
        return self.connected

    def setOnConnectionStateChanged(self, callback):
        self.connection_callback = callback

    def setOnDataReceived(self, callback):
        pass

    def read(self):
        return b""

    def disconnect(self):
        self.disconnect_calls += 1
        self.connected = False

    def notifyOnlineStatusChanged(self, is_online, interface_name):
        self.online_notifications.append(is_online)


class RNodeReconnectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        rns.LOG_DEBUG = 7
        rns.LOG_INFO = 6
        rns.LOG_WARNING = 4
        rns.LOG_ERROR = 3
        rns.log = lambda *args, **kwargs: None
        rns.Reticulum = types.SimpleNamespace(ANNOUNCE_CAP=2, MTU=500)
        rns.Transport = types.SimpleNamespace(inbound=lambda *args: None)

        interfaces = types.ModuleType("RNS.Interfaces")
        interface_module = types.ModuleType("RNS.Interfaces.Interface")
        interface_module.Interface = BaseInterface
        rns.Interfaces = interfaces

        sys.modules["RNS"] = rns
        sys.modules["RNS.Interfaces"] = interfaces
        sys.modules["RNS.Interfaces.Interface"] = interface_module

        spec = importlib.util.spec_from_file_location(
            "columba_rnode_reconnect_test", INTERFACE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)
        cls.Interface = cls.module.ColumbaRNodeInterface

    def new_interface(self):
        interface = self.Interface.__new__(self.Interface)
        interface.name = "Test RNode"
        interface.connection_mode = self.Interface.MODE_BLE
        interface.target_device_name = "Test RNode"
        interface._running = threading.Event()
        interface._read_thread = None
        interface._read_lock = threading.Lock()
        interface._start_lock = threading.Lock()
        interface._online_notification_lock = threading.RLock()
        interface._reconnect_lock = threading.Lock()
        interface._reconnect_thread = threading.current_thread()
        interface._reconnecting = True
        interface._reconnect_requested = False
        interface._reconnect_cancelled = threading.Event()
        interface._max_reconnect_attempts = 3
        interface._reconnect_interval = 0
        interface.online = False
        interface.detected = False
        interface._on_online_status_changed = None
        interface.usb_bridge = None
        return interface

    def test_ble_reconnect_survives_transport_and_configuration_failures(self):
        interface = self.new_interface()
        bridge = ScriptedBleBridge(interface, [False, True, True])
        interface.kotlin_bridge = bridge
        configuration_attempts = 0

        def configure_device():
            nonlocal configuration_attempts
            configuration_attempts += 1
            if configuration_attempts == 1:
                raise IOError("RNode did not answer detection after GATT connected")
            interface._set_online(True)

        interface._configure_device = configure_device
        real_sleep = time.sleep

        # Keep the production start/reconnect path while collapsing hardware
        # stabilization and polling delays to make the regression deterministic.
        with mock.patch.object(
            self.module.time,
            "sleep",
            side_effect=lambda _seconds: real_sleep(0.001),
        ):
            interface._reconnection_loop()

        self.assertEqual(3, bridge.connect_calls)
        self.assertEqual(2, configuration_attempts)
        self.assertTrue(all(bridge.callback_registered_at_connect))
        self.assertTrue(interface.online)
        self.assertFalse(interface._reconnecting)

        interface.stop()

    def test_ble_reconnect_stops_at_existing_attempt_limit(self):
        interface = self.new_interface()
        bridge = ScriptedBleBridge(interface, [False, False, False])
        interface.kotlin_bridge = bridge
        real_sleep = time.sleep

        with mock.patch.object(
            self.module.time,
            "sleep",
            side_effect=lambda _seconds: real_sleep(0.001),
        ):
            interface._reconnection_loop()

        self.assertEqual(3, bridge.connect_calls)
        self.assertFalse(interface.online)
        self.assertFalse(interface._reconnecting)

    def test_explicit_stop_cancels_reconnect_without_joining_itself(self):
        interface = self.new_interface()
        bridge = ScriptedBleBridge(interface, [False, False, False])
        interface.kotlin_bridge = bridge

        interface.stop()

        self.assertFalse(interface._reconnecting)
        self.assertEqual(1, bridge.disconnect_calls)

    def test_ble_reconnect_requires_fresh_protocol_state(self):
        interface = self.new_interface()
        interface.kotlin_bridge = ScriptedBleBridge(interface, [True])
        interface.detected = True
        interface.firmware_ok = True
        interface.interface_ready = True
        interface.r_frequency = 915000000
        interface.r_bandwidth = 125000
        interface.r_txpower = 7
        interface.r_sf = 7
        interface.r_cr = 5
        interface.r_state = 1

        def assert_fresh_state():
            self.assertFalse(interface.detected)
            self.assertFalse(interface.firmware_ok)
            self.assertFalse(interface.interface_ready)
            self.assertIsNone(interface.r_frequency)
            self.assertIsNone(interface.r_bandwidth)
            self.assertIsNone(interface.r_txpower)
            self.assertIsNone(interface.r_sf)
            self.assertIsNone(interface.r_cr)
            self.assertIsNone(interface.r_state)
            interface._set_online(True)

        interface._configure_device = assert_fresh_state
        real_sleep = time.sleep

        with mock.patch.object(
            self.module.time,
            "sleep",
            side_effect=lambda _seconds: real_sleep(0.001),
        ):
            self.assertTrue(interface.start())

        interface.stop()

    def test_explicit_stop_prevents_in_flight_reconnect_from_returning_online(self):
        interface = self.new_interface()
        interface.kotlin_bridge = ScriptedBleBridge(interface, [True])

        def configure_after_stop():
            interface.stop()
            interface._set_online(True)

        interface._configure_device = configure_after_stop
        real_sleep = time.sleep

        with mock.patch.object(
            self.module.time,
            "sleep",
            side_effect=lambda _seconds: real_sleep(0.001),
        ):
            self.assertFalse(interface.start())

        self.assertFalse(interface.online)
        self.assertFalse(interface._reconnecting)

    def test_late_disconnect_after_explicit_stop_does_not_restart_reconnect(self):
        interface = self.new_interface()
        bridge = ScriptedBleBridge(interface, [True])
        interface.kotlin_bridge = bridge

        interface.stop()
        interface._on_connection_state_changed(False, "Test RNode")

        self.assertTrue(interface._reconnect_cancelled.is_set())
        self.assertFalse(interface._reconnecting)
        self.assertIs(interface._reconnect_thread, threading.current_thread())

    def test_explicit_stop_orders_false_after_in_flight_true_notification(self):
        interface = self.new_interface()
        interface._reconnect_thread = None
        bridge = ScriptedBleBridge(interface, [True])
        interface.kotlin_bridge = bridge
        true_entered = threading.Event()
        release_true = threading.Event()
        stop_returned = threading.Event()
        notifications = []

        def online_callback(is_online):
            if is_online:
                true_entered.set()
                release_true.wait(timeout=1)
            notifications.append(is_online)

        interface._on_online_status_changed = online_callback
        online_thread = threading.Thread(target=interface._set_online, args=(True,))
        online_thread.start()
        self.assertTrue(true_entered.wait(timeout=1))

        stop_thread = threading.Thread(
            target=lambda: (interface.stop(), stop_returned.set())
        )
        stop_thread.start()

        self.assertFalse(stop_returned.wait(timeout=0.05))
        release_true.set()
        online_thread.join(timeout=1)
        stop_thread.join(timeout=1)

        self.assertTrue(stop_returned.is_set())
        self.assertEqual([True, False], notifications)
        self.assertFalse(interface.online)

    def test_online_observer_can_stop_interface_synchronously(self):
        interface = self.new_interface()
        interface._reconnect_thread = None
        interface.kotlin_bridge = ScriptedBleBridge(interface, [True])
        notifications = []

        def online_callback(is_online):
            notifications.append(is_online)
            if is_online:
                interface.stop()

        interface._on_online_status_changed = online_callback
        online_thread = threading.Thread(target=interface._set_online, args=(True,))
        online_thread.start()
        online_thread.join(timeout=1)

        self.assertFalse(online_thread.is_alive())
        self.assertEqual([True, False], notifications)
        self.assertEqual([False], interface.kotlin_bridge.online_notifications)
        self.assertFalse(interface.online)
        self.assertTrue(interface._reconnect_cancelled.is_set())

    def test_disconnect_during_success_teardown_is_not_lost(self):
        interface = self.new_interface()
        interface.kotlin_bridge = ScriptedBleBridge(interface, [True])
        attempts = 0

        def start():
            nonlocal attempts
            attempts += 1
            interface._set_online(True)
            if attempts == 1:
                interface._on_connection_state_changed(False, "Test RNode")
            return True

        interface.start = start
        interface._reconnection_loop()

        self.assertEqual(2, attempts)
        self.assertTrue(interface.online)
        self.assertFalse(interface._reconnecting)

    def test_start_attempts_are_single_flight(self):
        interface = self.new_interface()
        first_entered = threading.Event()
        release_first = threading.Event()
        active = 0
        max_active = 0
        active_lock = threading.Lock()

        def start_once():
            nonlocal active, max_active
            with active_lock:
                active += 1
                max_active = max(max_active, active)
            first_entered.set()
            release_first.wait(timeout=1)
            with active_lock:
                active -= 1
            return True

        interface._start_once = start_once
        first = threading.Thread(target=interface.start)
        second = threading.Thread(target=interface.start)
        first.start()
        self.assertTrue(first_entered.wait(timeout=1))
        second.start()
        time.sleep(0.05)
        self.assertEqual(1, max_active)
        release_first.set()
        first.join(timeout=1)
        second.join(timeout=1)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual(1, max_active)


if __name__ == "__main__":
    unittest.main()
