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


class MockBLEInterface:
    """
    Test harness that mimics BLEInterface structure for testing address change callbacks.

    This tests the fix for dual connection deduplication where Python wasn't notified
    when Kotlin changed the address mapping during deduplication.
    """

    def __init__(self):
        self.identity_to_address = {}
        self.address_to_identity = {}
        self.fragmenters = {}
        self.reassemblers = {}
        self.frag_lock = threading.Lock()

    def _get_fragmenter_key(self, peer_identity, address):
        """Generate key for fragmenter/reassembler lookup."""
        if peer_identity:
            return f"{peer_identity.hex()[:8]}_{address}"
        return address

    def _address_changed_callback(self, old_address: str, new_address: str, identity_hash: str):
        """
        Handle address change during dual connection deduplication.

        When Kotlin deduplicates a dual connection (same identity connected as both
        central and peripheral), it closes one direction and notifies Python via
        this callback so Python can update its address mappings.

        Args:
            old_address: The address that was closed/removed
            new_address: The address that remains active
            identity_hash: The 32-char hex identity hash for this peer
        """
        # Update identity_to_address mapping
        if identity_hash in self.identity_to_address:
            self.identity_to_address[identity_hash] = new_address

        # Update address_to_identity mapping
        peer_identity = self.address_to_identity.get(old_address)
        if peer_identity:
            self.address_to_identity[new_address] = peer_identity
            # Keep old mapping for fallback resolution during transition

        # Update fragmenter/reassembler keys
        if peer_identity:
            old_key = self._get_fragmenter_key(peer_identity, old_address)
            new_key = self._get_fragmenter_key(peer_identity, new_address)
            with self.frag_lock:
                if old_key in self.fragmenters:
                    self.fragmenters[new_key] = self.fragmenters.pop(old_key)
                if old_key in self.reassemblers:
                    self.reassemblers[new_key] = self.reassemblers.pop(old_key)


class TestBLEInterfaceAddressChangedCallback(unittest.TestCase):
    """
    Test _address_changed_callback which handles address changes during
    dual connection deduplication.

    When Kotlin deduplicates dual connections (same identity connected as both
    central and peripheral), it closes one direction and notifies Python via
    this callback so Python can update its address mappings.
    """

    def setUp(self):
        """Set up test fixtures."""
        self.interface = MockBLEInterface()

    def test_address_changed_updates_identity_to_address_mapping(self):
        """
        Test that _address_changed_callback updates identity_to_address mapping.

        When address changes from old to new, the identity_to_address map
        should point to the new address.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "AA:BB:CC:DD:EE:FF"
        identity_hash = "ab5609dfffb33b21a102e1ff81196be5"
        peer_identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # Set up initial state
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[old_address] = peer_identity

        # Call the callback (this should fail - method doesn't exist yet)
        self.interface._address_changed_callback(old_address, new_address, identity_hash)

        # Verify mapping updated
        self.assertEqual(self.interface.identity_to_address[identity_hash], new_address)

    def test_address_changed_updates_address_to_identity_mapping(self):
        """
        Test that _address_changed_callback updates address_to_identity mapping.

        The new address should map to the same peer identity.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "AA:BB:CC:DD:EE:FF"
        identity_hash = "ab5609dfffb33b21a102e1ff81196be5"
        peer_identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # Set up initial state
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[old_address] = peer_identity

        # Call the callback
        self.interface._address_changed_callback(old_address, new_address, identity_hash)

        # Verify new address maps to peer identity
        self.assertEqual(self.interface.address_to_identity[new_address], peer_identity)

    def test_address_changed_updates_fragmenter_keys(self):
        """
        Test that _address_changed_callback updates fragmenter keys.

        Fragmenters are keyed by (identity_hash, address). When address changes,
        the fragmenter should be accessible via the new key.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "AA:BB:CC:DD:EE:FF"
        identity_hash = "ab5609dfffb33b21a102e1ff81196be5"
        peer_identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        mock_fragmenter = Mock()

        # Set up initial state
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[old_address] = peer_identity
        old_key = self.interface._get_fragmenter_key(peer_identity, old_address)
        self.interface.fragmenters[old_key] = mock_fragmenter

        # Call the callback
        self.interface._address_changed_callback(old_address, new_address, identity_hash)

        # Verify fragmenter is accessible via new key
        new_key = self.interface._get_fragmenter_key(peer_identity, new_address)
        self.assertIn(new_key, self.interface.fragmenters)
        self.assertEqual(self.interface.fragmenters[new_key], mock_fragmenter)

    def test_address_changed_updates_reassembler_keys(self):
        """
        Test that _address_changed_callback updates reassembler keys.

        Reassemblers are keyed by (identity_hash, address). When address changes,
        the reassembler should be accessible via the new key.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "AA:BB:CC:DD:EE:FF"
        identity_hash = "ab5609dfffb33b21a102e1ff81196be5"
        peer_identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        mock_reassembler = Mock()

        # Set up initial state
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[old_address] = peer_identity
        old_key = self.interface._get_fragmenter_key(peer_identity, old_address)
        self.interface.reassemblers[old_key] = mock_reassembler

        # Call the callback
        self.interface._address_changed_callback(old_address, new_address, identity_hash)

        # Verify reassembler is accessible via new key
        new_key = self.interface._get_fragmenter_key(peer_identity, new_address)
        self.assertIn(new_key, self.interface.reassemblers)
        self.assertEqual(self.interface.reassemblers[new_key], mock_reassembler)


class MockDiscoveredPeer:
    """Mock discovered peer for testing."""
    def __init__(self, address: str, name: str = "RNS-test", rssi: int = -50):
        self.address = address
        self.name = name
        self.rssi = rssi


class MockBLEInterfaceMACRotation:
    """
    Test harness for MAC rotation handling in _select_peers_to_connect.

    Tests the fix where after MAC rotation cleanup, peer is immediately added
    to scored_peers list bypassing MAC sorting.
    """

    def __init__(self):
        self.identity_to_address = {}
        self.address_to_identity = {}
        self.spawned_interfaces = {}
        self.peers = {}  # address -> active connection
        self.connection_attempt_times = {}
        self.local_mac = "AA:BB:CC:DD:EE:FF"  # Higher MAC for sorting test
        self.cleanup_called_with = []  # Track cleanup calls

    def _compute_identity_hash(self, peer_identity):
        """Compute identity hash."""
        if peer_identity:
            return peer_identity.hex()[:16]
        return None

    def _score_peer(self, peer):
        """Score a peer for connection prioritization."""
        return abs(peer.rssi)  # Lower RSSI = higher priority

    def _cleanup_stale_interface(self, identity_hash: str, old_address: str):
        """Mock cleanup - just track that it was called."""
        self.cleanup_called_with.append((identity_hash, old_address))
        # Clean up mappings like real method
        if identity_hash in self.spawned_interfaces:
            del self.spawned_interfaces[identity_hash]
        if identity_hash in self.identity_to_address:
            del self.identity_to_address[identity_hash]

    def _select_peers_to_connect(self, discovered_peers):
        """
        Simplified version of _select_peers_to_connect that includes the MAC rotation fix.

        Returns list of (score, peer) tuples.
        """
        scored_peers = []

        for peer in discovered_peers:
            address = peer.address

            # Check for MAC rotation
            peer_identity = self.address_to_identity.get(address)
            if peer_identity:
                identity_hash = self._compute_identity_hash(peer_identity)
                if identity_hash in self.spawned_interfaces:
                    existing_address = self.identity_to_address.get(identity_hash)
                    if existing_address and existing_address != address:
                        # Same identity at different MAC = MAC rotation
                        if existing_address in self.peers:
                            # Old connection still active - skip
                            continue
                        else:
                            # Old connection dead - clean up and allow new connection
                            self._cleanup_stale_interface(identity_hash, existing_address)
                            # FIX: Bypass MAC sorting - we must reconnect after MAC rotation
                            score = self._score_peer(peer)
                            scored_peers.append((score, peer))
                            continue  # Skip remaining checks, peer already added

            # MAC sorting check (only reached if NOT MAC rotation)
            if self.local_mac > address:
                # We have higher MAC, we should initiate - add peer
                score = self._score_peer(peer)
                scored_peers.append((score, peer))
            # else: peer has higher MAC, they should initiate - skip

        return scored_peers


class TestMACRotationFix(unittest.TestCase):
    """
    Test MAC rotation handling in _select_peers_to_connect.

    Bug: After MAC rotation, peer interface wasn't recreated because MAC sorting
    check skipped the peer. Fix: After cleanup, immediately add peer and continue.
    """

    def setUp(self):
        """Set up test fixtures."""
        self.interface = MockBLEInterfaceMACRotation()

    def test_mac_rotation_detected_and_cleanup_called(self):
        """
        Test that MAC rotation is detected and cleanup is called.

        When same identity appears at new MAC with stale old connection,
        _cleanup_stale_interface should be called.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "77:88:99:AA:BB:CC"
        identity_hash = "ab5609dfffb33b21"
        peer_identity = bytes.fromhex("ab5609dfffb33b21a102e1ff81196be5")

        # Set up: identity exists at old address, but connection is stale (not in self.peers)
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[new_address] = peer_identity
        self.interface.spawned_interfaces[identity_hash] = Mock()
        # Note: old_address NOT in self.peers (connection is dead)

        # Create peer at new address
        peer = MockDiscoveredPeer(new_address, "RNS-ab5609")

        # Act
        self.interface._select_peers_to_connect([peer])

        # Assert: cleanup was called with correct arguments
        self.assertEqual(len(self.interface.cleanup_called_with), 1)
        self.assertEqual(self.interface.cleanup_called_with[0], (identity_hash, old_address))

    def test_mac_rotation_bypasses_mac_sorting(self):
        """
        Test that MAC rotation bypasses MAC sorting.

        After MAC rotation cleanup, peer should be added to connection list
        EVEN IF local MAC > peer MAC (which would normally skip the peer).
        """
        old_address = "11:22:33:44:55:66"
        new_address = "00:11:22:33:44:55"  # Lower than local MAC (AA:BB:CC...)
        identity_hash = "ab5609dfffb33b21"
        peer_identity = bytes.fromhex("ab5609dfffb33b21a102e1ff81196be5")

        # Set up: identity exists at old address, but connection is stale
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[new_address] = peer_identity
        self.interface.spawned_interfaces[identity_hash] = Mock()
        # old_address NOT in self.peers (stale connection)

        # Create peer at new address (lower MAC than local)
        peer = MockDiscoveredPeer(new_address, "RNS-ab5609")

        # Act
        result = self.interface._select_peers_to_connect([peer])

        # Assert: peer was added despite having lower MAC
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0][1].address, new_address)

    def test_mac_sorting_applies_when_no_mac_rotation(self):
        """
        Test that normal MAC sorting still works when there's no MAC rotation.

        When peer MAC > local MAC and no rotation, peer should be skipped
        (peer should initiate connection since they have higher MAC).
        """
        new_address = "FF:FF:FF:FF:FF:FF"  # Higher than local MAC (AA:BB:CC...)

        # No existing identity mapping - this is a new peer
        peer = MockDiscoveredPeer(new_address, "RNS-newpeer")

        # Act
        result = self.interface._select_peers_to_connect([peer])

        # Assert: peer was NOT added (they have higher MAC, they should initiate)
        self.assertEqual(len(result), 0)

    def test_mac_sorting_adds_peer_when_local_mac_higher(self):
        """
        Test that MAC sorting adds peer when local MAC is higher.

        When local MAC > peer MAC and no rotation, we should initiate.
        """
        new_address = "00:11:22:33:44:55"  # Lower than local MAC (AA:BB:CC...)

        # Set local MAC higher
        self.interface.local_mac = "FF:FF:FF:FF:FF:FF"

        peer = MockDiscoveredPeer(new_address, "RNS-newpeer")

        # Act
        result = self.interface._select_peers_to_connect([peer])

        # Assert: peer was added (we have higher MAC, we initiate)
        self.assertEqual(len(result), 1)

    def test_active_connection_skips_rotation(self):
        """
        Test that active connection prevents MAC rotation cleanup.

        When old connection is still active (in self.peers), we should
        NOT clean up or add the new address.
        """
        old_address = "11:22:33:44:55:66"
        new_address = "77:88:99:AA:BB:CC"
        identity_hash = "ab5609dfffb33b21"
        peer_identity = bytes.fromhex("ab5609dfffb33b21a102e1ff81196be5")

        # Set up: identity exists at old address AND connection is active
        self.interface.identity_to_address[identity_hash] = old_address
        self.interface.address_to_identity[new_address] = peer_identity
        self.interface.spawned_interfaces[identity_hash] = Mock()
        self.interface.peers[old_address] = Mock()  # Connection is ACTIVE

        peer = MockDiscoveredPeer(new_address, "RNS-ab5609")

        # Act
        result = self.interface._select_peers_to_connect([peer])

        # Assert: cleanup was NOT called, peer was NOT added
        self.assertEqual(len(self.interface.cleanup_called_with), 0)
        self.assertEqual(len(result), 0)


class TestEnsureAdvertisingRealClass(unittest.TestCase):
    """
    Test ensure_advertising() method on the REAL AndroidBLEDriver class.

    This tests the actual code in android_ble_driver.py to get coverage.
    Android may silently stop BLE advertising when:
    - App goes to background
    - Screen turns off
    - Device enters Doze mode

    This method checks and restarts advertising if needed.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class with proper mock setup."""
        # Remove existing bluetooth_driver mock to replace with proper class mock
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        # Create proper base class (not MagicMock)
        class MockBLEDriverInterface:
            """Mock base class for AndroidBLEDriver."""
            pass

        # Set up bluetooth_driver with proper class
        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        # Add ble_modules to path
        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        # Import the real class and module
        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures with a real driver instance."""
        # Create driver instance without calling __init__
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        # Set up log capture in the module's namespace
        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_ensure_advertising_no_bridge_returns_false(self):
        """
        Test that ensure_advertising returns False when no bridge.

        Without a Kotlin bridge, we cannot check or restart advertising.
        """
        self.driver.kotlin_bridge = None

        result = self.driver.ensure_advertising()

        self.assertFalse(result)
        self.assertTrue(len(self.log_calls) > 0, "Expected log calls")
        self.assertTrue(any("no bridge" in str(msg).lower() for msg, _ in self.log_calls))

    def test_ensure_advertising_already_active_returns_true(self):
        """
        Test that ensure_advertising returns True when advertising is active.

        When Kotlin reports advertising is active, return True without restart.
        """
        mock_bridge = Mock()
        mock_bridge.ensureAdvertising.return_value = True
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.ensure_advertising()

        self.assertTrue(result)
        mock_bridge.ensureAdvertising.assert_called_once()

    def test_ensure_advertising_was_stopped_returns_false_and_logs(self):
        """
        Test that ensure_advertising returns False and logs when restart triggered.

        When Kotlin reports advertising was stopped and restart was triggered,
        return False and log an info message.
        """
        mock_bridge = Mock()
        mock_bridge.ensureAdvertising.return_value = False
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.ensure_advertising()

        self.assertFalse(result)
        mock_bridge.ensureAdvertising.assert_called_once()
        self.assertTrue(len(self.log_calls) > 0, "Expected log calls")
        self.assertTrue(any("restarting" in str(msg).lower() for msg, _ in self.log_calls))

    def test_ensure_advertising_exception_returns_false(self):
        """
        Test that ensure_advertising returns False on exception.

        If Kotlin bridge throws an exception, catch it, log error, return False.
        """
        mock_bridge = Mock()
        mock_bridge.ensureAdvertising.side_effect = RuntimeError("Bridge error")
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.ensure_advertising()

        self.assertFalse(result)
        self.assertTrue(len(self.log_calls) > 0, "Expected log calls")
        self.assertTrue(any("error" in str(msg).lower() for msg, _ in self.log_calls))


class TestRequestIdentityResyncRealClass(unittest.TestCase):
    """
    Test request_identity_resync() method on the REAL AndroidBLEDriver class.

    This tests the actual code in android_ble_driver.py to get coverage.
    The method is called when BLEInterface receives data from a peer but has
    no identity mapping (Python's disconnect callback fired but Kotlin
    maintained the GATT connection).
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class with proper mock setup."""
        # Remove existing bluetooth_driver mock to replace with proper class mock
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        # Create proper base class (not MagicMock)
        class MockBLEDriverInterface:
            """Mock base class for AndroidBLEDriver."""
            pass

        # Set up bluetooth_driver with proper class
        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        # Add ble_modules to path
        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        # Import the real class and module
        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures with a real driver instance."""
        # Create driver instance without calling __init__
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        # Set up log capture in the module's namespace
        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_request_identity_resync_no_bridge_returns_false(self):
        """
        Test that request_identity_resync returns False when no bridge.

        Without a Kotlin bridge, we cannot request identity resync.
        """
        self.driver.kotlin_bridge = None

        result = self.driver.request_identity_resync("AA:BB:CC:DD:EE:FF")

        self.assertFalse(result)
        self.assertTrue(len(self.log_calls) > 0, "Expected log calls")
        self.assertTrue(any("no bridge" in str(msg).lower() for msg, _ in self.log_calls))

    def test_request_identity_resync_found_returns_true(self):
        """
        Test that request_identity_resync returns True when identity found.

        When Kotlin finds the identity for the address, return True.
        """
        mock_bridge = Mock()
        mock_bridge.requestIdentityResync.return_value = True
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.request_identity_resync("AA:BB:CC:DD:EE:FF")

        self.assertTrue(result)
        mock_bridge.requestIdentityResync.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_request_identity_resync_not_found_returns_false(self):
        """
        Test that request_identity_resync returns False when identity not found.

        When Kotlin doesn't find the identity for the address, return False.
        """
        mock_bridge = Mock()
        mock_bridge.requestIdentityResync.return_value = False
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.request_identity_resync("AA:BB:CC:DD:EE:FF")

        self.assertFalse(result)
        mock_bridge.requestIdentityResync.assert_called_once()

    def test_request_identity_resync_exception_returns_false(self):
        """
        Test that request_identity_resync returns False on exception.

        If Kotlin bridge throws an exception, catch it, log error, return False.
        """
        mock_bridge = Mock()
        mock_bridge.requestIdentityResync.side_effect = RuntimeError("Bridge error")
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.request_identity_resync("AA:BB:CC:DD:EE:FF")

        self.assertFalse(result)
        self.assertTrue(len(self.log_calls) > 0, "Expected log calls")
        self.assertTrue(any("error" in str(msg).lower() for msg, _ in self.log_calls))


if __name__ == '__main__':
    unittest.main()
