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
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test one of the handlers from the handlers dict
        handler = wrapper._announce_handlers["lxmf.delivery"]
        self.assertTrue(
            hasattr(handler, 'aspect_filter'),
            "Announce handler must have 'aspect_filter' attribute for RNS to call it"
        )

    def test_handler_has_received_announce_method(self):
        """
        Test that announce handler has required received_announce() callable.

        Per Reticulum docs: "and a received_announce(destination_hash,
        announced_identity, app_data) callable"
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test one of the handlers from the handlers dict
        handler = wrapper._announce_handlers["lxmf.delivery"]

        # The handler should have received_announce method
        self.assertTrue(
            hasattr(handler, 'received_announce'),
            "Announce handler must have 'received_announce' method"
        )

        # It should be callable
        self.assertTrue(
            callable(getattr(handler, 'received_announce', None)),
            "received_announce must be callable"
        )

    @patch('reticulum_wrapper.RNS')
    def test_handler_calls_underlying_callback(self, mock_rns):
        """
        Test that when received_announce is called, it invokes the actual handler.
        """
        # Mock RNS.Transport.hops_to to return 1
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Mock the internal handler to track calls
        wrapper._announce_handler = Mock()

        # Create a new handler with the mocked callback
        test_handler = reticulum_wrapper.AnnounceHandler("lxmf.delivery", wrapper._announce_handler)

        # Simulate RNS calling our handler
        test_dest_hash = b'test_destination_hash_bytes'
        test_identity = Mock()
        test_app_data = b'test_app_data'

        # This simulates what RNS.Transport would do
        test_handler.received_announce(
            test_dest_hash,
            test_identity,
            test_app_data
        )

        # Verify our internal handler was called
        wrapper._announce_handler.assert_called_once()

    def test_aspect_filter_matches_handler_aspect(self):
        """
        Test that each handler has its correct aspect_filter set.

        The implementation uses multiple handlers, each filtering for a specific aspect.
        This allows proper routing of announces to the correct handlers.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Verify each handler has the correct aspect filter
        expected_aspects = ["lxmf.delivery", "lxmf.propagation", "nomadnetwork.node"]

        for aspect in expected_aspects:
            handler = wrapper._announce_handlers[aspect]
            self.assertEqual(
                handler.aspect_filter,
                aspect,
                f"Handler for {aspect} should have matching aspect_filter"
            )

    def test_handler_structure_compatible_with_rns(self):
        """
        Integration test: Verify handler structure is compatible with RNS requirements.

        This tests the EXACT requirements from Reticulum's documentation.
        """
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Test all handlers for RNS compatibility
        for aspect, handler in wrapper._announce_handlers.items():
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
                f"Handler for {aspect} fails RNS compatibility checks: {failures}"
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

        # Use one of the handlers from the handlers dict
        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Call the handler (simulating what RNS would do)
        handler.received_announce(
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


class TestAnnounceTableExtraction(unittest.TestCase):
    """Test announce table extraction logic (lines 1225-1236)"""

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
    def test_extract_interface_from_announce_table(self, mock_rns):
        """Test extracting receiving interface from RNS.Transport.announce_table"""
        # Setup mock announce_table with proper structure
        test_dest_hash = b'test_dest_hash_123'

        # Mock packet with receiving_interface
        mock_packet = Mock()
        mock_interface = Mock()
        # Code builds "ClassName[UserConfiguredName]" from type().__name__ and .name
        mock_interface.__class__.__name__ = 'TCPInterface'
        mock_interface.name = "Testnet/127.0.0.1:4242"  # Just the user-configured name
        mock_packet.receiving_interface = mock_interface

        # announce_table entry structure: IDX_AT_PACKET is at index 5
        announce_entry = [None, None, None, None, None, mock_packet]

        mock_rns.Transport.announce_table = {test_dest_hash: announce_entry}
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Trigger announce handler
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify interface was extracted (format: "ClassName[UserConfiguredName]")
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['interface'], "TCPInterface[Testnet/127.0.0.1:4242]")

    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_announce_table(self, mock_rns):
        """Test handler gracefully handles missing announce_table"""
        # RNS.Transport has no announce_table attribute
        mock_rns.Transport.announce_table = None
        delattr(mock_rns.Transport, 'announce_table')
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was still stored (with None interface)
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['interface'])

    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_packet_in_announce_entry(self, mock_rns):
        """Test handler handles announce_table entry without packet"""
        test_dest_hash = b'test_dest_hash_123'

        # announce_table entry with only 5 elements (no packet at index 5)
        announce_entry = [None, None, None, None, None]

        mock_rns.Transport.announce_table = {test_dest_hash: announce_entry}
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Should not crash, interface should be None
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['interface'])


class TestLXMFDisplayNameExtraction(unittest.TestCase):
    """Test LXMF display name extraction logic (lines 1243-1279)"""

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
    def test_extract_display_name_for_lxmf_delivery(self, mock_rns, mock_lxmf):
        """Test extracting display name for lxmf.delivery aspect"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.display_name_from_app_data.return_value = "Alice"

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'app_data_with_name'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify LXMF.display_name_from_app_data was called
        mock_lxmf.display_name_from_app_data.assert_called_once_with(test_app_data)

        # Verify display name was stored
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['display_name'], "Alice")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_extract_display_name_for_lxmf_propagation(self, mock_rns, mock_lxmf):
        """Test extracting display name for lxmf.propagation aspect"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_name_from_app_data.return_value = "Propagation Node Alpha"

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'app_data_with_pn_name'

        handler = wrapper._announce_handlers["lxmf.propagation"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify LXMF.pn_name_from_app_data was called
        mock_lxmf.pn_name_from_app_data.assert_called_once_with(test_app_data)

        # Verify display name was stored
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['display_name'], "Propagation Node Alpha")

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_handle_lxmf_name_extraction_exception(self, mock_rns, mock_lxmf):
        """Test handler gracefully handles LXMF name extraction exceptions"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.display_name_from_app_data.side_effect = Exception("LXMF parsing error")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'malformed_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored with None display_name
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['display_name'])

    @patch('reticulum_wrapper.LXMF', None)
    @patch('reticulum_wrapper.RNS')
    def test_handle_missing_lxmf_module(self, mock_rns):
        """Test handler handles missing LXMF module gracefully"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Should not crash, display_name should be None
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['display_name'])


class TestStampCostExtraction(unittest.TestCase):
    """Test stamp cost/flexibility/peering cost extraction (lines 1262-1279)"""

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
    def test_extract_stamp_cost_for_delivery(self, mock_rns, mock_lxmf):
        """Test extracting stamp cost for lxmf.delivery aspect"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.stamp_cost_from_app_data.return_value = 8

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'app_data_with_stamp'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify LXMF.stamp_cost_from_app_data was called
        mock_lxmf.stamp_cost_from_app_data.assert_called_once_with(test_app_data)

        # Verify stamp cost was stored
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['stamp_cost'], 8)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_extract_propagation_node_stamp_costs(self, mock_rns, mock_lxmf):
        """Test extracting stamp cost, flexibility, and peering cost for propagation nodes"""
        # Setup RNS.vendor.umsgpack mock before creating wrapper
        # This needs to be set up BEFORE the import happens in _announce_handler
        mock_umsgpack = MagicMock()
        mock_umsgpack.unpackb.return_value = [
            None, None, None, None, None,
            [16, 2, 4]  # [stamp_cost, flexibility, peering_cost]
        ]
        mock_rns.vendor.umsgpack = mock_umsgpack

        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'propagation_node_app_data'

        # Patch umsgpack in the context where it's imported (inside the function)
        with patch('RNS.vendor.umsgpack.unpackb', return_value=[
            None, None, None, None, None,
            [16, 2, 4]  # [stamp_cost, flexibility, peering_cost]
        ]):
            handler = wrapper._announce_handlers["lxmf.propagation"]
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify LXMF.pn_stamp_cost_from_app_data was called
        mock_lxmf.pn_stamp_cost_from_app_data.assert_called_once_with(test_app_data)

        # Verify all costs were stored
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['stamp_cost'], 16)
        self.assertEqual(stored_announce['stamp_cost_flexibility'], 2)
        self.assertEqual(stored_announce['peering_cost'], 4)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_handle_umsgpack_unpacking_error(self, mock_rns, mock_lxmf):
        """Test handler gracefully handles umsgpack unpacking errors"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'malformed_propagation_data'

        # Patch umsgpack to raise exception during unpack
        with patch('RNS.vendor.umsgpack.unpackb', side_effect=Exception("Invalid msgpack data")):
            handler = wrapper._announce_handlers["lxmf.propagation"]

            # Should not raise exception
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored (flexibility and peering_cost should be None)
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_handle_stamp_cost_extraction_exception(self, mock_rns, mock_lxmf):
        """Test handler gracefully handles stamp cost extraction exceptions"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.stamp_cost_from_app_data.side_effect = Exception("Stamp cost parsing error")

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'malformed_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored with None stamp_cost
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['stamp_cost'])


class TestKotlinBridgeNotification(unittest.TestCase):
    """Test Kotlin bridge notification logic (lines 1298-1308)"""

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
    def test_kotlin_bridge_notified_on_announce(self, mock_rns):
        """Test that kotlin_reticulum_bridge.notifyAnnounceReceived is called"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Set up mock Kotlin bridge
        mock_bridge = Mock()
        wrapper.kotlin_reticulum_bridge = mock_bridge

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify bridge was notified
        mock_bridge.notifyAnnounceReceived.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    def test_no_crash_with_none_bridge(self, mock_rns):
        """Test that handler doesn't crash when kotlin_reticulum_bridge is None"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.kotlin_reticulum_bridge = None

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was still stored
        self.assertEqual(len(wrapper.pending_announces), 1)

    @patch('reticulum_wrapper.RNS')
    def test_bridge_notification_exception_handled(self, mock_rns):
        """Test that exceptions from bridge notification are caught and logged"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Set up mock bridge that raises exception
        mock_bridge = Mock()
        mock_bridge.notifyAnnounceReceived.side_effect = Exception("Bridge communication error")
        wrapper.kotlin_reticulum_bridge = mock_bridge

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception (exception should be caught and logged)
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was still stored despite bridge error
        self.assertEqual(len(wrapper.pending_announces), 1)


class TestCallbackExceptionHandling(unittest.TestCase):
    """Test callback exception handling logic (lines 1310-1316)"""

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
    def test_callback_exception_does_not_crash_handler(self, mock_rns):
        """Test that exceptions in registered callbacks don't crash the handler"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Register a callback that raises an exception
        failing_callback = Mock(side_effect=Exception("Callback processing error"))
        wrapper.announce_callbacks.append(failing_callback)

        # Also register a working callback to verify it still gets called
        working_callback = Mock()
        wrapper.announce_callbacks.append(working_callback)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]

        # Should not raise exception despite failing callback
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify both callbacks were called
        failing_callback.assert_called_once()
        working_callback.assert_called_once()

        # Verify announce was still stored
        self.assertEqual(len(wrapper.pending_announces), 1)

    @patch('reticulum_wrapper.RNS')
    def test_multiple_callbacks_all_called(self, mock_rns):
        """Test that all registered callbacks are called even if one fails"""
        mock_rns.Transport.hops_to.return_value = 1

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Register multiple callbacks
        callback1 = Mock()
        callback2 = Mock(side_effect=Exception("Callback 2 error"))
        callback3 = Mock()

        wrapper.announce_callbacks.extend([callback1, callback2, callback3])

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'test_app_data'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify all callbacks were called despite callback2 failing
        callback1.assert_called_once()
        callback2.assert_called_once()
        callback3.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    def test_callback_receives_correct_announce_event(self, mock_rns):
        """Test that callbacks receive the correct announce event structure"""
        mock_rns.Transport.hops_to.return_value = 2

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        callback = Mock()
        wrapper.announce_callbacks.append(callback)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey_456')
        test_app_data = b'test_app_data_789'

        handler = wrapper._announce_handlers["lxmf.delivery"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify callback was called with announce_event
        callback.assert_called_once()
        announce_event = callback.call_args[0][0]

        # Verify event structure
        self.assertEqual(announce_event['destination_hash'], test_dest_hash)
        self.assertEqual(announce_event['public_key'], b'test_pubkey_456')
        self.assertEqual(announce_event['app_data'], test_app_data)
        self.assertEqual(announce_event['aspect'], 'lxmf.delivery')
        self.assertEqual(announce_event['hops'], 2)


class TestMsgpackUpgradeEdgeCases(unittest.TestCase):
    """
    Test umsgpack deserialization edge cases for propagation node announces.

    These tests verify that the msgpack upgrade (0.9.8 -> 0.9.10) doesn't break
    the propagation node stamp cost extraction at lines 1054-1057 of reticulum_wrapper.py:

        from RNS.vendor import umsgpack
        data = umsgpack.unpackb(app_data)
        stamp_cost_flexibility = int(data[5][1])
        peering_cost = int(data[5][2])
    """

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
    def test_umsgpack_index_out_of_bounds_data5_missing(self, mock_rns, mock_lxmf):
        """Test handler when data[5] doesn't exist (short array)"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'short_array_data'

        # Mock umsgpack to return array with only 3 elements (no index 5)
        with patch('RNS.vendor.umsgpack.unpackb', return_value=[1, 2, 3]):
            handler = wrapper._announce_handlers["lxmf.propagation"]
            # Should not raise - IndexError caught by except block
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored with None values
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_umsgpack_index_out_of_bounds_nested(self, mock_rns, mock_lxmf):
        """Test handler when data[5][1] or data[5][2] doesn't exist"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'short_nested_data'

        # Mock umsgpack to return data[5] with only 1 element
        with patch('RNS.vendor.umsgpack.unpackb', return_value=[
            None, None, None, None, None,
            [16]  # Only stamp_cost, no flexibility or peering_cost
        ]):
            handler = wrapper._announce_handlers["lxmf.propagation"]
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored with None values for missing indices
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_umsgpack_type_error_on_int_conversion(self, mock_rns, mock_lxmf):
        """Test handler when int() conversion fails (non-numeric value)"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'non_numeric_data'

        # Mock umsgpack to return non-numeric values in the expected positions
        with patch('RNS.vendor.umsgpack.unpackb', return_value=[
            None, None, None, None, None,
            [16, "not_a_number", {"dict": "value"}]  # Can't convert to int
        ]):
            handler = wrapper._announce_handlers["lxmf.propagation"]
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored - exception caught
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        # Due to exception, these will be None
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_umsgpack_large_values(self, mock_rns, mock_lxmf):
        """Test handler with large int values at boundary"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 999999
        mock_lxmf.pn_announce_data_is_valid.return_value = True

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'large_values_data'

        # Test with large but valid integer values
        large_flex = 2**31 - 1  # Max 32-bit signed int
        large_peer = 2**16      # Larger than typical values

        with patch('RNS.vendor.umsgpack.unpackb', return_value=[
            None, None, None, None, None,
            [999999, large_flex, large_peer]
        ]):
            handler = wrapper._announce_handlers["lxmf.propagation"]
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify large values are handled correctly
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['stamp_cost'], 999999)
        self.assertEqual(stored_announce['stamp_cost_flexibility'], large_flex)
        self.assertEqual(stored_announce['peering_cost'], large_peer)

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_umsgpack_empty_app_data(self, mock_rns, mock_lxmf):
        """Test handler with empty app_data bytes"""
        mock_rns.Transport.hops_to.return_value = 1
        # Empty app_data should not trigger LXMF calls due to "if LXMF is not None and app_data" check

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b''  # Empty bytes

        handler = wrapper._announce_handlers["lxmf.propagation"]
        handler.received_announce(test_dest_hash, test_identity, test_app_data)

        # Verify announce was stored but costs are None (no LXMF extraction attempted)
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertIsNone(stored_announce['stamp_cost'])
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])

    @patch('reticulum_wrapper.LXMF')
    @patch('reticulum_wrapper.RNS')
    def test_pn_announce_data_is_valid_returns_false(self, mock_rns, mock_lxmf):
        """Test that umsgpack is not called when pn_announce_data_is_valid returns False"""
        mock_rns.Transport.hops_to.return_value = 1
        mock_lxmf.pn_stamp_cost_from_app_data.return_value = 16
        mock_lxmf.pn_announce_data_is_valid.return_value = False  # Data not valid

        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        test_dest_hash = b'test_dest_hash_123'
        test_identity = Mock()
        test_identity.get_public_key = Mock(return_value=b'test_pubkey')
        test_app_data = b'invalid_pn_data'

        # umsgpack should NOT be called since pn_announce_data_is_valid returns False
        with patch('RNS.vendor.umsgpack.unpackb') as mock_unpackb:
            handler = wrapper._announce_handlers["lxmf.propagation"]
            handler.received_announce(test_dest_hash, test_identity, test_app_data)

            # Verify umsgpack was not called
            mock_unpackb.assert_not_called()

        # Verify announce was stored with stamp_cost but no flexibility/peering
        self.assertEqual(len(wrapper.pending_announces), 1)
        stored_announce = wrapper.pending_announces[0]
        self.assertEqual(stored_announce['stamp_cost'], 16)
        self.assertIsNone(stored_announce['stamp_cost_flexibility'])
        self.assertIsNone(stored_announce['peering_cost'])


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
