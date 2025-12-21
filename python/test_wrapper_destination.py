"""
Test suite for ReticulumWrapper destination methods.
Tests destination creation, announcing, and retrieval functionality.
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch, call

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestCreateDestination(unittest.TestCase):
    """Test the create_destination method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_create_destination_in_mock_mode(self):
        """
        Test create_destination when RNS is not available (mock mode).
        Should return mock destination with hash and hex_hash.
        """
        # Force mock mode
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create identity dict
        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Create destination
        result = wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1", "aspect2"]
        )

        # Verify result structure
        self.assertIn('hash', result)
        self.assertIn('hex_hash', result)
        self.assertIsInstance(result['hash'], bytes)
        self.assertIsInstance(result['hex_hash'], str)
        self.assertEqual(len(result['hash']), 16)  # Mock hash is 16 bytes

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_direction_mapping(self, mock_rns):
        """
        Test that direction parameter is correctly mapped to RNS.Destination constants.
        """
        # Force RNS available
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_dest_hexhash'
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.OUT = 0x11

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Test IN direction
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )

        # Verify RNS.Destination was called with IN direction
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[1], mock_rns.Destination.IN)

        # Test OUT direction
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="OUT",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )

        # Verify RNS.Destination was called with OUT direction
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[1], mock_rns.Destination.OUT)

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_type_mapping(self, mock_rns):
        """
        Test that dest_type parameter is correctly mapped to RNS.Destination type constants.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_dest_hexhash'
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20
        mock_rns.Destination.GROUP = 0x21
        mock_rns.Destination.PLAIN = 0x22

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Test SINGLE type
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[2], mock_rns.Destination.SINGLE)

        # Test GROUP type
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="GROUP",
            app_name="testapp",
            aspects=["aspect1"]
        )
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[2], mock_rns.Destination.GROUP)

        # Test PLAIN type (default for unknown types)
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="UNKNOWN",
            app_name="testapp",
            aspects=["aspect1"]
        )
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[2], mock_rns.Destination.PLAIN)

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_stores_in_dict(self, mock_rns):
        """
        Test that created destination is stored in wrapper.destinations dict.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'abc123def456'
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Verify destinations dict is initially empty
        self.assertEqual(len(wrapper.destinations), 0)

        # Create destination
        result = wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )

        # Verify destination is stored in dict with hex hash as key
        self.assertEqual(len(wrapper.destinations), 1)
        self.assertIn('abc123def456', wrapper.destinations)
        self.assertEqual(wrapper.destinations['abc123def456'], mock_destination)

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_with_aspects(self, mock_rns):
        """
        Test that aspects are properly passed to RNS.Destination constructor.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_dest_hexhash'
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Create destination with multiple aspects
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1", "aspect2", "aspect3"]
        )

        # Verify RNS.Destination was called with correct arguments
        call_args = mock_rns.Destination.call_args[0]
        # Args should be: identity, direction, type, app_name, *aspects
        self.assertEqual(call_args[0], mock_identity)
        self.assertEqual(call_args[3], "testapp")
        self.assertEqual(call_args[4], "aspect1")
        self.assertEqual(call_args[5], "aspect2")
        self.assertEqual(call_args[6], "aspect3")

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_loads_identity_from_dict(self, mock_rns):
        """
        Test that identity is properly reconstructed from identity_dict.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_dest_hexhash'
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_private_key = b'test_private_key_123'
        identity_dict = {
            'private_key': test_private_key,
            'hash': b'test_hash'
        }

        # Create destination
        wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )

        # Verify identity was created and private key loaded
        mock_rns.Identity.assert_called()
        mock_identity.load_private_key.assert_called_once_with(test_private_key)

    @patch('reticulum_wrapper.RNS')
    def test_create_destination_error_handling(self, mock_rns):
        """
        Test that create_destination raises RuntimeError on failure.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Destination to raise an exception
        mock_rns.Destination.side_effect = Exception("Test error")
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Verify RuntimeError is raised
        with self.assertRaises(RuntimeError) as context:
            wrapper.create_destination(
                identity_dict=identity_dict,
                direction="IN",
                dest_type="SINGLE",
                app_name="testapp",
                aspects=["aspect1"]
            )

        self.assertIn("Failed to create destination", str(context.exception))


class TestAnnounceDestination(unittest.TestCase):
    """Test the announce_destination method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_announce_not_initialized(self):
        """
        Test announce_destination when Reticulum is not initialized.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        result = wrapper.announce_destination(b'test_hash')

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'])

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_not_found(self, mock_rns):
        """
        Test announce_destination when destination hash is not in tracking dict.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        # Try to announce a destination that doesn't exist
        dest_hash = b'nonexistent_hash'
        result = wrapper.announce_destination(dest_hash)

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not found', result['error'])

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_success(self, mock_rns):
        """
        Test successful destination announce.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock destination - hash and hexhash must match!
        test_hash = b'test_dest_hash16'  # 16 bytes
        mock_destination = Mock()
        mock_destination.hash = test_hash
        mock_destination.hexhash = test_hash.hex()
        mock_destination.announce = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        # Store destination in tracking dict
        wrapper.destinations[test_hash.hex()] = mock_destination

        # Announce it
        result = wrapper.announce_destination(test_hash)

        # Verify success
        self.assertTrue(result['success'])
        mock_destination.announce.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_with_app_data(self, mock_rns):
        """
        Test that app_data is properly passed to destination.announce().
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock destination - hash and hexhash must match!
        test_hash = b'test_dest_hash16'  # 16 bytes
        mock_destination = Mock()
        mock_destination.hash = test_hash
        mock_destination.hexhash = test_hash.hex()
        mock_destination.announce = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        # Store destination in tracking dict
        wrapper.destinations[test_hash.hex()] = mock_destination

        # Announce with custom app_data
        test_app_data = b'custom_app_data'
        result = wrapper.announce_destination(test_hash, app_data=test_app_data)

        # Verify announce was called with app_data
        self.assertTrue(result['success'])
        mock_destination.announce.assert_called_once_with(app_data=test_app_data)

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_default_app_data(self, mock_rns):
        """
        Test that display_name is used as default app_data when none provided.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock destination - hash and hexhash must match!
        test_hash = b'test_dest_hash16'  # 16 bytes
        mock_destination = Mock()
        mock_destination.hash = test_hash
        mock_destination.hexhash = test_hash.hex()
        mock_destination.announce = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = "TestUser"

        # Store destination in tracking dict
        wrapper.destinations[test_hash.hex()] = mock_destination

        # Announce without app_data
        result = wrapper.announce_destination(test_hash)

        # Verify announce was called with display_name as app_data
        self.assertTrue(result['success'])
        mock_destination.announce.assert_called_once_with(app_data=b'TestUser')

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_converts_jarray(self, mock_rns):
        """
        Test that jarray-like objects from Chaquopy are converted to bytes.
        This tests compatibility with Java/Kotlin byte arrays.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'abc123def456'
        mock_destination.announce = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Store destination in tracking dict
        wrapper.destinations['abc123def456'] = mock_destination

        # Simulate jarray (list that's iterable but not bytes)
        jarray_hash = [0xab, 0xc1, 0x23, 0xde, 0xf4, 0x56]
        jarray_app_data = [0x64, 0x61, 0x74, 0x61]  # "data" in bytes

        # Announce with jarray inputs
        result = wrapper.announce_destination(jarray_hash, app_data=jarray_app_data)

        # Verify success (conversion happened internally)
        self.assertTrue(result['success'])
        mock_destination.announce.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    def test_announce_lxmf_destination(self, mock_rns):
        """
        Test that local LXMF destination can be announced.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock LXMF destination - hash and hexhash must match!
        test_hash = b'lxmf_dest_hash16'  # 16 bytes
        mock_lxmf_dest = Mock()
        mock_lxmf_dest.hash = test_hash
        mock_lxmf_dest.hexhash = test_hash.hex()
        mock_lxmf_dest.announce = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError
        wrapper.local_lxmf_destination = mock_lxmf_dest

        # Announce the LXMF destination
        result = wrapper.announce_destination(test_hash)

        # Verify success
        self.assertTrue(result['success'])
        mock_lxmf_dest.announce.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    def test_announce_destination_error_handling(self, mock_rns):
        """
        Test that announce_destination handles exceptions gracefully.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock destination that raises exception on announce - hash and hexhash must match!
        test_hash = b'test_dest_hash16'  # 16 bytes
        mock_destination = Mock()
        mock_destination.hash = test_hash
        mock_destination.hexhash = test_hash.hex()
        mock_destination.announce = Mock(side_effect=Exception("Announce failed"))

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        # Store destination in tracking dict
        wrapper.destinations[test_hash.hex()] = mock_destination

        # Try to announce
        result = wrapper.announce_destination(test_hash)

        # Verify error is returned
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('Announce failed', result['error'])


class TestGetLxmfDestination(unittest.TestCase):
    """Test the get_lxmf_destination method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_lxmf_destination_not_available(self):
        """
        Test get_lxmf_destination when RNS is not available.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.get_lxmf_destination()

        self.assertIn('error', result)
        self.assertIn('not created', result['error'])

    @patch('reticulum_wrapper.RNS')
    def test_get_lxmf_destination_not_created(self, mock_rns):
        """
        Test get_lxmf_destination when LXMF destination hasn't been created yet.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.local_lxmf_destination = None

        result = wrapper.get_lxmf_destination()

        self.assertIn('error', result)
        self.assertIn('not created', result['error'])

    @patch('reticulum_wrapper.RNS')
    def test_get_lxmf_destination_success(self, mock_rns):
        """
        Test successful retrieval of LXMF destination.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock LXMF destination
        mock_lxmf_dest = Mock()
        mock_lxmf_dest.hash = b'lxmf_dest_hash_16b'
        mock_lxmf_dest.hexhash = 'abc123def456789'

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.local_lxmf_destination = mock_lxmf_dest

        result = wrapper.get_lxmf_destination()

        # Verify result structure
        self.assertIn('hash', result)
        self.assertIn('hex_hash', result)
        self.assertEqual(result['hash'], b'lxmf_dest_hash_16b')
        self.assertEqual(result['hex_hash'], 'abc123def456789')

    @patch('reticulum_wrapper.RNS')
    def test_get_lxmf_destination_returns_bytes_and_hex(self, mock_rns):
        """
        Test that get_lxmf_destination returns both binary and hex hash.
        This is important for different use cases (binary for RNS, hex for UI).
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock LXMF destination
        test_hash = b'\xab\xcd\xef\x12\x34\x56\x78\x90' * 2  # 16 bytes
        mock_lxmf_dest = Mock()
        mock_lxmf_dest.hash = test_hash
        mock_lxmf_dest.hexhash = test_hash.hex()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.local_lxmf_destination = mock_lxmf_dest

        result = wrapper.get_lxmf_destination()

        # Verify both forms are present and consistent
        self.assertIsInstance(result['hash'], bytes)
        self.assertIsInstance(result['hex_hash'], str)
        self.assertEqual(result['hash'].hex(), result['hex_hash'])


class TestCreateAndAnnounceTestDestination(unittest.TestCase):
    """Test the create_and_announce_test_destination helper method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_test_destination_not_initialized(self):
        """
        Test create_and_announce_test_destination when not initialized.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        result = wrapper.create_and_announce_test_destination()

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'])

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_creates_identity(self, mock_rns):
        """
        Test that create_and_announce_test_destination creates a new identity.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination()

        # Verify identity was created
        mock_rns.Identity.assert_called_once()
        self.assertTrue(result['success'])

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_creates_destination_with_debug_aspect(self, mock_rns):
        """
        Test that test destination is created with "debug" aspect.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination(app_name="testapp")

        # Verify RNS.Destination was called with correct arguments
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[0], mock_identity)
        self.assertEqual(call_args[1], mock_rns.Destination.IN)
        self.assertEqual(call_args[2], mock_rns.Destination.SINGLE)
        self.assertEqual(call_args[3], "testapp")
        self.assertEqual(call_args[4], "debug")

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_stores_in_dict(self, mock_rns):
        """
        Test that test destination is stored in wrapper.destinations.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash_abc123'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Verify initially empty
        self.assertEqual(len(wrapper.destinations), 0)

        result = wrapper.create_and_announce_test_destination()

        # Verify destination was stored
        self.assertTrue(result['success'])
        self.assertEqual(len(wrapper.destinations), 1)
        self.assertIn('test_hexhash_abc123', wrapper.destinations)

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_announces_with_app_data(self, mock_rns):
        """
        Test that test destination is announced with "Columba Debug Test" app_data.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination()

        # Verify announce was called with correct app_data
        self.assertTrue(result['success'])
        mock_destination.announce.assert_called_once_with(app_data=b"Columba Debug Test")

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_returns_complete_info(self, mock_rns):
        """
        Test that create_and_announce_test_destination returns all expected fields.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash_16'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash_123'
        mock_destination.hexhash = 'abc123def456'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination()

        # Verify all expected fields are present
        self.assertTrue(result['success'])
        self.assertIn('dest_hash', result)
        self.assertIn('hex_hash', result)
        self.assertIn('identity_hash', result)
        self.assertIn('app_data', result)

        # Verify field values
        self.assertEqual(result['dest_hash'], b'test_dest_hash_123')
        self.assertEqual(result['hex_hash'], 'abc123def456')
        self.assertEqual(result['identity_hash'], b'test_identity_hash_16')
        self.assertEqual(result['app_data'], b"Columba Debug Test")

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_custom_app_name(self, mock_rns):
        """
        Test that custom app_name parameter is used.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination(app_name="custom_app")

        # Verify custom app_name was used
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[3], "custom_app")

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_default_app_name(self, mock_rns):
        """
        Test that default app_name is "columba".
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_identity.hash = b'test_identity_hash'
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination
        mock_destination = Mock()
        mock_destination.hash = b'test_dest_hash'
        mock_destination.hexhash = 'test_hexhash'
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination()

        # Verify default app_name "columba" was used
        call_args = mock_rns.Destination.call_args[0]
        self.assertEqual(call_args[3], "columba")

    @patch('reticulum_wrapper.RNS')
    def test_test_destination_error_handling(self, mock_rns):
        """
        Test that create_and_announce_test_destination handles exceptions gracefully.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity to raise exception
        mock_rns.Identity.side_effect = Exception("Identity creation failed")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        result = wrapper.create_and_announce_test_destination()

        # Verify error is returned
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('Identity creation failed', result['error'])


class TestDestinationIntegration(unittest.TestCase):
    """Integration tests for destination-related methods"""

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
    def test_create_then_announce_workflow(self, mock_rns):
        """
        Integration test: Create a destination, then announce it.
        This tests the typical workflow.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        # Mock RNS.Destination - hash and hexhash must match!
        test_hash = b'test_dest_hash16'  # 16 bytes
        mock_destination = Mock()
        mock_destination.hash = test_hash
        mock_destination.hexhash = test_hash.hex()
        mock_destination.announce = Mock()
        mock_rns.Destination.return_value = mock_destination
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Step 1: Create destination
        create_result = wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="testapp",
            aspects=["aspect1"]
        )

        # Verify creation succeeded
        self.assertIn('hash', create_result)
        self.assertIn('hex_hash', create_result)

        # Step 2: Announce the destination
        announce_result = wrapper.announce_destination(
            create_result['hash'],
            app_data=b"Test Announce"
        )

        # Verify announce succeeded
        self.assertTrue(announce_result['success'])
        mock_destination.announce.assert_called_once_with(app_data=b"Test Announce")

    @patch('reticulum_wrapper.RNS')
    def test_multiple_destinations_tracked_separately(self, mock_rns):
        """
        Test that multiple destinations are tracked separately in wrapper.destinations.
        """
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock RNS.Identity
        mock_identity = Mock()
        mock_rns.Identity.return_value = mock_identity

        # Mock multiple RNS.Destination objects - hash and hexhash must match!
        dest1_hash = b'dest1_hash_16byt'  # 16 bytes
        dest1 = Mock()
        dest1.hash = dest1_hash
        dest1.hexhash = dest1_hash.hex()
        dest1.announce = Mock()

        dest2_hash = b'dest2_hash_16byt'  # 16 bytes
        dest2 = Mock()
        dest2.hash = dest2_hash
        dest2.hexhash = dest2_hash.hex()
        dest2.announce = Mock()

        # Configure mock to return different destinations
        mock_rns.Destination.side_effect = [dest1, dest2]
        mock_rns.Destination.IN = 0x10
        mock_rns.Destination.SINGLE = 0x20

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.display_name = None  # Set display_name to avoid AttributeError

        identity_dict = {
            'private_key': b'test_private_key',
            'hash': b'test_hash'
        }

        # Create first destination
        result1 = wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="app1",
            aspects=["aspect1"]
        )

        # Create second destination
        result2 = wrapper.create_destination(
            identity_dict=identity_dict,
            direction="IN",
            dest_type="SINGLE",
            app_name="app2",
            aspects=["aspect2"]
        )

        # Verify both are tracked separately
        self.assertEqual(len(wrapper.destinations), 2)
        self.assertIn(dest1_hash.hex(), wrapper.destinations)
        self.assertIn(dest2_hash.hex(), wrapper.destinations)

        # Announce first destination
        announce1 = wrapper.announce_destination(result1['hash'])
        self.assertTrue(announce1['success'])
        dest1.announce.assert_called_once()
        dest2.announce.assert_not_called()

        # Announce second destination
        announce2 = wrapper.announce_destination(result2['hash'])
        self.assertTrue(announce2['success'])
        dest2.announce.assert_called_once()


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
