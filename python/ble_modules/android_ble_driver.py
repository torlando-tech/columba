#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
AndroidBLEDriver - Platform-specific BLE driver for Android

Implements the BLEDriverInterface from ble-reticulum for Android devices.
Bridges Python to KotlinBLEBridge via Chaquopy for native Android BLE access.

This enables Reticulum BLE networking on Android by providing the same driver
interface as the Linux implementation, but using Android's native BLE stack.

Author: Columba Project
License: MIT
"""

import RNS
import sys
import os
import time
import threading
from typing import List, Optional, Callable

# Add parent interfaces directory to path for imports
# When deployed, bluetooth_driver.py is in the parent interfaces/ directory
_interfaces_dir = os.path.dirname(os.path.dirname(__file__))
if _interfaces_dir not in sys.path:
    sys.path.insert(0, _interfaces_dir)

# Import driver interface from bluetooth_driver (deployed in interfaces/)
from bluetooth_driver import BLEDriverInterface, BLEDevice, DriverState


class AndroidBLEDriver(BLEDriverInterface):
    """
    Android BLE driver implementing BLEDriverInterface from ble-reticulum.

    This driver bridges Python to the KotlinBLEBridge via Chaquopy, providing
    native Android BLE access while implementing the standard driver interface.

    The KotlinBLEBridge handles:
    - BLE scanning and advertising
    - GATT client/server operations
    - Dual-mode operation (central + peripheral)
    - Protocol v2.2 identity tracking
    - MTU negotiation
    - Connection management and error recovery

    Note: Fragmentation/reassembly handled by BLEInterface (parent class)

    This driver simply translates between the BLEDriverInterface and the
    KotlinBLEBridge, handling Chaquopy interop and callback routing.
    """

    def __init__(self, **kwargs):
        """
        Initialize the Android BLE driver.

        Args:
            **kwargs: Configuration parameters (accepted for compatibility with
                     BLEInterface but not used - Android driver gets config via Kotlin)
        """
        super().__init__()

        self._state = DriverState.IDLE
        self.kotlin_bridge = None
        self._transport_identity = None
        self._service_uuid = None
        self._rx_char_uuid = None
        self._tx_char_uuid = None
        self._identity_char_uuid = None

        # Track connected peers
        self._connected_peers = []
        self._peer_roles = {}  # address -> "central" or "peripheral"
        self._peer_mtus = {}  # address -> mtu (int)

        # Thread safety for identity handling (prevents race conditions)
        self._identity_lock = threading.Lock()
        self._pending_identities = {}  # address -> identity bytes (cached before connection)

        # Configuration (from kwargs or defaults)
        self._service_discovery_delay = kwargs.get('service_discovery_delay', 0.5)
        self._power_mode = "balanced"

        RNS.log("AndroidBLEDriver: Initialized", RNS.LOG_DEBUG)

    # --- Lifecycle & Configuration ---

    def start(self, service_uuid: str, rx_char_uuid: str, tx_char_uuid: str, identity_char_uuid: str):
        """Initialize the driver and Android BLE stack."""
        try:
            if self._state != DriverState.IDLE:
                RNS.log(f"AndroidBLEDriver: Cannot start - current state: {self._state}", RNS.LOG_WARNING)
                return

            RNS.log("AndroidBLEDriver: Starting...", RNS.LOG_INFO)

            # Store UUIDs
            self._service_uuid = service_uuid
            self._rx_char_uuid = rx_char_uuid
            self._tx_char_uuid = tx_char_uuid
            self._identity_char_uuid = identity_char_uuid

            # Clear pending identities from previous session
            with self._identity_lock:
                self._pending_identities.clear()

            # Get Kotlin bridge
            if self.kotlin_bridge is None:
                self.kotlin_bridge = self._get_kotlin_bridge()
                if self.kotlin_bridge is None:
                    raise Exception("Failed to get KotlinBLEBridge")

            # Setup callbacks
            self._setup_kotlin_callbacks()

            # Initialize Kotlin BLE stack
            self.kotlin_bridge.startAsync(
                service_uuid, rx_char_uuid, tx_char_uuid, identity_char_uuid
            )

            # Set identity if we have one
            if self._transport_identity:
                self.kotlin_bridge.setIdentity(self._transport_identity)

            self._state = DriverState.IDLE
            RNS.log("AndroidBLEDriver: Started successfully", RNS.LOG_INFO)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to start: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("critical", f"Failed to start driver: {e}", e)
            raise

    def stop(self):
        """Stop all BLE activity and release resources."""
        try:
            if self._state == DriverState.IDLE:
                return

            RNS.log("AndroidBLEDriver: Stopping...", RNS.LOG_INFO)

            if self.kotlin_bridge:
                self.kotlin_bridge.stopAsync()

            self._connected_peers.clear()
            self._peer_roles.clear()
            self._state = DriverState.IDLE

            RNS.log("AndroidBLEDriver: Stopped", RNS.LOG_INFO)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error stopping: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("error", f"Error stopping driver: {e}", e)

    def set_identity(self, identity_bytes: bytes):
        """Set the local transport identity (16 bytes)."""
        if len(identity_bytes) != 16:
            raise ValueError("Identity must be 16 bytes")

        self._transport_identity = identity_bytes

        if self.kotlin_bridge:
            self.kotlin_bridge.setIdentity(identity_bytes)

        hex_identity = identity_bytes.hex()
        RNS.log(f"AndroidBLEDriver: Identity set: {hex_identity}", RNS.LOG_DEBUG)

    # --- State & Properties ---

    @property
    def state(self) -> DriverState:
        """Return current driver state."""
        return self._state

    @property
    def connected_peers(self) -> List[str]:
        """Return list of connected peer addresses."""
        return self._connected_peers.copy()

    # --- Core Actions ---

    def start_scanning(self):
        """Start BLE scanning for nearby Reticulum nodes."""
        try:
            if not self.kotlin_bridge:
                raise Exception("Driver not started")

            self.kotlin_bridge.startScanningAsync()

            self._state = DriverState.SCANNING
            RNS.log("AndroidBLEDriver: Scanning started", RNS.LOG_DEBUG)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to start scanning: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("error", f"Failed to start scanning: {e}", e)

    def stop_scanning(self):
        """Stop BLE scanning."""
        try:
            if self.kotlin_bridge:
                self.kotlin_bridge.stopScanningAsync()

            if self._state == DriverState.SCANNING:
                self._state = DriverState.IDLE

            RNS.log("AndroidBLEDriver: Scanning stopped", RNS.LOG_DEBUG)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error stopping scan: {e}", RNS.LOG_ERROR)

    def start_advertising(self, device_name: str, identity: bytes):
        """Start BLE advertising."""
        try:
            if not self.kotlin_bridge:
                raise Exception("Driver not started")

            self.kotlin_bridge.startAdvertisingAsync(device_name)

            self._state = DriverState.ADVERTISING
            RNS.log(f"AndroidBLEDriver: Advertising started as '{device_name}'", RNS.LOG_DEBUG)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to start advertising: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("error", f"Failed to start advertising: {e}", e)

    def stop_advertising(self):
        """Stop BLE advertising."""
        try:
            if self.kotlin_bridge:
                self.kotlin_bridge.stopAdvertisingAsync()

            if self._state == DriverState.ADVERTISING:
                self._state = DriverState.IDLE

            RNS.log("AndroidBLEDriver: Advertising stopped", RNS.LOG_DEBUG)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error stopping advertising: {e}", RNS.LOG_ERROR)

    def connect(self, address: str):
        """Connect to a peer device (central role)."""
        try:
            if not self.kotlin_bridge:
                raise Exception("Driver not started")

            self.kotlin_bridge.connectAsync(address)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to connect to {address}: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("error", f"Failed to connect to {address}: {e}", e)

    def disconnect(self, address: str):
        """Disconnect from a peer device."""
        try:
            if self.kotlin_bridge:
                # Kotlin bridge launches coroutines internally
                self.kotlin_bridge.disconnectAsync(address)

            RNS.log(f"AndroidBLEDriver: Disconnecting from {address}", RNS.LOG_DEBUG)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error disconnecting from {address}: {e}", RNS.LOG_ERROR)

    def send(self, address: str, data: bytes):
        """Send data to a connected peer (data already fragmented by BLEInterface)."""
        try:
            if not self.kotlin_bridge:
                raise Exception("Driver not started")

            self.kotlin_bridge.sendAsync(address, data)

            RNS.log(f"AndroidBLEDriver: Sent {len(data)} bytes to {address}", RNS.LOG_EXTREME)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to send to {address}: {e}", RNS.LOG_ERROR)
            if self.on_error:
                self.on_error("error", f"Failed to send to {address}: {e}", e)

    # --- GATT Characteristic Operations ---

    def read_characteristic(self, address: str, char_uuid: str) -> bytes:
        """Read a GATT characteristic (not directly supported - identity via callback)."""
        # This method is here for interface compatibility. In this driver,
        # characteristic reads are handled by the Kotlin bridge, and the results
        # (like the peer identity) are passed up via callbacks.
        RNS.log(f"AndroidBLEDriver: read_characteristic is not implemented for direct use. Use callbacks instead.", RNS.LOG_WARNING)
        return b""

    def write_characteristic(self, address: str, char_uuid: str, data: bytes):
        """Write a GATT characteristic (use send() instead)."""
        # This method is here for interface compatibility. In this driver,
        # all data transmission is handled by the send() method, which abstracts
        # away the specific characteristic writes.
        RNS.log(f"AndroidBLEDriver: write_characteristic is not implemented for direct use. Use send() instead.", RNS.LOG_WARNING)

    def start_notify(self, address: str, char_uuid: str, callback: Callable[[bytes], None]):
        """Subscribe to notifications (handled automatically by Kotlin bridge)."""
        # Note: Notifications are automatically handled by KotlinBLEBridge
        # Data comes through on_data_received callback
        RNS.log(f"AndroidBLEDriver: start_notify not needed (automatic in bridge)", RNS.LOG_DEBUG)

    # --- Configuration & Queries ---

    def get_local_address(self) -> str:
        """Get the local Bluetooth adapter address."""
        # WARNING: Android privacy features prevent apps from accessing the real
        # Bluetooth MAC address. Returning a placeholder. This could have
        # unforeseen consequences in the ble-reticulum logic, which might rely
        # on a real address for tie-breaking or other functions.
        return "00:00:00:00:00:00"  # Placeholder

    def get_peer_role(self, address: str) -> Optional[str]:
        """Get the connection role for a peer ('central' or 'peripheral')."""
        return self._peer_roles.get(address)

    def set_service_discovery_delay(self, seconds: float):
        """Set delay between connection and service discovery (not needed on Android)."""
        self._service_discovery_delay = seconds
        RNS.log(f"AndroidBLEDriver: Service discovery delay set to {seconds}s (ignored on Android)", RNS.LOG_DEBUG)

    def set_power_mode(self, mode: str):
        """Set power mode for scanning ('aggressive', 'balanced', 'saver')."""
        if mode not in ["aggressive", "balanced", "saver"]:
            raise ValueError(f"Invalid power mode: {mode}")

        self._power_mode = mode
        RNS.log(f"AndroidBLEDriver: Power mode set to {mode}", RNS.LOG_DEBUG)
        # Note: Could propagate to KotlinBLEBridge if needed

    def get_peer_mtu(self, address: str) -> Optional[int]:
        """Get the negotiated MTU for a peer."""
        return self._peer_mtus.get(address)

    # --- Internal Methods ---

    def _get_kotlin_bridge(self):
        """Get KotlinBLEBridge instance from global wrapper."""
        try:
            # Import the global wrapper instance
            import reticulum_wrapper
            wrapper = reticulum_wrapper._global_wrapper_instance

            if wrapper is None:
                RNS.log("AndroidBLEDriver: No global wrapper instance found", RNS.LOG_ERROR)
                return None

            if wrapper.kotlin_ble_bridge is None:
                RNS.log("AndroidBLEDriver: No BLE bridge set in wrapper. Call set_ble_bridge() from Kotlin first.", RNS.LOG_ERROR)
                return None

            RNS.log("AndroidBLEDriver: Kotlin bridge acquired from wrapper", RNS.LOG_DEBUG)
            return wrapper.kotlin_ble_bridge

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Failed to get Kotlin bridge: {e}", RNS.LOG_ERROR)
            import traceback
            RNS.log(traceback.format_exc(), RNS.LOG_ERROR)
            return None

    def _setup_kotlin_callbacks(self):
        """Setup callbacks from Kotlin to Python."""
        if not self.kotlin_bridge:
            return

        self.kotlin_bridge.setOnDeviceDiscovered(lambda address, name, rssi, service_uuids: self._handle_device_discovered(address, name, rssi, service_uuids))
        # Accept 4 params: address, mtu, role, identity_hash (identity_hash may be None for Protocol v1 or peripheral)
        self.kotlin_bridge.setOnConnected(lambda address, mtu, role, identity_hash=None: self._handle_connected(address, mtu, role, identity_hash))
        self.kotlin_bridge.setOnDisconnected(lambda address: self._handle_disconnected(address))
        self.kotlin_bridge.setOnDataReceived(lambda address, data: self._handle_data_received(address, data))
        self.kotlin_bridge.setOnIdentityReceived(lambda address, identity_hash: self._handle_identity_received(address, identity_hash))
        self.kotlin_bridge.setOnMtuNegotiated(lambda address, mtu: self._handle_mtu_negotiated(address, mtu))
        self.kotlin_bridge.setOnAddressChanged(lambda old_addr, new_addr, identity_hash: self._handle_address_changed(old_addr, new_addr, identity_hash))

        RNS.log("AndroidBLEDriver: Kotlin callbacks configured", RNS.LOG_DEBUG)

    def _handle_device_discovered(self, address: str, name: Optional[str], rssi: int, service_uuids: Optional[List[str]]):
        """Handle device discovered event from Kotlin."""
        try:
            # service_uuids arrives as proper Python list (converted from Kotlin Array)
            device = BLEDevice(
                address=address,
                name=name or "Unknown",
                rssi=rssi,
                service_uuids=service_uuids or []
            )

            if self.on_device_discovered:
                self.on_device_discovered(device)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling device discovered: {e}", RNS.LOG_ERROR)

    def _handle_connected(self, address: str, mtu: int, role: str = "central", identity_hash: Optional[str] = None):
        """Handle peer connected event from Kotlin.

        Args:
            address: Peer MAC address
            mtu: Negotiated MTU
            role: Connection role ("central" or "peripheral")
            identity_hash: 32-char hex identity string (Protocol v2.2), or None for Protocol v1/peripheral
        """
        try:
            # DEBUG: Log callback entry for tracking callback chain
            RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_connected ENTRY: address={address}, mtu={mtu}, role={role}, identity_hash={identity_hash[:16] if identity_hash else 'None'}...", RNS.LOG_WARNING)

            if address not in self._connected_peers:
                self._connected_peers.append(address)

            # Set role from parameter (passed from Kotlin)
            self._peer_roles[address] = role

            RNS.log(f"AndroidBLEDriver: Connected to {address} (MTU={mtu}, role={role}, identity={'present' if identity_hash else 'pending'})", RNS.LOG_INFO)

            # Use identity from callback (passed directly to avoid race condition with onIdentityReceived)
            # Fall back to _pending_identities for backwards compatibility
            identity = None
            if identity_hash:
                try:
                    identity = bytes.fromhex(identity_hash)
                    RNS.log(f"AndroidBLEDriver: Using identity from onConnected callback: {identity_hash[:16]}...", RNS.LOG_DEBUG)
                except ValueError as e:
                    RNS.log(f"AndroidBLEDriver: Invalid identity_hash format: {e}", RNS.LOG_WARNING)

            if identity is None:
                # Fall back to pending identities (from earlier onIdentityReceived if it arrived first)
                # Use lock to prevent race condition with _handle_identity_received
                with self._identity_lock:
                    identity = self._pending_identities.pop(address, None)
                if identity:
                    RNS.log(f"AndroidBLEDriver: Using identity from pending cache", RNS.LOG_DEBUG)

            # Notify BLEInterface of connection (regardless of role)
            # Dual-connection prevention is handled by KotlinBLEBridge (lines 954-959)
            # If this callback fires, there's only ONE connection type (central XOR peripheral)
            if self.on_device_connected:
                # Call on_device_connected with identity if available (Protocol v2.2)
                # or None if no identity (Protocol v1 device, or peripheral pending handshake)
                self.on_device_connected(address, identity)
                if identity:
                    RNS.log(f"AndroidBLEDriver: Notified {role} connection with identity for {address}", RNS.LOG_DEBUG)
                else:
                    RNS.log(f"AndroidBLEDriver: Notified {role} connection without identity for {address} (will receive via handshake)", RNS.LOG_DEBUG)

            # Report MTU negotiation
            if self.on_mtu_negotiated:
                self.on_mtu_negotiated(address, mtu)

            # DEBUG: Log callback completion
            RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_connected EXIT: address={address}, called on_device_connected={self.on_device_connected is not None}, called on_mtu_negotiated={self.on_mtu_negotiated is not None}", RNS.LOG_WARNING)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling connected: {e}", RNS.LOG_ERROR)

    def _handle_disconnected(self, address: str):
        """Handle peer disconnected event from Kotlin."""
        try:
            if address in self._connected_peers:
                self._connected_peers.remove(address)
            if address in self._peer_roles:
                del self._peer_roles[address]
            if address in self._peer_mtus:
                del self._peer_mtus[address]

            RNS.log(f"AndroidBLEDriver: Disconnected from {address}", RNS.LOG_INFO)

            if self.on_device_disconnected:
                self.on_device_disconnected(address)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling disconnected: {e}", RNS.LOG_ERROR)

    def _handle_address_changed(self, old_address: str, new_address: str, identity_hash: str):
        """Handle address change event from Kotlin during dual connection deduplication.

        When Kotlin deduplicates a dual connection (same identity connected as both
        central and peripheral), it closes one direction and notifies Python via
        this callback so Python can update its address mappings.

        Args:
            old_address: The address that was closed/removed
            new_address: The address that remains active
            identity_hash: The 32-char hex identity hash for this peer
        """
        try:
            RNS.log(
                f"AndroidBLEDriver: Address changed for {identity_hash[:8]}: {old_address} -> {new_address}",
                RNS.LOG_INFO
            )

            if self.on_address_changed:
                self.on_address_changed(old_address, new_address, identity_hash)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling address changed: {e}", RNS.LOG_ERROR)

    def _handle_data_received(self, address: str, data: bytes):
        """Handle data received event from Kotlin (already defragmented).

        Note: Due to Android MAC randomization, data may arrive from an address
        that has a pending identity but never received onConnected. This happens
        when the same peer identity is seen from different MACs. In this case,
        we finalize the connection using the pending identity before passing data.
        """
        try:
            RNS.log(f"AndroidBLEDriver: Received {len(data)} bytes from {address}", RNS.LOG_EXTREME)

            # Check if this address has a pending identity but never got onConnected
            # This handles the case where Android MAC randomization causes identity
            # to arrive from one MAC but onConnected for a different MAC with same identity
            if address not in self._connected_peers:
                with self._identity_lock:
                    pending_identity = self._pending_identities.get(address)
                    if pending_identity:
                        # Finalize connection with pending identity
                        RNS.log(f"AndroidBLEDriver: Finalizing connection for {address} from pending identity (data arrived first)", RNS.LOG_DEBUG)
                        self._connected_peers.append(address)
                        self._peer_roles[address] = "peripheral"  # Data arriving = peripheral role
                        # Remove from pending before callback to prevent double-use
                        del self._pending_identities[address]

                        # Call on_device_connected to create identity mappings
                        if self.on_device_connected:
                            self.on_device_connected(address, pending_identity)

                        # IMPORTANT: Also call on_mtu_negotiated to create reassembler
                        # Without this, BLEInterface has identity but no reassembler
                        mtu = self._peer_mtus.get(address, 23)  # Default to BLE 4.0 minimum
                        if self.on_mtu_negotiated:
                            self.on_mtu_negotiated(address, mtu)

            if self.on_data_received:
                self.on_data_received(address, data)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling data received: {e}", RNS.LOG_ERROR)

    def _handle_identity_received(self, address: str, identity_hash: str):
        """Handle identity received event from Kotlin (Protocol v2.2).

        This is called when the central's identity is received via the GATT identity
        characteristic (for peripheral mode). For central mode, identity is passed
        directly in the onConnected callback, so this should not be called.

        IMPORTANT: For peripheral connections, this callback may arrive AFTER
        onConnected has already fired. In that case, we must notify BLEInterface
        of the identity so it can spawn the peer interface.

        Thread safety: Uses _identity_lock to prevent race conditions with
        _handle_connected when identity and connection callbacks interleave.
        """
        try:
            # DEBUG: Log callback entry
            RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_identity_received ENTRY: address={address}, identity={identity_hash[:16]}..., already_connected={address in self._connected_peers}", RNS.LOG_WARNING)

            RNS.log(f"AndroidBLEDriver: Identity received from {address}: {identity_hash[:16]}...",
                   RNS.LOG_INFO)

            # Convert hex string to bytes (16 bytes = 32 hex chars)
            identity_bytes = bytes.fromhex(identity_hash)

            # Use lock to prevent race condition with _handle_connected
            with self._identity_lock:
                # Check if peer is already connected (common case for peripheral mode)
                # where onConnected fires before identity is received
                if address in self._connected_peers:
                    # Peer is already connected - notify BLEInterface immediately
                    # This allows BLEInterface to spawn the peer interface with identity
                    RNS.log(f"AndroidBLEDriver: Late identity for connected peer {address}, notifying BLEInterface", RNS.LOG_DEBUG)
                    if self.on_device_connected:
                        self.on_device_connected(address, identity_bytes)
                        RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_identity_received: Called on_device_connected for late identity at {address}", RNS.LOG_WARNING)
                else:
                    # Peer not yet connected - cache identity for when onConnected fires
                    # This handles the race where identity arrives before connection
                    self._pending_identities[address] = identity_bytes
                    RNS.log(f"AndroidBLEDriver: Cached identity for {address}, waiting for connection complete", RNS.LOG_DEBUG)
                    RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_identity_received: Cached identity (peer not connected yet) for {address}", RNS.LOG_WARNING)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling identity received: {e}", RNS.LOG_ERROR)

    def _handle_mtu_negotiated(self, address: str, mtu: int):
        """Handle MTU negotiation completion from Kotlin."""
        try:
            # DEBUG: Log callback entry
            RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_mtu_negotiated ENTRY: address={address}, mtu={mtu}", RNS.LOG_WARNING)

            RNS.log(f"AndroidBLEDriver: MTU negotiated with {address}: {mtu}", RNS.LOG_INFO)

            # Store MTU for this peer
            self._peer_mtus[address] = mtu

            if self.on_mtu_negotiated:
                self.on_mtu_negotiated(address, mtu)
                RNS.log(f"AndroidBLEDriver: [CALLBACK] _handle_mtu_negotiated: Called on_mtu_negotiated for {address}", RNS.LOG_WARNING)

        except Exception as e:
            RNS.log(f"AndroidBLEDriver: Error handling MTU negotiated: {e}", RNS.LOG_ERROR)
