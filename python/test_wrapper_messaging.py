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
    def test_propagated_fails_without_propagation_node(self, mock_lxmf, mock_rns):
        """Test PROPAGATED fails when no propagation node is set"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.active_propagation_node = None

        mock_local_dest = MagicMock()
        wrapper.local_lxmf_destination = mock_local_dest

        result = wrapper.send_lxmf_message_with_method(
            dest_hash=b'0123456789abcdef',
            content="Test message",
            source_identity_private_key=b'privkey' * 10,
            delivery_method="propagated"
        )

        self.assertFalse(result['success'])
        self.assertIn('propagation node', result['error'].lower())

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

    def tearDown(self):
        """Clean up test fixtures"""
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
        event = json.loads(call_arg)
        self.assertEqual(event['source_hash'], mock_message.source_hash.hex())
        self.assertEqual(event['destination_hash'], mock_message.destination_hash.hex())


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
        # Verify all 10 retry attempts (sleep called 10 times)
        self.assertEqual(mock_sleep.call_count, 10)

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


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
