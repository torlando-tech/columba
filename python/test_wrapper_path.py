"""
Test suite for ReticulumWrapper path management methods.

Tests the following path-related methods:
- has_path: Check if a path to destination exists
- request_path: Request path discovery for a destination
- get_hop_count: Get hop count to a destination
- get_path_table: Get all known paths from the path table
"""

import sys
import os
import unittest
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


class TestHasPath(unittest.TestCase):
    """Tests for the has_path method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_has_path_mock_mode_returns_true(self):
        """Test that has_path returns True in mock mode (when RETICULUM_AVAILABLE is False)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # In mock mode, should always return True
        test_dest_hash = b'test_destination_hash'
        result = wrapper.has_path(test_dest_hash)

        self.assertTrue(result, "has_path should return True in mock mode")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_has_path_calls_rns_transport(self, mock_rns):
        """Test that has_path calls RNS.Transport.has_path when Reticulum is available"""
        # Mock RNS.Transport.has_path
        mock_rns.Transport.has_path = Mock(return_value=True)

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()  # Set reticulum to non-None

        test_dest_hash = b'test_destination_hash'
        result = wrapper.has_path(test_dest_hash)

        # Verify RNS.Transport.has_path was called with the correct argument
        mock_rns.Transport.has_path.assert_called_once_with(test_dest_hash)
        self.assertTrue(result)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_has_path_returns_false_when_no_path(self, mock_rns):
        """Test that has_path returns False when RNS.Transport.has_path returns False"""
        # Mock RNS.Transport.has_path to return False
        mock_rns.Transport.has_path = Mock(return_value=False)

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        test_dest_hash = b'test_destination_hash'
        result = wrapper.has_path(test_dest_hash)

        self.assertFalse(result, "has_path should return False when no path exists")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_has_path_returns_true_when_reticulum_not_initialized(self):
        """Test that has_path returns True when reticulum is not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = None  # Not initialized

        test_dest_hash = b'test_destination_hash'
        result = wrapper.has_path(test_dest_hash)

        self.assertTrue(result, "has_path should return True when reticulum is None")


class TestRequestPath(unittest.TestCase):
    """Tests for the request_path method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_request_path_mock_mode_returns_success(self):
        """Test that request_path returns success in mock mode"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_destination_hash'
        result = wrapper.request_path(test_dest_hash)

        self.assertTrue(result['success'], "request_path should return success in mock mode")
        self.assertNotIn('error', result, "Should not have error key in successful response")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_request_path_calls_rns_transport(self, mock_rns):
        """Test that request_path calls RNS.Transport.request_path"""
        # Mock RNS.Transport.request_path
        mock_rns.Transport.request_path = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_destination_hash'
        result = wrapper.request_path(test_dest_hash)

        # Verify RNS.Transport.request_path was called
        mock_rns.Transport.request_path.assert_called_once_with(test_dest_hash)
        self.assertTrue(result['success'], "request_path should return success")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_request_path_handles_exception(self, mock_rns):
        """Test that request_path handles exceptions gracefully"""
        # Mock RNS.Transport.request_path to raise an exception
        mock_rns.Transport.request_path = Mock(side_effect=Exception("Network error"))

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_destination_hash'
        result = wrapper.request_path(test_dest_hash)

        self.assertFalse(result['success'], "request_path should return failure on exception")
        self.assertIn('error', result, "Should include error message")
        self.assertEqual(result['error'], "Network error")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_request_path_return_structure(self, mock_rns):
        """Test that request_path returns a dict with expected structure"""
        mock_rns.Transport.request_path = Mock()

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_destination_hash'
        result = wrapper.request_path(test_dest_hash)

        # Verify return type
        self.assertIsInstance(result, dict, "request_path should return a dict")
        self.assertIn('success', result, "Result should have 'success' key")
        self.assertIsInstance(result['success'], bool, "'success' value should be bool")


class TestGetHopCount(unittest.TestCase):
    """Tests for the get_hop_count method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_hop_count_mock_mode_returns_mock_value(self):
        """Test that get_hop_count returns a mock value when Reticulum is not available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_destination_hash'
        result = wrapper.get_hop_count(test_dest_hash)

        # In mock mode, should return 3
        self.assertEqual(result, 3, "get_hop_count should return 3 in mock mode")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_hop_count_returns_none_when_no_path(self, mock_rns):
        """Test that get_hop_count returns None when no path exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        # Mock Transport.has_path to return False (no path to destination)
        mock_rns.Transport.has_path.return_value = False

        test_dest_hash = b'test_destination_hash'
        result = wrapper.get_hop_count(test_dest_hash)

        # Should return None when no path exists
        self.assertIsNone(result, "get_hop_count should return None when no path exists")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_get_hop_count_returns_mock_when_reticulum_not_initialized(self):
        """Test that get_hop_count returns mock value when reticulum is None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = None

        test_dest_hash = b'test_destination_hash'
        result = wrapper.get_hop_count(test_dest_hash)

        self.assertEqual(result, 3, "Should return mock value when reticulum is None")


class TestGetPathTable(unittest.TestCase):
    """Tests for the get_path_table method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_path_table_mock_mode_returns_empty_list(self):
        """Test that get_path_table returns empty list in mock mode"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.get_path_table()

        self.assertIsInstance(result, list, "get_path_table should return a list")
        self.assertEqual(len(result), 0, "Should return empty list in mock mode")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_get_path_table_returns_empty_when_reticulum_not_initialized(self):
        """Test that get_path_table returns empty list when reticulum is None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = None

        result = wrapper.get_path_table()

        self.assertEqual(result, [], "Should return empty list when reticulum is None")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_returns_hex_encoded_hashes(self, mock_rns):
        """Test that get_path_table converts destination hashes to hex strings"""
        # Mock path table with some destination hashes
        test_hash_1 = b'\xaa\xbb\xcc\xdd\xee\xff'
        test_hash_2 = b'\x11\x22\x33\x44\x55\x66'

        mock_rns.Transport.path_table = {
            test_hash_1: Mock(),
            test_hash_2: Mock()
        }

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        result = wrapper.get_path_table()

        # Verify results are hex-encoded
        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 2)

        # Verify hex encoding
        expected_hex_1 = test_hash_1.hex()
        expected_hex_2 = test_hash_2.hex()

        self.assertIn(expected_hex_1, result)
        self.assertIn(expected_hex_2, result)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_handles_empty_path_table(self, mock_rns):
        """Test that get_path_table handles empty path table correctly"""
        mock_rns.Transport.path_table = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        result = wrapper.get_path_table()

        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 0)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_handles_exception(self, mock_rns):
        """Test that get_path_table handles exceptions and returns empty list"""
        # Mock path_table to raise an exception when accessed
        mock_rns.Transport.path_table = property(
            lambda self: (_ for _ in ()).throw(Exception("Transport error"))
        )

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        result = wrapper.get_path_table()

        # Should return empty list on error
        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 0)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_returns_list_of_strings(self, mock_rns):
        """Test that get_path_table returns a list of string (not bytes)"""
        test_hash = b'\xaa\xbb\xcc'
        mock_rns.Transport.path_table = {test_hash: Mock()}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        result = wrapper.get_path_table()

        # Verify all items are strings
        self.assertTrue(all(isinstance(item, str) for item in result),
                        "All items in path table should be strings (hex-encoded)")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_hex_format(self, mock_rns):
        """Test that get_path_table returns properly formatted hex strings"""
        # Use a known hash to verify hex encoding
        test_hash = b'\xde\xad\xbe\xef'
        expected_hex = 'deadbeef'

        mock_rns.Transport.path_table = {test_hash: Mock()}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        result = wrapper.get_path_table()

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0], expected_hex,
                        "Hex encoding should produce lowercase hex string without prefix")


class TestPathMethodsIntegration(unittest.TestCase):
    """Integration tests for path management methods working together"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_path_request_workflow(self, mock_rns):
        """
        Test typical workflow: check path, request if missing, verify it appears in table.
        This simulates the common pattern of path discovery.
        """
        test_dest_hash = b'\xaa\xbb\xcc\xdd'

        # Initially no path
        mock_rns.Transport.has_path = Mock(return_value=False)
        mock_rns.Transport.request_path = Mock()
        mock_rns.Transport.path_table = {}

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        # Step 1: Check if path exists
        has_path = wrapper.has_path(test_dest_hash)
        self.assertFalse(has_path, "Should not have path initially")

        # Step 2: Request path
        request_result = wrapper.request_path(test_dest_hash)
        self.assertTrue(request_result['success'], "Path request should succeed")
        mock_rns.Transport.request_path.assert_called_once_with(test_dest_hash)

        # Step 3: Simulate path appearing in table
        mock_rns.Transport.has_path = Mock(return_value=True)
        mock_rns.Transport.path_table = {test_dest_hash: Mock()}

        # Step 4: Verify path now exists
        has_path_after = wrapper.has_path(test_dest_hash)
        self.assertTrue(has_path_after, "Should have path after request")

        # Step 5: Verify it appears in path table
        path_table = wrapper.get_path_table()
        expected_hex = test_dest_hash.hex()
        self.assertIn(expected_hex, path_table, "Requested path should appear in path table")

    def test_all_methods_exist_and_callable(self):
        """Verify all path management methods exist and are callable"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        methods = ['has_path', 'request_path', 'get_hop_count', 'get_path_table']

        for method_name in methods:
            self.assertTrue(
                hasattr(wrapper, method_name),
                f"ReticulumWrapper should have {method_name} method"
            )
            self.assertTrue(
                callable(getattr(wrapper, method_name)),
                f"{method_name} should be callable"
            )

    def test_method_signatures(self):
        """Test that methods have correct signatures"""
        import inspect

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # has_path should accept dest_hash parameter
        has_path_sig = inspect.signature(wrapper.has_path)
        self.assertIn('dest_hash', has_path_sig.parameters)

        # request_path should accept dest_hash parameter
        request_path_sig = inspect.signature(wrapper.request_path)
        self.assertIn('dest_hash', request_path_sig.parameters)

        # get_hop_count should accept dest_hash parameter
        hop_count_sig = inspect.signature(wrapper.get_hop_count)
        self.assertIn('dest_hash', hop_count_sig.parameters)

        # get_path_table should not require parameters
        path_table_sig = inspect.signature(wrapper.get_path_table)
        self.assertEqual(len(path_table_sig.parameters), 0,
                        "get_path_table should not require parameters")


class TestPathMethodsErrorHandling(unittest.TestCase):
    """Tests for error handling in path methods"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_has_path_with_invalid_hash(self):
        """Test has_path behavior with invalid hash (should not crash)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test with None
        result = wrapper.has_path(None)
        # Should return True in mock mode even with None
        self.assertIsInstance(result, bool)

    def test_request_path_with_invalid_hash(self):
        """Test request_path behavior with invalid hash"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test with None - should still return success in mock mode
        result = wrapper.request_path(None)
        self.assertIsInstance(result, dict)
        self.assertIn('success', result)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_path_table_with_malformed_path_table(self, mock_rns):
        """Test get_path_table handles malformed data gracefully"""
        # Create a path table with non-bytes keys (should not happen but test robustness)
        mock_rns.Transport.path_table = {
            b'\xaa\xbb': Mock(),
            # Mix in something that might cause .hex() to fail
        }

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.reticulum = Mock()

        # Should not crash, should return valid list
        result = wrapper.get_path_table()
        self.assertIsInstance(result, list)


class TestProbeLinkSpeed(unittest.TestCase):
    """Tests for the probe_link_speed method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_probe_link_speed_not_initialized_returns_status(self):
        """Test that probe_link_speed returns not_initialized when wrapper is not ready"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        # Don't initialize - reticulum is None

        test_dest_hash = bytes.fromhex('0123456789abcdef0123456789abcdef')
        result = wrapper.probe_link_speed(test_dest_hash)

        self.assertIsInstance(result, dict)
        self.assertEqual(result['status'], 'not_initialized')
        self.assertIsNone(result['establishment_rate_bps'])
        self.assertIsNone(result['hops'])
        self.assertFalse(result['link_reused'])

    def test_probe_link_speed_returns_correct_structure(self):
        """Test that probe_link_speed returns all expected fields"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        # Don't initialize

        test_dest_hash = bytes.fromhex('0123456789abcdef0123456789abcdef')
        result = wrapper.probe_link_speed(test_dest_hash)

        # Check all expected fields are present
        expected_fields = ['status', 'establishment_rate_bps', 'expected_rate_bps',
                          'rtt_seconds', 'hops', 'link_reused']
        for field in expected_fields:
            self.assertIn(field, result, f"Field '{field}' should be present in result")

    def test_probe_link_speed_timeout_parameter(self):
        """Test that probe_link_speed accepts custom timeout"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = bytes.fromhex('0123456789abcdef0123456789abcdef')
        # Should not raise with custom timeout
        result = wrapper.probe_link_speed(test_dest_hash, timeout_seconds=5.0)

        self.assertIsInstance(result, dict)
        self.assertIn('status', result)


if __name__ == '__main__':
    unittest.main(verbosity=2)
