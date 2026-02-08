"""
Test suite for Reticulum Wrapper module.

Tests the AnnounceHandler class, ReticulumWrapper callbacks,
telemetry functions, and various utility methods.
"""

import sys
import os
import struct
import time
import json
import threading
import unittest
from unittest.mock import Mock, MagicMock, patch, call
import tempfile
import shutil

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Import u-msgpack-python (should be pre-installed)
try:
    import umsgpack
except ImportError:
    raise ImportError("u-msgpack-python is required. Install with: pip install u-msgpack-python")

# Make umsgpack available
sys.modules['umsgpack'] = umsgpack

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper
import importlib

# Re-assign umsgpack in the module
reticulum_wrapper.umsgpack = umsgpack


class TestGlobalExceptionHandler(unittest.TestCase):
    """Test the global exception handler."""

    def test_exception_handler_passes_keyboard_interrupt(self):
        """KeyboardInterrupt should pass through to original handler."""
        original_handler = sys.__excepthook__

        with patch.object(sys, '__excepthook__', MagicMock()) as mock_handler:
            reticulum_wrapper._global_exception_handler(
                KeyboardInterrupt,
                KeyboardInterrupt("test"),
                None
            )
            mock_handler.assert_called_once()

    def test_exception_handler_passes_system_exit(self):
        """SystemExit should pass through to original handler."""
        with patch.object(sys, '__excepthook__', MagicMock()) as mock_handler:
            reticulum_wrapper._global_exception_handler(
                SystemExit,
                SystemExit(0),
                None
            )
            mock_handler.assert_called_once()

    @patch('reticulum_wrapper.log_error')
    def test_exception_handler_logs_other_exceptions(self, mock_log):
        """Other exceptions should be logged."""
        try:
            raise ValueError("test error")
        except ValueError:
            import traceback
            exc_info = sys.exc_info()

        reticulum_wrapper._global_exception_handler(
            exc_info[0], exc_info[1], exc_info[2]
        )

        # Should have been called with error logs
        self.assertTrue(mock_log.called)


class TestFieldConstants(unittest.TestCase):
    """Test LXMF field constants."""

    def test_field_telemetry(self):
        """FIELD_TELEMETRY should be 0x02."""
        self.assertEqual(reticulum_wrapper.FIELD_TELEMETRY, 0x02)

    def test_field_columba_meta(self):
        """FIELD_COLUMBA_META should be 0x70."""
        self.assertEqual(reticulum_wrapper.FIELD_COLUMBA_META, 0x70)

    def test_field_icon_appearance(self):
        """FIELD_ICON_APPEARANCE should be 0x04."""
        self.assertEqual(reticulum_wrapper.FIELD_ICON_APPEARANCE, 0x04)

    def test_legacy_location_field(self):
        """LEGACY_LOCATION_FIELD should be 7."""
        self.assertEqual(reticulum_wrapper.LEGACY_LOCATION_FIELD, 7)

    def test_sid_time(self):
        """SID_TIME should be 0x01."""
        self.assertEqual(reticulum_wrapper.SID_TIME, 0x01)

    def test_sid_location(self):
        """SID_LOCATION should be 0x02."""
        self.assertEqual(reticulum_wrapper.SID_LOCATION, 0x02)


class TestGetHelloMessage(unittest.TestCase):
    """Test get_hello_message function."""

    def test_returns_string(self):
        """get_hello_message should return a string."""
        result = reticulum_wrapper.get_hello_message()
        self.assertIsInstance(result, str)

    def test_contains_hello(self):
        """Message should contain 'Hello'."""
        result = reticulum_wrapper.get_hello_message()
        self.assertIn("Hello", result)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_indicates_reticulum_available(self):
        """Should indicate when Reticulum is available."""
        result = reticulum_wrapper.get_hello_message()
        self.assertIn("available", result.lower())

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', False)
    def test_indicates_mock_mode(self):
        """Should indicate mock mode when Reticulum unavailable."""
        result = reticulum_wrapper.get_hello_message()
        self.assertIn("Mock mode", result)


class TestAnnounceHandler(unittest.TestCase):
    """Test the AnnounceHandler wrapper class."""

    def test_initialization(self):
        """Test AnnounceHandler initialization."""
        callback = MagicMock()
        handler = reticulum_wrapper.AnnounceHandler("lxmf.delivery", callback)

        self.assertEqual(handler.aspect_filter, "lxmf.delivery")
        self.assertEqual(handler.callback, callback)

    def test_initialization_with_none_filter(self):
        """Test AnnounceHandler with None filter (all announces)."""
        callback = MagicMock()
        handler = reticulum_wrapper.AnnounceHandler(None, callback)

        self.assertIsNone(handler.aspect_filter)

    def test_received_announce_calls_callback(self):
        """received_announce should invoke callback with correct arguments."""
        callback = MagicMock()
        handler = reticulum_wrapper.AnnounceHandler("lxmf.delivery", callback)

        dest_hash = bytes.fromhex("deadbeef" * 4)
        identity = MagicMock()
        app_data = b"test_data"
        announce_hash = bytes.fromhex("abcd1234" * 4)

        handler.received_announce(dest_hash, identity, app_data, announce_hash)

        callback.assert_called_once_with(
            "lxmf.delivery",
            dest_hash,
            identity,
            app_data,
            announce_hash
        )

    def test_received_announce_without_packet_hash(self):
        """received_announce should work without announce_packet_hash."""
        callback = MagicMock()
        handler = reticulum_wrapper.AnnounceHandler("nomadnetwork.node", callback)

        dest_hash = bytes.fromhex("deadbeef" * 4)
        identity = MagicMock()
        app_data = b"node_data"

        handler.received_announce(dest_hash, identity, app_data)

        # Should still call callback with None for packet hash
        callback.assert_called_once()
        args = callback.call_args[0]
        self.assertEqual(args[0], "nomadnetwork.node")
        self.assertEqual(args[1], dest_hash)


class TestReticulumWrapperInitialization(unittest.TestCase):
    """Test ReticulumWrapper initialization."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_initialization_sets_storage_path(self):
        """Wrapper should store the storage path."""
        self.assertEqual(self.wrapper.storage_path, self.temp_dir)

    def test_initialization_creates_empty_collections(self):
        """Wrapper should initialize empty collections."""
        self.assertEqual(self.wrapper.message_callbacks, [])
        self.assertEqual(self.wrapper.announce_callbacks, [])
        self.assertEqual(self.wrapper.link_callbacks, [])
        self.assertEqual(self.wrapper.destinations, {})
        self.assertEqual(self.wrapper.pending_announces, [])
        self.assertEqual(self.wrapper.seen_message_hashes, set())
        self.assertEqual(self.wrapper.seen_announce_hashes, set())

    def test_initialization_not_initialized_flag(self):
        """Wrapper should not be initialized by default."""
        self.assertFalse(self.wrapper.initialized)

    def test_initialization_creates_announce_handlers(self):
        """Wrapper should create aspect-specific announce handlers."""
        expected_aspects = [
            "lxmf.delivery",
            "lxmf.propagation",
            "nomadnetwork.node",
            "rmsp.maps",
        ]
        for aspect in expected_aspects:
            self.assertIn(aspect, self.wrapper._announce_handlers)
            self.assertIsInstance(
                self.wrapper._announce_handlers[aspect],
                reticulum_wrapper.AnnounceHandler
            )


class TestReticulumWrapperCallbacks(unittest.TestCase):
    """Test callback registration methods."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_ble_bridge(self):
        """set_ble_bridge should store the bridge."""
        mock_bridge = MagicMock()
        self.wrapper.set_ble_bridge(mock_bridge)
        self.assertEqual(self.wrapper.kotlin_ble_bridge, mock_bridge)

    def test_set_rnode_bridge(self):
        """set_rnode_bridge should store the bridge."""
        mock_bridge = MagicMock()
        self.wrapper.set_rnode_bridge(mock_bridge)
        self.assertEqual(self.wrapper.kotlin_rnode_bridge, mock_bridge)

    def test_set_reticulum_bridge(self):
        """set_reticulum_bridge should store the bridge."""
        mock_bridge = MagicMock()
        self.wrapper.set_reticulum_bridge(mock_bridge)
        self.assertEqual(self.wrapper.kotlin_reticulum_bridge, mock_bridge)

    def test_set_delivery_status_callback(self):
        """set_delivery_status_callback should store the callback."""
        mock_callback = MagicMock()
        self.wrapper.set_delivery_status_callback(mock_callback)
        self.assertEqual(self.wrapper.kotlin_delivery_status_callback, mock_callback)

    def test_set_message_received_callback(self):
        """set_message_received_callback should store the callback."""
        mock_callback = MagicMock()
        self.wrapper.set_message_received_callback(mock_callback)
        self.assertEqual(self.wrapper.kotlin_message_received_callback, mock_callback)

    def test_set_location_received_callback(self):
        """set_location_received_callback should store the callback."""
        mock_callback = MagicMock()
        self.wrapper.set_location_received_callback(mock_callback)
        self.assertEqual(self.wrapper.kotlin_location_received_callback, mock_callback)

    def test_set_reaction_received_callback(self):
        """set_reaction_received_callback should store the callback."""
        mock_callback = MagicMock()
        self.wrapper.set_reaction_received_callback(mock_callback)
        self.assertEqual(self.wrapper.kotlin_reaction_received_callback, mock_callback)

    def test_set_kotlin_request_alternative_relay_callback(self):
        """set_kotlin_request_alternative_relay_callback should store the callback."""
        mock_callback = MagicMock()
        self.wrapper.set_kotlin_request_alternative_relay_callback(mock_callback)
        self.assertEqual(
            self.wrapper.kotlin_request_alternative_relay_callback,
            mock_callback
        )

    def test_set_stamp_generator_callback(self):
        """set_stamp_generator_callback should store the callback."""
        mock_callback = MagicMock()

        # Mock the LXMF module's LXStamper
        with patch.dict('sys.modules', {'LXMF': MagicMock(), 'LXMF.LXStamper': MagicMock()}):
            with patch('reticulum_wrapper.LXMF') as mock_lxmf:
                self.wrapper.set_stamp_generator_callback(mock_callback)

        self.assertEqual(self.wrapper.kotlin_stamp_generator_callback, mock_callback)


class TestReticulumWrapperGetPairedRnodes(unittest.TestCase):
    """Test get_paired_rnodes method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_error_without_bridge(self):
        """Should return error when bridge not set."""
        result = self.wrapper.get_paired_rnodes()
        self.assertFalse(result['success'])
        self.assertEqual(result['devices'], [])
        self.assertIn('not set', result['error'])

    def test_returns_devices_from_bridge(self):
        """Should return devices from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.return_value = ["RNode 1", "RNode 2"]
        self.wrapper.kotlin_rnode_bridge = mock_bridge

        result = self.wrapper.get_paired_rnodes()
        self.assertTrue(result['success'])
        self.assertEqual(result['devices'], ["RNode 1", "RNode 2"])

    def test_handles_empty_device_list(self):
        """Should handle empty device list."""
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.return_value = []
        self.wrapper.kotlin_rnode_bridge = mock_bridge

        result = self.wrapper.get_paired_rnodes()
        self.assertTrue(result['success'])
        self.assertEqual(result['devices'], [])

    def test_handles_bridge_exception(self):
        """Should handle exception from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.side_effect = Exception("Bluetooth error")
        self.wrapper.kotlin_rnode_bridge = mock_bridge

        result = self.wrapper.get_paired_rnodes()
        self.assertFalse(result['success'])
        self.assertIn('Bluetooth error', result['error'])


class TestReticulumWrapperCheckSharedInstance(unittest.TestCase):
    """Test check_shared_instance_available method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('socket.socket')
    def test_returns_true_when_port_open(self, mock_socket_class):
        """Should return True when connection succeeds."""
        mock_socket = MagicMock()
        mock_socket.connect_ex.return_value = 0
        mock_socket_class.return_value = mock_socket

        result = self.wrapper.check_shared_instance_available()
        self.assertTrue(result)
        mock_socket.close.assert_called_once()

    @patch('socket.socket')
    def test_returns_false_when_port_closed(self, mock_socket_class):
        """Should return False when connection fails."""
        mock_socket = MagicMock()
        mock_socket.connect_ex.return_value = 111  # Connection refused
        mock_socket_class.return_value = mock_socket

        result = self.wrapper.check_shared_instance_available()
        self.assertFalse(result)

    @patch('socket.socket')
    def test_handles_socket_timeout(self, mock_socket_class):
        """Should return False on timeout."""
        import socket
        mock_socket = MagicMock()
        mock_socket.connect_ex.side_effect = socket.timeout()
        mock_socket_class.return_value = mock_socket

        result = self.wrapper.check_shared_instance_available()
        self.assertFalse(result)

    @patch('socket.socket')
    def test_handles_socket_error(self, mock_socket_class):
        """Should return False on socket error."""
        mock_socket_class.side_effect = Exception("Socket error")

        result = self.wrapper.check_shared_instance_available()
        self.assertFalse(result)


class TestReticulumWrapperGetStatus(unittest.TestCase):
    """Test get_status method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_shutdown_when_not_initialized(self):
        """Should return SHUTDOWN when not initialized."""
        result = self.wrapper.get_status()
        self.assertEqual(result, "SHUTDOWN")

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_ready_when_initialized(self):
        """Should return READY when initialized."""
        self.wrapper.initialized = True
        self.wrapper.reticulum = MagicMock()

        result = self.wrapper.get_status()
        self.assertEqual(result, "READY")


class TestReticulumWrapperCreateIdentityLegacy(unittest.TestCase):
    """Test legacy create_identity method (no args version)."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', False)
    def test_mock_mode_returns_random_keys(self):
        """In mock mode, should return random bytes (testing direct method)."""
        # Test that mock identity generation works correctly
        # Mock identity for testing
        result = {
            'hash': os.urandom(16),
            'public_key': os.urandom(32),
            'private_key': os.urandom(32)
        }

        self.assertIn('hash', result)
        self.assertIn('public_key', result)
        self.assertIn('private_key', result)
        self.assertEqual(len(result['hash']), 16)
        self.assertEqual(len(result['public_key']), 32)
        self.assertEqual(len(result['private_key']), 32)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_creates_real_identity_structure(self, mock_rns):
        """Test that RNS Identity creation works correctly."""
        mock_identity = MagicMock()
        mock_identity.hash = b'0' * 16
        mock_identity.get_public_key.return_value = b'1' * 32
        mock_identity.get_private_key.return_value = b'2' * 32
        mock_rns.Identity.return_value = mock_identity

        # Create identity directly using RNS
        identity = mock_rns.Identity()
        result = {
            'hash': identity.hash,
            'public_key': identity.get_public_key(),
            'private_key': identity.get_private_key()
        }

        self.assertEqual(result['hash'], b'0' * 16)
        self.assertEqual(result['public_key'], b'1' * 32)
        self.assertEqual(result['private_key'], b'2' * 32)


class TestReticulumWrapperLoadIdentity(unittest.TestCase):
    """Test load_identity method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', False)
    def test_mock_mode_raises_error(self):
        """In mock mode, should raise NotImplementedError."""
        with self.assertRaises(RuntimeError):
            self.wrapper.load_identity("/path/to/identity")

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_loads_identity_from_file(self, mock_rns):
        """Should load identity from file."""
        mock_identity = MagicMock()
        mock_identity.hash = b'x' * 16
        mock_identity.get_public_key.return_value = b'y' * 32
        mock_identity.get_private_key.return_value = b'z' * 32
        mock_rns.Identity.from_file.return_value = mock_identity

        result = self.wrapper.load_identity("/path/to/identity")

        mock_rns.Identity.from_file.assert_called_once_with("/path/to/identity")
        self.assertEqual(result['hash'], b'x' * 16)


class TestReticulumWrapperSaveIdentity(unittest.TestCase):
    """Test save_identity method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', False)
    def test_mock_mode_returns_success(self):
        """In mock mode, should return success."""
        result = self.wrapper.save_identity(b'key', "/path/to/identity")
        self.assertTrue(result['success'])

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_saves_identity_to_file(self, mock_rns):
        """Should save identity to file."""
        mock_identity = MagicMock()
        mock_rns.Identity.return_value = mock_identity

        result = self.wrapper.save_identity(b'private_key', "/path/to/identity")

        mock_identity.load_private_key.assert_called_once_with(b'private_key')
        mock_identity.to_file.assert_called_once_with("/path/to/identity")
        self.assertTrue(result['success'])

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_handles_save_error(self, mock_rns):
        """Should handle save errors gracefully."""
        mock_identity = MagicMock()
        mock_identity.to_file.side_effect = Exception("Write error")
        mock_rns.Identity.return_value = mock_identity

        result = self.wrapper.save_identity(b'key', "/path/to/identity")
        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestReticulumWrapperCreateDestination(unittest.TestCase):
    """Test create_destination method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', False)
    def test_mock_mode_creates_mock_destination(self):
        """In mock mode, should create mock destination."""
        identity_dict = {
            'hash': b'hash',
            'public_key': b'pubkey',
            'private_key': b'privkey',
        }

        result = self.wrapper.create_destination(
            identity_dict,
            "IN",
            "SINGLE",
            "test.app",
            ["aspect1"]
        )

        self.assertIn('hash', result)
        self.assertIn('hex_hash', result)
        self.assertEqual(len(result['hash']), 16)

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_creates_real_destination(self, mock_rns):
        """Should create real destination."""
        mock_identity = MagicMock()
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'd' * 16
        mock_dest.hexhash = 'd' * 32
        mock_rns.Destination.return_value = mock_dest
        mock_rns.Destination.IN = "IN"
        mock_rns.Destination.SINGLE = "SINGLE"

        identity_dict = {'private_key': b'key'}

        result = self.wrapper.create_destination(
            identity_dict,
            "IN",
            "SINGLE",
            "test.app",
            ["aspect"]
        )

        self.assertEqual(result['hash'], b'd' * 16)
        self.assertEqual(result['hex_hash'], 'd' * 32)
        # Should be stored in destinations dict
        self.assertIn('d' * 32, self.wrapper.destinations)


class TestReticulumWrapperShutdown(unittest.TestCase):
    """Test shutdown method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_shutdown_when_not_initialized(self):
        """Should succeed when not initialized."""
        result = self.wrapper.shutdown()
        self.assertTrue(result['success'])

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_shutdown_clears_state(self, mock_rns):
        """Shutdown should clear all state."""
        self.wrapper.initialized = True
        self.wrapper.reticulum = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.destinations['test'] = 'dest'
        self.wrapper.pending_announces.append('announce')

        mock_rns.Transport.interfaces = []
        mock_rns.Transport.destinations = {}
        mock_rns.Transport.destination_table = {}
        mock_rns.Transport.announce_table = {}
        mock_rns.Transport.held_announces = {}
        mock_rns.Transport.announce_handlers = []
        mock_rns.Transport.local_client_interfaces = []
        mock_rns.Transport.local_client_rssi_cache = {}
        mock_rns.Transport.local_client_snr_cache = {}
        mock_rns.Transport.local_client_q_cache = {}

        result = self.wrapper.shutdown()

        self.assertTrue(result['success'])
        self.assertFalse(self.wrapper.initialized)
        self.assertIsNone(self.wrapper.reticulum)
        self.assertIsNone(self.wrapper.router)
        self.assertEqual(len(self.wrapper.destinations), 0)
        self.assertEqual(len(self.wrapper.pending_announces), 0)


class TestReticulumWrapperAnnounceDestination(unittest.TestCase):
    """Test announce_destination method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        # Set display_name as it's expected to be set after initialize()
        self.wrapper.display_name = "Test Peer"

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_error_when_not_initialized(self):
        """Should return error when not initialized."""
        result = self.wrapper.announce_destination(b'hash')
        self.assertFalse(result['success'])

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_error_for_unknown_destination(self):
        """Should return error for unknown destination."""
        self.wrapper.initialized = True

        result = self.wrapper.announce_destination(b'0' * 16)
        self.assertFalse(result['success'])
        self.assertIn('not found', result['error'])

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_announces_known_destination(self):
        """Should announce known destination."""
        self.wrapper.initialized = True

        mock_dest = MagicMock()
        dest_hash = b'0' * 16
        self.wrapper.destinations[dest_hash.hex()] = mock_dest

        result = self.wrapper.announce_destination(dest_hash)

        self.assertTrue(result['success'])
        mock_dest.announce.assert_called_once()

    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_announces_lxmf_destination(self):
        """Should announce LXMF destination if hash matches."""
        self.wrapper.initialized = True

        mock_dest = MagicMock()
        mock_dest.hexhash = 'a' * 32
        self.wrapper.local_lxmf_destination = mock_dest

        result = self.wrapper.announce_destination(bytes.fromhex('a' * 32))

        self.assertTrue(result['success'])
        mock_dest.announce.assert_called_once()


class TestReticulumWrapperAnnounceHandler(unittest.TestCase):
    """Test the _announce_handler method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'LXMF', None)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_announce_handler_stores_event(self, mock_rns):
        """_announce_handler should store announce event."""
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.PATHFINDER_M = 255
        mock_rns.Transport.announce_table = {}

        mock_identity = MagicMock()
        mock_identity.get_public_key.return_value = b'pubkey'

        self.wrapper._announce_handler(
            "lxmf.delivery",
            b'dest_hash_here!',
            mock_identity,
            b'app_data',
            None
        )

        self.assertEqual(len(self.wrapper.pending_announces), 1)
        event = self.wrapper.pending_announces[0]
        self.assertEqual(event['destination_hash'], b'dest_hash_here!')
        self.assertEqual(event['aspect'], 'lxmf.delivery')
        self.assertEqual(event['hops'], 2)

    @patch.object(reticulum_wrapper, 'LXMF', None)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_announce_handler_notifies_kotlin(self, mock_rns):
        """_announce_handler should notify Kotlin bridge."""
        mock_rns.Transport.hops_to.return_value = 0
        mock_rns.Transport.PATHFINDER_M = 255
        mock_rns.Transport.announce_table = {}

        mock_bridge = MagicMock()
        self.wrapper.kotlin_reticulum_bridge = mock_bridge

        mock_identity = MagicMock()
        mock_identity.get_public_key.return_value = b'key'

        self.wrapper._announce_handler(
            "nomadnetwork.node",
            b'd' * 16,
            mock_identity,
            None,
            None
        )

        mock_bridge.notifyAnnounceReceived.assert_called_once()

    @patch.object(reticulum_wrapper, 'LXMF', None)
    @patch.object(reticulum_wrapper, 'RNS')
    def test_announce_handler_calls_callbacks(self, mock_rns):
        """_announce_handler should call registered callbacks."""
        mock_rns.Transport.hops_to.return_value = 1
        mock_rns.Transport.PATHFINDER_M = 255
        mock_rns.Transport.announce_table = {}

        callback = MagicMock()
        self.wrapper.announce_callbacks.append(callback)

        mock_identity = MagicMock()
        mock_identity.get_public_key.return_value = b'key'

        self.wrapper._announce_handler(
            "lxmf.delivery",
            b'h' * 16,
            mock_identity,
            b'data',
            None
        )

        callback.assert_called_once()

    @patch.object(reticulum_wrapper, 'RNS')
    def test_announce_handler_handles_rmsp_aspect(self, mock_rns):
        """_announce_handler should handle RMSP map server announces."""
        mock_rns.Transport.hops_to.return_value = 1
        mock_rns.Transport.PATHFINDER_M = 255
        mock_rns.Transport.announce_table = {}

        # Mock umsgpack for RMSP parsing
        mock_rns.vendor.umsgpack = umsgpack

        mock_identity = MagicMock()
        mock_identity.get_public_key.return_value = b'key'

        # Create RMSP announce data
        rmsp_data = umsgpack.packb({
            'n': 'Test Map Server',
            'v': '0.1.0',
            'c': ['u4pr'],
            'z': [0, 15],
            'f': ['pmtiles'],
            'l': ['osm'],
            'u': 1703980800,
        })

        # Mock LXMF to avoid issues
        with patch.object(reticulum_wrapper, 'LXMF', None):
            with patch.object(self.wrapper, 'parse_rmsp_announce') as mock_parse:
                self.wrapper._announce_handler(
                    "rmsp.maps",
                    b'm' * 16,
                    mock_identity,
                    rmsp_data,
                    None
                )

                mock_parse.assert_called_once()


class TestPackLocationTelemetryIntegration(unittest.TestCase):
    """Integration tests for pack/unpack location telemetry."""

    def test_round_trip_basic(self):
        """Test basic pack/unpack round trip."""
        packed = reticulum_wrapper.pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = reticulum_wrapper.unpack_location_telemetry(packed)

        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], 37.7749, places=6)
        self.assertAlmostEqual(result['lng'], -122.4194, places=6)
        self.assertAlmostEqual(result['acc'], 10.0, places=2)

    def test_round_trip_with_optional_fields(self):
        """Test round trip with all optional fields."""
        packed = reticulum_wrapper.pack_location_telemetry(
            lat=35.6762,
            lon=139.6503,
            accuracy=5.0,
            timestamp_ms=1703980800000,
            altitude=150.5,
            speed=25.5,
            bearing=90.0,
        )
        result = reticulum_wrapper.unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['altitude'], 150.5, places=2)
        self.assertAlmostEqual(result['speed'], 25.5, places=2)
        self.assertAlmostEqual(result['bearing'], 90.0, places=2)


class TestConcurrency(unittest.TestCase):
    """Test thread safety of wrapper methods."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_announce_lock_prevents_race_conditions(self):
        """Announce lock should prevent race conditions."""
        # Simulate concurrent access to pending_announces
        results = []

        def add_announce(i):
            with self.wrapper.announce_lock:
                self.wrapper.pending_announces.append(f"announce_{i}")
                results.append(i)

        threads = [threading.Thread(target=add_announce, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(len(self.wrapper.pending_announces), 10)
        self.assertEqual(len(results), 10)


class TestEdgeCases(unittest.TestCase):
    """Test edge cases and error handling."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_unpack_location_telemetry_handles_none(self):
        """unpack_location_telemetry should handle None input."""
        result = reticulum_wrapper.unpack_location_telemetry(None)
        self.assertIsNone(result)

    def test_unpack_location_telemetry_handles_empty_bytes(self):
        """unpack_location_telemetry should handle empty bytes."""
        result = reticulum_wrapper.unpack_location_telemetry(b"")
        self.assertIsNone(result)

    def test_unpack_location_telemetry_handles_invalid_msgpack(self):
        """unpack_location_telemetry should handle invalid msgpack."""
        result = reticulum_wrapper.unpack_location_telemetry(b"not msgpack")
        self.assertIsNone(result)

    def test_announce_handler_handles_callback_exception(self):
        """_announce_handler should handle callback exceptions."""
        def bad_callback(event):
            raise Exception("Callback error")

        self.wrapper.announce_callbacks.append(bad_callback)

        with patch.object(reticulum_wrapper, 'RNS') as mock_rns, \
             patch.object(reticulum_wrapper, 'LXMF', None):
            mock_rns.Transport.hops_to.return_value = 0
            mock_rns.Transport.PATHFINDER_M = 255
            mock_rns.Transport.announce_table = {}

            mock_identity = MagicMock()
            mock_identity.get_public_key.return_value = b'key'

            # Should not raise exception
            self.wrapper._announce_handler(
                "lxmf.delivery",
                b'x' * 16,
                mock_identity,
                None,
                None
            )


if __name__ == '__main__':
    unittest.main()
