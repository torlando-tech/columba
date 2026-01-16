"""
Test suite for propagated message status protection (Issue #257 fix).

When messages are sent via propagation (relay node), they show 'propagated' status
after the relay confirms receipt. However, LXMF may later call the failure callback
spuriously because it expects delivery confirmation that will never come from the relay.

This test suite verifies that:
1. Successfully propagated messages are tracked in _successfully_propagated
2. Spurious failure callbacks are ignored for already-propagated messages
3. Stale tracking entries are cleaned up to prevent memory leaks
4. The 'propagated' status is emitted correctly for PROPAGATED delivery method
"""

import json
import os
import sys
import time
import unittest
from unittest.mock import MagicMock, Mock, patch

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


class TestSuccessfullyPropagatedInit(unittest.TestCase):
    """Tests for successfully propagated tracking initialization."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_successfully_propagated_dict_initialized(self):
        """Test that _successfully_propagated dict is initialized empty"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_successfully_propagated'))
        self.assertIsInstance(wrapper._successfully_propagated, dict)
        self.assertEqual(len(wrapper._successfully_propagated), 0)

    def test_propagated_tracking_ttl_initialized(self):
        """Test that TTL for propagated tracking is initialized (24 hours)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_propagated_tracking_ttl_seconds'))
        self.assertEqual(wrapper._propagated_tracking_ttl_seconds, 86400)  # 24 hours

    def test_pending_file_notifications_initialized(self):
        """Test that _pending_file_notifications dict is initialized empty"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_pending_file_notifications'))
        self.assertIsInstance(wrapper._pending_file_notifications, dict)
        self.assertEqual(len(wrapper._pending_file_notifications), 0)


class TestPropagatedStatusEmission(unittest.TestCase):
    """Tests for correct status emission for propagated messages."""

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
    def test_propagated_method_emits_propagated_status(self):
        """When desired_method is PROPAGATED, should emit 'propagated' status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'propagated_msg_1'
        mock_message.desired_method = mock_lxmf.LXMessage.PROPAGATED

        wrapper._on_message_sent(mock_message)

        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_event = json.loads(call_args)
        self.assertEqual(status_event['status'], 'propagated')
        self.assertEqual(status_event['message_hash'], b'propagated_msg_1'.hex())

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_direct_method_emits_sent_status(self):
        """When desired_method is DIRECT, should emit 'sent' status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'direct_msg_123'
        mock_message.desired_method = mock_lxmf.LXMessage.DIRECT

        wrapper._on_message_sent(mock_message)

        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_event = json.loads(call_args)
        self.assertEqual(status_event['status'], 'sent')

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_propagated_message_tracked_in_dict(self):
        """Successfully propagated message should be tracked in _successfully_propagated"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'tracked_prop_msg'
        mock_message.desired_method = mock_lxmf.LXMessage.PROPAGATED

        wrapper._on_message_sent(mock_message)

        msg_hash = b'tracked_prop_msg'.hex()
        self.assertIn(msg_hash, wrapper._successfully_propagated)
        # Timestamp should be recent (within last second)
        self.assertAlmostEqual(wrapper._successfully_propagated[msg_hash], time.time(), delta=1.0)


class TestSpuriousFailureProtection(unittest.TestCase):
    """Tests for protection against spurious failure callbacks."""

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
    def test_spurious_failure_ignored_for_propagated_message(self):
        """Failure callback should be ignored if message already propagated"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        # Simulate a message that was already propagated successfully
        msg_hash_hex = b'already_prop_msg'.hex()
        wrapper._successfully_propagated[msg_hash_hex] = time.time()

        mock_message = MagicMock()
        mock_message.hash = b'already_prop_msg'

        # This simulates LXMF's spurious failure callback
        wrapper._on_message_failed(mock_message)

        # Should NOT have called Kotlin callback with 'failed' status
        if wrapper.kotlin_delivery_status_callback.called:
            call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
            status_event = json.loads(call_args)
            self.assertNotEqual(status_event['status'], 'failed')

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_legitimate_failure_still_processed(self):
        """Legitimate failures (not in _successfully_propagated) should still be processed"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'legitimate_fail'
        # Not tracked as successfully propagated
        mock_message.try_propagation_on_fail = False
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

        wrapper._on_message_failed(mock_message)

        # Should have called Kotlin callback with 'failed' status
        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status_event = json.loads(call_args)
        self.assertEqual(status_event['status'], 'failed')

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_propagation_retry_still_works_for_new_messages(self):
        """Propagation retry should still work for messages not yet propagated"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'relay_hash_abc'
        wrapper.router = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'new_retry_msg'
        mock_message.try_propagation_on_fail = True
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

        wrapper._on_message_failed(mock_message)

        # Should have changed desired_method to PROPAGATED
        self.assertEqual(mock_message.desired_method, mock_lxmf.LXMessage.PROPAGATED)
        # Should have called router.handle_outbound for retry
        wrapper.router.handle_outbound.assert_called_once_with(mock_message)


class TestStaleTrackingCleanup(unittest.TestCase):
    """Tests for cleanup of stale propagated tracking entries."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_cleanup_removes_stale_entries(self):
        """Entries older than TTL should be removed"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Add entries: one fresh, one stale
        now = time.time()
        wrapper._successfully_propagated['fresh_msg'] = now
        wrapper._successfully_propagated['stale_msg'] = now - 90000  # > 24 hours old

        wrapper._cleanup_stale_propagated_tracking()

        self.assertIn('fresh_msg', wrapper._successfully_propagated)
        self.assertNotIn('stale_msg', wrapper._successfully_propagated)

    def test_cleanup_does_nothing_when_empty(self):
        """Cleanup should handle empty dict gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Should not raise any exception
        wrapper._cleanup_stale_propagated_tracking()

        self.assertEqual(len(wrapper._successfully_propagated), 0)

    def test_cleanup_preserves_recent_entries(self):
        """Recent entries should not be removed"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        now = time.time()
        for i in range(5):
            wrapper._successfully_propagated[f'recent_msg_{i}'] = now - (i * 3600)  # 0-4 hours old

        wrapper._cleanup_stale_propagated_tracking()

        # All should still be present (all < 24 hours old)
        self.assertEqual(len(wrapper._successfully_propagated), 5)


class TestImmediateStateCheck(unittest.TestCase):
    """Tests for immediate state check after propagation retry."""

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
    def test_immediate_success_detected_and_tracked(self):
        """If propagation succeeds immediately, should detect and track it"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'good_relay'
        wrapper.router = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'immediate_success'
        mock_message.try_propagation_on_fail = True
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []
        # Simulate immediate success - state becomes SENT right away
        mock_message.state = mock_lxmf.LXMessage.SENT
        mock_message.desired_method = mock_lxmf.LXMessage.PROPAGATED

        wrapper._on_message_failed(mock_message)

        # The _on_message_sent callback should have been called with 'propagated' status
        # and message should be tracked
        msg_hash = b'immediate_success'.hex()
        self.assertIn(msg_hash, wrapper._successfully_propagated)


if __name__ == '__main__':
    unittest.main()
