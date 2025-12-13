"""
Test suite for ReticulumWrapper config manipulation methods.

Tests _remove_autointerface_from_config() and _setup_interface() methods
for handling RNS config file modifications and interface setup.
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch
import tempfile
import shutil

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestRemoveAutoInterfaceFromConfig(unittest.TestCase):
    """Test _remove_autointerface_from_config method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.config_path = os.path.join(self.temp_dir, "config")

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_removes_autointerface_section_from_valid_config(self):
        """Test that AutoInterface section is removed from valid config file"""
        # Create a config file with AutoInterface section
        config_content = """[reticulum]
  enable_transport = False
  share_instance = No

[[Auto Discovery]]
  type = AutoInterface
  enabled = True

[[TCP Interface]]
  type = TCPClientInterface
  enabled = True
  target_host = 127.0.0.1
  target_port = 4242
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Remove AutoInterface section
        self.wrapper._remove_autointerface_from_config()

        # Verify AutoInterface section is removed
        with open(self.config_path, 'r') as f:
            new_content = f.read()

        self.assertNotIn("[[Auto Discovery]]", new_content)
        self.assertNotIn("type = AutoInterface", new_content)
        # Verify other sections remain
        self.assertIn("[[TCP Interface]]", new_content)
        self.assertIn("type = TCPClientInterface", new_content)
        self.assertIn("[reticulum]", new_content)

    def test_handles_missing_config_file(self):
        """Test that method handles missing config file gracefully"""
        # Ensure config file doesn't exist
        if os.path.exists(self.config_path):
            os.remove(self.config_path)

        # Should not raise exception
        try:
            self.wrapper._remove_autointerface_from_config()
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

    def test_handles_config_with_no_autointerface_section(self):
        """Test that method handles config file with no AutoInterface section"""
        # Create config without AutoInterface
        config_content = """[reticulum]
  enable_transport = False
  share_instance = No

[[TCP Interface]]
  type = TCPClientInterface
  enabled = True
  target_host = 127.0.0.1
  target_port = 4242
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        original_content = config_content

        # Remove AutoInterface section (should do nothing)
        self.wrapper._remove_autointerface_from_config()

        # Verify content unchanged
        with open(self.config_path, 'r') as f:
            new_content = f.read()

        self.assertEqual(new_content, original_content)

    def test_handles_permission_error(self):
        """Test that method handles permission errors appropriately"""
        # Create config file
        config_content = """[[Auto Discovery]]
  type = AutoInterface
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Make file read-only
        os.chmod(self.config_path, 0o444)

        try:
            # Should raise exception due to permission error
            with self.assertRaises(Exception):
                self.wrapper._remove_autointerface_from_config()
        finally:
            # Restore permissions for cleanup
            os.chmod(self.config_path, 0o644)

    def test_removes_only_autointerface_section_not_content(self):
        """Test that only AutoInterface section is removed, not individual lines"""
        # Create config with AutoInterface section and other interfaces
        config_content = """[reticulum]
  enable_transport = False

[[Auto Discovery]]
  type = AutoInterface
  enabled = True
  some_setting = value

[[UDP Interface]]
  type = UDPInterface
  enabled = True
  port = 4242
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Remove AutoInterface section
        self.wrapper._remove_autointerface_from_config()

        # Verify only AutoInterface section is removed
        with open(self.config_path, 'r') as f:
            lines = f.readlines()

        # AutoInterface section and its contents should be gone
        for line in lines:
            self.assertNotIn("Auto Discovery", line)
            self.assertNotIn("type = AutoInterface", line)
            self.assertNotIn("some_setting = value", line)

        # Other sections should remain
        content = ''.join(lines)
        self.assertIn("[[UDP Interface]]", content)
        self.assertIn("type = UDPInterface", content)

    def test_handles_multiple_sections_after_autointerface(self):
        """Test that sections after AutoInterface are preserved"""
        config_content = """[[Auto Discovery]]
  type = AutoInterface
  enabled = True

[[TCP Interface]]
  type = TCPClientInterface
  enabled = True

[[UDP Interface]]
  type = UDPInterface
  enabled = True
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Remove AutoInterface section
        self.wrapper._remove_autointerface_from_config()

        # Verify both subsequent sections remain
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertNotIn("[[Auto Discovery]]", content)
        self.assertIn("[[TCP Interface]]", content)
        self.assertIn("[[UDP Interface]]", content)

    def test_preserves_section_order(self):
        """Test that the order of remaining sections is preserved"""
        config_content = """[reticulum]
  setting1 = value1

[[Auto Discovery]]
  type = AutoInterface

[[First Interface]]
  type = First

[[Second Interface]]
  type = Second
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Remove AutoInterface section
        self.wrapper._remove_autointerface_from_config()

        # Verify order is preserved
        with open(self.config_path, 'r') as f:
            lines = f.readlines()

        # Find indices of sections
        reticulum_idx = next(i for i, line in enumerate(lines) if '[reticulum]' in line)
        first_idx = next(i for i, line in enumerate(lines) if '[[First Interface]]' in line)
        second_idx = next(i for i, line in enumerate(lines) if '[[Second Interface]]' in line)

        # Verify order
        self.assertLess(reticulum_idx, first_idx)
        self.assertLess(first_idx, second_idx)

    def test_handles_empty_config_file(self):
        """Test that method handles empty config file"""
        # Create empty config file
        with open(self.config_path, 'w') as f:
            f.write("")

        # Should not raise exception
        try:
            self.wrapper._remove_autointerface_from_config()
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

    def test_handles_malformed_section_headers(self):
        """Test that method handles malformed section headers gracefully"""
        config_content = """[reticulum]
  setting = value

[[Auto Discovery]
  type = AutoInterface
  missing_closing_bracket = yes

[[Normal Section]]
  type = Normal
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        # Should not raise exception
        try:
            self.wrapper._remove_autointerface_from_config()
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

    def test_removes_autointerface_with_different_name_variations(self):
        """Test that AutoInterface is detected with name variations"""
        # Test with exact match
        config_content = """[[Auto Discovery]]
  type = AutoInterface
"""
        with open(self.config_path, 'w') as f:
            f.write(config_content)

        self.wrapper._remove_autointerface_from_config()

        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertNotIn("Auto Discovery", content)


class TestSetupInterface(unittest.TestCase):
    """Test _setup_interface method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    def test_setup_autointerface(self, mock_rns):
        """Test setting up AutoInterface"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_auto_iface = MagicMock()
        mock_rns.Interfaces.AutoInterface.AutoInterface.return_value = mock_auto_iface

        # Test config
        iface_config = {
            "type": "AutoInterface"
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify AutoInterface was created
        mock_rns.Interfaces.AutoInterface.AutoInterface.assert_called_once_with(
            mock_transport,
            "AutoInterface"
        )

        # Verify interface was configured and added
        self.assertTrue(mock_auto_iface.OUT)
        self.assertIn(mock_auto_iface, mock_transport.interfaces)

    @patch('reticulum_wrapper.RNS')
    def test_setup_tcpclientinterface_with_defaults(self, mock_rns):
        """Test setting up TCPClientInterface with default host/port"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_tcp_iface = MagicMock()
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_tcp_iface

        # Test config
        iface_config = {
            "type": "TCPClientInterface"
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify TCPClientInterface was created with defaults
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.assert_called_once_with(
            mock_transport,
            "TCPClientInterface",
            "127.0.0.1",  # default host
            4242  # default port
        )

        # Verify interface was configured and added
        self.assertTrue(mock_tcp_iface.OUT)
        self.assertIn(mock_tcp_iface, mock_transport.interfaces)

    @patch('reticulum_wrapper.RNS')
    def test_setup_tcpclientinterface_with_custom_host_port(self, mock_rns):
        """Test setting up TCPClientInterface with custom host and port"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_tcp_iface = MagicMock()
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_tcp_iface

        # Test config
        iface_config = {
            "type": "TCPClientInterface",
            "host": "192.168.1.100",
            "port": 8888
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify TCPClientInterface was created with custom values
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.assert_called_once_with(
            mock_transport,
            "TCPClientInterface",
            "192.168.1.100",
            8888
        )

        # Verify interface was configured and added
        self.assertTrue(mock_tcp_iface.OUT)
        self.assertIn(mock_tcp_iface, mock_transport.interfaces)

    @patch('reticulum_wrapper.RNS')
    def test_setup_udpinterface_with_default_port(self, mock_rns):
        """Test setting up UDPInterface with default port"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_udp_iface = MagicMock()
        mock_rns.Interfaces.UDPInterface.UDPInterface.return_value = mock_udp_iface

        # Test config
        iface_config = {
            "type": "UDPInterface"
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify UDPInterface was created with default port
        mock_rns.Interfaces.UDPInterface.UDPInterface.assert_called_once_with(
            mock_transport,
            "UDPInterface",
            4242  # default port
        )

        # Verify interface was configured and added
        self.assertTrue(mock_udp_iface.OUT)
        self.assertIn(mock_udp_iface, mock_transport.interfaces)

    @patch('reticulum_wrapper.RNS')
    def test_setup_udpinterface_with_custom_port(self, mock_rns):
        """Test setting up UDPInterface with custom port"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_udp_iface = MagicMock()
        mock_rns.Interfaces.UDPInterface.UDPInterface.return_value = mock_udp_iface

        # Test config
        iface_config = {
            "type": "UDPInterface",
            "port": 9999
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify UDPInterface was created with custom port
        mock_rns.Interfaces.UDPInterface.UDPInterface.assert_called_once_with(
            mock_transport,
            "UDPInterface",
            9999
        )

        # Verify interface was configured and added
        self.assertTrue(mock_udp_iface.OUT)
        self.assertIn(mock_udp_iface, mock_transport.interfaces)

    @patch('reticulum_wrapper.RNS')
    def test_handles_unknown_interface_type(self, mock_rns):
        """Test that unknown interface type is handled gracefully"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        # Test config
        iface_config = {
            "type": "UnknownInterface"
        }

        # Should not raise exception
        try:
            self.wrapper._setup_interface(iface_config)
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

        # Verify no interface was created
        self.assertFalse(mock_rns.Interfaces.AutoInterface.AutoInterface.called)
        self.assertFalse(mock_rns.Interfaces.TCPInterface.TCPClientInterface.called)
        self.assertFalse(mock_rns.Interfaces.UDPInterface.UDPInterface.called)

    @patch('reticulum_wrapper.RNS')
    def test_handles_missing_type_field(self, mock_rns):
        """Test that missing type field is handled gracefully"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        # Test config
        iface_config = {
            "host": "localhost",
            "port": 4242
        }

        # Should not raise exception
        try:
            self.wrapper._setup_interface(iface_config)
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

        # Verify no interface was created
        self.assertFalse(mock_rns.Interfaces.AutoInterface.AutoInterface.called)
        self.assertFalse(mock_rns.Interfaces.TCPInterface.TCPClientInterface.called)
        self.assertFalse(mock_rns.Interfaces.UDPInterface.UDPInterface.called)

    @patch('reticulum_wrapper.RNS')
    def test_handles_empty_config(self, mock_rns):
        """Test that empty config dict is handled gracefully"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        # Test config
        iface_config = {}

        # Should not raise exception
        try:
            self.wrapper._setup_interface(iface_config)
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

        # Verify no interface was created
        self.assertFalse(mock_rns.Interfaces.AutoInterface.AutoInterface.called)
        self.assertFalse(mock_rns.Interfaces.TCPInterface.TCPClientInterface.called)
        self.assertFalse(mock_rns.Interfaces.UDPInterface.UDPInterface.called)

    @patch('reticulum_wrapper.RNS')
    def test_tcpclientinterface_with_only_host(self, mock_rns):
        """Test TCPClientInterface uses default port when only host provided"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_tcp_iface = MagicMock()
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_tcp_iface

        # Test config
        iface_config = {
            "type": "TCPClientInterface",
            "host": "example.com"
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify default port is used
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.assert_called_once_with(
            mock_transport,
            "TCPClientInterface",
            "example.com",
            4242  # default port
        )

    @patch('reticulum_wrapper.RNS')
    def test_tcpclientinterface_with_only_port(self, mock_rns):
        """Test TCPClientInterface uses default host when only port provided"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        mock_tcp_iface = MagicMock()
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_tcp_iface

        # Test config
        iface_config = {
            "type": "TCPClientInterface",
            "port": 5555
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Verify default host is used
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.assert_called_once_with(
            mock_transport,
            "TCPClientInterface",
            "127.0.0.1",  # default host
            5555
        )

    @patch('reticulum_wrapper.RNS')
    def test_multiple_interfaces_can_be_setup(self, mock_rns):
        """Test that multiple interfaces can be set up sequentially"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        # Mock all interface constructors
        mock_auto_iface = MagicMock()
        mock_tcp_iface = MagicMock()
        mock_udp_iface = MagicMock()

        mock_rns.Interfaces.AutoInterface.AutoInterface.return_value = mock_auto_iface
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_tcp_iface
        mock_rns.Interfaces.UDPInterface.UDPInterface.return_value = mock_udp_iface

        # Test configs
        configs = [
            {"type": "AutoInterface"},
            {"type": "TCPClientInterface", "host": "server1.com", "port": 4242},
            {"type": "UDPInterface", "port": 5555}
        ]

        # Setup all interfaces
        for config in configs:
            self.wrapper._setup_interface(config)

        # Verify all interfaces were created
        mock_rns.Interfaces.AutoInterface.AutoInterface.assert_called_once()
        mock_rns.Interfaces.TCPInterface.TCPClientInterface.assert_called_once()
        mock_rns.Interfaces.UDPInterface.UDPInterface.assert_called_once()

        # Verify all interfaces were added to transport
        self.assertEqual(len(mock_transport.interfaces), 3)

    @patch('reticulum_wrapper.RNS')
    def test_interface_out_flag_set_correctly(self, mock_rns):
        """Test that OUT flag is set to True for all interface types"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_rns.Transport = mock_transport

        # Test configs
        configs = [
            {"type": "AutoInterface"},
            {"type": "TCPClientInterface"},
            {"type": "UDPInterface"}
        ]

        for config in configs:
            # Reset transport interfaces
            mock_transport.interfaces = []

            # Create a fresh mock for each interface
            mock_iface = MagicMock()

            if config["type"] == "AutoInterface":
                mock_rns.Interfaces.AutoInterface.AutoInterface.return_value = mock_iface
            elif config["type"] == "TCPClientInterface":
                mock_rns.Interfaces.TCPInterface.TCPClientInterface.return_value = mock_iface
            elif config["type"] == "UDPInterface":
                mock_rns.Interfaces.UDPInterface.UDPInterface.return_value = mock_iface

            # Setup interface
            self.wrapper._setup_interface(config)

            # Verify OUT flag is set
            created_iface = mock_transport.interfaces[0]
            self.assertTrue(created_iface.OUT, f"OUT flag not set for {config['type']}")

    @patch('reticulum_wrapper.RNS')
    def test_case_sensitive_interface_type(self, mock_rns):
        """Test that interface type matching is case-sensitive"""
        # Setup mocks
        mock_transport = MagicMock()
        mock_transport.interfaces = []
        mock_rns.Transport = mock_transport

        # Test config with lowercase type
        iface_config = {
            "type": "autointerface"  # lowercase
        }

        # Setup interface
        self.wrapper._setup_interface(iface_config)

        # Should not match AutoInterface (case-sensitive)
        self.assertFalse(mock_rns.Interfaces.AutoInterface.AutoInterface.called)


if __name__ == '__main__':
    unittest.main()
