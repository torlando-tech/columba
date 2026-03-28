"""Tests for format_interface_name() in interface_lookup.py."""
import unittest
from unittest.mock import Mock

from interface_lookup import format_interface_name


class TestFormatInterfaceName(unittest.TestCase):
    """Tests for the format_interface_name function."""

    def test_none_returns_none(self):
        self.assertIsNone(format_interface_name(None))

    def test_user_configured_name(self):
        iface = Mock()
        type(iface).__name__ = "TCPInterface"
        iface.name = "Sideband Server/192.168.1.100:4965"
        self.assertEqual("TCPInterface[Sideband Server/192.168.1.100:4965]", format_interface_name(iface))

    def test_name_equals_classname_with_target_ip(self):
        """Auto-discovered interface: name == class name, but target_ip is set."""
        iface = Mock()
        type(iface).__name__ = "BackboneClientInterface"
        iface.name = "BackboneClientInterface"
        iface.target_ip = "10.0.4.63"
        iface.target_port = 4243
        self.assertEqual("BackboneClientInterface[10.0.4.63:4243]", format_interface_name(iface))

    def test_name_equals_classname_with_target_host(self):
        """Auto-discovered interface with hostname instead of IP."""
        iface = Mock()
        type(iface).__name__ = "TCPClientInterface"
        iface.name = "TCPClientInterface"
        iface.target_ip = None
        iface.target_host = "rns.example.com"
        iface.target_port = 4242
        self.assertEqual("TCPClientInterface[rns.example.com:4242]", format_interface_name(iface))

    def test_name_equals_classname_target_ip_no_port(self):
        """Target IP without port."""
        iface = Mock()
        type(iface).__name__ = "TCPClientInterface"
        iface.name = "TCPClientInterface"
        iface.target_ip = "10.0.0.1"
        iface.target_port = None
        self.assertEqual("TCPClientInterface[10.0.0.1]", format_interface_name(iface))

    def test_name_equals_classname_no_target_with_str(self):
        """Falls back to str() when no target attributes, str() has brackets."""
        iface = Mock()
        type(iface).__name__ = "AutoInterface"
        iface.name = "AutoInterface"
        iface.target_ip = None
        iface.target_host = None
        iface.__str__ = Mock(return_value="AutoInterface[Local/fe80::1]")
        self.assertEqual("AutoInterface[Local/fe80::1]", format_interface_name(iface))

    def test_name_equals_classname_no_target_no_brackets_in_str(self):
        """Falls back to class name when str() has no brackets."""
        iface = Mock()
        type(iface).__name__ = "AutoInterface"
        iface.name = "AutoInterface"
        iface.target_ip = None
        iface.target_host = None
        iface.__str__ = Mock(return_value="AutoInterface")
        self.assertEqual("AutoInterface", format_interface_name(iface))


if __name__ == "__main__":
    unittest.main()
