"""ColumbaRNodeInterface — upstream's Android RNode interface with Columba transports.

`RNS.Interfaces.Android.RNodeInterface` owns the complete RNode implementation:
KISS framing, detect/init/validate, the read loop, framebuffer, battery, airtime
limits and TCP mode. Its single transport seam is the connection object it stores
in `self.serial` from `open_port()`; the class only requires

    is_open, read(), write(bytes) -> int, close(), cleanup()
    connected, CONNECT_TIMEOUT, device_disappeared   (BLE lifecycle)

Upstream cannot supply those connections under Chaquopy: its BLE path is built on
`able` (pyjnius GATT) and its Bluetooth-Classic path on `pyjnius` BluetoothSocket,
both unusable in an embedded Python. Columba already owns byte-level transports in
Kotlin (`KotlinRNodeBridge` for BLE/Classic, `KotlinUSBBridge` for USB-serial), so
this module injects them and inherits everything else from upstream.

Two lifecycle seams are overridden: `validateRadioState()` adopts the TX power
the device echoes (firmware clamps/quantises it) instead of failing the interface,
and `reconnect_port()` owns a supervisor that keeps retrying after upstream would
park forever on `hw_errors`/`pairing_required`; BLE retries use the bridge's
`connectAuto()` so a radio that left range for hours reconnects when it returns.
The RNode companion service's device-presence event calls
`event_bridge.reconnect_rnode_interfaces()` to re-arm this supervisor.

Deployment (unchanged): `event_bridge` copies this file to
`<configdir>/interfaces/ColumbaRNodeInterface.py`, and `RnsConfigFile` emits
`type = ColumbaRNodeInterface` for BLE/Classic/USB RNode interfaces (TCP RNode
uses upstream `type = RNodeInterface` directly).

Config keys consumed here: `connection_mode` (ble|classic|usb|tcp),
`target_device_name`, `target_device_address`, `usb_device_id`,
`usb_vendor_id`/`usb_product_id`, `enable_framebuffer`.
"""

import RNS
import threading
import time

from RNS.Interfaces.Interface import Interface
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface as AndroidRNodeInterface


class BridgeConnection:
    """Stream adapter exposing an RNode transport the way upstream expects.

    Upstream treats the object as `self.serial`: non-blocking `read()`, `write()`
    returning the byte count, `is_open`, plus the BLE lifecycle members
    (`connected`, `CONNECT_TIMEOUT`, `device_disappeared`, `close`, `cleanup`).
    """

    CONNECT_TIMEOUT = 7.0

    def __init__(self, name, read_fn, write_fn, connected_fn, close_fn=None):
        self.name = name
        self._read = read_fn
        self._write = write_fn
        self._connected = connected_fn
        self._close = close_fn

    @property
    def connected(self):
        try:
            return bool(self._connected())
        except Exception:  # noqa: BLE001
            return False

    @property
    def is_open(self):
        return self.connected

    @property
    def in_waiting(self):
        return 0

    @property
    def device_disappeared(self):
        return not self.connected

    def read(self, *args, **kwargs):
        if not self.connected:
            raise IOError(f"RNode transport for {self.name} is not connected")
        return bytes(self._read() or b"")

    def write(self, data):
        data = bytes(data)
        if not self.connected:
            return 0
        written = self._write(data)
        # writeSync/write report -1 when Android rejects the write; keep that
        # value so upstream's `written != len(frame)` check raises IOError
        # instead of accepting a frame the radio never received.
        return 0 if written is None else written

    def close(self):
        if self._close is not None:
            try:
                self._close()
            except Exception as e:  # noqa: BLE001
                RNS.log(f"Error closing RNode transport for {self.name}: {e}", RNS.LOG_DEBUG)

    def cleanup(self):
        self.close()


class ColumbaRNodeInterface(AndroidRNodeInterface):
    """Upstream Android RNode interface with Kotlin-backed BLE/Classic/USB transports."""

    MODE_BLE = "ble"
    MODE_CLASSIC = "classic"
    MODE_USB = "usb"
    MODE_TCP = "tcp"

    # After a missed permission prompt, wait this long before asking again.
    USB_PERMISSION_RETRY_S = 60.0
    # Chaquopy cannot use Kotlin default arguments, so the blocking permission
    # request always gets an explicit timeout (the Kotlin default is the same).
    USB_PERMISSION_TIMEOUT_MS = 30000

    # Reconnect policy. BLE uses KotlinRNodeBridge.connectAuto() (GATT
    # autoConnect), which waits for the radio to reappear instead of failing a
    # direct connect every few seconds. The poll interval is only used while a
    # connect is pending; failures back off so a radio that stays out of range
    # for hours does not hammer the Android BLE stack. pairing_required is a
    # genuine bond loss that retrying cannot repair, so it gets a long backoff -
    # but never a permanent latch.
    RECONNECT_POLL_S = 1.0
    RECONNECT_BACKOFF_MAX_S = 30.0
    PAIRING_RETRY_S = 60.0

    def __init__(self, owner, configuration):
        c = Interface.get_config_obj(configuration)
        self.connection_mode = (c["connection_mode"] if "connection_mode" in c else self.MODE_CLASSIC) or self.MODE_CLASSIC
        self.connection_mode = str(self.connection_mode).lower()
        self.target_device_name = c["target_device_name"] if "target_device_name" in c else None
        self.target_device_address = c["target_device_address"] if "target_device_address" in c else None
        self.usb_device_id = int(c["usb_device_id"]) if "usb_device_id" in c and c["usb_device_id"] is not None else None
        self.usb_vendor_id = int(c["usb_vendor_id"]) if "usb_vendor_id" in c and c["usb_vendor_id"] is not None else None
        self.usb_product_id = int(c["usb_product_id"]) if "usb_product_id" in c and c["usb_product_id"] is not None else None
        self.enable_framebuffer = c.as_bool("enable_framebuffer") if "enable_framebuffer" in c else False

        self._online = False
        # Contract attribute read by PythonRnsTransportAdmin (`status_reason`):
        # the UI shows the pairing warning + repair action when it is
        # "pairing_required".
        self.status_reason = None
        self._online_lock = threading.Lock()
        self._kotlin_bridge = None
        self._usb_bridge = None
        self._logo_requested = False
        # Android drops USB grants when the app is reinstalled. Track the outcome
        # so the reconnect loop asks once instead of opening a dialog every retry.
        self._usb_permission_denied = False
        self._usb_permission_next_attempt = 0.0
        # One supervisor thread per interface; the shared reconnect_lock is the
        # actual single-runner guard (upstream may also spawn a thread when the
        # first open_port() fails).
        self._reconnect_thread = None
        self._reconnect_wakeup = threading.Event()

        # Upstream's own connections (able / pyjnius) must never be built here:
        # `allow_bluetooth` would construct AndroidBluetoothManager -> autoclass.
        # RnsConfigFile emits ble/classic as force_ble, usb as a serial port name.
        super().__init__(owner, configuration)

        self._display_logo()

        # Upstream only starts its periodic reconnect when the first failure left
        # hw_errors empty. A transient validation/read error during startup
        # latches the interface offline forever. Own the supervisor here so the
        # radio is retried even when upstream's condition was false.
        if not self.online and not self.detached:
            self._start_reconnect_thread()

    # ---- online state: mirror transitions to the Kotlin bridge ----

    @property
    def online(self):
        return self._online

    @online.setter
    def online(self, value):
        value = bool(value)
        with getattr(self, "_online_lock", threading.Lock()):
            changed = getattr(self, "_online", False) != value
            self._online = value
        if changed:
            self._notify_online_status(value)
            if value and self.enable_framebuffer and not self._logo_requested:
                # Upstream only re-displays last_imagedata from reconnect_port(),
                # so the first connection has to show the logo itself. Off the
                # validation path so a slow write cannot delay going online.
                self._logo_requested = True
                threading.Thread(target=self._display_logo, daemon=True).start()

    def _notify_online_status(self, online):
        # The Kotlin side raises/dismisses the "RNode Disconnected" heads-up
        # notification from this listener. USB has its own detach broadcast path
        # and its bridge has no online-status listener, so it is filtered out.
        if self.connection_mode == self.MODE_USB:
            return
        bridge = self._kotlin_bridge
        if bridge is None or not hasattr(bridge, "notifyOnlineStatusChanged"):
            return
        try:
            bridge.notifyOnlineStatusChanged(online, self.name)
        except Exception as e:  # noqa: BLE001
            RNS.log(f"Failed to notify Kotlin bridge of online status change: {e}", RNS.LOG_DEBUG)

    # ---- transports ----

    def open_port(self):
        if self.connection_mode == self.MODE_TCP:
            return super().open_port()

        if self.connection_mode == self.MODE_USB:
            connection = self._open_usb_port()
        else:
            connection = self._open_bluetooth_port()

        if connection is None:
            self.serial = None
            self.ble = None
            raise IOError(f"Could not connect RNode '{self.name}' via {self.connection_mode}")

        self.serial = connection
        self.ble = connection if self.connection_mode in (self.MODE_BLE, self.MODE_CLASSIC) else None
        self.port = f"{self.connection_mode}://{self.target_device_name or self.name}"

    def _open_bluetooth_port(self):
        import event_bridge

        # Local until this interface actually owns the connection: the bridge is a
        # process-wide singleton, and detach() closes whatever _kotlin_bridge holds.
        bridge = event_bridge.get_rnode_bridge()
        if bridge is None:
            RNS.log("ColumbaRNodeInterface: KotlinRNodeBridge unavailable — RNode stays offline", RNS.LOG_ERROR)
            return None

        if not self.target_device_name:
            RNS.log(f"ColumbaRNodeInterface '{self.name}': no target_device_name configured", RNS.LOG_ERROR)
            return None
        already = None
        try:
            if bridge.isConnected():
                already = bridge.getConnectedDeviceName()
        except Exception as e:  # noqa: BLE001
            RNS.log(f"ColumbaRNodeInterface: bridge contention check failed (continuing): {e}", RNS.LOG_DEBUG)

        if already and already != self.target_device_name:
            # KotlinRNodeBridge is a process-wide singleton with one connection
            # and one read buffer. A second interface must not reconnect under
            # the first one's feet and split its byte stream.
            RNS.log(
                f"ColumbaRNodeInterface: KotlinRNodeBridge already serving '{already}'; "
                f"'{self.target_device_name}' stays offline until the other RNode is disabled",
                RNS.LOG_ERROR,
            )
            return None

        kotlin_mode = "ble" if self.connection_mode == self.MODE_BLE else "classic"
        RNS.log(f"Connecting to RNode '{self.target_device_name}' via "
               f"{'BLE' if kotlin_mode == 'ble' else 'Bluetooth Classic'}...", RNS.LOG_INFO)
        if not bridge.connect(self.target_device_name, kotlin_mode):
            reason = None
            try:
                reason = bridge.getLastConnectionFailure()
            except Exception:  # noqa: BLE001
                pass
            RNS.log(f"ColumbaRNodeInterface: failed to connect '{self.target_device_name}' ({reason or 'no reason reported'})", RNS.LOG_ERROR)
            self.status_reason = reason
            return None

        self.status_reason = None
        self._kotlin_bridge = bridge
        return BridgeConnection(
            name=self.name,
            read_fn=bridge.read,
            write_fn=bridge.writeSync,
            connected_fn=bridge.isConnected,
            close_fn=bridge.disconnect,
        )

    def _start_reconnect_thread(self):
        if self.detached or self.online:
            return
        if self._reconnect_thread is not None and self._reconnect_thread.is_alive():
            return
        self._reconnect_thread = threading.Thread(
            target=self.reconnect_port,
            daemon=True,
            name=f"RNodeReconnect[{self.name}]",
        )
        self._reconnect_thread.start()

    def restart_reconnect(self):
        """Re-arm the supervisor after a device-presence event or a UI action.

        Clears both latches: pairing_required (Android may need to re-encrypt a
        stale but valid bond) and hw_errors (upstream treats any hardware/validation
        error as permanent). Wakes a sleeping supervisor instead of waiting for
        its backoff to expire.
        """
        if self.detached or self.online:
            return
        self.status_reason = None
        self.hw_errors.clear()
        self._reconnect_wakeup.set()
        self._start_reconnect_thread()

    def _bridge_failure(self, bridge):
        try:
            return bridge.getLastConnectionFailure()
        except Exception:  # noqa: BLE001
            return None

    def _close_reconnect_transport(self):
        connections = []
        if self.serial is not None:
            connections.append(self.serial)
        if self.ble is not None and self.ble is not self.serial:
            connections.append(self.ble)
        self.serial = None
        self.ble = None
        for connection in connections:
            try:
                connection.close()
            except Exception as e:  # noqa: BLE001
                RNS.log(f"{self} closing stale transport failed: {e}", RNS.LOG_DEBUG)

    def _reconnect_once(self):
        """One connection attempt. True means it made progress (including a
        pending BLE autoConnect), so the supervisor should poll again soon."""
        if self.serial is not None or self.ble is not None:
            self._close_reconnect_transport()

        if self.connection_mode == self.MODE_USB:
            self.open_port()
            if self.serial is not None and self.serial.is_open:
                self.configure_device()
                return True
            return False

        import event_bridge

        bridge = event_bridge.get_rnode_bridge()
        if bridge is None:
            RNS.log("ColumbaRNodeInterface: KotlinRNodeBridge unavailable — RNode stays offline", RNS.LOG_ERROR)
            return False
        if not self.target_device_name:
            RNS.log(f"ColumbaRNodeInterface '{self.name}': no target_device_name configured", RNS.LOG_ERROR)
            return False

        if bridge.isConnected():
            already = None
            try:
                already = bridge.getConnectedDeviceName()
            except Exception as e:  # noqa: BLE001
                RNS.log(f"{self} bridge contention check failed (continuing): {e}", RNS.LOG_DEBUG)
            if already and already != self.target_device_name:
                RNS.log(
                    f"ColumbaRNodeInterface: KotlinRNodeBridge already serving '{already}'; "
                    f"'{self.target_device_name}' stays offline until the other RNode is disabled",
                    RNS.LOG_ERROR,
                )
                return False
            self.open_port()
            if self.serial is not None and self.serial.is_open:
                self.configure_device()
            return True

        if self.connection_mode == self.MODE_BLE and hasattr(bridge, "connectAuto"):
            # A pending autoConnect is already armed in the GATT stack; polling is
            # all we should do until it succeeds or times out.
            if hasattr(bridge, "isConnecting") and bridge.isConnecting():
                return True
            self.status_reason = None
            if bridge.connectAuto(self.target_device_name, "ble"):
                return True
            if hasattr(bridge, "isConnecting") and bridge.isConnecting():
                return True
            self.status_reason = self._bridge_failure(bridge)
            return False

        # Bluetooth Classic has no autoConnect equivalent: retry the blocking SPP
        # connect with the supervisor's backoff.
        if not bridge.connect(self.target_device_name, self.connection_mode):
            self.status_reason = self._bridge_failure(bridge)
            return False
        return True

    def reconnect_port(self):
        if self.detached or self.online:
            return
        if self.connection_mode == self.MODE_TCP:
            return super().reconnect_port()

        if not self.reconnect_lock.acquire(blocking=False):
            return
        try:
            backoff = max(float(self.reconnect_w), self.RECONNECT_POLL_S)
            while not self.online and not self.detached:
                # Upstream latches hw_errors permanently: configure_device appends
                # one and the supervisor loop exits forever. A radio that vanished
                # or rebooted must keep retrying, so clear the latches per attempt
                # and rely on the backoff (and pairing backoff) for pacing.
                self.hw_errors.clear()
                progressed = False
                try:
                    progressed = self._reconnect_once()
                except Exception as e:  # noqa: BLE001
                    RNS.log(f"{self} reconnect attempt failed: {e}", RNS.LOG_ERROR)
                    self.online = False
                    self._close_reconnect_transport()

                if self.online:
                    RNS.log(f"Reconnected {self}", RNS.LOG_INFO)
                    if self.enable_framebuffer and self.last_imagedata is not None:
                        self._logo_requested = True
                        self.display_image(self.last_imagedata)
                        self.enable_external_framebuffer()
                    break
                if self.detached:
                    break

                if self.status_reason == "pairing_required":
                    wait = self.PAIRING_RETRY_S
                elif progressed:
                    wait = self.RECONNECT_POLL_S
                else:
                    wait = backoff
                    backoff = min(backoff * 2.0, self.RECONNECT_BACKOFF_MAX_S)
                self._reconnect_wakeup.wait(wait)
                self._reconnect_wakeup.clear()
        finally:
            self.reconnect_lock.release()

    def detach(self):
        """Detach even when the transport never opened.

        Upstream's detach() finishes with `self.ble.close()` / `self.serial.close()`,
        assuming open_port() succeeded. A transport that never came up - USB
        permission missing, radio unplugged, bridge held by another interface -
        leaves those members None, so the AttributeError aborts Reticulum's
        detach_interface() and disabling the interface fails with "needs a restart
        to apply" instead of detaching. Close whatever bridge exists instead.
        """
        # An interface can detach while its BLE autoConnect is still pending
        # (radio absent). Cancel that specific GATT so it cannot establish a
        # link after the interface is disabled; do not touch another radio's
        # connection.
        if self.connection_mode in (self.MODE_BLE, self.MODE_CLASSIC) and self.target_device_name:
            try:
                import event_bridge

                bridge = event_bridge.get_rnode_bridge()
                if bridge is not None and hasattr(bridge, "cancelAutoConnect"):
                    bridge.cancelAutoConnect(self.target_device_name)
            except Exception as e:  # noqa: BLE001
                RNS.log(f"ColumbaRNodeInterface: cancelling pending autoConnect failed: {e}", RNS.LOG_DEBUG)
        try:
            super().detach()
            return
        except Exception as e:  # noqa: BLE001
            RNS.log(f"ColumbaRNodeInterface: detach on an unopened transport ({e})", RNS.LOG_WARNING)
        finally:
            # Mark detached before waking a supervisor parked in the reconnect
            # backoff so it observes the flag immediately, not after the wait.
            self.detached = True
            wakeup = getattr(self, "_reconnect_wakeup", None)
            if wakeup is not None:
                wakeup.set()

        for bridge in (self._usb_bridge, self._kotlin_bridge):
            if bridge is None:
                continue
            try:
                if bridge.isConnected():
                    bridge.disconnect()
            except Exception as e:  # noqa: BLE001
                RNS.log(f"ColumbaRNodeInterface: closing transport during detach failed: {e}", RNS.LOG_DEBUG)

    def _ensure_usb_permission(self, bridge, device_id):
        """Request Android's USB permission for this device before opening it.

        The Kotlin backend asks for the grant when it opens a USB serial interface;
        the Python path has to do the same. Grants are per app install, so a
        reinstall leaves a configured USB RNode in "No permission for device" with
        no prompt, and the reconnect loop retries forever.
        """
        try:
            if bridge.hasPermission(device_id):
                self._usb_permission_denied = False
                return True
        except Exception as e:  # noqa: BLE001
            # Old bridge without the query: let connect() report the real failure.
            RNS.log(f"ColumbaRNodeInterface: USB permission check failed: {e}", RNS.LOG_DEBUG)
            return True

        now = time.time()
        if self._usb_permission_denied or now < self._usb_permission_next_attempt:
            return False

        self._usb_permission_next_attempt = now + self.USB_PERMISSION_RETRY_S
        RNS.log(f"ColumbaRNodeInterface: requesting USB permission for device {device_id}", RNS.LOG_INFO)
        try:
            granted = bool(bridge.requestPermissionBlocking(device_id, self.USB_PERMISSION_TIMEOUT_MS))
        except Exception as e:  # noqa: BLE001
            RNS.log(f"ColumbaRNodeInterface: USB permission request failed: {e}", RNS.LOG_ERROR)
            return False

        if not granted:
            # The system dialog outlives the wait: the user can answer it after
            # we stopped waiting. A later grant is still picked up by the
            # hasPermission() check at the top of the next attempt, so only the
            # prompt is suppressed here.
            try:
                if bridge.hasPermission(device_id):
                    self._usb_permission_denied = False
                    return True
            except Exception as e:  # noqa: BLE001
                RNS.log(f"ColumbaRNodeInterface: USB permission re-check failed: {e}", RNS.LOG_DEBUG)
            self._usb_permission_denied = True
            RNS.log(
                f"ColumbaRNodeInterface: USB permission for device {device_id} not granted "
                "(prompt unanswered or denied); re-enable the interface to ask again",
                RNS.LOG_ERROR,
            )
        return granted

    def _open_usb_port(self):
        import usb_bridge

        # Local until this interface actually owns the connection: detach() closes
        # whatever _usb_bridge holds, and the bridge is a singleton.
        bridge = usb_bridge.get_usb_bridge()
        if bridge is None:
            RNS.log("ColumbaRNodeInterface: KotlinUSBBridge unavailable — RNode stays offline", RNS.LOG_ERROR)
            return None

        device_id = self.usb_device_id
        if self.usb_vendor_id is not None and self.usb_product_id is not None:
            device_id = bridge.findDeviceByVidPid(self.usb_vendor_id, self.usb_product_id)
            if device_id < 0:
                RNS.log(
                    f"ColumbaRNodeInterface: USB device {RNS.hexrep(self.usb_vendor_id)}:{RNS.hexrep(self.usb_product_id)} not found",
                    RNS.LOG_WARNING,
                )
                return None
            self.usb_device_id = device_id

        if device_id is None:
            RNS.log("ColumbaRNodeInterface: no USB device id configured", RNS.LOG_ERROR)
            return None

        if not self._ensure_usb_permission(bridge, device_id):
            return None

        try:
            if bridge.isConnected():
                bridge.disconnect()
        except Exception as e:  # noqa: BLE001
            RNS.log(f"ColumbaRNodeInterface: clearing stale USB connection failed: {e}", RNS.LOG_DEBUG)

        if not bridge.connect(device_id, 115200):
            RNS.log(f"ColumbaRNodeInterface: failed to connect USB device {device_id}", RNS.LOG_ERROR)
            return None

        def paced_read():
            # Upstream's read loop only sleeps for BLE/Classic transports; USB
            # would spin on an empty bridge queue, so pace the read here.
            data = bridge.read()
            if not data:
                time.sleep(0.02)
            return data

        self._usb_bridge = bridge
        return BridgeConnection(
            name=self.name,
            read_fn=paced_read,
            write_fn=bridge.write,
            connected_fn=bridge.isConnected,
            close_fn=bridge.disconnect,
        )

    # ---- radio configuration validation ----

    def validateRadioState(self):
        """Adopt the device's TX power instead of failing the whole interface.

        RNode firmware rewrites the requested TX power before echoing it: it
        clamps to the modem/board ceiling (22 dBm SX1262, 17 dBm SX127x,
        13/20 dBm SX1280) and, on LoRa-PA boards, quantises the request through
        its PA gain table, so a legitimate sub-floor setting reports the lowest
        power the PA can produce (1 dBm -> 11 dBm on a 735D, `PA_GC1109` gain
        table). Upstream compares the echo with the configuration and aborts
        the startup on any divergence, which leaves the radio offline with no
        recovery path — sub-floor power settings are unusable that way.

        The device's value is adopted as the interface's TX power, so the
        running interface matches what the radio actually transmits. Every
        other parameter keeps upstream's fatal validation: frequency,
        bandwidth, spreading factor, coding rate and radio state have no
        legitimate device-side rewrite, so a mismatch there means the radio is
        not on the configured channel plan. See issue #1255.
        """
        if self.r_txpower is None:
            # The echo is produced by the read loop after `setTXPower()`; it is
            # normally already here by the time configuration is validated.
            deadline = time.time() + 2.5
            while self.r_txpower is None and time.time() < deadline:
                time.sleep(0.05)

        reported = self.r_txpower
        if reported is not None and reported != self.txpower:
            if 0 <= reported <= 37:
                RNS.log(
                    f"{self} TX power: configured {self.txpower} dBm, the device can only "
                    f"transmit {reported} dBm — adopting the device value",
                    RNS.LOG_WARNING,
                )
                self.txpower = reported
            else:
                # 0xFF is the firmware's "unset" sentinel; anything else is
                # outside the range upstream accepts for a configuration.
                RNS.log(
                    f"{self} reported an invalid TX power ({reported} dBm); "
                    f"keeping the configured {self.txpower} dBm",
                    RNS.LOG_ERROR,
                )

        return super().validateRadioState()

    # ---- accessors the Kotlin service layer polls ----

    def get_rssi(self):
        return self.r_stat_rssi

    def get_snr(self):
        return self.r_stat_snr

    def get_battery(self):
        # None while offline or before the device reports 0x27: the AIDL layer
        # maps None to its "no reading" sentinel instead of showing a stale value.
        if not self.online:
            return None
        if self.r_battery_state == AndroidRNodeInterface.BATTERY_STATE_UNKNOWN:
            return None
        return self.r_battery_percent

    # ---- framebuffer logo ----

    def _display_logo(self):
        if not self.enable_framebuffer:
            return
        try:
            from columba_logo import columba_fb_data
        except Exception as e:  # noqa: BLE001
            RNS.log(f"{self} columba logo not available: {e}", RNS.LOG_DEBUG)
            return

        self.last_imagedata = bytes(columba_fb_data)
        if not self.online:
            # Upstream re-displays last_imagedata from reconnect_port() once the
            # radio is back, so holding it here is enough.
            return
        if self.display is None:
            # Detected device reports no framebuffer (see upstream detect()).
            return
        try:
            self.display_image(self.last_imagedata)
            self.enable_external_framebuffer()
            RNS.log(f"{self} displayed columba logo on RNode", RNS.LOG_DEBUG)
        except Exception as e:  # noqa: BLE001
            RNS.log(f"{self} could not display logo: {e}", RNS.LOG_DEBUG)

    def __str__(self):
        return f"ColumbaRNodeInterface[{self.name}]"


interface_class = ColumbaRNodeInterface
