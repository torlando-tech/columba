"""
BLE Agent for Automatic Pairing - Reticulum BLE Interface

This module implements a BlueZ D-Bus agent for handling BLE pairing
automatically without user interaction. This is required for zero-touch
mesh networking where devices need to pair automatically.

Background:
-----------
BlueZ's GATT caching mechanism (Bluetooth 5.1 Database Hash) triggers
automatic pairing when connecting to BlueZ-based GATT servers. This
happens even when GATT characteristics have no security requirements.

The pairing is needed for "Service Changed" indication subscriptions
to persist across connections. Without an agent to handle the pairing,
the pairing fails with "Numeric Comparison failed" error.

Solution:
---------
Register a BlueZ agent with DisplayOnly or NoInputNoOutput capability
to force "Just Works" pairing method, which auto-completes without
user interaction.

Security:
---------
Just Works pairing provides:
- Unauthenticated encryption (BLE Security Mode 1 Level 2)
- Vulnerable to MITM attacks during pairing
- Acceptable for Reticulum use case because:
  * BLE is just transport layer
  * Reticulum has its own cryptographic security
  * Physical BLE range (~10-30m) limits attack surface
  * Standard practice for IoT mesh devices

Author: Reticulum BLE Interface Contributors
License: MIT
Date: 2025-10-15
"""

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
import logging
from typing import Optional

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class BLEAgent(dbus.service.Object):
    """
    BlueZ Agent for automatic BLE pairing

    Implements org.bluez.Agent1 D-Bus interface to handle pairing
    requests automatically without user interaction.

    This enables zero-touch mesh networking where BLE devices can
    discover and pair with each other automatically.
    """

    AGENT_PATH = "/org/bluez/reticulum_ble_agent"

    def __init__(self, bus: dbus.SystemBus, capability: str = "NoInputNoOutput"):
        """
        Initialize BLE agent

        Args:
            bus: D-Bus system bus connection
            capability: IO capability - "NoInputNoOutput" (default) or "DisplayOnly"
                       NoInputNoOutput: Recommended for Linux-to-Linux (avoids MITM requirement)
                       DisplayOnly: Alternative capability mode (not typically needed for Linux-to-Linux)
        """
        super().__init__(bus, self.AGENT_PATH)
        self.capability = capability
        self._log(f"BLE Agent initialized with capability: {capability}", "INFO")

    def _log(self, message: str, level: str = "INFO"):
        """Log message with RNS logging if available, else standard logging"""
        try:
            import RNS
            level_map = {
                "DEBUG": RNS.LOG_DEBUG,
                "INFO": RNS.LOG_INFO,
                "WARNING": RNS.LOG_WARNING,
                "ERROR": RNS.LOG_ERROR,
            }
            RNS.log(f"BLEAgent[{self.capability}] {message}", level_map.get(level, RNS.LOG_INFO))
        except:
            # Fallback to standard logging
            log_func = getattr(logger, level.lower(), logger.info)
            log_func(f"BLEAgent[{self.capability}] {message}")

    # ========== org.bluez.Agent1 Interface Methods ==========

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Release(self):
        """
        Called when agent is unregistered

        This is invoked when the service daemon unregisters the agent.
        An agent can use it to do cleanup tasks.
        """
        self._log("Agent released by BlueZ", "DEBUG")

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        """
        Auto-authorize all GATT service access

        This method gets called when the service daemon needs to
        authorize a connection/service to a device.

        Args:
            device: D-Bus object path of the device
            uuid: Service UUID to authorize
        """
        device_addr = self._format_device_path(device)
        self._log(f"Auto-authorizing service {uuid} for {device_addr}", "DEBUG")
        return  # Implicit success

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        """
        Auto-authorize general authorization requests

        Args:
            device: D-Bus object path of the device
        """
        device_addr = self._format_device_path(device)
        self._log(f"Auto-authorizing connection for {device_addr}", "DEBUG")
        return  # Implicit success

    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        """
        Auto-confirm pairing (Just Works method)

        This method gets called for Just Works pairing where both
        devices auto-accept the pairing without user interaction.

        Args:
            device: D-Bus object path of the device
            passkey: Numeric passkey (usually 0 for Just Works)
        """
        device_addr = self._format_device_path(device)
        self._log(f"Auto-confirming Just Works pairing for {device_addr} (passkey: {passkey})", "INFO")
        return  # Implicit success - pairing accepted!

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        """
        Return passkey for pairing (fallback)

        Not typically used with DisplayOnly, but implemented for completeness.

        Args:
            device: D-Bus object path of the device

        Returns:
            Passkey (0 for auto-accept)
        """
        device_addr = self._format_device_path(device)
        self._log(f"Passkey requested for {device_addr}, returning 0", "DEBUG")
        return dbus.UInt32(0)

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        """
        Handle pairing cancellation

        Called when pairing is cancelled by the remote device or timeout.
        """
        self._log("Pairing cancelled", "WARNING")

    # ========== Helper Methods ==========

    def _format_device_path(self, device_path: str) -> str:
        """
        Format D-Bus device path to readable address

        Args:
            device_path: D-Bus path like /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF

        Returns:
            MAC address like AA:BB:CC:DD:EE:FF
        """
        try:
            # Extract device part and convert underscores to colons
            if isinstance(device_path, str) and "dev_" in device_path:
                addr = device_path.split("dev_")[-1].replace("_", ":")
                return addr
            return str(device_path)
        except:
            return str(device_path)


def register_agent(capability: str = "NoInputNoOutput") -> Optional[BLEAgent]:
    """
    Register BLE agent with BlueZ for automatic pairing

    This function creates and registers a D-Bus agent that handles
    BLE pairing requests automatically. The agent capability determines
    which pairing method is used.

    Capabilities:
    -------------
    - NoInputNoOutput: Forces Just Works pairing without MITM (RECOMMENDED)
      * Auto-accepts pairing without user interaction
      * Avoids MITM (Man-In-The-Middle) protection requirements
      * Compatible with headless Linux-to-Linux connections
      * Suitable for IoT mesh devices

    - DisplayOnly: Alternative for Just Works with MITM capable devices
      * May request MITM protection which requires compatible central device

    Args:
        capability: Agent IO capability ("DisplayOnly" or "NoInputNoOutput")

    Returns:
        BLEAgent instance if successful, None if failed

    Raises:
        Exception: If D-Bus connection or agent registration fails
    """
    try:
        # Set up D-Bus main loop (required for agents)
        DBusGMainLoop(set_as_default=True)

        # Connect to system bus
        bus = dbus.SystemBus()

        # Create agent
        agent = BLEAgent(bus, capability)

        # Get AgentManager interface
        manager_obj = bus.get_object("org.bluez", "/org/bluez")
        manager = dbus.Interface(manager_obj, "org.bluez.AgentManager1")

        # Register agent with BlueZ
        manager.RegisterAgent(BLEAgent.AGENT_PATH, capability)

        # Request this agent to be the default
        manager.RequestDefaultAgent(BLEAgent.AGENT_PATH)

        agent._log(f"✓ Agent registered as default with capability: {capability}", "INFO")

        return agent

    except dbus.exceptions.DBusException as e:
        logger.error(f"D-Bus error registering agent: {e}")
        raise
    except Exception as e:
        logger.error(f"Failed to register agent: {type(e).__name__}: {e}")
        raise


def unregister_agent(agent: Optional[BLEAgent] = None):
    """
    Unregister BLE agent from BlueZ

    Args:
        agent: BLEAgent instance to unregister (can be None)
    """
    try:
        bus = dbus.SystemBus()
        manager_obj = bus.get_object("org.bluez", "/org/bluez")
        manager = dbus.Interface(manager_obj, "org.bluez.AgentManager1")

        # Unregister agent
        manager.UnregisterAgent(BLEAgent.AGENT_PATH)

        logger.info(f"Agent unregistered from BlueZ")

    except dbus.exceptions.DBusException as e:
        # Agent might not be registered, ignore
        logger.debug(f"Agent unregister warning: {e}")
    except Exception as e:
        logger.warning(f"Error unregistering agent: {e}")


# Convenience aliases
register_ble_agent = register_agent
unregister_ble_agent = unregister_agent
