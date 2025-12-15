"""
Test suite for ReticulumWrapper peer identity management methods.

Tests the following methods:
- recall_identity: Recall peer identity from Reticulum's cache by destination hash
- store_peer_identity: Store a peer's identity locally for later recall
- restore_all_peer_identities: Bulk restore multiple peer identities (e.g., on app startup)
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch
import base64

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestRecallIdentity(unittest.TestCase):
    """Test the recall_identity method for retrieving cached peer identities"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_recall_identity_reticulum_not_available(self):
        """Test that recall_identity returns error when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.recall_identity("aabbccdd" * 4)  # 32 char hex string

        self.assertFalse(result.get('found'))
        self.assertIn('error', result)
        self.assertEqual(result['error'], "Reticulum not available")

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_recall_identity_found_in_cache(self, mock_rns):
        """Test successful recall of identity from RNS cache"""
        # Create a mock identity with a public key
        mock_identity = Mock()
        mock_public_key = b'0' * 32  # 32 byte public key
        mock_identity.get_public_key.return_value = mock_public_key

        # Make RNS.Identity.recall return our mock identity
        mock_rns.Identity.recall.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test with a valid 32-char hex string (16 bytes)
        dest_hash_hex = "aabbccdd" * 4
        result = wrapper.recall_identity(dest_hash_hex)

        # Verify the result
        self.assertTrue(result.get('found'))
        self.assertIn('public_key', result)
        self.assertEqual(result['public_key'], mock_public_key.hex())

        # Verify RNS.Identity.recall was called with correct bytes
        expected_bytes = bytes.fromhex(dest_hash_hex)
        mock_rns.Identity.recall.assert_called_once_with(expected_bytes)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_recall_identity_not_found_in_cache(self, mock_rns):
        """Test recall_identity when identity is not in cache"""
        # Make RNS.Identity.recall return None (not found)
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        dest_hash_hex = "1122334455667788" * 2  # 32 char hex string
        result = wrapper.recall_identity(dest_hash_hex)

        # Verify the result indicates not found
        self.assertFalse(result.get('found'))
        self.assertNotIn('public_key', result)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_recall_identity_invalid_hex_string(self, mock_rns):
        """Test recall_identity with invalid hex string"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test with invalid hex (contains 'Z')
        invalid_hex = "ZZZZZZZZ" * 4
        result = wrapper.recall_identity(invalid_hex)

        # Should return found=False with error message
        self.assertFalse(result.get('found'))
        self.assertIn('error', result)
        self.assertIn('Invalid hex string', result['error'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_recall_identity_rns_exception(self, mock_rns):
        """Test recall_identity when RNS.Identity.recall raises exception"""
        # Make RNS.Identity.recall raise an exception
        mock_rns.Identity.recall.side_effect = Exception("Network error")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        dest_hash_hex = "aabbccdd" * 4
        result = wrapper.recall_identity(dest_hash_hex)

        # Should return found=False with error message
        self.assertFalse(result.get('found'))
        self.assertIn('error', result)
        self.assertIn('Network error', result['error'])


class TestStorePeerIdentity(unittest.TestCase):
    """Test the store_peer_identity method for storing peer identities"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_store_peer_identity_reticulum_not_available(self):
        """Test that store_peer_identity returns error when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'1' * 16
        public_key = b'2' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        self.assertFalse(result.get('success'))
        self.assertIn('error', result)
        self.assertEqual(result['error'], "Reticulum not available")

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_success(self, mock_rns):
        """Test successful storage of peer identity"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'actual_hash_16b!'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_16byte'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None  # Not yet recallable

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'actual_hash_16b!'
        public_key = b'3' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Verify success
        self.assertTrue(result.get('success'))
        self.assertNotIn('error', result)

        # Verify RNS.Identity was created correctly
        mock_rns.Identity.assert_called_once_with(create_keys=False)
        mock_identity.load_public_key.assert_called_once_with(public_key)

        # Verify LXMF destination was created
        mock_rns.Destination.assert_called_once_with(
            mock_identity,
            'OUT',
            'SINGLE',
            "lxmf", "delivery"
        )

        # Verify identity was stored in wrapper's cache
        actual_hash_hex = b'actual_hash_16b!'.hex()
        dest_hash_hex = b'dest_hash_16byte'.hex()
        self.assertIn(actual_hash_hex, wrapper.identities)
        self.assertIn(dest_hash_hex, wrapper.identities)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_hash_mismatch(self, mock_rns):
        """Test store_peer_identity when identity hash doesn't match actual hash from public key"""
        # Create mock identity with different hash than provided
        mock_identity = Mock()
        mock_identity.hash = b'actual_hash_16b!'  # Actual hash from public key
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_16byte'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Provide a different identity hash (simulating database corruption)
        db_identity_hash = b'wrong_hash_16by!'  # Different from actual
        public_key = b'4' * 32

        result = wrapper.store_peer_identity(db_identity_hash, public_key)

        # Should still succeed (uses actual hash from public key)
        self.assertTrue(result.get('success'))

        # Verify both hashes are stored (for compatibility)
        actual_hash_hex = b'actual_hash_16b!'.hex()
        db_hash_hex = b'wrong_hash_16by!'.hex()
        self.assertIn(actual_hash_hex, wrapper.identities)
        self.assertIn(db_hash_hex, wrapper.identities)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_with_jarray_conversion(self, mock_rns):
        """Test store_peer_identity with Java array-like objects (Chaquopy integration)"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Simulate Java array (has __iter__ but is not bytes/bytearray)
        class MockJavaArray:
            def __iter__(self):
                return iter([5] * 16)

        class MockJavaArrayPubkey:
            def __iter__(self):
                return iter([6] * 32)

        identity_hash = MockJavaArray()
        public_key = MockJavaArrayPubkey()

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should successfully convert and store
        self.assertTrue(result.get('success'))

        # Verify load_public_key was called with bytes
        call_args = mock_identity.load_public_key.call_args[0][0]
        self.assertIsInstance(call_args, bytes)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_destination_creation_error(self, mock_rns):
        """Test store_peer_identity when destination creation fails"""
        # Create mock identity
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.side_effect = Exception("Failed to create destination")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'7' * 16
        public_key = b'8' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should return error
        self.assertFalse(result.get('success'))
        self.assertIn('error', result)
        self.assertIn('Failed to create destination', result['error'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_already_recallable(self, mock_rns):
        """Test store_peer_identity when identity is already recallable via RNS"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'

        # Make recall return the identity (already cached)
        mock_rns.Identity.recall.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'hash_16_bytes_ok'
        public_key = b'9' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should still succeed
        self.assertTrue(result.get('success'))

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_register_destination_fallback_attribute_error(self, mock_rns):
        """Test Transport.register_destination() fallback when method doesn't exist (AttributeError)"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        # Make Transport.register_destination raise AttributeError (method not available)
        mock_rns.Transport.register_destination.side_effect = AttributeError("No such method")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'hash_16_bytes_ok'
        public_key = b'a' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should still succeed (falls back to local cache)
        self.assertTrue(result.get('success'))

        # Verify identity was stored in local cache
        actual_hash_hex = b'hash_16_bytes_ok'.hex()
        self.assertIn(actual_hash_hex, wrapper.identities)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_register_destination_exception(self, mock_rns):
        """Test Transport.register_destination() fallback when it raises a generic exception"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        # Make Transport.register_destination raise a generic exception
        mock_rns.Transport.register_destination.side_effect = RuntimeError("Transport error")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'hash_16_bytes_ok'
        public_key = b'b' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should still succeed (falls back to local cache)
        self.assertTrue(result.get('success'))

        # Verify identity was stored in local cache
        actual_hash_hex = b'hash_16_bytes_ok'.hex()
        self.assertIn(actual_hash_hex, wrapper.identities)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_test_recall_verification_not_recallable(self, mock_rns):
        """Test the test recall verification path when identity is not yet recallable"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'

        # Make test recall return None (not recallable) - this tests lines 2897-2901
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'hash_16_bytes_ok'
        public_key = b'c' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should still succeed despite not being recallable
        self.assertTrue(result.get('success'))

        # Verify recall was called to test recallability
        mock_rns.Identity.recall.assert_called_once_with(b'dest_hash_ok_16b')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_peer_identity_test_recall_verification_recallable(self, mock_rns):
        """Test the test recall verification path when identity is successfully recallable"""
        # Create mock identity and destination
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'

        # Make test recall return the identity (successfully recallable)
        mock_rns.Identity.recall.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_hash = b'hash_16_bytes_ok'
        public_key = b'd' * 32

        result = wrapper.store_peer_identity(identity_hash, public_key)

        # Should succeed
        self.assertTrue(result.get('success'))

        # Verify recall was called to test recallability
        mock_rns.Identity.recall.assert_called_once_with(b'dest_hash_ok_16b')


class TestRestoreAllPeerIdentities(unittest.TestCase):
    """Test the restore_all_peer_identities method for bulk identity restoration"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_restore_all_reticulum_not_available(self):
        """Test restore_all_peer_identities when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {"identity_hash": "aabb" * 8, "public_key": base64.b64encode(b'key1' * 8).decode()}
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 0)
        self.assertIn('errors', result)
        self.assertEqual(result['errors'], ["Reticulum not available"])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_with_list_input(self, mock_rns):
        """Test restore_all_peer_identities with list of peer data"""
        # Setup mocks for successful storage
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create test data with proper format
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,  # 32 char hex = 16 bytes
                "public_key": base64.b64encode(b'key1' * 8).decode()  # 32 bytes base64 encoded
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": base64.b64encode(b'key2' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Both should succeed
        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_with_json_string_input(self, mock_rns):
        """Test restore_all_peer_identities with JSON string input"""
        import json

        # Setup mocks for successful storage
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create test data as JSON string
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]
        peer_data_json = json.dumps(peer_data)

        result = wrapper.restore_all_peer_identities(peer_data_json)

        # Should parse JSON and succeed
        self.assertEqual(result['success_count'], 1)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_missing_identity_hash(self, mock_rns):
        """Test restore_all_peer_identities with missing identity_hash field"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Peer data missing identity_hash
        peer_data = [
            {
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 0 success and 1 error
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing identity_hash', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_missing_public_key(self, mock_rns):
        """Test restore_all_peer_identities with missing public_key field"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Peer data missing public_key
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 0 success and 1 error
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing public_key', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_partial_success(self, mock_rns):
        """Test restore_all_peer_identities with mix of success and failures"""
        # Setup mocks - first succeeds, second fails
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create test data - second one has invalid base64
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": "INVALID_BASE64!@#$"
            },
            {
                "identity_hash": "55667788" * 4,
                "public_key": base64.b64encode(b'key3' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 2 successes and 1 error
        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 1)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_invalid_hex_string(self, mock_rns):
        """Test restore_all_peer_identities with invalid hex string"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Invalid hex string (contains 'Z')
        peer_data = [
            {
                "identity_hash": "ZZZZZZZZ" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should fail with error
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing peer', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_empty_list(self, mock_rns):
        """Test restore_all_peer_identities with empty peer data list"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.restore_all_peer_identities([])

        # Should succeed with 0 count and no errors
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_store_failure(self, mock_rns):
        """Test restore_all_peer_identities when store_peer_identity fails"""
        # Make Identity creation fail
        mock_rns.Identity.side_effect = Exception("Identity creation failed")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 0 success and 1 error
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Failed to restore', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_large_batch(self, mock_rns):
        """Test restore_all_peer_identities with large batch of peers"""
        # Setup mocks for successful storage
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create 100 peers
        peer_data = []
        for i in range(100):
            peer_data.append({
                "identity_hash": f"{i:08x}" * 4,  # Generate unique hex
                "public_key": base64.b64encode(f"key{i}".ljust(32, '0').encode()).decode()
            })

        result = wrapper.restore_all_peer_identities(peer_data)

        # All should succeed
        self.assertEqual(result['success_count'], 100)
        self.assertEqual(len(result['errors']), 0)

    def test_restore_all_invalid_json_string(self):
        """Test restore_all_peer_identities with invalid JSON string"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Invalid JSON string
        invalid_json = "{ this is not valid json }"

        result = wrapper.restore_all_peer_identities(invalid_json)

        # Should return error
        self.assertEqual(result['success_count'], 0)
        self.assertGreater(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_invalid_hex_in_identity_hash(self, mock_rns):
        """Test restore_all_peer_identities with invalid hex string in identity_hash field"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Peer data with invalid hex in identity_hash (contains 'Z')
        peer_data = [
            {
                "identity_hash": "ZZZZZZZZ12345678" * 2,  # Invalid hex
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should fail gracefully
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing peer', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_invalid_hex_in_public_key_base64(self, mock_rns):
        """Test restore_all_peer_identities with invalid base64 in public_key field"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Peer data with invalid base64 in public_key
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": "!!!INVALID_BASE64!!!"  # Invalid base64
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should fail gracefully
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing peer', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_exception_during_store_call(self, mock_rns):
        """Test restore_all_peer_identities when store_peer_identity raises exception"""
        # Setup mocks
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        # Make Identity creation raise an exception
        mock_rns.Identity.side_effect = Exception("Identity creation failed")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 0 success and 1 error
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Failed to restore', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_mixed_success_failure_batch(self, mock_rns):
        """Test restore_all_peer_identities with mixed success/failure batch (accumulates errors)"""
        # Setup mocks - will succeed for first and third, fail for second
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create test data - mix of valid, invalid hex, and valid
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            },
            {
                "identity_hash": "GGGGGGGG" * 4,  # Invalid hex
                "public_key": base64.b64encode(b'key2' * 8).decode()
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": "NOT_BASE64!!!"  # Invalid base64
            },
            {
                "identity_hash": "55667788" * 4,
                "public_key": base64.b64encode(b'key4' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 2 successes and 2 errors (accumulates all errors)
        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 2)

        # Verify error accumulation
        for error in result['errors']:
            self.assertIn('Error processing peer', error)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_json_parse_error(self, mock_rns):
        """Test restore_all_peer_identities with malformed JSON that causes parse error"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Completely invalid JSON
        invalid_json = '{"unclosed": "bracket"'

        result = wrapper.restore_all_peer_identities(invalid_json)

        # Should return error from outer exception handler
        self.assertEqual(result['success_count'], 0)
        self.assertGreater(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_restore_all_store_returns_error_dict(self, mock_rns):
        """Test restore_all_peer_identities when store_peer_identity returns error dict"""
        # Setup mocks to fail destination creation
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_rns.Identity.return_value = mock_identity
        # Make Destination creation fail
        mock_rns.Destination.side_effect = Exception("Destination creation failed")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # Should have 0 success and 1 error with the failure message
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Failed to restore', result['errors'][0])
        self.assertIn('Destination creation failed', result['errors'][0])


class TestPeerIdentityIntegration(unittest.TestCase):
    """Integration tests for peer identity management workflow"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_store_and_recall_workflow(self, mock_rns):
        """Test the complete workflow: store identity and then recall it"""
        # Setup mocks
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()
        mock_public_key = b'test_public_key_32_bytes_long!!'
        mock_identity.get_public_key.return_value = mock_public_key

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        # Configure RNS mocks
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'

        # Initially not recallable, then recallable after storage
        mock_rns.Identity.recall.side_effect = [None, mock_identity]

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Store the identity
        identity_hash = b'hash_16_bytes_ok'
        public_key = b'test_public_key_32_bytes_long!!'

        store_result = wrapper.store_peer_identity(identity_hash, public_key)
        self.assertTrue(store_result.get('success'))

        # Now try to recall it
        dest_hash_hex = b'dest_hash_ok_16b'.hex()
        recall_result = wrapper.recall_identity(dest_hash_hex)

        # Should be found
        self.assertTrue(recall_result.get('found'))
        self.assertEqual(recall_result['public_key'], mock_public_key.hex())

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_and_verify(self, mock_rns):
        """Test bulk restore followed by individual recall verification"""
        # Setup mocks
        mock_identity = Mock()
        mock_identity.hash = b'hash_16_bytes_ok'
        mock_identity.load_public_key = Mock()

        mock_destination = Mock()
        mock_destination.hash = b'dest_hash_ok_16b'

        mock_rns.Identity.return_value = mock_identity
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.OUT = 'OUT'
        mock_rns.Destination.SINGLE = 'SINGLE'
        mock_rns.Identity.recall.return_value = None

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Bulk restore multiple peers
        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'key1' * 8).decode()
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": base64.b64encode(b'key2' * 8).decode()
            },
            {
                "identity_hash": "55667788" * 4,
                "public_key": base64.b64encode(b'key3' * 8).decode()
            }
        ]

        result = wrapper.restore_all_peer_identities(peer_data)

        # All should succeed
        self.assertEqual(result['success_count'], 3)
        self.assertEqual(len(result['errors']), 0)

        # Verify they're stored in the wrapper's cache
        # At minimum, the destination hashes should be cached
        self.assertGreater(len(wrapper.identities), 0)


class TestBulkRestoreAnnounceIdentities(unittest.TestCase):
    """Test the bulk_restore_announce_identities method for fast announce restoration"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_bulk_restore_announces_reticulum_not_available(self):
        """Test that bulk_restore_announce_identities returns error when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {"destination_hash": "aabbccdd" * 4, "public_key": base64.b64encode(b'key1' * 16).decode()}
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 0)
        self.assertIn('errors', result)
        self.assertEqual(result['errors'], ["Reticulum not available"])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_success(self, mock_rns):
        """Test successful bulk restore of announce identities"""
        # Setup mock for known_destinations dict
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512  # 64 bytes

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create test data - 64 byte public keys
        public_key_1 = b'k' * 64
        public_key_2 = b'm' * 64
        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,  # 32 hex chars = 16 bytes
                "public_key": base64.b64encode(public_key_1).decode()
            },
            {
                "destination_hash": "11223344" * 4,
                "public_key": base64.b64encode(public_key_2).decode()
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        # Both should succeed
        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 0)

        # Verify entries were added to Identity.known_destinations
        self.assertEqual(len(mock_rns.Identity.known_destinations), 2)

        # Verify the dict entries have correct structure
        dest_hash_1 = bytes.fromhex("aabbccdd" * 4)
        self.assertIn(dest_hash_1, mock_rns.Identity.known_destinations)
        entry = mock_rns.Identity.known_destinations[dest_hash_1]
        self.assertEqual(len(entry), 4)  # [time, packet_hash, public_key, app_data]
        self.assertEqual(entry[2], public_key_1)  # public key at index 2
        self.assertIsNone(entry[1])  # packet_hash is None
        self.assertIsNone(entry[3])  # app_data is None

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_with_json_string(self, mock_rns):
        """Test bulk_restore_announce_identities with JSON string input"""
        import json

        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'x' * 64).decode()
            }
        ]
        json_string = json.dumps(announce_data)

        result = wrapper.bulk_restore_announce_identities(json_string)

        self.assertEqual(result['success_count'], 1)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_missing_destination_hash(self, mock_rns):
        """Test bulk_restore_announce_identities with missing destination_hash field"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {"public_key": base64.b64encode(b'k' * 64).decode()}  # Missing destination_hash
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing destination_hash', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_missing_public_key(self, mock_rns):
        """Test bulk_restore_announce_identities with missing public_key field"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {"destination_hash": "aabbccdd" * 4}  # Missing public_key
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing public_key', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_invalid_hex(self, mock_rns):
        """Test bulk_restore_announce_identities with invalid hex in destination_hash"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "ZZZZZZZZ" * 4,  # Invalid hex
                "public_key": base64.b64encode(b'k' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing announce', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_invalid_base64(self, mock_rns):
        """Test bulk_restore_announce_identities with invalid base64 in public_key"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": "!!!INVALID_BASE64!!!"
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing announce', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_empty_list(self, mock_rns):
        """Test bulk_restore_announce_identities with empty list"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.bulk_restore_announce_identities([])

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_large_batch(self, mock_rns):
        """Test bulk_restore_announce_identities with large batch (1000 announces)"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create 1000 announces
        announce_data = []
        for i in range(1000):
            announce_data.append({
                "destination_hash": f"{i:032x}",  # 32 hex chars
                "public_key": base64.b64encode(f"key{i:06d}".ljust(64, '0').encode()).decode()
            })

        result = wrapper.bulk_restore_announce_identities(announce_data)

        # All should succeed
        self.assertEqual(result['success_count'], 1000)
        self.assertEqual(len(result['errors']), 0)
        self.assertEqual(len(mock_rns.Identity.known_destinations), 1000)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_partial_success(self, mock_rns):
        """Test bulk_restore_announce_identities with mix of valid and invalid entries"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'k' * 64).decode()
            },
            {
                "destination_hash": "INVALID_HEX",
                "public_key": base64.b64encode(b'm' * 64).decode()
            },
            {
                "destination_hash": "11223344" * 4,
                "public_key": base64.b64encode(b'n' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 1)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_populates_local_cache(self, mock_rns):
        """Test that bulk_restore_announce_identities also populates wrapper.identities cache"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'k' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        self.assertEqual(result['success_count'], 1)
        # Check local cache is populated
        dest_hash_hex = "aabbccdd" * 4
        self.assertIn(dest_hash_hex, wrapper.identities)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_wrong_public_key_size(self, mock_rns):
        """Test bulk_restore_announce_identities with wrong public key size"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512  # Expects 64 byte keys

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'short').decode()  # Too short
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)

        # Should fail due to wrong key size
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('public key size', result['errors'][0].lower())


class TestBulkRestorePeerIdentities(unittest.TestCase):
    """Test the bulk_restore_peer_identities method for fast peer identity restoration"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', False)
    def test_bulk_restore_peers_reticulum_not_available(self):
        """Test that bulk_restore_peer_identities returns error when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {"identity_hash": "aabbccdd" * 4, "public_key": base64.b64encode(b'key1' * 16).decode()}
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 0)
        self.assertIn('errors', result)
        self.assertEqual(result['errors'], ["Reticulum not available"])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_success(self, mock_rns):
        """Test successful bulk restore of peer identities"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32  # Mock hash function
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128  # 16 bytes
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,  # 32 hex chars = 16 bytes
                "public_key": base64.b64encode(b'k' * 64).decode()
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": base64.b64encode(b'm' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        # Both should succeed
        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 0)

        # Verify entries were added to Identity.known_destinations
        self.assertGreater(len(mock_rns.Identity.known_destinations), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_with_json_string(self, mock_rns):
        """Test bulk_restore_peer_identities with JSON string input"""
        import json

        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'x' * 64).decode()
            }
        ]
        json_string = json.dumps(peer_data)

        result = wrapper.bulk_restore_peer_identities(json_string)

        self.assertEqual(result['success_count'], 1)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_missing_identity_hash(self, mock_rns):
        """Test bulk_restore_peer_identities with missing identity_hash field"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {"public_key": base64.b64encode(b'k' * 64).decode()}  # Missing identity_hash
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing identity_hash', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_missing_public_key(self, mock_rns):
        """Test bulk_restore_peer_identities with missing public_key field"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {"identity_hash": "aabbccdd" * 4}  # Missing public_key
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('missing public_key', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_invalid_hex_in_identity_hash_is_ignored(self, mock_rns):
        """Test bulk_restore_peer_identities ignores invalid hex in identity_hash field.

        The identity_hash field is not validated because the implementation computes
        the actual identity hash from the public key (which is the source of truth).
        This is by design - the stored identity_hash may be stale/incorrect.
        """
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "ZZZZZZZZ" * 4,  # Invalid hex - ignored since we compute from public_key
                "public_key": base64.b64encode(b'k' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        # Should succeed - identity_hash is not validated, we compute from public_key
        self.assertEqual(result['success_count'], 1)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_invalid_base64(self, mock_rns):
        """Test bulk_restore_peer_identities with invalid base64 in public_key"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": "!!!INVALID_BASE64!!!"
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('Error processing peer', result['errors'][0])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_empty_list(self, mock_rns):
        """Test bulk_restore_peer_identities with empty list"""
        mock_rns.Identity.known_destinations = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.bulk_restore_peer_identities([])

        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_large_batch(self, mock_rns):
        """Test bulk_restore_peer_identities with large batch (1000 peers)"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create 1000 peers
        peer_data = []
        for i in range(1000):
            peer_data.append({
                "identity_hash": f"{i:032x}",  # 32 hex chars
                "public_key": base64.b64encode(f"key{i:06d}".ljust(64, '0').encode()).decode()
            })

        result = wrapper.bulk_restore_peer_identities(peer_data)

        # All should succeed
        self.assertEqual(result['success_count'], 1000)
        self.assertEqual(len(result['errors']), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_partial_success(self, mock_rns):
        """Test bulk_restore_peer_identities with mix of valid and invalid entries.

        Note: Invalid identity_hash does NOT cause failure - we compute from public_key.
        Only invalid public_key (bad base64) causes failure.
        """
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'k' * 64).decode()
            },
            {
                "identity_hash": "11223344" * 4,
                "public_key": "INVALID_BASE64!!!"  # This causes failure
            },
            {
                "identity_hash": "55667788" * 4,
                "public_key": base64.b64encode(b'n' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 2)
        self.assertEqual(len(result['errors']), 1)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_populates_local_cache(self, mock_rns):
        """Test that bulk_restore_peer_identities also populates wrapper.identities cache"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512
        mock_rns.Identity.NAME_HASH_LENGTH = 80
        mock_rns.Identity.full_hash = lambda x: b'h' * 32
        mock_rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        mock_rns.Identity.truncated_hash = lambda x: b't' * 16

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'k' * 64).decode()
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        self.assertEqual(result['success_count'], 1)
        # Check local cache is populated (either by identity_hash or computed dest_hash)
        self.assertGreater(len(wrapper.identities), 0)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_peers_wrong_public_key_size(self, mock_rns):
        """Test bulk_restore_peer_identities with wrong public key size"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512  # Expects 64 byte keys

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        peer_data = [
            {
                "identity_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(b'short').decode()  # Too short
            }
        ]

        result = wrapper.bulk_restore_peer_identities(peer_data)

        # Should fail due to wrong key size
        self.assertEqual(result['success_count'], 0)
        self.assertEqual(len(result['errors']), 1)
        self.assertIn('public key size', result['errors'][0].lower())


class TestBulkRestoreEquivalence(unittest.TestCase):
    """Test that bulk restore produces equivalent results to individual store"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_bulk_restore_announces_same_format_as_remember(self, mock_rns):
        """Test that bulk_restore_announce_identities produces same dict format as Identity.remember()"""
        mock_rns.Identity.known_destinations = {}
        mock_rns.Identity.KEYSIZE = 512

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        public_key = b'k' * 64
        announce_data = [
            {
                "destination_hash": "aabbccdd" * 4,
                "public_key": base64.b64encode(public_key).decode()
            }
        ]

        result = wrapper.bulk_restore_announce_identities(announce_data)
        self.assertEqual(result['success_count'], 1)

        # Check the format matches what Identity.remember() would produce
        dest_hash = bytes.fromhex("aabbccdd" * 4)
        entry = mock_rns.Identity.known_destinations[dest_hash]

        # Format should be: [timestamp, packet_hash, public_key, app_data]
        self.assertIsInstance(entry[0], float)  # timestamp
        self.assertIsNone(entry[1])  # packet_hash
        self.assertEqual(entry[2], public_key)  # public_key
        self.assertIsNone(entry[3])  # app_data


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
