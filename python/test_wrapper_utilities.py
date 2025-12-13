"""
Test suite for ReticulumWrapper utility and debug methods.
Tests utility methods like echo, simple_method, sleep and debug methods
like get_debug_info, get_failed_interfaces, get_local_identity_info, etc.
"""

import sys
import os
import unittest
import json
import time
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


class TestUtilityMethods(unittest.TestCase):
    """Test utility methods used for threading safety verification"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_echo_returns_message_unchanged(self):
        """Test that echo method returns the input message unchanged"""
        test_message = "Hello, World!"
        result = self.wrapper.echo(test_message)
        self.assertEqual(result, test_message)

    def test_echo_handles_empty_string(self):
        """Test that echo method handles empty strings"""
        result = self.wrapper.echo("")
        self.assertEqual(result, "")

    def test_echo_handles_special_characters(self):
        """Test that echo method handles special characters and unicode"""
        test_cases = [
            "Special chars: !@#$%^&*()",
            "Unicode: 你好世界",
            "Emojis: 🚀🎉",
            "Newlines:\nand\ttabs",
        ]
        for test_message in test_cases:
            with self.subTest(message=test_message):
                result = self.wrapper.echo(test_message)
                self.assertEqual(result, test_message)

    def test_simple_method_returns_value_unchanged(self):
        """Test that simple_method returns the input value unchanged"""
        test_value = 42
        result = self.wrapper.simple_method(test_value)
        self.assertEqual(result, test_value)

    def test_simple_method_handles_zero(self):
        """Test that simple_method handles zero"""
        result = self.wrapper.simple_method(0)
        self.assertEqual(result, 0)

    def test_simple_method_handles_negative_numbers(self):
        """Test that simple_method handles negative numbers"""
        test_value = -100
        result = self.wrapper.simple_method(test_value)
        self.assertEqual(result, test_value)

    def test_simple_method_handles_large_numbers(self):
        """Test that simple_method handles large numbers"""
        test_value = 999999999
        result = self.wrapper.simple_method(test_value)
        self.assertEqual(result, test_value)

    def test_sleep_blocks_for_specified_duration(self):
        """Test that sleep method blocks for approximately the specified duration"""
        sleep_duration = 0.1  # 100ms
        start_time = time.time()
        self.wrapper.sleep(sleep_duration)
        end_time = time.time()

        elapsed_time = end_time - start_time
        # Allow some tolerance for timing (50ms tolerance)
        self.assertGreaterEqual(elapsed_time, sleep_duration - 0.05)
        self.assertLess(elapsed_time, sleep_duration + 0.15)

    def test_sleep_handles_zero_duration(self):
        """Test that sleep method handles zero duration without error"""
        # Should complete quickly without blocking
        start_time = time.time()
        self.wrapper.sleep(0)
        end_time = time.time()

        elapsed_time = end_time - start_time
        # Should be nearly instant (less than 50ms)
        self.assertLess(elapsed_time, 0.05)

    def test_sleep_returns_none(self):
        """Test that sleep method returns None"""
        result = self.wrapper.sleep(0.01)
        self.assertIsNone(result)


class TestTransportIdentityHash(unittest.TestCase):
    """Test get_transport_identity_hash method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_none_when_not_initialized(self):
        """Test that get_transport_identity_hash returns None when not initialized"""
        self.wrapper.initialized = False
        self.wrapper.transport_identity_hash = None

        result = self.wrapper.get_transport_identity_hash()
        self.assertIsNone(result)

    def test_returns_none_when_hash_not_set(self):
        """Test that get_transport_identity_hash returns None when hash is None"""
        self.wrapper.initialized = True
        self.wrapper.transport_identity_hash = None

        result = self.wrapper.get_transport_identity_hash()
        self.assertIsNone(result)

    def test_returns_hash_when_initialized(self):
        """Test that get_transport_identity_hash returns the hash when initialized"""
        test_hash = b'0123456789abcdef'  # 16-byte hash
        self.wrapper.initialized = True
        self.wrapper.transport_identity_hash = test_hash

        result = self.wrapper.get_transport_identity_hash()
        self.assertEqual(result, test_hash)

    def test_returns_16_byte_hash(self):
        """Test that the returned hash is 16 bytes for BLE Protocol v2"""
        test_hash = b'0123456789abcdef'
        self.wrapper.initialized = True
        self.wrapper.transport_identity_hash = test_hash

        result = self.wrapper.get_transport_identity_hash()
        self.assertEqual(len(result), 16)


class TestDebugInfo(unittest.TestCase):
    """Test get_debug_info method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_dict(self):
        """Test that get_debug_info returns a dictionary"""
        result = self.wrapper.get_debug_info()
        self.assertIsInstance(result, dict)

    def test_contains_initialized_field(self):
        """Test that debug info contains 'initialized' field"""
        result = self.wrapper.get_debug_info()
        self.assertIn('initialized', result)
        self.assertIsInstance(result['initialized'], bool)

    def test_contains_reticulum_available_field(self):
        """Test that debug info contains 'reticulum_available' field"""
        result = self.wrapper.get_debug_info()
        self.assertIn('reticulum_available', result)

    def test_contains_storage_path_field(self):
        """Test that debug info contains 'storage_path' field"""
        result = self.wrapper.get_debug_info()
        self.assertIn('storage_path', result)
        self.assertEqual(result['storage_path'], self.temp_dir)

    def test_contains_failed_interfaces_field(self):
        """Test that debug info contains 'failed_interfaces' field"""
        result = self.wrapper.get_debug_info()
        self.assertIn('failed_interfaces', result)
        self.assertIsInstance(result['failed_interfaces'], list)

    def test_initialized_reflects_wrapper_state(self):
        """Test that 'initialized' field reflects wrapper's state"""
        # Test when not initialized
        self.wrapper.initialized = False
        result = self.wrapper.get_debug_info()
        self.assertFalse(result['initialized'])

        # Test when initialized
        self.wrapper.initialized = True
        result = self.wrapper.get_debug_info()
        self.assertTrue(result['initialized'])

    def test_failed_interfaces_includes_added_failures(self):
        """Test that failed_interfaces field includes interface failures"""
        # Add a failed interface
        test_failure = {
            'name': 'test_interface',
            'error': 'Connection failed',
            'recoverable': False
        }
        self.wrapper.failed_interfaces.append(test_failure)

        result = self.wrapper.get_debug_info()
        self.assertIn('failed_interfaces', result)
        self.assertEqual(len(result['failed_interfaces']), 1)
        self.assertEqual(result['failed_interfaces'][0], test_failure)


class TestFailedInterfaces(unittest.TestCase):
    """Test get_failed_interfaces method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_json_string(self):
        """Test that get_failed_interfaces returns a JSON string"""
        result = self.wrapper.get_failed_interfaces()
        self.assertIsInstance(result, str)

        # Should be valid JSON
        parsed = json.loads(result)
        self.assertIsInstance(parsed, list)

    def test_returns_empty_list_when_no_failures(self):
        """Test that get_failed_interfaces returns empty list when no failures"""
        result = self.wrapper.get_failed_interfaces()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    def test_returns_failed_interfaces_list(self):
        """Test that get_failed_interfaces returns list of failed interfaces"""
        # Add some failed interfaces
        test_failures = [
            {
                'name': 'ble_interface',
                'error': 'Bluetooth not available',
                'recoverable': True
            },
            {
                'name': 'tcp_interface',
                'error': 'Connection refused',
                'recoverable': False
            }
        ]
        self.wrapper.failed_interfaces = test_failures

        result = self.wrapper.get_failed_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 2)
        self.assertEqual(parsed[0]['name'], 'ble_interface')
        self.assertEqual(parsed[0]['error'], 'Bluetooth not available')
        self.assertTrue(parsed[0]['recoverable'])
        self.assertEqual(parsed[1]['name'], 'tcp_interface')
        self.assertEqual(parsed[1]['error'], 'Connection refused')
        self.assertFalse(parsed[1]['recoverable'])

    def test_handles_missing_failed_interfaces_attribute(self):
        """Test that get_failed_interfaces handles missing attribute gracefully"""
        # Remove the attribute if it exists
        if hasattr(self.wrapper, 'failed_interfaces'):
            delattr(self.wrapper, 'failed_interfaces')

        result = self.wrapper.get_failed_interfaces()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])


class TestLocalIdentityInfo(unittest.TestCase):
    """Test get_local_identity_info method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_none_when_no_identity_exists(self):
        """
        Test that get_local_identity_info returns None when no identity exists.

        Note: Per the implementation, this method currently always returns None
        as local identity management is not yet implemented. This test documents
        the current behavior.
        """
        result = self.wrapper.get_local_identity_info()
        self.assertIsNone(result)

    def test_return_type_is_optional_dict(self):
        """
        Test that get_local_identity_info returns either None or a Dict.

        This test validates the method signature which specifies Optional[Dict].
        When identity management is implemented, this test should be updated
        to verify the Dict structure.
        """
        result = self.wrapper.get_local_identity_info()
        self.assertTrue(result is None or isinstance(result, dict))


class TestThreadingSafety(unittest.TestCase):
    """Test that utility methods are thread-safe"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_echo_is_thread_safe(self):
        """
        Test that echo method can be called from multiple threads.

        The echo method should be thread-safe as it doesn't access shared state.
        """
        import threading

        results = []
        errors = []

        def call_echo(message):
            try:
                result = self.wrapper.echo(message)
                results.append(result)
            except Exception as e:
                errors.append(e)

        # Create multiple threads calling echo concurrently
        threads = []
        for i in range(10):
            thread = threading.Thread(target=call_echo, args=(f"Message {i}",))
            threads.append(thread)
            thread.start()

        # Wait for all threads to complete
        for thread in threads:
            thread.join()

        # Verify no errors occurred
        self.assertEqual(len(errors), 0, f"Thread-safety errors: {errors}")

        # Verify all calls succeeded
        self.assertEqual(len(results), 10)

    def test_simple_method_is_thread_safe(self):
        """
        Test that simple_method can be called from multiple threads.

        The simple_method should be thread-safe as it doesn't access shared state.
        """
        import threading

        results = []
        errors = []

        def call_simple_method(value):
            try:
                result = self.wrapper.simple_method(value)
                results.append(result)
            except Exception as e:
                errors.append(e)

        # Create multiple threads calling simple_method concurrently
        threads = []
        for i in range(10):
            thread = threading.Thread(target=call_simple_method, args=(i,))
            threads.append(thread)
            thread.start()

        # Wait for all threads to complete
        for thread in threads:
            thread.join()

        # Verify no errors occurred
        self.assertEqual(len(errors), 0, f"Thread-safety errors: {errors}")

        # Verify all calls succeeded
        self.assertEqual(len(results), 10)

        # Verify results contain expected values
        self.assertEqual(set(results), set(range(10)))


if __name__ == '__main__':
    unittest.main()
