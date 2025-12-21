"""
Test suite for propagation node fallback when relay is offline.

When the selected relay (propagation node) is offline and a message falls back
to propagation, it also fails. This test suite verifies that the system:
1. Requests alternative relays from Kotlin when propagation fails
2. Retries messages with alternative relays
3. Tracks tried relays to prevent infinite loops
4. Fails permanently only when no alternatives are available
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


class TestPropagationFallbackInit(unittest.TestCase):
    """Tests for propagation fallback initialization."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_alternative_relay_callback_initialized(self):
        """Test that alternative relay callback is initialized to None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, 'kotlin_request_alternative_relay_callback'))
        self.assertIsNone(wrapper.kotlin_request_alternative_relay_callback)

    def test_pending_fallback_messages_initialized(self):
        """Test that pending fallback messages dict is initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_pending_relay_fallback_messages'))
        self.assertIsInstance(wrapper._pending_relay_fallback_messages, dict)
        self.assertEqual(len(wrapper._pending_relay_fallback_messages), 0)

    def test_max_relay_retries_initialized(self):
        """Test that max relay retries is initialized to 3"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        self.assertTrue(hasattr(wrapper, '_max_relay_retries'))
        self.assertEqual(wrapper._max_relay_retries, 3)

    def test_set_alternative_relay_callback(self):
        """Test registering the alternative relay callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        callback = MagicMock()

        wrapper.set_kotlin_request_alternative_relay_callback(callback)

        self.assertEqual(wrapper.kotlin_request_alternative_relay_callback, callback)


class TestPropagationRetryFailure(unittest.TestCase):
    """Tests for handling propagation retry failures."""

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
    def test_propagation_retry_failure_requests_alternative_relay(self):
        """When propagation retry fails, should request alternative relay from Kotlin"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'offline_relay_hash'
        wrapper.router = MagicMock()
        wrapper.kotlin_request_alternative_relay_callback = MagicMock()

        # Message that already tried propagation
        mock_message = MagicMock()
        mock_message.hash = b'failed_prop_msg1'
        mock_message.try_propagation_on_fail = False  # Already cleared from first retry
        mock_message.propagation_retry_attempted = True  # Already tried propagation
        mock_message.tried_relays = [b'offline_relay_hash']  # One relay already tried

        wrapper._on_message_failed(mock_message)

        # Should have called Kotlin to request alternative relay
        wrapper.kotlin_request_alternative_relay_callback.assert_called_once()

        # Verify the request contains expected data
        call_args = wrapper.kotlin_request_alternative_relay_callback.call_args[0][0]
        request = json.loads(call_args)
        self.assertIn('message_hash', request)
        self.assertIn('exclude_relays', request)
        self.assertIn(b'offline_relay_hash'.hex(), request['exclude_relays'])

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_tracks_propagation_retry_attempts(self):
        """Should set propagation_retry_attempted flag to prevent infinite loops"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.active_propagation_node = b'relay_hash_123'
        wrapper.router = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'track_retry_msg'
        mock_message.try_propagation_on_fail = True
        # Simulate fresh message - no prior propagation attempt
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

        # First failure triggers propagation retry
        wrapper._on_message_failed(mock_message)

        # Message should now have propagation_retry_attempted flag
        self.assertTrue(mock_message.propagation_retry_attempted)
        # Should have recorded the tried relay
        self.assertIn(b'relay_hash_123', mock_message.tried_relays)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_multiple_alternative_relays_tried_in_sequence(self):
        """If first alternative fails, should try next one (excluding already tried)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()
        wrapper.kotlin_request_alternative_relay_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'multi_relay_msg'
        mock_message.propagation_retry_attempted = True
        mock_message.tried_relays = [b'relay1', b'relay2']  # Already tried two relays

        wrapper._on_message_failed(mock_message)

        # Should request another alternative, excluding already tried
        wrapper.kotlin_request_alternative_relay_callback.assert_called_once()
        call_args = wrapper.kotlin_request_alternative_relay_callback.call_args[0][0]
        request = json.loads(call_args)
        self.assertIn('exclude_relays', request)
        self.assertIn(b'relay1'.hex(), request['exclude_relays'])
        self.assertIn(b'relay2'.hex(), request['exclude_relays'])

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_max_relay_retry_limit(self):
        """Should not try more than MAX_RELAY_RETRIES alternative relays"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()
        wrapper.kotlin_request_alternative_relay_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'max_retry_msg'
        # Already tried 3 relays (max)
        mock_message.tried_relays = [b'r1', b'r2', b'r3']
        mock_message.propagation_retry_attempted = True

        wrapper._on_message_failed(mock_message)

        # Should NOT request more alternatives
        wrapper.kotlin_request_alternative_relay_callback.assert_not_called()

        # Should fail permanently
        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status = json.loads(call_args)
        self.assertEqual(status['status'], 'failed')
        self.assertEqual(status['reason'], 'max_relay_retries_exceeded')

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_message_stored_pending_alternative(self):
        """Message should be stored while waiting for alternative relay"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_request_alternative_relay_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'pending_msg_123'
        mock_message.propagation_retry_attempted = True
        mock_message.tried_relays = [b'relay1']

        wrapper._on_message_failed(mock_message)

        # Message should be stored in pending dict
        msg_hash_hex = b'pending_msg_123'.hex()
        self.assertIn(msg_hash_hex, wrapper._pending_relay_fallback_messages)
        self.assertEqual(wrapper._pending_relay_fallback_messages[msg_hash_hex], mock_message)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_status_callback_notifies_retrying_alternative(self):
        """Should notify Kotlin of 'retrying_alternative_relay' status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_request_alternative_relay_callback = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'status_msg_123'
        mock_message.propagation_retry_attempted = True
        mock_message.tried_relays = [b'relay1']

        wrapper._on_message_failed(mock_message)

        # Should have notified status
        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status = json.loads(call_args)
        self.assertEqual(status['status'], 'retrying_alternative_relay')
        self.assertEqual(status['tried_count'], 1)


class TestAlternativeRelayReceived(unittest.TestCase):
    """Tests for handling alternative relay responses from Kotlin."""

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
    def test_alternative_relay_triggers_message_retry(self):
        """When Kotlin provides alternative relay, message should be retried"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        # Set up a pending failed message
        mock_message = MagicMock()
        mock_message.hash = b'pending_retry_msg'
        mock_message.tried_relays = [b'old_relay']
        wrapper._pending_relay_fallback_messages = {
            b'pending_retry_msg'.hex(): mock_message
        }

        new_relay_hash = b'alternative_relay1'
        wrapper.on_alternative_relay_received(new_relay_hash)

        # Should update propagation node and retry message
        self.assertEqual(wrapper.active_propagation_node, new_relay_hash)
        wrapper.router.handle_outbound.assert_called_once_with(mock_message)

        # Should have added new relay to tried list
        self.assertIn(new_relay_hash, mock_message.tried_relays)

        # Should clear pending messages
        self.assertEqual(len(wrapper._pending_relay_fallback_messages), 0)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_no_alternative_relays_fails_permanently(self):
        """When no alternative relays available, message should fail permanently"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'final_fail_msg'
        wrapper._pending_relay_fallback_messages = {
            b'final_fail_msg'.hex(): mock_message
        }

        # Signal no alternatives available
        wrapper.on_alternative_relay_received(None)

        # Message should be marked failed with reason
        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status = json.loads(call_args)
        self.assertEqual(status['status'], 'failed')
        self.assertEqual(status['reason'], 'no_relays_available')

        # Pending should be cleared
        self.assertEqual(len(wrapper._pending_relay_fallback_messages), 0)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_alternative_relay_configures_message_for_propagation(self):
        """Alternative relay retry should properly configure message for propagation"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'config_test_msg'
        mock_message.tried_relays = []
        mock_message.delivery_attempts = 5
        mock_message.packed = b'old_packed'
        mock_message.propagation_packed = b'old_prop_packed'
        mock_message.propagation_stamp = b'old_stamp'
        mock_message.defer_propagation_stamp = False

        wrapper._pending_relay_fallback_messages = {
            b'config_test_msg'.hex(): mock_message
        }

        wrapper.on_alternative_relay_received(b'new_relay_hash')

        # Should reset for fresh propagation attempt
        self.assertEqual(mock_message.delivery_attempts, 0)
        self.assertIsNone(mock_message.packed)
        self.assertIsNone(mock_message.propagation_packed)
        self.assertIsNone(mock_message.propagation_stamp)
        self.assertTrue(mock_message.defer_propagation_stamp)
        self.assertEqual(mock_message.desired_method, mock_lxmf.LXMessage.PROPAGATED)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_multiple_pending_messages_all_retried(self):
        """All pending messages should be retried when alternative relay arrives"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()
        wrapper.kotlin_delivery_status_callback = MagicMock()

        # Multiple pending messages
        msg1 = MagicMock()
        msg1.hash = b'msg1_hash'
        msg1.tried_relays = []

        msg2 = MagicMock()
        msg2.hash = b'msg2_hash'
        msg2.tried_relays = []

        wrapper._pending_relay_fallback_messages = {
            b'msg1_hash'.hex(): msg1,
            b'msg2_hash'.hex(): msg2,
        }

        wrapper.on_alternative_relay_received(b'new_relay')

        # Both messages should be retried
        self.assertEqual(wrapper.router.handle_outbound.call_count, 2)

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_no_pending_messages_is_noop(self):
        """Should handle case when no messages are pending gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()

        # No pending messages
        wrapper._pending_relay_fallback_messages = {}

        # Should not raise, just log warning
        wrapper.on_alternative_relay_received(b'some_relay')

        # Router should not be called
        wrapper.router.handle_outbound.assert_not_called()

    @patch.object(reticulum_wrapper, 'LXMF', mock_lxmf)
    def test_handles_jarray_relay_hash(self):
        """Should handle Java array relay hash from Chaquopy"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'jarray_test_msg'
        mock_message.tried_relays = []
        wrapper._pending_relay_fallback_messages = {
            b'jarray_test_msg'.hex(): mock_message
        }

        # Simulate jarray (list-like) from Java
        jarray_hash = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                       0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10]

        wrapper.on_alternative_relay_received(jarray_hash)

        # Should have converted and used
        expected_bytes = bytes(jarray_hash)
        self.assertEqual(wrapper.active_propagation_node, expected_bytes)


class TestFailMessagePermanently(unittest.TestCase):
    """Tests for _fail_message_permanently helper."""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_fail_message_notifies_kotlin(self):
        """Should notify Kotlin with failed status and reason"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'fail_perm_msg'

        wrapper._fail_message_permanently(mock_message, 'test_reason')

        wrapper.kotlin_delivery_status_callback.assert_called_once()
        call_args = wrapper.kotlin_delivery_status_callback.call_args[0][0]
        status = json.loads(call_args)
        self.assertEqual(status['status'], 'failed')
        self.assertEqual(status['reason'], 'test_reason')
        self.assertEqual(status['message_hash'], b'fail_perm_msg'.hex())

    def test_fail_message_removes_from_pending(self):
        """Should remove message from pending dict if present"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = MagicMock()

        mock_message = MagicMock()
        mock_message.hash = b'remove_pend_msg'

        # Add to pending
        wrapper._pending_relay_fallback_messages = {
            b'remove_pend_msg'.hex(): mock_message
        }

        wrapper._fail_message_permanently(mock_message, 'some_reason')

        # Should be removed
        self.assertNotIn(b'remove_pend_msg'.hex(), wrapper._pending_relay_fallback_messages)


if __name__ == '__main__':
    unittest.main()
