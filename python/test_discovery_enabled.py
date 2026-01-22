"""
Test suite for is_discovery_enabled() method.

Tests the ability to check if interface discovery and auto-connect is enabled
in RNS 1.1.0+ via the should_autoconnect_discovered_interfaces() method.
"""

import sys
import os
import unittest
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


class TestIsDiscoveryEnabled(unittest.TestCase):
    """Test is_discovery_enabled method"""

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

    def test_returns_false_when_reticulum_not_available(self):
        """Test that method returns False when RETICULUM_AVAILABLE is False"""
        # Temporarily set RETICULUM_AVAILABLE to False
        original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = False

        try:
            result = self.wrapper.is_discovery_enabled()
            self.assertFalse(result)
        finally:
            reticulum_wrapper.RETICULUM_AVAILABLE = original_available

    def test_returns_false_when_not_initialized(self):
        """Test that method returns False when wrapper.reticulum is None"""
        # Ensure wrapper is not initialized
        self.wrapper.reticulum = None

        result = self.wrapper.is_discovery_enabled()
        self.assertFalse(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_true_when_discovery_enabled(self, mock_rns):
        """Test that method returns True when RNS reports discovery is enabled"""
        self.wrapper.reticulum = MagicMock()

        # Mock RNS.Reticulum to have the method and return True
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(return_value=True)

        result = self.wrapper.is_discovery_enabled()
        self.assertTrue(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_false_when_discovery_disabled(self, mock_rns):
        """Test that method returns False when RNS reports discovery is disabled"""
        self.wrapper.reticulum = MagicMock()

        # Mock RNS.Reticulum to have the method and return False
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(return_value=False)

        result = self.wrapper.is_discovery_enabled()
        self.assertFalse(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_returns_false_when_method_not_available(self, mock_rns):
        """Test that method returns False when should_autoconnect_discovered_interfaces doesn't exist"""
        self.wrapper.reticulum = MagicMock()

        # Remove the method from RNS.Reticulum to simulate older RNS version
        # Configure the mock so hasattr returns False for should_autoconnect_discovered_interfaces
        mock_reticulum = MagicMock(spec=[])  # Empty spec means no attributes
        mock_rns.Reticulum = mock_reticulum

        result = self.wrapper.is_discovery_enabled()
        self.assertFalse(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_exception_gracefully(self, mock_rns):
        """Test that method returns False when an exception occurs"""
        self.wrapper.reticulum = MagicMock()

        # Mock the method to raise an exception
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(
            side_effect=RuntimeError("Discovery error")
        )

        result = self.wrapper.is_discovery_enabled()
        self.assertFalse(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_handles_attribute_error_gracefully(self, mock_rns):
        """Test that method returns False when AttributeError occurs"""
        self.wrapper.reticulum = MagicMock()

        # Mock the method to raise AttributeError
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(
            side_effect=AttributeError("No such attribute")
        )

        result = self.wrapper.is_discovery_enabled()
        self.assertFalse(result)

    def test_return_type_is_bool(self):
        """Test that the return type is always a boolean"""
        # Test when not initialized
        self.wrapper.reticulum = None
        result = self.wrapper.is_discovery_enabled()
        self.assertIsInstance(result, bool)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_return_type_is_bool_when_initialized(self, mock_rns):
        """Test that the return type is a boolean when initialized"""
        self.wrapper.reticulum = MagicMock()
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(return_value=True)
        result = self.wrapper.is_discovery_enabled()
        self.assertIsInstance(result, bool)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_calls_rns_method_correctly(self, mock_rns):
        """Test that the method calls RNS.Reticulum.should_autoconnect_discovered_interfaces()"""
        self.wrapper.reticulum = MagicMock()

        # Set up mock
        mock_method = Mock(return_value=True)
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = mock_method

        self.wrapper.is_discovery_enabled()

        # Verify the method was called
        mock_method.assert_called_once()


class TestIsDiscoveryEnabledWithFixtures(unittest.TestCase):
    """Test is_discovery_enabled using pytest-style fixtures from conftest"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_uninitialized_wrapper_returns_false(self):
        """Test that uninitialized wrapper returns False"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        # wrapper.reticulum is None by default
        result = wrapper.is_discovery_enabled()
        self.assertFalse(result)

    @patch.object(reticulum_wrapper, 'RNS')
    @patch.object(reticulum_wrapper, 'RETICULUM_AVAILABLE', True)
    def test_initialized_wrapper_checks_rns_status(self, mock_rns):
        """Test that initialized wrapper properly checks RNS status"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.reticulum = MagicMock()

        # Set up mock to return True
        mock_rns.Reticulum.should_autoconnect_discovered_interfaces = Mock(return_value=True)

        result = wrapper.is_discovery_enabled()
        self.assertTrue(result)


if __name__ == '__main__':
    unittest.main()
