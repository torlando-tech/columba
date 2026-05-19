# Stub usb4a module for Chaquopy
#
# Required by usbserial4a. TCP mode doesn't use USB.


class usb:
    """Stub USB class."""

    @staticmethod
    def get_usb_device(device_name):
        """Return None - USB devices not available in Chaquopy."""
        return None


class USBDevice:
    """Stub USB device class."""
    pass
