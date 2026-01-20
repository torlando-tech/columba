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
        RNS.log("get_connected_usb_devices: USB bridge not initialized", RNS.LOG_ERROR)
        return {'success': False, 'devices': [], 'error': 'USB bridge not initialized'}

    try:
        RNS.log(f"get_connected_usb_devices: calling getConnectedUsbDevices()", RNS.LOG_DEBUG)
        devices = _usb_bridge_instance.getConnectedUsbDevices()
        # Chaquopy returns Java ArrayList - use size() method instead of len()
        device_count = devices.size() if hasattr(devices, 'size') else len(devices)
        RNS.log(f"get_connected_usb_devices: got {device_count} devices from bridge", RNS.LOG_DEBUG)
        device_list = []

        # Use index-based iteration because Chaquopy returns Kotlin List
        # as Java ArrayList which isn't directly iterable in Python
        for i in range(device_count):
            device = devices.get(i) if hasattr(devices, 'get') else devices[i]
            # Chaquopy maps Kotlin data class properties to getter methods
            # Try getXxx() methods first, fall back to property access
            device_list.append({
                'device_id': device.getDeviceId() if hasattr(device, 'getDeviceId') else device.deviceId,
                'vendor_id': device.getVendorId() if hasattr(device, 'getVendorId') else device.vendorId,
                'product_id': device.getProductId() if hasattr(device, 'getProductId') else device.productId,
                'device_name': device.getDeviceName() if hasattr(device, 'getDeviceName') else device.deviceName,
                'manufacturer_name': device.getManufacturerName() if hasattr(device, 'getManufacturerName') else device.manufacturerName,
                'product_name': device.getProductName() if hasattr(device, 'getProductName') else device.productName,
                'serial_number': device.getSerialNumber() if hasattr(device, 'getSerialNumber') else device.serialNumber,
                'driver_type': device.getDriverType() if hasattr(device, 'getDriverType') else device.driverType,
            })

        RNS.log(f"get_connected_usb_devices: returning {len(device_list)} devices", RNS.LOG_DEBUG)
        return {'success': True, 'devices': device_list}
    except Exception as e:
        import traceback
        RNS.log(f"Error getting USB devices: {e}\n{traceback.format_exc()}", RNS.LOG_ERROR)
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


def query_device_hash(device_id, baud_rate=115200, timeout=3.0):
    """
    Query the device hash from an RNode connected via USB.

    This temporarily connects to the device, sends CMD_DEV_HASH,
    receives the 16-byte response, and returns bytes 14-15 formatted
    as a hex string (which matches the Bluetooth device name suffix).

    Args:
        device_id: Integer device ID from get_connected_usb_devices()
        baud_rate: Baud rate (default 115200 for RNode)
        timeout: Timeout in seconds for the query

    Returns:
        dict with 'success' bool and 'identifier' string (e.g., "958F")
        On failure, 'error' contains the error message
    """
    import time

    if _usb_bridge_instance is None:
        return {'success': False, 'error': 'USB bridge not initialized'}

    # KISS protocol constants
    FEND = 0xC0
    CMD_DEV_HASH = 0x56

    try:
        # Connect to the device
        RNS.log(f"query_device_hash: connecting to device {device_id}", RNS.LOG_DEBUG)
        if not _usb_bridge_instance.connect(device_id, baud_rate):
            return {'success': False, 'error': 'Failed to connect to device'}

        # Give device time to initialize
        time.sleep(0.3)

        # Send CMD_DEV_HASH request (command with non-zero byte to request hash)
        kiss_cmd = bytes([FEND, CMD_DEV_HASH, 0x01, FEND])
        RNS.log(f"query_device_hash: sending CMD_DEV_HASH", RNS.LOG_DEBUG)
        written = _usb_bridge_instance.write(kiss_cmd)
        if written != len(kiss_cmd):
            _usb_bridge_instance.disconnect()
            return {'success': False, 'error': f'Write failed: wrote {written} of {len(kiss_cmd)}'}

        # Read response with timeout
        start_time = time.time()
        response_buffer = bytearray()

        while (time.time() - start_time) < timeout:
            raw_data = _usb_bridge_instance.read()
            if hasattr(raw_data, '__len__'):
                data = bytes(raw_data)
            else:
                data = bytes(raw_data) if raw_data else b""

            if len(data) > 0:
                response_buffer.extend(data)
                RNS.log(f"query_device_hash: received {len(data)} bytes, total {len(response_buffer)}", RNS.LOG_DEBUG)

                # Parse the response to find device hash
                # Format: FEND CMD_DEV_HASH <16 bytes hash> FEND
                identifier = _parse_device_hash_response(response_buffer)
                if identifier:
                    RNS.log(f"query_device_hash: found identifier '{identifier}'", RNS.LOG_INFO)
                    _usb_bridge_instance.disconnect()
                    return {'success': True, 'identifier': identifier}

            time.sleep(0.05)

        # Timeout
        _usb_bridge_instance.disconnect()
        return {'success': False, 'error': 'Timeout waiting for device hash response'}

    except Exception as e:
        import traceback
        RNS.log(f"query_device_hash error: {e}\n{traceback.format_exc()}", RNS.LOG_ERROR)
        try:
            _usb_bridge_instance.disconnect()
        except Exception:
            pass
        return {'success': False, 'error': str(e)}


def _parse_device_hash_response(data):
    """
    Parse device hash response from KISS data.

    The response format is: FEND CMD_DEV_HASH <16 bytes hash> FEND
    Returns the identifier string (bytes 14-15 as hex), or None if not found.
    """
    FEND = 0xC0
    FESC = 0xDB
    TFEND = 0xDC
    TFESC = 0xDD
    CMD_DEV_HASH = 0x56

    # Find start of frame
    i = 0
    while i < len(data):
        if data[i] == FEND:
            # Found potential start
            i += 1
            if i >= len(data):
                return None

            # Check if this is CMD_DEV_HASH response
            if data[i] == CMD_DEV_HASH:
                i += 1
                # Parse the hash data (16 bytes, potentially escaped)
                hash_bytes = bytearray()
                while i < len(data) and len(hash_bytes) < 16:
                    if data[i] == FEND:
                        # End of frame before we got 16 bytes
                        break
                    elif data[i] == FESC:
                        i += 1
                        if i >= len(data):
                            return None
                        if data[i] == TFEND:
                            hash_bytes.append(FEND)
                        elif data[i] == TFESC:
                            hash_bytes.append(FESC)
                        else:
                            hash_bytes.append(data[i])
                    else:
                        hash_bytes.append(data[i])
                    i += 1

                if len(hash_bytes) >= 16:
                    # Extract bytes 14-15 and format as hex
                    identifier = f"{hash_bytes[14]:02X}{hash_bytes[15]:02X}"
                    return identifier
            else:
                # Not CMD_DEV_HASH, continue searching
                pass
        else:
            i += 1

    return None
