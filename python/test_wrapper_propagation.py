"""
Test suite for ReticulumWrapper propagation node functionality.

Tests the four key propagation node methods:
1. set_outbound_propagation_node - Set/clear propagation node
2. get_outbound_propagation_node - Get current node configuration
3. request_messages_from_propagation_node - Request messages from node
4. get_propagation_state - Get sync state and progress
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch, PropertyMock

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestSetOutboundPropagationNode(unittest.TestCase):
    """Test set_outbound_propagation_node method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Enable Reticulum
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock router
        self.mock_router = Mock()
        self.wrapper.router = self.mock_router
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_propagation_node_success(self):
        """Test successfully setting a propagation node"""
        test_hash = b'1234567890123456'  # 16-byte hash

        result = self.wrapper.set_outbound_propagation_node(test_hash)

        # Verify success
        self.assertTrue(result['success'])

        # Verify router was called with correct hash
        self.mock_router.set_outbound_propagation_node.assert_called_once_with(test_hash)

        # Verify internal state was updated
        self.assertEqual(self.wrapper.active_propagation_node, test_hash)

    def test_set_propagation_node_clears_when_none(self):
        """Test clearing propagation node by passing None"""
        # First set a node
        self.wrapper.active_propagation_node = b'existing_node_hash'

        result = self.wrapper.set_outbound_propagation_node(None)

        # Verify success
        self.assertTrue(result['success'])

        # Verify router was called with None
        self.mock_router.set_outbound_propagation_node.assert_called_once_with(None)

        # Verify internal state was cleared
        self.assertIsNone(self.wrapper.active_propagation_node)

    def test_set_propagation_node_converts_jarray(self):
        """Test that jarray-like objects are converted to bytes"""
        # Create a mock jarray-like object (iterable but not bytes)
        test_jarray = [0x12, 0x34, 0x56, 0x78, 0x90, 0xab, 0xcd, 0xef,
                       0x12, 0x34, 0x56, 0x78, 0x90, 0xab, 0xcd, 0xef]

        result = self.wrapper.set_outbound_propagation_node(test_jarray)

        # Verify success
        self.assertTrue(result['success'])

        # Verify router was called with bytes
        call_args = self.mock_router.set_outbound_propagation_node.call_args[0][0]
        self.assertIsInstance(call_args, bytes)

        # Verify bytes content matches
        expected_bytes = bytes(test_jarray)
        self.assertEqual(call_args, expected_bytes)

    def test_set_propagation_node_not_initialized(self):
        """Test error when wrapper not initialized"""
        self.wrapper.initialized = False

        result = self.wrapper.set_outbound_propagation_node(b'test_hash_123456')

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'].lower())

    def test_set_propagation_node_no_router(self):
        """Test error when router is not available"""
        self.wrapper.router = None

        result = self.wrapper.set_outbound_propagation_node(b'test_hash_123456')

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'].lower())

    def test_set_propagation_node_router_exception(self):
        """Test handling of router exceptions"""
        self.mock_router.set_outbound_propagation_node.side_effect = Exception("Router error")

        result = self.wrapper.set_outbound_propagation_node(b'test_hash_123456')

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertEqual(result['error'], "Router error")


class TestGetOutboundPropagationNode(unittest.TestCase):
    """Test get_outbound_propagation_node method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Enable Reticulum
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock router
        self.mock_router = Mock()
        self.wrapper.router = self.mock_router
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_propagation_node_returns_hex(self):
        """Test getting propagation node returns hex string"""
        test_hash = b'1234567890123456'
        self.mock_router.get_outbound_propagation_node.return_value = test_hash

        result = self.wrapper.get_outbound_propagation_node()

        # Verify success
        self.assertTrue(result['success'])

        # Verify hex string returned
        self.assertIn('propagation_node', result)
        self.assertEqual(result['propagation_node'], test_hash.hex())

    def test_get_propagation_node_returns_none_when_not_set(self):
        """Test getting propagation node when none is set"""
        self.mock_router.get_outbound_propagation_node.return_value = None

        result = self.wrapper.get_outbound_propagation_node()

        # Verify success
        self.assertTrue(result['success'])

        # Verify None returned
        self.assertIn('propagation_node', result)
        self.assertIsNone(result['propagation_node'])

    def test_get_propagation_node_not_initialized(self):
        """Test error when wrapper not initialized"""
        self.wrapper.initialized = False

        result = self.wrapper.get_outbound_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'].lower())

    def test_get_propagation_node_no_router(self):
        """Test error when router is not available"""
        self.wrapper.router = None

        result = self.wrapper.get_outbound_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)

    def test_get_propagation_node_router_exception(self):
        """Test handling of router exceptions"""
        self.mock_router.get_outbound_propagation_node.side_effect = Exception("Router error")

        result = self.wrapper.get_outbound_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestRequestMessagesFromPropagationNode(unittest.TestCase):
    """Test request_messages_from_propagation_node method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock router
        self.mock_router = Mock()
        self.mock_router.propagation_transfer_state = 0
        self.wrapper.router = self.mock_router
        self.wrapper.initialized = True

        # Set active propagation node
        self.wrapper.active_propagation_node = b'1234567890123456'

        # Mock default identity
        self.mock_identity = Mock()
        self.mock_identity.hash = b'identity_hash123'
        self.wrapper.default_identity = self.mock_identity

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_request_messages_success_default_identity(self):
        """Test successfully requesting messages with default identity"""
        result = self.wrapper.request_messages_from_propagation_node()

        # Verify success
        self.assertTrue(result['success'])

        # Verify router was called with default identity
        self.mock_router.request_messages_from_propagation_node.assert_called_once()
        call_args = self.mock_router.request_messages_from_propagation_node.call_args
        self.assertEqual(call_args[0][0], self.mock_identity)

        # Verify state is returned
        self.assertIn('state', result)
        self.assertEqual(result['state'], 0)

    @patch('reticulum_wrapper.RNS')
    def test_request_messages_with_custom_identity(self, mock_rns):
        """Test requesting messages with custom identity"""
        # Create mock identity private key
        test_private_key = b'test_private_key_bytes_32_chars!'

        # Mock RNS.Identity.from_bytes
        mock_custom_identity = Mock()
        mock_custom_identity.hash = b'custom_identity_'
        mock_rns.Identity.from_bytes.return_value = mock_custom_identity

        result = self.wrapper.request_messages_from_propagation_node(
            identity_private_key=test_private_key
        )

        # Verify success
        self.assertTrue(result['success'])

        # Verify from_bytes was called with the private key
        mock_rns.Identity.from_bytes.assert_called_once_with(test_private_key)

        # Verify router was called with custom identity
        call_args = self.mock_router.request_messages_from_propagation_node.call_args
        self.assertEqual(call_args[0][0], mock_custom_identity)

    def test_request_messages_with_custom_max_messages(self):
        """Test requesting messages with custom max_messages"""
        result = self.wrapper.request_messages_from_propagation_node(max_messages=512)

        # Verify success
        self.assertTrue(result['success'])

        # Verify router was called with custom max_messages
        call_args = self.mock_router.request_messages_from_propagation_node.call_args
        self.assertEqual(call_args[1]['max_messages'], 512)

    def test_request_messages_no_propagation_node(self):
        """Test error when no propagation node is configured"""
        self.wrapper.active_propagation_node = None

        result = self.wrapper.request_messages_from_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('No propagation node', result['error'])

        # Verify error code
        self.assertEqual(result['errorCode'], 'NO_PROPAGATION_NODE')

    def test_request_messages_not_initialized(self):
        """Test error when wrapper not initialized"""
        self.wrapper.initialized = False

        result = self.wrapper.request_messages_from_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'].lower())

    def test_request_messages_no_router(self):
        """Test error when router is not available"""
        self.wrapper.router = None

        result = self.wrapper.request_messages_from_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)

    @patch('reticulum_wrapper.RNS')
    def test_request_messages_converts_jarray_identity(self, mock_rns):
        """Test that jarray-like private key is converted to bytes"""
        # Create a mock jarray-like object (iterable but not bytes)
        test_jarray = [0x01] * 32  # 32-byte private key

        # Mock RNS.Identity.from_bytes
        mock_identity = Mock()
        mock_identity.hash = b'converted_ident'
        mock_rns.Identity.from_bytes.return_value = mock_identity

        result = self.wrapper.request_messages_from_propagation_node(
            identity_private_key=test_jarray
        )

        # Verify success
        self.assertTrue(result['success'])

        # Verify from_bytes was called with bytes
        call_args = mock_rns.Identity.from_bytes.call_args[0][0]
        self.assertIsInstance(call_args, bytes)
        self.assertEqual(call_args, bytes(test_jarray))

    def test_request_messages_router_exception(self):
        """Test handling of router exceptions"""
        self.mock_router.request_messages_from_propagation_node.side_effect = Exception("Request failed")

        result = self.wrapper.request_messages_from_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertEqual(result['error'], "Request failed")

    def test_request_messages_returns_transfer_state(self):
        """Test that current transfer state is returned"""
        # Set different transfer states
        test_states = [0, 1, 2, 3, 4, 5, 7]

        for state in test_states:
            self.mock_router.propagation_transfer_state = state
            result = self.wrapper.request_messages_from_propagation_node()

            self.assertTrue(result['success'])
            self.assertEqual(result['state'], state)


class TestGetPropagationState(unittest.TestCase):
    """Test get_propagation_state method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Enable Reticulum
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock router
        self.mock_router = Mock()
        self.wrapper.router = self.mock_router
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_propagation_state_idle(self):
        """Test getting state when idle (state 0)"""
        self.mock_router.propagation_transfer_state = 0
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        # Verify success
        self.assertTrue(result['success'])

        # Verify state details
        self.assertEqual(result['state'], 0)
        self.assertEqual(result['state_name'], 'idle')
        self.assertEqual(result['progress'], 0.0)

    def test_get_propagation_state_path_requested(self):
        """Test state during path discovery (state 1)"""
        self.mock_router.propagation_transfer_state = 1
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 1)
        self.assertEqual(result['state_name'], 'path_requested')

    def test_get_propagation_state_link_establishing(self):
        """Test state during link establishment (state 2)"""
        self.mock_router.propagation_transfer_state = 2
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 2)
        self.assertEqual(result['state_name'], 'link_establishing')

    def test_get_propagation_state_link_established(self):
        """Test state when link is established (state 3)"""
        self.mock_router.propagation_transfer_state = 3
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 3)
        self.assertEqual(result['state_name'], 'link_established')

    def test_get_propagation_state_request_sent(self):
        """Test state when message list requested (state 4)"""
        self.mock_router.propagation_transfer_state = 4
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 4)
        self.assertEqual(result['state_name'], 'request_sent')

    def test_get_propagation_state_receiving(self):
        """Test state during message download (state 5)"""
        self.mock_router.propagation_transfer_state = 5
        self.mock_router.propagation_transfer_progress = 0.65

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 5)
        self.assertEqual(result['state_name'], 'receiving')
        self.assertEqual(result['progress'], 0.65)

    def test_get_propagation_state_complete(self):
        """Test state when transfer complete (state 7)"""
        self.mock_router.propagation_transfer_state = 7
        self.mock_router.propagation_transfer_progress = 1.0
        self.mock_router.propagation_transfer_last_result = 5  # 5 messages received

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 7)
        self.assertEqual(result['state_name'], 'complete')
        self.assertEqual(result['progress'], 1.0)
        self.assertEqual(result['messages_received'], 5)

    def test_get_propagation_state_unknown_state(self):
        """Test handling of unknown state values"""
        self.mock_router.propagation_transfer_state = 99
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 99)
        self.assertEqual(result['state_name'], 'unknown_99')

    def test_get_propagation_state_messages_received(self):
        """Test that messages_received is included in response"""
        self.mock_router.propagation_transfer_state = 0
        self.mock_router.propagation_transfer_progress = 0.0
        self.mock_router.propagation_transfer_last_result = 10

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertIn('messages_received', result)
        self.assertEqual(result['messages_received'], 10)

    def test_get_propagation_state_no_last_result_attribute(self):
        """Test handling when propagation_transfer_last_result doesn't exist"""
        self.mock_router.propagation_transfer_state = 0
        self.mock_router.propagation_transfer_progress = 0.0

        # Use a special mock that raises AttributeError for propagation_transfer_last_result
        del self.mock_router.propagation_transfer_last_result

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertIn('messages_received', result)
        self.assertEqual(result['messages_received'], 0)  # Should default to 0

    def test_get_propagation_state_not_initialized(self):
        """Test error when wrapper not initialized"""
        self.wrapper.initialized = False

        result = self.wrapper.get_propagation_state()

        self.assertFalse(result['success'])
        self.assertIn('error', result)
        self.assertIn('not initialized', result['error'].lower())

    def test_get_propagation_state_no_router(self):
        """Test error when router is not available"""
        self.wrapper.router = None

        result = self.wrapper.get_propagation_state()

        self.assertFalse(result['success'])
        self.assertIn('error', result)

    def test_get_propagation_state_router_exception(self):
        """Test handling of router exceptions"""
        # Make accessing state raise an exception
        type(self.mock_router).propagation_transfer_state = PropertyMock(
            side_effect=Exception("State error")
        )

        result = self.wrapper.get_propagation_state()

        self.assertFalse(result['success'])
        self.assertIn('error', result)


class TestPropagationNodeIntegration(unittest.TestCase):
    """Integration tests for propagation node workflow"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Enable Reticulum
        reticulum_wrapper.RETICULUM_AVAILABLE = True

        # Mock router
        self.mock_router = Mock()
        self.wrapper.router = self.mock_router
        self.wrapper.initialized = True

        # Mock default identity
        self.mock_identity = Mock()
        self.mock_identity.hash = b'identity_hash123'
        self.wrapper.default_identity = self.mock_identity

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_full_propagation_workflow(self):
        """Test complete workflow: set node, request messages, check state"""
        node_hash = b'propagation_node'

        # Step 1: Set propagation node
        self.mock_router.get_outbound_propagation_node.return_value = node_hash
        result = self.wrapper.set_outbound_propagation_node(node_hash)
        self.assertTrue(result['success'])

        # Step 2: Verify node was set
        result = self.wrapper.get_outbound_propagation_node()
        self.assertTrue(result['success'])
        self.assertEqual(result['propagation_node'], node_hash.hex())

        # Step 3: Request messages
        self.mock_router.propagation_transfer_state = 1  # path_requested
        result = self.wrapper.request_messages_from_propagation_node()
        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 1)

        # Step 4: Check state during transfer
        self.mock_router.propagation_transfer_state = 5  # receiving
        self.mock_router.propagation_transfer_progress = 0.5
        result = self.wrapper.get_propagation_state()
        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 5)
        self.assertEqual(result['state_name'], 'receiving')
        self.assertEqual(result['progress'], 0.5)

        # Step 5: Check state when complete
        self.mock_router.propagation_transfer_state = 7  # complete
        self.mock_router.propagation_transfer_progress = 1.0
        self.mock_router.propagation_transfer_last_result = 3
        result = self.wrapper.get_propagation_state()
        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 7)
        self.assertEqual(result['state_name'], 'complete')
        self.assertEqual(result['messages_received'], 3)

    def test_cannot_request_without_setting_node(self):
        """Test that requesting messages fails if no node is set"""
        # Ensure no node is set
        self.wrapper.active_propagation_node = None

        # Try to request messages
        result = self.wrapper.request_messages_from_propagation_node()

        # Verify failure
        self.assertFalse(result['success'])
        self.assertEqual(result['errorCode'], 'NO_PROPAGATION_NODE')

    def test_can_get_state_without_active_transfer(self):
        """Test that getting state works even without active transfer"""
        self.mock_router.propagation_transfer_state = 0
        self.mock_router.propagation_transfer_progress = 0.0

        result = self.wrapper.get_propagation_state()

        self.assertTrue(result['success'])
        self.assertEqual(result['state'], 0)
        self.assertEqual(result['state_name'], 'idle')

    def test_clearing_node_resets_internal_state(self):
        """Test that clearing node resets wrapper's internal state"""
        # Set a node first
        node_hash = b'propagation_node'
        self.wrapper.set_outbound_propagation_node(node_hash)
        self.assertEqual(self.wrapper.active_propagation_node, node_hash)

        # Clear the node
        result = self.wrapper.set_outbound_propagation_node(None)
        self.assertTrue(result['success'])

        # Verify internal state cleared
        self.assertIsNone(self.wrapper.active_propagation_node)

        # Verify can't request messages after clearing
        result = self.wrapper.request_messages_from_propagation_node()
        self.assertFalse(result['success'])
        self.assertEqual(result['errorCode'], 'NO_PROPAGATION_NODE')


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
