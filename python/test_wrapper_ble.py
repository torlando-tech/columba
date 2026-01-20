"""
Test suite for ReticulumWrapper BLE and RNode interface methods

Tests BLE interface initialization, RNode interface initialization,
and deprecated BLE methods (ble_packet_received, poll_ble_incoming, send_via_ble).
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch, call
import threading
import tempfile
import shutil

# Add parent directory to path to import modules
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS before importing reticulum_wrapper
mock_rns = MagicMock()
mock_rns.LOG_DEBUG = 5
mock_rns.LOG_INFO = 4
mock_rns.LOG_WARNING = 3
mock_rns.LOG_ERROR = 2
mock_rns.LOG_EXTREME = 6
mock_rns.log = MagicMock()

# Create persistent Transport mock with interfaces list
mock_transport = MagicMock()
mock_transport.interfaces = []
mock_rns.Transport = mock_transport

sys.modules['RNS'] = mock_rns
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()

# Mock LXMF
sys.modules['LXMF'] = MagicMock()

# Mock rnode_interface module (needed for patching)
sys.modules['rnode_interface'] = MagicMock()

# Import after mocking
import reticulum_wrapper


class TestBLEInterfaceInitialization(unittest.TestCase):
    """Test initialize_ble_interface() with driver-based architecture."""

    def setUp(self):
        """Set up test fixtures."""
        # Reset Transport.interfaces before each test
        mock_rns.Transport.interfaces = []

        # Ensure reticulum_wrapper's global RNS is set to our mock
        reticulum_wrapper.RNS = mock_rns
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Create wrapper instance
        self.wrapper = reticulum_wrapper.ReticulumWrapper('/tmp/test')
        self.wrapper.initialized = True  # Mark as initialized

    def tearDown(self):
        """Clean up after each test."""
        mock_rns.Transport.interfaces = []

    def test_initialize_ble_interface_when_not_initialized(self):
        """
        Test that initialize_ble_interface fails if Reticulum is not initialized.

        Returns error indicating Reticulum must be initialized first.
        """
        self.wrapper.initialized = False

        result = self.wrapper.initialize_ble_interface()

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'Reticulum not initialized')

    def test_initialize_ble_interface_not_found(self):
        """
        Test that initialize_ble_interface fails if AndroidBLEInterface not in mock_rns.Transport.interfaces.

        When RNS doesn't load AndroidBLEInterface from config, returns error.
        """
        # Mock some other interface
        mock_interface = Mock()
        mock_interface.__class__.__name__ = 'TCPInterface'
        mock_rns.Transport.interfaces = [mock_interface]

        result = self.wrapper.initialize_ble_interface()

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'AndroidBLEInterface not loaded by RNS')

    def test_initialize_ble_interface_found_and_offline(self):
        """
        Test successful BLE interface initialization when interface is offline.

        When AndroidBLEInterface is found in mock_rns.Transport.interfaces and is offline,
        should call start() and return success.
        """
        # Mock AndroidBLEInterface
        mock_ble_interface = Mock()
        mock_ble_interface.__class__.__name__ = 'AndroidBLEInterface'
        mock_ble_interface.name = 'ble0'
        mock_ble_interface.online = False
        mock_ble_interface.start = Mock()

        mock_rns.Transport.interfaces = [mock_ble_interface]

        result = self.wrapper.initialize_ble_interface()

        self.assertTrue(result['success'])
        mock_ble_interface.start.assert_called_once()
        self.assertEqual(self.wrapper.ble_interface, mock_ble_interface)

    def test_initialize_ble_interface_found_and_already_online(self):
        """
        Test BLE interface initialization when interface is already online.

        When AndroidBLEInterface is found and already online, should not call start()
        but still return success.
        """
        # Mock AndroidBLEInterface
        mock_ble_interface = Mock()
        mock_ble_interface.__class__.__name__ = 'AndroidBLEInterface'
        mock_ble_interface.name = 'ble0'
        mock_ble_interface.online = True
        mock_ble_interface.start = Mock()

        mock_rns.Transport.interfaces = [mock_ble_interface]

        result = self.wrapper.initialize_ble_interface()

        self.assertTrue(result['success'])
        mock_ble_interface.start.assert_not_called()
        self.assertEqual(self.wrapper.ble_interface, mock_ble_interface)

    def test_initialize_ble_interface_finds_correct_interface(self):
        """
        Test that initialize_ble_interface finds AndroidBLEInterface among multiple interfaces.

        When multiple interfaces exist, should match by class name and find the correct one.
        """
        # Mock multiple interfaces
        mock_tcp_interface = Mock()
        mock_tcp_interface.__class__.__name__ = 'TCPInterface'

        mock_ble_interface = Mock()
        mock_ble_interface.__class__.__name__ = 'AndroidBLEInterface'
        mock_ble_interface.name = 'ble0'
        mock_ble_interface.online = False
        mock_ble_interface.start = Mock()

        mock_udp_interface = Mock()
        mock_udp_interface.__class__.__name__ = 'UDPInterface'

        mock_rns.Transport.interfaces = [mock_tcp_interface, mock_ble_interface, mock_udp_interface]

        result = self.wrapper.initialize_ble_interface()

        self.assertTrue(result['success'])
        self.assertEqual(self.wrapper.ble_interface, mock_ble_interface)

    def test_initialize_ble_interface_exception_handling(self):
        """
        Test that initialize_ble_interface handles exceptions gracefully.

        When start() raises an exception, should catch it and return error.
        """
        # Mock AndroidBLEInterface that raises exception on start()
        mock_ble_interface = Mock()
        mock_ble_interface.__class__.__name__ = 'AndroidBLEInterface'
        mock_ble_interface.name = 'ble0'
        mock_ble_interface.online = False
        mock_ble_interface.start = Mock(side_effect=Exception("Bluetooth not available"))

        mock_rns.Transport.interfaces = [mock_ble_interface]

        result = self.wrapper.initialize_ble_interface()

        self.assertFalse(result['success'])
        self.assertIn('Bluetooth not available', result['error'])


class TestRNodeInterfaceInitialization(unittest.TestCase):
    """Test initialize_rnode_interface() with Kotlin bridge architecture."""

    def setUp(self):
        """Set up test fixtures."""
        # Reset Transport.interfaces before each test
        mock_rns.Transport.interfaces = []

        # Ensure reticulum_wrapper's global RNS is set to our mock
        reticulum_wrapper.RNS = mock_rns
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Create wrapper instance
        self.wrapper = reticulum_wrapper.ReticulumWrapper('/tmp/test')
        self.wrapper.initialized = True

        # Set up RNode config (as list for multiple interface support)
        self.wrapper._pending_rnode_configs = [{
            'name': 'rnode0',
            'target_device_name': 'RNode ABC123',
            'port': 'mock_port',
            'speed': 115200
        }]

        # Set up mock Kotlin bridge
        self.wrapper.kotlin_rnode_bridge = Mock()
        self.wrapper.kotlin_rnode_bridge.notifyError = Mock()
        self.wrapper.kotlin_rnode_bridge.notifyOnlineStatusChanged = Mock()

    def tearDown(self):
        """Clean up after each test."""
        mock_rns.Transport.interfaces = []

    def test_initialize_rnode_interface_when_not_initialized(self):
        """
        Test that initialize_rnode_interface fails if Reticulum is not initialized.

        Returns error indicating Reticulum must be initialized first.
        """
        self.wrapper.initialized = False

        result = self.wrapper.initialize_rnode_interface()

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'Reticulum not initialized')

    def test_initialize_rnode_interface_no_pending_config(self):
        """
        Test that initialize_rnode_interface succeeds gracefully when no config is pending.

        When no RNode config is set, returns success with message.
        """
        self.wrapper._pending_rnode_configs = []

        result = self.wrapper.initialize_rnode_interface()

        self.assertTrue(result['success'])
        self.assertEqual(result['message'], 'No RNode interface configured')

    def test_initialize_rnode_interface_no_kotlin_bridge(self):
        """
        Test that initialize_rnode_interface fails if Kotlin bridge is not set.

        Returns error indicating bridge must be set first.
        """
        self.wrapper.kotlin_rnode_bridge = None

        result = self.wrapper.initialize_rnode_interface()

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'KotlinRNodeBridge not set. Call set_rnode_bridge() first.')

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_creates_interface(self, mock_rnode_interface_class):
        """
        Test successful RNode interface creation.

        When all prerequisites are met, should create ColumbaRNodeInterface,
        set up callbacks, start it, and register with mock_rns.Transport.
        """
        # Mock the ColumbaRNodeInterface
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = True
        mock_rnode_interface.start = Mock(return_value=True)
        mock_rnode_interface.setOnErrorReceived = Mock()
        mock_rnode_interface.setOnOnlineStatusChanged = Mock()
        mock_rnode_interface_class.return_value = mock_rnode_interface

        # Save the config before it gets cleared
        expected_config = self.wrapper._pending_rnode_configs[0]

        result = self.wrapper.initialize_rnode_interface()

        # Verify interface was created with correct parameters
        mock_rnode_interface_class.assert_called_once_with(
            owner=self.wrapper,
            name='rnode0',
            config=expected_config
        )

        # Verify callbacks were set up
        mock_rnode_interface.setOnErrorReceived.assert_called_once()
        mock_rnode_interface.setOnOnlineStatusChanged.assert_called_once()

        # Verify interface was started
        mock_rnode_interface.start.assert_called_once()

        # Verify interface was registered with Transport
        self.assertIn(mock_rnode_interface, mock_rns.Transport.interfaces)

        # Verify pending configs were cleared
        self.assertEqual(self.wrapper._pending_rnode_configs, [])

        # Verify interface stored in dict
        self.assertIn('rnode0', self.wrapper.rnode_interfaces)

        # Verify success
        self.assertTrue(result['success'])

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_start_failure(self, mock_rnode_interface_class):
        """
        Test RNode interface initialization when start() fails.

        Even if start() returns False, interface should still be registered
        (for auto-reconnect capability) and return success.
        """
        # Mock interface that fails to start
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = False
        mock_rnode_interface.start = Mock(return_value=False)
        mock_rnode_interface.setOnErrorReceived = Mock()
        mock_rnode_interface.setOnOnlineStatusChanged = Mock()
        mock_rnode_interface_class.return_value = mock_rnode_interface

        result = self.wrapper.initialize_rnode_interface()

        # Verify interface was still registered despite start failure
        self.assertIn(mock_rnode_interface, mock_rns.Transport.interfaces)

        # Should still return success (interface has auto-reconnect)
        self.assertTrue(result['success'])

    def test_initialize_rnode_interface_reconnect_offline_interface(self):
        """
        Test reconnecting an existing offline RNode interface.

        When interface exists but is offline, should call start() to reconnect.
        """
        # Set up existing offline interface in the interfaces dict
        mock_existing_interface = Mock()
        mock_existing_interface.online = False
        mock_existing_interface.start = Mock(return_value=True)
        self.wrapper.rnode_interfaces = {'rnode0': mock_existing_interface}
        self.wrapper._pending_rnode_configs = []  # No new configs, just reconnect

        result = self.wrapper.initialize_rnode_interface()

        mock_existing_interface.start.assert_called_once()
        self.assertTrue(result['success'])
        self.assertEqual(result['message'], 'Existing interfaces checked')

    def test_initialize_rnode_interface_reconnect_failure(self):
        """
        Test failure when reconnecting existing offline interface.

        When start() fails on existing offline interface, the interface
        remains registered for auto-reconnect and returns success.
        """
        # Set up existing offline interface that fails to reconnect
        mock_existing_interface = Mock()
        mock_existing_interface.online = False
        mock_existing_interface.start = Mock(return_value=False)
        self.wrapper.rnode_interfaces = {'rnode0': mock_existing_interface}
        self.wrapper._pending_rnode_configs = []  # No new configs

        result = self.wrapper.initialize_rnode_interface()

        # With multi-interface support, reconnect failure doesn't fail the whole operation
        # The interface stays registered for auto-reconnect
        self.assertTrue(result['success'])
        self.assertEqual(result['message'], 'Existing interfaces checked')

    def test_initialize_rnode_interface_already_online(self):
        """
        Test initialization when interface is already online.

        When interface exists and is already online, should skip initialization.
        """
        # Set up existing online interface in the interfaces dict
        mock_existing_interface = Mock()
        mock_existing_interface.online = True
        mock_existing_interface.start = Mock()
        self.wrapper.rnode_interfaces = {'rnode0': mock_existing_interface}
        self.wrapper._pending_rnode_configs = []  # No new configs

        result = self.wrapper.initialize_rnode_interface()

        # Should not call start()
        mock_existing_interface.start.assert_not_called()
        self.assertTrue(result['success'])
        self.assertEqual(result['message'], 'Existing interfaces checked')

    def test_initialize_rnode_interface_concurrent_calls_prevented(self):
        """
        Test that concurrent initialization calls are prevented.

        When initialization is already in progress, subsequent calls should
        return immediately without initializing again.
        """
        # Simulate initialization in progress
        self.wrapper._rnode_initializing = True

        result = self.wrapper.initialize_rnode_interface()

        self.assertTrue(result['success'])
        self.assertEqual(result['message'], 'Initialization already in progress')

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_error_callback(self, mock_rnode_interface_class):
        """
        Test that error callback is properly set up and notifies Kotlin.

        When error callback is triggered, should notify Kotlin bridge.
        """
        # Mock the ColumbaRNodeInterface
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = True
        mock_rnode_interface.start = Mock(return_value=True)
        mock_rnode_interface.setOnErrorReceived = Mock()
        mock_rnode_interface.setOnOnlineStatusChanged = Mock()
        mock_rnode_interface_class.return_value = mock_rnode_interface

        result = self.wrapper.initialize_rnode_interface()

        # Get the error callback that was registered
        error_callback = mock_rnode_interface.setOnErrorReceived.call_args[0][0]

        # Trigger the error callback
        error_callback(123, "Test error")

        # Verify Kotlin bridge was notified
        self.wrapper.kotlin_rnode_bridge.notifyError.assert_called_once_with(123, "Test error")

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_online_status_callback(self, mock_rnode_interface_class):
        """
        Test that online status callback is properly set up and notifies Kotlin.

        When online status changes, should notify Kotlin bridge.
        """
        # Mock the ColumbaRNodeInterface
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = True
        mock_rnode_interface.start = Mock(return_value=True)
        mock_rnode_interface.setOnErrorReceived = Mock()
        mock_rnode_interface.setOnOnlineStatusChanged = Mock()
        mock_rnode_interface_class.return_value = mock_rnode_interface

        result = self.wrapper.initialize_rnode_interface()

        # Get the online status callback that was registered
        status_callback = mock_rnode_interface.setOnOnlineStatusChanged.call_args[0][0]

        # Trigger the callback
        status_callback(True)

        # Verify Kotlin bridge was notified
        self.wrapper.kotlin_rnode_bridge.notifyOnlineStatusChanged.assert_called_once_with(True)

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_callback_exception_handling(self, mock_rnode_interface_class):
        """
        Test that callback exceptions are handled gracefully.

        When Kotlin bridge raises exception, should catch it and not propagate.
        """
        # Mock the ColumbaRNodeInterface
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = True
        mock_rnode_interface.start = Mock(return_value=True)
        mock_rnode_interface.setOnErrorReceived = Mock()
        mock_rnode_interface.setOnOnlineStatusChanged = Mock()
        mock_rnode_interface_class.return_value = mock_rnode_interface

        # Make Kotlin bridge raise exception
        self.wrapper.kotlin_rnode_bridge.notifyError.side_effect = Exception("Bridge error")

        result = self.wrapper.initialize_rnode_interface()

        # Get the error callback
        error_callback = mock_rnode_interface.setOnErrorReceived.call_args[0][0]

        # Trigger the callback - should not raise exception
        try:
            error_callback(123, "Test error")
        except Exception:
            self.fail("Error callback should not propagate exceptions")

    def test_initialize_rnode_interface_thread_safety(self):
        """
        Test that concurrent initialization attempts are thread-safe.

        Multiple threads calling initialize_rnode_interface should be serialized
        by the lock.
        """
        results = []

        def init_rnode():
            result = self.wrapper.initialize_rnode_interface()
            results.append(result)

        # Create multiple threads
        threads = [threading.Thread(target=init_rnode) for _ in range(5)]

        # Start all threads
        for t in threads:
            t.start()

        # Wait for all threads to complete
        for t in threads:
            t.join()

        # Verify we got results from all threads
        self.assertEqual(len(results), 5)

        # At least one should succeed, others should either succeed or report
        # "already in progress"
        success_count = sum(1 for r in results if r['success'])
        self.assertGreater(success_count, 0)


class TestDeprecatedBLEMethods(unittest.TestCase):
    """Test deprecated BLE methods (backward compatibility stubs)."""

    def setUp(self):
        """Set up test fixtures."""
        self.wrapper = reticulum_wrapper.ReticulumWrapper('/tmp/test')

    def test_ble_packet_received_deprecated(self):
        """
        Test that ble_packet_received is deprecated and does nothing.

        Method should be callable for backward compatibility but do nothing.
        """
        # Should not raise exception
        try:
            self.wrapper.ble_packet_received("AA:BB:CC:DD:EE:FF", b"test data")
        except Exception:
            self.fail("ble_packet_received should not raise exception")

    def test_poll_ble_incoming_deprecated(self):
        """
        Test that poll_ble_incoming is deprecated and returns empty list.

        Method should return empty list for backward compatibility.
        """
        result = self.wrapper.poll_ble_incoming()

        self.assertEqual(result, [])
        self.assertIsInstance(result, list)

    def test_send_via_ble_deprecated(self):
        """
        Test that send_via_ble is deprecated and returns failure.

        Method should return failure dict for backward compatibility.
        """
        result = self.wrapper.send_via_ble("AA:BB:CC:DD:EE:FF", b"test data")

        self.assertFalse(result['success'])
        self.assertIn('deprecated', result['error'].lower())


class TestBLEInterfaceEdgeCases(unittest.TestCase):
    """Test edge cases and error conditions for BLE interface."""

    def setUp(self):
        """Set up test fixtures."""
        mock_rns.Transport.interfaces = []

        # Ensure reticulum_wrapper's global RNS is set to our mock
        reticulum_wrapper.RNS = mock_rns
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        self.wrapper = reticulum_wrapper.ReticulumWrapper('/tmp/test')
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up after each test."""
        mock_rns.Transport.interfaces = []

    def test_initialize_ble_interface_empty_interfaces_list(self):
        """
        Test BLE initialization with empty interfaces list.

        Should return error when no interfaces exist.
        """
        mock_rns.Transport.interfaces = []

        result = self.wrapper.initialize_ble_interface()

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'AndroidBLEInterface not loaded by RNS')

    def test_initialize_ble_interface_multiple_calls(self):
        """
        Test calling initialize_ble_interface multiple times.

        Should handle multiple calls gracefully.
        """
        # Mock AndroidBLEInterface
        mock_ble_interface = Mock()
        mock_ble_interface.__class__.__name__ = 'AndroidBLEInterface'
        mock_ble_interface.name = 'ble0'
        mock_ble_interface.online = False
        mock_ble_interface.start = Mock()

        mock_rns.Transport.interfaces = [mock_ble_interface]

        # First call
        result1 = self.wrapper.initialize_ble_interface()
        self.assertTrue(result1['success'])

        # Second call - interface is now online
        mock_ble_interface.online = True
        result2 = self.wrapper.initialize_ble_interface()
        self.assertTrue(result2['success'])

        # start() should only be called once (first time when offline)
        self.assertEqual(mock_ble_interface.start.call_count, 1)


class TestRNodeInterfaceEdgeCases(unittest.TestCase):
    """Test edge cases and error conditions for RNode interface."""

    def setUp(self):
        """Set up test fixtures."""
        mock_rns.Transport.interfaces = []

        # Ensure reticulum_wrapper's global RNS is set to our mock
        reticulum_wrapper.RNS = mock_rns
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        self.wrapper = reticulum_wrapper.ReticulumWrapper('/tmp/test')
        self.wrapper.initialized = True
        self.wrapper._pending_rnode_configs = [{
            'name': 'rnode0',
            'target_device_name': 'RNode ABC123'
        }]
        self.wrapper.kotlin_rnode_bridge = Mock()

    def tearDown(self):
        """Clean up after each test."""
        mock_rns.Transport.interfaces = []

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_missing_online_status_callback(self, mock_rnode_interface_class):
        """
        Test RNode initialization when setOnOnlineStatusChanged is not available.

        Should handle gracefully if interface doesn't support status callback.
        """
        # Mock interface without setOnOnlineStatusChanged
        mock_rnode_interface = Mock()
        mock_rnode_interface.online = True
        mock_rnode_interface.start = Mock(return_value=True)
        mock_rnode_interface.setOnErrorReceived = Mock()
        # Don't set setOnOnlineStatusChanged
        del mock_rnode_interface.setOnOnlineStatusChanged

        mock_rnode_interface_class.return_value = mock_rnode_interface

        # Should not raise AttributeError
        result = self.wrapper.initialize_rnode_interface()

        self.assertTrue(result['success'])

    @patch('rnode_interface.ColumbaRNodeInterface')
    def test_initialize_rnode_interface_exception_during_creation(self, mock_rnode_interface_class):
        """
        Test exception handling during interface creation.

        When ColumbaRNodeInterface constructor raises exception, should return error.
        """
        mock_rnode_interface_class.side_effect = Exception("USB device not found")

        result = self.wrapper.initialize_rnode_interface()

        self.assertFalse(result['success'])
        self.assertIn('USB device not found', result['error'])

    def test_initialize_rnode_interface_lock_cleanup_on_exception(self):
        """
        Test that initialization lock is properly reset on exception.

        When initialization fails, the _rnode_initializing flag should be reset
        so subsequent attempts can proceed.
        """
        # Force an exception by not setting initialized
        self.wrapper.initialized = False

        result = self.wrapper.initialize_rnode_interface()

        # Verify lock was cleaned up (flag reset)
        # The actual implementation uses a context manager, so the flag
        # should still be False after the method completes
        self.assertFalse(self.wrapper._rnode_initializing)


class TestGetPairedRNodes(unittest.TestCase):
    """Test get_paired_rnodes method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_paired_rnodes_returns_device_list(self):
        """Test successful device retrieval from bridge."""
        # Create a mock bridge
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.return_value = ["RNode A1B2", "RNode C3D4"]

        # Set the bridge
        self.wrapper.set_rnode_bridge(mock_bridge)

        # Call the method
        result = self.wrapper.get_paired_rnodes()

        # Verify the result
        self.assertTrue(result['success'])
        self.assertEqual(result['devices'], ["RNode A1B2", "RNode C3D4"])
        self.assertNotIn('error', result)

    def test_get_paired_rnodes_returns_error_when_no_bridge(self):
        """Test returns error dict when rnode_bridge not set."""
        # Don't set any bridge (it's None by default)
        self.assertIsNone(self.wrapper.kotlin_rnode_bridge)

        # Call the method
        result = self.wrapper.get_paired_rnodes()

        # Verify the result
        self.assertFalse(result['success'])
        self.assertEqual(result['devices'], [])
        self.assertEqual(result['error'], 'KotlinRNodeBridge not set')

    def test_get_paired_rnodes_handles_bridge_exception(self):
        """Test exception handling when bridge.getPairedRNodes() throws."""
        # Create a mock bridge that raises an exception
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.side_effect = Exception("Connection failed")

        # Set the bridge
        self.wrapper.set_rnode_bridge(mock_bridge)

        # Call the method
        result = self.wrapper.get_paired_rnodes()

        # Verify the result
        self.assertFalse(result['success'])
        self.assertEqual(result['devices'], [])
        self.assertEqual(result['error'], 'Connection failed')

    def test_get_paired_rnodes_handles_empty_device_list(self):
        """Test empty list from bridge returns empty devices."""
        # Create a mock bridge that returns empty list
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.return_value = []

        # Set the bridge
        self.wrapper.set_rnode_bridge(mock_bridge)

        # Call the method
        result = self.wrapper.get_paired_rnodes()

        # Verify the result
        self.assertTrue(result['success'])
        self.assertEqual(result['devices'], [])
        self.assertNotIn('error', result)

    def test_get_paired_rnodes_handles_none_from_bridge(self):
        """Test None response from bridge returns empty devices."""
        # Create a mock bridge that returns None
        mock_bridge = MagicMock()
        mock_bridge.getPairedRNodes.return_value = None

        # Set the bridge
        self.wrapper.set_rnode_bridge(mock_bridge)

        # Call the method
        result = self.wrapper.get_paired_rnodes()

        # Verify the result
        self.assertTrue(result['success'])
        self.assertEqual(result['devices'], [])
        self.assertNotIn('error', result)


if __name__ == '__main__':
    unittest.main()
