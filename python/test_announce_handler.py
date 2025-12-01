"""
Test suite for announce handler functionality
Tests that the announce handler properly integrates with RNS.Transport.register_announce_handler
"""

import sys
import os
import unittest
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


class TestAnnounceHandler(unittest.TestCase):
    """Test the announce handler registration and functionality"""

    def setUp(self):
        """Set up test fixtures"""
        # Create a temporary storage path
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_handler_has_aspect_filter_attribute(self):
        """
        Test that announce handler has required aspect_filter attribute.

        Per Reticulum docs: "Must be an object with an aspect_filter attribute"
        This is REQUIRED for RNS.Transport.register_announce_handler to work.

        EXPECTED TO FAIL: Current implementation uses bare function without aspect_filter
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # The handler reference should have aspect_filter attribute
        self.assertTrue(
            hasattr(wrapper._announce_handler_ref, 'aspect_filter'),
            "Announce handler must have 'aspect_filter' attribute for RNS to call it"
        )

    def test_handler_has_received_announce_method(self):
        """
        Test that announce handler has required received_announce() callable.

        Per Reticulum docs: "and a received_announce(destination_hash,
        announced_identity, app_data) callable"
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # The handler should have received_announce method
        self.assertTrue(
            hasattr(wrapper._announce_handler_ref, 'received_announce'),
            "Announce handler must have 'received_announce' method"
        )

        # It should be callable
        self.assertTrue(
            callable(getattr(wrapper._announce_handler_ref, 'received_announce', None)),
            "received_announce must be callable"
        )

    @patch('reticulum_wrapper.RNS')
    def test_handler_calls_underlying_callback(self, mock_rns):
        """
        Test that when received_announce is called, it invokes the actual handler.

        EXPECTED TO FAIL: Current implementation is just a function reference,
        not a proper handler object.
        """
        # Mock RNS.Transport.hops_to to return 1
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock the internal handler to track calls
        original_handler = wrapper._announce_handler
        wrapper._announce_handler = Mock()

        # Recreate handler ref
        wrapper._announce_handler_ref = reticulum_wrapper.AnnounceHandler(wrapper._announce_handler)

        # Recreate handler ref (simulating what __init__ does)
        if hasattr(wrapper._announce_handler_ref, 'received_announce'):
            # Simulate RNS calling our handler
            test_dest_hash = b'test_destination_hash_bytes'
            test_identity = Mock()
            test_app_data = b'test_app_data'

            # This simulates what RNS.Transport would do
            wrapper._announce_handler_ref.received_announce(
                test_dest_hash,
                test_identity,
                test_app_data
            )

            # Verify our internal handler was called
            wrapper._announce_handler.assert_called_once()
        else:
            self.fail("Handler doesn't have received_announce method")

    def test_aspect_filter_allows_all_announces(self):
        """
        Test that aspect_filter is None to receive ALL announces.

        aspect_filter=None means "receive all announces regardless of aspect"
        This is what we want for Columba to see all peer announces.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        if hasattr(wrapper._announce_handler_ref, 'aspect_filter'):
            # aspect_filter should be None to receive all announces
            self.assertIsNone(
                wrapper._announce_handler_ref.aspect_filter,
                "aspect_filter should be None to receive all announces"
            )
        else:
            self.fail("Handler doesn't have aspect_filter attribute")

    def test_handler_structure_compatible_with_rns(self):
        """
        Integration test: Verify handler structure is compatible with RNS requirements.

        This tests the EXACT requirements from Reticulum's documentation.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        handler = wrapper._announce_handler_ref

        # Check all RNS requirements
        checks = {
            'has_aspect_filter': hasattr(handler, 'aspect_filter'),
            'has_received_announce': hasattr(handler, 'received_announce'),
            'received_announce_callable': callable(getattr(handler, 'received_announce', None)),
        }

        failures = [check for check, passed in checks.items() if not passed]

        self.assertEqual(
            [],
            failures,
            f"Handler fails RNS compatibility checks: {failures}"
        )


class TestAnnounceHandlerIntegration(unittest.TestCase):
    """Integration tests for announce handling flow"""

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
    def test_announce_handler_stores_pending_announces(self, mock_rns):
        """
        Test that when an announce is received, it's stored in pending_announces.

        This tests the full flow:
        1. RNS calls handler.received_announce()
        2. Handler calls wrapper._announce_handler()
        3. Announce is added to pending_announces queue
        """
        # Mock RNS.Transport.hops_to to return 1
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Ensure pending_announces is empty
        self.assertEqual(len(wrapper.pending_announces), 0)

        # Simulate an announce being received
        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_identity.hash = b'test_identity_hash'
        test_app_data = b'test_app_data'

        if hasattr(wrapper._announce_handler_ref, 'received_announce'):
            # Call the handler (simulating what RNS would do)
            wrapper._announce_handler_ref.received_announce(
                test_dest_hash,
                test_identity,
                test_app_data
            )

            # Verify announce was stored
            self.assertEqual(
                len(wrapper.pending_announces),
                1,
                "Announce should be added to pending_announces queue"
            )

            # Verify announce structure
            stored_announce = wrapper.pending_announces[0]
            self.assertEqual(stored_announce['destination_hash'], test_dest_hash)
            self.assertEqual(stored_announce['app_data'], test_app_data)
        else:
            self.fail("Handler doesn't have received_announce method")


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
