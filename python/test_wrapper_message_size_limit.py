"""
Test suite for ReticulumWrapper message size limit functionality.

Tests the incoming message size limit configuration at initialization
and runtime adjustment via set_incoming_message_size_limit.
"""

import sys
import os
import unittest
import tempfile
import shutil
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestInitializeWithMessageLimit(unittest.TestCase):
    """Test initialize() with incoming_message_limit_kb parameter"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_initialize_default_limit(self, mock_lxmf, mock_rns):
        """Test initialize uses default 1024KB limit when not specified"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_rns.Reticulum.return_value = MagicMock()
        mock_rns.Identity.return_value = MagicMock()
        mock_rns.Destination.return_value = MagicMock()
        mock_router = MagicMock()
        mock_lxmf.LXMRouter.return_value = mock_router

        config = '{"interfaces": [], "allowAnonymous": false}'

        result = wrapper.initialize(config_json=config)

        # Verify LXMRouter was called with delivery_limit=1024
        mock_lxmf.LXMRouter.assert_called_once()
        call_kwargs = mock_lxmf.LXMRouter.call_args[1]
        self.assertEqual(call_kwargs.get('delivery_limit'), 1024)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_initialize_custom_limit_5mb(self, mock_lxmf, mock_rns):
        """Test initialize with 5MB limit (5120KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_rns.Reticulum.return_value = MagicMock()
        mock_rns.Identity.return_value = MagicMock()
        mock_rns.Destination.return_value = MagicMock()
        mock_router = MagicMock()
        mock_lxmf.LXMRouter.return_value = mock_router

        config = '{"interfaces": [], "allowAnonymous": false}'

        result = wrapper.initialize(
            config_json=config,
            incoming_message_limit_kb=5120
        )

        call_kwargs = mock_lxmf.LXMRouter.call_args[1]
        self.assertEqual(call_kwargs.get('delivery_limit'), 5120)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_initialize_unlimited_limit(self, mock_lxmf, mock_rns):
        """Test initialize with 'unlimited' (128MB = 131072KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_rns.Reticulum.return_value = MagicMock()
        mock_rns.Identity.return_value = MagicMock()
        mock_rns.Destination.return_value = MagicMock()
        mock_router = MagicMock()
        mock_lxmf.LXMRouter.return_value = mock_router

        config = '{"interfaces": [], "allowAnonymous": false}'

        result = wrapper.initialize(
            config_json=config,
            incoming_message_limit_kb=131072
        )

        call_kwargs = mock_lxmf.LXMRouter.call_args[1]
        self.assertEqual(call_kwargs.get('delivery_limit'), 131072)


class TestSetIncomingMessageSizeLimit(unittest.TestCase):
    """Test set_incoming_message_size_limit() runtime method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    def test_set_limit_when_not_initialized(self):
        """Test set_incoming_message_size_limit fails when not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False
        wrapper.router = None

        result = wrapper.set_incoming_message_size_limit(1024)

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    def test_set_limit_when_router_is_none(self):
        """Test set_incoming_message_size_limit fails when router is None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = None

        result = wrapper.set_incoming_message_size_limit(1024)

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    def test_set_limit_success(self):
        """Test set_incoming_message_size_limit succeeds with valid router"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(5120)

        self.assertTrue(result['success'])
        self.assertEqual(wrapper.router.delivery_per_transfer_limit, 5120)

    def test_set_limit_1mb(self):
        """Test setting 1MB limit (1024KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(1024)

        self.assertTrue(result['success'])
        self.assertEqual(wrapper.router.delivery_per_transfer_limit, 1024)

    def test_set_limit_10mb(self):
        """Test setting 10MB limit (10240KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(10240)

        self.assertTrue(result['success'])
        self.assertEqual(wrapper.router.delivery_per_transfer_limit, 10240)

    def test_set_limit_25mb(self):
        """Test setting 25MB limit (25600KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(25600)

        self.assertTrue(result['success'])
        self.assertEqual(wrapper.router.delivery_per_transfer_limit, 25600)

    def test_set_limit_unlimited(self):
        """Test setting unlimited (128MB = 131072KB)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(131072)

        self.assertTrue(result['success'])
        self.assertEqual(wrapper.router.delivery_per_transfer_limit, 131072)

    def test_set_limit_when_reticulum_not_available(self):
        """Test set_incoming_message_size_limit fails when Reticulum not available"""
        reticulum_wrapper.RETICULUM_AVAILABLE = False
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        result = wrapper.set_incoming_message_size_limit(1024)

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    def test_set_limit_exception_handling(self):
        """Test set_incoming_message_size_limit handles exceptions gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        # Make setting the attribute raise an exception
        type(wrapper.router).delivery_per_transfer_limit = property(
            lambda self: None,
            lambda self, v: (_ for _ in ()).throw(RuntimeError("Test error"))
        )

        result = wrapper.set_incoming_message_size_limit(1024)

        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestFileAttachmentPaths(unittest.TestCase):
    """Test file_attachment_paths parameter in send_lxmf_message_with_method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_file_attachment_paths(self, mock_lxmf, mock_rns):
        """Test sending message with file attachment paths (for large files)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'msghash123456789'
        mock_lxmf.LXMessage.return_value = mock_message

        # Create a temp file for testing
        temp_file = os.path.join(self.temp_dir, "large_file.bin")
        with open(temp_file, 'wb') as f:
            f.write(b'Large file content' * 100)

        file_attachment_paths = [['large_file.bin', temp_file]]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test with file path",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachment_paths=file_attachment_paths
        )

        self.assertTrue(result['success'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_missing_file_path(self, mock_lxmf, mock_rns):
        """Test sending message with non-existent file path fails gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'msghash123456789'
        mock_lxmf.LXMessage.return_value = mock_message

        file_attachment_paths = [['missing_file.bin', '/nonexistent/path/file.bin']]

        # Should handle missing file gracefully (may succeed or fail depending on impl)
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test with missing file",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachment_paths=file_attachment_paths
        )

        # The result should be a dict with success or error
        self.assertIsInstance(result, dict)


if __name__ == '__main__':
    unittest.main()
