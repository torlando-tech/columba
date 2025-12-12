"""
Test suite for opportunistic message timeout functionality.

When opportunistic messages are sent to offline recipients, they get stuck in SENT state
forever waiting for a delivery receipt. This test suite verifies that the timeout mechanism
correctly triggers propagation fallback after the configured timeout period.
"""

import sys
import os
import time
import unittest
from unittest.mock import Mock, MagicMock, patch

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


class TestOpportunisticTimeoutTracking(unittest.TestCase):
    """Tests for opportunistic message tracking initialization and basic operations."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_tracking_dict_initialized(self):
        """Test that opportunistic message tracking dict is initialized in __init__"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_opportunistic_messages'))
        self.assertIsInstance(wrapper._opportunistic_messages, dict)
        self.assertEqual(len(wrapper._opportunistic_messages), 0)

    def test_timeout_config_initialized(self):
        """Test that timeout configuration is initialized with expected defaults"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Verify timeout is 30 seconds
        self.assertEqual(wrapper._opportunistic_timeout_seconds, 30)
        # Verify check interval is 10 seconds
        self.assertEqual(wrapper._opportunistic_check_interval, 10)
        # Verify timer reference is initialized to None
        self.assertIsNone(wrapper._opportunistic_timer)

    def test_timer_methods_exist(self):
        """Test that timer management methods exist"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_start_opportunistic_timer'))
        self.assertTrue(callable(wrapper._start_opportunistic_timer))
        self.assertTrue(hasattr(wrapper, '_opportunistic_timeout_loop'))
        self.assertTrue(callable(wrapper._opportunistic_timeout_loop))
        self.assertTrue(hasattr(wrapper, '_check_opportunistic_timeouts'))
        self.assertTrue(callable(wrapper._check_opportunistic_timeouts))


class TestOpportunisticTimeoutCheck(unittest.TestCase):
    """Tests for the _check_opportunistic_timeouts method."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_no_timeout_when_empty(self):
        """Test that no errors occur when checking empty tracking dict"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Should not raise any exception
        wrapper._check_opportunistic_timeouts()
        self.assertEqual(len(wrapper._opportunistic_messages), 0)

    def test_no_timeout_for_fresh_messages(self):
        """Test that fresh messages are not timed out"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message
        mock_message = MagicMock()
        mock_message.hash = b'test_hash_12345'

        # Add to tracking with current time
        wrapper._opportunistic_messages['test_hash_12345'.encode().hex()] = {
            'message': mock_message,
            'sent_time': time.time()
        }

        # Check timeouts - should not trigger
        wrapper._check_opportunistic_timeouts()

        # Message should still be tracked
        self.assertEqual(len(wrapper._opportunistic_messages), 1)

    def test_timeout_triggers_for_old_messages(self):
        """Test that old messages trigger timeout and failure callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message with try_propagation_on_fail
        mock_message = MagicMock()
        mock_message.hash = b'test_hash_12345'
        mock_message.try_propagation_on_fail = True

        # Add to tracking with time in the past (beyond timeout)
        msg_hash_hex = b'test_hash_12345'.hex()
        wrapper._opportunistic_messages[msg_hash_hex] = {
            'message': mock_message,
            'sent_time': time.time() - 35  # 35 seconds ago (past 30s timeout)
        }

        # Mock the failure callback
        wrapper._on_message_failed = MagicMock()

        # Check timeouts - should trigger
        wrapper._check_opportunistic_timeouts()

        # Message should be removed from tracking
        self.assertEqual(len(wrapper._opportunistic_messages), 0)

        # Failure callback should have been called
        wrapper._on_message_failed.assert_called_once_with(mock_message)

    def test_mixed_fresh_and_old_messages(self):
        """Test that only old messages are timed out, fresh ones are kept"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create mock messages
        old_message = MagicMock()
        old_message.hash = b'old_hash_12345'
        old_message.try_propagation_on_fail = True

        fresh_message = MagicMock()
        fresh_message.hash = b'fresh_hash_6789'

        # Add old message (past timeout)
        old_hash_hex = b'old_hash_12345'.hex()
        wrapper._opportunistic_messages[old_hash_hex] = {
            'message': old_message,
            'sent_time': time.time() - 35  # Past timeout
        }

        # Add fresh message (within timeout)
        fresh_hash_hex = b'fresh_hash_6789'.hex()
        wrapper._opportunistic_messages[fresh_hash_hex] = {
            'message': fresh_message,
            'sent_time': time.time()  # Just now
        }

        # Mock the failure callback
        wrapper._on_message_failed = MagicMock()

        # Check timeouts
        wrapper._check_opportunistic_timeouts()

        # Only old message should be removed
        self.assertEqual(len(wrapper._opportunistic_messages), 1)
        self.assertIn(fresh_hash_hex, wrapper._opportunistic_messages)
        self.assertNotIn(old_hash_hex, wrapper._opportunistic_messages)

        # Failure callback should only be called for old message
        wrapper._on_message_failed.assert_called_once_with(old_message)


class TestRemoveFromTracking(unittest.TestCase):
    """Tests for removing messages from tracking on delivery/failure."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_delivery_removes_from_tracking(self):
        """Test that successful delivery removes message from tracking"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message
        mock_message = MagicMock()
        msg_hash = b'delivered_hash_123'
        mock_message.hash = msg_hash
        msg_hash_hex = msg_hash.hex()

        # Add to tracking
        wrapper._opportunistic_messages[msg_hash_hex] = {
            'message': mock_message,
            'sent_time': time.time()
        }

        # Verify it's tracked
        self.assertEqual(len(wrapper._opportunistic_messages), 1)

        # Simulate delivery callback
        wrapper._on_message_delivered(mock_message)

        # Should be removed from tracking
        self.assertNotIn(msg_hash_hex, wrapper._opportunistic_messages)

    def test_failure_removes_from_tracking(self):
        """Test that failure removes message from tracking"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message without retry flag
        mock_message = MagicMock()
        msg_hash = b'failed_hash_456'
        mock_message.hash = msg_hash
        mock_message.try_propagation_on_fail = False
        msg_hash_hex = msg_hash.hex()

        # Add to tracking
        wrapper._opportunistic_messages[msg_hash_hex] = {
            'message': mock_message,
            'sent_time': time.time()
        }

        # Verify it's tracked
        self.assertEqual(len(wrapper._opportunistic_messages), 1)

        # Simulate failure callback
        wrapper._on_message_failed(mock_message)

        # Should be removed from tracking
        self.assertNotIn(msg_hash_hex, wrapper._opportunistic_messages)

    def test_untracked_message_delivery_no_error(self):
        """Test that delivery of untracked message doesn't raise error"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message that was never tracked
        mock_message = MagicMock()
        mock_message.hash = b'untracked_hash_789'

        # Verify tracking is empty
        self.assertEqual(len(wrapper._opportunistic_messages), 0)

        # Should not raise any exception
        wrapper._on_message_delivered(mock_message)


class TestTimerManagement(unittest.TestCase):
    """Tests for timer thread management."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_start_timer_creates_thread(self):
        """Test that _start_opportunistic_timer creates a daemon thread"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True  # Simulate initialized state

        # Timer should be None initially
        self.assertIsNone(wrapper._opportunistic_timer)

        # Start the timer
        wrapper._start_opportunistic_timer()

        # Timer should now be a thread
        self.assertIsNotNone(wrapper._opportunistic_timer)
        self.assertTrue(wrapper._opportunistic_timer.daemon)

        # Clean up
        wrapper.initialized = False
        time.sleep(0.1)  # Give thread time to exit

    def test_start_timer_idempotent(self):
        """Test that calling _start_opportunistic_timer multiple times doesn't create multiple threads"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Start the timer
        wrapper._start_opportunistic_timer()
        first_timer = wrapper._opportunistic_timer

        # Start again
        wrapper._start_opportunistic_timer()
        second_timer = wrapper._opportunistic_timer

        # Should be the same thread
        self.assertIs(first_timer, second_timer)

        # Clean up
        wrapper.initialized = False
        time.sleep(0.1)


class TestPropagationFallback(unittest.TestCase):
    """Integration tests for propagation fallback flow."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_timeout_triggers_propagation_retry(self):
        """Test that timeout triggers propagation fallback when configured"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'prop_node_hash'  # Simulate configured prop node
        wrapper.router = MagicMock()

        # Create a mock message with propagation retry flag
        mock_message = MagicMock()
        mock_message.hash = b'timeout_hash_123'
        mock_message.try_propagation_on_fail = True
        mock_message.delivery_attempts = 5

        # Add to tracking with time in the past
        msg_hash_hex = b'timeout_hash_123'.hex()
        wrapper._opportunistic_messages[msg_hash_hex] = {
            'message': mock_message,
            'sent_time': time.time() - 35  # Past timeout
        }

        # Check timeouts
        wrapper._check_opportunistic_timeouts()

        # Should have triggered propagation retry
        self.assertTrue(wrapper.router.handle_outbound.called)
        # Message should have been converted to PROPAGATED
        self.assertEqual(mock_message.desired_method, mock_lxmf.LXMessage.PROPAGATED)
        # Retry flag should be cleared to prevent infinite loop
        self.assertFalse(mock_message.try_propagation_on_fail)
        # Delivery attempts should be reset
        self.assertEqual(mock_message.delivery_attempts, 0)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_propagation_retry_sets_stamp_generation_flags(self):
        """Test that propagation retry properly configures stamp generation.

        Propagation nodes require valid stamps (proof-of-work). When retrying
        via propagation, we must:
        1. Clear propagation_packed (old propagation data)
        2. Clear propagation_stamp (old stamp)
        3. Set defer_propagation_stamp=True to trigger stamp generation
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'prop_node_hash'
        wrapper.router = MagicMock()

        # Create a mock message with old propagation data (simulating prior attempt)
        mock_message = MagicMock()
        mock_message.hash = b'stamp_test_hash'
        mock_message.try_propagation_on_fail = True
        mock_message.delivery_attempts = 3
        mock_message.packed = b'old_packed_data'
        mock_message.propagation_packed = b'old_propagation_packed'
        mock_message.propagation_stamp = b'old_invalid_stamp'
        mock_message.defer_propagation_stamp = False

        # Trigger failure callback (simulating opportunistic delivery failure)
        wrapper._on_message_failed(mock_message)

        # Verify stamp generation is properly configured
        self.assertIsNone(mock_message.packed, "packed should be cleared for re-packing")
        self.assertIsNone(mock_message.propagation_packed, "propagation_packed should be cleared")
        self.assertIsNone(mock_message.propagation_stamp, "propagation_stamp should be cleared")
        self.assertTrue(mock_message.defer_propagation_stamp,
                       "defer_propagation_stamp must be True to trigger stamp generation")

        # Verify message was resubmitted to router
        wrapper.router.handle_outbound.assert_called_once_with(mock_message)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_propagation_retry_without_prior_propagation_data(self):
        """Test that propagation retry works when message has no prior propagation data.

        This is the common case - opportunistic messages don't have propagation
        data until they're converted to PROPAGATED delivery.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'prop_node_hash'
        wrapper.router = MagicMock()

        # Create a fresh opportunistic message (no propagation attributes yet)
        mock_message = MagicMock(spec=['hash', 'try_propagation_on_fail', 'delivery_attempts'])
        mock_message.hash = b'fresh_msg_hash'
        mock_message.try_propagation_on_fail = True
        mock_message.delivery_attempts = 2

        # Trigger failure callback
        wrapper._on_message_failed(mock_message)

        # Verify stamp generation flags are set (even if attributes didn't exist before)
        self.assertIsNone(mock_message.packed)
        self.assertIsNone(mock_message.propagation_packed)
        self.assertIsNone(mock_message.propagation_stamp)
        self.assertTrue(mock_message.defer_propagation_stamp)
        self.assertEqual(mock_message.desired_method, mock_lxmf.LXMessage.PROPAGATED)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_no_propagation_retry_without_active_node(self):
        """Test that propagation retry doesn't happen without an active propagation node."""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = None  # No propagation node configured
        wrapper.router = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'no_prop_node_hash'
        mock_message.try_propagation_on_fail = True

        # Trigger failure callback
        wrapper._on_message_failed(mock_message)

        # Should NOT have tried to resubmit
        wrapper.router.handle_outbound.assert_not_called()
        # Should have reported failure
        wrapper.kotlin_delivery_status_callback.assert_called()


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
