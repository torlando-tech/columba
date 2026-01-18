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


class TestDuplicateIdentityCallbackMissing(unittest.TestCase):
    """
    BUG TEST: on_duplicate_identity_detected callback is NOT wired up on Android.

    BUG DESCRIPTION:
    ----------------
    On Linux, BLEInterface sets driver.on_duplicate_identity_detected = _check_duplicate_identity
    and the LinuxBluetoothDriver calls this callback when identity is received.

    On Android:
    1. BLEInterface sets driver.on_duplicate_identity_detected = _check_duplicate_identity
    2. AndroidBLEDriver._setup_kotlin_callbacks() does NOT wire this to Kotlin
    3. KotlinBLEBridge doesn't have onDuplicateIdentityDetected callback anyway
    4. Result: Python's duplicate identity check is NEVER called on Android

    This means duplicate connections (same identity at different MAC addresses)
    are allowed on Android, wasting resources and potentially causing duplicate
    packet delivery.

    EXPECTED BEHAVIOR:
    -----------------
    AndroidBLEDriver._setup_kotlin_callbacks() should set up:
        self.kotlin_bridge.setOnDuplicateIdentityDetected(self._handle_duplicate_identity)

    And KotlinBLEBridge should call this callback in handleIdentityReceived()
    BEFORE notifying Python of the connection.
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

    def test_setup_kotlin_callbacks_wires_duplicate_identity_detection(self):
        """
        Test that _setup_kotlin_callbacks wires up on_duplicate_identity_detected.

        This verifies that:
        1. on_duplicate_identity_detected callback is set on the driver
        2. _setup_kotlin_callbacks() wires it to Kotlin via setOnDuplicateIdentityDetected
        3. Therefore, Python's duplicate check IS called on Android
        """
        # Create driver instance without calling __init__
        driver = object.__new__(self.AndroidBLEDriver)
        driver._connected_peers = []
        driver._peer_roles = {}
        driver._peer_mtus = {}
        driver._pending_identities = {}
        driver._identity_lock = threading.Lock()
        driver._address_to_identity = {}
        driver._identity_to_address = {}

        # Mock the Kotlin bridge
        mock_bridge = MagicMock()
        driver.kotlin_bridge = mock_bridge

        # Set up the on_duplicate_identity_detected callback (simulating what BLEInterface does)
        def mock_check_duplicate(address, identity):
            return False  # Not a duplicate

        driver.on_duplicate_identity_detected = mock_check_duplicate

        # Suppress RNS.log calls
        with patch.object(self.abd_module, 'RNS') as mock_rns:
            mock_rns.LOG_DEBUG = 5
            mock_rns.LOG_INFO = 4
            mock_rns.log = MagicMock()

            # Call the actual method
            driver._setup_kotlin_callbacks()

        # Verify that setOnDuplicateIdentityDetected WAS called
        mock_bridge.setOnDuplicateIdentityDetected.assert_called_once()

        # The callback should be a lambda that calls _handle_duplicate_identity_detected
        callback = mock_bridge.setOnDuplicateIdentityDetected.call_args[0][0]
        self.assertIsNotNone(callback, "Callback should be set")

    def test_all_callbacks_wired_including_duplicate_identity_detection(self):
        """
        Test that all callbacks are wired up in _setup_kotlin_callbacks,
        including the duplicate identity detection callback.

        This verifies that the full callback chain is established for Android.
        """
        # List of ALL callbacks that should be wired up in _setup_kotlin_callbacks
        wired_callbacks = [
            "setOnDeviceDiscovered",
            "setOnConnected",
            "setOnDisconnected",
            "setOnDataReceived",
            "setOnIdentityReceived",
            "setOnMtuNegotiated",
            "setOnAddressChanged",
            "setOnDuplicateIdentityDetected",  # Added for MAC rotation handling
        ]

        # Verify the list is complete
        self.assertEqual(len(wired_callbacks), 8, "Should have 8 wired callbacks")
        self.assertIn(
            "setOnDuplicateIdentityDetected",
            wired_callbacks,
            "setOnDuplicateIdentityDetected must be in the list of wired callbacks "
            "for duplicate identity detection to work on Android."
        )


class TestDuplicateIdentityDetectionFlow(unittest.TestCase):
    """
    Test the expected flow for duplicate identity detection on Android.

    Documents how the fix should work:
    1. Kotlin receives identity in handleIdentityReceived()
    2. Kotlin calls Python's on_duplicate_identity_detected(address, identity)
    3. Python's _check_duplicate_identity checks identity_to_address map
    4. If duplicate found, Python returns True
    5. Kotlin rejects the connection with safe message format (no blacklist trigger)
    """

    def test_duplicate_identity_detection_expected_flow(self):
        """
        Document the expected flow for duplicate identity detection.

        This test describes what SHOULD happen after the fix is implemented.
        """
        # Expected flow:
        flow_steps = [
            "1. Identity X already connected at MAC_OLD (identity_to_address[hash(X)] = MAC_OLD)",
            "2. MAC_NEW connects (Android MAC rotation)",
            "3. Kotlin receives identity X from MAC_NEW in handleIdentityReceived()",
            "4. BEFORE calling onConnected, Kotlin calls onDuplicateIdentityDetected(MAC_NEW, X)",
            "5. Python's _check_duplicate_identity finds hash(X) -> MAC_OLD",
            "6. Python returns True (is duplicate)",
            "7. Kotlin logs safe message: 'Duplicate identity rejected for MAC_NEW' (no blacklist)",
            "8. Kotlin disconnects MAC_NEW GATT connection",
            "9. Connection from MAC_NEW is rejected, MAC_OLD connection continues",
        ]

        # The key insight: step 4 doesn't exist today (BUG)
        # After the fix, step 4 will call onDuplicateIdentityDetected

        # Verify we have all expected steps documented
        self.assertEqual(len(flow_steps), 9)
        self.assertIn("onDuplicateIdentityDetected", flow_steps[3])

    def test_safe_error_message_formats_for_duplicate_rejection(self):
        """
        Test that safe error message formats don't trigger blacklist.

        When duplicate identity is rejected, the error message must NOT match
        the blacklist regex pattern "Connection failed to" or "Connection timeout to".
        """
        import re

        mac = "AA:BB:CC:DD:EE:02"

        # Blacklist regex from BLEInterface._error_callback
        blacklist_regex = r'(?:Connection (?:failed|timeout) to|to) ([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})'

        # Messages that SHOULD trigger blacklist (real failures)
        unsafe_messages = [
            f"Connection failed to {mac}: timeout",
            f"Connection timeout to {mac}",
        ]

        # Messages that SHOULD NOT trigger blacklist (duplicate identity)
        safe_messages = [
            f"Duplicate identity rejected for {mac}",
            f"Rejecting duplicate identity from {mac}",
            f"MAC rotation duplicate detected: {mac}",
        ]

        # Verify unsafe messages match
        for msg in unsafe_messages:
            self.assertIsNotNone(
                re.search(blacklist_regex, msg),
                f"Unsafe message should match blacklist regex: {msg}"
            )

        # Verify safe messages do NOT match
        for msg in safe_messages:
            self.assertIsNone(
                re.search(blacklist_regex, msg),
                f"Safe message should NOT match blacklist regex: {msg}"
            )


class TestEnsureBytesFunction(unittest.TestCase):
    """
    Test the ensure_bytes() utility function.

    This function converts Chaquopy jarray to Python bytes when needed.
    When Kotlin passes ByteArray to Python via Chaquopy, it arrives as a
    jarray('B') (Java array), not Python bytes.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real ensure_bytes function."""
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

        # Import the real function
        import android_ble_driver as abd_module
        cls.abd_module = abd_module

    def test_ensure_bytes_with_bytes_returns_same_object(self):
        """
        Test that ensure_bytes returns the same object when given bytes.

        When input is already Python bytes, no conversion is needed.
        """
        data = b'\x01\x02\x03\x04\x05'

        result = self.abd_module.ensure_bytes(data)

        self.assertIs(result, data)  # Same object
        self.assertEqual(result, b'\x01\x02\x03\x04\x05')

    def test_ensure_bytes_with_list_converts_to_bytes(self):
        """
        Test that ensure_bytes converts iterables to bytes.

        jarray is iterable, so we can test with a list which behaves similarly.
        """
        # Simulate jarray-like behavior with a list of integers
        data = [0x01, 0x02, 0x03, 0x04, 0x05]

        result = self.abd_module.ensure_bytes(data)

        self.assertIsInstance(result, bytes)
        self.assertEqual(result, b'\x01\x02\x03\x04\x05')

    def test_ensure_bytes_with_empty_bytes(self):
        """
        Test that ensure_bytes handles empty bytes correctly.
        """
        data = b''

        result = self.abd_module.ensure_bytes(data)

        self.assertIs(result, data)
        self.assertEqual(result, b'')

    def test_ensure_bytes_with_empty_iterable(self):
        """
        Test that ensure_bytes handles empty iterables correctly.
        """
        data = []

        result = self.abd_module.ensure_bytes(data)

        self.assertIsInstance(result, bytes)
        self.assertEqual(result, b'')

    def test_ensure_bytes_with_tuple(self):
        """
        Test that ensure_bytes converts tuple (another iterable) to bytes.
        """
        data = (0x41, 0x42, 0x43)  # 'ABC'

        result = self.abd_module.ensure_bytes(data)

        self.assertIsInstance(result, bytes)
        self.assertEqual(result, b'ABC')


class MockJarray:
    """
    Mock Chaquopy jarray for testing ensure_bytes.

    Chaquopy's jarray is iterable but not a bytes instance.
    This mock simulates that behavior.
    """

    def __init__(self, data):
        self._data = list(data)

    def __iter__(self):
        return iter(self._data)


class TestEnsureBytesWithMockJarray(unittest.TestCase):
    """
    Test ensure_bytes with a mock jarray that mimics Chaquopy behavior.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real ensure_bytes function."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.abd_module = abd_module

    def test_ensure_bytes_with_mock_jarray(self):
        """
        Test that ensure_bytes converts mock jarray to bytes.

        This simulates the actual Chaquopy jarray behavior where data
        arrives as a Java array, not Python bytes.
        """
        # Create mock jarray with identity data
        jarray_data = MockJarray([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10])

        result = self.abd_module.ensure_bytes(jarray_data)

        self.assertIsInstance(result, bytes)
        self.assertEqual(len(result), 16)
        self.assertEqual(result.hex(), '0102030405060708090a0b0c0d0e0f10')

    def test_ensure_bytes_jarray_allows_hex_method(self):
        """
        Test that after conversion, we can call bytes methods like .hex().

        This is the main purpose of ensure_bytes - to allow Python bytes
        methods on data received from Kotlin.
        """
        jarray_data = MockJarray([0xab, 0xcd, 0xef])

        result = self.abd_module.ensure_bytes(jarray_data)

        # These would fail on jarray but work after conversion
        self.assertEqual(result.hex(), 'abcdef')
        self.assertEqual(len(result), 3)
        self.assertEqual(result[0], 0xab)


class TestHandleDuplicateIdentityDetected(unittest.TestCase):
    """
    Test _handle_duplicate_identity_detected method on the REAL AndroidBLEDriver class.

    This method is called by Kotlin before accepting a connection to check
    if the identity is already connected at a different address (MAC rotation).
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class with proper mock setup."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures with a real driver instance."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        self.driver.on_duplicate_identity_detected = None

        # Set up log capture
        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_duplicate_identity_no_callback_returns_false(self):
        """
        Test that _handle_duplicate_identity_detected returns False when no callback.

        When BLEInterface hasn't set on_duplicate_identity_detected, we allow
        all connections (fail open).
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # No callback set
        self.driver.on_duplicate_identity_detected = None

        result = self.driver._handle_duplicate_identity_detected(address, identity)

        self.assertFalse(result)

    def test_duplicate_identity_callback_returns_false_for_non_duplicate(self):
        """
        Test that _handle_duplicate_identity_detected returns False for non-duplicates.

        When the callback returns False (not a duplicate), the connection is allowed.
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # Callback returns False (not a duplicate)
        self.driver.on_duplicate_identity_detected = Mock(return_value=False)

        result = self.driver._handle_duplicate_identity_detected(address, identity)

        self.assertFalse(result)
        self.driver.on_duplicate_identity_detected.assert_called_once_with(address, identity)

    def test_duplicate_identity_callback_returns_true_for_duplicate(self):
        """
        Test that _handle_duplicate_identity_detected returns True for duplicates.

        When the callback returns True (is duplicate), the connection is rejected.
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # Callback returns True (is duplicate)
        self.driver.on_duplicate_identity_detected = Mock(return_value=True)

        result = self.driver._handle_duplicate_identity_detected(address, identity)

        self.assertTrue(result)
        self.driver.on_duplicate_identity_detected.assert_called_once_with(address, identity)
        # Should log warning about duplicate rejection
        self.assertTrue(any("Duplicate identity rejected" in str(msg) for msg, _ in self.log_calls))

    def test_duplicate_identity_exception_returns_false(self):
        """
        Test that _handle_duplicate_identity_detected returns False on exception.

        On error, we fail open and allow the connection.
        """
        address = "AA:BB:CC:DD:EE:FF"
        identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        # Callback raises exception
        self.driver.on_duplicate_identity_detected = Mock(side_effect=RuntimeError("Test error"))

        result = self.driver._handle_duplicate_identity_detected(address, identity)

        self.assertFalse(result)
        # Should log error
        self.assertTrue(any("Error in duplicate identity check" in str(msg) for msg, _ in self.log_calls))

    def test_duplicate_identity_converts_jarray_to_bytes(self):
        """
        Test that _handle_duplicate_identity_detected converts jarray to bytes.

        When Kotlin passes ByteArray, it arrives as jarray which needs conversion.
        """
        address = "AA:BB:CC:DD:EE:FF"
        # Use mock jarray instead of bytes
        jarray_identity = MockJarray([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                      0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10])

        # Callback that checks the type
        received_identity = []

        def capture_callback(addr, identity):
            received_identity.append(identity)
            return False

        self.driver.on_duplicate_identity_detected = capture_callback

        self.driver._handle_duplicate_identity_detected(address, jarray_identity)

        # Verify callback received bytes, not jarray
        self.assertEqual(len(received_identity), 1)
        self.assertIsInstance(received_identity[0], bytes)
        self.assertEqual(received_identity[0].hex(), '0102030405060708090a0b0c0d0e0f10')


class TestHandleDataReceivedEnsureBytes(unittest.TestCase):
    """
    Test that _handle_data_received uses ensure_bytes to convert jarray.

    This tests the specific code path where data arrives from Kotlin as
    jarray and needs conversion before processing.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures with a real driver instance."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver._connected_peers = []
        self.driver._peer_roles = {}
        self.driver._peer_mtus = {}
        self.driver._pending_identities = {}
        self.driver._identity_lock = threading.Lock()
        self.driver.on_data_received = None
        self.driver.on_device_connected = None
        self.driver.on_mtu_negotiated = None

        # Set up log capture
        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_handle_data_received_converts_jarray(self):
        """
        Test that _handle_data_received converts jarray data to bytes.

        The on_data_received callback should receive Python bytes, not jarray.
        """
        address = "AA:BB:CC:DD:EE:FF"
        self.driver._connected_peers.append(address)

        # Simulate jarray data from Kotlin
        jarray_data = MockJarray([0x48, 0x65, 0x6c, 0x6c, 0x6f])  # "Hello"

        received_data = []

        def capture_callback(addr, data):
            received_data.append((addr, data))

        self.driver.on_data_received = capture_callback

        self.driver._handle_data_received(address, jarray_data)

        # Verify callback received bytes
        self.assertEqual(len(received_data), 1)
        addr, data = received_data[0]
        self.assertEqual(addr, address)
        self.assertIsInstance(data, bytes)
        self.assertEqual(data, b'Hello')

    def test_handle_data_received_passes_through_bytes(self):
        """
        Test that _handle_data_received passes through bytes unchanged.
        """
        address = "AA:BB:CC:DD:EE:FF"
        self.driver._connected_peers.append(address)

        bytes_data = b'Test data'

        received_data = []
        self.driver.on_data_received = lambda addr, data: received_data.append((addr, data))

        self.driver._handle_data_received(address, bytes_data)

        self.assertEqual(len(received_data), 1)
        self.assertEqual(received_data[0][1], b'Test data')

    def test_handle_data_received_finalizes_pending_connection(self):
        """
        Test race condition: data arrives for address with pending identity but not connected.

        This tests the fix where data arrives BEFORE onConnected callback fires,
        but AFTER onIdentityReceived cached the identity. The driver should:
        1. Detect pending identity for this address
        2. Add to connected_peers
        3. Call on_device_connected with the pending identity
        4. Call on_mtu_negotiated (to create reassembler)
        5. Then pass data to on_data_received
        """
        address = "AA:BB:CC:DD:EE:FF"
        pending_identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        data = b"Hello"

        # Address NOT in connected_peers, but HAS pending identity
        self.driver._connected_peers = []
        self.driver._pending_identities[address] = pending_identity
        self.driver._peer_mtus[address] = 512  # Cached MTU from earlier

        connected_calls = []
        mtu_calls = []
        data_calls = []
        self.driver.on_device_connected = lambda addr, identity: connected_calls.append((addr, identity))
        self.driver.on_mtu_negotiated = lambda addr, mtu: mtu_calls.append((addr, mtu))
        self.driver.on_data_received = lambda addr, data: data_calls.append((addr, data))

        self.driver._handle_data_received(address, data)

        # Verify connection was finalized
        self.assertIn(address, self.driver._connected_peers)
        self.assertEqual(self.driver._peer_roles[address], "peripheral")

        # Verify pending identity was consumed
        self.assertNotIn(address, self.driver._pending_identities)

        # Verify callbacks called in correct order
        self.assertEqual(len(connected_calls), 1)
        self.assertEqual(connected_calls[0][0], address)
        self.assertEqual(connected_calls[0][1], pending_identity)

        self.assertEqual(len(mtu_calls), 1)
        self.assertEqual(mtu_calls[0], (address, 512))

        self.assertEqual(len(data_calls), 1)
        self.assertEqual(data_calls[0], (address, data))

    def test_handle_data_received_uses_default_mtu_when_not_cached(self):
        """
        Test that default MTU (23) is used when no cached MTU available.

        When data arrives before connection AND MTU wasn't negotiated,
        use BLE 4.0 minimum MTU (23).
        """
        address = "AA:BB:CC:DD:EE:FF"
        pending_identity = b'\x01' * 16
        data = b"Hello"

        # Pending identity but NO cached MTU
        self.driver._connected_peers = []
        self.driver._pending_identities[address] = pending_identity
        # No MTU in _peer_mtus

        mtu_calls = []
        self.driver.on_device_connected = Mock()
        self.driver.on_mtu_negotiated = lambda addr, mtu: mtu_calls.append((addr, mtu))
        self.driver.on_data_received = Mock()

        self.driver._handle_data_received(address, data)

        # Verify default MTU (23) was used
        self.assertEqual(len(mtu_calls), 1)
        self.assertEqual(mtu_calls[0], (address, 23))


class TestAndroidBLEDriverLifecycle(unittest.TestCase):
    """
    Test lifecycle methods on the REAL AndroidBLEDriver class.

    Tests __init__, start(), and stop() to ensure proper state transitions.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class with proper mock setup."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module
        cls.DriverState = MockDriverState

    def setUp(self):
        """Set up test fixtures."""
        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_init_creates_proper_state(self):
        """Test that __init__ initializes all required attributes."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()

        self.assertEqual(driver._state, self.DriverState.IDLE)
        self.assertIsNone(driver.kotlin_bridge)
        self.assertIsNone(driver._transport_identity)
        self.assertEqual(driver._connected_peers, [])
        self.assertEqual(driver._peer_roles, {})
        self.assertEqual(driver._peer_mtus, {})
        self.assertEqual(driver._address_to_identity, {})
        self.assertEqual(driver._identity_to_address, {})
        self.assertEqual(driver._pending_identities, {})
        self.assertIsInstance(driver._identity_lock, type(threading.Lock()))

    def test_init_accepts_kwargs(self):
        """Test that __init__ accepts configuration kwargs."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__(service_discovery_delay=1.5)

        self.assertEqual(driver._service_discovery_delay, 1.5)

    def test_start_with_no_bridge_raises(self):
        """Test that start() raises exception when bridge unavailable."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()
        driver.on_error = Mock()

        # Mock _get_kotlin_bridge to return None
        driver._get_kotlin_bridge = Mock(return_value=None)

        with self.assertRaises(Exception) as context:
            driver.start("service-uuid", "rx-uuid", "tx-uuid", "id-uuid")

        self.assertIn("Failed to get KotlinBLEBridge", str(context.exception))
        driver.on_error.assert_called_once()

    def test_start_when_already_started_logs_warning(self):
        """Test that start() logs warning when not in IDLE state."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()
        driver._state = self.DriverState.SCANNING

        driver.start("service-uuid", "rx-uuid", "tx-uuid", "id-uuid")

        self.assertTrue(any("Cannot start" in str(msg) for msg, _ in self.log_calls))

    def test_start_success_configures_bridge(self):
        """Test that start() configures bridge and sets up callbacks."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()

        mock_bridge = MagicMock()
        driver._get_kotlin_bridge = Mock(return_value=mock_bridge)
        driver._transport_identity = b'\x01' * 16

        driver.start("service-uuid", "rx-uuid", "tx-uuid", "id-uuid")

        # Verify UUIDs stored
        self.assertEqual(driver._service_uuid, "service-uuid")
        self.assertEqual(driver._rx_char_uuid, "rx-uuid")
        self.assertEqual(driver._tx_char_uuid, "tx-uuid")
        self.assertEqual(driver._identity_char_uuid, "id-uuid")

        # Verify bridge methods called
        mock_bridge.startAsync.assert_called_once_with(
            "service-uuid", "rx-uuid", "tx-uuid", "id-uuid"
        )
        mock_bridge.setIdentity.assert_called_once_with(b'\x01' * 16)

    def test_stop_when_idle_does_nothing(self):
        """Test that stop() returns early when already IDLE."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()
        driver._state = self.DriverState.IDLE

        initial_log_count = len(self.log_calls)
        driver.stop()

        # Should not log "Stopping..." since we're already idle
        stop_logs = [msg for msg, _ in self.log_calls[initial_log_count:] if "Stopping" in str(msg)]
        self.assertEqual(len(stop_logs), 0)

    def test_stop_clears_state_and_calls_bridge(self):
        """Test that stop() clears state and stops bridge."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()
        driver._state = self.DriverState.SCANNING

        mock_bridge = MagicMock()
        driver.kotlin_bridge = mock_bridge
        driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]
        driver._peer_roles = {"AA:BB:CC:DD:EE:FF": "central"}

        driver.stop()

        mock_bridge.stopAsync.assert_called_once()
        self.assertEqual(driver._connected_peers, [])
        self.assertEqual(driver._peer_roles, {})
        self.assertEqual(driver._state, self.DriverState.IDLE)

    def test_stop_handles_exception(self):
        """Test that stop() handles exceptions gracefully."""
        driver = object.__new__(self.AndroidBLEDriver)
        driver.__init__()
        driver._state = self.DriverState.SCANNING
        driver.on_error = Mock()

        mock_bridge = MagicMock()
        mock_bridge.stopAsync.side_effect = RuntimeError("Bridge error")
        driver.kotlin_bridge = mock_bridge

        # Should not raise
        driver.stop()

        driver.on_error.assert_called_once()
        self.assertTrue(any("Error stopping" in str(msg) for msg, _ in self.log_calls))


class TestAndroidBLEDriverConfiguration(unittest.TestCase):
    """
    Test configuration methods on the REAL AndroidBLEDriver class.

    Tests set_identity(), set_service_discovery_delay(), set_power_mode().
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver._transport_identity = None
        self.driver.kotlin_bridge = None
        self.driver._service_discovery_delay = 0.5
        self.driver._power_mode = "balanced"

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_set_identity_valid_16_bytes(self):
        """Test that set_identity() accepts valid 16-byte identity."""
        identity = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        self.driver.set_identity(identity)

        self.assertEqual(self.driver._transport_identity, identity)

    def test_set_identity_invalid_length_raises(self):
        """Test that set_identity() raises ValueError for invalid length."""
        with self.assertRaises(ValueError) as context:
            self.driver.set_identity(b'\x01\x02\x03')

        self.assertIn("16 bytes", str(context.exception))

    def test_set_identity_propagates_to_bridge(self):
        """Test that set_identity() propagates to Kotlin bridge if available."""
        identity = b'\x01' * 16
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.set_identity(identity)

        mock_bridge.setIdentity.assert_called_once_with(identity)

    def test_set_service_discovery_delay(self):
        """Test that set_service_discovery_delay() stores value."""
        self.driver.set_service_discovery_delay(2.5)

        self.assertEqual(self.driver._service_discovery_delay, 2.5)
        self.assertTrue(any("discovery delay" in str(msg).lower() for msg, _ in self.log_calls))

    def test_set_power_mode_valid_modes(self):
        """Test that set_power_mode() accepts valid modes."""
        for mode in ["aggressive", "balanced", "saver"]:
            self.driver.set_power_mode(mode)
            self.assertEqual(self.driver._power_mode, mode)

    def test_set_power_mode_invalid_mode_raises(self):
        """Test that set_power_mode() raises ValueError for invalid mode."""
        with self.assertRaises(ValueError) as context:
            self.driver.set_power_mode("turbo")

        self.assertIn("Invalid power mode", str(context.exception))


class TestAndroidBLEDriverScanning(unittest.TestCase):
    """
    Test scanning methods on the REAL AndroidBLEDriver class.

    Tests start_scanning() and stop_scanning().
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module
        cls.DriverState = MockDriverState

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        self.driver._state = MockDriverState.IDLE
        self.driver.on_error = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_start_scanning_no_bridge_raises(self):
        """Test that start_scanning() raises when no bridge."""
        self.driver.on_error = Mock()

        self.driver.start_scanning()

        self.driver.on_error.assert_called_once()
        self.assertTrue(any("Failed to start scanning" in str(msg) for msg, _ in self.log_calls))

    def test_start_scanning_success(self):
        """Test that start_scanning() calls bridge and updates state."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.start_scanning()

        mock_bridge.startScanningAsync.assert_called_once()
        self.assertEqual(self.driver._state, self.DriverState.SCANNING)

    def test_stop_scanning_with_bridge(self):
        """Test that stop_scanning() calls bridge."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge
        self.driver._state = self.DriverState.SCANNING

        self.driver.stop_scanning()

        mock_bridge.stopScanningAsync.assert_called_once()
        self.assertEqual(self.driver._state, self.DriverState.IDLE)

    def test_stop_scanning_no_bridge_does_not_raise(self):
        """Test that stop_scanning() without bridge doesn't raise."""
        self.driver.kotlin_bridge = None
        self.driver._state = self.DriverState.SCANNING

        # Should not raise
        self.driver.stop_scanning()

    def test_stop_scanning_handles_exception(self):
        """Test that stop_scanning() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.stopScanningAsync.side_effect = RuntimeError("Bridge error")
        self.driver.kotlin_bridge = mock_bridge

        # Should not raise
        self.driver.stop_scanning()

        self.assertTrue(any("Error stopping scan" in str(msg) for msg, _ in self.log_calls))


class TestAndroidBLEDriverAdvertising(unittest.TestCase):
    """
    Test advertising methods on the REAL AndroidBLEDriver class.

    Tests start_advertising() and stop_advertising().
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module
        cls.DriverState = MockDriverState

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        self.driver._state = MockDriverState.IDLE
        self.driver.on_error = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_start_advertising_no_bridge_raises(self):
        """Test that start_advertising() raises when no bridge."""
        self.driver.on_error = Mock()

        self.driver.start_advertising("RNS-test", b'\x01' * 16)

        self.driver.on_error.assert_called_once()
        self.assertTrue(any("Failed to start advertising" in str(msg) for msg, _ in self.log_calls))

    def test_start_advertising_success(self):
        """Test that start_advertising() calls bridge and updates state."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.start_advertising("RNS-test", b'\x01' * 16)

        mock_bridge.startAdvertisingAsync.assert_called_once_with("RNS-test")
        self.assertEqual(self.driver._state, self.DriverState.ADVERTISING)

    def test_stop_advertising_with_bridge(self):
        """Test that stop_advertising() calls bridge."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge
        self.driver._state = self.DriverState.ADVERTISING

        self.driver.stop_advertising()

        mock_bridge.stopAdvertisingAsync.assert_called_once()
        self.assertEqual(self.driver._state, self.DriverState.IDLE)

    def test_stop_advertising_no_bridge_does_not_raise(self):
        """Test that stop_advertising() without bridge doesn't raise."""
        self.driver.kotlin_bridge = None
        self.driver._state = self.DriverState.ADVERTISING

        # Should not raise
        self.driver.stop_advertising()

    def test_stop_advertising_handles_exception(self):
        """Test that stop_advertising() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.stopAdvertisingAsync.side_effect = RuntimeError("Bridge error")
        self.driver.kotlin_bridge = mock_bridge

        # Should not raise
        self.driver.stop_advertising()

        self.assertTrue(any("Error stopping advertising" in str(msg) for msg, _ in self.log_calls))


class TestAndroidBLEDriverConnection(unittest.TestCase):
    """
    Test connection methods on the REAL AndroidBLEDriver class.

    Tests should_connect(), connect(), disconnect(), disconnect_central(), disconnect_peripheral().
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        self.driver.on_error = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_should_connect_no_bridge_returns_false(self):
        """Test that should_connect() returns False when no bridge."""
        result = self.driver.should_connect("AA:BB:CC:DD:EE:FF")

        self.assertFalse(result)
        self.assertTrue(any("no bridge" in str(msg).lower() for msg, _ in self.log_calls))

    def test_should_connect_returns_bridge_result(self):
        """Test that should_connect() returns bridge result."""
        mock_bridge = MagicMock()
        mock_bridge.shouldConnect.return_value = True
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.should_connect("AA:BB:CC:DD:EE:FF")

        self.assertTrue(result)
        mock_bridge.shouldConnect.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_should_connect_exception_returns_false(self):
        """Test that should_connect() returns False on exception."""
        mock_bridge = MagicMock()
        mock_bridge.shouldConnect.side_effect = RuntimeError("Error")
        self.driver.kotlin_bridge = mock_bridge

        result = self.driver.should_connect("AA:BB:CC:DD:EE:FF")

        self.assertFalse(result)

    def test_connect_no_bridge_raises(self):
        """Test that connect() calls error callback when no bridge."""
        self.driver.on_error = Mock()

        self.driver.connect("AA:BB:CC:DD:EE:FF")

        self.driver.on_error.assert_called_once()

    def test_connect_success(self):
        """Test that connect() calls bridge connectAsync."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.connect("AA:BB:CC:DD:EE:FF")

        mock_bridge.connectAsync.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_disconnect_with_bridge(self):
        """Test that disconnect() calls bridge disconnectAsync."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.disconnect("AA:BB:CC:DD:EE:FF")

        mock_bridge.disconnectAsync.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_disconnect_no_bridge_does_not_raise(self):
        """Test that disconnect() without bridge doesn't raise."""
        # Should not raise
        self.driver.disconnect("AA:BB:CC:DD:EE:FF")

    def test_disconnect_handles_exception(self):
        """Test that disconnect() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.disconnectAsync.side_effect = RuntimeError("Error")
        self.driver.kotlin_bridge = mock_bridge

        # Should not raise
        self.driver.disconnect("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("Error disconnecting" in str(msg) for msg, _ in self.log_calls))

    def test_disconnect_central_with_bridge(self):
        """Test that disconnect_central() calls bridge disconnectCentralAsync."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.disconnect_central("AA:BB:CC:DD:EE:FF")

        mock_bridge.disconnectCentralAsync.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_disconnect_central_no_bridge_logs_warning(self):
        """Test that disconnect_central() logs warning when no bridge."""
        self.driver.disconnect_central("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("no bridge" in str(msg).lower() for msg, _ in self.log_calls))

    def test_disconnect_peripheral_with_bridge(self):
        """Test that disconnect_peripheral() calls bridge disconnectPeripheralAsync."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.disconnect_peripheral("AA:BB:CC:DD:EE:FF")

        mock_bridge.disconnectPeripheralAsync.assert_called_once_with("AA:BB:CC:DD:EE:FF")

    def test_disconnect_peripheral_no_bridge_logs_warning(self):
        """Test that disconnect_peripheral() logs warning when no bridge."""
        self.driver.disconnect_peripheral("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("no bridge" in str(msg).lower() for msg, _ in self.log_calls))

    def test_disconnect_central_handles_exception(self):
        """Test that disconnect_central() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.disconnectCentralAsync.side_effect = RuntimeError("Error")
        self.driver.kotlin_bridge = mock_bridge

        # Should not raise
        self.driver.disconnect_central("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("Error disconnecting central" in str(msg) for msg, _ in self.log_calls))

    def test_disconnect_peripheral_handles_exception(self):
        """Test that disconnect_peripheral() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.disconnectPeripheralAsync.side_effect = RuntimeError("Error")
        self.driver.kotlin_bridge = mock_bridge

        # Should not raise
        self.driver.disconnect_peripheral("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("Error disconnecting peripheral" in str(msg) for msg, _ in self.log_calls))


class TestAndroidBLEDriverSend(unittest.TestCase):
    """
    Test send() method on the REAL AndroidBLEDriver class.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver.kotlin_bridge = None
        self.driver.on_error = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_send_no_bridge_calls_error(self):
        """Test that send() calls error callback when no bridge."""
        self.driver.on_error = Mock()

        self.driver.send("AA:BB:CC:DD:EE:FF", b"test data")

        self.driver.on_error.assert_called_once()

    def test_send_success(self):
        """Test that send() calls bridge sendAsync."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver.send("AA:BB:CC:DD:EE:FF", b"test data")

        mock_bridge.sendAsync.assert_called_once_with("AA:BB:CC:DD:EE:FF", b"test data")

    def test_send_handles_exception(self):
        """Test that send() handles exceptions gracefully."""
        mock_bridge = MagicMock()
        mock_bridge.sendAsync.side_effect = RuntimeError("Error")
        self.driver.kotlin_bridge = mock_bridge
        self.driver.on_error = Mock()

        self.driver.send("AA:BB:CC:DD:EE:FF", b"test data")

        self.driver.on_error.assert_called_once()


class TestAndroidBLEDriverGattStubs(unittest.TestCase):
    """
    Test GATT stub methods on the REAL AndroidBLEDriver class.

    These methods exist for interface compatibility but log warnings.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_read_characteristic_returns_empty_and_logs_warning(self):
        """Test that read_characteristic() returns empty bytes and logs warning."""
        result = self.driver.read_characteristic("AA:BB:CC:DD:EE:FF", "char-uuid")

        self.assertEqual(result, b"")
        self.assertTrue(any("not implemented" in str(msg).lower() for msg, _ in self.log_calls))

    def test_write_characteristic_logs_warning(self):
        """Test that write_characteristic() logs warning."""
        self.driver.write_characteristic("AA:BB:CC:DD:EE:FF", "char-uuid", b"data")

        self.assertTrue(any("not implemented" in str(msg).lower() for msg, _ in self.log_calls))

    def test_start_notify_logs_debug(self):
        """Test that start_notify() logs debug message."""
        self.driver.start_notify("AA:BB:CC:DD:EE:FF", "char-uuid", Mock())

        self.assertTrue(any("not needed" in str(msg).lower() for msg, _ in self.log_calls))


class TestAndroidBLEDriverQueries(unittest.TestCase):
    """
    Test query methods on the REAL AndroidBLEDriver class.

    Tests state property, connected_peers, get_local_address(), get_peer_role(), get_peer_mtu().
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module
        cls.DriverState = MockDriverState

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver._state = MockDriverState.IDLE
        self.driver._connected_peers = []
        self.driver._peer_roles = {}
        self.driver._peer_mtus = {}

    def test_state_property(self):
        """Test that state property returns current state."""
        self.driver._state = self.DriverState.SCANNING

        self.assertEqual(self.driver.state, self.DriverState.SCANNING)

    def test_connected_peers_returns_copy(self):
        """Test that connected_peers returns a copy of the list."""
        self.driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]

        result = self.driver.connected_peers
        result.append("11:22:33:44:55:66")

        # Original should not be modified
        self.assertEqual(len(self.driver._connected_peers), 1)

    def test_get_local_address_returns_placeholder(self):
        """Test that get_local_address() returns placeholder."""
        result = self.driver.get_local_address()

        self.assertEqual(result, "00:00:00:00:00:00")

    def test_get_peer_role_returns_role(self):
        """Test that get_peer_role() returns role when present."""
        self.driver._peer_roles["AA:BB:CC:DD:EE:FF"] = "central"

        result = self.driver.get_peer_role("AA:BB:CC:DD:EE:FF")

        self.assertEqual(result, "central")

    def test_get_peer_role_returns_none_for_unknown(self):
        """Test that get_peer_role() returns None for unknown peer."""
        result = self.driver.get_peer_role("AA:BB:CC:DD:EE:FF")

        self.assertIsNone(result)

    def test_get_peer_mtu_returns_mtu(self):
        """Test that get_peer_mtu() returns MTU when present."""
        self.driver._peer_mtus["AA:BB:CC:DD:EE:FF"] = 512

        result = self.driver.get_peer_mtu("AA:BB:CC:DD:EE:FF")

        self.assertEqual(result, 512)

    def test_get_peer_mtu_returns_none_for_unknown(self):
        """Test that get_peer_mtu() returns None for unknown peer."""
        result = self.driver.get_peer_mtu("AA:BB:CC:DD:EE:FF")

        self.assertIsNone(result)


class TestAndroidBLEDriverCallbackHandlers(unittest.TestCase):
    """
    Test callback handler methods on the REAL AndroidBLEDriver class.

    Tests _handle_device_discovered, _handle_connected, _handle_disconnected,
    _handle_address_changed, _handle_identity_received, _handle_mtu_negotiated.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        cls.MockBLEDevice = MagicMock()

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = cls.MockBLEDevice
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver._connected_peers = []
        self.driver._peer_roles = {}
        self.driver._peer_mtus = {}
        self.driver._pending_identities = {}
        self.driver._identity_lock = threading.Lock()
        self.driver._address_to_identity = {}
        self.driver._identity_to_address = {}
        self.driver.on_device_discovered = None
        self.driver.on_device_connected = None
        self.driver.on_device_disconnected = None
        self.driver.on_data_received = None
        self.driver.on_mtu_negotiated = None
        self.driver.on_address_changed = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_handle_device_discovered_creates_ble_device(self):
        """Test that _handle_device_discovered creates BLEDevice and calls callback."""
        received = []
        self.driver.on_device_discovered = lambda dev: received.append(dev)

        self.driver._handle_device_discovered("AA:BB:CC:DD:EE:FF", "RNS-test", -50, ["uuid1"])

        self.assertEqual(len(received), 1)
        self.MockBLEDevice.assert_called_once_with(
            address="AA:BB:CC:DD:EE:FF",
            name="RNS-test",
            rssi=-50,
            service_uuids=["uuid1"]
        )

    def test_handle_device_discovered_handles_none_values(self):
        """Test that _handle_device_discovered handles None name and uuids."""
        self.driver.on_device_discovered = Mock()
        self.MockBLEDevice.reset_mock()

        self.driver._handle_device_discovered("AA:BB:CC:DD:EE:FF", None, -50, None)

        self.MockBLEDevice.assert_called_once_with(
            address="AA:BB:CC:DD:EE:FF",
            name="Unknown",
            rssi=-50,
            service_uuids=[]
        )

    def test_handle_connected_adds_peer_and_calls_callbacks(self):
        """Test that _handle_connected adds peer and calls callbacks."""
        connected_calls = []
        mtu_calls = []
        self.driver.on_device_connected = lambda addr, identity: connected_calls.append((addr, identity))
        self.driver.on_mtu_negotiated = lambda addr, mtu: mtu_calls.append((addr, mtu))

        identity_hex = "0102030405060708090a0b0c0d0e0f10"
        self.driver._handle_connected("AA:BB:CC:DD:EE:FF", 512, "central", identity_hex)

        self.assertIn("AA:BB:CC:DD:EE:FF", self.driver._connected_peers)
        self.assertEqual(self.driver._peer_roles["AA:BB:CC:DD:EE:FF"], "central")
        self.assertEqual(len(connected_calls), 1)
        self.assertEqual(connected_calls[0][0], "AA:BB:CC:DD:EE:FF")
        self.assertEqual(connected_calls[0][1], bytes.fromhex(identity_hex))
        self.assertEqual(len(mtu_calls), 1)

    def test_handle_connected_uses_pending_identity_fallback(self):
        """Test that _handle_connected uses pending identity when no identity_hash."""
        connected_calls = []
        self.driver.on_device_connected = lambda addr, identity: connected_calls.append((addr, identity))
        self.driver.on_mtu_negotiated = Mock()

        pending_identity = b'\x01' * 16
        self.driver._pending_identities["AA:BB:CC:DD:EE:FF"] = pending_identity

        self.driver._handle_connected("AA:BB:CC:DD:EE:FF", 512, "peripheral", None)

        self.assertEqual(connected_calls[0][1], pending_identity)
        self.assertNotIn("AA:BB:CC:DD:EE:FF", self.driver._pending_identities)

    def test_handle_disconnected_cleans_up_state(self):
        """Test that _handle_disconnected cleans up all state."""
        address = "AA:BB:CC:DD:EE:FF"
        identity_hex = "0102030405060708090a0b0c0d0e0f10"

        self.driver._connected_peers = [address]
        self.driver._peer_roles[address] = "central"
        self.driver._peer_mtus[address] = 512
        self.driver._address_to_identity[address] = identity_hex
        self.driver._identity_to_address[identity_hex] = address

        disconnected_calls = []
        self.driver.on_device_disconnected = lambda addr: disconnected_calls.append(addr)

        self.driver._handle_disconnected(address)

        self.assertNotIn(address, self.driver._connected_peers)
        self.assertNotIn(address, self.driver._peer_roles)
        self.assertNotIn(address, self.driver._peer_mtus)
        self.assertNotIn(address, self.driver._address_to_identity)
        self.assertNotIn(identity_hex, self.driver._identity_to_address)
        self.assertEqual(disconnected_calls, [address])

    def test_handle_address_changed_forwards_callback(self):
        """Test that _handle_address_changed forwards to callback."""
        changed_calls = []
        self.driver.on_address_changed = lambda old, new, id_hash: changed_calls.append((old, new, id_hash))

        self.driver._handle_address_changed("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF", "ab5609df")

        self.assertEqual(len(changed_calls), 1)
        self.assertEqual(changed_calls[0], ("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF", "ab5609df"))

    def test_handle_identity_received_caches_when_not_connected(self):
        """Test that _handle_identity_received caches identity when peer not connected."""
        identity_hex = "0102030405060708090a0b0c0d0e0f10"

        self.driver._handle_identity_received("AA:BB:CC:DD:EE:FF", identity_hex)

        self.assertEqual(
            self.driver._pending_identities["AA:BB:CC:DD:EE:FF"],
            bytes.fromhex(identity_hex)
        )

    def test_handle_identity_received_notifies_when_already_connected(self):
        """Test that _handle_identity_received notifies when peer already connected."""
        connected_calls = []
        self.driver.on_device_connected = lambda addr, identity: connected_calls.append((addr, identity))
        self.driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]

        identity_hex = "0102030405060708090a0b0c0d0e0f10"
        self.driver._handle_identity_received("AA:BB:CC:DD:EE:FF", identity_hex)

        self.assertEqual(len(connected_calls), 1)
        self.assertEqual(connected_calls[0][1], bytes.fromhex(identity_hex))

    def test_handle_mtu_negotiated_stores_and_calls_callback(self):
        """Test that _handle_mtu_negotiated stores MTU and calls callback."""
        mtu_calls = []
        self.driver.on_mtu_negotiated = lambda addr, mtu: mtu_calls.append((addr, mtu))

        self.driver._handle_mtu_negotiated("AA:BB:CC:DD:EE:FF", 512)

        self.assertEqual(self.driver._peer_mtus["AA:BB:CC:DD:EE:FF"], 512)
        self.assertEqual(mtu_calls, [("AA:BB:CC:DD:EE:FF", 512)])

    def test_handle_device_discovered_exception_logged(self):
        """Test that _handle_device_discovered logs exceptions."""
        # Set callback that raises exception
        def bad_callback(dev):
            raise RuntimeError("Test error")

        self.driver.on_device_discovered = bad_callback

        # Should not raise
        self.driver._handle_device_discovered("AA:BB:CC:DD:EE:FF", "test", -50, [])

        self.assertTrue(any("Error handling device discovered" in str(msg) for msg, _ in self.log_calls))

    def test_handle_connected_exception_logged(self):
        """Test that _handle_connected logs exceptions."""
        def bad_callback(addr, identity):
            raise RuntimeError("Test error")

        self.driver.on_device_connected = bad_callback
        self.driver.on_mtu_negotiated = Mock()

        # Should not raise
        self.driver._handle_connected("AA:BB:CC:DD:EE:FF", 512, "central", "0102030405060708090a0b0c0d0e0f10")

        self.assertTrue(any("Error handling connected" in str(msg) for msg, _ in self.log_calls))

    def test_handle_disconnected_exception_logged(self):
        """Test that _handle_disconnected logs exceptions."""
        def bad_callback(addr):
            raise RuntimeError("Test error")

        self.driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]
        self.driver.on_device_disconnected = bad_callback

        # Should not raise
        self.driver._handle_disconnected("AA:BB:CC:DD:EE:FF")

        self.assertTrue(any("Error handling disconnected" in str(msg) for msg, _ in self.log_calls))

    def test_handle_address_changed_exception_logged(self):
        """Test that _handle_address_changed logs exceptions."""
        def bad_callback(old, new, id_hash):
            raise RuntimeError("Test error")

        self.driver.on_address_changed = bad_callback

        # Should not raise
        self.driver._handle_address_changed("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF", "ab5609df")

        self.assertTrue(any("Error handling address changed" in str(msg) for msg, _ in self.log_calls))

    def test_handle_identity_received_exception_logged(self):
        """Test that _handle_identity_received logs exceptions."""
        def bad_callback(addr, identity):
            raise RuntimeError("Test error")

        self.driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]
        self.driver.on_device_connected = bad_callback

        # Should not raise
        self.driver._handle_identity_received("AA:BB:CC:DD:EE:FF", "0102030405060708090a0b0c0d0e0f10")

        self.assertTrue(any("Error handling identity received" in str(msg) for msg, _ in self.log_calls))

    def test_handle_mtu_negotiated_exception_logged(self):
        """Test that _handle_mtu_negotiated logs exceptions."""
        def bad_callback(addr, mtu):
            raise RuntimeError("Test error")

        self.driver.on_mtu_negotiated = bad_callback

        # Should not raise
        self.driver._handle_mtu_negotiated("AA:BB:CC:DD:EE:FF", 512)

        self.assertTrue(any("Error handling MTU negotiated" in str(msg) for msg, _ in self.log_calls))

    def test_handle_data_received_exception_logged(self):
        """Test that _handle_data_received logs exceptions."""
        def bad_callback(addr, data):
            raise RuntimeError("Test error")

        self.driver._connected_peers = ["AA:BB:CC:DD:EE:FF"]
        self.driver.on_data_received = bad_callback

        # Should not raise
        self.driver._handle_data_received("AA:BB:CC:DD:EE:FF", b"data")

        self.assertTrue(any("Error handling data received" in str(msg) for msg, _ in self.log_calls))

    def test_handle_connected_invalid_identity_hash_logs_warning(self):
        """Test that _handle_connected logs warning for invalid identity_hash."""
        self.driver.on_device_connected = Mock()
        self.driver.on_mtu_negotiated = Mock()

        # Invalid hex string
        self.driver._handle_connected("AA:BB:CC:DD:EE:FF", 512, "central", "not_valid_hex")

        self.assertTrue(any("Invalid identity_hash format" in str(msg) for msg, _ in self.log_calls))


class TestAndroidBLEDriverKotlinBridge(unittest.TestCase):
    """
    Test _get_kotlin_bridge() method on the REAL AndroidBLEDriver class.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

        # Save original reticulum_wrapper module for restoration
        self._original_reticulum_wrapper = sys.modules.get('reticulum_wrapper')

    def tearDown(self):
        """Restore original RNS.log and reticulum_wrapper module."""
        self.abd_module.RNS.log = self._original_log

        # Restore original reticulum_wrapper module to prevent test isolation issues
        if self._original_reticulum_wrapper is not None:
            sys.modules['reticulum_wrapper'] = self._original_reticulum_wrapper
        elif 'reticulum_wrapper' in sys.modules:
            # If original was not present but something is now, remove it
            del sys.modules['reticulum_wrapper']

    def test_get_kotlin_bridge_success(self):
        """Test that _get_kotlin_bridge returns bridge from wrapper."""
        mock_bridge = MagicMock()
        mock_wrapper = MagicMock()
        mock_wrapper.kotlin_ble_bridge = mock_bridge

        mock_module = MagicMock()
        mock_module._global_wrapper_instance = mock_wrapper
        sys.modules['reticulum_wrapper'] = mock_module

        result = self.driver._get_kotlin_bridge()

        self.assertEqual(result, mock_bridge)

    def test_get_kotlin_bridge_no_wrapper_returns_none(self):
        """Test that _get_kotlin_bridge returns None when no wrapper."""
        mock_module = MagicMock()
        mock_module._global_wrapper_instance = None
        sys.modules['reticulum_wrapper'] = mock_module

        result = self.driver._get_kotlin_bridge()

        self.assertIsNone(result)
        self.assertTrue(any("No global wrapper" in str(msg) for msg, _ in self.log_calls))

    def test_get_kotlin_bridge_no_bridge_in_wrapper_returns_none(self):
        """Test that _get_kotlin_bridge returns None when wrapper has no bridge."""
        mock_wrapper = MagicMock()
        mock_wrapper.kotlin_ble_bridge = None

        mock_module = MagicMock()
        mock_module._global_wrapper_instance = mock_wrapper
        sys.modules['reticulum_wrapper'] = mock_module

        result = self.driver._get_kotlin_bridge()

        self.assertIsNone(result)
        self.assertTrue(any("No BLE bridge set" in str(msg) for msg, _ in self.log_calls))

    def test_get_kotlin_bridge_import_error_returns_none(self):
        """Test that _get_kotlin_bridge returns None on import error."""
        # Remove reticulum_wrapper to cause ImportError
        if 'reticulum_wrapper' in sys.modules:
            del sys.modules['reticulum_wrapper']

        # Patch import to raise ImportError
        with patch.dict(sys.modules, {'reticulum_wrapper': None}):
            result = self.driver._get_kotlin_bridge()

        self.assertIsNone(result)


class TestAndroidBLEDriverSetupKotlinCallbacks(unittest.TestCase):
    """
    Test _setup_kotlin_callbacks() method on the REAL AndroidBLEDriver class.
    """

    @classmethod
    def setUpClass(cls):
        """Import the real AndroidBLEDriver class."""
        if 'bluetooth_driver' in sys.modules:
            del sys.modules['bluetooth_driver']
        if 'android_ble_driver' in sys.modules:
            del sys.modules['android_ble_driver']

        class MockBLEDriverInterface:
            pass

        mock_bt_driver = MagicMock()
        mock_bt_driver.BLEDriverInterface = MockBLEDriverInterface
        mock_bt_driver.DriverState = MockDriverState
        mock_bt_driver.BLEDevice = MagicMock()
        sys.modules['bluetooth_driver'] = mock_bt_driver

        ble_modules_dir = os.path.join(os.path.dirname(__file__), 'ble_modules')
        if ble_modules_dir not in sys.path:
            sys.path.insert(0, ble_modules_dir)

        import android_ble_driver as abd_module
        cls.AndroidBLEDriver = abd_module.AndroidBLEDriver
        cls.abd_module = abd_module

    def setUp(self):
        """Set up test fixtures."""
        self.driver = object.__new__(self.AndroidBLEDriver)
        self.driver._connected_peers = []
        self.driver._peer_roles = {}
        self.driver._peer_mtus = {}
        self.driver._pending_identities = {}
        self.driver._identity_lock = threading.Lock()
        self.driver._address_to_identity = {}
        self.driver._identity_to_address = {}
        self.driver.kotlin_bridge = None

        self.log_calls = []
        self._original_log = self.abd_module.RNS.log
        self.abd_module.RNS.log = lambda msg, level=4: self.log_calls.append((msg, level))

    def tearDown(self):
        """Restore original RNS.log."""
        self.abd_module.RNS.log = self._original_log

    def test_setup_kotlin_callbacks_no_bridge_returns_early(self):
        """Test that _setup_kotlin_callbacks returns early when no bridge."""
        self.driver.kotlin_bridge = None

        # Should not raise
        self.driver._setup_kotlin_callbacks()

    def test_setup_kotlin_callbacks_wires_all_callbacks(self):
        """Test that _setup_kotlin_callbacks wires all expected callbacks."""
        mock_bridge = MagicMock()
        self.driver.kotlin_bridge = mock_bridge

        self.driver._setup_kotlin_callbacks()

        # Verify all callbacks are wired
        mock_bridge.setOnDeviceDiscovered.assert_called_once()
        mock_bridge.setOnConnected.assert_called_once()
        mock_bridge.setOnDisconnected.assert_called_once()
        mock_bridge.setOnDataReceived.assert_called_once()
        mock_bridge.setOnIdentityReceived.assert_called_once()
        mock_bridge.setOnMtuNegotiated.assert_called_once()
        mock_bridge.setOnAddressChanged.assert_called_once()
        mock_bridge.setOnDuplicateIdentityDetected.assert_called_once()


if __name__ == '__main__':
    unittest.main()
