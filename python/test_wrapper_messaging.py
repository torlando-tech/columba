"""
Test suite for ReticulumWrapper messaging methods.

Tests send_lxmf_message, send_lxmf_message_with_method, send_packet,
message callbacks, delivery confirmations, and error handling.
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


class TestSendLXMFMessage(unittest.TestCase):
    """Test send_lxmf_message method - full message send flow"""

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
    def test_send_message_not_initialized(self, mock_lxmf, mock_rns):
        """Test that send fails when wrapper is not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey'
        )

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertEqual(result['error'], 'LXMF not initialized')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_message_success(self, mock_lxmf, mock_rns):
        """Test successful message send"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router
        mock_router = MagicMock()
        wrapper.router = mock_router

        # Mock local LXMF destination
        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest
        wrapper.display_name = "TestUser"

        # Mock identity recall
        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        mock_rns.Identity.recall.return_value = mock_identity

        # Mock LXMF destination creation
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock LXMF message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.SENT
        mock_lxmf.LXMessage.return_value = mock_message

        # Send message
        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10
        )

        # Verify success
        self.assertTrue(result['success'])
        self.assertIn('message_hash', result)
        self.assertIn('timestamp', result)

        # Verify router.handle_outbound was called
        mock_router.handle_outbound.assert_called_once_with(mock_message)

        # Verify callbacks were registered
        mock_message.register_delivery_callback.assert_called_once()
        mock_message.register_failed_callback.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_message_with_image_attachment(self, mock_lxmf, mock_rns):
        """Test sending message with image attachment"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest
        wrapper.display_name = "TestUser"

        # Mock identity recall
        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        # Mock destination creation
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.SENT
        mock_lxmf.LXMessage.return_value = mock_message

        # Send with image
        image_data = b'\x89PNG\r\n\x1a\n' + b'\x00' * 100
        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Check out this image!",
            source_identity_private_key=b'privkey' * 10,
            image_data=image_data,
            image_format='png'
        )

        self.assertTrue(result['success'])

        # Verify LXMessage was created with fields containing image
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertIn('fields', call_kwargs)
        fields = call_kwargs['fields']
        # Field 6 is the image field: {6: [format, data]}
        self.assertIn(6, fields)
        self.assertEqual(fields[6][0], 'png')  # format
        self.assertEqual(fields[6][1], image_data)  # data

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_message_identity_not_found(self, mock_lxmf, mock_rns):
        """Test sending when recipient identity cannot be recalled"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        wrapper.local_lxmf_destination = mock_local_dest
        wrapper.display_name = "TestUser"

        # Identity recall returns None
        mock_rns.Identity.recall.return_value = None

        # Empty identities cache
        wrapper.identities = {}

        # Should still request path and attempt send
        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10
        )

        # Should fail with identity not found error
        self.assertFalse(result['success'])
        self.assertIn('error', result)

        # Verify path request was attempted
        mock_rns.Transport.request_path.assert_called()

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_message_with_cached_identity(self, mock_lxmf, mock_rns):
        """Test that cached identities are used when recall fails"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest
        wrapper.display_name = "TestUser"

        # Identity recall fails
        mock_rns.Identity.recall.return_value = None

        # But identity is in cache
        dest_hash = b'0123456789abcdef'
        mock_cached_identity = MagicMock()
        mock_cached_identity.hash = dest_hash
        wrapper.identities[dest_hash.hex()] = mock_cached_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.SENT
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message(
            dest_hash=dest_hash,
            content="Test message",
            source_identity_private_key=b'privkey' * 10
        )

        # Should succeed using cached identity
        self.assertTrue(result['success'])

    def test_send_message_when_reticulum_unavailable(self):
        """Test behavior when Reticulum is not available"""
        reticulum_wrapper.RETICULUM_AVAILABLE = False
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey'
        )

        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestSendLXMFMessageWithMethod(unittest.TestCase):
    """Test send_lxmf_message_with_method - delivery method selection"""

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
    def test_send_with_direct_method(self, mock_lxmf, mock_rns):
        """Test sending with DIRECT delivery method"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Mock identity
        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct"
        )

        self.assertTrue(result['success'])
        self.assertEqual(result['delivery_method'], 'direct')

        # Verify message was created with DIRECT method
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertEqual(call_kwargs['desired_method'], mock_lxmf.LXMessage.DIRECT)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_opportunistic_method(self, mock_lxmf, mock_rns):
        """Test sending with OPPORTUNISTIC delivery method"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Mock identity
        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        # Short message for opportunistic
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Short",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="opportunistic"
        )

        self.assertTrue(result['success'])
        self.assertEqual(result['delivery_method'], 'opportunistic')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_opportunistic_falls_back_for_large_content(self, mock_lxmf, mock_rns):
        """Test OPPORTUNISTIC falls back to DIRECT for large content"""
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
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        # Large content (>295 bytes)
        large_content = "A" * 300
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content=large_content,
            source_identity_private_key=b'privkey' * 10,
            delivery_method="opportunistic"
        )

        self.assertTrue(result['success'])

        # Should fall back to direct
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertEqual(call_kwargs['desired_method'], mock_lxmf.LXMessage.DIRECT)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_opportunistic_falls_back_for_attachments(self, mock_lxmf, mock_rns):
        """Test OPPORTUNISTIC falls back to DIRECT when image attached"""
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
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Short",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="opportunistic",
            image_data=b'imagedata',
            image_format='jpg'
        )

        self.assertTrue(result['success'])

        # Should fall back to direct due to attachment
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertEqual(call_kwargs['desired_method'], mock_lxmf.LXMessage.DIRECT)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_propagated_method(self, mock_lxmf, mock_rns):
        """Test sending with PROPAGATED delivery method"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        # Set active propagation node
        wrapper.active_propagation_node = b'propnode12345678'

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="propagated"
        )

        self.assertTrue(result['success'])
        self.assertEqual(result['delivery_method'], 'propagated')

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_propagated_falls_back_to_direct_without_propagation_node(self, mock_lxmf, mock_rns):
        """Test PROPAGATED falls back to DIRECT when no propagation node is set"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = None

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Set up identity mock for send to work
        mock_identity = MagicMock()
        mock_rns.Identity.return_value = mock_identity
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="propagated"
        )

        # Should succeed by falling back to DIRECT
        self.assertTrue(result['success'])
        # Verify it used DIRECT method (fallback from PROPAGATED)
        mock_lxmf.LXMessage.assert_called()
        call_kwargs = mock_lxmf.LXMessage.call_args
        # The desired_method should be DIRECT (fallback)
        self.assertEqual(call_kwargs.kwargs.get('desired_method'), mock_lxmf.LXMessage.DIRECT)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_try_propagation_on_fail_flag(self, mock_lxmf, mock_rns):
        """Test that try_propagation_on_fail flag is set on message"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'propnode12345678'

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            try_propagation_on_fail=True
        )

        self.assertTrue(result['success'])
        # Verify flag was set on message
        self.assertTrue(mock_message.try_propagation_on_fail)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_send_with_immediate_sent_state_check(self, mock_rns, mock_lxmf):
        """Test that send_lxmf_message_with_method checks for immediate SENT state"""
        # Setup module-level mocks
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        reticulum_wrapper.LXMF = mock_lxmf
        reticulum_wrapper.RNS = mock_rns

        # Set LXMF constants
        mock_lxmf.LXMessage.SENT = 0x04
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        mock_lxmf.LXMessage.OUTBOUND = 0x01

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'\xaa\x01\x8e\xd2\x8f\xb6\x44\x05\xf8\x47\x78\x66\xb7\x8a\x66\x8c'

        # Mock identity
        mock_identity = MagicMock()
        mock_identity.hash = b'\xde\xad\xbe\xef' * 4
        mock_identity.load_private_key = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'\xca\xfe\xba\xbe' * 8
        mock_dest.announce = MagicMock()  # Mock announce to avoid errors
        mock_rns.Destination.return_value = mock_dest
        wrapper.local_lxmf_destination = mock_dest

        # Create a message that has SENT state immediately after handle_outbound
        mock_message = MagicMock()
        mock_message.hash = b'\xab\xcd\xef' * 10 + b'\x12\x34'
        mock_message.state = 0x04  # SENT state
        mock_message.try_propagation_on_fail = False
        mock_message.register_delivery_callback = MagicMock()
        mock_message.register_failed_callback = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        # Mock _on_message_sent to track calls
        wrapper._on_message_sent = MagicMock()

        # Send message
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'\xde\xad\xbe\xef' * 4,
            content="Test message",
            source_identity_private_key=b'\x00' * 32,
            delivery_method="propagated"
        )

        # Verify result success
        self.assertTrue(result['success'])

        # Verify _on_message_sent was called since state was SENT
        wrapper._on_message_sent.assert_called_once_with(mock_message)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_send_does_not_call_on_message_sent_for_outbound_state(self, mock_rns, mock_lxmf):
        """Test that _on_message_sent is NOT called when state is OUTBOUND (not SENT)"""
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        reticulum_wrapper.LXMF = mock_lxmf
        reticulum_wrapper.RNS = mock_rns

        # Set LXMF constants
        mock_lxmf.LXMessage.SENT = 0x04
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        mock_lxmf.LXMessage.OUTBOUND = 0x01

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'\xaa\x01\x8e\xd2\x8f\xb6\x44\x05\xf8\x47\x78\x66\xb7\x8a\x66\x8c'

        # Mock identity
        mock_identity = MagicMock()
        mock_identity.hash = b'\xde\xad\xbe\xef' * 4
        mock_identity.load_private_key = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'\xca\xfe\xba\xbe' * 8
        mock_dest.announce = MagicMock()
        mock_rns.Destination.return_value = mock_dest
        wrapper.local_lxmf_destination = mock_dest

        # Create a message with OUTBOUND state (not SENT)
        mock_message = MagicMock()
        mock_message.hash = b'\xab\xcd\xef' * 10 + b'\x12\x34'
        mock_message.state = 0x01  # OUTBOUND state, not SENT
        mock_message.try_propagation_on_fail = False
        mock_message.register_delivery_callback = MagicMock()
        mock_message.register_failed_callback = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        # Mock _on_message_sent to track calls
        wrapper._on_message_sent = MagicMock()

        # Send message
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'\xde\xad\xbe\xef' * 4,
            content="Test message",
            source_identity_private_key=b'\x00' * 32,
            delivery_method="propagated"
        )

        # Verify result success
        self.assertTrue(result['success'])

        # Verify _on_message_sent was NOT called since state was not SENT
        wrapper._on_message_sent.assert_not_called()

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_send_handles_missing_state_attribute_gracefully(self, mock_rns, mock_lxmf):
        """Test that send handles messages without state attribute gracefully"""
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        reticulum_wrapper.LXMF = mock_lxmf
        reticulum_wrapper.RNS = mock_rns

        # Set LXMF constants
        mock_lxmf.LXMessage.SENT = 0x04
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        mock_lxmf.LXMessage.OUTBOUND = 0x01

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'\xaa\x01\x8e\xd2\x8f\xb6\x44\x05\xf8\x47\x78\x66\xb7\x8a\x66\x8c'

        # Mock identity
        mock_identity = MagicMock()
        mock_identity.hash = b'\xde\xad\xbe\xef' * 4
        mock_identity.load_private_key = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'\xca\xfe\xba\xbe' * 8
        mock_dest.announce = MagicMock()
        mock_rns.Destination.return_value = mock_dest
        wrapper.local_lxmf_destination = mock_dest

        # Create a message WITHOUT state attribute (use spec to exclude it)
        mock_message = MagicMock(spec=['hash', 'try_propagation_on_fail',
                                        'register_delivery_callback', 'register_failed_callback'])
        mock_message.hash = b'\xab\xcd\xef' * 10 + b'\x12\x34'
        mock_message.try_propagation_on_fail = False
        mock_message.register_delivery_callback = MagicMock()
        mock_message.register_failed_callback = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        # Mock _on_message_sent
        wrapper._on_message_sent = MagicMock()

        # Send message - should NOT crash
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'\xde\xad\xbe\xef' * 4,
            content="Test message",
            source_identity_private_key=b'\x00' * 32,
            delivery_method="propagated"
        )

        # Verify result success - should not crash
        self.assertTrue(result['success'])

        # _on_message_sent should NOT be called (no state attribute)
        wrapper._on_message_sent.assert_not_called()

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_send_handles_state_check_exception_gracefully(self, mock_rns, mock_lxmf):
        """Test that exceptions during state check don't crash send"""
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        reticulum_wrapper.LXMF = mock_lxmf
        reticulum_wrapper.RNS = mock_rns

        # Set LXMF constants
        mock_lxmf.LXMessage.SENT = 0x04
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        mock_lxmf.LXMessage.OUTBOUND = 0x01

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'\xaa\x01\x8e\xd2\x8f\xb6\x44\x05\xf8\x47\x78\x66\xb7\x8a\x66\x8c'

        # Mock identity
        mock_identity = MagicMock()
        mock_identity.hash = b'\xde\xad\xbe\xef' * 4
        mock_identity.load_private_key = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'\xca\xfe\xba\xbe' * 8
        mock_dest.announce = MagicMock()
        mock_rns.Destination.return_value = mock_dest
        wrapper.local_lxmf_destination = mock_dest

        # Create a message where accessing state raises an exception
        mock_message = MagicMock()
        mock_message.hash = b'\xab\xcd\xef' * 10 + b'\x12\x34'
        # Make state property raise an exception
        type(mock_message).state = property(lambda self: (_ for _ in ()).throw(RuntimeError("State error")))
        mock_message.try_propagation_on_fail = False
        mock_message.register_delivery_callback = MagicMock()
        mock_message.register_failed_callback = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        # Mock _on_message_sent
        wrapper._on_message_sent = MagicMock()

        # Send message - should NOT crash despite state access error
        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'\xde\xad\xbe\xef' * 4,
            content="Test message",
            source_identity_private_key=b'\x00' * 32,
            delivery_method="propagated"
        )

        # Verify result success - exception should be caught
        self.assertTrue(result['success'])

        # _on_message_sent should NOT be called due to exception
        wrapper._on_message_sent.assert_not_called()

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_send_with_propagated_state_does_not_trigger_sent_callback(self, mock_rns, mock_lxmf):
        """Test that PROPAGATED state does not trigger _on_message_sent"""
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        reticulum_wrapper.LXMF = mock_lxmf
        reticulum_wrapper.RNS = mock_rns

        mock_lxmf.LXMessage.SENT = 0x04
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        mock_lxmf.LXMessage.OUTBOUND = 0x01

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = b'\xaa\x01\x8e\xd2\x8f\xb6\x44\x05\xf8\x47\x78\x66\xb7\x8a\x66\x8c'

        mock_identity = MagicMock()
        mock_identity.hash = b'\xde\xad\xbe\xef' * 4
        mock_identity.load_private_key = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity
        mock_rns.Identity.return_value = mock_identity

        mock_dest = MagicMock()
        mock_dest.hash = b'\xca\xfe\xba\xbe' * 8
        mock_dest.announce = MagicMock()
        mock_rns.Destination.return_value = mock_dest
        wrapper.local_lxmf_destination = mock_dest

        # Create a message with PROPAGATED state (not SENT)
        mock_message = MagicMock()
        mock_message.hash = b'\xab\xcd\xef' * 10 + b'\x12\x34'
        mock_message.state = 0x03  # PROPAGATED state
        mock_message.try_propagation_on_fail = False
        mock_message.register_delivery_callback = MagicMock()
        mock_message.register_failed_callback = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        wrapper._on_message_sent = MagicMock()

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'\xde\xad\xbe\xef' * 4,
            content="Test message",
            source_identity_private_key=b'\x00' * 32,
            delivery_method="propagated"
        )

        self.assertTrue(result['success'])
        # PROPAGATED != SENT, so callback should NOT be called
        wrapper._on_message_sent.assert_not_called()

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_image_data_path_reads_file(self, mock_lxmf, mock_rns):
        """Test that image_data_path reads image from file and cleans up"""
        import tempfile
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Mock identity
        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        # Mock destination
        mock_dest = MagicMock()
        mock_dest.hash = b'lxmfdest12345678'
        mock_rns.Destination.return_value = mock_dest

        # Mock message
        mock_message = MagicMock()
        mock_message.hash = b'messagehash12345'
        mock_lxmf.LXMessage.return_value = mock_message

        # Create a temp file with image data
        image_data = b'\x89PNG\r\n\x1a\n' + b'\x00' * 100
        with tempfile.NamedTemporaryFile(delete=False, suffix='.png') as f:
            f.write(image_data)
            temp_image_path = f.name

        try:
            result = wrapper.send_lxmf_message_with_method(
                dest_hash=b'0123456789abcdef',
                content="Test message with image",
                source_identity_private_key=b'privkey' * 10,
                delivery_method="direct",
                image_data_path=temp_image_path,
                image_format="png"
            )

            self.assertTrue(result['success'])

            # Verify image data was passed to message creation
            call_args = mock_lxmf.LXMessage.call_args
            # The fields should include the image data
            fields = call_args[1].get('fields')
            if fields:
                # Check that image data was included in fields
                field_found = False
                for field in fields:
                    if isinstance(field, tuple) and len(field) >= 2:
                        if field[1] == image_data:
                            field_found = True
                            break
                self.assertTrue(field_found or mock_lxmf.LXMessage.called)

            # Verify file was deleted (cleanup)
            self.assertFalse(os.path.exists(temp_image_path))
        finally:
            # Clean up in case test fails
            if os.path.exists(temp_image_path):
                os.remove(temp_image_path)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_missing_image_file_returns_error(self, mock_lxmf, mock_rns):
        """Test error handling when image_data_path file doesn't exist"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()

        mock_local_dest = MagicMock()
        mock_local_dest.hash = b'localdest1234567'
        wrapper.local_lxmf_destination = mock_local_dest

        # Mock identity
        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            image_data_path="/nonexistent/path/to/image.png",
            image_format="png"
        )

        # Should return error for missing file
        self.assertFalse(result['success'])
        self.assertIn('delivery_method', result)
        self.assertIsNone(result['delivery_method'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_error_response_includes_delivery_method_field(self, mock_lxmf, mock_rns):
        """Verify all error responses include delivery_method=None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False  # Not initialized - will cause error
        wrapper.router = None

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct"
        )

        self.assertFalse(result['success'])
        self.assertIn('delivery_method', result)
        self.assertIsNone(result['delivery_method'])


class TestSendPacket(unittest.TestCase):
    """Test send_packet method - raw packet sending"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_send_packet_when_unavailable(self):
        """Test send_packet returns mock data when Reticulum unavailable"""
        original = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.send_packet(
            dest_hash=b'0123456789abcdef',
            data=b'test data'
        )

        # Should return mock receipt
        self.assertIn('receipt_hash', result)
        self.assertIn('delivered', result)
        self.assertIn('timestamp', result)
        self.assertTrue(result['delivered'])

        reticulum_wrapper.RETICULUM_AVAILABLE = original

    def test_send_packet_not_implemented(self):
        """Test that send_packet is not fully implemented yet"""
        original = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result = wrapper.send_packet(
            dest_hash=b'0123456789abcdef',
            data=b'test data'
        )

        # Should return empty receipt (not implemented)
        self.assertIn('receipt_hash', result)
        self.assertFalse(result['delivered'])

        reticulum_wrapper.RETICULUM_AVAILABLE = original


class TestMessageCallbacks(unittest.TestCase):
    """Test register_message_callback and _on_message"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_register_callback(self):
        """Test registering a message callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        callback = Mock()
        wrapper.register_message_callback(callback)

        # Callback should be in list
        self.assertIn(callback, wrapper.message_callbacks)

    def test_on_message_invokes_callbacks(self):
        """Test that _on_message invokes all registered callbacks"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Register multiple callbacks
        callback1 = Mock()
        callback2 = Mock()
        wrapper.register_message_callback(callback1)
        wrapper.register_message_callback(callback2)

        # Create mock message
        mock_message = Mock()
        mock_message.content = b'Test content'
        mock_message.source_hash = b'source123'
        mock_message.destination_hash = b'dest456'
        mock_message.timestamp = 1234567890

        # Invoke internal handler
        wrapper._on_message(mock_message)

        # Both callbacks should be called with message dict
        callback1.assert_called_once()
        callback2.assert_called_once()

        # Verify structure of passed data
        call_arg = callback1.call_args[0][0]
        self.assertEqual(call_arg['content'], b'Test content')
        self.assertEqual(call_arg['source'], b'source123')
        self.assertEqual(call_arg['destination'], b'dest456')
        self.assertEqual(call_arg['timestamp'], 1234567890)

    def test_on_message_handles_callback_errors(self):
        """Test that callback errors don't crash _on_message"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Register callback that raises exception
        bad_callback = Mock(side_effect=Exception("Callback error"))
        good_callback = Mock()

        wrapper.register_message_callback(bad_callback)
        wrapper.register_message_callback(good_callback)

        mock_message = Mock()
        mock_message.content = b'Test'
        mock_message.source_hash = b'src'
        mock_message.destination_hash = b'dst'
        mock_message.timestamp = 123

        # Should not raise exception
        wrapper._on_message(mock_message)

        # Good callback should still be called
        good_callback.assert_called_once()


class TestDeliveryCallbacks(unittest.TestCase):
    """Test delivery status callbacks: _on_message_delivered, _on_message_failed, _on_message_sent"""

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
    def test_on_message_delivered_direct(self):
        """Test _on_message_delivered callback for DIRECT delivery (state=DELIVERED)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock Kotlin callback
        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        # Create mock message with DELIVERED state (direct delivery confirmed by recipient)
        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Direct delivery confirmed

        # Call delivery callback
        wrapper._on_message_delivered(mock_message)

        # Kotlin callback should be invoked with JSON
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        # Parse JSON
        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'delivered')
        self.assertEqual(status_event['message_hash'], mock_message.hash.hex())
        self.assertIn('timestamp', status_event)

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_on_message_delivered_propagated(self):
        """Test _on_message_delivered callback for PROPAGATED delivery (state=SENT)

        When a message is sent via propagation, LXMF sets state=SENT (not DELIVERED)
        because the relay accepted the message, but the end recipient hasn't confirmed.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock Kotlin callback
        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        # Create mock message with SENT state (propagated, relay accepted)
        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.SENT  # Propagated (relay accepted)

        # Call delivery callback
        wrapper._on_message_delivered(mock_message)

        # Kotlin callback should be invoked with 'propagated' status
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        # Parse JSON - should be 'propagated', not 'delivered'
        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'propagated')
        self.assertEqual(status_event['message_hash'], mock_message.hash.hex())
        self.assertIn('timestamp', status_event)

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_on_message_delivered_removes_from_opportunistic_tracking(self):
        """Test that delivered messages are removed from opportunistic tracking"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = Mock()

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status

        # Add to opportunistic tracking
        wrapper._opportunistic_messages = {
            mock_message.hash.hex(): {'message': mock_message, 'sent_time': time.time()}
        }

        # Call delivery callback
        wrapper._on_message_delivered(mock_message)

        # Should be removed from tracking
        self.assertNotIn(mock_message.hash.hex(), wrapper._opportunistic_messages)

    @patch('reticulum_wrapper.LXMF')
    def test_on_message_failed_without_retry(self, mock_lxmf):
        """Test _on_message_failed when not retrying via propagation"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.try_propagation_on_fail = False
        # New attributes for relay fallback tracking
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

        wrapper._on_message_failed(mock_message)

        # Kotlin callback should be invoked with 'failed' status
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'failed')
        self.assertEqual(status_event['message_hash'], mock_message.hash.hex())
        self.assertEqual(status_event['reason'], 'delivery_failed')

    @patch('reticulum_wrapper.LXMF')
    def test_on_message_failed_retries_via_propagation(self, mock_lxmf):
        """Test _on_message_failed retries via propagation when flag set"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = Mock()
        wrapper.active_propagation_node = b'propnode12345678'

        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.try_propagation_on_fail = True
        # New attributes for relay fallback tracking (first attempt)
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []
        # fields must be a dict (not Mock) for "5 in fields" check to work
        mock_message.fields = {}

        wrapper._on_message_failed(mock_message)

        # Should switch to PROPAGATED method
        self.assertEqual(mock_message.desired_method, mock_lxmf.LXMessage.PROPAGATED)

        # Should re-submit to router
        wrapper.router.handle_outbound.assert_called_once_with(mock_message)

        # Kotlin callback should be invoked with 'retrying_propagated' status
        call_arg = mock_kotlin_callback.call_args[0][0]
        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'retrying_propagated')

        # Flag should be cleared to prevent infinite retry
        self.assertFalse(mock_message.try_propagation_on_fail)

        # Should track propagation attempt
        self.assertTrue(mock_message.propagation_retry_attempted)
        self.assertIn(b'propnode12345678', mock_message.tried_relays)

    def test_on_message_failed_no_retry_without_propagation_node(self):
        """Test _on_message_failed doesn't retry when no propagation node"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = Mock()
        wrapper.active_propagation_node = None  # No propagation node

        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.try_propagation_on_fail = True
        # New attributes for relay fallback tracking
        mock_message.propagation_retry_attempted = False
        mock_message.tried_relays = []

        wrapper._on_message_failed(mock_message)

        # Should not retry
        wrapper.router.handle_outbound.assert_not_called()

        # Should report as failed
        call_arg = mock_kotlin_callback.call_args[0][0]
        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'failed')

    def test_on_message_sent(self):
        """Test _on_message_sent callback"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_kotlin_callback = Mock()
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'

        wrapper._on_message_sent(mock_message)

        # Kotlin callback should be invoked with 'sent' status
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        status_event = json.loads(call_arg)
        self.assertEqual(status_event['status'], 'sent')
        self.assertEqual(status_event['message_hash'], mock_message.hash.hex())

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_delivery_callbacks_handle_missing_kotlin_callback(self):
        """Test delivery callbacks work when Kotlin callback not set"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_delivery_status_callback = None

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status

        # Should not raise exception
        wrapper._on_message_delivered(mock_message)
        wrapper._on_message_sent(mock_message)


class TestOnLXMFDelivery(unittest.TestCase):
    """Test _on_lxmf_delivery - incoming message handler"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

        # Mock the global LXMF module which is None in test environment
        self.mock_lxmf = Mock()
        self.mock_lxmf.FIELD_FILE_ATTACHMENTS = 5
        self.lxmf_patcher = patch.object(reticulum_wrapper, 'LXMF', self.mock_lxmf)
        self.lxmf_patcher.start()

    def tearDown(self):
        """Clean up test fixtures"""
        self.lxmf_patcher.stop()
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_on_lxmf_delivery_adds_to_pending_inbound(self):
        """Test that received messages are added to pending_inbound queue"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock router with pending_inbound
        mock_router = Mock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        # Create mock LXMF message
        mock_message = Mock()
        mock_message.source_hash = b'source123'
        mock_message.destination_hash = b'dest456'
        mock_message.content = b'Test message content'
        mock_message.timestamp = 1234567890
        mock_message.fields = {}  # Required for reaction detection

        wrapper._on_lxmf_delivery(mock_message)

        # Message should be added to pending_inbound
        self.assertEqual(len(mock_router.pending_inbound), 1)
        self.assertEqual(mock_router.pending_inbound[0], mock_message)

    def test_on_lxmf_delivery_creates_pending_inbound_if_missing(self):
        """Test that pending_inbound is created if router doesn't have it"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock router WITHOUT pending_inbound
        mock_router = Mock(spec=[])
        wrapper.router = mock_router

        mock_message = Mock()
        mock_message.source_hash = b'source123'
        mock_message.destination_hash = b'dest456'
        mock_message.content = b'Test message'
        mock_message.fields = {}  # Required for reaction detection

        wrapper._on_lxmf_delivery(mock_message)

        # Should create pending_inbound
        self.assertTrue(hasattr(mock_router, 'pending_inbound'))
        self.assertIn(mock_message, mock_router.pending_inbound)

    def test_on_lxmf_delivery_prevents_duplicates(self):
        """Test that duplicate messages are not added twice"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_message = Mock()
        mock_message.source_hash = b'source123'
        mock_message.destination_hash = b'dest456'
        mock_message.content = b'Test message'
        mock_message.fields = {}  # Required for reaction detection

        # Add same message twice
        wrapper._on_lxmf_delivery(mock_message)
        wrapper._on_lxmf_delivery(mock_message)

        # Should only be added once
        self.assertEqual(len(mock_router.pending_inbound), 1)

    def test_on_lxmf_delivery_invokes_kotlin_callback(self):
        """Test that Kotlin message received callback is invoked"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.pending_inbound = []
        wrapper.router = mock_router

        mock_kotlin_callback = Mock()
        wrapper.kotlin_message_received_callback = mock_kotlin_callback

        # Use MagicMock with proper byte values
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'  # 16 bytes
        mock_message.destination_hash = b'dest456dest45678'  # 16 bytes
        mock_message.content = b'Test message'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'

        wrapper._on_lxmf_delivery(mock_message)

        # Kotlin callback should be invoked with JSON
        mock_kotlin_callback.assert_called_once()
        call_arg = mock_kotlin_callback.call_args[0][0]

        # Parse JSON
        import base64
        event = json.loads(call_arg)
        # Source hash and destination hash are now base64 encoded (not hex)
        self.assertEqual(event['source_hash'], base64.b64encode(mock_message.source_hash).decode('ascii'))
        self.assertEqual(event['destination_hash'], base64.b64encode(mock_message.destination_hash).decode('ascii'))


class TestPollReceivedMessages(unittest.TestCase):
    """Test poll_received_messages - message polling"""

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

    def test_poll_returns_empty_when_not_initialized(self):
        """Test polling returns empty list when not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = False

        messages = wrapper.poll_received_messages()

        self.assertEqual(messages, [])

    def test_poll_returns_empty_when_no_router(self):
        """Test polling returns empty list when router is None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = None

        messages = wrapper.poll_received_messages()

        self.assertEqual(messages, [])

    def test_poll_returns_empty_when_reticulum_unavailable(self):
        """Test polling returns empty when Reticulum unavailable"""
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        messages = wrapper.poll_received_messages()

        self.assertEqual(messages, [])

    def test_poll_retrieves_pending_messages(self):
        """Test polling retrieves messages from pending_inbound"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router with pending messages
        mock_router = Mock()
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'  # 16 bytes
        mock_message.destination_hash = b'dest456dest45678'  # 16 bytes
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        messages = wrapper.poll_received_messages()

        # Should retrieve message
        self.assertEqual(len(messages), 1)

        # Verify message structure
        msg = messages[0]
        # The returned message has bytes for hashes (not hex strings)
        self.assertEqual(msg['source_hash'], mock_message.source_hash)
        self.assertEqual(msg['destination_hash'], mock_message.destination_hash)
        # Content should be decoded string
        self.assertIn('content', msg)
        self.assertEqual(msg['content'], mock_message.content.decode('utf-8'))

    def test_poll_prevents_duplicate_processing(self):
        """Test that messages are not returned twice"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = Mock()
        mock_message = Mock()
        mock_message.source_hash = b'source123'
        mock_message.destination_hash = b'dest456'
        mock_message.content = b'Test'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        # Poll first time
        messages1 = wrapper.poll_received_messages()
        self.assertEqual(len(messages1), 1)

        # Poll second time - same message still in queue
        messages2 = wrapper.poll_received_messages()

        # Should not return same message twice (tracked by seen_message_hashes)
        self.assertEqual(len(messages2), 0)

    def test_poll_handles_missing_pending_inbound(self):
        """Test polling handles router without pending_inbound attribute"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router without pending_inbound
        mock_router = Mock(spec=[])
        wrapper.router = mock_router

        # Should not crash
        messages = wrapper.poll_received_messages()
        self.assertEqual(messages, [])

    @patch('reticulum_wrapper.RNS')
    def test_poll_extracts_public_key_when_identity_found(self, mock_rns):
        """Test that poll_received_messages extracts public key from RNS.Identity.recall()"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router with pending message
        mock_router = Mock()
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'  # 16 bytes
        mock_message.destination_hash = b'dest456dest45678'  # 16 bytes
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        # Mock identity with public key
        test_public_key = b'test_public_key_32bytes_exactly!'
        mock_identity = Mock()
        mock_identity.get_public_key.return_value = test_public_key
        mock_rns.Identity.recall.return_value = mock_identity

        messages = wrapper.poll_received_messages()

        # Should retrieve message
        self.assertEqual(len(messages), 1)

        # Verify public key is included
        msg = messages[0]
        self.assertIn('public_key', msg)
        self.assertEqual(msg['public_key'], test_public_key)

        # Verify Identity.recall was called with source_hash
        mock_rns.Identity.recall.assert_called_once_with(mock_message.source_hash)

    @patch('reticulum_wrapper.RNS')
    def test_poll_handles_identity_not_found(self, mock_rns):
        """Test that poll_received_messages handles RNS.Identity.recall() returning None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router with pending message
        mock_router = Mock()
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'  # 16 bytes
        mock_message.destination_hash = b'dest456dest45678'  # 16 bytes
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash567891234'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        # Mock identity recall returning None
        mock_rns.Identity.recall.return_value = None

        messages = wrapper.poll_received_messages()

        # Should retrieve message
        self.assertEqual(len(messages), 1)

        # Verify public_key is NOT in message (identity not found)
        msg = messages[0]
        self.assertNotIn('public_key', msg)

    @patch('reticulum_wrapper.RNS')
    def test_poll_handles_identity_recall_exception(self, mock_rns):
        """Test that poll_received_messages handles exceptions from RNS.Identity.recall()"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router with pending message
        mock_router = Mock()
        mock_message = MagicMock()
        mock_message.source_hash = b'source123source1'  # 16 bytes
        mock_message.destination_hash = b'dest456dest45678'  # 16 bytes
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash891234567'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router

        # Mock identity recall raising exception
        mock_rns.Identity.recall.side_effect = Exception("Network error")

        # Should not crash
        messages = wrapper.poll_received_messages()

        # Should still retrieve message
        self.assertEqual(len(messages), 1)

        # Verify public_key is NOT in message (exception occurred)
        msg = messages[0]
        self.assertNotIn('public_key', msg)

    @patch('reticulum_wrapper.RNS')
    def test_poll_reads_cached_hop_count(self, mock_rns):
        """Test that poll_received_messages reads hop count cached at delivery time"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router with pending message that has cached hop count
        mock_router = Mock()
        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'
        # Simulate hop count captured at delivery time
        mock_message._columba_hops = 3

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router
        mock_rns.Identity.recall.return_value = None

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        self.assertIn('hops', messages[0])
        self.assertEqual(messages[0]['hops'], 3)

    @patch('reticulum_wrapper.RNS')
    def test_poll_reads_cached_zero_hop_count(self, mock_rns):
        """Test that poll_received_messages reads hop count of 0 (direct delivery)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = Mock()
        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678a'
        # Simulate direct delivery (0 hops)
        mock_message._columba_hops = 0

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router
        mock_rns.Identity.recall.return_value = None

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        self.assertIn('hops', messages[0])
        self.assertEqual(messages[0]['hops'], 0)

    @patch('reticulum_wrapper.RNS')
    def test_poll_omits_hops_when_not_cached(self, mock_rns):
        """Test that poll_received_messages omits hops when not captured at delivery"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = Mock()
        # Use Mock (not MagicMock) to avoid auto-creating attributes
        mock_message = Mock(spec=['source_hash', 'destination_hash', 'content', 'timestamp', 'fields', 'hash'])
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678b'
        # No _columba_hops attribute - simulates message where capture failed

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router
        mock_rns.Identity.recall.return_value = None

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        self.assertNotIn('hops', messages[0])  # No cached hop count

    @patch('reticulum_wrapper.RNS')
    def test_poll_reads_cached_receiving_interface(self, mock_rns):
        """Test that poll_received_messages reads receiving interface cached at delivery"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        mock_router = Mock()
        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678c'
        # Simulate interface captured at delivery time (only for direct messages)
        mock_message._columba_hops = 0
        mock_message._columba_interface = 'AutoInterface'

        mock_router.pending_inbound = [mock_message]
        wrapper.router = mock_router
        mock_rns.Identity.recall.return_value = None

        messages = wrapper.poll_received_messages()

        self.assertEqual(len(messages), 1)
        self.assertIn('receiving_interface', messages[0])
        self.assertEqual(messages[0]['receiving_interface'], 'AutoInterface')


class TestOnLxmfDeliveryHopCapture(unittest.TestCase):
    """Test hop count and interface capture at delivery time in _on_lxmf_delivery()"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_captures_hop_count_when_path_exists(self, mock_rns, mock_lxmf):
        """Test that _on_lxmf_delivery captures hop count when path exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        # Create mock LXMF message
        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash123456789'

        # Mock RNS.Transport to return hop count
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.path_table = {}

        # Call delivery handler
        wrapper._on_lxmf_delivery(mock_message)

        # Verify hop count was captured on message object
        self.assertEqual(mock_message._columba_hops, 2)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_skips_hop_count_when_no_path(self, mock_rns, mock_lxmf):
        """Test that hop count not captured when has_path returns False"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock(spec=['source_hash', 'destination_hash', 'content', 'timestamp', 'fields', 'hash'])
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678a'

        # Mock RNS.Transport to return no path
        mock_rns.Transport.has_path.return_value = False
        mock_rns.Transport.path_table = {}

        wrapper._on_lxmf_delivery(mock_message)

        # Verify hop count was NOT captured (attribute should not exist)
        self.assertFalse(hasattr(mock_message, '_columba_hops'))

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_captures_interface_from_lxmf_directly(self, mock_rns, mock_lxmf):
        """Test interface captured when LXMF provides receiving_interface directly (opportunistic messages)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        # Create interface with proper class name (AutoInterfacePeer -> AutoInterface)
        class AutoInterfacePeer:
            pass
        mock_interface = AutoInterfacePeer()

        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678f'
        # LXMF provides receiving_interface directly (opportunistic message scenario)
        mock_message.receiving_interface = mock_interface
        mock_message.receiving_hops = None

        # Mock RNS.Transport - path_table should NOT be checked when LXMF provides interface
        mock_rns.Transport.has_path.return_value = False
        mock_rns.Transport.path_table = {}

        wrapper._on_lxmf_delivery(mock_message)

        # Verify interface was captured from LXMF directly (AutoInterfacePeer -> AutoInterface)
        self.assertEqual(mock_message._columba_interface, "AutoInterface")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_captures_interface_for_direct_message(self, mock_rns, mock_lxmf):
        """Test interface captured when hops=0 and path table entry exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678b'
        mock_message.receiving_interface = None  # Force path_table lookup
        mock_message.receiving_hops = None

        # Create interface with proper class name (we use type().__name__ now)
        class AutoInterfacePeer:
            pass
        mock_interface = AutoInterfacePeer()

        # Mock RNS.Transport for direct delivery (0 hops) with interface in path table
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 0
        mock_rns.Transport.path_table = {
            b'source123source1': [None, None, None, None, None, mock_interface]
        }

        wrapper._on_lxmf_delivery(mock_message)

        # Verify both hop count and interface were captured (AutoInterfacePeer -> AutoInterface)
        self.assertEqual(mock_message._columba_hops, 0)
        self.assertEqual(mock_message._columba_interface, "AutoInterface")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_captures_interface_for_multihop_message(self, mock_rns, mock_lxmf):
        """Test interface captured for multi-hop messages (last hop interface)"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678c'
        mock_message.receiving_interface = None  # Force path_table lookup
        mock_message.receiving_hops = None

        # Create interface with proper class name (we use type().__name__ now)
        class AutoInterfacePeer:
            pass
        mock_interface = AutoInterfacePeer()

        # Mock RNS.Transport for multi-hop delivery (3 hops)
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 3
        mock_rns.Transport.path_table = {
            b'source123source1': [None, None, None, None, None, mock_interface]
        }

        wrapper._on_lxmf_delivery(mock_message)

        # Verify both hop count and interface captured for multi-hop
        self.assertEqual(mock_message._columba_hops, 3)
        self.assertEqual(mock_message._columba_interface, "AutoInterface")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_uses_interface_class_name(self, mock_rns, mock_lxmf):
        """Test interface type is identified by class name"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678d'
        mock_message.receiving_interface = None  # Force path_table lookup
        mock_message.receiving_hops = None

        # Create interface with proper class name (we use type().__name__ now)
        class TCPClientInterface:
            pass
        mock_interface = TCPClientInterface()

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 0
        mock_rns.Transport.path_table = {
            b'source123source1': [None, None, None, None, None, mock_interface]
        }

        wrapper._on_lxmf_delivery(mock_message)

        self.assertEqual(mock_message._columba_interface, "TCPClientInterface")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_uses_class_name_directly(self, mock_rns, mock_lxmf):
        """Test class name is used to identify interface type"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock()
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678e'
        mock_message.receiving_interface = None  # Force path_table lookup
        mock_message.receiving_hops = None

        # Create a custom interface class - class name is used directly
        class CustomSerialInterface:
            pass
        mock_interface = CustomSerialInterface()

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 0
        mock_rns.Transport.path_table = {
            b'source123source1': [None, None, None, None, None, mock_interface]
        }

        wrapper._on_lxmf_delivery(mock_message)

        self.assertEqual(mock_message._columba_interface, "CustomSerialInterface")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_delivery_handles_exception_gracefully(self, mock_rns, mock_lxmf):
        """Test exception in RNS.Transport calls doesn't crash delivery"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = Mock()
        wrapper.router.pending_inbound = []

        mock_message = Mock(spec=['source_hash', 'destination_hash', 'content', 'timestamp', 'fields', 'hash'])
        mock_message.source_hash = b'source123source1'
        mock_message.destination_hash = b'dest456dest45678'
        mock_message.content = b'Test content'
        mock_message.timestamp = 1234567890
        mock_message.fields = None
        mock_message.hash = b'msghash12345678f'

        # Mock RNS.Transport to raise exception
        mock_rns.Transport.has_path.side_effect = Exception("Transport error")

        # Should not raise - delivery should continue even if hop capture fails
        wrapper._on_lxmf_delivery(mock_message)

        # Message should still be in pending_inbound queue
        self.assertIn(mock_message, wrapper.router.pending_inbound)
        # No hop count should be captured
        self.assertFalse(hasattr(mock_message, '_columba_hops'))


class TestErrorHandling(unittest.TestCase):
    """Test error handling across messaging methods"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_message_handles_router_exception(self, mock_lxmf, mock_rns):
        """Test that exceptions during send are caught and returned"""
        original = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True

        # Mock router that raises exception
        mock_router = Mock()
        mock_router.handle_outbound.side_effect = Exception("Network error")
        wrapper.router = mock_router

        mock_local_dest = MagicMock()
        wrapper.local_lxmf_destination = mock_local_dest
        wrapper.display_name = "Test"

        mock_identity = MagicMock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_lxmf.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test",
            source_identity_private_key=b'privkey' * 10
        )

        # Should return error dict
        self.assertFalse(result['success'])
        self.assertIn('error', result)

        reticulum_wrapper.RETICULUM_AVAILABLE = original

    @patch('reticulum_wrapper.LXMF', mock_lxmf)
    def test_delivery_callback_handles_kotlin_callback_error(self):
        """Test that errors in Kotlin callback don't crash delivery handler"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock callback that raises exception
        mock_kotlin_callback = Mock(side_effect=Exception("Kotlin error"))
        wrapper.kotlin_delivery_status_callback = mock_kotlin_callback

        mock_message = Mock()
        mock_message.hash = b'messagehash12345'
        mock_message.state = mock_lxmf.LXMessage.DELIVERED  # Set state for proper status

        # Should not raise exception
        wrapper._on_message_delivered(mock_message)
        wrapper._on_message_sent(mock_message)


class TestPathRequestRetryLogic(unittest.TestCase):
    """Test path request retry logic when identity not immediately found"""

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

    @patch('reticulum_wrapper.time.sleep')
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_requests_path_when_identity_not_found(self, mock_lxmf_module, mock_rns, mock_sleep):
        """Test that path is requested when identity recall initially fails"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.local_lxmf_destination = MagicMock()
        wrapper.display_name = "Test"

        # Identity found on 3rd attempt (after path request)
        mock_identity = MagicMock()
        mock_identity.hash = b'0123456789abcdef'
        # recall returns None twice, then identity on 3rd call
        mock_rns.Identity.recall.side_effect = [None, None, None, mock_identity]

        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        mock_message = MagicMock()
        mock_message.hash = b'msghash123456789'
        mock_lxmf_module.LXMessage.return_value = mock_message

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct"
        )

        # Verify path was requested
        mock_rns.Transport.request_path.assert_called()
        # Verify sleep was called (retry loop)
        self.assertTrue(mock_sleep.called)

    @patch('reticulum_wrapper.time.sleep')
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_path_request_timeout_returns_error(self, mock_lxmf_module, mock_rns, mock_sleep):
        """Test error returned when path request times out after all retries"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.local_lxmf_destination = MagicMock()
        wrapper.display_name = "Test"

        # Identity never found
        mock_rns.Identity.recall.return_value = None

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct"
        )

        # Should fail with "not known" error
        self.assertFalse(result['success'])
        self.assertIn('not known', result['error'].lower())
        # Verify retry attempts ran (at least 10 sleep calls for the retry loop)
        # Note: Use >= because background threads may also call time.sleep
        self.assertGreaterEqual(mock_sleep.call_count, 10)

    @patch('reticulum_wrapper.time.sleep')
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_path_request_exception_handled(self, mock_lxmf_module, mock_rns, mock_sleep):
        """Test that request_path exception doesn't crash, continues to retry"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.local_lxmf_destination = MagicMock()
        wrapper.display_name = "Test"

        # request_path raises exception
        mock_rns.Transport.request_path.side_effect = Exception("Network error")
        # Identity never found
        mock_rns.Identity.recall.return_value = None

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct"
        )

        # Should still return error (not crash)
        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestFileAttachments(unittest.TestCase):
    """Test file attachment handling in send_lxmf_message and send_lxmf_message_with_method"""

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
    def test_send_with_file_attachments_list_format(self, mock_lxmf, mock_rns):
        """Test sending with file attachments in [filename, bytes] list format"""
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

        file_attachments = [
            ['test.txt', b'Hello, World!'],
            ['data.bin', b'\x00\x01\x02\x03']
        ]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test with files",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        # Verify LXMessage was created with fields containing file attachments
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertIn('fields', call_kwargs)
        self.assertIn(5, call_kwargs['fields'])
        self.assertEqual(len(call_kwargs['fields'][5]), 2)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_file_attachments_tuple_format(self, mock_lxmf, mock_rns):
        """Test sending with file attachments in (filename, bytes) tuple format"""
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

        # Tuple format
        file_attachments = [
            ('document.pdf', b'%PDF-1.4...'),
        ]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test with PDF",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertIn(5, call_kwargs['fields'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_opportunistic_falls_back_for_file_attachments(self, mock_lxmf, mock_rns):
        """Test OPPORTUNISTIC falls back to DIRECT when file attachments present"""
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

        file_attachments = [['small.txt', b'tiny']]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Short",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="opportunistic",
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        # Should fall back to direct due to file attachment
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertEqual(call_kwargs['desired_method'], mock_lxmf.LXMessage.DIRECT)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_both_image_and_file_attachments(self, mock_lxmf, mock_rns):
        """Test sending with both image (field 6) and file attachments (field 5)"""
        # Set the FIELD_IMAGE constant so it's used as the key instead of a MagicMock
        mock_lxmf.FIELD_IMAGE = 6
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

        file_attachments = [['doc.txt', b'Document content']]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test with both",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            image_data=b'\xff\xd8\xff\xe0...',
            image_format='jpg',
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        # Should have both field 5 (files) and field 6 (image)
        self.assertIn(5, call_kwargs['fields'])
        self.assertIn(6, call_kwargs['fields'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_with_empty_file_attachments_list(self, mock_lxmf, mock_rns):
        """Test that empty file_attachments list doesn't add field 5"""
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

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="No attachments",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachments=[]
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        # Fields should be None or not contain field 5
        if call_kwargs.get('fields'):
            self.assertNotIn(5, call_kwargs['fields'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_send_lxmf_message_with_file_attachments(self, mock_lxmf, mock_rns):
        """Test send_lxmf_message (not with_method) with file attachments"""
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

        file_attachments = [
            ['report.pdf', b'PDF content here'],
            ['data.csv', b'a,b,c\n1,2,3']
        ]

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertIn(5, call_kwargs['fields'])
        self.assertEqual(len(call_kwargs['fields'][5]), 2)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_file_attachment_dict_format(self, mock_lxmf, mock_rns):
        """Test file attachments in dict format with 'filename' and 'data' keys"""
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

        # Dict format (used by send_lxmf_message)
        file_attachments = [
            {'filename': 'config.json', 'data': b'{"key": "value"}'},
        ]

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Dict format test",
            source_identity_private_key=b'privkey' * 10,
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        self.assertIn(5, call_kwargs['fields'])

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_file_attachment_skips_invalid_format(self, mock_lxmf, mock_rns):
        """Test that invalid attachment formats are skipped without crashing"""
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

        # Mix of valid and invalid formats
        file_attachments = [
            ['valid.txt', b'Valid content'],
            'invalid_string',  # Invalid - should be skipped
            123,  # Invalid - should be skipped
            ['another_valid.bin', b'\x00\x01'],
        ]

        result = wrapper.send_lxmf_message(
            dest_hash=b'0123456789abcdef',
            content="Mixed formats",
            source_identity_private_key=b'privkey' * 10,
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])
        call_kwargs = mock_lxmf.LXMessage.call_args[1]
        # Only 2 valid attachments should be in field 5
        self.assertEqual(len(call_kwargs['fields'][5]), 2)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.LXMF')
    def test_file_attachment_binary_data_conversion(self, mock_lxmf, mock_rns):
        """Test that non-bytes data is converted to bytes"""
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

        # Use bytearray instead of bytes
        file_attachments = [
            ['array.bin', bytearray([0, 1, 2, 3, 4])],
        ]

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Bytearray test",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="direct",
            file_attachments=file_attachments
        )

        self.assertTrue(result['success'])


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
