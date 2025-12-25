"""
Test suite for ReticulumWrapper reaction methods.

Tests send_reaction, reaction receive handling via callback and polling,
Field 16 reaction parsing, and emoji reaction edge cases.
"""

import sys
import os
import unittest
import time
import json
from unittest.mock import Mock, MagicMock, patch, call

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock LXMF with message state constants before importing
mock_lxmf = MagicMock()
mock_lxmf.LXMessage.OPPORTUNISTIC = 0x01
mock_lxmf.LXMessage.DIRECT = 0x02
mock_lxmf.LXMessage.PROPAGATED = 0x03
mock_lxmf.LXMessage.SENT = 0x04
mock_lxmf.LXMessage.DELIVERED = 0x05
mock_lxmf.LXMessage.FAILED = 0x06

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf

# Now import after mocking
import reticulum_wrapper


class TestSendReaction(unittest.TestCase):
    """Test send_reaction method - sending emoji reactions via LXMF"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

        # Enable RETICULUM_AVAILABLE for these tests
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_not_initialized(self, mock_lxmf_mod, mock_rns):
        """Test that send_reaction fails when wrapper is not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="abc123def456",
            emoji="\U0001F44D",  # Thumbs up
            source_identity_private_key=b'privkey' * 10
        )

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertEqual(result['error'], 'LXMF not initialized')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_no_router(self, mock_lxmf_mod, mock_rns):
        """Test that send_reaction fails when router is None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = None

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="abc123def456",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_success(self, mock_lxmf_mod, mock_rns):
        """Test successful reaction send"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        # Mock local LXMF destination
        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Mock identity recall
        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        # Mock destination creation
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock LXMF message
        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        # Send reaction
        target_message_id = "abc123def456789012345678"
        emoji = "\U0001F44D"  # Thumbs up

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id=target_message_id,
            emoji=emoji,
            source_identity_private_key=b'privkey' * 10
        )

        # Verify success
        self.assertTrue(result['success'])
        self.assertIn('message_hash', result)
        self.assertIn('timestamp', result)
        self.assertEqual(result['target_message_id'], target_message_id)
        self.assertEqual(result['emoji'], emoji)

        # Verify router.handle_outbound was called
        wrapper.router.handle_outbound.assert_called_once_with(mock_message)

        # Verify LXMessage was created with OPPORTUNISTIC delivery
        call_kwargs = mock_lxmf_mod.LXMessage.call_args[1]
        self.assertEqual(call_kwargs['desired_method'], mock_lxmf_mod.LXMessage.OPPORTUNISTIC)

        # Verify empty content (reaction data is in fields)
        self.assertEqual(call_kwargs['content'], b"")

        # Verify Field 16 contains reaction data
        self.assertIn('fields', call_kwargs)
        fields = call_kwargs['fields']
        self.assertIn(16, fields)
        field_16 = fields[16]
        self.assertEqual(field_16['reaction_to'], target_message_id)
        self.assertEqual(field_16['emoji'], emoji)
        self.assertIn('sender', field_16)  # Sender hash should be present

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_various_emojis(self, mock_lxmf_mod, mock_rns):
        """Test sending reactions with various emoji types"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        # Test various emoji types from the reaction picker
        emojis = [
            "\U0001F44D",  # Thumbs up
            "\u2764\ufe0f",  # Red heart with variation selector
            "\U0001F602",  # Face with tears of joy
            "\U0001F62E",  # Face with open mouth
            "\U0001F622",  # Crying face
            "\U0001F621",  # Pouting face
        ]

        for emoji in emojis:
            mock_lxmf_mod.reset_mock()
            mock_lxmf_mod.LXMessage.return_value = mock_message
            mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

            result = wrapper.send_reaction(
                dest_hash=b'0123456789abcdef',
                target_message_id="test_message_id_123",
                emoji=emoji,
                source_identity_private_key=b'privkey' * 10
            )

            self.assertTrue(result['success'], f"Failed for emoji: {emoji}")
            self.assertEqual(result['emoji'], emoji)

            # Verify emoji is correctly stored in Field 16
            call_kwargs = mock_lxmf_mod.LXMessage.call_args[1]
            self.assertEqual(call_kwargs['fields'][16]['emoji'], emoji)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_identity_not_found(self, mock_lxmf_mod, mock_rns):
        """Test sending reaction when recipient identity cannot be recalled"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.return_value = mock_identity

        # Identity recall always returns None
        mock_rns.Identity.recall.return_value = None

        # Empty identities cache
        wrapper.identities = {}

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="test_message_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        # Should fail with identity not found error
        self.assertFalse(result['success'])
        self.assertIn('not known', result['error'].lower())

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_with_cached_identity(self, mock_lxmf_mod, mock_rns):
        """Test that cached identities are used when recall fails"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.return_value = mock_identity

        # Identity recall fails
        mock_rns.Identity.recall.return_value = None

        # But identity is in cache
        dest_hash = b'0123456789abcdef'
        mock_cached_identity = MagicMock()
        mock_cached_identity.hash = dest_hash
        wrapper.identities[dest_hash.hex()] = mock_cached_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        result = wrapper.send_reaction(
            dest_hash=dest_hash,
            target_message_id="test_message_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        # Should succeed using cached identity
        self.assertTrue(result['success'])

    def test_send_reaction_when_reticulum_unavailable(self):
        """Test behavior when Reticulum is not available"""
        reticulum_wrapper.RETICULUM_AVAILABLE = False
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="test_message_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey'
        )

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_registers_callbacks(self, mock_lxmf_mod, mock_rns):
        """Test that delivery and failed callbacks are registered on reaction message"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="test_message_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        self.assertTrue(result['success'])

        # Verify callbacks were registered
        mock_message.register_delivery_callback.assert_called_once()
        mock_message.register_failed_callback.assert_called_once()


class TestReactionReceiveCallback(unittest.TestCase):
    """Test reaction receive handling via _on_lxmf_delivery callback"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_on_lxmf_delivery_detects_reaction(self):
        """Test that _on_lxmf_delivery correctly identifies reaction messages"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = MagicMock()
        wrapper.kotlin_reaction_received_callback = mock_kotlin_callback

        # Create mock reaction message with Field 16
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""  # Empty content for reactions
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_abc123',
                'emoji': '\U0001F44D',
                'sender': 'sender_hash_hex_value'
            }
        }

        wrapper._on_lxmf_delivery(mock_message)

        # Kotlin reaction callback should be invoked
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        # Parse JSON
        event = json.loads(call_arg)
        self.assertEqual(event['reaction_to'], 'target_message_abc123')
        self.assertEqual(event['emoji'], '\U0001F44D')
        self.assertEqual(event['sender'], 'sender_hash_hex_value')
        self.assertEqual(event['source_hash'], mock_message.source_hash.hex())
        self.assertIn('timestamp', event)

    def test_on_lxmf_delivery_skips_regular_processing_for_reaction(self):
        """Test that reactions skip the regular message queue"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        wrapper.kotlin_reaction_received_callback = MagicMock()
        wrapper.kotlin_message_received_callback = MagicMock()

        # Create mock reaction message
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_abc123',
                'emoji': '\U0001F44D',
                'sender': 'sender_hash_hex_value'
            }
        }

        wrapper._on_lxmf_delivery(mock_message)

        # Reaction callback should be invoked
        wrapper.kotlin_reaction_received_callback.assert_called_once()

        # Regular message callback should NOT be invoked
        wrapper.kotlin_message_received_callback.assert_not_called()

        # Message should NOT be added to pending_inbound queue
        self.assertEqual(len(mock_router.pending_inbound), 0)

    def test_on_lxmf_delivery_handles_missing_reaction_callback(self):
        """Test reaction processing when no callback is registered"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        # No reaction callback registered
        wrapper.kotlin_reaction_received_callback = None

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_abc123',
                'emoji': '\U0001F44D',
                'sender': 'sender_hash_hex_value'
            }
        }

        # Should not crash
        wrapper._on_lxmf_delivery(mock_message)

        # Reaction should still skip regular processing
        self.assertEqual(len(mock_router.pending_inbound), 0)

    def test_on_lxmf_delivery_regular_message_not_treated_as_reaction(self):
        """Test that regular messages (without reaction_to) are processed normally"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        wrapper.kotlin_reaction_received_callback = MagicMock()
        wrapper.kotlin_message_received_callback = MagicMock()

        # Create regular message with Field 16 that has reply_to (not reaction_to)
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b"Regular message content"
        mock_message.hash = b'messagehash12345'
        mock_message.fields = {
            16: {
                'reply_to': 'some_message_id'  # Reply, not reaction
            }
        }

        wrapper._on_lxmf_delivery(mock_message)

        # Reaction callback should NOT be invoked
        wrapper.kotlin_reaction_received_callback.assert_not_called()

        # Regular message callback SHOULD be invoked
        wrapper.kotlin_message_received_callback.assert_called_once()

        # Message SHOULD be added to pending_inbound queue
        self.assertEqual(len(mock_router.pending_inbound), 1)

    def test_on_lxmf_delivery_handles_callback_error(self):
        """Test that errors in reaction callback don't crash processing"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        # Callback that raises exception
        mock_callback = MagicMock(side_effect=Exception("Callback error"))
        wrapper.kotlin_reaction_received_callback = mock_callback

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_abc123',
                'emoji': '\U0001F44D',
                'sender': 'sender_hash_hex_value'
            }
        }

        # Should not crash
        wrapper._on_lxmf_delivery(mock_message)

        # Callback was attempted
        mock_callback.assert_called_once()

    def test_on_lxmf_delivery_various_emoji_types(self):
        """Test reaction processing with various emoji types"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = MagicMock()
        wrapper.kotlin_reaction_received_callback = mock_kotlin_callback

        emojis = [
            '\U0001F44D',  # Thumbs up
            '\u2764\ufe0f',  # Red heart with variation selector
            '\U0001F602',  # Face with tears of joy
            '\U0001F3F3\ufe0f\u200d\U0001F308',  # Rainbow flag (ZWJ sequence)
            '\U0001F469\u200d\U0001F4BB',  # Woman technologist (ZWJ sequence)
        ]

        for emoji in emojis:
            mock_kotlin_callback.reset_mock()

            mock_message = MagicMock()
            mock_message.source_hash = b'source123source1'
            mock_message.destination_hash = b'dest456dest45678'
            mock_message.content = b""
            mock_message.hash = b'reactionhash1234'
            mock_message.fields = {
                16: {
                    'reaction_to': 'target_message_id',
                    'emoji': emoji,
                    'sender': 'sender_hash'
                }
            }

            wrapper._on_lxmf_delivery(mock_message)

            mock_kotlin_callback.assert_called_once()
            call_arg = mock_kotlin_callback.call_args[0][0]
            event = json.loads(call_arg)
            self.assertEqual(event['emoji'], emoji, f"Failed for emoji: {emoji}")


class TestReactionReceivePolling(unittest.TestCase):
    """Test reaction receive handling via poll_received_messages"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    def test_poll_identifies_reaction_messages(self):
        """Test that poll_received_messages marks reaction messages correctly"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = MagicMock()

        # Create mock reaction message
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.timestamp = 1234567890
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_abc123',
                'emoji': '\U0001F44D',
                'sender': 'sender_hash_hex_value'
            }
        }

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        msg = messages[0]

        # Should be marked as reaction
        self.assertTrue(msg.get('is_reaction', False))
        self.assertEqual(msg.get('reaction_to'), 'target_message_abc123')
        self.assertEqual(msg.get('reaction_emoji'), '\U0001F44D')
        self.assertEqual(msg.get('reaction_sender'), 'sender_hash_hex_value')

    def test_poll_regular_message_not_marked_as_reaction(self):
        """Test that regular messages are not marked as reactions"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = MagicMock()

        # Create regular message with Field 16 reply_to (not reaction_to)
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b"Regular message"
        mock_message.timestamp = 1234567890
        mock_message.hash = b'messagehash12345'
        mock_message.fields = {
            16: {
                'reply_to': 'some_message_id'
            }
        }

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        msg = messages[0]

        # Should NOT be marked as reaction
        self.assertFalse(msg.get('is_reaction', False))
        self.assertNotIn('reaction_to', msg)

    def test_poll_message_without_field_16(self):
        """Test polling message without Field 16"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = MagicMock()

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b"Simple message"
        mock_message.timestamp = 1234567890
        mock_message.hash = b'messagehash12345'
        mock_message.fields = None  # No fields

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        msg = messages[0]

        # Should NOT be marked as reaction
        self.assertFalse(msg.get('is_reaction', False))


class TestSetReactionReceivedCallback(unittest.TestCase):
    """Test set_reaction_received_callback method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_reaction_received_callback(self):
        """Test registering reaction received callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        callback = MagicMock()
        wrapper.set_reaction_received_callback(callback)

        self.assertEqual(wrapper.kotlin_reaction_received_callback, callback)

    def test_set_reaction_received_callback_replaces_existing(self):
        """Test that new callback replaces existing one"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        callback1 = MagicMock()
        callback2 = MagicMock()

        wrapper.set_reaction_received_callback(callback1)
        wrapper.set_reaction_received_callback(callback2)

        self.assertEqual(wrapper.kotlin_reaction_received_callback, callback2)
        self.assertNotEqual(wrapper.kotlin_reaction_received_callback, callback1)


class TestReactionField16Structure(unittest.TestCase):
    """Test Field 16 structure for reactions"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_field_16_contains_required_keys(self, mock_lxmf_mod, mock_rns):
        """Test that Field 16 contains all required reaction keys"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'sender_hash_bytes'
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="target_msg_12345678",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        self.assertTrue(result['success'])

        # Get Field 16 from LXMessage call
        call_kwargs = mock_lxmf_mod.LXMessage.call_args[1]
        field_16 = call_kwargs['fields'][16]

        # Verify required keys
        self.assertIn('reaction_to', field_16)
        self.assertIn('emoji', field_16)
        self.assertIn('sender', field_16)

        # Verify values
        self.assertEqual(field_16['reaction_to'], "target_msg_12345678")
        self.assertEqual(field_16['emoji'], "\U0001F44D")
        # Sender should be the hex of the sender's identity hash
        self.assertIsInstance(field_16['sender'], str)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_sender_hash_is_source_identity(self, mock_lxmf_mod, mock_rns):
        """Test that sender in Field 16 is the source identity hash"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Create identity with known hash
        expected_sender_hash = b'sender_hash_byte'
        mock_source_identity = MagicMock()
        mock_source_identity.hash = expected_sender_hash

        mock_recipient_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_recipient_identity
        mock_rns.Identity.return_value = mock_source_identity

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="target_msg_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        self.assertTrue(result['success'])

        call_kwargs = mock_lxmf_mod.LXMessage.call_args[1]
        field_16 = call_kwargs['fields'][16]

        # Sender should be the hex representation of source identity hash
        self.assertEqual(field_16['sender'], expected_sender_hash.hex())


class TestReactionEdgeCases(unittest.TestCase):
    """Test edge cases for reaction handling"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_with_jarray_dest_hash(self, mock_lxmf_mod, mock_rns):
        """Test send_reaction handles Java array (jarray) conversion for dest_hash"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'reactionhash1234'
        mock_lxmf_mod.LXMessage.return_value = mock_message
        mock_lxmf_mod.LXMessage.OPPORTUNISTIC = 0x01

        # Simulate jarray by passing a list (which has __iter__ but is not bytes)
        jarray_like = list(b'0123456789abcdef')

        result = wrapper.send_reaction(
            dest_hash=jarray_like,
            target_message_id="target_msg_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        # Should handle conversion and succeed
        self.assertTrue(result['success'])

    def test_reaction_receive_with_empty_emoji(self):
        """Test reaction receive handling when emoji is empty string"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = MagicMock()
        wrapper.kotlin_reaction_received_callback = mock_kotlin_callback

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_id',
                'emoji': '',  # Empty emoji
                'sender': 'sender_hash'
            }
        }

        # Should not crash
        wrapper._on_lxmf_delivery(mock_message)

        # Callback should still be invoked
        mock_kotlin_callback.assert_called_once()
        event = json.loads(mock_kotlin_callback.call_args[0][0])
        self.assertEqual(event['emoji'], '')

    def test_reaction_receive_with_missing_sender(self):
        """Test reaction receive when sender field is missing"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = MagicMock()
        wrapper.kotlin_reaction_received_callback = mock_kotlin_callback

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_id',
                'emoji': '\U0001F44D'
                # 'sender' is missing
            }
        }

        # Should not crash
        wrapper._on_lxmf_delivery(mock_message)

        # Callback should be invoked with empty sender
        mock_kotlin_callback.assert_called_once()
        event = json.loads(mock_kotlin_callback.call_args[0][0])
        self.assertEqual(event['sender'], '')

    def test_reaction_receive_partial_field_16(self):
        """Test reaction receive when Field 16 has only reaction_to"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = MagicMock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = MagicMock()
        wrapper.kotlin_reaction_received_callback = mock_kotlin_callback

        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b""
        mock_message.hash = b'reactionhash1234'
        mock_message.fields = {
            16: {
                'reaction_to': 'target_message_id'
                # No emoji, no sender
            }
        }

        # Should not crash - this is still a reaction message
        wrapper._on_lxmf_delivery(mock_message)

        mock_kotlin_callback.assert_called_once()
        event = json.loads(mock_kotlin_callback.call_args[0][0])
        self.assertEqual(event['reaction_to'], 'target_message_id')
        self.assertEqual(event['emoji'], '')
        self.assertEqual(event['sender'], '')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_reaction_exception_handling(self, mock_lxmf_mod, mock_rns):
        """Test that send_reaction handles exceptions gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        wrapper.local_lxmf_destination = mock_local_dest

        # Make Identity() constructor raise exception
        mock_rns.Identity.side_effect = Exception("Identity error")

        result = wrapper.send_reaction(
            dest_hash=b'0123456789abcdef',
            target_message_id="target_msg_id",
            emoji="\U0001F44D",
            source_identity_private_key=b'privkey' * 10
        )

        # Should return error dict, not crash
        self.assertFalse(result['success'])
        self.assertIn('error', result)


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
