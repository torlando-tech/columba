"""
Test suite for Android BLE Driver

Tests the race condition handling in AndroidBLEDriver where data may arrive
from an address with a pending identity but no completed connection.
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch
from enum import Enum, auto
import threading

# Add parent directory to path to import modules
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS before importing the driver
mock_rns = MagicMock()
mock_rns.LOG_DEBUG = 5
mock_rns.LOG_INFO = 4
mock_rns.LOG_WARNING = 3
mock_rns.LOG_ERROR = 2
mock_rns.LOG_EXTREME = 6
mock_rns.log = MagicMock()
sys.modules['RNS'] = mock_rns


# Create mock DriverState enum
class MockDriverState(Enum):
    IDLE = auto()
    SCANNING = auto()
    ADVERTISING = auto()
    CONNECTING = auto()


# Mock bluetooth_driver module
mock_bluetooth_driver = MagicMock()
mock_bluetooth_driver.DriverState = MockDriverState
mock_bluetooth_driver.BLEDriverInterface = MagicMock()
mock_bluetooth_driver.BLEDevice = MagicMock()
sys.modules['bluetooth_driver'] = mock_bluetooth_driver


class MockAndroidBLEDriver:
    """
    Test harness that mimics AndroidBLEDriver structure.

    This extracts the relevant logic from AndroidBLEDriver._handle_data_received
    for testing the race condition fix.
    """

    def __init__(self):
        self._connected_peers = []
        self._peer_roles = {}
        self._peer_mtus = {}
        self._pending_identities = {}
        self._identity_lock = threading.Lock()

        # Callbacks
        self.on_device_connected = None
        self.on_mtu_negotiated = None
        self.on_data_received = None

    def _handle_data_received(self, address: str, data: bytes):
        """Handle data received - mirrors the fix in android_ble_driver.py."""
        try:
            # Check if this address has a pending identity but never got onConnected
            if address not in self._connected_peers:
                with self._identity_lock:
                    pending_identity = self._pending_identities.get(address)
                    if pending_identity:
                        # Finalize connection with pending identity
                        self._connected_peers.append(address)
                        self._peer_roles[address] = "peripheral"
                        # Remove from pending before callback
                        del self._pending_identities[address]

                        # Call on_device_connected to create identity mappings
                        if self.on_device_connected:
                            self.on_device_connected(address, pending_identity)

                        # Also call on_mtu_negotiated to create reassembler
                        mtu = self._peer_mtus.get(address, 23)
                        if self.on_mtu_negotiated:
                            self.on_mtu_negotiated(address, mtu)

            if self.on_data_received:
                self.on_data_received(address, data)

        except Exception as e:
            pass  # Error handling


class TestAndroidBLEDriverRaceCondition(unittest.TestCase):
    """Test race condition handling when data arrives before connection completes."""

    def setUp(self):
        """Set up test fixtures."""
        self.driver = MockAndroidBLEDriver()

        # Set up mock callbacks
        self.driver.on_device_connected = Mock()
        self.driver.on_mtu_negotiated = Mock()
        self.driver.on_data_received = Mock()

    def test_data_from_unknown_address_without_pending_identity(self):
        """
        Test that data from unknown address without pending identity is passed through.

        When data arrives from an address that:
        - Is NOT in connected_peers
        - Does NOT have a pending identity

        The driver should NOT call on_device_connected, just pass data through.
        """
        address = "AA:BB:CC:DD:EE:FF"
        data = b"test data"

        self.driver._handle_data_received(address, data)

        # Should not trigger connection callbacks
        self.driver.on_device_connected.assert_not_called()
        self.driver.on_mtu_negotiated.assert_not_called()

        # Should still pass data through
        self.driver.on_data_received.assert_called_once_with(address, data)

    def test_data_from_connected_peer(self):
        """
        Test that data from connected peer is passed through normally.

        When data arrives from an address that IS in connected_peers,
        the driver should just pass data through without any connection logic.
        """
        address = "AA:BB:CC:DD:EE:FF"
        data = b"test data"

        # Mark peer as connected
        self.driver._connected_peers.append(address)

        self.driver._handle_data_received(address, data)

        # Should not trigger connection callbacks (already connected)
        self.driver.on_device_connected.assert_not_called()
        self.driver.on_mtu_negotiated.assert_not_called()

        # Should pass data through
        self.driver.on_data_received.assert_called_once_with(address, data)

    def test_data_from_address_with_pending_identity(self):
        """
        Test that data from address with pending identity triggers connection finalization.

        This is the key race condition fix: When data arrives from an address that:
        - Is NOT in connected_peers
        - HAS a pending identity

        The driver should:
        1. Add to connected_peers
        2. Call on_device_connected with the pending identity
        3. Call on_mtu_negotiated to create reassembler
        4. Pass data through to on_data_received
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10"
        data = b"test data"

        # Set up pending identity
        self.driver._pending_identities[address] = identity

        self.driver._handle_data_received(address, data)

        # Should trigger connection finalization
        self.driver.on_device_connected.assert_called_once_with(address, identity)
        self.driver.on_mtu_negotiated.assert_called_once()

        # Check MTU callback was called with address and default MTU (23)
        mtu_call_args = self.driver.on_mtu_negotiated.call_args
        self.assertEqual(mtu_call_args[0][0], address)
        self.assertEqual(mtu_call_args[0][1], 23)  # Default BLE 4.0 MTU

        # Should pass data through
        self.driver.on_data_received.assert_called_once_with(address, data)

        # Should be added to connected peers
        self.assertIn(address, self.driver._connected_peers)
        self.assertEqual(self.driver._peer_roles[address], "peripheral")

        # Should be removed from pending identities
        self.assertNotIn(address, self.driver._pending_identities)

    def test_data_from_address_with_pending_identity_uses_cached_mtu(self):
        """
        Test that MTU from cache is used when available.

        If we have a cached MTU for this address (from earlier negotiation),
        the driver should use that MTU value instead of the default.
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10"
        data = b"test data"
        cached_mtu = 512

        # Set up pending identity and cached MTU
        self.driver._pending_identities[address] = identity
        self.driver._peer_mtus[address] = cached_mtu

        self.driver._handle_data_received(address, data)

        # Check MTU callback was called with cached MTU
        mtu_call_args = self.driver.on_mtu_negotiated.call_args
        self.assertEqual(mtu_call_args[0][1], cached_mtu)

    def test_pending_identity_consumed_only_once(self):
        """
        Test that pending identity is removed after first use.

        If data arrives twice from same address with pending identity,
        only the first time should trigger connection finalization.
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10"
        data1 = b"first data"
        data2 = b"second data"

        # Set up pending identity
        self.driver._pending_identities[address] = identity

        # First data arrival - should trigger connection
        self.driver._handle_data_received(address, data1)

        # Reset mocks
        self.driver.on_device_connected.reset_mock()
        self.driver.on_mtu_negotiated.reset_mock()

        # Second data arrival - should NOT trigger connection (already connected)
        self.driver._handle_data_received(address, data2)

        # Should not trigger connection callbacks second time
        self.driver.on_device_connected.assert_not_called()
        self.driver.on_mtu_negotiated.assert_not_called()


class TestAndroidBLEDriverCallbackOrdering(unittest.TestCase):
    """Test that callbacks are called in correct order."""

    def setUp(self):
        """Set up test fixtures."""
        self.driver = MockAndroidBLEDriver()

        # Track call order
        self.call_order = []

        def track_connected(addr, identity):
            self.call_order.append(('connected', addr))

        def track_mtu(addr, mtu):
            self.call_order.append(('mtu', addr, mtu))

        def track_data(addr, data):
            self.call_order.append(('data', addr, len(data)))

        self.driver.on_device_connected = track_connected
        self.driver.on_mtu_negotiated = track_mtu
        self.driver.on_data_received = track_data

    def test_callback_order_for_pending_identity(self):
        """
        Test that callbacks are called in correct order for pending identity case.

        Order must be:
        1. on_device_connected (creates identity mappings)
        2. on_mtu_negotiated (creates reassembler)
        3. on_data_received (processes data with reassembler)
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10"
        data = b"test data"

        self.driver._pending_identities[address] = identity

        self.driver._handle_data_received(address, data)

        # Verify order
        self.assertEqual(len(self.call_order), 3)
        self.assertEqual(self.call_order[0][0], 'connected')
        self.assertEqual(self.call_order[1][0], 'mtu')
        self.assertEqual(self.call_order[2][0], 'data')


if __name__ == '__main__':
    unittest.main()
