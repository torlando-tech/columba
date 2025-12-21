"""
Test suite for path request functionality.

When sending messages to destinations that haven't announced since app start,
Columba should automatically request a path from the network and wait for a response.
"""

import sys
import os
import time
import unittest
from unittest.mock import Mock, MagicMock, patch, call

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
mock_rns = MagicMock()
mock_lxmf = MagicMock()
mock_lxmf.LXMessage.OPPORTUNISTIC = 0x01
mock_lxmf.LXMessage.DIRECT = 0x02
mock_lxmf.LXMessage.PROPAGATED = 0x03
mock_lxmf.LXMessage.SENT = 0x04

sys.modules['RNS'] = mock_rns
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf

# Now import after mocking
import reticulum_wrapper


class TestPathRequestIntegration(unittest.TestCase):
    """Integration tests that verify the path request code is present and structured correctly."""

    def test_path_request_code_exists_in_send_lxmf_message(self):
        """Verify that send_lxmf_message contains path request logic"""
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message)

        # Check for key patterns that indicate path request logic
        self.assertIn('Transport.request_path', source,
                      "send_lxmf_message should call Transport.request_path")
        self.assertIn('Identity not found, requesting path', source,
                      "send_lxmf_message should log path request")
        self.assertIn('Identity resolved after path request', source,
                      "send_lxmf_message should log successful resolution")

    def test_path_request_code_exists_in_send_lxmf_message_with_method(self):
        """Verify that send_lxmf_message_with_method contains path request logic"""
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message_with_method)

        # Check for key patterns
        self.assertIn('Transport.request_path', source,
                      "send_lxmf_message_with_method should call Transport.request_path")
        self.assertIn('Identity not found, requesting path', source,
                      "send_lxmf_message_with_method should log path request")

    def test_retry_loop_has_correct_timing(self):
        """Verify the retry loop parameters (10 iterations, 0.5s sleep = 5s total)"""
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message_with_method)

        # Check for the retry loop structure
        self.assertIn('range(10)', source,
                      "Retry loop should iterate 10 times")
        self.assertIn('sleep(0.5)', source,
                      "Should sleep 0.5 seconds between retries")


class TestPathRequestErrorMessages(unittest.TestCase):
    """Tests for correct error messages when path request fails."""

    def test_error_message_mentions_path_requested(self):
        """Verify error message indicates path was requested"""
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message_with_method)

        # The error message should indicate that a path was requested
        self.assertIn('Path requested but no response received', source,
                      "Error message should mention path was requested")


class TestPathRequestMocking(unittest.TestCase):
    """Unit tests using mocking to verify path request behavior."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_wrapper_initialization(self):
        """Test that wrapper initializes correctly with mocked RNS"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.assertIsNotNone(wrapper)
        self.assertEqual(wrapper.storage_path, self.temp_dir)

    def test_identities_dict_exists(self):
        """Test that wrapper has identities cache"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.assertTrue(hasattr(wrapper, 'identities'))
        self.assertIsInstance(wrapper.identities, dict)

    def test_path_request_function_callable(self):
        """Test that Transport.request_path is accessible"""
        # Verify the mock structure
        self.assertTrue(callable(mock_rns.Transport.request_path))


class TestPathRequestLogic(unittest.TestCase):
    """Direct tests of the path request decision logic."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_identity_recall_is_called(self):
        """Test that Identity.recall is called during send preparation"""
        # This is a structural test - verify the code path exists
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message_with_method)

        # Should try to recall identity first
        self.assertIn('Identity.recall(dest_hash)', source)
        # Should also try with from_identity_hash=True
        self.assertIn('from_identity_hash=True', source)

    def test_local_cache_fallback_exists(self):
        """Test that local identities cache is checked"""
        import inspect
        source = inspect.getsource(reticulum_wrapper.ReticulumWrapper.send_lxmf_message_with_method)

        # Should check local cache
        self.assertIn('self.identities', source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
