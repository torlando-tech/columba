"""
USB Bridge for Columba RNode support.

Provides Python interface to KotlinUSBBridge for USB serial communication
with RNode devices. Handles device enumeration, permission requests, and
bridge initialization.

This module serves as the Python-side wrapper for the Kotlin USB bridge,
providing a clean interface for the rnode_interface.py to use when
operating in USB mode.
"""

import RNS

# Global USB bridge instance (set from Kotlin during initialization)
_usb_bridge_instance = None


def set_usb_bridge(bridge):
    """
    Set the KotlinUSBBridge instance.

    Called from reticulum_wrapper.py during initialization when the
    Kotlin bridge passes the USB bridge reference to Python.

    Args:
        bridge: KotlinUSBBridge instance from Kotlin
    """
    global _usb_bridge_instance
    _usb_bridge_instance = bridge
    RNS.log("USB bridge set", RNS.LOG_DEBUG)


def get_usb_bridge():
    """
    Get the KotlinUSBBridge instance.

    Returns:
        KotlinUSBBridge instance or None if not initialized
    """
    return _usb_bridge_instance


def is_available():
    """
    Check if USB bridge is available.

    Returns:
        True if USB bridge is initialized, False otherwise
    """
    return _usb_bridge_instance is not None


def get_connected_usb_devices():
    """
    Get list of connected USB serial devices.

    Returns a list of device info dictionaries with:
    - device_id: Integer device ID (used for connect())
    - vendor_id: USB VID
    - product_id: USB PID
    - device_name: System device name (e.g., /dev/bus/usb/...)
    - manufacturer_name: Manufacturer string (may be None)
    - product_name: Product string (may be None)
    - serial_number: Serial number string (may be None)
    - driver_type: Detected driver type (FTDI, CP210x, CH340, CDC-ACM, etc.)

    Returns:
        dict with 'success' bool and 'devices' list
    """
    if _usb_bridge_instance is None:
        return {'success': False, 'devices': [], 'error': 'USB bridge not initialized'}

    try:
        devices = _usb_bridge_instance.getConnectedUsbDevices()
        device_list = []

        for device in devices:
            device_list.append({
                'device_id': device.deviceId,
                'vendor_id': device.vendorId,
                'product_id': device.productId,
                'device_name': device.deviceName,
                'manufacturer_name': device.manufacturerName,
                'product_name': device.productName,
                'serial_number': device.serialNumber,
                'driver_type': device.driverType,
            })

        return {'success': True, 'devices': device_list}
    except Exception as e:
        RNS.log(f"Error getting USB devices: {e}", RNS.LOG_ERROR)
        return {'success': False, 'devices': [], 'error': str(e)}


def has_permission(device_id):
    """
    Check if we have permission to access a USB device.

    Args:
        device_id: Integer device ID from get_connected_usb_devices()

    Returns:
        True if permission granted, False otherwise
    """
    if _usb_bridge_instance is None:
        return False

    try:
        return _usb_bridge_instance.hasPermission(device_id)
    except Exception as e:
        RNS.log(f"Error checking USB permission: {e}", RNS.LOG_ERROR)
        return False


def request_usb_permission(device_id, callback):
    """
    Request USB permission for a device.

    Android requires explicit user permission before accessing USB devices.
    This triggers a system dialog asking the user to grant permission.

    Args:
        device_id: Integer device ID from get_connected_usb_devices()
        callback: Function called with boolean result (True = granted)
    """
    if _usb_bridge_instance is None:
        callback(False)
        return

    try:
        _usb_bridge_instance.requestPermission(device_id, callback)
    except Exception as e:
        RNS.log(f"Error requesting USB permission: {e}", RNS.LOG_ERROR)
        callback(False)


def connect(device_id, baud_rate=115200):
    """
    Connect to a USB device.

    Args:
        device_id: Integer device ID from get_connected_usb_devices()
        baud_rate: Baud rate (default 115200 for RNode)

    Returns:
        True if connection successful, False otherwise
    """
    if _usb_bridge_instance is None:
        RNS.log("USB bridge not initialized", RNS.LOG_ERROR)
        return False

    try:
        return _usb_bridge_instance.connect(device_id, baud_rate)
    except Exception as e:
        RNS.log(f"Error connecting to USB device: {e}", RNS.LOG_ERROR)
        return False


def disconnect():
    """Disconnect from the current USB device."""
    if _usb_bridge_instance is None:
        return

    try:
        _usb_bridge_instance.disconnect()
    except Exception as e:
        RNS.log(f"Error disconnecting USB device: {e}", RNS.LOG_ERROR)


def is_connected():
    """
    Check if currently connected to a USB device.

    Returns:
        True if connected, False otherwise
    """
    if _usb_bridge_instance is None:
        return False

    try:
        return _usb_bridge_instance.isConnected()
    except Exception as e:
        RNS.log(f"Error checking USB connection: {e}", RNS.LOG_ERROR)
        return False


def write(data):
    """
    Write data to the USB device.

    Args:
        data: Bytes to write (typically KISS-framed data)

    Returns:
        Number of bytes written, or -1 on error
    """
    if _usb_bridge_instance is None:
        return -1

    try:
        return _usb_bridge_instance.write(data)
    except Exception as e:
        RNS.log(f"USB write error: {e}", RNS.LOG_ERROR)
        return -1


def read():
    """
    Read available data from the USB device (non-blocking).

    Returns:
        Bytes of available data, or empty bytes if no data
    """
    if _usb_bridge_instance is None:
        return b''

    try:
        data = _usb_bridge_instance.read()
        if hasattr(data, '__len__'):
            return bytes(data)
        return b''
    except Exception as e:
        RNS.log(f"USB read error: {e}", RNS.LOG_ERROR)
        return b''


def available():
    """
    Get number of bytes available to read.

    Returns:
        Number of bytes in read buffer
    """
    if _usb_bridge_instance is None:
        return 0

    try:
        return _usb_bridge_instance.available()
    except Exception as e:
        RNS.log(f"USB available error: {e}", RNS.LOG_ERROR)
        return 0


def get_connected_device_id():
    """
    Get the currently connected device ID.

    Returns:
        Device ID or None if not connected
    """
    if _usb_bridge_instance is None:
        return None

    try:
        return _usb_bridge_instance.getConnectedDeviceId()
    except Exception as e:
        RNS.log(f"Error getting connected device ID: {e}", RNS.LOG_ERROR)
        return None


def set_on_data_received(callback):
    """
    Set callback for received data.

    Args:
        callback: Function called with bytes when data arrives
    """
    if _usb_bridge_instance is None:
        return

    try:
        _usb_bridge_instance.setOnDataReceived(callback)
    except Exception as e:
        RNS.log(f"Error setting USB data callback: {e}", RNS.LOG_ERROR)


def set_on_connection_state_changed(callback):
    """
    Set callback for connection state changes.

    Args:
        callback: Function called with (connected: bool, device_id: int)
    """
    if _usb_bridge_instance is None:
        return

    try:
        _usb_bridge_instance.setOnConnectionStateChanged(callback)
    except Exception as e:
        RNS.log(f"Error setting USB connection callback: {e}", RNS.LOG_ERROR)


def set_on_bluetooth_pin_received(callback):
    """
    Set callback for Bluetooth PIN received during USB pairing mode.

    When the RNode is put into Bluetooth pairing mode via USB, it sends
    a 6-digit PIN that must be entered on the Android device's Bluetooth
    settings to complete pairing.

    Args:
        callback: Function called with pin string
    """
    if _usb_bridge_instance is None:
        return

    try:
        _usb_bridge_instance.setOnBluetoothPinReceived(callback)
    except Exception as e:
        RNS.log(f"Error setting USB Bluetooth PIN callback: {e}", RNS.LOG_ERROR)
