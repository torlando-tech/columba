"""
Test suite for ReticulumWrapper announce polling functionality.
Tests the poll_received_announces() method which polls RNS.Transport.announce_table
for new announces and returns them as a list of dictionaries.
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch
import tempfile
import shutil
import time

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestPollReceivedAnnounces(unittest.TestCase):
    """Test announce polling functionality"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        # Clear seen announce hashes
        self.wrapper.seen_announce_hashes.clear()

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_poll_with_no_announces_returns_empty_list(self, mock_rns):
        """Test that polling with an empty announce_table returns an empty list"""
        mock_rns.Transport.announce_table = {}

        result = self.wrapper.poll_received_announces()

        self.assertEqual(result, [])
        self.assertIsInstance(result, list)

    def test_poll_when_not_initialized_returns_empty_list(self):
        """Test that polling when not initialized returns an empty list"""
        self.wrapper.initialized = False

        result = self.wrapper.poll_received_announces()

        self.assertEqual(result, [])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_poll_when_reticulum_not_available_returns_empty_list(self):
        """Test that polling when RETICULUM_AVAILABLE is False returns an empty list"""
        result = self.wrapper.poll_received_announces()

        self.assertEqual(result, [])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_poll_with_no_announce_table_attribute_returns_empty_list(self, mock_rns):
        """Test that polling when RNS.Transport has no announce_table returns an empty list"""
        # Remove announce_table attribute
        delattr(mock_rns.Transport, 'announce_table')

        result = self.wrapper.poll_received_announces()

        self.assertEqual(result, [])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_poll_with_valid_announce_returns_list_of_dicts(self, mock_rns):
        """Test that polling with a valid announce returns a list with announce dictionaries"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = b'\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x20'
        mock_identity.get_public_key = MagicMock(return_value=b'mock_public_key_data')

        # Create interface with proper .name attribute
        # Code builds "ClassName[UserConfiguredName]" from type().__name__ and .name
        class TCPClientInterface:
            name = "Testnet/192.168.1.100:4242"  # Just the user-configured name
        mock_interface = TCPClientInterface()
        mock_packet = MagicMock()
        mock_packet.receiving_interface = mock_interface

        announce_entry = [time.time(), 0, 0, None, 0, mock_packet]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'app_data_content')
        mock_rns.Transport.hops_to = MagicMock(return_value=3)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertIsInstance(result[0], dict)

        announce = result[0]
        self.assertEqual(announce['destination_hash'], dest_hash)
        self.assertEqual(announce['identity_hash'], mock_identity.hash)
        self.assertEqual(announce['public_key'], b'mock_public_key_data')
        self.assertEqual(announce['app_data'], b'app_data_content')
        self.assertEqual(announce['hops'], 3)
        self.assertEqual(announce['interface'], "TCPClientInterface[Testnet/192.168.1.100:4242]")
        self.assertIn('timestamp', announce)
        self.assertIsInstance(announce['timestamp'], int)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_deduplication_same_announce_not_returned_twice(self, mock_rns):
        """Test that the same announce hash is not returned on subsequent polls"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        # First poll should return the announce
        result1 = self.wrapper.poll_received_announces()
        self.assertEqual(len(result1), 1)

        # Second poll should return empty list (same announce already seen)
        result2 = self.wrapper.poll_received_announces()
        self.assertEqual(len(result2), 0)

        # Verify the hash was added to seen_announce_hashes
        hash_hex = dest_hash.hex()
        self.assertIn(hash_hex, self.wrapper.seen_announce_hashes)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_identity_gracefully(self, mock_rns):
        """Test that announces with missing identities are handled gracefully"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(side_effect=Exception("Identity not found"))
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        announce = result[0]
        # When identity is missing, identity_hash should fallback to dest_hash
        self.assertEqual(announce['identity_hash'], dest_hash)
        self.assertEqual(announce['public_key'], b'')

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_app_data_gracefully(self, mock_rns):
        """Test that announces with missing app_data are handled gracefully"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'pubkey')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(side_effect=Exception("App data not found"))
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        announce = result[0]
        self.assertEqual(announce['app_data'], b'')

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_none_hops_defaults_to_zero(self, mock_rns):
        """Test that None hops value defaults to 0"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=None)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['hops'], 0)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_pathfinder_m_hops_defaults_to_zero(self, mock_rns):
        """Test that PATHFINDER_M hops value defaults to 0"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Transport.PATHFINDER_M = 128
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=128)  # PATHFINDER_M value

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['hops'], 0)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_receiving_interface_gracefully(self, mock_rns):
        """Test that announces with missing receiving_interface are handled gracefully"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        # Announce entry with less than 6 elements (no packet)
        announce_entry = [time.time(), 0, 0, None, 0]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertIsNone(result[0]['interface'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_packet_without_receiving_interface_attribute(self, mock_rns):
        """Test that packets without receiving_interface attribute are handled gracefully"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        # Mock packet without receiving_interface attribute
        mock_packet = MagicMock(spec=[])  # Empty spec means no attributes

        announce_entry = [time.time(), 0, 0, None, 0, mock_packet]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertIsNone(result[0]['interface'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_multiple_announces(self, mock_rns):
        """Test that multiple announces are all returned in one poll"""
        dest_hash1 = b'\x01' * 16
        dest_hash2 = b'\x02' * 16
        dest_hash3 = b'\x03' * 16

        mock_identity1 = MagicMock()
        mock_identity1.hash = dest_hash1
        mock_identity1.get_public_key = MagicMock(return_value=b'key1')

        mock_identity2 = MagicMock()
        mock_identity2.hash = dest_hash2
        mock_identity2.get_public_key = MagicMock(return_value=b'key2')

        mock_identity3 = MagicMock()
        mock_identity3.hash = dest_hash3
        mock_identity3.get_public_key = MagicMock(return_value=b'key3')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {
            dest_hash1: announce_entry,
            dest_hash2: announce_entry,
            dest_hash3: announce_entry,
        }

        def recall_side_effect(dest_hash):
            if dest_hash == dest_hash1:
                return mock_identity1
            elif dest_hash == dest_hash2:
                return mock_identity2
            elif dest_hash == dest_hash3:
                return mock_identity3
            return None

        mock_rns.Identity.recall = MagicMock(side_effect=recall_side_effect)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 3)
        # Verify all three destination hashes are present
        dest_hashes = {announce['destination_hash'] for announce in result}
        self.assertEqual(dest_hashes, {dest_hash1, dest_hash2, dest_hash3})

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_timestamp_is_milliseconds(self, mock_rns):
        """Test that timestamp is in milliseconds (unix time * 1000)"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        before_time = int(time.time() * 1000)
        result = self.wrapper.poll_received_announces()
        after_time = int(time.time() * 1000)

        self.assertEqual(len(result), 1)
        timestamp = result[0]['timestamp']

        # Timestamp should be in milliseconds and within reasonable range
        self.assertGreaterEqual(timestamp, before_time)
        self.assertLessEqual(timestamp, after_time)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_bytes_fields_returned_as_bytes(self, mock_rns):
        """Test that destination_hash, identity_hash, public_key, and app_data are returned as bytes"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        identity_hash = b'\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x20'
        public_key = b'mock_public_key_bytes'
        app_data = b'mock_app_data_bytes'

        mock_identity = MagicMock()
        mock_identity.hash = identity_hash
        mock_identity.get_public_key = MagicMock(return_value=public_key)

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=app_data)
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        announce = result[0]

        # All these fields should be bytes
        self.assertIsInstance(announce['destination_hash'], bytes)
        self.assertIsInstance(announce['identity_hash'], bytes)
        self.assertIsInstance(announce['public_key'], bytes)
        self.assertIsInstance(announce['app_data'], bytes)

        # Verify the actual values
        self.assertEqual(announce['destination_hash'], dest_hash)
        self.assertEqual(announce['identity_hash'], identity_hash)
        self.assertEqual(announce['public_key'], public_key)
        self.assertEqual(announce['app_data'], app_data)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_handle_various_aspects(self, mock_rns):
        """Test handling announces with various aspects (lxmf.delivery, lxmf.propagation, etc.)"""
        # Create different destination hashes representing different aspects
        lxmf_delivery_hash = b'\x01' * 16
        lxmf_propagation_hash = b'\x02' * 16
        nomadnet_node_hash = b'\x03' * 16

        mock_identity1 = MagicMock()
        mock_identity1.hash = lxmf_delivery_hash
        mock_identity1.get_public_key = MagicMock(return_value=b'key1')

        mock_identity2 = MagicMock()
        mock_identity2.hash = lxmf_propagation_hash
        mock_identity2.get_public_key = MagicMock(return_value=b'key2')

        mock_identity3 = MagicMock()
        mock_identity3.hash = nomadnet_node_hash
        mock_identity3.get_public_key = MagicMock(return_value=b'key3')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {
            lxmf_delivery_hash: announce_entry,
            lxmf_propagation_hash: announce_entry,
            nomadnet_node_hash: announce_entry,
        }

        def recall_side_effect(dest_hash):
            if dest_hash == lxmf_delivery_hash:
                return mock_identity1
            elif dest_hash == lxmf_propagation_hash:
                return mock_identity2
            elif dest_hash == nomadnet_node_hash:
                return mock_identity3
            return None

        mock_rns.Identity.recall = MagicMock(side_effect=recall_side_effect)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        # All three announces should be returned regardless of aspect
        self.assertEqual(len(result), 3)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_exception_handling_returns_empty_list(self, mock_rns):
        """Test that exceptions during polling are caught and return empty list"""
        # Make announce_table.keys() raise an exception
        mock_rns.Transport.announce_table.keys = MagicMock(side_effect=Exception("Unexpected error"))

        result = self.wrapper.poll_received_announces()

        self.assertEqual(result, [])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_last_announce_poll_time_updated(self, mock_rns):
        """Test that last_announce_poll_time is updated after polling"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        before_time = time.time()
        result = self.wrapper.poll_received_announces()
        after_time = time.time()

        # Verify last_announce_poll_time was updated
        self.assertGreaterEqual(self.wrapper.last_announce_poll_time, before_time)
        self.assertLessEqual(self.wrapper.last_announce_poll_time, after_time)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_interface_class_name_extraction(self, mock_rns):
        """Test that interface type is identified by class name"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        # Create interface with proper class name (we use type().__name__ now)
        class UDPInterface:
            pass
        mock_interface = UDPInterface()

        mock_packet = MagicMock()
        mock_packet.receiving_interface = mock_interface

        announce_entry = [time.time(), 0, 0, None, 0, mock_packet]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['interface'], "UDPInterface")
        self.assertIsInstance(result[0]['interface'], str)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_interface_name_same_as_class_returns_class_only(self, mock_rns):
        """Test that when interface.name equals class name, only class name is returned"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        # Create interface where .name equals class name
        class AutoInterface:
            name = "AutoInterface"
        mock_interface = AutoInterface()

        mock_packet = MagicMock()
        mock_packet.receiving_interface = mock_interface

        announce_entry = [time.time(), 0, 0, None, 0, mock_packet]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        # Should return just "AutoInterface", not "AutoInterface[AutoInterface]"
        self.assertEqual(result[0]['interface'], "AutoInterface")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_interface_with_none_name_returns_class_only(self, mock_rns):
        """Test that when interface.name is None, only class name is returned"""
        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'

        mock_identity = MagicMock()
        mock_identity.hash = dest_hash
        mock_identity.get_public_key = MagicMock(return_value=b'')

        # Create interface where .name is None
        class TCPClientInterface:
            name = None
        mock_interface = TCPClientInterface()

        mock_packet = MagicMock()
        mock_packet.receiving_interface = mock_interface

        announce_entry = [time.time(), 0, 0, None, 0, mock_packet]
        mock_rns.Transport.announce_table = {dest_hash: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result = self.wrapper.poll_received_announces()

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['interface'], "TCPClientInterface")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_seen_announce_hashes_persistence(self, mock_rns):
        """Test that seen_announce_hashes persists across multiple polls"""
        dest_hash1 = b'\x01' * 16
        dest_hash2 = b'\x02' * 16

        mock_identity1 = MagicMock()
        mock_identity1.hash = dest_hash1
        mock_identity1.get_public_key = MagicMock(return_value=b'')

        mock_identity2 = MagicMock()
        mock_identity2.hash = dest_hash2
        mock_identity2.get_public_key = MagicMock(return_value=b'')

        announce_entry = [time.time(), 0, 0, None, 0, None]

        # First poll with dest_hash1
        mock_rns.Transport.announce_table = {dest_hash1: announce_entry}
        mock_rns.Identity.recall = MagicMock(return_value=mock_identity1)
        mock_rns.Identity.recall_app_data = MagicMock(return_value=b'')
        mock_rns.Transport.hops_to = MagicMock(return_value=0)

        result1 = self.wrapper.poll_received_announces()
        self.assertEqual(len(result1), 1)

        # Add dest_hash2 to announce_table
        mock_rns.Transport.announce_table = {
            dest_hash1: announce_entry,
            dest_hash2: announce_entry,
        }

        def recall_side_effect(dest_hash):
            if dest_hash == dest_hash1:
                return mock_identity1
            elif dest_hash == dest_hash2:
                return mock_identity2
            return None

        mock_rns.Identity.recall = MagicMock(side_effect=recall_side_effect)

        result2 = self.wrapper.poll_received_announces()

        # Only dest_hash2 should be returned (dest_hash1 already seen)
        self.assertEqual(len(result2), 1)
        self.assertEqual(result2[0]['destination_hash'], dest_hash2)

        # Both hashes should be in seen_announce_hashes
        self.assertEqual(len(self.wrapper.seen_announce_hashes), 2)
        self.assertIn(dest_hash1.hex(), self.wrapper.seen_announce_hashes)
        self.assertIn(dest_hash2.hex(), self.wrapper.seen_announce_hashes)


if __name__ == '__main__':
    unittest.main()
