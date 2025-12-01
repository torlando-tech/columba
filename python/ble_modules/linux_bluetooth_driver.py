"""
Linux Bluetooth Driver for BLE

This module implements the BLEDriverInterface abstraction for Linux using:
- bleak: BLE central operations (scanning, connecting, GATT client)
- bluezero: BLE peripheral operations (GATT server, advertising)
- D-Bus: Direct BlueZ API access for platform-specific workarounds

Platform-specific workarounds included:
1. BlueZ ServicesResolved race condition (Bleak 1.1.1 + bluezero)
2. LE-only connection via D-Bus ConnectDevice (BlueZ >= 5.49)
3. BLE Agent registration for automatic pairing
4. MTU negotiation via 3 fallback methods

USAGE EXAMPLE:
--------------

    from linux_bluetooth_driver import LinuxBluetoothDriver

    # Create driver instance (no Reticulum dependencies)
    driver = LinuxBluetoothDriver(
        discovery_interval=5.0,
        connection_timeout=10.0,
        min_rssi=-90,
        service_discovery_delay=1.5,
        max_peers=7,
        adapter_index=0  # hci0
    )

    # Set up callbacks
    def on_device_discovered(device):
        print(f"Discovered: {device.name} ({device.address}) RSSI: {device.rssi}")

    def on_device_connected(address):
        print(f"Connected: {address}")

    def on_data_received(address, data):
        print(f"Received {len(data)} bytes from {address}")

    def on_mtu_negotiated(address, mtu):
        print(f"MTU negotiated with {address}: {mtu}")

    driver.on_device_discovered = on_device_discovered
    driver.on_device_connected = on_device_connected
    driver.on_data_received = on_data_received
    driver.on_mtu_negotiated = on_mtu_negotiated

    # Start driver
    driver.start(
        service_uuid="37145b00-442d-4a94-917f-8f42c5da28e3",
        rx_char_uuid="37145b00-442d-4a94-917f-8f42c5da28e5",
        tx_char_uuid="37145b00-442d-4a94-917f-8f42c5da28e4",
        identity_char_uuid="37145b00-442d-4a94-917f-8f42c5da28e6"
    )

    # Set identity for peripheral mode
    driver.set_identity(b"\\x01\\x02\\x03...\\x10")  # 16 bytes

    # Start scanning (central mode)
    driver.start_scanning()

    # Start advertising (peripheral mode)
    driver.start_advertising("MyDevice", b"\\x01\\x02\\x03...\\x10")

    # Connect to a peer
    driver.connect("AA:BB:CC:DD:EE:FF")

    # Send data (automatically uses GATT write or notification)
    driver.send("AA:BB:CC:DD:EE:FF", b"Hello, peer!")

    # Stop driver
    driver.stop()

ARCHITECTURE:
-------------

The driver uses a dedicated asyncio event loop in a separate thread to handle
all BLE operations asynchronously. This allows the main thread to remain
responsive while BLE operations run in the background.

Thread Architecture:
- Main thread: User-facing API (start, stop, connect, send, etc.)
- Event loop thread: All async BLE operations (scanning, connecting, GATT ops)
- GATT server thread: Bluezero peripheral (blocking publish())

Cross-thread communication:
- Main → Event loop: asyncio.run_coroutine_threadsafe()
- Event loop → Main: Callbacks (on_device_discovered, on_data_received, etc.)
- GATT server → Main: Callbacks from bluezero write_callback

ROLE-AWARE send():
------------------

The send() method automatically determines whether to use GATT write (central)
or notification (peripheral) based on the connection type:

- Central connection (we connected to them): GATT write to RX characteristic
- Peripheral connection (they connected to us): Notification on TX characteristic

This abstraction simplifies the high-level interface logic by hiding the
BLE role complexity at the driver level.

DEPENDENCIES:
-------------

Required:
- bleak >= 0.22.0 (BLE central operations)
- dbus-fast >= 1.0.0 (D-Bus communication)

Optional (for peripheral mode):
- bluezero >= 0.9.1 (GATT server)
- dbus-python >= 1.2.18 (bluezero dependency)

Author: Reticulum BLE Interface Contributors
License: MIT
"""

from __future__ import annotations

import asyncio
import threading
import time
import logging
import warnings
from typing import Optional, Callable, List, Dict
from dataclasses import dataclass

# Import RNS for logging
try:
    import RNS
except ImportError:
    # Fallback for when RNS is not available (standalone testing)
    RNS = None

# Capture Python warnings and route them through RNS logger
def _rns_showwarning(message, category, filename, lineno, file=None, line=None):
    """Custom warning handler that routes warnings to RNS logger."""
    if RNS:
        warning_msg = f"{category.__name__}: {message} ({filename}:{lineno})"
        RNS.log(warning_msg, RNS.LOG_WARNING)
    else:
        # Fallback to default warning behavior
        import sys
        if file is None:
            file = sys.stderr
        try:
            file.write(warnings.formatwarning(message, category, filename, lineno, line))
        except (AttributeError, IOError):
            pass

# Install custom warning handler
warnings.showwarning = _rns_showwarning

# Import the abstraction
try:
    from bluetooth_driver import BLEDriverInterface, BLEDevice, DriverState
except ImportError:
    import sys
    import os
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from bluetooth_driver import BLEDriverInterface, BLEDevice, DriverState

# Bleak (BLE central operations)
try:
    import bleak
    from bleak import BleakScanner, BleakClient
    from bleak.backends.bluezdbus.manager import BlueZManager
    HAS_BLEAK = True
except ImportError:
    HAS_BLEAK = False
    BleakScanner = None
    BleakClient = None

# Bluezero (BLE peripheral operations)
try:
    from bluezero import peripheral, adapter
    BLUEZERO_AVAILABLE = True
except ImportError:
    BLUEZERO_AVAILABLE = False

# BLE Agent for automatic pairing
try:
    from BLEAgent import register_agent, unregister_agent
    HAS_BLE_AGENT = True
except ImportError:
    try:
        from RNS.Interfaces.BLEAgent import register_agent, unregister_agent
        HAS_BLE_AGENT = True
    except ImportError:
        HAS_BLE_AGENT = False

# D-Bus for platform-specific operations
try:
    from dbus_fast.aio import MessageBus
    from dbus_fast import BusType, Variant
    HAS_DBUS = True
except ImportError:
    HAS_DBUS = False


# ============================================================================
# BlueZ ServicesResolved Race Condition Workaround
# ============================================================================
# Issue: When connecting to BlueZ-based GATT servers (like bluezero), BlueZ
#        sets ServicesResolved=True BEFORE services are fully exported to D-Bus
# Cause: BlueZ GATT database cache timing issue (bluez/bluez#1489)
# Impact: Bleak attempts to enumerate services before they're available,
#         causing -5 (EIO) error and immediate disconnect
# Fix: Poll D-Bus service map to verify services actually exist before proceeding
# Status: Works with bluezero; proper fix should be in BlueZ or Bleak upstream
# GitHub: https://github.com/hbldh/bleak/issues/1677
# ============================================================================

def apply_bluez_services_resolved_patch():
    """
    Apply monkey patch to fix BlueZ ServicesResolved race condition.

    This must be called before any BleakClient connections are made.
    """
    if not HAS_BLEAK:
        return False

    try:
        # Store original method
        _original_wait_for_services_discovery = BlueZManager._wait_for_services_discovery

        async def _patched_wait_for_services_discovery(self, device_path: str) -> None:
            """
            Patched version that waits for services to actually appear in D-Bus.

            Fixes race condition where ServicesResolved=True before services
            are fully exported to D-Bus (common when connecting to BlueZ peripherals).
            """
            # Call original wait for ServicesResolved property
            await _original_wait_for_services_discovery(self, device_path)

            # Additional verification: Poll until services actually appear in D-Bus
            max_attempts = 20  # 20 attempts * 100ms = 2 seconds max
            retry_delay = 0.1  # 100ms between attempts

            for attempt in range(max_attempts):
                # Check if services are actually present in the service map
                service_paths = self._service_map.get(device_path, set())

                if service_paths and len(service_paths) > 0:
                    # Services found! Verify at least one service has been fully loaded
                    # by checking if it exists in the properties dictionary
                    try:
                        first_service_path = next(iter(service_paths))
                        if first_service_path in self._properties:
                            # Success: Services are actually in D-Bus
                            if RNS:
                                RNS.log(f"BlueZ timing fix: Services verified in D-Bus after {attempt * retry_delay:.2f}s", RNS.LOG_EXTREME)
                            return
                    except (StopIteration, KeyError):
                        pass  # Service not ready yet

                # Services not ready yet, wait before next check
                if attempt < max_attempts - 1:  # Don't sleep on last attempt
                    await asyncio.sleep(retry_delay)

            # If we get here, services didn't appear within timeout
            # Log warning but don't raise - let get_services() handle it
            if RNS:
                RNS.log(f"BlueZ timing fix: Services not found in D-Bus after {max_attempts * retry_delay}s, proceeding anyway", RNS.LOG_WARNING)

        # Apply the patch
        BlueZManager._wait_for_services_discovery = _patched_wait_for_services_discovery
        if RNS:
            RNS.log("Applied Bleak BlueZ ServicesResolved timing patch for bluezero compatibility", RNS.LOG_INFO)
        return True

    except Exception as e:
        # If patching fails, log warning but don't prevent driver from loading
        if RNS:
            RNS.log(f"Failed to apply Bleak BlueZ timing patch: {e}. Connections to bluezero peripherals may fail.", RNS.LOG_WARNING)
        return False


@dataclass
class PeerConnection:
    """Tracks information about a connected peer."""
    address: str
    client: Optional[BleakClient] = None  # For central connections
    mtu: int = 23  # Negotiated MTU
    connection_type: str = "unknown"  # "central" or "peripheral"
    connected_at: float = 0.0
    peer_identity: Optional[bytes] = None  # 16-byte identity hash


class LinuxBluetoothDriver(BLEDriverInterface):
    """
    Linux implementation of BLE driver using bleak and bluezero.

    This driver provides:
    - Central mode: BLE scanning and connections via bleak
    - Peripheral mode: GATT server and advertising via bluezero
    - Platform workarounds for BlueZ quirks
    - Dedicated asyncio event loop in separate thread
    - Role-aware send() that automatically uses GATT write or notification

    Architecture:
    - Main thread: User-facing API (start, stop, send, etc.)
    - Event loop thread: All async BLE operations
    - Cross-thread communication via run_coroutine_threadsafe
    """

    def __init__(
        self,
        discovery_interval: float = 5.0,
        connection_timeout: float = 10.0,
        min_rssi: int = -90,
        service_discovery_delay: float = 1.5,
        max_peers: int = 7,
        adapter_index: int = 0,
        agent_capability: str = "NoInputNoOutput"
    ):
        """
        Initialize Linux BLE driver.

        Args:
            discovery_interval: Seconds between discovery scans (default: 5.0)
            connection_timeout: Connection timeout in seconds (default: 10.0)
            min_rssi: Minimum RSSI for connection attempts (default: -90 dBm)
            service_discovery_delay: Delay after connection for bluezero D-Bus registration (default: 1.5s)
            max_peers: Maximum simultaneous connections (default: 7)
            adapter_index: Bluetooth adapter index (0 = hci0, 1 = hci1, etc.)
            agent_capability: BLE pairing agent capability (default: "NoInputNoOutput" for Just Works pairing)
        """
        # Validate dependencies
        if not HAS_BLEAK:
            raise ImportError("bleak library required for Linux BLE driver. Install with: pip install bleak>=0.22.0")

        # Configuration
        self.discovery_interval = discovery_interval
        self.connection_timeout = connection_timeout
        self.min_rssi = min_rssi
        self.service_discovery_delay = service_discovery_delay
        self.max_peers = max_peers
        self.adapter_index = adapter_index
        self.adapter_path = f"/org/bluez/hci{adapter_index}"
        self.agent_capability = agent_capability

        # Service UUIDs (set by start())
        self.service_uuid: Optional[str] = None
        self.rx_char_uuid: Optional[str] = None
        self.tx_char_uuid: Optional[str] = None
        self.identity_char_uuid: Optional[str] = None

        # State
        self._state = DriverState.IDLE
        self._running = False
        self._scanning = False
        self._advertising = False

        # Connected peers
        self._peers: Dict[str, PeerConnection] = {}  # address -> PeerConnection
        self._peers_lock = threading.RLock()

        # Pending connections (prevents race condition from concurrent connection attempts)
        self._connecting_peers: set = set()  # addresses with connection attempts in progress
        self._connecting_lock = threading.Lock()

        # Local identity (for peripheral mode)
        self._local_identity: Optional[bytes] = None

        # Local adapter address (for connection direction preference)
        self.local_address: Optional[str] = None

        # Power mode
        self.power_mode = "balanced"  # "aggressive", "balanced", "saver"

        # Event loop management
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.loop_thread: Optional[threading.Thread] = None

        # Peripheral mode (bluezero)
        self.gatt_server: Optional['BluezeroGATTServer'] = None
        self.ble_agent = None

        # BlueZ version detection
        self.bluez_version: Optional[tuple] = None
        self.has_connect_device = None  # None = unknown, True/False = tested

        # Logging
        self.log_prefix = "LinuxBLEDriver"

        # Scanner health tracking
        self.consecutive_empty_scans = 0

        # Apply BlueZ timing patch
        apply_bluez_services_resolved_patch()

        # Detect BlueZ version
        self._detect_bluez_version()

    def _log(self, message: str, level: str = "INFO"):
        """Log message with appropriate level."""
        if RNS:
            # Map Python logging level strings to RNS log levels
            level_map = {
                "DEBUG": RNS.LOG_DEBUG,
                "INFO": RNS.LOG_INFO,
                "WARNING": RNS.LOG_WARNING,
                "ERROR": RNS.LOG_ERROR,
                "CRITICAL": RNS.LOG_CRITICAL,
                "EXTREME": RNS.LOG_EXTREME,
            }
            rns_level = level_map.get(level.upper(), RNS.LOG_INFO)
            RNS.log(f"{self.log_prefix} {message}", rns_level)
        else:
            # Fallback to standard Python logging if RNS not available
            log_func = getattr(logging, level.lower(), logging.info)
            log_func(f"{self.log_prefix} {message}")

    # ========================================================================
    # Lifecycle & Configuration
    # ========================================================================

    def start(self, service_uuid: str, rx_char_uuid: str, tx_char_uuid: str, identity_char_uuid: str):
        """
        Initialize the driver and start the BLE stack.

        This creates the dedicated event loop thread and initializes the GATT server.
        """
        if self._running:
            self._log("Driver already running", "WARNING")
            return

        self._log("Starting Linux BLE driver...")

        # Store UUIDs
        self.service_uuid = service_uuid
        self.rx_char_uuid = rx_char_uuid
        self.tx_char_uuid = tx_char_uuid
        self.identity_char_uuid = identity_char_uuid

        # Start event loop thread
        self.loop_thread = threading.Thread(target=self._run_event_loop, daemon=True, name="BLE-EventLoop")
        self.loop_thread.start()

        # Wait for event loop to be ready
        timeout = 5.0
        start_time = time.time()
        while self.loop is None and (time.time() - start_time) < timeout:
            time.sleep(0.1)

        if self.loop is None:
            raise RuntimeError("Failed to start event loop within timeout")

        # Get local adapter address
        future = asyncio.run_coroutine_threadsafe(self._get_local_adapter_address(), self.loop)
        try:
            self.local_address = future.result(timeout=5.0)
            if self.local_address:
                self._log(f"Local adapter address: {self.local_address}")
        except Exception as e:
            self._log(f"Could not get local adapter address: {e}", "WARNING")

        # Initialize GATT server for peripheral mode (if bluezero available)
        if BLUEZERO_AVAILABLE:
            try:
                self.gatt_server = BluezeroGATTServer(
                    driver=self,
                    service_uuid=service_uuid,
                    rx_char_uuid=rx_char_uuid,
                    tx_char_uuid=tx_char_uuid,
                    identity_char_uuid=identity_char_uuid,
                    adapter_index=self.adapter_index,
                    agent_capability=self.agent_capability
                )
                self._log("GATT server initialized")
            except Exception as e:
                self._log(f"Failed to initialize GATT server: {e}", "WARNING")
                self.gatt_server = None
        else:
            self._log("Bluezero not available, peripheral mode disabled", "WARNING")

        self._running = True
        self._state = DriverState.IDLE
        self._log("Driver started successfully")

    def stop(self):
        """Stop all BLE activity and release resources."""
        if not self._running:
            return

        self._log("Stopping Linux BLE driver...")
        self._running = False

        # Stop scanning
        if self._scanning:
            self.stop_scanning()

        # Stop advertising
        if self._advertising:
            self.stop_advertising()

        # Disconnect all peers
        with self._peers_lock:
            for address in list(self._peers.keys()):
                try:
                    self.disconnect(address)
                except Exception as e:
                    self._log(f"Error disconnecting {address}: {e}", "WARNING")

        # Stop GATT server
        if self.gatt_server:
            try:
                self.gatt_server.stop()
            except Exception as e:
                self._log(f"Error stopping GATT server: {e}", "WARNING")

        # Stop event loop
        if self.loop and self.loop.is_running():
            self.loop.call_soon_threadsafe(self.loop.stop)

        # Wait for thread to exit
        if self.loop_thread and self.loop_thread.is_alive():
            self.loop_thread.join(timeout=5.0)

        self._state = DriverState.IDLE
        self._log("Driver stopped")

    def set_identity(self, identity_bytes: bytes):
        """Set the local identity for the GATT server."""
        if not isinstance(identity_bytes, bytes):
            raise TypeError(f"identity_bytes must be bytes, got {type(identity_bytes)}")

        if len(identity_bytes) != 16:
            raise ValueError(f"identity_bytes must be 16 bytes, got {len(identity_bytes)}")

        self._local_identity = identity_bytes

        if self.gatt_server:
            self.gatt_server.set_identity(identity_bytes)

        self._log(f"Local identity set: {identity_bytes.hex()}")

    # ========================================================================
    # State & Properties
    # ========================================================================

    @property
    def state(self) -> DriverState:
        """Return current driver state."""
        return self._state

    @property
    def connected_peers(self) -> List[str]:
        """Return list of connected peer addresses."""
        with self._peers_lock:
            return list(self._peers.keys())

    # ========================================================================
    # Scanning (Central Mode)
    # ========================================================================

    def start_scanning(self):
        """Start scanning for BLE devices."""
        if not self._running:
            self._log("Cannot start scanning: driver not running", "ERROR")
            return

        if self._scanning:
            self._log("Already scanning", "DEBUG")
            return

        self._log("Starting BLE scanning...")
        self._scanning = True
        self._state = DriverState.SCANNING

        # Start scan loop in event loop
        asyncio.run_coroutine_threadsafe(self._scan_loop(), self.loop)

    def stop_scanning(self):
        """Stop scanning for BLE devices."""
        if not self._scanning:
            return

        self._log("Stopping BLE scanning...")
        self._scanning = False

        if not self._advertising:
            self._state = DriverState.IDLE

    def _should_pause_scanning(self) -> bool:
        """
        Check if scanning should be paused due to active connections.

        Scanner interference with active connections can cause BlueZ
        "Operation already in progress" errors. We pause scanning when
        connections are being established.

        Returns:
            True if scanning should be paused (connections in progress)
            False if scanning can proceed normally
        """
        return len(self._connecting_peers) > 0

    async def _scan_loop(self):
        """Main scanning loop (runs in event loop thread)."""
        self._log("Scan loop started", "DEBUG")

        while self._scanning and self._running:
            try:
                await self._perform_scan()

                # Sleep based on power mode
                if self.power_mode == "aggressive":
                    sleep_time = 1.0
                elif self.power_mode == "saver":
                    # Skip scanning if we have connected peers
                    with self._peers_lock:
                        if len(self._peers) > 0:
                            sleep_time = 60.0
                        else:
                            sleep_time = 30.0
                else:  # balanced
                    sleep_time = self.discovery_interval

                await asyncio.sleep(sleep_time)

            except Exception as e:
                self._log(f"Error in scan loop: {e}", "ERROR")
                await asyncio.sleep(5.0)  # Back off on errors

        self._log("Scan loop stopped", "DEBUG")

    async def _perform_scan(self):
        """Perform a single BLE scan."""
        # Check if we should pause scanning due to active connections
        # This prevents "Operation already in progress" errors from BlueZ
        if self._should_pause_scanning():
            self._log("Pausing scan: connection(s) in progress", "DEBUG")
            return  # Skip this scan cycle, will retry on next loop iteration

        discovered_devices = []
        callback_count = [0]  # Use list to allow modification in nested function

        def detection_callback(device, advertisement_data):
            """Called for each discovered device."""
            callback_count[0] += 1
            self._log(f"🔍 CALLBACK INVOKED: {device.address} ({device.name or 'Unknown'}) RSSI={advertisement_data.rssi} UUIDs={advertisement_data.service_uuids}", "EXTRA")
            discovered_devices.append((device, advertisement_data))

        # Scan duration based on power mode
        if self.power_mode == "aggressive":
            scan_time = 2.0
        elif self.power_mode == "saver":
            scan_time = 0.5
        else:  # balanced
            scan_time = 1.0

        self._log(f"🔍 Starting BleakScanner (power_mode={self.power_mode}, scan_time={scan_time}s, service_uuid={self.service_uuid})", "EXTRA")
        scanner = BleakScanner(
            detection_callback=detection_callback,
            service_uuids=[self.service_uuid] if self.service_uuid else None
        )

        try:
            self._log("🔍 Calling scanner.start()", "EXTRA")
            await scanner.start()
            self._log(f"🔍 Scanner started, sleeping for {scan_time}s", "EXTRA")
            await asyncio.sleep(scan_time)
            self._log("🔍 Calling scanner.stop()", "EXTRA")
            await scanner.stop()
            self._log(f"🔍 Scanner stopped. Total devices discovered: {len(discovered_devices)}", "EXTRA")
        except Exception as e:
            error_msg = str(e)
            self._log(f"🔍 Scanner exception: {error_msg}", "ERROR")

            # Check for adapter power issues
            if "No powered Bluetooth adapters" in error_msg or "Not Powered" in error_msg:
                self._log("Bluetooth adapter is not powered!", "ERROR")
                if self.on_error:
                    self.on_error("error", "Bluetooth adapter not powered. Run 'bluetoothctl power on'", e)
                return
            else:
                raise

        # Detect scanner callback corruption
        if callback_count[0] == 0:
            self.consecutive_empty_scans += 1
            self._log(f"⚠️ Scanner corruption detected: 0 callbacks after {scan_time}s scan (streak: {self.consecutive_empty_scans})", "WARNING")

            if self.consecutive_empty_scans >= 3:
                self._log("⚠️ CRITICAL: Bleak scanner callbacks not firing", "ERROR")
                self._log("⚠️ Bluetooth/BlueZ/D-Bus state is corrupted", "ERROR")
                self._log("⚠️ System reboot required to restore BLE scanning", "ERROR")

                if self.on_error:
                    self.on_error("critical",
                        f"Scanner callback failure detected (0 callbacks for {self.consecutive_empty_scans} consecutive scans). "
                        "Bluetooth stack requires reboot.",
                        Exception("BleakScanner callbacks not invoked"))
        else:
            # Reset counter on successful callback
            if self.consecutive_empty_scans > 0:
                self._log(f"✓ Scanner callbacks resumed after {self.consecutive_empty_scans} empty scans", "INFO")
            self.consecutive_empty_scans = 0

        # Process discovered devices
        self._log(f"🔍 Processing {len(discovered_devices)} discovered devices", "EXTRA")
        for device, adv_data in discovered_devices:
            # Check if device advertises our service UUID
            if self.service_uuid and self.service_uuid.lower() in [uuid.lower() for uuid in adv_data.service_uuids]:
                self._log(f"✓ {device.address} has service UUID {self.service_uuid}", "EXTRA")

                # Check RSSI threshold
                if adv_data.rssi < self.min_rssi:
                    self._log(f"✗ {device.address}: RSSI {adv_data.rssi} below threshold {self.min_rssi}", "EXTRA")
                    continue

                # Check for invalid/sentinel RSSI values (-127, -128 indicate no signal/error)
                if adv_data.rssi in (-127, -128, 0):
                    self._log(f"✗ {device.address}: invalid sentinel RSSI {adv_data.rssi} dBm", "DEBUG")
                    continue

                self._log(f"✓ {device.address} passed all filters, notifying callback", "EXTRA")

                # Create BLEDevice and notify callback
                ble_device = BLEDevice(
                    address=device.address,
                    name=device.name or "Unknown",
                    rssi=adv_data.rssi,
                    service_uuids=list(adv_data.service_uuids),
                    manufacturer_data=dict(adv_data.manufacturer_data) if hasattr(adv_data, 'manufacturer_data') else {}
                )

                if self.on_device_discovered:
                    try:
                        self.on_device_discovered(ble_device)
                    except Exception as e:
                        self._log(f"Error in device discovered callback: {e}", "ERROR")
            else:
                self._log(f"✗ {device.address} ({device.name or 'Unknown'}): service UUID mismatch (has {adv_data.service_uuids}, want {self.service_uuid})", "EXTRA")

    # ========================================================================
    # Advertising (Peripheral Mode)
    # ========================================================================

    def start_advertising(self, device_name: Optional[str], identity: bytes):
        """Start advertising as a BLE peripheral."""
        if not self._running:
            self._log("Cannot start advertising: driver not running", "ERROR")
            return

        if not self.gatt_server:
            self._log("Cannot start advertising: GATT server not available", "ERROR")
            if self.on_error:
                self.on_error("error", "GATT server not available (bluezero not installed?)", None)
            return

        if self._advertising:
            self._log("Already advertising", "DEBUG")
            return

        if device_name:
            self._log(f"Starting BLE advertising as '{device_name}'...")
        else:
            self._log("Starting BLE advertising (no device name)...")

        # Set identity
        self.set_identity(identity)

        # Start GATT server
        try:
            self.gatt_server.start(device_name)
            self._advertising = True
            self._state = DriverState.ADVERTISING
            self._log("Advertising started")
        except Exception as e:
            self._log(f"Failed to start advertising: {e}", "ERROR")
            if self.on_error:
                self.on_error("error", f"Failed to start advertising: {e}", e)

    def stop_advertising(self):
        """Stop advertising."""
        if not self._advertising:
            return

        self._log("Stopping BLE advertising...")

        if self.gatt_server:
            try:
                self.gatt_server.stop()
            except Exception as e:
                self._log(f"Error stopping GATT server: {e}", "WARNING")

        self._advertising = False

        if not self._scanning:
            self._state = DriverState.IDLE

    # ========================================================================
    # Connection Management (Central Mode)
    # ========================================================================

    def connect(self, address: str):
        """Connect to a peer device (central role)."""
        if not self._running:
            self._log("Cannot connect: driver not running", "ERROR")
            return

        # Check if already connected
        with self._peers_lock:
            if address in self._peers:
                self._log(f"Already connected to {address}", "DEBUG")
                return

        # Check if connection already in progress
        with self._connecting_lock:
            if address in self._connecting_peers:
                self._log(f"Connection already in progress to {address}", "DEBUG")
                return
            self._connecting_peers.add(address)
            # Diagnostic: Log when connection attempt starts
            self._log(f"Added {address} to connecting set (total: {len(self._connecting_peers)})", "INFO")

        # Check max peers
        with self._peers_lock:
            if len(self._peers) >= self.max_peers:
                self._log(f"Cannot connect to {address}: max peers ({self.max_peers}) reached", "WARNING")
                # Remove from connecting set since we're not actually connecting
                with self._connecting_lock:
                    self._connecting_peers.discard(address)
                return

        # Start connection in event loop
        future = asyncio.run_coroutine_threadsafe(self._connect_to_peer(address), self.loop)

        # Add callback to ensure cleanup even if coroutine fails unexpectedly
        # This guarantees cleanup on success, failure, timeout, or cancellation
        def cleanup_connecting_state(fut):
            """Callback to clean up connecting state when connection attempt completes."""
            import sys
            try:
                if RNS:
                    RNS.log(f"{self.log_prefix} [BLE-CLEANUP] Callback invoked for {address}", RNS.LOG_EXTREME)

                with self._connecting_lock:
                    was_present = address in self._connecting_peers
                    self._connecting_peers.discard(address)

                    # Try logging, but don't fail if it doesn't work
                    try:
                        if was_present:
                            self._log(f"Cleaned up connecting state for {address}", "INFO")
                        else:
                            # This indicates the finally block cleaned it up first
                            if RNS:
                                RNS.log(f"{self.log_prefix} [BLE-CLEANUP] {address} already cleaned by finally block", RNS.LOG_EXTREME)
                    except Exception as log_exc:
                        if RNS:
                            RNS.log(f"{self.log_prefix} [BLE-CLEANUP] Logging failed for {address}: {log_exc}", RNS.LOG_EXTREME)

            except Exception as e:
                if RNS:
                    RNS.log(f"{self.log_prefix} [BLE-CLEANUP-ERROR] Callback failed for {address}: {e}", RNS.LOG_EXTREME)
                # Emergency cleanup
                try:
                    with self._connecting_lock:
                        self._connecting_peers.discard(address)
                except:
                    pass

        future.add_done_callback(cleanup_connecting_state)

    def disconnect(self, address: str):
        """Disconnect from a peer device."""
        with self._peers_lock:
            if address not in self._peers:
                self._log(f"Not connected to {address}", "DEBUG")
                return

            peer = self._peers[address]

        # Disconnect based on connection type
        if peer.connection_type == "central" and peer.client:
            # Central connection: disconnect client
            future = asyncio.run_coroutine_threadsafe(peer.client.disconnect(), self.loop)
            try:
                future.result(timeout=5.0)
            except Exception as e:
                self._log(f"Error disconnecting from {address}: {e}", "WARNING")

        # For peripheral connections, client disconnects from us (we can't force disconnect)

        # Clean up
        with self._peers_lock:
            if address in self._peers:
                del self._peers[address]

        if self.on_device_disconnected:
            try:
                self.on_device_disconnected(address)
            except Exception as e:
                self._log(f"Error in device disconnected callback: {e}", "ERROR")

        self._log(f"Disconnected from {address}")

    def _handle_peripheral_disconnected(self, address: str):
        """
        Handle disconnection of a central device from our GATT server (peripheral mode).

        This is called by the GATT server when a central disconnects. It performs cleanup
        of the peer connection from the driver's _peers dictionary and notifies callbacks.

        This fixes the bug where peripheral mode disconnections were never cleaned up,
        causing the peer limit to be reached and blocking new connections.

        Args:
            address: MAC address of the disconnected central device
        """
        self._log(f"Handling peripheral disconnection from {address}", "DEBUG")

        # Clean up from _peers dictionary
        with self._peers_lock:
            if address in self._peers:
                del self._peers[address]
                self._log(f"Removed {address} from _peers (peripheral disconnect)", "DEBUG")
            else:
                self._log(f"Central {address} not in _peers during disconnect", "DEBUG")
                return

        # Notify higher-level callbacks (BLEInterface)
        if self.on_device_disconnected:
            try:
                self.on_device_disconnected(address)
            except Exception as e:
                self._log(f"Error in device disconnected callback for {address}: {e}", "ERROR")

        self._log(f"Peripheral disconnection cleanup complete for {address}")

    async def _remove_bluez_device(self, address: str) -> bool:
        """
        Remove stale device object from BlueZ via D-Bus.

        This clears any lingering connection state that might cause
        "Operation already in progress" errors on subsequent attempts.

        Args:
            address: MAC address of the device to remove (e.g., "AA:BB:CC:DD:EE:FF")

        Returns:
            True if device was removed successfully, False otherwise
        """
        if not HAS_DBUS:
            self._log(f"Cannot remove BlueZ device {address}: D-Bus not available", "DEBUG")
            return False

        try:
            # Convert MAC address to D-Bus path format
            # AA:BB:CC:DD:EE:FF → /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF
            dev_path = f"{self.adapter_path}/dev_{address.replace(':', '_')}"

            # Connect to D-Bus
            bus = await MessageBus(bus_type=BusType.SYSTEM).connect()

            # Get adapter interface
            introspection = await bus.introspect('org.bluez', self.adapter_path)
            adapter_obj = bus.get_proxy_object('org.bluez', self.adapter_path, introspection)
            adapter_iface = adapter_obj.get_interface('org.bluez.Adapter1')

            # Remove device
            await adapter_iface.call_remove_device(dev_path)

            self._log(f"Removed stale BlueZ device object for {address}", "DEBUG")
            return True

        except Exception as e:
            # Device might not exist or already removed - that's fine
            # Only log at DEBUG since this is expected in many cases
            error_str = str(e).lower()
            if "does not exist" in error_str or "unknownobject" in error_str:
                self._log(f"BlueZ device {address} already removed or doesn't exist", "DEBUG")
            else:
                self._log(f"Could not remove BlueZ device {address}: {e}", "DEBUG")
            return False

    async def _connect_to_peer(self, address: str):
        """Connect to a peer (runs in event loop thread)."""
        connection_start_time = time.time()
        self._log(f"[CONNECT-FLOW] Starting connection to {address}", "INFO")

        try:  # Outer try-finally to ensure cleanup of connecting state
            # Create disconnection callback
            def disconnected_callback(client_obj):
                """Called when device disconnects."""
                # Enhanced diagnostics: Log disconnect timing and potential reason
                connection_duration = time.time() - connection_start_time
                self._log(f"Device {address} disconnected unexpectedly after {connection_duration:.2f}s", "WARNING")

                # Clean up
                with self._peers_lock:
                    if address in self._peers:
                        del self._peers[address]

                if self.on_device_disconnected:
                    try:
                        self.on_device_disconnected(address)
                    except Exception as e:
                        self._log(f"Error in device disconnected callback: {e}", "ERROR")

            # Try LE-specific connection if BlueZ >= 5.49
            le_connection_attempted = False
            if self.bluez_version and self.bluez_version >= (5, 49) and self.has_connect_device != False:
                try:
                    await self._connect_via_dbus_le(address)
                    le_connection_attempted = True
                    self._log(f"LE-specific connection initiated for {address}", "INFO")
                except AttributeError as e:
                    # ConnectDevice method doesn't exist in this BlueZ version
                    self._log(f"ConnectDevice() method not available: {e}", "WARNING")
                    self.has_connect_device = False
                except Exception as e:
                    # Check if this is a successful object path return (D-Bus signature 'o')
                    # dbus_fast raises exception with "unexpected signature: 'o'" when ConnectDevice
                    # succeeds and returns the device object path - this is normal/expected behavior
                    error_str = str(e)
                    if 'unexpected signature' in error_str.lower() and "'o'" in error_str:
                        le_connection_attempted = True
                        self._log(f"LE-specific connection initiated for {address} (object path returned)", "INFO")
                    else:
                        # Actual failure - log and retry on next connection
                        self._log(f"ConnectDevice() failed (will retry): {e}", "WARNING")
                        # Don't set has_connect_device to False - allow retry

            # Create BleakClient
            client = BleakClient(address, disconnected_callback=disconnected_callback, timeout=self.connection_timeout)

            # Connect
            connect_phase_start = time.time()
            if not le_connection_attempted:
                self._log(f"[CONNECT-FLOW] Initiating BLE connection to {address}", "INFO")
                await client.connect(timeout=self.connection_timeout)
            else:
                # If ConnectDevice was used, check if already connected
                if not client.is_connected:
                    self._log(f"[CONNECT-FLOW] LE-specific connection active, completing BLE connection to {address}", "INFO")
                    await client.connect(timeout=self.connection_timeout)

            if not client.is_connected:
                raise RuntimeError("Connection failed")

            connect_duration = time.time() - connect_phase_start
            self._log(f"[CONNECT-FLOW] BLE connection established to {address} in {connect_duration:.2f}s", "INFO")

            # Service discovery delay (for bluezero D-Bus registration)
            if self.service_discovery_delay > 0:
                self._log(f"[CONNECT-FLOW] Waiting {self.service_discovery_delay}s for service discovery...", "INFO")
                await asyncio.sleep(self.service_discovery_delay)

            # Discover services
            service_discovery_start = time.time()
            services = list(client.services) if client.services else []

            # Fallback: force discovery if services empty
            if not services:
                self._log(f"[CONNECT-FLOW] Services property empty, forcing discovery for {address}...", "INFO")
                services_collection = await client.get_services()
                services = list(services_collection)

            service_discovery_duration = time.time() - service_discovery_start
            self._log(f"[CONNECT-FLOW] Service discovery completed for {address} in {service_discovery_duration:.2f}s, found {len(services)} services", "INFO")

            # Find Reticulum service
            reticulum_service = None
            for svc in services:
                if svc.uuid.lower() == self.service_uuid.lower():
                    reticulum_service = svc
                    break

            if not reticulum_service:
                raise RuntimeError(f"Reticulum service {self.service_uuid} not found (available services: {[s.uuid for s in services[:3]]}...)")

            self._log(f"[CONNECT-FLOW] Found Reticulum service on {address}, reading identity characteristic", "INFO")

            # Read identity characteristic
            identity_read_start = time.time()
            peer_identity = None
            for char in reticulum_service.characteristics:
                if char.uuid.lower() == self.identity_char_uuid.lower():
                    identity_value = await client.read_gatt_char(char)
                    if len(identity_value) == 16:
                        peer_identity = bytes(identity_value)
                        identity_read_duration = time.time() - identity_read_start
                        self._log(f"[CONNECT-FLOW] Read identity from {address} in {identity_read_duration:.2f}s: {peer_identity.hex()}", "INFO")
                    else:
                        self._log(f"[CONNECT-FLOW] Invalid identity length from {address}: {len(identity_value)} bytes (expected 16)", "WARNING")
                    break

            if not peer_identity:
                raise RuntimeError(f"Could not read peer identity (identity characteristic not found or invalid)")

            # Check for duplicate identity (Android MAC rotation)
            if hasattr(self, 'on_duplicate_identity_detected') and self.on_duplicate_identity_detected:
                try:
                    is_duplicate = self.on_duplicate_identity_detected(address, peer_identity)
                    if is_duplicate:
                        self._log(f"[CONNECT-FLOW] Duplicate identity detected for {address}, aborting connection", "WARNING")
                        # Disconnect cleanly
                        if client.is_connected:
                            await client.disconnect()
                        raise RuntimeError(f"Duplicate identity - already connected via different MAC (Android MAC rotation)")
                except RuntimeError:
                    # Re-raise the abort exception
                    raise
                except Exception as e:
                    # Log but don't fail connection if callback has issues
                    self._log(f"[CONNECT-FLOW] Error in duplicate identity callback: {e}", "WARNING")

            # Negotiate MTU
            mtu = await self._negotiate_mtu(client)
            self._log(f"Negotiated MTU {mtu} with {address}", "DEBUG")

            # Store connection
            peer_conn = PeerConnection(
                address=address,
                client=client,
                mtu=mtu,
                connection_type="central",
                connected_at=time.time(),
                peer_identity=peer_identity
            )

            with self._peers_lock:
                self._peers[address] = peer_conn

            # Set up notifications
            notification_setup_start = time.time()
            self._log(f"[CONNECT-FLOW] Starting notification setup for {address}", "INFO")
            await client.start_notify(
                self.tx_char_uuid,
                lambda sender, data: self._handle_notification(address, data)
            )
            notification_setup_duration = time.time() - notification_setup_start
            self._log(f"[CONNECT-FLOW] Notifications enabled for {address} in {notification_setup_duration:.2f}s", "INFO")

            # Send identity handshake (if we have local identity)
            if self._local_identity:
                # Phase 2: Add connection state validation before handshake
                if not client.is_connected:
                    self._log(f"[CONNECT-FLOW] Connection to {address} lost before identity handshake, aborting", "WARNING")
                    raise RuntimeError("Connection lost before identity handshake")

                handshake_start = time.time()
                self._log(f"[CONNECT-FLOW] Sending identity handshake to {address} ({len(self._local_identity)} bytes)", "INFO")
                try:
                    await client.write_gatt_char(
                        self.rx_char_uuid,
                        self._local_identity,
                        response=True
                    )
                    handshake_duration = time.time() - handshake_start
                    self._log(f"[CONNECT-FLOW] Identity handshake sent to {address} in {handshake_duration:.2f}s", "INFO")
                except Exception as e:
                    handshake_duration = time.time() - handshake_start
                    self._log(f"[CONNECT-FLOW] Failed to send identity handshake to {address} after {handshake_duration:.2f}s: {type(e).__name__}: {e}", "WARNING")
                    # Phase 2: Check if failure is due to disconnect
                    if not client.is_connected:
                        self._log(f"[CONNECT-FLOW] Connection to {address} was lost during handshake write", "WARNING")
                    raise  # Re-raise to trigger connection failure handling

            # Notify callback with peer identity
            if self.on_device_connected:
                try:
                    self.on_device_connected(address, peer_identity)
                except Exception as e:
                    self._log(f"Error in device connected callback: {e}", "ERROR")

            # Notify MTU callback
            if self.on_mtu_negotiated:
                try:
                    self.on_mtu_negotiated(address, mtu)
                except Exception as e:
                    self._log(f"Error in MTU negotiated callback: {e}", "ERROR")

            total_connection_time = time.time() - connection_start_time
            self._log(f"[CONNECT-FLOW] ✓ Connection complete to {address} (MTU: {mtu}) - Total time: {total_connection_time:.2f}s", "INFO")
            self._log(f"Connected to {address} (MTU: {mtu})")

        except asyncio.TimeoutError:
            self._log(f"Connection timeout to {address}", "WARNING")

            # Clean up BlueZ state by explicitly disconnecting client
            try:
                if 'client' in locals() and client and hasattr(client, 'is_connected'):
                    if client.is_connected:
                        self._log(f"Disconnecting client for {address} after timeout (cleanup)", "DEBUG")
                        await client.disconnect()
                    else:
                        self._log(f"Client for {address} already disconnected", "DEBUG")
            except Exception as cleanup_e:
                self._log(f"Error during timeout cleanup disconnect for {address}: {cleanup_e}", "DEBUG")

            # Remove stale BlueZ device object to prevent "Operation already in progress" errors
            try:
                await self._remove_bluez_device(address)
            except Exception as removal_e:
                self._log(f"Error removing BlueZ device {address} after timeout: {removal_e}", "DEBUG")

            if self.on_error:
                self.on_error("warning", f"Connection timeout to {address}", None)
        except Exception as e:
            self._log(f"Connection failed to {address}: {e}", "ERROR")

            # Clean up BlueZ state by explicitly disconnecting client
            try:
                if 'client' in locals() and client and hasattr(client, 'is_connected'):
                    if client.is_connected:
                        self._log(f"Disconnecting client for {address} after error (cleanup)", "DEBUG")
                        await client.disconnect()
                    else:
                        self._log(f"Client for {address} already disconnected", "DEBUG")
            except Exception as cleanup_e:
                self._log(f"Error during failure cleanup disconnect for {address}: {cleanup_e}", "DEBUG")

            # Remove stale BlueZ device object to prevent "Operation already in progress" errors
            try:
                await self._remove_bluez_device(address)
            except Exception as removal_e:
                self._log(f"Error removing BlueZ device {address} after failure: {removal_e}", "DEBUG")

            if self.on_error:
                self.on_error("error", f"Connection failed to {address}: {e}", e)
        finally:
            # Backup cleanup (primary cleanup is via Future callback in connect())
            # This provides defense-in-depth in case the callback doesn't execute
            with self._connecting_lock:
                self._connecting_peers.discard(address)

    async def _connect_via_dbus_le(self, peer_address: str) -> bool:
        """
        Connect using D-Bus ConnectDevice() with explicit LE type.

        This forces BLE connection instead of BR/EDR on dual-mode devices.
        Requires BlueZ >= 5.49 with experimental mode (-E flag).
        """
        if not HAS_DBUS:
            raise ImportError("dbus_fast not available")

        self._log(f"Attempting LE-specific connection via ConnectDevice() to {peer_address}", "DEBUG")

        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()

        # Get adapter interface
        introspection = await bus.introspect('org.bluez', self.adapter_path)
        adapter_obj = bus.get_proxy_object('org.bluez', self.adapter_path, introspection)
        adapter_iface = adapter_obj.get_interface('org.bluez.Adapter1')

        # Call ConnectDevice with LE parameters
        params = {
            "Address": Variant("s", peer_address),
            "AddressType": Variant("s", "public")  # Force LE public address
        }

        # ConnectDevice() returns a D-Bus object path (signature 'o')
        # This is normal/expected - the object path indicates successful connection initiation
        result = await adapter_iface.call_connect_device(params)

        # Log the object path for debugging
        if result:
            self._log(f"ConnectDevice() succeeded for {peer_address}, got object path: {result}", "DEBUG")
        else:
            self._log(f"ConnectDevice() succeeded for {peer_address}", "DEBUG")

        self.has_connect_device = True
        return True

    async def _negotiate_mtu(self, client: BleakClient) -> int:
        """
        Negotiate MTU using 3 fallback methods.

        Returns negotiated MTU size.
        """
        mtu = None

        # Method 1: Try direct MTU property access (BlueZ 5.62+)
        if hasattr(client, '_backend') and hasattr(client, 'services') and client.services:
            try:
                for char in client.services.characteristics.values():
                    if hasattr(char, 'obj') and len(char.obj) > 1:
                        char_props = char.obj[1]
                        if isinstance(char_props, dict) and "MTU" in char_props:
                            mtu = char_props["MTU"]
                            self._log(f"Read MTU {mtu} from characteristic property", "DEBUG")
                            break
            except Exception as e:
                self._log(f"Could not read MTU from characteristic properties: {e}", "DEBUG")

        # Method 2: Try _acquire_mtu() for older BlueZ versions
        if mtu is None and hasattr(client, '_backend') and hasattr(client._backend, '_acquire_mtu'):
            try:
                await client._backend._acquire_mtu()
                mtu = client.mtu_size
                self._log(f"Acquired MTU {mtu} via _acquire_mtu()", "DEBUG")
            except Exception as e:
                self._log(f"Failed to acquire MTU via _acquire_mtu(): {e}", "DEBUG")

        # Method 3: Fallback to client.mtu_size
        if mtu is None:
            try:
                mtu = client.mtu_size
                self._log(f"Using fallback MTU {mtu} from client.mtu_size", "DEBUG")
            except Exception as e:
                self._log(f"Could not get MTU, using default 23: {e}", "WARNING")
                mtu = 23

        return mtu

    def _handle_notification(self, address: str, data: bytes):
        """Handle incoming notification from peer."""
        if self.on_data_received:
            try:
                self.on_data_received(address, data)
            except Exception as e:
                self._log(f"Error in data received callback: {e}", "ERROR")

    # ========================================================================
    # Data Transmission
    # ========================================================================

    def send(self, address: str, data: bytes):
        """
        Send data to a connected peer.

        Automatically chooses GATT write (central) or notification (peripheral).
        """
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError(f"Not connected to {address}")

            peer = self._peers[address]

        if peer.connection_type == "central":
            # We connected to them: use GATT write
            future = asyncio.run_coroutine_threadsafe(
                peer.client.write_gatt_char(self.rx_char_uuid, data, response=False),
                self.loop
            )
            try:
                future.result(timeout=5.0)
            except Exception as e:
                self._log(f"Error sending data to {address}: {e}", "ERROR")
                raise

        elif peer.connection_type == "peripheral":
            # They connected to us: use notification
            if self.gatt_server:
                try:
                    self.gatt_server.send_notification(address, data)
                except Exception as e:
                    self._log(f"Error sending notification to {address}: {e}", "ERROR")
                    raise
            else:
                raise RuntimeError("GATT server not available for peripheral connection")

        else:
            raise RuntimeError(f"Unknown connection type: {peer.connection_type}")

    # ========================================================================
    # GATT Characteristic Operations
    # ========================================================================

    def read_characteristic(self, address: str, char_uuid: str) -> bytes:
        """Read a GATT characteristic value."""
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError(f"Not connected to {address}")

            peer = self._peers[address]

        if peer.connection_type != "central" or not peer.client:
            raise RuntimeError("Can only read characteristics in central mode")

        future = asyncio.run_coroutine_threadsafe(
            peer.client.read_gatt_char(char_uuid),
            self.loop
        )

        try:
            result = future.result(timeout=5.0)
            return bytes(result)
        except Exception as e:
            self._log(f"Error reading characteristic {char_uuid} from {address}: {type(e).__name__}: {e}", "ERROR")
            raise

    def write_characteristic(self, address: str, char_uuid: str, data: bytes):
        """Write a value to a GATT characteristic."""
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError(f"Not connected to {address}")

            peer = self._peers[address]

        if peer.connection_type != "central" or not peer.client:
            raise RuntimeError("Can only write characteristics in central mode")

        future = asyncio.run_coroutine_threadsafe(
            peer.client.write_gatt_char(char_uuid, data, response=True),
            self.loop
        )

        try:
            future.result(timeout=5.0)
        except Exception as e:
            self._log(f"Error writing characteristic {char_uuid} to {address}: {e}", "ERROR")
            raise

    def start_notify(self, address: str, char_uuid: str, callback: Callable[[bytes], None]):
        """Subscribe to notifications from a GATT characteristic."""
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError(f"Not connected to {address}")

            peer = self._peers[address]

        if peer.connection_type != "central" or not peer.client:
            raise RuntimeError("Can only subscribe to notifications in central mode")

        def notification_handler(sender, data):
            """Wrapper to call user callback."""
            try:
                callback(bytes(data))
            except Exception as e:
                self._log(f"Error in notification callback: {e}", "ERROR")

        future = asyncio.run_coroutine_threadsafe(
            peer.client.start_notify(char_uuid, notification_handler),
            self.loop
        )

        try:
            future.result(timeout=5.0)
        except Exception as e:
            self._log(f"Error starting notifications for {char_uuid} from {address}: {e}", "ERROR")
            raise

    # ========================================================================
    # Configuration & Queries
    # ========================================================================

    def get_local_address(self) -> str:
        """Return local Bluetooth adapter MAC address."""
        return self.local_address or "00:00:00:00:00:00"

    def get_peer_role(self, address: str) -> Optional[str]:
        """Return the connection role ('central' or 'peripheral') for a peer."""
        with self._peers_lock:
            if address in self._peers:
                return self._peers[address].connection_type
            return None

    def get_peer_mtu(self, address: str) -> Optional[int]:
        """Return the negotiated MTU for a peer connection.

        Checks both central connections (we connected to them) and peripheral
        connections (they connected to us).
        """
        # Check central connections (we are central)
        with self._peers_lock:
            if address in self._peers:
                return self._peers[address].mtu

        # Check peripheral connections (we are peripheral, they are central)
        if self.gatt_server:
            with self.gatt_server.centrals_lock:
                if address in self.gatt_server.connected_centrals:
                    return self.gatt_server.connected_centrals[address].get("mtu")

        return None

    def set_service_discovery_delay(self, seconds: float):
        """Set delay between connection and service discovery."""
        self.service_discovery_delay = seconds
        self._log(f"Service discovery delay set to {seconds}s")

    def set_power_mode(self, mode: str):
        """Set power mode for scanning."""
        if mode not in ["aggressive", "balanced", "saver"]:
            raise ValueError(f"Invalid power mode: {mode}")

        self.power_mode = mode
        self._log(f"Power mode set to {mode}")

    # ========================================================================
    # Event Loop Management
    # ========================================================================

    def _run_event_loop(self):
        """Run asyncio event loop in separate thread."""
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self._log("Event loop thread started", "DEBUG")
        self.loop.run_forever()
        self._log("Event loop thread stopped", "DEBUG")

    # ========================================================================
    # Platform Detection
    # ========================================================================

    async def _get_local_adapter_address(self) -> Optional[str]:
        """Get local Bluetooth adapter MAC address via D-Bus."""
        if not HAS_DBUS:
            return None

        try:
            from bleak.backends.bluezdbus import defs

            bus = await MessageBus(bus_type=BusType.SYSTEM).connect()

            # Try specified adapter
            try:
                introspection = await bus.introspect('org.bluez', self.adapter_path)
                obj = bus.get_proxy_object('org.bluez', self.adapter_path, introspection)
                adapter = obj.get_interface(defs.ADAPTER_INTERFACE)
                properties_interface = obj.get_interface('org.freedesktop.DBus.Properties')
                address = await properties_interface.call_get(defs.ADAPTER_INTERFACE, 'Address')

                # Extract value from Variant
                if hasattr(address, 'value'):
                    address = address.value

                self._log(f"Local adapter address: {address}", "DEBUG")
                return address

            except Exception as e:
                self._log(f"Could not get adapter address via D-Bus: {e}", "DEBUG")
                return None

        except Exception as e:
            self._log(f"D-Bus adapter address retrieval failed: {e}", "DEBUG")
            return None

    def _detect_bluez_version(self):
        """Detect BlueZ version from bluetoothctl."""
        try:
            import subprocess
            result = subprocess.run(
                ['bluetoothctl', '--version'],
                capture_output=True,
                text=True,
                timeout=5
            )
            version_str = result.stdout.strip().split()[-1]
            self.bluez_version = tuple(map(int, version_str.split('.')))
            self._log(f"Detected BlueZ version {version_str}")
        except Exception as e:
            self._log(f"Could not detect BlueZ version: {e}", "DEBUG")
            self.bluez_version = None


# ============================================================================
# Bluezero GATT Server (Peripheral Mode)
# ============================================================================

class BluezeroGATTServer:
    """
    GATT server implementation using bluezero.

    This handles peripheral mode operations:
    - Creating GATT service and characteristics
    - Accepting connections from centrals
    - Receiving data via RX characteristic (centrals write to us)
    - Sending data via TX characteristic (we notify centrals)
    """

    def __init__(
        self,
        driver: LinuxBluetoothDriver,
        service_uuid: str,
        rx_char_uuid: str,
        tx_char_uuid: str,
        identity_char_uuid: str,
        adapter_index: int = 0,
        agent_capability: str = "NoInputNoOutput"
    ):
        """Initialize GATT server."""
        if not BLUEZERO_AVAILABLE:
            raise ImportError("bluezero library required for GATT server")

        self.driver = driver
        self.service_uuid = service_uuid
        self.rx_char_uuid = rx_char_uuid
        self.tx_char_uuid = tx_char_uuid
        self.identity_char_uuid = identity_char_uuid
        self.adapter_index = adapter_index
        self.agent_capability = agent_capability

        # bluezero objects
        self.peripheral_obj = None
        self.tx_characteristic = None
        self.identity_characteristic = None

        # State
        self.running = False

        # Identity
        self.identity_bytes: Optional[bytes] = None

        # BLE agent
        self.ble_agent = None

        # Threads
        self.server_thread: Optional[threading.Thread] = None
        self.disconnect_monitor_thread: Optional[threading.Thread] = None
        self.stale_poll_thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        self.started_event = threading.Event()

        # Connected centrals (address -> info dict)
        self.connected_centrals: Dict[str, dict] = {}
        self.centrals_lock = threading.RLock()

        # Wire up disconnection callback to driver
        # This ensures peripheral disconnect events trigger cleanup in the driver
        self.on_central_disconnected = driver._handle_peripheral_disconnected

    def _log(self, message: str, level: str = "INFO"):
        """Log message."""
        self.driver._log(f"GATTServer: {message}", level)

    def set_identity(self, identity_bytes: bytes):
        """Set the identity value for the Identity characteristic."""
        if len(identity_bytes) != 16:
            raise ValueError("Identity must be 16 bytes")

        self.identity_bytes = identity_bytes
        # Proactively update the characteristic value if it already exists
        if self.identity_characteristic:
            self.identity_characteristic.set_value(list(self.identity_bytes))

        self._log(f"Identity set: {identity_bytes.hex()}")

    def _verify_services_on_dbus(self, timeout: float = 5.0) -> bool:
        """
        Verify that GATT services are actually exported to D-Bus.

        This prevents the race condition where started_event fires before
        peripheral.publish() fully exports services to D-Bus, causing
        "service not found" errors when centrals connect immediately.

        Args:
            timeout: Maximum time to wait for services (seconds)

        Returns:
            True if services found on D-Bus, False otherwise
        """
        if not HAS_DBUS:
            self._log("D-Bus not available, skipping service verification", "DEBUG")
            return True  # Assume success if D-Bus not available

        import time
        import asyncio

        poll_interval = 0.2  # Poll every 200ms
        elapsed = 0.0

        self._log(f"Polling D-Bus for service {self.service_uuid}...", "DEBUG")

        while elapsed < timeout:
            try:
                # Check if services are present on D-Bus
                # We do this by trying to introspect the adapter and looking for our service
                async def check_services():
                    try:
                        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()

                        # Introspect the adapter
                        adapter_path = f"/org/bluez/hci{self.adapter_index}"
                        introspection = await bus.introspect('org.bluez', adapter_path)

                        # Look for GATT service paths under the adapter
                        # Services appear as /org/bluez/hci0/service000X
                        # We can't directly query by UUID easily, but if introspection succeeds
                        # and doesn't error, services are likely ready
                        # This is a basic check - services being registered is indicated by
                        # the adapter introspection being successful after publish()

                        self._log("D-Bus adapter introspection successful, services likely ready", "DEBUG")
                        return True

                    except Exception as e:
                        self._log(f"D-Bus check error: {e}", "DEBUG")
                        return False

                # Run the async check
                result = asyncio.run(check_services())

                if result:
                    self._log(f"Services verified on D-Bus after {elapsed:.1f}s", "DEBUG")
                    return True

            except Exception as e:
                self._log(f"Error checking D-Bus services: {e}", "DEBUG")

            time.sleep(poll_interval)
            elapsed += poll_interval

        self._log(f"Services not found on D-Bus after {timeout}s timeout", "DEBUG")
        return False

    def _monitor_device_disconnections(self):
        """
        Monitor D-Bus for device disconnection signals (runs in separate thread).

        This method subscribes to PropertiesChanged signals from BlueZ using the
        high-level ObjectManager API and detects when connected central devices
        disconnect. When a disconnect is detected, it calls _handle_central_disconnected()
        to perform cleanup.

        This fixes the bug where peripheral disconnections were never detected,
        causing stale peer entries and eventual connection blocking.

        Runs continuously until stop_event is set.

        Implementation: Uses ObjectManager to monitor all BlueZ devices and subscribes
        to PropertiesChanged signals via the high-level proxy interface, which properly
        handles D-Bus message dispatch and signal delivery.
        """
        import sys

        if not HAS_DBUS:
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] D-Bus not available, disconnect monitoring disabled", RNS.LOG_EXTREME)
            self._log("D-Bus not available, disconnect monitoring disabled", "WARNING")
            return

        import asyncio
        from dbus_fast.aio import MessageBus
        from dbus_fast import BusType

        if RNS:
            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Starting D-Bus disconnect monitoring thread...", RNS.LOG_EXTREME)
        self._log("Starting D-Bus disconnect monitoring thread...", "DEBUG")

        async def monitor_loop():
            """Async loop that monitors D-Bus signals using ObjectManager."""
            import sys
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Entered monitor_loop()", RNS.LOG_EXTREME)

            bus = None
            device_proxies = {}  # Track proxy objects for each device

            try:
                # Connect to system bus
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Connecting to D-Bus...", RNS.LOG_EXTREME)
                bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Connected to D-Bus successfully", RNS.LOG_EXTREME)
                self._log("Connected to D-Bus for disconnect monitoring", "DEBUG")

                # Get ObjectManager for BlueZ to discover all devices
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Getting ObjectManager introspection...", RNS.LOG_EXTREME)
                introspection = await bus.introspect("org.bluez", "/")
                obj = bus.get_proxy_object("org.bluez", "/", introspection)
                object_manager = obj.get_interface("org.freedesktop.DBus.ObjectManager")
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] ObjectManager interface acquired", RNS.LOG_EXTREME)

                def handle_properties_changed(interface_name, changed_properties, invalidated_properties, device_path):
                    """Handle PropertiesChanged signal from a specific device."""
                    try:
                        # Only interested in org.bluez.Device1 interface
                        if interface_name != "org.bluez.Device1":
                            return

                        # Check if Connected property changed
                        if "Connected" in changed_properties:
                            # changed_properties is a dict of {property_name: Variant}
                            is_connected = changed_properties["Connected"].value

                            if not is_connected:  # Device disconnected
                                # Extract MAC address from D-Bus path
                                # Path format: /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF
                                if "/dev_" in device_path:
                                    mac_with_underscores = device_path.split("/dev_")[-1]
                                    mac_address = mac_with_underscores.replace("_", ":")

                                    if RNS:
                                        RNS.log(f"{self.log_prefix} [GATT-MONITOR] D-Bus: Device {mac_address} disconnected", RNS.LOG_EXTREME)
                                    self._log(f"D-Bus: Device {mac_address} disconnected", "DEBUG")

                                    # Check if this was a connected central
                                    with self.centrals_lock:
                                        if mac_address in self.connected_centrals:
                                            if RNS:
                                                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Detected central disconnect: {mac_address}", RNS.LOG_EXTREME)
                                            self._log(f"Detected central disconnect via D-Bus: {mac_address}", "INFO")
                                            # Call disconnect handler
                                            self._handle_central_disconnected(mac_address)

                    except Exception as e:
                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Error in PropertiesChanged handler: {e}", RNS.LOG_EXTREME)
                        self._log(f"Error in D-Bus signal handler: {e}", "ERROR")
                        import traceback
                        traceback.print_exc(file=sys.stderr)

                async def subscribe_to_device(device_path):
                    """Subscribe to PropertiesChanged for a specific device."""
                    try:
                        # Skip if already subscribed
                        if device_path in device_proxies:
                            return

                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Subscribing to device: {device_path}", RNS.LOG_EXTREME)

                        # Get device proxy
                        device_introspection = await bus.introspect("org.bluez", device_path)
                        device_obj = bus.get_proxy_object("org.bluez", device_path, device_introspection)
                        device_proxies[device_path] = device_obj

                        # Get Properties interface
                        props_iface = device_obj.get_interface("org.freedesktop.DBus.Properties")

                        # Subscribe to PropertiesChanged with lambda that passes device_path
                        props_iface.on_properties_changed(
                            lambda iface, changed, invalidated: handle_properties_changed(
                                iface, changed, invalidated, device_path
                            )
                        )

                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Subscribed to device {device_path}", RNS.LOG_EXTREME)

                    except Exception as e:
                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Error subscribing to device {device_path}: {e}", RNS.LOG_EXTREME)
                        self._log(f"Error subscribing to device {device_path}: {e}", "WARNING")

                def on_interfaces_added(path, interfaces):
                    """Handle new devices being added to BlueZ."""
                    try:
                        if "org.bluez.Device1" in interfaces:
                            if RNS:
                                RNS.log(f"{self.log_prefix} [GATT-MONITOR] New device added: {path}", RNS.LOG_EXTREME)
                            # Schedule subscription in the event loop
                            asyncio.create_task(subscribe_to_device(path))
                    except Exception as e:
                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Error in InterfacesAdded handler: {e}", RNS.LOG_EXTREME)

                def on_interfaces_removed(path, interfaces):
                    """Handle devices being removed from BlueZ."""
                    try:
                        if "org.bluez.Device1" in interfaces:
                            if RNS:
                                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Device removed: {path}", RNS.LOG_EXTREME)
                            # Clean up proxy
                            if path in device_proxies:
                                del device_proxies[path]
                    except Exception as e:
                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Error in InterfacesRemoved handler: {e}", RNS.LOG_EXTREME)

                # Subscribe to device additions/removals
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Setting up ObjectManager signal handlers...", RNS.LOG_EXTREME)
                object_manager.on_interfaces_added(on_interfaces_added)
                object_manager.on_interfaces_removed(on_interfaces_removed)
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] ObjectManager handlers configured", RNS.LOG_EXTREME)

                # Get existing devices and subscribe to them
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Getting existing managed objects...", RNS.LOG_EXTREME)
                managed_objects = await object_manager.call_get_managed_objects()
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Found {len(managed_objects)} managed objects", RNS.LOG_EXTREME)

                device_count = 0
                for path, interfaces in managed_objects.items():
                    if "org.bluez.Device1" in interfaces:
                        device_count += 1
                        await subscribe_to_device(path)

                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Subscribed to {device_count} existing devices", RNS.LOG_EXTREME)
                self._log(f"D-Bus monitoring active for {device_count} devices", "DEBUG")

                # Keep the event loop running
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Entering wait loop...", RNS.LOG_EXTREME)

                # Poll stop_event and yield to event loop to process D-Bus messages
                while not self.stop_event.is_set():
                    await asyncio.sleep(0.5)

                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] Stop event set, exiting loop", RNS.LOG_EXTREME)
                self._log("D-Bus monitoring loop exiting", "DEBUG")

            except Exception as e:
                if RNS:
                    RNS.log(f"{self.log_prefix} [GATT-MONITOR] EXCEPTION in monitoring loop: {e}", RNS.LOG_EXTREME)
                self._log(f"Error in D-Bus monitoring loop: {e}", "ERROR")
                import traceback
                traceback.print_exc(file=sys.stderr)

            finally:
                # Clean up bus connection
                if bus:
                    try:
                        bus.disconnect()
                        if RNS:
                            RNS.log(f"{self.log_prefix} [GATT-MONITOR] D-Bus connection closed", RNS.LOG_EXTREME)
                    except:
                        pass

        # Run the async monitoring loop
        try:
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Calling asyncio.run(monitor_loop())", RNS.LOG_EXTREME)
            asyncio.run(monitor_loop())
        except Exception as e:
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Thread exception: {e}", RNS.LOG_EXTREME)
            self._log(f"D-Bus monitoring thread error: {e}", "ERROR")
            import traceback
            traceback.print_exc(file=sys.stderr)

        if RNS:
            RNS.log(f"{self.log_prefix} [GATT-MONITOR] Thread exited", RNS.LOG_EXTREME)
        self._log("D-Bus disconnect monitoring thread exited", "DEBUG")

    def _poll_stale_connections(self):
        """
        Polling-based fallback for detecting stale connections (runs in separate thread).

        This method runs independently of D-Bus signal monitoring and provides a
        safety net by periodically checking if devices in connected_centrals are
        still actually connected according to BlueZ's Device1 interface.

        Polls every 30 seconds and triggers cleanup for any centrals that are
        marked as connected locally but show Connected=False in BlueZ.

        This handles cases where D-Bus signals are missed or delayed, ensuring
        cleanup always happens eventually.
        """
        import sys
        import time

        if RNS:
            RNS.log(f"{self.log_prefix} [STALE-POLL] Starting stale connection polling thread...", RNS.LOG_EXTREME)
        self._log("Starting stale connection polling", "DEBUG")

        # Import at function level to avoid issues if not available
        try:
            import dbus
        except ImportError:
            if RNS:
                RNS.log(f"{self.log_prefix} [STALE-POLL] dbus-python not available, polling disabled", RNS.LOG_EXTREME)
            self._log("dbus-python not available, stale connection polling disabled", "WARNING")
            return

        while not self.stop_event.is_set():
            try:
                # Wait for 30 seconds (check stop_event frequently)
                for _ in range(60):  # 60 * 0.5s = 30s
                    if self.stop_event.is_set():
                        break
                    time.sleep(0.5)

                if self.stop_event.is_set():
                    break

                # Check all connected centrals
                with self.centrals_lock:
                    centrals_to_check = list(self.connected_centrals.keys())

                if not centrals_to_check:
                    continue

                if RNS:
                    RNS.log(f"{self.log_prefix} [STALE-POLL] Checking {len(centrals_to_check)} centrals...", RNS.LOG_EXTREME)

                # Connect to D-Bus and check each device
                try:
                    bus = dbus.SystemBus()

                    for mac_address in centrals_to_check:
                        try:
                            # Convert MAC to D-Bus path format
                            dbus_path = f"/org/bluez/hci0/dev_{mac_address.replace(':', '_')}"

                            # Get device object
                            device_obj = bus.get_object("org.bluez", dbus_path)
                            props_iface = dbus.Interface(device_obj, "org.freedesktop.DBus.Properties")

                            # Check Connected property
                            is_connected = props_iface.Get("org.bluez.Device1", "Connected")

                            if not is_connected:
                                # Device shows as disconnected in BlueZ but we still have it tracked
                                if RNS:
                                    RNS.log(f"{self.log_prefix} [STALE-POLL] Detected stale connection: {mac_address}", RNS.LOG_EXTREME)
                                self._log(f"Polling detected stale connection: {mac_address}", "INFO")

                                # Trigger cleanup
                                with self.centrals_lock:
                                    if mac_address in self.connected_centrals:
                                        self._handle_central_disconnected(mac_address)

                        except dbus.exceptions.DBusException as e:
                            # Device might not exist in BlueZ anymore
                            if "UnknownObject" in str(e) or "UnknownMethod" in str(e):
                                if RNS:
                                    RNS.log(f"{self.log_prefix} [STALE-POLL] Device {mac_address} no longer in BlueZ, cleaning up", RNS.LOG_EXTREME)
                                self._log(f"Device {mac_address} no longer in BlueZ", "DEBUG")

                                # Trigger cleanup
                                with self.centrals_lock:
                                    if mac_address in self.connected_centrals:
                                        self._handle_central_disconnected(mac_address)
                            else:
                                # Other D-Bus error, log but don't cleanup
                                if RNS:
                                    RNS.log(f"{self.log_prefix} [STALE-POLL] D-Bus error checking {mac_address}: {e}", RNS.LOG_EXTREME)

                except Exception as e:
                    if RNS:
                        RNS.log(f"{self.log_prefix} [STALE-POLL] Error during polling cycle: {e}", RNS.LOG_EXTREME)
                    self._log(f"Error in stale connection polling: {e}", "WARNING")

            except Exception as e:
                if RNS:
                    RNS.log(f"{self.log_prefix} [STALE-POLL] Unexpected error: {e}", RNS.LOG_EXTREME)
                self._log(f"Unexpected error in polling thread: {e}", "ERROR")
                import traceback
                traceback.print_exc(file=sys.stderr)

        if RNS:
            RNS.log(f"{self.log_prefix} [STALE-POLL] Thread exited", RNS.LOG_EXTREME)
        self._log("Stale connection polling thread exited", "DEBUG")

    def start(self, device_name: Optional[str]):
        """Start GATT server and advertising."""
        import sys
        if RNS:
            RNS.log(f"{self.log_prefix} [GATT-MONITOR] BluezeroGATTServer.start() called, device_name={device_name}", RNS.LOG_EXTREME)

        if self.running:
            self._log("Server already running", "WARNING")
            return

        # Ensure identity is set before starting
        if not self.identity_bytes:
            raise RuntimeError("Identity must be set before starting GATT server. Call set_identity() first.")

        if device_name:
            self._log(f"Starting GATT server with device name '{device_name}'...")
        else:
            self._log("Starting GATT server (no device name)...")

        # Reset events
        self.stop_event.clear()
        self.started_event.clear()

        # Start server thread
        self.server_thread = threading.Thread(
            target=self._run_server_thread,
            args=(device_name,),
            daemon=True,
            name="bluezero-gatt-server"
        )
        self.server_thread.start()

        # Wait for server to start
        started = self.started_event.wait(timeout=10.0)

        if not started or not self.running:
            raise RuntimeError("GATT server failed to start within timeout")

        # Additional verification: Ensure services are actually exported to D-Bus
        # This prevents race condition where started_event fires before publish()
        # fully exports services, causing "service not found" errors
        self._log("Verifying services are exported to D-Bus...", "DEBUG")

        services_ready = self._verify_services_on_dbus(timeout=5.0)

        if not services_ready:
            self._log("Services not found on D-Bus after timeout", "WARNING")
            # Don't fail hard - server might still work, just warn
            # raise RuntimeError("GATT services not found on D-Bus")

        # Start D-Bus disconnect monitoring thread
        import sys
        if RNS:
            RNS.log(f"{self.log_prefix} [GATT-MONITOR] About to start monitoring thread, HAS_DBUS={HAS_DBUS}", RNS.LOG_EXTREME)
        if HAS_DBUS:
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Creating thread...", RNS.LOG_EXTREME)
            self.disconnect_monitor_thread = threading.Thread(
                target=self._monitor_device_disconnections,
                daemon=True,
                name="dbus-disconnect-monitor"
            )
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Starting thread...", RNS.LOG_EXTREME)
            self.disconnect_monitor_thread.start()
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] Thread started successfully", RNS.LOG_EXTREME)
            self._log("D-Bus disconnect monitoring started", "DEBUG")
        else:
            if RNS:
                RNS.log(f"{self.log_prefix} [GATT-MONITOR] HAS_DBUS is False, skipping", RNS.LOG_EXTREME)
            self._log("D-Bus not available, disconnect monitoring disabled", "WARNING")

        # Start stale connection polling thread (fallback mechanism)
        if RNS:
            RNS.log(f"{self.log_prefix} [STALE-POLL] Starting stale connection polling thread...", RNS.LOG_EXTREME)
        self.stale_poll_thread = threading.Thread(
            target=self._poll_stale_connections,
            daemon=True,
            name="stale-connection-poller"
        )
        self.stale_poll_thread.start()
        if RNS:
            RNS.log(f"{self.log_prefix} [STALE-POLL] Thread started successfully", RNS.LOG_EXTREME)
        self._log("Stale connection polling started", "DEBUG")

        self._log("GATT server started and advertising")

    def stop(self):
        """Stop GATT server and advertising."""
        if not self.running:
            return

        self._log("Stopping GATT server...")

        # Signal server thread to stop
        self.stop_event.set()
        self.running = False

        # Wait for server thread to exit
        if self.server_thread and self.server_thread.is_alive():
            self.server_thread.join(timeout=5.0)

        # Wait for disconnect monitoring thread to exit
        if self.disconnect_monitor_thread and self.disconnect_monitor_thread.is_alive():
            self.disconnect_monitor_thread.join(timeout=2.0)
            self._log("D-Bus disconnect monitoring stopped", "DEBUG")

        # Wait for stale polling thread to exit
        if self.stale_poll_thread and self.stale_poll_thread.is_alive():
            self.stale_poll_thread.join(timeout=2.0)
            self._log("Stale connection polling stopped", "DEBUG")

        # Unregister agent
        if self.ble_agent and HAS_BLE_AGENT:
            try:
                unregister_agent(self.ble_agent)
                self._log("BLE agent unregistered", "DEBUG")
            except Exception as e:
                self._log(f"Error unregistering agent: {e}", "DEBUG")
            self.ble_agent = None

        with self.centrals_lock:
            self.connected_centrals.clear()

        self._log("GATT server stopped")

    def _run_server_thread(self, device_name: str):
        """Run GATT server in separate thread."""
        try:
            self._log("Server thread starting...", "DEBUG")

            # Register BLE agent for automatic pairing
            if HAS_BLE_AGENT:
                try:
                    self.ble_agent = register_agent(self.agent_capability)
                    self._log(f"BLE agent registered with capability: {self.agent_capability}")
                except Exception as e:
                    self._log(f"Failed to register BLE agent: {e}", "WARNING")
                    self.ble_agent = None

            # Suppress bluezero logging
            logging.getLogger('bluezero').setLevel(logging.WARNING)
            logging.getLogger('bluezero.GATT').setLevel(logging.WARNING)
            logging.getLogger('bluezero.localGATT').setLevel(logging.WARNING)
            logging.getLogger('bluezero.adapter').setLevel(logging.WARNING)
            logging.getLogger('bluezero.peripheral').setLevel(logging.WARNING)

            # Get adapter
            adapters = adapter.list_adapters()
            if not adapters:
                self._log("No Bluetooth adapters found!", "ERROR")
                self.started_event.set()
                return

            if self.adapter_index >= len(adapters):
                self._log(f"Adapter index {self.adapter_index} out of range (only {len(adapters)} adapters)", "ERROR")
                self.started_event.set()
                return

            local_adapter = adapter.Adapter(adapters[self.adapter_index])
            adapter_address = local_adapter.address
            self._log(f"Using adapter: {adapter_address}", "DEBUG")

            # Create peripheral (omit local_name if None to save advertisement packet space)
            if device_name:
                self.peripheral_obj = peripheral.Peripheral(
                    adapter_address,
                    local_name=device_name
                )
            else:
                self.peripheral_obj = peripheral.Peripheral(adapter_address)

            # Add service
            self.peripheral_obj.add_service(
                srv_id=1,
                uuid=self.service_uuid,
                primary=True
            )
            self._log(f"Added service: {self.service_uuid}", "DEBUG")

            # Add RX characteristic (centrals write to us)
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=1,
                uuid=self.rx_char_uuid,
                value=[],
                notifying=False,
                flags=['write', 'write-without-response'],
                write_callback=self._handle_write_rx
            )
            self._log(f"Added RX characteristic: {self.rx_char_uuid}", "DEBUG")

            # Add TX characteristic (we notify centrals)
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=2,
                uuid=self.tx_char_uuid,
                value=[],
                notifying=True,
                flags=['read', 'notify']
            )
            self._log(f"Added TX characteristic: {self.tx_char_uuid}", "DEBUG")

            # Add Identity characteristic (centrals read our identity)
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=3,
                uuid=self.identity_char_uuid,
                value=[0]*16,  # Initialize with 16-byte placeholder
                notifying=False,
                flags=['read'],
                read_callback=self._handle_read_identity
            )
            self.identity_characteristic = self.peripheral_obj.characteristics[-1]
            self._log(f"Added Identity characteristic: {self.identity_char_uuid}", "DEBUG")

            # Set the identity value (guaranteed to be available by start() precondition)
            self.identity_characteristic.set_value(list(self.identity_bytes))
            self._log(f"Identity characteristic set to: {self.identity_bytes.hex()}")

            # Save TX characteristic reference
            if len(self.peripheral_obj.characteristics) >= 2:
                self.tx_characteristic = self.peripheral_obj.characteristics[1]  # chr_id=2
                self._log("Saved TX characteristic reference", "DEBUG")
            else:
                self._log(f"ERROR: TX characteristic not found!", "ERROR")
                self.started_event.set()
                return

            self._log("GATT server configured successfully")

            # Signal ready
            self.running = True
            self.started_event.set()

            # Publish (blocks until stopped)
            self._log("Publishing (blocking call)...", "DEBUG")
            self.peripheral_obj.publish()

        except Exception as e:
            self._log(f"Server thread error: {type(e).__name__}: {e}", "ERROR")
            import traceback
            traceback.print_exc()
            self.started_event.set()
        finally:
            self.running = False
            self._log("Server thread exiting", "DEBUG")

    def _handle_write_rx(self, value, options):
        """Handle write to RX characteristic (bluezero callback)."""
        # Convert to bytes
        if isinstance(value, list):
            data = bytes(value)
        elif isinstance(value, bytes):
            data = value
        else:
            data = bytes(value)

        # Extract central address and MTU
        central_address = options.get("device", "unknown")
        if central_address and central_address != "unknown":
            central_address = central_address.split("/")[-1].replace("_", ":")

        mtu = options.get("mtu", None)

        self._log(f"Received {len(data)} bytes from {central_address} (MTU: {mtu})", "DEBUG")

        # Track central connection
        with self.centrals_lock:
            if central_address not in self.connected_centrals:
                self._handle_central_connected(central_address, mtu)
            elif mtu is not None:
                # Update MTU
                old_mtu = self.connected_centrals[central_address].get("mtu", "unknown")
                if old_mtu != mtu:
                    self.connected_centrals[central_address]["mtu"] = mtu
                    self._log(f"Updated MTU for {central_address}: {old_mtu} -> {mtu}", "DEBUG")

                    # Notify callback
                    if self.driver.on_mtu_negotiated:
                        try:
                            self.driver.on_mtu_negotiated(central_address, mtu)
                        except Exception as e:
                            self._log(f"Error in MTU negotiated callback: {e}", "ERROR")

        # Pass data to driver callback
        if self.driver.on_data_received:
            try:
                self.driver.on_data_received(central_address, data)
            except Exception as e:
                self._log(f"Error in data received callback: {e}", "ERROR")

        return value  # bluezero expects value to be returned

    def _handle_read_identity(self, options):
        """Handle read of Identity characteristic (bluezero callback)."""
        central_address = options.get("device", "unknown")
        if central_address and central_address != "unknown":
            central_address = central_address.split("/")[-1].replace("_", ":")

        if self.identity_bytes is None:
            self._log(f"Identity read from {central_address}: not available", "WARNING")
            return []

        identity_list = list(self.identity_bytes)
        self._log(f"Identity read from {central_address}: {len(identity_list)} bytes", "DEBUG")
        return identity_list

    def _handle_central_connected(self, central_address: str, mtu: Optional[int]):
        """Handle new central connection."""
        if central_address in self.connected_centrals:
            self._log(f"Central {central_address} already connected", "WARNING")
            return

        effective_mtu = mtu if mtu is not None else 185

        self.connected_centrals[central_address] = {
            "address": central_address,
            "connected_at": time.time(),
            "mtu": effective_mtu
        }

        # Add to driver's peer list
        peer_conn = PeerConnection(
            address=central_address,
            client=None,  # No client for peripheral connections
            mtu=effective_mtu,
            connection_type="peripheral",
            connected_at=time.time()
        )

        with self.driver._peers_lock:
            self.driver._peers[central_address] = peer_conn

        self._log(f"Central connected: {central_address} (MTU: {effective_mtu})")

        # Notify callback (identity not available yet for peripheral connections)
        if self.driver.on_device_connected:
            try:
                self.driver.on_device_connected(central_address, None)
            except Exception as e:
                self._log(f"Error in device connected callback: {e}", "ERROR")

        # Notify MTU callback
        if self.driver.on_mtu_negotiated:
            try:
                self.driver.on_mtu_negotiated(central_address, effective_mtu)
            except Exception as e:
                self._log(f"Error in MTU negotiated callback: {e}", "ERROR")

    def _handle_central_disconnected(self, central_address: str):
        """
        Handle central disconnection from GATT server.

        This method is called when a central device disconnects from our peripheral.
        It performs cleanup and notifies the driver via the on_central_disconnected callback.

        Args:
            central_address: MAC address of the disconnected central device
        """
        with self.centrals_lock:
            if central_address not in self.connected_centrals:
                self._log(f"Central {central_address} not in connected list during disconnect", "DEBUG")
                return

            info = self.connected_centrals[central_address]
            connection_duration = time.time() - info['connected_at']

            # Log with appropriate severity based on connection duration
            if connection_duration < 30:
                # Short-lived connections may indicate power management issues (e.g., Android doze mode)
                self._log(
                    f"Central disconnected: {central_address} "
                    f"(was connected for {connection_duration:.1f}s - unusually short, may indicate power management)",
                    level="WARNING"
                )
                # Add troubleshooting hint for Android devices
                if connection_duration < 20:
                    self._log(
                        f"Short connection duration detected. If {central_address} is an Android device, "
                        f"ensure battery optimization is disabled for the BLE app and the device is not in doze mode.",
                        level="WARNING"
                    )
            else:
                self._log(
                    f"Central disconnected: {central_address} "
                    f"(was connected for {connection_duration:.1f}s)",
                    level="INFO"
                )

            del self.connected_centrals[central_address]

        # Notify driver via callback (if wired up)
        if hasattr(self, 'on_central_disconnected') and self.on_central_disconnected:
            try:
                self.on_central_disconnected(central_address)
            except Exception as e:
                self._log(f"Error in central disconnected callback: {e}", "ERROR")

    def send_notification(self, central_address: str, data: bytes):
        """Send notification to a connected central."""
        if not self.running or not self.tx_characteristic:
            raise RuntimeError("GATT server not running")

        with self.centrals_lock:
            if central_address not in self.connected_centrals:
                raise RuntimeError(f"Central {central_address} not connected")

        # Convert to list for bluezero
        if isinstance(data, bytes):
            value = list(data)
        else:
            value = data

        # Update characteristic value (bluezero automatically sends notification)
        self.tx_characteristic.set_value(value)

        self._log(f"Sent notification: {len(data)} bytes to {central_address}", "DEBUG")


# ============================================================================
# Module Exports
# ============================================================================

__all__ = [
    'LinuxBluetoothDriver',
    'apply_bluez_services_resolved_patch',
]
