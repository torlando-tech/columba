"""
Unit tests for usb_bridge.py

Tests the Python USB bridge module that wraps the Kotlin USB bridge
for USB serial communication with RNode devices.
"""

import pytest
import sys
from unittest.mock import MagicMock, patch, PropertyMock

# Mock RNS before importing usb_bridge
sys.modules['RNS'] = MagicMock()

import usb_bridge


class TestUsbBridgeSetup:
    """Tests for USB bridge initialization."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_set_usb_bridge_stores_instance(self):
        """set_usb_bridge should store the bridge instance."""
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)
        assert usb_bridge._usb_bridge_instance is mock_bridge

    def test_get_usb_bridge_returns_instance(self):
        """get_usb_bridge should return the stored instance."""
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)
        assert usb_bridge.get_usb_bridge() is mock_bridge

    def test_get_usb_bridge_returns_none_when_not_set(self):
        """get_usb_bridge should return None when not initialized."""
        assert usb_bridge.get_usb_bridge() is None

    def test_is_available_returns_true_when_set(self):
        """is_available should return True when bridge is set."""
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)
        assert usb_bridge.is_available() is True

    def test_is_available_returns_false_when_not_set(self):
        """is_available should return False when bridge is not set."""
        assert usb_bridge.is_available() is False


class TestGetConnectedUsbDevices:
    """Tests for get_connected_usb_devices()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_error_when_not_initialized(self):
        """Should return error when bridge not initialized."""
        result = usb_bridge.get_connected_usb_devices()
        assert result['success'] is False
        assert result['devices'] == []
        assert 'not initialized' in result['error']

    def test_returns_devices_from_bridge(self):
        """Should return devices from Kotlin bridge."""
        mock_device = MagicMock()
        # Configure getter methods (Chaquopy exposes Kotlin data class props as getters)
        mock_device.getDeviceId.return_value = 1
        mock_device.getVendorId.return_value = 0x0403
        mock_device.getProductId.return_value = 0x6001
        mock_device.getDeviceName.return_value = "/dev/bus/usb/001/002"
        mock_device.getManufacturerName.return_value = "FTDI"
        mock_device.getProductName.return_value = "FT232R"
        mock_device.getSerialNumber.return_value = "A12345"
        mock_device.getDriverType.return_value = "FTDI"

        mock_bridge = MagicMock()
        mock_bridge.getConnectedUsbDevices.return_value = [mock_device]
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.get_connected_usb_devices()

        assert result['success'] is True
        assert len(result['devices']) == 1
        assert result['devices'][0]['device_id'] == 1
        assert result['devices'][0]['vendor_id'] == 0x0403
        assert result['devices'][0]['product_id'] == 0x6001
        assert result['devices'][0]['driver_type'] == "FTDI"

    def test_returns_empty_list_on_no_devices(self):
        """Should return empty list when no devices connected."""
        mock_bridge = MagicMock()
        mock_bridge.getConnectedUsbDevices.return_value = []
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.get_connected_usb_devices()

        assert result['success'] is True
        assert result['devices'] == []

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.getConnectedUsbDevices.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.get_connected_usb_devices()

        assert result['success'] is False
        assert result['devices'] == []
        assert 'Test error' in result['error']


class TestHasPermission:
    """Tests for has_permission()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_false_when_not_initialized(self):
        """Should return False when bridge not initialized."""
        assert usb_bridge.has_permission(1) is False

    def test_returns_true_when_has_permission(self):
        """Should return True when bridge has permission."""
        mock_bridge = MagicMock()
        mock_bridge.hasPermission.return_value = True
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.has_permission(1) is True
        mock_bridge.hasPermission.assert_called_once_with(1)

    def test_returns_false_when_no_permission(self):
        """Should return False when bridge doesn't have permission."""
        mock_bridge = MagicMock()
        mock_bridge.hasPermission.return_value = False
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.has_permission(1) is False

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.hasPermission.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.has_permission(1) is False


class TestRequestUsbPermission:
    """Tests for request_usb_permission()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_calls_callback_false_when_not_initialized(self):
        """Should call callback with False when bridge not initialized."""
        callback = MagicMock()
        usb_bridge.request_usb_permission(1, callback)
        callback.assert_called_once_with(False)

    def test_calls_bridge_request_permission(self):
        """Should call bridge requestPermission method."""
        callback = MagicMock()
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.request_usb_permission(1, callback)

        mock_bridge.requestPermission.assert_called_once_with(1, callback)

    def test_handles_exception(self):
        """Should handle exceptions and call callback with False."""
        callback = MagicMock()
        mock_bridge = MagicMock()
        mock_bridge.requestPermission.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.request_usb_permission(1, callback)

        callback.assert_called_once_with(False)


class TestConnect:
    """Tests for connect()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_false_when_not_initialized(self):
        """Should return False when bridge not initialized."""
        assert usb_bridge.connect(1) is False

    def test_calls_bridge_connect(self):
        """Should call bridge connect method."""
        mock_bridge = MagicMock()
        mock_bridge.connect.return_value = True
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.connect(1, 115200)

        assert result is True
        mock_bridge.connect.assert_called_once_with(1, 115200)

    def test_uses_default_baud_rate(self):
        """Should use default baud rate of 115200."""
        mock_bridge = MagicMock()
        mock_bridge.connect.return_value = True
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.connect(1)

        mock_bridge.connect.assert_called_once_with(1, 115200)

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.connect.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.connect(1) is False


class TestDisconnect:
    """Tests for disconnect()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_does_nothing_when_not_initialized(self):
        """Should not raise when bridge not initialized."""
        usb_bridge.disconnect()  # Should not raise

    def test_calls_bridge_disconnect(self):
        """Should call bridge disconnect method."""
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.disconnect()

        mock_bridge.disconnect.assert_called_once()

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.disconnect.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.disconnect()  # Should not raise


class TestIsConnected:
    """Tests for is_connected()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_false_when_not_initialized(self):
        """Should return False when bridge not initialized."""
        assert usb_bridge.is_connected() is False

    def test_returns_bridge_status(self):
        """Should return bridge connection status."""
        mock_bridge = MagicMock()
        mock_bridge.isConnected.return_value = True
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.is_connected() is True

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.isConnected.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.is_connected() is False


class TestWrite:
    """Tests for write()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_negative_one_when_not_initialized(self):
        """Should return -1 when bridge not initialized."""
        assert usb_bridge.write(b'\x00\x01') == -1

    def test_calls_bridge_write(self):
        """Should call bridge write method."""
        mock_bridge = MagicMock()
        mock_bridge.write.return_value = 2
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.write(b'\x00\x01')

        assert result == 2
        mock_bridge.write.assert_called_once_with(b'\x00\x01')

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.write.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.write(b'\x00\x01') == -1


class TestRead:
    """Tests for read()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_empty_when_not_initialized(self):
        """Should return empty bytes when bridge not initialized."""
        assert usb_bridge.read() == b''

    def test_returns_data_from_bridge(self):
        """Should return data from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.read.return_value = bytes([0xC0, 0x00, 0xC0])
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.read()

        assert result == bytes([0xC0, 0x00, 0xC0])

    def test_converts_to_bytes(self):
        """Should convert result to bytes."""
        mock_bridge = MagicMock()
        # Simulate Java byte array that has __len__
        mock_data = MagicMock()
        mock_data.__len__ = MagicMock(return_value=3)
        mock_data.__iter__ = MagicMock(return_value=iter([0xC0, 0x00, 0xC0]))
        mock_bridge.read.return_value = mock_data
        usb_bridge.set_usb_bridge(mock_bridge)

        result = usb_bridge.read()

        assert isinstance(result, bytes)

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.read.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.read() == b''


class TestAvailable:
    """Tests for available()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_zero_when_not_initialized(self):
        """Should return 0 when bridge not initialized."""
        assert usb_bridge.available() == 0

    def test_returns_bridge_available(self):
        """Should return available bytes from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.available.return_value = 10
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.available() == 10

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.available.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.available() == 0


class TestGetConnectedDeviceId:
    """Tests for get_connected_device_id()."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_returns_none_when_not_initialized(self):
        """Should return None when bridge not initialized."""
        assert usb_bridge.get_connected_device_id() is None

    def test_returns_device_id_from_bridge(self):
        """Should return device ID from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.getConnectedDeviceId.return_value = 42
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.get_connected_device_id() == 42

    def test_handles_exception(self):
        """Should handle exceptions from bridge."""
        mock_bridge = MagicMock()
        mock_bridge.getConnectedDeviceId.side_effect = Exception("Test error")
        usb_bridge.set_usb_bridge(mock_bridge)

        assert usb_bridge.get_connected_device_id() is None


class TestCallbackSetters:
    """Tests for callback setter functions."""

    def setup_method(self):
        """Reset global state before each test."""
        usb_bridge._usb_bridge_instance = None

    def test_set_on_data_received_calls_bridge(self):
        """Should call bridge setOnDataReceived."""
        callback = MagicMock()
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.set_on_data_received(callback)

        mock_bridge.setOnDataReceived.assert_called_once_with(callback)

    def test_set_on_connection_state_changed_calls_bridge(self):
        """Should call bridge setOnConnectionStateChanged."""
        callback = MagicMock()
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.set_on_connection_state_changed(callback)

        mock_bridge.setOnConnectionStateChanged.assert_called_once_with(callback)

    def test_set_on_bluetooth_pin_received_calls_bridge(self):
        """Should call bridge setOnBluetoothPinReceived."""
        callback = MagicMock()
        mock_bridge = MagicMock()
        usb_bridge.set_usb_bridge(mock_bridge)

        usb_bridge.set_on_bluetooth_pin_received(callback)

        mock_bridge.setOnBluetoothPinReceived.assert_called_once_with(callback)

    def test_callback_setters_handle_not_initialized(self):
        """Callback setters should not raise when bridge not initialized."""
        callback = MagicMock()
        usb_bridge.set_on_data_received(callback)  # Should not raise
        usb_bridge.set_on_connection_state_changed(callback)  # Should not raise
        usb_bridge.set_on_bluetooth_pin_received(callback)  # Should not raise
