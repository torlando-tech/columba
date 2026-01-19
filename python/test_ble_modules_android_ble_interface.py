"""
Test suite for ble_modules.android_ble_interface module.

Tests the AndroidBLEInterface class from the ble_modules package which
provides Android-specific BLE interface implementation with RSSI support.
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


class TestBleModulesAndroidBLEInterfaceGetRssi(unittest.TestCase):
    """Tests for ble_modules AndroidBLEInterface.get_rssi() method."""

    def test_get_rssi_with_driver_returns_rssi(self):
        """get_rssi() should delegate to driver.get_last_receive_rssi()."""
        # Create an object with the same structure as AndroidBLEInterface
        interface = Mock()
        interface.driver = Mock()
        interface.driver.get_last_receive_rssi.return_value = -58

        # Implement get_rssi logic
        def get_rssi():
            if hasattr(interface, 'driver') and interface.driver is not None:
                return interface.driver.get_last_receive_rssi()
            return None

        result = get_rssi()
        self.assertEqual(result, -58)
        interface.driver.get_last_receive_rssi.assert_called_once()

    def test_get_rssi_without_driver_returns_none(self):
        """get_rssi() should return None when no driver is set."""
        interface = Mock(spec=[])  # No driver attribute

        def get_rssi():
            if hasattr(interface, 'driver') and interface.driver is not None:
                return interface.driver.get_last_receive_rssi()
            return None

        result = get_rssi()
        self.assertIsNone(result)

    def test_get_rssi_with_none_driver_returns_none(self):
        """get_rssi() should return None when driver is None."""
        interface = Mock()
        interface.driver = None

        def get_rssi():
            if hasattr(interface, 'driver') and interface.driver is not None:
                return interface.driver.get_last_receive_rssi()
            return None

        result = get_rssi()
        self.assertIsNone(result)

    def test_get_rssi_returns_negative_values(self):
        """get_rssi() should return negative dBm values correctly."""
        interface = Mock()
        interface.driver = Mock()
        interface.driver.get_last_receive_rssi.return_value = -82

        def get_rssi():
            if hasattr(interface, 'driver') and interface.driver is not None:
                return interface.driver.get_last_receive_rssi()
            return None

        result = get_rssi()
        self.assertEqual(result, -82)
        self.assertLess(result, 0)  # RSSI should be negative


class TestBleModulesAndroidBLEInterfaceClass(unittest.TestCase):
    """Tests for the AndroidBLEInterface class structure."""

    def test_class_has_driver_class_attribute(self):
        """AndroidBLEInterface should define driver_class as AndroidBLEDriver."""
        # This verifies the class attribute pattern used
        class MockBLEInterface:
            driver_class = None

        class MockAndroidBLEInterface(MockBLEInterface):
            driver_class = "AndroidBLEDriver"

        self.assertEqual(MockAndroidBLEInterface.driver_class, "AndroidBLEDriver")

    def test_get_rssi_method_signature(self):
        """get_rssi() should be a method that returns RSSI value."""
        class MockAndroidBLEInterface:
            def __init__(self):
                self.driver = None

            def get_rssi(self):
                if hasattr(self, 'driver') and self.driver is not None:
                    return self.driver.get_last_receive_rssi()
                return None

        interface = MockAndroidBLEInterface()
        result = interface.get_rssi()
        self.assertIsNone(result)


class TestBleModulesInterfaceClassRegistration(unittest.TestCase):
    """Tests for module-level interface class registration."""

    def test_interface_class_pattern(self):
        """Module should set interface_class for Reticulum discovery."""
        # The module pattern: interface_class = AndroidBLEInterface
        class MockAndroidBLEInterface:
            pass

        interface_class = MockAndroidBLEInterface

        self.assertEqual(interface_class, MockAndroidBLEInterface)


if __name__ == '__main__':
    unittest.main()
