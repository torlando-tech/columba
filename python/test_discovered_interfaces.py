"""
Test suite for get_discovered_interfaces() method.

Tests the ability to retrieve discovered interface information from RNS 1.1.0+
via the RNS.Reticulum.discovered_interfaces() method.
"""

import sys
import os
import unittest
import json
from unittest.mock import Mock, MagicMock, patch

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


class TestGetDiscoveredInterfaces(unittest.TestCase):
    """Test get_discovered_interfaces method"""

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
        original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        try:
            result = self.wrapper.get_discovered_interfaces()
            self.assertEqual(result, "[]")
            parsed = json.loads(result)
            self.assertEqual(parsed, [])
        finally:
            reticulum_wrapper.RETICULUM_AVAILABLE = original_available

    def test_returns_empty_json_when_not_initialized(self):
        """Test that method returns empty JSON array when wrapper.reticulum is None"""
        self.wrapper.reticulum = None

        result = self.wrapper.get_discovered_interfaces()
        self.assertEqual(result, "[]")
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_empty_json_when_method_not_available(self, mock_rns):
        """Test that method returns empty JSON when discovered_interfaces doesn't exist"""
        self.wrapper.reticulum = MagicMock()

        # Remove the method from RNS.Reticulum to simulate older RNS version
        mock_reticulum = MagicMock(spec=[])  # Empty spec means no attributes
        mock_rns.Reticulum = mock_reticulum

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_empty_json_when_no_interfaces_discovered(self, mock_rns):
        """Test that method returns empty JSON when no interfaces are discovered"""
        self.wrapper.reticulum = MagicMock()
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_single_tcp_interface(self, mock_rns):
        """Test that method properly returns a single TCP interface"""
        self.wrapper.reticulum = MagicMock()

        # Mock discovered interface data
        mock_interface = {
            'name': 'TestTCPServer',
            'type': 'TCPServerInterface',
            'transport_id': b'\x01\x02\x03\x04',
            'network_id': b'\x05\x06\x07\x08',
            'status': 'available',
            'status_code': 1000,
            'last_heard': 1234567890,
            'heard_count': 5,
            'hops': 2,
            'value': 14,
            'reachable_on': '192.168.1.100',
            'port': 4242,
        }
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[mock_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['name'], 'TestTCPServer')
        self.assertEqual(parsed[0]['type'], 'TCPServerInterface')
        self.assertEqual(parsed[0]['transport_id'], '01020304')
        self.assertEqual(parsed[0]['network_id'], '05060708')
        self.assertEqual(parsed[0]['reachable_on'], '192.168.1.100')
        self.assertEqual(parsed[0]['port'], 4242)
        self.assertEqual(parsed[0]['status_code'], 1000)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_radio_interface_with_lora_params(self, mock_rns):
        """Test that method properly returns a radio interface with LoRa parameters"""
        self.wrapper.reticulum = MagicMock()

        mock_interface = {
            'name': 'TestRNode',
            'type': 'RNodeInterface',
            'transport_id': b'\xaa\xbb\xcc\xdd',
            'status': 'available',
            'status_code': 1000,
            'frequency': 868000000,
            'bandwidth': 125000,
            'sf': 7,
            'cr': 5,
            'modulation': 'LoRa',
        }
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[mock_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['name'], 'TestRNode')
        self.assertEqual(parsed[0]['type'], 'RNodeInterface')
        self.assertEqual(parsed[0]['frequency'], 868000000)
        self.assertEqual(parsed[0]['bandwidth'], 125000)
        self.assertEqual(parsed[0]['spreading_factor'], 7)
        self.assertEqual(parsed[0]['coding_rate'], 5)
        self.assertEqual(parsed[0]['modulation'], 'LoRa')

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_interface_with_location(self, mock_rns):
        """Test that method properly returns interface with location data"""
        self.wrapper.reticulum = MagicMock()

        mock_interface = {
            'name': 'GeolocatedInterface',
            'type': 'TCPServerInterface',
            'status': 'available',
            'status_code': 1000,
            'latitude': 37.7749,
            'longitude': -122.4194,
            'height': 100.5,
        }
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[mock_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['latitude'], 37.7749)
        self.assertEqual(parsed[0]['longitude'], -122.4194)
        self.assertEqual(parsed[0]['height'], 100.5)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_multiple_interfaces_sorted(self, mock_rns):
        """Test that method returns multiple interfaces sorted by status and stamp value"""
        self.wrapper.reticulum = MagicMock()

        mock_interfaces = [
            {'name': 'LowPriority', 'type': 'TCP', 'status_code': 100, 'value': 10},
            {'name': 'HighPriority', 'type': 'TCP', 'status_code': 1000, 'value': 20},
            {'name': 'MediumPriority', 'type': 'TCP', 'status_code': 1000, 'value': 15},
        ]
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=mock_interfaces)

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 3)
        # Should be sorted by status_code (desc), then stamp_value (desc)
        self.assertEqual(parsed[0]['name'], 'HighPriority')
        self.assertEqual(parsed[1]['name'], 'MediumPriority')
        self.assertEqual(parsed[2]['name'], 'LowPriority')

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_string_transport_id(self, mock_rns):
        """Test that method handles string transport_id (not bytes)"""
        self.wrapper.reticulum = MagicMock()

        mock_interface = {
            'name': 'StringIdInterface',
            'type': 'TCPServerInterface',
            'transport_id': 'already_a_string',  # String instead of bytes
        }
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[mock_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['transport_id'], 'already_a_string')

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_filters_none_values(self, mock_rns):
        """Test that None values are filtered from output"""
        self.wrapper.reticulum = MagicMock()

        mock_interface = {
            'name': 'SparseInterface',
            'type': 'TCPServerInterface',
            'port': None,  # None value should be filtered
            'frequency': None,  # None value should be filtered
        }
        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[mock_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        self.assertEqual(len(parsed), 1)
        self.assertNotIn('port', parsed[0])
        self.assertNotIn('frequency', parsed[0])

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_exception_in_interface_processing(self, mock_rns):
        """Test that method handles exceptions when processing individual interfaces"""
        self.wrapper.reticulum = MagicMock()

        # First interface causes exception, second is valid
        bad_interface = MagicMock()
        bad_interface.get = Mock(side_effect=RuntimeError("Test error"))

        good_interface = {
            'name': 'GoodInterface',
            'type': 'TCPServerInterface',
        }

        mock_rns.Reticulum.discovered_interfaces = Mock(return_value=[bad_interface, good_interface])

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)

        # Should still get the good interface
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['name'], 'GoodInterface')

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_exception_gracefully(self, mock_rns):
        """Test that method returns empty JSON when an exception occurs"""
        self.wrapper.reticulum = MagicMock()
        mock_rns.Reticulum.discovered_interfaces = Mock(side_effect=RuntimeError("Discovery error"))

        result = self.wrapper.get_discovered_interfaces()
        parsed = json.loads(result)
        self.assertEqual(parsed, [])

    def test_result_is_valid_json(self):
        """Test that the result is always valid JSON"""
        self.wrapper.reticulum = None

        result = self.wrapper.get_discovered_interfaces()

        # Should not raise
        parsed = json.loads(result)
        self.assertIsInstance(parsed, list)


class TestGetDiscoveryStatusName(unittest.TestCase):
    """Test _get_discovery_status_name helper method"""

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

    def test_status_available_for_code_1000(self):
        """Test that status code 1000 returns 'available'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(1000), "available")

    def test_status_available_for_code_above_1000(self):
        """Test that status code above 1000 returns 'available'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(1500), "available")

    def test_status_unknown_for_code_100(self):
        """Test that status code 100 returns 'unknown'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(100), "unknown")

    def test_status_unknown_for_code_500(self):
        """Test that status code 500 returns 'unknown'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(500), "unknown")

    def test_status_stale_for_code_0(self):
        """Test that status code 0 returns 'stale'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(0), "stale")

    def test_status_stale_for_negative_code(self):
        """Test that negative status code returns 'stale'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(-1), "stale")

    def test_status_stale_for_code_99(self):
        """Test that status code 99 returns 'stale'"""
        self.assertEqual(self.wrapper._get_discovery_status_name(99), "stale")


class TestDiscoveryConfigGeneration(unittest.TestCase):
    """Test discovery config generation in _create_config_file"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.wrapper.config_dir = self.temp_dir

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_config_includes_discover_interfaces_when_enabled(self):
        """Test that config includes discover_interfaces when enabled"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=True,
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertIn("discover_interfaces = yes", config_content)

    def test_config_includes_autoconnect_count_when_set(self):
        """Test that config includes autoconnect count when > 0"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=True,
            autoconnect_discovered_interfaces=5,
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertIn("autoconnect_discovered_interfaces = 5", config_content)

    def test_config_includes_discovery_sources_when_provided(self):
        """Test that config includes discovery sources when provided"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=True,
            interface_discovery_sources=["abc123", "def456"],
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertIn("interface_discovery_sources = abc123, def456", config_content)

    def test_config_includes_custom_discovery_value(self):
        """Test that config includes custom required_discovery_value"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=True,
            required_discovery_value=18,
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertIn("required_discovery_value = 18", config_content)

    def test_config_omits_default_discovery_value(self):
        """Test that config omits required_discovery_value when default (14)"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=True,
            required_discovery_value=14,  # Default value
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertNotIn("required_discovery_value", config_content)

    def test_config_omits_discovery_settings_when_disabled(self):
        """Test that config omits discovery settings when disabled"""
        result = self.wrapper._create_config_file(
            interfaces=[],
            discover_interfaces=False,
            autoconnect_discovered_interfaces=0,
        )
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertNotIn("discover_interfaces", config_content)
        self.assertNotIn("autoconnect_discovered_interfaces", config_content)

    def test_config_includes_bootstrap_only_for_tcp_client(self):
        """Test that config includes bootstrap_only for TCP client interfaces"""
        interfaces = [{
            'type': 'TCPClient',
            'name': 'Bootstrap Server',
            'target_host': '10.0.0.1',
            'target_port': 4242,
            'enabled': True,
            'bootstrap_only': True,
        }]

        result = self.wrapper._create_config_file(interfaces=interfaces)
        self.assertTrue(result)

        config_path = os.path.join(self.temp_dir, "config")
        with open(config_path, 'r') as f:
            config_content = f.read()

        self.assertIn("bootstrap_only = yes", config_content)


if __name__ == '__main__':
    unittest.main()
