"""Tests for stub modules (usb4a, jnius, usbserial4a).

These stub modules exist to satisfy import checks in RNode interfaces when
running on Chaquopy (Android Python). They allow TCP RNode connections to work
even though the actual USB/Bluetooth functionality uses native Kotlin code.
"""

import sys
import os
import unittest

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class TestStubModules(unittest.TestCase):
    """Test stub module functionality"""

    def test_usb4a_get_usb_device_returns_none(self):
        """Test that usb4a.usb.get_usb_device returns None"""
        from usb4a import usb

        result = usb.get_usb_device("any_device")

        # Should return None since USB is not available in Chaquopy
        self.assertIsNone(result)

    def test_usb4a_usb_device_class_exists(self):
        """Test that usb4a.USBDevice class is importable"""
        from usb4a import USBDevice

        # Should be importable
        self.assertIsNotNone(USBDevice)

        # Should be a class
        self.assertTrue(isinstance(USBDevice, type))

    def test_jnius_autoclass_raises_not_implemented(self):
        """Test that jnius.autoclass raises NotImplementedError when called"""
        from jnius import autoclass

        # Should raise NotImplementedError when actually called
        with self.assertRaises(NotImplementedError) as context:
            autoclass("SomeClass")

        # Verify error message is informative
        self.assertIn("jnius.autoclass", str(context.exception))
        self.assertIn("not available", str(context.exception))

    def test_usbserial4a_serial4a_class_exists(self):
        """Test that usbserial4a.serial4a class is importable"""
        from usbserial4a import serial4a

        # Should be importable
        self.assertIsNotNone(serial4a)

        # Should be a class
        self.assertTrue(isinstance(serial4a, type))

    def test_usbserial4a_import_succeeds(self):
        """Test that usbserial4a module can be imported"""
        import usbserial4a

        # Should import successfully
        self.assertIsNotNone(usbserial4a)

        # Should have serial4a attribute
        self.assertTrue(hasattr(usbserial4a, 'serial4a'))


if __name__ == '__main__':
    unittest.main()
