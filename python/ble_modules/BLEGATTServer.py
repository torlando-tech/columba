"""
BLE GATT Server for Reticulum

This module implements BLE peripheral mode (GATT server) using the bluezero library
to enable devices to advertise themselves and accept connections from BLE centrals.

Implementation details:
- Uses bluezero (direct BlueZ D-Bus API)
- Linux-only (requires BlueZ 5.x)
- Thread-based architecture (bluezero publish() blocks)
- Supports multiple concurrent central connections
- MTU negotiation via BlueZ callback options

Author: Reticulum BLE Interface Contributors
License: MIT
"""

from __future__ import annotations

import asyncio
import time
import threading
import queue
from typing import Any, Dict, Optional, Callable
import logging

try:
    from bluezero import peripheral, adapter
    BLUEZERO_AVAILABLE = True
except ImportError:
    BLUEZERO_AVAILABLE = False

# Import BLE agent for automatic pairing
try:
    from BLEAgent import register_agent, unregister_agent
    HAS_BLE_AGENT = True
except ImportError:
    try:
        from RNS.Interfaces.BLEAgent import register_agent, unregister_agent
        HAS_BLE_AGENT = True
    except ImportError:
        HAS_BLE_AGENT = False


class BLEGATTServer:
    """
    BLE GATT Server for Reticulum (Peripheral Mode)

    Handles:
    - Advertising Reticulum service
    - Accepting connections from BLE centrals
    - Receiving data via RX characteristic (centrals write to us)
    - Sending data via TX characteristic (we notify centrals)
    - Managing multiple concurrent central connections

    This enables a device to be discovered by other BLE devices acting as centrals.
    """

    # Service UUID for Reticulum BLE
    SERVICE_UUID = "37145b00-442d-4a94-917f-8f42c5da28e3"

    # RX Characteristic: Centrals write to this (we receive)
    RX_CHAR_UUID = "37145b00-442d-4a94-917f-8f42c5da28e5"

    # TX Characteristic: We notify on this (centrals receive)
    TX_CHAR_UUID = "37145b00-442d-4a94-917f-8f42c5da28e4"

    # Identity Characteristic: Centrals read this to get stable node identity (Protocol v2)
    IDENTITY_CHAR_UUID = "37145b00-442d-4a94-917f-8f42c5da28e6"

    def __init__(self, interface, device_name: str = "Reticulum-Node", agent_capability: str = "NoInputNoOutput"):
        """
        Initialize BLE GATT Server

        Args:
            interface: Parent BLEInterface instance
            device_name: BLE device name for advertising
            agent_capability: Pairing agent capability ("NoInputNoOutput" for Just Works pairing, or "DisplayOnly")
                             Default "NoInputNoOutput" avoids MITM protection requirements
        """
        if not BLUEZERO_AVAILABLE:
            raise ImportError("BLE GATT Server requires 'bluezero' library. Install with: pip install bluezero>=0.9.1 dbus-python>=1.2.18")

        self.interface = interface
        self.device_name = device_name
        self.agent_capability = agent_capability
        self.running = False

        # bluezero objects (created in thread)
        self.peripheral_obj = None
        self.tx_characteristic = None
        self.rx_characteristic = None

        # Identity (Protocol v2)
        self.identity_hash = None  # 16-byte Transport identity hash

        # BLE agent for automatic pairing
        self.ble_agent = None

        # Threading
        self.server_thread = None
        self.stop_event = threading.Event()
        self.started_event = threading.Event()
        self.notification_queue = queue.Queue()

        # Track connected centrals
        # Key: central address (if available), Value: connection info
        self.connected_centrals: Dict[str, dict] = {}
        self._centrals_lock = threading.RLock()  # Reentrant lock to allow nested acquisitions in callback chains

        # Callbacks for data handling
        self.on_data_received: Optional[Callable] = None  # Called when data written to RX
        self.on_central_connected: Optional[Callable] = None
        self.on_central_disconnected: Optional[Callable] = None

        # Logging
        self.log_prefix = f"BLEGATTServer[{device_name}]"

        self._log(f"Initialized bluezero GATT server (agent capability: {agent_capability})", level="DEBUG")

    def _log(self, message: str, level: str = "INFO"):
        """Log message with appropriate level"""
        # Use RNS.log for consistent logging with interface
        try:
            import RNS
            # Map string level to RNS log levels
            level_map = {
                "DEBUG": RNS.LOG_DEBUG,
                "INFO": RNS.LOG_INFO,
                "WARNING": RNS.LOG_WARNING,
                "ERROR": RNS.LOG_ERROR,
            }
            rns_level = level_map.get(level.upper(), RNS.LOG_INFO)
            RNS.log(f"{self.log_prefix} {message}", rns_level)
        except:
            # Fallback to standard logging if RNS not available
            log_func = getattr(logging, level.lower(), logging.info)
            log_func(f"{self.log_prefix} {message}")

    # ========== bluezero Callbacks (run in server thread) ==========

    def _handle_write_rx(self, value, options):
        """
        Handle write request from central (bluezero callback)

        Called when a central writes data to RX characteristic.
        This runs in the bluezero thread.

        Args:
            value: The data written by the central (list of ints)
            options: D-Bus options dict (may contain 'device' address)

        Returns:
            value: Echo back the value (required by bluezero)
        """
        # Convert to bytes - ensure we always have bytes type
        if isinstance(value, list):
            data = bytes(value)
        elif isinstance(value, bytes):
            data = value
        else:
            # Handle other types (bytearray, etc.)
            data = bytes(value)

        # Extract central address and MTU from options (if available)
        central_address = options.get("device", "unknown")
        if central_address and central_address != "unknown":
            central_address = central_address.split("/")[-1].replace("_", ":")

        # Extract negotiated MTU from options (BlueZ provides this in GATT server callbacks)
        mtu = options.get("mtu", None)

        self._log(f">>> WRITE REQUEST from {central_address}: {len(data)} bytes (type: {type(data).__name__}, MTU: {mtu})", level="INFO")

        # Track this central if not already tracked, and update MTU if provided
        with self._centrals_lock:
            already_connected = central_address in self.connected_centrals
            self._log(f"Central membership check: {central_address} already_connected={already_connected}, dict_size={len(self.connected_centrals)}", level="DEBUG")

            if not already_connected:
                self._log(f"New central detected, calling _handle_central_connected({central_address}, mtu={mtu})", level="DEBUG")
                self._handle_central_connected(central_address, mtu)
            elif mtu is not None:
                # Update MTU for existing central (may be negotiated after first connection)
                old_mtu = self.connected_centrals[central_address].get("mtu", "unknown")
                if old_mtu != mtu:
                    self.connected_centrals[central_address]["mtu"] = mtu
                    self._log(f"Updated MTU for {central_address}: {old_mtu} -> {mtu}", level="DEBUG")

        # Pass data to callback for processing
        if self.on_data_received:
            try:
                # Verify data is bytes before callback
                if not isinstance(data, bytes):
                    self._log(f"WARNING: Converting {type(data).__name__} to bytes before callback", level="WARNING")
                    data = bytes(data)

                # Call the callback (synchronous call - runs in bluezero thread)
                self.on_data_received(data, central_address)
            except Exception as e:
                self._log(f"ERROR in data received callback: {type(e).__name__}: {e}", level="ERROR")
                import traceback
                self._log(f"Traceback: {traceback.format_exc()}", level="ERROR")
        else:
            self._log(f"on_data_received callback is NONE! Data LOST: {len(data)} bytes from {central_address}", level="ERROR")

        return value  # bluezero expects us to return the value

    def _handle_read_identity(self, options):
        """
        Handle read request for Identity characteristic (bluezero callback)

        Called when a central reads the Identity characteristic.
        Returns the 16-byte Transport identity hash.

        Args:
            options: D-Bus options dict (may contain 'device' address)

        Returns:
            list of ints: The 16-byte identity hash as a list of integers
        """
        # Extract central address from options
        central_address = options.get("device", "unknown")
        if central_address and central_address != "unknown":
            central_address = central_address.split("/")[-1].replace("_", ":")

        if self.identity_hash is None:
            self._log(f">>> READ REQUEST for Identity from {central_address}: Identity not available yet", level="WARNING")
            return []  # Return empty if not available

        # Convert bytes to list of ints for bluezero
        identity_list = list(self.identity_hash)
        self._log(f">>> READ REQUEST for Identity from {central_address}: Serving {len(identity_list)} bytes", level="INFO")
        return identity_list

    def _handle_central_connected(self, central_address: str, mtu: Optional[int] = None):
        """
        Handle new central connection

        Args:
            central_address: Address of connected central
            mtu: Negotiated MTU size (if available from BlueZ callback)
        """
        # DIAGNOSTIC: Method entry
        self._log(f"_handle_central_connected ENTRY: address={central_address}, mtu={mtu}, dict_size={len(self.connected_centrals)}", level="DEBUG")

        if central_address in self.connected_centrals:
            self._log(f"_handle_central_connected: {central_address} ALREADY in connected_centrals (duplicate call), skipping", level="WARNING")
            return

        # Default MTU: 185 bytes is common for BLE 4.2
        # Will be updated if BlueZ provides actual negotiated MTU
        effective_mtu = mtu if mtu is not None else 185

        self.connected_centrals[central_address] = {
            "address": central_address,
            "connected_at": time.time(),
            "bytes_received": 0,
            "bytes_sent": 0,
            "mtu": effective_mtu,
        }

        self._log(f"Central connected: {central_address} (MTU: {effective_mtu})", level="INFO")

        if self.on_central_connected:
            try:
                self._log(f"Invoking on_central_connected({central_address})...", level="DEBUG")
                self.on_central_connected(central_address)
                self._log(f"on_central_connected callback completed successfully for {central_address}", level="DEBUG")
            except Exception as e:
                self._log(f"Error in central connected callback: {e}", level="ERROR")

    def _handle_central_disconnected(self, central_address: str):
        """
        Handle central disconnection

        Args:
            central_address: Address of disconnected central
        """
        if central_address not in self.connected_centrals:
            return

        info = self.connected_centrals[central_address]
        self._log(
            f"Central disconnected: {central_address} "
            f"(RX: {info['bytes_received']}, TX: {info['bytes_sent']})",
            level="INFO"
        )

        del self.connected_centrals[central_address]

        if self.on_central_disconnected:
            try:
                self.on_central_disconnected(central_address)
            except Exception as e:
                self._log(f"Error in central disconnected callback: {e}", level="ERROR")

    # ========== Server Thread ==========

    def _run_server_thread(self):
        """
        Run bluezero GATT server in separate thread

        This thread blocks in peripheral.publish() until stopped.
        """
        try:
            self._log("Server thread starting...", level="DEBUG")

            # Register BLE agent for automatic pairing (if available)
            # MUST be done before creating peripheral to handle initial pairing
            if HAS_BLE_AGENT:
                try:
                    self.ble_agent = register_agent(self.agent_capability)
                    self._log(f"✓ BLE agent registered with capability: {self.agent_capability}", level="INFO")
                except Exception as e:
                    self._log(f"Failed to register BLE agent: {e}. Pairing may fail.", level="WARNING")
                    self.ble_agent = None
            else:
                self._log("BLEAgent module not available. Pairing will require manual interaction.", level="WARNING")

            # Suppress bluezero INFO logging to prevent TUI interference
            # bluezero logs things like "Notifying already, nothing to do" which
            # pollute stdout/stderr and break Nomadnet TUI display
            import logging
            logging.getLogger('bluezero').setLevel(logging.WARNING)
            logging.getLogger('bluezero.GATT').setLevel(logging.WARNING)
            logging.getLogger('bluezero.localGATT').setLevel(logging.WARNING)
            logging.getLogger('bluezero.adapter').setLevel(logging.WARNING)
            logging.getLogger('bluezero.peripheral').setLevel(logging.WARNING)

            # Get Bluetooth adapter
            adapters = adapter.list_adapters()
            if not adapters:
                self._log("No Bluetooth adapters found!", level="ERROR")
                self.started_event.set()  # Signal failure
                return

            local_adapter = adapter.Adapter(adapters[0])
            adapter_address = local_adapter.address
            self._log(f"Using adapter: {adapter_address}", level="DEBUG")

            # Create peripheral
            self.peripheral_obj = peripheral.Peripheral(
                adapter_address,
                local_name=self.device_name
            )

            # Add Reticulum service
            self.peripheral_obj.add_service(
                srv_id=1,
                uuid=self.SERVICE_UUID,
                primary=True
            )
            self._log(f"Added service: {self.SERVICE_UUID}", level="DEBUG")

            # Add RX characteristic (write from central)
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=1,
                uuid=self.RX_CHAR_UUID,
                value=[],
                notifying=False,
                flags=['write', 'write-without-response'],
                write_callback=self._handle_write_rx
            )
            self._log(f"Added RX characteristic: {self.RX_CHAR_UUID} (WRITE)", level="DEBUG")

            # Add TX characteristic (notify to central)
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=2,
                uuid=self.TX_CHAR_UUID,
                value=[],
                notifying=True,
                flags=['read', 'notify']
            )
            self._log(f"Added TX characteristic: {self.TX_CHAR_UUID} (READ, NOTIFY)", level="DEBUG")

            # Add Identity characteristic (read to get stable node identity - Protocol v2)
            identity_value = list(self.identity_hash) if self.identity_hash else []
            self.peripheral_obj.add_characteristic(
                srv_id=1,
                chr_id=3,
                uuid=self.IDENTITY_CHAR_UUID,
                value=identity_value,
                notifying=False,
                flags=['read'],
                read_callback=self._handle_read_identity
            )
            if identity_value:
                self._log(f"Added Identity characteristic: {self.IDENTITY_CHAR_UUID} (READ) with {len(identity_value)} bytes - Protocol v2", level="DEBUG")
            else:
                self._log(f"Added Identity characteristic: {self.IDENTITY_CHAR_UUID} (READ) with EMPTY value - will be updated when identity loads", level="WARNING")

            # Find and save TX characteristic for later notification sends
            # Characteristics are stored in order added: chr_id=1 (RX) is index 0, chr_id=2 (TX) is index 1
            if len(self.peripheral_obj.characteristics) >= 2:
                self.tx_characteristic = self.peripheral_obj.characteristics[1]  # chr_id=2 (TX)
                self._log(f"Saved TX characteristic reference (chr_id=2)", level="DEBUG")
            else:
                self._log(f"ERROR: TX characteristic not found! Only {len(self.peripheral_obj.characteristics)} characteristics", level="ERROR")
                self.started_event.set()
                return

            self._log("✓ GATT server configured successfully", level="INFO")

            # Signal that server is ready
            self.running = True
            self.started_event.set()

            # Start publishing (this blocks until stopped)
            self._log("Publishing (blocking call)...", level="DEBUG")
            self.peripheral_obj.publish()

        except Exception as e:
            self._log(f"Server thread error: {type(e).__name__}: {e}", level="ERROR")
            import traceback
            traceback.print_exc()
            self.started_event.set()  # Signal failure
        finally:
            # Unregister agent
            if self.ble_agent and HAS_BLE_AGENT:
                try:
                    unregister_agent(self.ble_agent)
                    self._log("BLE agent unregistered", level="DEBUG")
                except Exception as e:
                    self._log(f"Error unregistering agent: {e}", level="DEBUG")
                self.ble_agent = None

            self.running = False
            self._log("Server thread exiting", level="DEBUG")

    # ========== Public API (async, compatible with BLEGATTServer) ==========

    async def start(self):
        """
        Start the GATT server and begin advertising

        This creates the BLE service and characteristics, then starts advertising
        so that BLE centrals can discover and connect to this device.
        """
        if self.running:
            self._log("Server already running", level="WARNING")
            return

        try:
            self._log("Starting GATT server...")

            # Reset events
            self.stop_event.clear()
            self.started_event.clear()

            # Start server thread
            self.server_thread = threading.Thread(
                target=self._run_server_thread,
                daemon=True,
                name="bluezero-gatt-server"
            )
            self.server_thread.start()

            # Wait for server to start (with timeout)
            started = self.started_event.wait(timeout=10.0)

            if not started or not self.running:
                self._log("GATT server failed to start within timeout", level="ERROR")
                raise TimeoutError("GATT server startup timeout")

            self._log("✓ GATT server started and advertising", level="INFO")
            self._log(f"Device name: {self.device_name}", level="INFO")
            self._log(f"Service UUID: {self.SERVICE_UUID}", level="DEBUG")

        except Exception as e:
            error_type = type(e).__name__
            self._log(f"Failed to start GATT server: {error_type}: {e}", level="ERROR")
            self.running = False
            raise

    def set_transport_identity(self, identity_hash: bytes):
        """
        Set the Transport identity hash for BLE Protocol v2.

        This should be called after RNS.Transport is initialized and before
        starting the GATT server (or early during startup).

        Args:
            identity_hash: 16-byte Reticulum Transport identity hash
        """
        if not isinstance(identity_hash, bytes):
            raise TypeError(f"identity_hash must be bytes, got {type(identity_hash)}")

        if len(identity_hash) != 16:
            raise ValueError(f"identity_hash must be 16 bytes, got {len(identity_hash)}")

        self.identity_hash = identity_hash
        self._log(f"Transport identity set: {identity_hash.hex()}", level="INFO")

    async def stop(self):
        """
        Stop the GATT server and advertising

        Disconnects all centrals and stops advertising.
        """
        if not self.running:
            return

        try:
            self._log("Stopping GATT server...")

            # Signal server thread to stop
            self.stop_event.set()
            self.running = False

            # Wait for thread to exit (with timeout)
            if self.server_thread and self.server_thread.is_alive():
                self.server_thread.join(timeout=5.0)
                if self.server_thread.is_alive():
                    self._log("Server thread did not exit cleanly", level="WARNING")

            # Clean up connected centrals
            num_centrals = len(self.connected_centrals)
            if num_centrals > 0:
                self._log(f"Disconnecting {num_centrals} connected central(s)", level="DEBUG")

            with self._centrals_lock:
                self.connected_centrals.clear()

            self._log("✓ GATT server stopped", level="INFO")

        except Exception as e:
            error_type = type(e).__name__
            self._log(f"Error stopping GATT server: {error_type}: {e}", level="ERROR")
            # Ensure cleanup even on error
            self.running = False
            with self._centrals_lock:
                self.connected_centrals.clear()

    async def send_notification(self, data: bytes, central_address: Optional[str] = None):
        """
        Send notification to connected central(s)

        Sends data to a specific central or broadcasts to all connected centrals.
        Uses BLE notification mechanism on TX characteristic.

        Args:
            data: Data to send (BLE fragment)
            central_address: Specific central to send to, or None for broadcast

        Returns:
            bool: True if sent successfully, False otherwise
        """
        if not self.running or not self.tx_characteristic:
            self._log("Cannot send notification: server not running", level="WARNING")
            return False

        if not data:
            self._log("Cannot send notification: empty data", level="WARNING")
            return False

        # Check if target central is connected
        if central_address:
            with self._centrals_lock:
                if central_address not in self.connected_centrals:
                    self._log(f"Cannot send notification: central {central_address} not connected", level="WARNING")
                    return False

        try:
            # Convert bytes to list of ints (bluezero format)
            if isinstance(data, bytes):
                value = list(data)
            else:
                value = data

            # Update TX characteristic value
            # bluezero automatically sends notification to subscribed centrals
            self.tx_characteristic.set_value(value)

            # Update statistics
            with self._centrals_lock:
                if central_address and central_address in self.connected_centrals:
                    self.connected_centrals[central_address]["bytes_sent"] += len(data)
                else:
                    # Broadcast: update all centrals
                    for addr in self.connected_centrals:
                        self.connected_centrals[addr]["bytes_sent"] += len(data)

            self._log(
                f"Sent notification: {len(data)} bytes to "
                f"{central_address if central_address else 'all centrals'}",
                level="DEBUG"
            )

            return True

        except Exception as e:
            error_type = type(e).__name__
            self._log(f"Error sending notification: {error_type}: {e}", level="ERROR")
            return False

    # ========== Connection Management ==========

    def is_connected(self, central_address: str) -> bool:
        """
        Check if a central is currently connected

        Args:
            central_address: Address to check

        Returns:
            bool: True if connected, False otherwise
        """
        with self._centrals_lock:
            return central_address in self.connected_centrals

    def get_connected_centrals(self) -> list:
        """
        Get list of currently connected central addresses

        Returns:
            list: List of central addresses
        """
        with self._centrals_lock:
            return list(self.connected_centrals.keys())

    def get_connection_info(self, central_address: str) -> Optional[dict]:
        """
        Get connection information for a specific central

        Args:
            central_address: Address of central

        Returns:
            dict: Connection info or None if not connected
        """
        with self._centrals_lock:
            return self.connected_centrals.get(central_address)

    def get_central_mtu(self, central_address: str) -> int:
        """
        Get negotiated MTU for a connected central

        Args:
            central_address: Address of central

        Returns:
            int: Negotiated MTU size, or 185 (default) if not connected or unknown
        """
        with self._centrals_lock:
            if central_address in self.connected_centrals:
                return self.connected_centrals[central_address].get("mtu", 185)
            return 185  # Default fallback

    def get_statistics(self) -> dict:
        """
        Get server statistics

        Returns:
            dict: Statistics including connected centrals, bytes transferred, etc.
        """
        with self._centrals_lock:
            total_rx = sum(info["bytes_received"] for info in self.connected_centrals.values())
            total_tx = sum(info["bytes_sent"] for info in self.connected_centrals.values())

            return {
                "running": self.running,
                "device_name": self.device_name,
                "connected_centrals": len(self.connected_centrals),
                "total_bytes_received": total_rx,
                "total_bytes_sent": total_tx,
                "centrals": list(self.connected_centrals.values()),
            }

    def __str__(self) -> str:
        """String representation"""
        status = "running" if self.running else "stopped"
        centrals = len(self.connected_centrals)
        return f"BLEGATTServer({self.device_name}, {status}, {centrals} centrals)"

    def __repr__(self) -> str:
        """Detailed representation"""
        return (
            f"BLEGATTServer(device_name='{self.device_name}', "
            f"running={self.running}, "
            f"connected_centrals={len(self.connected_centrals)})"
        )
