"""Seam test: ColumbaRNodeInterface must stay an upstream subclass with our transport.

The adapter deletes Columba's own copy of the RNode protocol by inheriting
`RNS.Interfaces.Android.RNodeInterface`. Two things can silently break that:

  * the module stops subclassing the upstream class (someone reintroduces a
    fork of KISS/readLoop), or
  * the injected transport loses a member upstream's lifecycle depends on
    (`is_open`, `read`, `write`, `connected`, `CONNECT_TIMEOUT`,
    `device_disappeared`), which is what keeps a real radio online.

Both are checked here against the REAL pinned RNS (skipped when it is not
importable — `run_python_tests.py` installs the exact SHAs from
`build.gradle.kts`).
"""

import importlib.util
import sys
import threading
import types
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/columba_rnode_interface.py"
)


def _real_rns_available():
    try:
        import RNS  # noqa: F401
        import RNS._version  # noqa: F401
        return True
    except Exception:
        return False


class _FakeBridge:
    """Stand-in for KotlinRNodeBridge: just enough for the BLE open_port path."""

    def __init__(
        self,
        connected=False,
        device_name="RNode Test",
        write_result=None,
        connect_ok=True,
        failure_reason=None,
    ):
        self._connected = connected
        self._device_name = device_name
        self._write_result = write_result
        self._connect_ok = connect_ok
        self._failure_reason = failure_reason
        self._connecting = False
        self._auto_connect_script = []
        self.auto_connect_calls = 0
        self.written = []
        self.rx = b""

    def isConnected(self):
        return self._connected

    def getConnectedDeviceName(self):
        return self._device_name if self._connected else None

    def connect(self, device_name, mode):
        self._connected = self._connect_ok
        if self._connected:
            self._device_name = device_name
        return self._connect_ok

    def connectAuto(self, device_name, mode):
        self.auto_connect_calls += 1
        action = self._auto_connect_script.pop(0) if self._auto_connect_script else "connected"
        if action == "pending":
            self._connecting = True
            return False
        self._connecting = False
        self._connected = action == "connected"
        if self._connected:
            self._device_name = device_name
        return self._connected

    def isConnecting(self):
        return self._connecting

    def getLastConnectionFailure(self):
        return self._failure_reason

    def writeSync(self, data):
        self.written.append(bytes(data))
        # KotlinRNodeBridge reports -1 when Android rejects the write.
        return len(data) if self._write_result is None else self._write_result

    def read(self):
        data, self.rx = self.rx, b""
        return data

    def disconnect(self):
        self._connected = False
        self._connecting = False


class _FakeUsbBridge:
    """Stand-in for KotlinUSBBridge: permission handling plus a one-shot connect."""

    def __init__(
        self,
        device_id=1002,
        permission=False,
        grant=True,
        connect_ok=True,
    ):
        self.device_id = device_id
        self.permission = permission
        self.grant = grant
        self.connect_ok = connect_ok
        self.permission_requests = 0
        self.connected = False

    def findDeviceByVidPid(self, vendor_id, product_id):
        return self.device_id

    def hasPermission(self, device_id):
        return self.permission

    def requestPermissionBlocking(self, device_id, timeout_ms):
        # Chaquopy cannot use Kotlin default arguments: the adapter must pass both.
        self.permission_timeouts = getattr(self, "permission_timeouts", [])
        self.permission_timeouts.append(timeout_ms)
        self.permission_requests += 1
        self.permission = self.grant
        return self.grant

    def isConnected(self):
        return self.connected

    def connect(self, device_id, baud_rate):
        self.connected = self.connect_ok
        return self.connect_ok

    def disconnect(self):
        self.connected = False

    def read(self):
        return b""

    def write(self, data):
        return len(data)


@unittest.skipUnless(_real_rns_available(), "real pinned RNS not importable in this environment")
class RNodeUpstreamAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import RNS.Interfaces.Android.RNodeInterface as upstream

        cls.upstream = upstream
        spec = importlib.util.spec_from_file_location(
            "columba_rnode_interface_seam_test", MODULE_PATH
        )
        cls.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.mod)

    def _fake_event_bridge(self, bridge):
        module = types.ModuleType("event_bridge")
        module.get_rnode_bridge = lambda: bridge
        return module

    def _new_interface(self):
        cls = self.mod.ColumbaRNodeInterface
        iface = cls.__new__(cls)
        iface.name = "Test RNode"
        iface.connection_mode = cls.MODE_BLE
        iface.target_device_name = "RNode Test"
        iface.target_device_address = None
        iface._kotlin_bridge = None
        iface._usb_bridge = None
        iface.status_reason = None
        iface._online = False
        iface._online_lock = threading.Lock()
        iface.detached = False
        iface.hw_errors = []
        iface.serial = None
        iface.ble = None
        iface.tcp = None
        iface.use_ble = False
        iface.use_tcp = False
        iface.enable_framebuffer = False
        iface.last_imagedata = None
        iface._logo_requested = False
        iface.reconnect_w = 4.0
        iface.reconnect_lock = threading.Lock()
        iface._reconnect_thread = None
        iface._reconnect_wakeup = threading.Event()
        return iface

    def _open_ble(self, iface, bridge):
        previous = sys.modules.get("event_bridge")
        sys.modules["event_bridge"] = self._fake_event_bridge(bridge)
        try:
            connection = iface._open_bluetooth_port()
        finally:
            if previous is not None:
                sys.modules["event_bridge"] = previous
            else:
                sys.modules.pop("event_bridge", None)
        return connection

    def _ble_interface(self, bridge):
        return self._open_ble(self._new_interface(), bridge)

    def _fake_usb_bridge_module(self, bridge):
        module = types.ModuleType("usb_bridge")
        module.get_usb_bridge = lambda: bridge
        return module

    def _detach_ready(self, iface):
        iface.serial = None
        iface.ble = None
        iface.tcp = None
        iface.use_ble = False
        iface.use_tcp = False
        iface.enable_framebuffer = False
        iface.detached = False
        return iface

    def _new_usb_interface(self, vendor_id=0x303A, product_id=0x1001):
        iface = self._new_interface()
        iface.connection_mode = self.mod.ColumbaRNodeInterface.MODE_USB
        iface.usb_device_id = None
        iface.usb_vendor_id = vendor_id
        iface.usb_product_id = product_id
        iface._usb_permission_denied = False
        iface._usb_permission_next_attempt = 0.0
        return iface

    def _open_usb(self, iface, bridge):
        previous = sys.modules.get("usb_bridge")
        sys.modules["usb_bridge"] = self._fake_usb_bridge_module(bridge)
        try:
            return iface._open_usb_port()
        finally:
            if previous is not None:
                sys.modules["usb_bridge"] = previous
            else:
                sys.modules.pop("usb_bridge", None)

    def test_adapter_inherits_upstream_android_interface(self):
        self.assertTrue(
            issubclass(self.mod.ColumbaRNodeInterface, self.upstream.RNodeInterface),
            "the adapter must inherit upstream's protocol implementation",
        )
        # Its own KISS/protocol copy would be dead weight and drift.
        self.assertFalse(hasattr(self.mod, "KISS"))

    def test_ble_transport_satisfies_upstreams_connection_contract(self):
        bridge = _FakeBridge()
        connection = self._ble_interface(bridge)

        self.assertIsNotNone(connection)
        self.assertTrue(connection.is_open)
        self.assertFalse(connection.device_disappeared)
        self.assertGreaterEqual(connection.CONNECT_TIMEOUT, 1.0)

        bridge.rx = b"\xc0\x00payload"
        self.assertEqual(b"\xc0\x00payload", connection.read())

        self.assertEqual(3, connection.write(b"abc"))
        self.assertEqual([b"abc"], bridge.written)

        connection.close()
        self.assertFalse(bridge.isConnected())
        self.assertTrue(connection.device_disappeared)
        with self.assertRaises(IOError):
            connection.read()

    def test_rejected_write_is_not_reported_as_complete(self):
        bridge = _FakeBridge(write_result=-1)
        connection = self._ble_interface(bridge)

        # Upstream compares the returned count against len(frame) and raises
        # IOError on a mismatch: a rejected write has to stay a rejection.
        self.assertEqual(-1, connection.write(b"abc"))
        self.assertEqual([b"abc"], bridge.written)

    def test_write_while_disconnected_is_not_a_full_write(self):
        connection = self._ble_interface(_FakeBridge())
        self.assertEqual(3, connection.write(b"abc"))

        connection.close()
        self.assertEqual(0, connection.write(b"abc"))

    def test_pairing_required_is_surfaced_and_presence_rearms_reconnect(self):
        # Android reports the broken bond; the UI keys off status_reason ==
        # "pairing_required" to show the warning and the repair action.
        bridge = _FakeBridge(connect_ok=False, failure_reason="pairing_required")
        iface = self._new_interface()
        self.assertIsNone(self._open_ble(iface, bridge))
        self.assertEqual("pairing_required", iface.status_reason)

        # A broken bond must not strand the interface forever: a presence event
        # (or the UI's repair/reconnect action) clears both latches and wakes
        # the supervisor. The supervisor then retries with the long pairing
        # backoff instead of declaring the interface permanently dead.
        with mock.patch.object(iface, "_start_reconnect_thread") as start:
            iface.restart_reconnect()
        start.assert_called_once()
        self.assertIsNone(iface.status_reason)
        self.assertTrue(iface._reconnect_wakeup.is_set())
        self.assertEqual([], iface.hw_errors)

    def test_reconnect_retries_despite_latched_hardware_error(self):
        # Upstream's supervisor exits forever when configure_device appends a
        # hardware/validation error. Our supervisor clears that latch per
        # attempt; this pins the behavior a range drop used to break.
        bridge = _FakeBridge(connected=True)
        iface = self._new_interface()
        iface.hw_errors = [{"error": 1, "description": "modem timeout"}]

        with mock.patch.object(iface, "configure_device") as configure:
            configure.side_effect = lambda: setattr(iface, "online", True)
            previous = sys.modules.get("event_bridge")
            sys.modules["event_bridge"] = self._fake_event_bridge(bridge)
            try:
                iface.reconnect_port()
            finally:
                if previous is not None:
                    sys.modules["event_bridge"] = previous
                else:
                    sys.modules.pop("event_bridge", None)

        self.assertTrue(iface.online)
        self.assertEqual([], iface.hw_errors)
        configure.assert_called_once()

    def test_reconnect_uses_auto_connect_and_polls_pending_gatt(self):
        bridge = _FakeBridge(connected=False)
        bridge._auto_connect_script = ["pending"]
        iface = self._new_interface()

        previous = sys.modules.get("event_bridge")
        sys.modules["event_bridge"] = self._fake_event_bridge(bridge)
        try:
            with mock.patch.object(iface, "configure_device") as configure:
                configure.side_effect = lambda: setattr(iface, "online", True)

                # First attempt arms a pending autoConnect (one GATT).
                self.assertTrue(iface._reconnect_once())
                self.assertEqual(1, bridge.auto_connect_calls)
                self.assertTrue(bridge.isConnecting())
                self.assertIsNone(iface.serial)

                # While pending, polling must not stack another GATT.
                self.assertTrue(iface._reconnect_once())
                self.assertEqual(1, bridge.auto_connect_calls)

                # The controller reported success between polls: attach and
                # configure the radio.
                bridge._connecting = False
                bridge._connected = True
                self.assertTrue(iface._reconnect_once())
                self.assertTrue(iface.online)
                self.assertIsNotNone(iface.serial)
                configure.assert_called_once()
        finally:
            if previous is not None:
                sys.modules["event_bridge"] = previous
            else:
                sys.modules.pop("event_bridge", None)

    def test_successful_connect_clears_the_status_reason(self):
        iface = self._new_interface()
        iface.status_reason = "pairing_required"
        self.assertIsNotNone(self._open_ble(iface, _FakeBridge()))
        self.assertIsNone(iface.status_reason)

    def test_ble_open_port_refuses_to_steal_the_bridge(self):
        bridge = _FakeBridge(connected=True, device_name="RNode Other")
        self.assertIsNone(self._ble_interface(bridge))
        self.assertEqual("RNode Other", bridge.getConnectedDeviceName())


    # ---- USB permission + detach ----

    def test_usb_open_requests_permission_before_connecting(self):
        # A reinstall drops the USB grant; the Python path must ask for it the way
        # the Kotlin backend does instead of looping on "No permission".
        bridge = _FakeUsbBridge(permission=False, grant=True)
        iface = self._new_usb_interface()

        connection = self._open_usb(iface, bridge)

        self.assertIsInstance(connection, self.mod.BridgeConnection)
        self.assertEqual(1, bridge.permission_requests)
        self.assertEqual(
            [self.mod.ColumbaRNodeInterface.USB_PERMISSION_TIMEOUT_MS],
            bridge.permission_timeouts,
        )

    def test_usb_open_does_not_repeat_a_denied_prompt(self):
        bridge = _FakeUsbBridge(permission=False, grant=False)
        iface = self._new_usb_interface()

        self.assertIsNone(self._open_usb(iface, bridge))
        self.assertIsNone(self._open_usb(iface, bridge))

        self.assertEqual(1, bridge.permission_requests)
        self.assertTrue(iface._usb_permission_denied)

    def test_permission_answered_after_the_wait_still_connects(self):
        # The system dialog outlives the blocking wait: the interface must pick up
        # a grant the user gave after the request returned, not demand a re-enable.
        bridge = _FakeUsbBridge(permission=False, grant=False)
        iface = self._new_usb_interface()

        self.assertIsNone(self._open_usb(iface, bridge))
        self.assertTrue(iface._usb_permission_denied)

        bridge.permission = True
        self.assertIsNotNone(self._open_usb(iface, bridge))
        self.assertFalse(iface._usb_permission_denied)


    def test_failed_ble_contention_does_not_own_the_shared_bridge(self):
        # The bridge is a process-wide singleton serving one RNode. The interface
        # that loses the contention never opened it, so detaching it must not
        # disconnect the working radio.
        bridge = _FakeBridge(connected=True, device_name="RNode Other")
        iface = self._detach_ready(self._new_interface())
        self.assertIsNone(self._open_ble(iface, bridge))
        self.assertIsNone(iface._kotlin_bridge)

        iface.detach()

        self.assertTrue(bridge.isConnected())

    def test_failed_usb_open_does_not_own_the_shared_bridge(self):
        bridge = _FakeUsbBridge(device_id=-1, permission=True)
        bridge.connected = True
        iface = self._detach_ready(self._new_usb_interface())
        self.assertIsNone(self._open_usb(iface, bridge))
        self.assertIsNone(iface._usb_bridge)

        iface.detach()

        self.assertTrue(bridge.connected)

    def test_failed_usb_permission_does_not_own_the_shared_bridge(self):
        bridge = _FakeUsbBridge(permission=False, grant=False)
        bridge.connected = True
        iface = self._detach_ready(self._new_usb_interface())
        self.assertIsNone(self._open_usb(iface, bridge))
        self.assertIsNone(iface._usb_bridge)

        iface.detach()

        self.assertTrue(bridge.connected)
    def test_detach_survives_a_transport_that_never_opened(self):
        # open_port() never succeeded (permission denied, radio unplugged), so
        # serial is None. Upstream's detach() would raise AttributeError and
        # Reticulum would report the failed detach as "needs a restart".
        iface = self._new_interface()
        iface.serial = None
        iface.ble = None
        iface.tcp = None
        iface.use_ble = False
        iface.use_tcp = False
        iface.enable_framebuffer = False
        iface.detached = False
        bridge = _FakeUsbBridge(permission=True)
        bridge.connected = True
        iface._usb_bridge = bridge

        iface.detach()

        self.assertTrue(iface.detached)
        self.assertFalse(bridge.connected)

    def test_detach_survives_an_unopened_ble_transport(self):
        iface = self._new_interface()
        iface.serial = None
        iface.ble = None
        iface.tcp = None
        iface.use_ble = True
        iface.use_tcp = False
        iface.enable_framebuffer = False
        iface.detached = False
        bridge = _FakeBridge(connected=True)
        iface._kotlin_bridge = bridge

        iface.detach()

        self.assertTrue(iface.detached)
        self.assertFalse(bridge.isConnected())

    # ---- radio configuration validation ----

    def _radio_state(self, **overrides):
        """Configured state plus the matching echo, as `validateRadioState()` compares them."""
        iface = self._new_interface()
        iface.platform = 0
        iface.frequency = 869525000
        iface.r_frequency = 869525000
        iface.bandwidth = 125000
        iface.r_bandwidth = 125000
        iface.txpower = 1
        iface.r_txpower = 11
        iface.sf = 7
        iface.r_sf = 7
        iface.state = 1
        iface.r_state = 1
        for key, value in overrides.items():
            setattr(iface, key, value)
        return iface

    def _validate(self, iface):
        # Upstream waits 1-2 s for the echoes before comparing; the adapter's
        # wait loop is skipped unless the TX power echo is still missing.
        with mock.patch.object(self.upstream, "sleep", lambda *_: None):
            return iface.validateRadioState()

    def test_device_rewritten_tx_power_is_adopted_not_fatal(self):
        # The firmware clamps/quantises TX power and echoes its own value: 1 dBm
        # on a 735D reports 11 dBm (PA gain table floor). The interface has to
        # come online at the achievable power instead of aborting startup.
        iface = self._radio_state()

        self.assertTrue(self._validate(iface))
        self.assertEqual(11, iface.txpower)

    def test_adoption_does_not_mask_the_other_radio_parameters(self):
        iface = self._radio_state(r_sf=9)

        self.assertFalse(self._validate(iface))
        # The TX power was still reconciled, so a later restart does not repeat
        # the mismatch for a parameter the device never rewrote.
        self.assertEqual(11, iface.txpower)

    def test_missing_tx_power_echo_is_left_to_upstreams_validation(self):
        # No echo means the device never reported a power: adopt nothing and
        # let upstream's comparison decide (it fails, as before this change).
        iface = self._radio_state(r_txpower=None)
        fake_time = types.SimpleNamespace(sleep=lambda *_: None, time=lambda: float("inf"))

        with mock.patch.object(self.mod, "time", fake_time):
            self.assertFalse(self._validate(iface))
        self.assertEqual(1, iface.txpower)

    def test_unset_tx_power_sentinel_is_not_adopted(self):
        # 0xFF is the firmware's "not configured" sentinel, not a power level.
        iface = self._radio_state(r_txpower=0xFF)

        self.assertFalse(self._validate(iface))
        self.assertEqual(1, iface.txpower)


if __name__ == "__main__":
    unittest.main()
