#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
AndroidBLEInterface - Reticulum Interface for Android BLE
=========================================================

This module provides a Reticulum interface for Bluetooth Low Energy (BLE) on
Android devices. It leverages the `ble-reticulum` driver-based architecture
to reuse the core `BLEInterface` logic while plugging in an Android-specific
driver (`AndroidBLEDriver`).

The `AndroidBLEDriver` acts as a bridge to the `KotlinBLEBridge` via Chaquopy,
which in turn manages all native Android BLE operations.

This interface is automatically discovered and loaded by Reticulum if placed
in the `~/.reticulum/interfaces/` directory and configured in `config`.

This file is a thin wrapper that configures and initializes the generic
`BLEInterface` with the `AndroidBLEDriver`.

Author: Columba Project
License: MIT
"""

import RNS
import sys
import os

# When Reticulum loads this interface with exec(), we need to ensure the interfaces
# directory is in sys.path so imports work. The interfaces are in the app storage.
_storage_base = os.environ.get("HOME", "/data/user/0/com.lxmf.messenger/files")
_interfaces_dir = os.path.join(_storage_base, "reticulum", "interfaces")
if os.path.exists(_interfaces_dir) and _interfaces_dir not in sys.path:
    sys.path.insert(0, _interfaces_dir)

# Import the generic BLEInterface and the Android-specific driver
# Note: BLEInterface is deployed to the same interfaces directory as this file
from BLEInterface import BLEInterface
from drivers.android_ble_driver import AndroidBLEDriver


class AndroidBLEInterface(BLEInterface):
    """
    Reticulum interface for Android BLE.

    This class inherits from the generic `BLEInterface` and uses the
    `AndroidBLEDriver` to provide Android-specific BLE functionality.
    All the complex logic for peer management, connection handling, and
    fragmentation is handled by the parent `BLEInterface`.
    """

    # Override driver class to use Android implementation
    driver_class = AndroidBLEDriver

    def __init__(self, owner, config=None):
        """
        Initialize the Android BLE interface.

        Args:
            owner: The Reticulum Transport instance that owns this interface.
            config: A dictionary containing configuration options.
        """
        # Call parent constructor - it will use our driver_class
        super().__init__(owner, config)

        RNS.log(f"Android BLE Interface '{self.name}' initialized", RNS.LOG_INFO)

        # Log configuration details if attributes are available
        if hasattr(self, 'mode_str'):
            RNS.log(f"  Mode: {self.mode_str}", RNS.LOG_INFO)
        if hasattr(self, 'enable_central'):
            RNS.log(f"  Central: {'Enabled' if self.enable_central else 'Disabled'}", RNS.LOG_INFO)
        if hasattr(self, 'enable_peripheral'):
            RNS.log(f"  Peripheral: {'Enabled' if self.enable_peripheral else 'Disabled'}", RNS.LOG_INFO)
        RNS.log(f"  Max Peers: {self.max_peers}", RNS.LOG_INFO)


# Register this class as the interface entry point for Reticulum
interface_class = AndroidBLEInterface

