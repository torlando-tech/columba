"""
Test suite for get_autoconnected_interface_endpoints() method.

Tests the ability to retrieve auto-connected interface endpoints from RNS Transport,
which are interfaces created dynamically by RNS discovery that have the
'autoconnect_hash' attribute set.
"""

import sys
import os
import unittest
import json
from unittest.mock import Mock, MagicMock, patch, PropertyMock

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper (if not already mocked by conftest)
if 'RNS' not in sys.modules:
    sys.modules['RNS'] = MagicMock()
    sys.modules['RNS.vendor'] = MagicMock()
    sys.modules['RNS.vendor.platformutils'] = MagicMock()
    sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestGetAutoconnectedInterfaceEndpoints(unittest.TestCase):
    """Test get_autoconnected_interface_endpoints method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_returns_empty_json_when_reticulum_not_available(self):
        """Test that method returns empty JSON array when RETICULUM_AVAILABLE is False"""
        # Temporarily set RETICULUM_AVAILABLE to False
        original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        try:
            result = self.wrapper.get_autoconnected_interface_endpoints()
            self.assertEqual(result, "[]")
            parsed = json.loads(result)
            self.assertEqual(parsed, [])
        finally:
            reticulum_wrapper.RETICULUM_AVAILABLE = original_available

    def test_returns_empty_json_when_not_initialized(self):
        """Test that method returns empty JSON array when wrapper.reticulum is None"""
        # Ensure wrapper is not initialized
        self.wrapper.reticulum = None

        result = self.wrapper.get_autoconnected_interface_endpoints()
        self.assertEqual(result, "[]")
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    def test_returns_empty_json_when_no_interfaces(self, mock_rns):
        """Test that method returns empty JSON array when RNS.Transport.interfaces is empty"""
        # Initialize wrapper
        self.wrapper.reticulum = MagicMock()

        # Set up empty interfaces list
        mock_rns.Transport.interfaces = []

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    def test_returns_empty_json_when_interfaces_lack_autoconnect_hash(self, mock_rns):
        """Test that method returns empty JSON when interfaces don't have autoconnect_hash attribute"""
        self.wrapper.reticulum = MagicMock()

        # Create interface without autoconnect_hash
        iface = MagicMock(spec=['name', 'target_ip', 'target_port', 'online'])
        iface.name = "TestInterface"
        iface.target_ip = "192.168.1.1"
        iface.target_port = 4242
        iface.online = True
        # Note: No autoconnect_hash attribute

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    def test_returns_empty_json_when_interfaces_are_offline(self, mock_rns):
        """Test that method returns empty JSON when interfaces have autoconnect_hash but are offline"""
        self.wrapper.reticulum = MagicMock()

        # Create interface with autoconnect_hash but offline
        iface = MagicMock()
        iface.name = "TestInterface"
        iface.autoconnect_hash = b'some_hash'
        iface.online = False
        iface.target_ip = "192.168.1.1"
        iface.target_port = 4242

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    def test_returns_empty_json_when_interfaces_lack_target_info(self, mock_rns):
        """Test that method returns empty JSON when interfaces lack target_ip/target_port"""
        self.wrapper.reticulum = MagicMock()

        # Create interface with autoconnect_hash but no target info
        iface = MagicMock(spec=['name', 'autoconnect_hash', 'online'])
        iface.name = "TestInterface"
        iface.autoconnect_hash = b'some_hash'
        iface.online = True
        # Note: No target_ip or target_port attributes

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_endpoint_for_single_autoconnected_interface(self, mock_rns):
        """Test that method returns correct endpoint for a single auto-connected interface"""
        self.wrapper.reticulum = MagicMock()

        # Create valid auto-connected interface
        iface = MagicMock()
        iface.name = "AutoConnectedInterface"
        iface.autoconnect_hash = b'some_hash'
        iface.online = True
        iface.target_ip = "192.168.1.1"
        iface.target_port = 4242

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "192.168.1.1:4242")

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_multiple_endpoints(self, mock_rns):
        """Test that method returns all endpoints for multiple auto-connected interfaces"""
        self.wrapper.reticulum = MagicMock()

        # Create multiple valid auto-connected interfaces
        iface1 = MagicMock()
        iface1.name = "Interface1"
        iface1.autoconnect_hash = b'hash1'
        iface1.online = True
        iface1.target_ip = "192.168.1.1"
        iface1.target_port = 4242

        iface2 = MagicMock()
        iface2.name = "Interface2"
        iface2.autoconnect_hash = b'hash2'
        iface2.online = True
        iface2.target_ip = "10.0.0.100"
        iface2.target_port = 5353

        mock_rns.Transport.interfaces = [iface1, iface2]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 2)
        self.assertIn("192.168.1.1:4242", parsed)
        self.assertIn("10.0.0.100:5353", parsed)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_filters_out_non_autoconnected_interfaces(self, mock_rns):
        """Test that method only returns auto-connected interfaces, not regular ones"""
        self.wrapper.reticulum = MagicMock()

        # Create auto-connected interface
        auto_iface = MagicMock()
        auto_iface.name = "AutoInterface"
        auto_iface.autoconnect_hash = b'hash'
        auto_iface.online = True
        auto_iface.target_ip = "192.168.1.1"
        auto_iface.target_port = 4242

        # Create regular interface (no autoconnect_hash)
        regular_iface = MagicMock(spec=['name', 'target_ip', 'target_port', 'online'])
        regular_iface.name = "RegularInterface"
        regular_iface.target_ip = "192.168.2.2"
        regular_iface.target_port = 4243
        regular_iface.online = True

        mock_rns.Transport.interfaces = [auto_iface, regular_iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "192.168.1.1:4242")
        self.assertNotIn("192.168.2.2:4243", parsed)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_filters_out_offline_interfaces(self, mock_rns):
        """Test that method only returns online interfaces"""
        self.wrapper.reticulum = MagicMock()

        # Create online auto-connected interface
        online_iface = MagicMock()
        online_iface.name = "OnlineInterface"
        online_iface.autoconnect_hash = b'hash1'
        online_iface.online = True
        online_iface.target_ip = "192.168.1.1"
        online_iface.target_port = 4242

        # Create offline auto-connected interface
        offline_iface = MagicMock()
        offline_iface.name = "OfflineInterface"
        offline_iface.autoconnect_hash = b'hash2'
        offline_iface.online = False
        offline_iface.target_ip = "192.168.2.2"
        offline_iface.target_port = 4243

        mock_rns.Transport.interfaces = [online_iface, offline_iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "192.168.1.1:4242")
        self.assertNotIn("192.168.2.2:4243", parsed)

    @patch.object(reticulum_wrapper, 'RNS')
    def test_handles_exception_gracefully(self, mock_rns):
        """Test that method returns empty JSON when an exception occurs"""
        self.wrapper.reticulum = MagicMock()

        # Make Transport.interfaces raise an exception when accessed
        type(mock_rns.Transport).interfaces = PropertyMock(
            side_effect=RuntimeError("Transport error")
        )

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_ipv6_addresses(self, mock_rns):
        """Test that method properly handles IPv6 target addresses"""
        self.wrapper.reticulum = MagicMock()

        # Create interface with IPv6 address
        iface = MagicMock()
        iface.name = "IPv6Interface"
        iface.autoconnect_hash = b'hash'
        iface.online = True
        iface.target_ip = "2001:db8::1"
        iface.target_port = 4242

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "2001:db8::1:4242")

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_yggdrasil_ipv6_addresses(self, mock_rns):
        """Test that method properly handles Yggdrasil IPv6 addresses (0200::/7 range)"""
        self.wrapper.reticulum = MagicMock()

        # Create interface with Yggdrasil address
        iface = MagicMock()
        iface.name = "YggdrasilInterface"
        iface.autoconnect_hash = b'hash'
        iface.online = True
        iface.target_ip = "200:abcd::1"
        iface.target_port = 4242

        mock_rns.Transport.interfaces = [iface]

        result = self.wrapper.get_autoconnected_interface_endpoints()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0], "200:abcd::1:4242")

    @patch.object(reticulum_wrapper, 'RNS')
    def test_result_is_valid_json(self, mock_rns):
        """Test that the result is always valid JSON"""
        self.wrapper.reticulum = MagicMock()
        mock_rns.Transport.interfaces = []

        result = self.wrapper.get_autoconnected_interface_endpoints()

        # Should not raise
        parsed = json.loads(result)
        self.assertIsInstance(parsed, list)


if __name__ == '__main__':
    unittest.main()
