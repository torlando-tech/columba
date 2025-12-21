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
mock_lxmf.LXMessage.DELIVERED = 0x08

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

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_delivery_removes_from_tracking(self):
        """Test that successful delivery removes message from tracking"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message
        mock_message = MagicMock()
        msg_hash = b'delivered_hash_123'
        mock_message.hash = msg_hash
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status
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

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_untracked_message_delivery_no_error(self):
        """Test that delivery of untracked message doesn't raise error"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create a mock message that was never tracked
        mock_message = MagicMock()
        mock_message.hash = b'untracked_hash_789'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status

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


class TestStartOpportunisticTimer(unittest.TestCase):
    """Tests for the _start_opportunistic_timer method."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_timer_thread_starts_when_called(self):
        """Test that timer thread is created and started"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Initially no timer
        self.assertIsNone(wrapper._opportunistic_timer)

        # Start timer
        wrapper._start_opportunistic_timer()

        # Timer should be created and alive
        self.assertIsNotNone(wrapper._opportunistic_timer)
        self.assertTrue(wrapper._opportunistic_timer.is_alive())

        # Clean up
        wrapper.initialized = False
        time.sleep(0.1)

    def test_timer_is_daemon_thread(self):
        """Test that created thread is a daemon thread"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        wrapper._start_opportunistic_timer()

        # Verify it's a daemon thread
        self.assertTrue(wrapper._opportunistic_timer.daemon)

        # Clean up
        wrapper.initialized = False
        time.sleep(0.1)

    def test_doesnt_start_if_already_running(self):
        """Test that timer doesn't restart if already running"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Start first time
        wrapper._start_opportunistic_timer()
        first_timer = wrapper._opportunistic_timer
        first_thread_id = first_timer.ident

        # Give it a moment to start
        time.sleep(0.05)

        # Try to start again while still running
        wrapper._start_opportunistic_timer()
        second_timer = wrapper._opportunistic_timer

        # Should be the same thread
        self.assertIs(first_timer, second_timer)
        self.assertEqual(first_thread_id, second_timer.ident)

        # Clean up
        wrapper.initialized = False
        time.sleep(0.1)

    def test_restarts_if_previous_thread_died(self):
        """Test that timer restarts if previous thread has died"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper._opportunistic_check_interval = 0.05  # Speed up for testing

        # Start first time
        wrapper._start_opportunistic_timer()
        first_timer = wrapper._opportunistic_timer

        # Stop the thread by setting initialized to False
        wrapper.initialized = False
        # Wait for thread to exit (needs to be longer than check_interval)
        first_timer.join(timeout=0.5)

        # Verify first thread is no longer alive
        self.assertFalse(first_timer.is_alive())

        # Re-initialize and start again
        wrapper.initialized = True
        wrapper._start_opportunistic_timer()
        second_timer = wrapper._opportunistic_timer

        # Should be a different thread (not the same object)
        self.assertIsNot(first_timer, second_timer)
        self.assertTrue(second_timer.is_alive())

        # Clean up
        wrapper.initialized = False
        second_timer.join(timeout=0.5)


class TestOpportunisticTimeoutLoop(unittest.TestCase):
    """Tests for the _opportunistic_timeout_loop method."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_loop_exits_when_not_initialized(self):
        """Test that loop exits cleanly when initialized is False"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        # Run loop in thread (it should exit immediately)
        import threading
        loop_thread = threading.Thread(target=wrapper._opportunistic_timeout_loop)
        loop_thread.start()

        # Wait for thread to complete
        loop_thread.join(timeout=1.0)

        # Thread should have exited
        self.assertFalse(loop_thread.is_alive())

    def test_loop_calls_check_periodically(self):
        """Test that loop calls _check_opportunistic_timeouts periodically"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper._opportunistic_check_interval = 0.05  # Speed up for testing

        # Mock the check method
        wrapper._check_opportunistic_timeouts = MagicMock()

        # Add a message to trigger checking
        mock_message = MagicMock()
        wrapper._opportunistic_messages['test_hash'] = {
            'message': mock_message,
            'sent_time': time.time()
        }

        # Start loop in thread
        import threading
        loop_thread = threading.Thread(target=wrapper._opportunistic_timeout_loop)
        loop_thread.start()

        # Let it run for a bit
        time.sleep(0.15)  # Should get ~3 calls

        # Stop the loop
        wrapper.initialized = False
        loop_thread.join(timeout=1.0)

        # Verify check was called multiple times
        self.assertGreaterEqual(wrapper._check_opportunistic_timeouts.call_count, 2)

    def test_loop_skips_check_when_no_messages(self):
        """Test that loop doesn't call check when tracking dict is empty"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper._opportunistic_check_interval = 0.05

        # Mock the check method
        wrapper._check_opportunistic_timeouts = MagicMock()

        # Ensure tracking dict is empty
        wrapper._opportunistic_messages = {}

        # Start loop
        import threading
        loop_thread = threading.Thread(target=wrapper._opportunistic_timeout_loop)
        loop_thread.start()

        # Let it run for a bit
        time.sleep(0.15)

        # Stop the loop
        wrapper.initialized = False
        loop_thread.join(timeout=1.0)

        # Check should not have been called (dict was empty)
        wrapper._check_opportunistic_timeouts.assert_not_called()

    def test_loop_respects_check_interval(self):
        """Test that loop uses the configured check interval"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper._opportunistic_check_interval = 0.1  # 100ms interval

        # Mock the check method
        wrapper._check_opportunistic_timeouts = MagicMock()

        # Add a message
        wrapper._opportunistic_messages['test'] = {
            'message': MagicMock(),
            'sent_time': time.time()
        }

        # Start loop
        import threading
        loop_thread = threading.Thread(target=wrapper._opportunistic_timeout_loop)
        start_time = time.time()
        loop_thread.start()

        # Run for just over 2 intervals
        time.sleep(0.25)

        # Stop the loop
        wrapper.initialized = False
        loop_thread.join(timeout=1.0)
        elapsed = time.time() - start_time

        # Should have been called 2-3 times (accounting for timing variance)
        call_count = wrapper._check_opportunistic_timeouts.call_count
        self.assertGreaterEqual(call_count, 2)
        self.assertLessEqual(call_count, 4)


class TestCheckOpportunisticTimeoutsThreading(unittest.TestCase):
    """Tests for threading aspects of _check_opportunistic_timeouts method."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_identifies_timed_out_messages(self):
        """Test that messages exceeding 30 seconds are identified"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create messages - some timed out, some not
        old_message = MagicMock()
        old_message.hash = b'old_msg'
        old_message.try_propagation_on_fail = True

        recent_message = MagicMock()
        recent_message.hash = b'recent_msg'

        # Add to tracking
        wrapper._opportunistic_messages[b'old_msg'.hex()] = {
            'message': old_message,
            'sent_time': time.time() - 35  # 35 seconds ago
        }
        wrapper._opportunistic_messages[b'recent_msg'.hex()] = {
            'message': recent_message,
            'sent_time': time.time() - 5  # 5 seconds ago
        }

        # Mock failure callback
        wrapper._on_message_failed = MagicMock()

        # Check timeouts
        wrapper._check_opportunistic_timeouts()

        # Only old message should have triggered callback
        wrapper._on_message_failed.assert_called_once_with(old_message)

    def test_calls_delivery_callback_with_failure_status(self):
        """Test that timed-out messages trigger failure callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()
        wrapper.active_propagation_node = None  # No retry

        mock_message = MagicMock()
        mock_message.hash = b'timeout_msg'
        mock_message.try_propagation_on_fail = False

        # Add old message
        wrapper._opportunistic_messages[b'timeout_msg'.hex()] = {
            'message': mock_message,
            'sent_time': time.time() - 40
        }

        # Check timeouts
        wrapper._check_opportunistic_timeouts()

        # Kotlin callback should have been called with failed status
        wrapper.kotlin_delivery_status_callback.assert_called_once()
        import json
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_data = json.loads(call_args)
        self.assertEqual(status_data['status'], 'failed')

    def test_removes_timed_out_messages_from_tracking(self):
        """Test that timed-out messages are removed from tracking dict"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper._on_message_failed = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'remove_msg'
        mock_message.try_propagation_on_fail = True

        msg_hash_hex = b'remove_msg'.hex()
        wrapper._opportunistic_messages[msg_hash_hex] = {
            'message': mock_message,
            'sent_time': time.time() - 35
        }

        # Verify it's tracked
        self.assertIn(msg_hash_hex, wrapper._opportunistic_messages)

        # Check timeouts
        wrapper._check_opportunistic_timeouts()

        # Should be removed
        self.assertNotIn(msg_hash_hex, wrapper._opportunistic_messages)

    def test_handles_empty_tracking_dict(self):
        """Test that empty tracking dict doesn't cause errors"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper._opportunistic_messages = {}

        # Should not raise any exception
        try:
            wrapper._check_opportunistic_timeouts()
            success = True
        except Exception:
            success = False

        self.assertTrue(success)

    def test_thread_safe_iteration(self):
        """Test that iteration creates a copy to avoid modification errors"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Create multiple messages
        for i in range(5):
            msg = MagicMock()
            msg.hash = f'msg_{i}'.encode()
            msg.try_propagation_on_fail = True

            wrapper._opportunistic_messages[f'msg_{i}'.encode().hex()] = {
                'message': msg,
                'sent_time': time.time() - 35  # All timed out
            }

        # Mock failure callback - it should be safe even though dict is modified
        wrapper._on_message_failed = MagicMock()

        # This should not raise "dictionary changed size during iteration" error
        try:
            wrapper._check_opportunistic_timeouts()
            success = True
        except RuntimeError as e:
            if "dictionary changed size" in str(e):
                success = False
            else:
                raise

        self.assertTrue(success)
        # All messages should have been processed
        self.assertEqual(wrapper._on_message_failed.call_count, 5)
        # All should be removed from tracking
        self.assertEqual(len(wrapper._opportunistic_messages), 0)

    def test_multiple_timeouts_processed_in_single_check(self):
        """Test that multiple timed-out messages are all processed in one check"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper._on_message_failed = MagicMock()

        # Create 3 timed-out messages
        for i in range(3):
            msg = MagicMock()
            msg.hash = f'multi_{i}'.encode()
            msg.try_propagation_on_fail = True

            wrapper._opportunistic_messages[f'multi_{i}'.encode().hex()] = {
                'message': msg,
                'sent_time': time.time() - 35
            }

        # One check should process all
        wrapper._check_opportunistic_timeouts()

        # All 3 should have triggered callback
        self.assertEqual(wrapper._on_message_failed.call_count, 3)
        # All should be removed
        self.assertEqual(len(wrapper._opportunistic_messages), 0)


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
        # New attributes for relay fallback tracking (first attempt)
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

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
        # Should track propagation attempt
        self.assertTrue(mock_message.propagation_retry_attempted)

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
        # New attributes for relay fallback tracking (first attempt)
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

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


class TestDeliveryCallbacks(unittest.TestCase):
    """Tests for delivery status callbacks to Kotlin."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_on_message_delivered_calls_kotlin_callback(self):
        """Test that _on_message_delivered invokes Kotlin callback with correct JSON"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        # Create mock message with DELIVERED state (direct delivery)
        mock_message = MagicMock()
        mock_message.hash = b'delivered_test_hash'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Direct delivery confirmed

        # Trigger callback
        wrapper._on_message_delivered(mock_message)

        # Verify callback was called
        wrapper.kotlin_delivery_status_callback.assert_called_once()

        # Verify JSON structure
        import json
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_data = json.loads(call_args)
        self.assertEqual(status_data['status'], 'delivered')
        self.assertEqual(status_data['message_hash'], b'delivered_test_hash'.hex())
        self.assertIn('timestamp', status_data)

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_on_message_delivered_handles_missing_callback(self):
        """Test that _on_message_delivered handles missing callback gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = None

        mock_message = MagicMock()
        mock_message.hash = b'test_hash'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status

        # Should not raise exception
        wrapper._on_message_delivered(mock_message)

    def test_on_message_failed_calls_kotlin_callback(self):
        """Test that _on_message_failed invokes Kotlin callback with failed status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()
        wrapper.active_propagation_node = None  # No retry

        mock_message = MagicMock()
        mock_message.hash = b'failed_test_hash'
        mock_message.try_propagation_on_fail = False

        wrapper._on_message_failed(mock_message)

        wrapper.kotlin_delivery_status_callback.assert_called_once()
        import json
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_data = json.loads(call_args)
        self.assertEqual(status_data['status'], 'failed')

    def test_on_message_failed_handles_missing_callback(self):
        """Test that _on_message_failed handles missing callback gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = None
        wrapper.active_propagation_node = None

        mock_message = MagicMock()
        mock_message.hash = b'test_hash'
        mock_message.try_propagation_on_fail = False

        # Should not raise exception
        wrapper._on_message_failed(mock_message)

    def test_on_message_sent_calls_kotlin_callback(self):
        """Test that _on_message_sent invokes Kotlin callback with sent status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'sent_test_hash'

        wrapper._on_message_sent(mock_message)

        wrapper.kotlin_delivery_status_callback.assert_called_once()
        import json
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_data = json.loads(call_args)
        self.assertEqual(status_data['status'], 'sent')

    def test_on_message_sent_handles_missing_callback(self):
        """Test that _on_message_sent handles missing callback gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = None

        mock_message = MagicMock()
        mock_message.hash = b'test_hash'

        # Should not raise exception
        wrapper._on_message_sent(mock_message)


class TestPropagationNodeSetting(unittest.TestCase):
    """Tests for propagation node configuration methods."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_outbound_propagation_node_stores_hash(self):
        """Test that set_outbound_propagation_node stores the node hash"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        result = wrapper.set_outbound_propagation_node(dest_hash)

        self.assertTrue(result.get('success'))
        self.assertEqual(wrapper.active_propagation_node, dest_hash)
        wrapper.router.set_outbound_propagation_node.assert_called_once_with(dest_hash)

    def test_set_outbound_propagation_node_clears_with_none(self):
        """Test that set_outbound_propagation_node clears node with None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'existing_hash'
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        result = wrapper.set_outbound_propagation_node(None)

        self.assertTrue(result.get('success'))
        self.assertIsNone(wrapper.active_propagation_node)
        wrapper.router.set_outbound_propagation_node.assert_called_once_with(None)

    def test_get_outbound_propagation_node_returns_hex_string(self):
        """Test that get_outbound_propagation_node returns hex string format"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        dest_hash = b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10'
        wrapper.router.get_outbound_propagation_node.return_value = dest_hash

        result = wrapper.get_outbound_propagation_node()

        self.assertTrue(result.get('success'))
        self.assertEqual(result.get('propagation_node'), dest_hash.hex())


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
