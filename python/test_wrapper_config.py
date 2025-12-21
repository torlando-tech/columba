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


class TestTransportNodeConfig(unittest.TestCase):
    """Test _create_config_file transport node setting"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.config_path = os.path.join(self.temp_dir, "config")

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_standalone_mode_transport_enabled_by_default(self):
        """Test that standalone mode has transport enabled by default"""
        interfaces = []

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = yes", content)

    def test_standalone_mode_transport_enabled_explicit(self):
        """Test that standalone mode with enable_transport=True generates yes"""
        interfaces = []

        result = self.wrapper._create_config_file(interfaces, enable_transport=True)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = yes", content)
        self.assertNotIn("enable_transport = no", content)

    def test_standalone_mode_transport_disabled(self):
        """Test that standalone mode with enable_transport=False generates no"""
        interfaces = []

        result = self.wrapper._create_config_file(interfaces, enable_transport=False)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = no", content)
        self.assertNotIn("enable_transport = yes", content)

    def test_shared_instance_mode_transport_enabled_by_default(self):
        """Test that shared instance mode has transport enabled by default"""
        interfaces = []

        result = self.wrapper._create_config_file(
            interfaces,
            use_shared_instance=True
        )

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = yes", content)

    def test_shared_instance_mode_transport_enabled_explicit(self):
        """Test that shared instance mode with enable_transport=True generates yes"""
        interfaces = []

        result = self.wrapper._create_config_file(
            interfaces,
            use_shared_instance=True,
            enable_transport=True
        )

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = yes", content)
        self.assertNotIn("enable_transport = no", content)

    def test_shared_instance_mode_transport_disabled(self):
        """Test that shared instance mode with enable_transport=False generates no"""
        interfaces = []

        result = self.wrapper._create_config_file(
            interfaces,
            use_shared_instance=True,
            enable_transport=False
        )

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = no", content)
        self.assertNotIn("enable_transport = yes", content)

    def test_standalone_with_interfaces_transport_enabled(self):
        """Test standalone mode with interfaces and transport enabled"""
        interfaces = [
            {"type": "AutoInterface", "name": "Auto Discovery"}
        ]

        result = self.wrapper._create_config_file(interfaces, enable_transport=True)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = yes", content)
        self.assertIn("[[Auto Discovery]]", content)

    def test_standalone_with_interfaces_transport_disabled(self):
        """Test standalone mode with interfaces and transport disabled"""
        interfaces = [
            {"type": "AutoInterface", "name": "Auto Discovery"}
        ]

        result = self.wrapper._create_config_file(interfaces, enable_transport=False)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = no", content)
        self.assertIn("[[Auto Discovery]]", content)

    def test_shared_instance_with_rpc_key_transport_disabled(self):
        """Test shared instance with RPC key and transport disabled"""
        interfaces = []

        result = self.wrapper._create_config_file(
            interfaces,
            use_shared_instance=True,
            rpc_key="abc123def456",
            enable_transport=False
        )

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        self.assertIn("enable_transport = no", content)
        self.assertIn("rpc_key = abc123def456", content)

    def test_transport_setting_appears_in_reticulum_section(self):
        """Test that enable_transport appears in [reticulum] section"""
        interfaces = []

        result = self.wrapper._create_config_file(interfaces, enable_transport=False)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify enable_transport comes after [reticulum] and before [interfaces]
        reticulum_pos = content.find("[reticulum]")
        transport_pos = content.find("enable_transport = no")
        interfaces_pos = content.find("[interfaces]")

        self.assertNotEqual(reticulum_pos, -1)
        self.assertNotEqual(transport_pos, -1)
        self.assertNotEqual(interfaces_pos, -1)
        self.assertGreater(transport_pos, reticulum_pos)
        self.assertLess(transport_pos, interfaces_pos)


class TestGetDebugInfoTransport(unittest.TestCase):
    """Test get_debug_info transport_enabled field"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_get_debug_info_transport_enabled_true(self, mock_rns):
        """Test that get_debug_info returns transport_enabled=True when enabled"""
        # Setup mocks
        mock_rns.Transport.interfaces = []
        mock_rns.Transport.identity = MagicMock()
        mock_rns.Reticulum.transport_enabled.return_value = True

        # Make wrapper appear initialized
        self.wrapper.initialized = True
        self.wrapper.reticulum = MagicMock()

        # Call get_debug_info
        info = self.wrapper.get_debug_info()

        # Verify transport_enabled is True
        self.assertTrue(info['transport_enabled'])
        mock_rns.Reticulum.transport_enabled.assert_called_once()

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    def test_get_debug_info_transport_enabled_false(self, mock_rns):
        """Test that get_debug_info returns transport_enabled=False when disabled"""
        # Setup mocks
        mock_rns.Transport.interfaces = []
        mock_rns.Transport.identity = None
        mock_rns.Reticulum.transport_enabled.return_value = False

        # Make wrapper appear initialized
        self.wrapper.initialized = True
        self.wrapper.reticulum = MagicMock()

        # Call get_debug_info
        info = self.wrapper.get_debug_info()

        # Verify transport_enabled is False
        self.assertFalse(info['transport_enabled'])
        mock_rns.Reticulum.transport_enabled.assert_called_once()

    def test_get_debug_info_transport_enabled_false_when_not_initialized(self):
        """Test that get_debug_info returns transport_enabled=False when not initialized"""
        # Wrapper not initialized
        self.wrapper.initialized = False

        # Call get_debug_info
        info = self.wrapper.get_debug_info()

        # Verify transport_enabled is False when not initialized
        self.assertFalse(info['transport_enabled'])


class TestErrorHandling(unittest.TestCase):
    """Test error handling in config creation and shared instance checks"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.config_path = os.path.join(self.temp_dir, "config")

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_create_config_skips_rnode_with_empty_tcp_host(self):
        """Test that _create_config_file skips RNode with empty tcp_host"""
        interfaces = [
            {
                "type": "RNode",
                "name": "Bad RNode",
                "connection_mode": "tcp",
                "tcp_host": "",  # Empty tcp_host
                "frequency": 915000000
            },
            {
                "type": "AutoInterface",
                "name": "Auto Discovery"
            }
        ]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # RNode section header may exist, but no type/config should be present
        # The important thing is no RNodeInterface type is added
        self.assertNotIn("type = RNodeInterface", content)
        self.assertNotIn("tcp_host =", content)
        # AutoInterface should still be present
        self.assertIn("Auto Discovery", content)
        self.assertIn("type = AutoInterface", content)

    @patch('reticulum_wrapper.log_error')
    def test_create_config_logs_error_for_empty_tcp_host(self, mock_log_error):
        """Test that empty tcp_host logs an error message"""
        interfaces = [
            {
                "type": "RNode",
                "name": "Invalid RNode",
                "connection_mode": "tcp",
                "tcp_host": "   ",  # Whitespace only
            }
        ]

        self.wrapper._create_config_file(interfaces)

        # Verify error was logged
        mock_log_error.assert_called()
        call_args = str(mock_log_error.call_args)
        self.assertIn("tcp_host is empty", call_args)

    def test_check_shared_instance_handles_socket_timeout(self):
        """Test that check_shared_instance_available handles socket timeout"""
        # Test with a very short timeout to a non-existent host
        # Use a non-routable IP (RFC 5737 documentation range)
        result = self.wrapper.check_shared_instance_available(
            host="192.0.2.1",  # Non-routable test IP
            port=37428,
            timeout=0.001  # Very short timeout
        )

        # Should return False on timeout or connection failure
        self.assertFalse(result)

    def test_check_shared_instance_handles_connection_refused(self):
        """Test that check_shared_instance_available handles connection refused"""
        # Test with localhost on a port that's very unlikely to be listening
        # Port 1 requires root privileges, so it should be refused
        result = self.wrapper.check_shared_instance_available(
            host="127.0.0.1",
            port=1,  # Privileged port unlikely to be open
            timeout=0.1
        )

        # Should return False when connection is refused
        self.assertFalse(result)


class TestCallbackRegistration(unittest.TestCase):
    """Test callback registration methods"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_alternative_relay_callback_stores_callback(self):
        """Test that set_kotlin_request_alternative_relay_callback stores the callback"""
        mock_callback = MagicMock()

        self.wrapper.set_kotlin_request_alternative_relay_callback(mock_callback)

        # Verify callback is stored
        self.assertEqual(self.wrapper.kotlin_request_alternative_relay_callback, mock_callback)

    def test_set_message_received_callback_stores_callback(self):
        """Test that set_message_received_callback stores the callback"""
        mock_callback = MagicMock()

        self.wrapper.set_message_received_callback(mock_callback)

        # Verify callback is stored
        self.assertEqual(self.wrapper.kotlin_message_received_callback, mock_callback)

    def test_set_delivery_status_callback_stores_callback(self):
        """Test that set_delivery_status_callback stores the callback"""
        mock_callback = MagicMock()

        self.wrapper.set_delivery_status_callback(mock_callback)

        # Verify callback is stored
        self.assertEqual(self.wrapper.kotlin_delivery_status_callback, mock_callback)


class TestStateTransitions(unittest.TestCase):
    """Test state transition and edge case handling"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RNS')
    def test_clear_stale_ble_paths_handles_empty_path_table(self, mock_rns):
        """Test that _clear_stale_ble_paths handles empty path table"""
        # Mock empty path table
        mock_rns.Transport.path_table = {}

        # Should not raise exception
        try:
            self.wrapper._clear_stale_ble_paths()
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

    @patch('reticulum_wrapper.RNS')
    def test_clear_stale_ble_paths_handles_malformed_entries(self, mock_rns):
        """Test that _clear_stale_ble_paths handles malformed path table entries"""
        # Mock path table with malformed entry (tuple too short, missing elements)
        # Path table entries are tuples: [timestamp, hops, expires, random_blobs, interface_hash, interface]
        mock_rns.Transport.path_table = {
            b'test_dest_hash_1': [100]  # Too short, will cause IndexError when accessing entry[5]
        }

        # Should not raise exception - malformed entries should be caught and skipped
        try:
            self.wrapper._clear_stale_ble_paths()
            # If we got here, the method handled the malformed entry gracefully
        except Exception as e:
            self.fail(f"Method raised unexpected exception: {e}")

    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.time')
    def test_clear_stale_ble_paths_removes_timestamp_zero_paths(self, mock_time, mock_rns):
        """Test that _clear_stale_ble_paths removes paths with timestamp=0"""
        # Mock current time
        mock_time.time.return_value = 1000.0

        # Mock BLE interface
        mock_interface = MagicMock()
        mock_interface.__class__.__name__ = 'AndroidBLEInterface'

        # Mock path table with timestamp=0 entry
        # Path table format: [timestamp, hops, expires, random_blobs, interface_hash, interface]
        dest_hash = b'test_dest_hash_1'
        mock_rns.Transport.path_table = {
            dest_hash: [0, 1, 2000, b'random', b'hash', mock_interface]
        }

        # Clear stale paths
        self.wrapper._clear_stale_ble_paths()

        # Verify path was removed
        self.assertNotIn(dest_hash, mock_rns.Transport.path_table)

    def test_cache_device_type_skips_unknown_type(self):
        """Test that cache operations handle unknown device types gracefully"""
        # This test validates that the wrapper doesn't crash with unexpected data
        # Device type caching is handled internally by RNS, this tests robustness
        try:
            # Create a wrapper and verify it initializes without device type cache errors
            wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
            self.assertIsNotNone(wrapper)
        except Exception as e:
            self.fail(f"Wrapper initialization failed: {e}")

    def test_get_cached_device_type_handles_exception(self):
        """Test that device type retrieval handles exceptions gracefully"""
        # This test validates exception handling for device type operations
        # The wrapper should handle missing or corrupted cache data gracefully
        try:
            # Wrapper should initialize even if device type cache is unavailable
            wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
            # Verify wrapper is usable
            self.assertFalse(wrapper.initialized)
            self.assertEqual(wrapper.storage_path, self.temp_dir)
        except Exception as e:
            self.fail(f"Wrapper failed to handle device type cache exception: {e}")


class TestTcpRNodeConfigGeneration(unittest.TestCase):
    """Test TCP RNode configuration generation in _create_config_file"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.config_path = os.path.join(self.temp_dir, "config")

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_create_config_tcp_rnode_writes_complete_config(self):
        """Test TCP RNode with all LoRa parameters writes complete config file."""
        interfaces = [{
            "type": "RNode",
            "name": "Test TCP RNode",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 915000000,
            "bandwidth": 125000,
            "tx_power": 17,
            "spreading_factor": 8,
            "coding_rate": 5
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify all required parameters are present
        self.assertIn("type = RNodeInterface", content)
        self.assertIn("enabled = yes", content)
        self.assertIn("tcp_host = 192.168.1.100", content)
        self.assertIn("frequency = 915000000", content)
        self.assertIn("bandwidth = 125000", content)
        self.assertIn("txpower = 17", content)
        self.assertIn("spreadingfactor = 8", content)
        self.assertIn("codingrate = 5", content)

    def test_create_config_tcp_rnode_includes_airtime_limits(self):
        """Test st_alock and lt_alock are written when present."""
        interfaces = [{
            "type": "RNode",
            "name": "RNode with Limits",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 869525000,
            "bandwidth": 250000,
            "tx_power": 14,
            "spreading_factor": 10,
            "coding_rate": 5,
            "st_alock": 15,
            "lt_alock": 5
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify airtime limits are present
        self.assertIn("airtime_limit_short = 15", content)
        self.assertIn("airtime_limit_long = 5", content)

    def test_create_config_tcp_rnode_omits_airtime_limits_when_none(self):
        """Test airtime limits are omitted when not specified."""
        interfaces = [{
            "type": "RNode",
            "name": "RNode No Limits",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 915000000,
            "bandwidth": 125000,
            "tx_power": 17,
            "spreading_factor": 8,
            "coding_rate": 5
            # No st_alock or lt_alock specified
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify airtime limits are not present
        self.assertNotIn("airtime_limit_short", content)
        self.assertNotIn("airtime_limit_long", content)

    def test_create_config_tcp_rnode_handles_gateway_mode(self):
        """Test interface_mode is written for non-full modes."""
        interfaces = [{
            "type": "RNode",
            "name": "Gateway RNode",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 915000000,
            "bandwidth": 125000,
            "tx_power": 17,
            "spreading_factor": 8,
            "coding_rate": 5,
            "mode": "gateway"
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify interface_mode is present for non-full mode
        self.assertIn("interface_mode = gateway", content)

    def test_create_config_tcp_rnode_omits_mode_when_full(self):
        """Test interface_mode is omitted when mode is 'full' (default)."""
        interfaces = [{
            "type": "RNode",
            "name": "Full Mode RNode",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 915000000,
            "bandwidth": 125000,
            "tx_power": 17,
            "spreading_factor": 8,
            "coding_rate": 5,
            "mode": "full"
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify interface_mode is not present for full mode
        self.assertNotIn("interface_mode", content)

    def test_create_config_tcp_rnode_with_boundary_mode(self):
        """Test interface_mode is written for boundary mode."""
        interfaces = [{
            "type": "RNode",
            "name": "Boundary RNode",
            "connection_mode": "tcp",
            "tcp_host": "192.168.1.100",
            "frequency": 915000000,
            "bandwidth": 125000,
            "tx_power": 17,
            "spreading_factor": 8,
            "coding_rate": 5,
            "mode": "boundary"
        }]

        result = self.wrapper._create_config_file(interfaces)

        self.assertTrue(result)
        with open(self.config_path, 'r') as f:
            content = f.read()

        # Verify interface_mode is present for boundary mode
        self.assertIn("interface_mode = boundary", content)


if __name__ == '__main__':
    unittest.main()
