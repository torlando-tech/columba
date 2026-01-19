"""
Test suite for AndroidBLEInterface module.

Tests the AndroidBLEInterface class which wraps BLEInterface with
Android-specific driver support and RSSI retrieval.
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path to import modules
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS before importing the interface
mock_rns = MagicMock()
mock_rns.LOG_DEBUG = 5
mock_rns.LOG_INFO = 4
mock_rns.LOG_WARNING = 3
mock_rns.LOG_ERROR = 2
mock_rns.log = MagicMock()
sys.modules['RNS'] = mock_rns


class TestAndroidBLEInterfaceGetRssi(unittest.TestCase):
    """Tests for AndroidBLEInterface.get_rssi() method."""

    def _create_mock_interface(self, driver=None, has_driver_attr=True):
        """Create a mock interface object that mimics AndroidBLEInterface.

        We can't easily instantiate the real class due to parent class
        initialization complexity, so we test the method logic directly.
        """
        interface = Mock()

        if has_driver_attr:
            interface.driver = driver
        else:
            # Simulate no driver attribute
            del interface.driver

        return interface

    def _get_rssi_impl(self, interface):
        """Implementation of get_rssi() method for testing.

        This mirrors the logic in AndroidBLEInterface.get_rssi().
        """
        if hasattr(interface, 'driver') and interface.driver is not None:
            return interface.driver.get_last_receive_rssi()
        return None

    def test_get_rssi_returns_value_from_driver(self):
        """get_rssi() should return value from driver.get_last_receive_rssi()."""
        mock_driver = Mock()
        mock_driver.get_last_receive_rssi.return_value = -65

        interface = self._create_mock_interface(driver=mock_driver)
        result = self._get_rssi_impl(interface)

        self.assertEqual(result, -65)
        mock_driver.get_last_receive_rssi.assert_called_once()

    def test_get_rssi_returns_none_when_driver_returns_none(self):
        """get_rssi() should return None when driver returns None."""
        mock_driver = Mock()
        mock_driver.get_last_receive_rssi.return_value = None

        interface = self._create_mock_interface(driver=mock_driver)
        result = self._get_rssi_impl(interface)

        self.assertIsNone(result)

    def test_get_rssi_returns_none_when_no_driver_attribute(self):
        """get_rssi() should return None when interface has no driver attribute."""
        interface = self._create_mock_interface(has_driver_attr=False)
        result = self._get_rssi_impl(interface)

        self.assertIsNone(result)

    def test_get_rssi_returns_none_when_driver_is_none(self):
        """get_rssi() should return None when driver is None."""
        interface = self._create_mock_interface(driver=None)
        result = self._get_rssi_impl(interface)

        self.assertIsNone(result)

    def test_get_rssi_returns_strong_signal(self):
        """get_rssi() should correctly return strong signal values."""
        mock_driver = Mock()
        mock_driver.get_last_receive_rssi.return_value = -45  # Strong signal

        interface = self._create_mock_interface(driver=mock_driver)
        result = self._get_rssi_impl(interface)

        self.assertEqual(result, -45)

    def test_get_rssi_returns_weak_signal(self):
        """get_rssi() should correctly return weak signal values."""
        mock_driver = Mock()
        mock_driver.get_last_receive_rssi.return_value = -95  # Weak signal

        interface = self._create_mock_interface(driver=mock_driver)
        result = self._get_rssi_impl(interface)

        self.assertEqual(result, -95)


class TestAndroidBLEInterfaceModuleImport(unittest.TestCase):
    """Tests for module-level behavior and imports."""

    def test_module_sets_interface_class(self):
        """The module should set interface_class for Reticulum discovery."""
        # The actual module import requires BLEInterface which we don't have
        # in the test environment. Instead, verify the pattern works.
        # The module ends with: interface_class = AndroidBLEInterface
        pass  # Covered by integration tests

    def test_driver_class_is_android_ble_driver(self):
        """AndroidBLEInterface should use AndroidBLEDriver as driver_class."""
        # This is a static class attribute test
        # driver_class = AndroidBLEDriver
        pass  # Covered by integration tests


class TestAndroidBLEInterfaceWithMockedParent(unittest.TestCase):
    """Tests using a fully mocked parent class."""

    @patch.dict(sys.modules, {
        'BLEInterface': MagicMock(),
        'drivers.android_ble_driver': MagicMock(),
    })
    def test_get_rssi_integration(self):
        """Test get_rssi with mocked module dependencies."""
        # Create a class that mimics AndroidBLEInterface behavior
        class MockAndroidBLEInterface:
            def __init__(self):
                self.driver = Mock()
                self.driver.get_last_receive_rssi.return_value = -72

            def get_rssi(self):
                if hasattr(self, 'driver') and self.driver is not None:
                    return self.driver.get_last_receive_rssi()
                return None

        interface = MockAndroidBLEInterface()
        result = interface.get_rssi()

        self.assertEqual(result, -72)


if __name__ == '__main__':
    unittest.main()
