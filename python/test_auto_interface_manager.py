"""
Test suite for auto_interface_manager.py — hot-adding network interfaces to AutoInterface.

Tests the ability to dynamically detect and add new network interfaces to an
existing RNS AutoInterface at runtime, which is needed when WiFi connects after
the app starts without it.
"""

import sys
import os
import json
import unittest
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Ensure RNS submodule mocks exist in sys.modules (conftest only mocks RNS top-level).
# _find_auto_interface and hot_add_interfaces do `from RNS.Interfaces.AutoInterface import ...`
# which needs these intermediate modules to be importable.
_mock_auto_interface_module = MagicMock()


class _SentinelAutoInterface:
    """Sentinel class so isinstance() checks work in tests."""
    pass


_mock_auto_interface_module.AutoInterface = _SentinelAutoInterface

if 'RNS.Interfaces' not in sys.modules:
    sys.modules['RNS.Interfaces'] = MagicMock()
if 'RNS.Interfaces.AutoInterface' not in sys.modules:
    sys.modules['RNS.Interfaces.AutoInterface'] = _mock_auto_interface_module
if 'RNS.Interfaces.netinfo' not in sys.modules:
    sys.modules['RNS.Interfaces.netinfo'] = MagicMock()

# Mock logging_utils (Android-only module)
if 'logging_utils' not in sys.modules:
    sys.modules['logging_utils'] = MagicMock()

import auto_interface_manager


class TestFindAutoInterface(unittest.TestCase):
    """Test _find_auto_interface discovery logic."""

    def test_returns_none_when_no_interfaces(self):
        """No interfaces in Transport means no AutoInterface."""
        rns = MagicMock()
        rns.Transport.interfaces = []

        result = auto_interface_manager._find_auto_interface(rns)

        self.assertIsNone(result)

    def test_returns_none_when_no_auto_interface(self):
        """Non-AutoInterface interfaces should be skipped."""
        rns = MagicMock()
        # Regular MagicMock is NOT an instance of _SentinelAutoInterface
        other_iface = MagicMock()
        rns.Transport.interfaces = [other_iface]

        result = auto_interface_manager._find_auto_interface(rns)

        self.assertIsNone(result)

    def test_returns_first_auto_interface(self):
        """Should return the first AutoInterface found."""
        rns = MagicMock()
        # Create an instance of the sentinel class so isinstance() matches
        auto_iface = _SentinelAutoInterface()
        rns.Transport.interfaces = [auto_iface]

        result = auto_interface_manager._find_auto_interface(rns)

        self.assertEqual(result, auto_iface)

    def test_skips_non_auto_returns_auto(self):
        """Should skip non-AutoInterface and return the AutoInterface."""
        rns = MagicMock()
        other = MagicMock()
        auto_iface = _SentinelAutoInterface()
        rns.Transport.interfaces = [other, auto_iface]

        result = auto_interface_manager._find_auto_interface(rns)

        self.assertEqual(result, auto_iface)


class TestScanNewInterfaces(unittest.TestCase):
    """Test _scan_new_interfaces detection of unadopted interfaces."""

    def _make_auto_iface(self, adopted=None, ignored=None, allowed=None):
        """Create a mock AutoInterface with configurable state."""
        auto_iface = MagicMock()
        auto_iface.adopted_interfaces = adopted or {}
        auto_iface.ignored_interfaces = ignored or []
        auto_iface.allowed_interfaces = allowed or []
        return auto_iface

    def _make_auto_cls(self, android_ignore=None, all_ignore=None):
        """Create a mock AutoInterface class with ignore lists."""
        auto_cls = MagicMock()
        auto_cls.ANDROID_IGNORE_IFS = android_ignore or []
        auto_cls.ALL_IGNORE_IFS = all_ignore or []
        return auto_cls

    def test_returns_empty_when_no_new_interfaces(self):
        """Already-adopted interfaces should be skipped."""
        auto_iface = self._make_auto_iface(adopted={"wlan0": "fe80::1"})
        auto_iface.list_interfaces.return_value = ["wlan0"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_skips_android_ignored_interfaces(self):
        """Android-specific ignored interfaces (rmnet, etc.) should be skipped."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["rmnet0"]
        auto_cls = self._make_auto_cls(android_ignore=["rmnet0"])
        netinfo = MagicMock()

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_skips_user_ignored_interfaces(self):
        """User-configured ignored interfaces should be skipped."""
        auto_iface = self._make_auto_iface(ignored=["eth0"])
        auto_iface.list_interfaces.return_value = ["eth0"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_skips_all_ignore_interfaces(self):
        """Globally ignored interfaces (lo, etc.) should be skipped."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["lo"]
        auto_cls = self._make_auto_cls(all_ignore=["lo"])
        netinfo = MagicMock()

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_skips_interfaces_not_in_allowed_list(self):
        """When allowed_interfaces is set, skip interfaces not in the list."""
        auto_iface = self._make_auto_iface(allowed=["wlan0"])
        auto_iface.list_interfaces.return_value = ["eth0"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_detects_new_interface_with_link_local_address(self):
        """New interface with fe80: link-local address should be detected."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["wlan0"]
        auto_iface.descope_linklocal.return_value = "fe80::abcd"
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()
        netinfo.AF_INET6 = 10

        auto_iface.list_addresses.return_value = {
            10: [{"addr": "fe80::abcd%wlan0"}]
        }

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {"wlan0": "fe80::abcd"})

    def test_skips_interface_without_ipv6(self):
        """Interface without IPv6 addresses should be skipped."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["wlan0"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()
        netinfo.AF_INET6 = 10

        auto_iface.list_addresses.return_value = {2: [{"addr": "192.168.1.1"}]}

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_skips_interface_without_link_local(self):
        """Interface with IPv6 but no fe80: link-local should be skipped."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["wlan0"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()
        netinfo.AF_INET6 = 10

        auto_iface.list_addresses.return_value = {
            10: [{"addr": "2001:db8::1"}]
        }

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(result, {})

    def test_detects_multiple_new_interfaces(self):
        """Multiple new interfaces should all be detected."""
        auto_iface = self._make_auto_iface()
        auto_iface.list_interfaces.return_value = ["wlan0", "wlan1"]
        auto_cls = self._make_auto_cls()
        netinfo = MagicMock()
        netinfo.AF_INET6 = 10

        def mock_addresses(ifname):
            addrs = {
                "wlan0": {10: [{"addr": "fe80::1%wlan0"}]},
                "wlan1": {10: [{"addr": "fe80::2%wlan1"}]},
            }
            return addrs.get(ifname, {})

        auto_iface.list_addresses.side_effect = mock_addresses
        auto_iface.descope_linklocal.side_effect = lambda a: a.split("%")[0]

        result = auto_interface_manager._scan_new_interfaces(auto_iface, auto_cls, netinfo)

        self.assertEqual(len(result), 2)
        self.assertIn("wlan0", result)
        self.assertIn("wlan1", result)


class TestHotAddInterfaces(unittest.TestCase):
    """Test the top-level hot_add_interfaces orchestration."""

    @patch('auto_interface_manager._scan_new_interfaces')
    @patch('auto_interface_manager._find_auto_interface')
    def test_returns_error_when_no_auto_interface(self, mock_find, mock_scan):
        """Should return error when no AutoInterface exists in Transport."""
        mock_find.return_value = None

        result = json.loads(auto_interface_manager.hot_add_interfaces())

        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "no AutoInterface in Transport")

    @patch('auto_interface_manager._scan_new_interfaces')
    @patch('auto_interface_manager._find_auto_interface')
    def test_returns_no_new_interfaces_when_none_found(self, mock_find, mock_scan):
        """Should return success with no_new_interfaces when scan finds nothing."""
        mock_find.return_value = MagicMock()
        mock_scan.return_value = {}

        result = json.loads(auto_interface_manager.hot_add_interfaces())

        self.assertTrue(result["success"])
        self.assertEqual(result["action"], "no_new_interfaces")

    @patch('auto_interface_manager._add_interface')
    @patch('auto_interface_manager._scan_new_interfaces')
    @patch('auto_interface_manager._find_auto_interface')
    def test_adds_new_interfaces_and_marks_active(self, mock_find, mock_scan, mock_add):
        """Should add detected interfaces and mark AutoInterface as active."""
        auto_iface = MagicMock()
        auto_iface.receives = False
        auto_iface.OUT = False
        auto_iface.carrier_changed = False
        mock_find.return_value = auto_iface
        mock_scan.return_value = {"wlan0": "fe80::1"}

        result = json.loads(auto_interface_manager.hot_add_interfaces())

        self.assertTrue(result["success"])
        self.assertEqual(result["action"], "added_interfaces")
        self.assertEqual(result["count"], 1)
        mock_add.assert_called_once()
        self.assertTrue(auto_iface.receives)
        self.assertTrue(auto_iface.OUT)
        self.assertTrue(auto_iface.carrier_changed)

    @patch('auto_interface_manager._add_interface')
    @patch('auto_interface_manager._scan_new_interfaces')
    @patch('auto_interface_manager._find_auto_interface')
    def test_handles_add_interface_failure_gracefully(self, mock_find, mock_scan, mock_add):
        """Should continue and report count even if some interfaces fail to add."""
        auto_iface = MagicMock()
        auto_iface.receives = True
        auto_iface.OUT = True
        mock_find.return_value = auto_iface
        mock_scan.return_value = {"wlan0": "fe80::1", "wlan1": "fe80::2"}

        # First call succeeds, second throws
        mock_add.side_effect = [None, Exception("Socket error")]

        result = json.loads(auto_interface_manager.hot_add_interfaces())

        self.assertTrue(result["success"])
        self.assertEqual(result["count"], 1)

    @patch('auto_interface_manager._add_interface')
    @patch('auto_interface_manager._scan_new_interfaces')
    @patch('auto_interface_manager._find_auto_interface')
    def test_does_not_mark_active_when_no_interfaces_added(self, mock_find, mock_scan, mock_add):
        """When all adds fail, receives/OUT should not be changed."""
        auto_iface = MagicMock()
        auto_iface.receives = False
        auto_iface.OUT = False
        auto_iface.carrier_changed = False
        mock_find.return_value = auto_iface
        mock_scan.return_value = {"wlan0": "fe80::1"}

        mock_add.side_effect = Exception("Socket error")

        result = json.loads(auto_interface_manager.hot_add_interfaces())

        self.assertTrue(result["success"])
        self.assertEqual(result["count"], 0)
        # Should NOT mark active when nothing was added
        self.assertFalse(auto_iface.receives)
        self.assertFalse(auto_iface.OUT)
        self.assertFalse(auto_iface.carrier_changed)


class TestAddInterface(unittest.TestCase):
    """Test _add_interface socket and thread setup."""

    def _make_socket_mocks(self, mock_socket):
        """Configure socket module mock with all needed constants."""
        mock_sock = MagicMock()
        mock_socket.socket.return_value = mock_sock
        mock_socket.AF_INET6 = 10
        mock_socket.SOCK_DGRAM = 2
        mock_socket.SOL_SOCKET = 1
        mock_socket.SO_REUSEADDR = 2
        mock_socket.SO_REUSEPORT = 15
        mock_socket.IPPROTO_IPV6 = 41
        mock_socket.IPV6_MULTICAST_IF = 17
        mock_socket.IPV6_JOIN_GROUP = 20
        mock_socket.getaddrinfo.return_value = [(10, 2, 0, '', ('::1', 29716, 0, 0))]
        mock_socket.inet_pton.return_value = b'\x00' * 16
        return mock_sock

    def _make_auto_iface(self):
        """Create a mock AutoInterface for _add_interface tests."""
        auto_iface = MagicMock()
        auto_iface.link_local_addresses = []
        auto_iface.adopted_interfaces = {}
        auto_iface.multicast_echoes = {}
        auto_iface.interface_name_to_index.return_value = 42
        auto_iface.discovery_scope = 0
        auto_iface.interface_servers = {}
        return auto_iface

    @patch('auto_interface_manager.socketserver')
    @patch('auto_interface_manager.threading')
    @patch('auto_interface_manager.socket')
    def test_registers_interface_in_adopted(self, mock_socket, mock_threading, mock_ss):
        """Should register the interface in AutoInterface state dictionaries."""
        self._make_socket_mocks(mock_socket)
        auto_iface = self._make_auto_iface()
        auto_cls = MagicMock()
        auto_cls.SCOPE_LINK = 0

        mock_threading.Thread.return_value = MagicMock()
        mock_ss.UDPServer.return_value = MagicMock()

        auto_interface_manager._add_interface(auto_iface, auto_cls, "wlan0", "fe80::1")

        self.assertIn("fe80::1", auto_iface.link_local_addresses)
        self.assertEqual(auto_iface.adopted_interfaces["wlan0"], "fe80::1")
        self.assertIn("wlan0", auto_iface.multicast_echoes)

    @patch('auto_interface_manager.socketserver')
    @patch('auto_interface_manager.threading')
    @patch('auto_interface_manager.socket')
    def test_starts_three_threads(self, mock_socket, mock_threading, mock_ss):
        """Should start 2 discovery threads + 1 UDP server thread = 3 total."""
        self._make_socket_mocks(mock_socket)
        auto_iface = self._make_auto_iface()
        auto_cls = MagicMock()
        auto_cls.SCOPE_LINK = 0

        mock_thread = MagicMock()
        mock_threading.Thread.return_value = mock_thread
        mock_ss.UDPServer.return_value = MagicMock()

        auto_interface_manager._add_interface(auto_iface, auto_cls, "wlan0", "fe80::1")

        self.assertEqual(mock_thread.start.call_count, 3)

    @patch('auto_interface_manager.socketserver')
    @patch('auto_interface_manager.threading')
    @patch('auto_interface_manager.socket')
    def test_creates_and_registers_udp_server(self, mock_socket, mock_threading, mock_ss):
        """Should create UDP data server and register in interface_servers."""
        self._make_socket_mocks(mock_socket)
        auto_iface = self._make_auto_iface()
        auto_cls = MagicMock()
        auto_cls.SCOPE_LINK = 0

        mock_threading.Thread.return_value = MagicMock()
        mock_server = MagicMock()
        mock_ss.UDPServer.return_value = mock_server

        auto_interface_manager._add_interface(auto_iface, auto_cls, "wlan0", "fe80::1")

        self.assertEqual(auto_iface.interface_servers["wlan0"], mock_server)
        mock_ss.UDPServer.assert_called_once()

    @patch('auto_interface_manager.socketserver')
    @patch('auto_interface_manager.threading')
    @patch('auto_interface_manager.socket')
    def test_joins_multicast_group(self, mock_socket, mock_threading, mock_ss):
        """Should join the IPv6 multicast group on the new interface."""
        mock_sock = self._make_socket_mocks(mock_socket)
        auto_iface = self._make_auto_iface()
        auto_cls = MagicMock()
        auto_cls.SCOPE_LINK = 0

        mock_threading.Thread.return_value = MagicMock()
        mock_ss.UDPServer.return_value = MagicMock()

        auto_interface_manager._add_interface(auto_iface, auto_cls, "wlan0", "fe80::1")

        # The multicast discovery socket should have IPV6_JOIN_GROUP called
        join_calls = [
            c for c in mock_sock.setsockopt.call_args_list
            if c[0][1] == 20  # IPV6_JOIN_GROUP
        ]
        self.assertEqual(len(join_calls), 1)

    @patch('auto_interface_manager.socketserver')
    @patch('auto_interface_manager.threading')
    @patch('auto_interface_manager.socket')
    def test_creates_two_sockets(self, mock_socket, mock_threading, mock_ss):
        """Should create unicast + multicast discovery sockets (2 total)."""
        self._make_socket_mocks(mock_socket)
        auto_iface = self._make_auto_iface()
        auto_cls = MagicMock()
        auto_cls.SCOPE_LINK = 0

        mock_threading.Thread.return_value = MagicMock()
        mock_ss.UDPServer.return_value = MagicMock()

        auto_interface_manager._add_interface(auto_iface, auto_cls, "wlan0", "fe80::1")

        # 2 sockets: unicast discovery + multicast discovery
        self.assertEqual(mock_socket.socket.call_count, 2)


if __name__ == '__main__':
    unittest.main()
