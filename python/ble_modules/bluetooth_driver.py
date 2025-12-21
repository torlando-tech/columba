
from abc import ABC, abstractmethod
from typing import List, Optional, Callable, Dict
from enum import Enum, auto
from dataclasses import dataclass, field

# --- Data Structures ---

@dataclass
class BLEDevice:
    """Represents a discovered BLE device."""
    address: str
    name: str
    rssi: int
    service_uuids: List[str] = field(default_factory=list)
    manufacturer_data: Dict[int, bytes] = field(default_factory=dict)

class DriverState(Enum):
    """Represents the state of the BLE driver."""
    IDLE = auto()
    SCANNING = auto()
    ADVERTISING = auto()
    # Note: More granular states like CONNECTING could be added if the
    # high-level logic requires them, but the list of connected peers
    # might be sufficient for most use cases.

# --- Driver Interface ---

class BLEDriverInterface(ABC):
    """
    Abstract interface for a platform-specific BLE driver.

    This contract separates the high-level Reticulum BLE interface logic
    from the low-level, platform-specific Bluetooth operations. It is designed
    to be implemented by different backend libraries (e.g., bleak/bluezero on Linux,
    or a Chaquopy-bridged Kotlin implementation on Android).

    The driver is responsible for managing the actual BLE connections, but it
    reports events asynchronously via the provided callbacks.
    """

    # --- Callbacks ---
    # The consumer of this driver (e.g., a high-level BLEInterface) must
    # implement and assign these callbacks to receive events from the driver.

    on_device_discovered: Optional[Callable[[BLEDevice], None]] = None
    on_device_connected: Optional[Callable[[str, Optional[bytes]], None]] = None  # address, peer_identity (None for peripheral role)
    on_device_disconnected: Optional[Callable[[str], None]] = None  # address
    on_data_received: Optional[Callable[[str, bytes], None]] = None  # address, data
    on_mtu_negotiated: Optional[Callable[[str, int], None]] = None  # address, mtu
    on_error: Optional[Callable[[str, str, Optional[Exception]], None]] = None  # severity, message, exception
    on_address_changed: Optional[Callable[[str, str, str], None]] = None  # old_address, new_address, identity_hash

    # --- Lifecycle & Configuration ---

    @abstractmethod
    def start(self, service_uuid: str, rx_char_uuid: str, tx_char_uuid: str, identity_char_uuid: str):
        """
        Initializes the driver and its underlying BLE stack. This includes
        setting up the GATT server characteristics required for the peripheral role.
        This method should be called before any other operations.
        """
        pass

    @abstractmethod
    def stop(self):
        """
        Stops all BLE activity (scanning, advertising, connections) and releases all
        underlying system resources.
        """
        pass

    @abstractmethod
    def set_identity(self, identity_bytes: bytes):
        """
        Sets the value of the read-only Identity characteristic for the local GATT server.
        This must be called before starting advertising.
        """
        pass

    # --- State & Properties ---

    @property
    @abstractmethod
    def state(self) -> DriverState:
        """Returns the current operational state of the driver."""
        pass

    @property
    @abstractmethod
    def connected_peers(self) -> List[str]:
        """Returns a list of MAC addresses for all currently connected peers."""
        pass

    # --- Core Actions ---

    @abstractmethod
    def start_scanning(self):
        """
        Starts scanning for devices advertising the configured service UUID.
        Discovered devices will be reported via the on_device_discovered callback.
        """
        pass

    @abstractmethod
    def stop_scanning(self):
        """Stops scanning for devices."""
        pass

    @abstractmethod
    def start_advertising(self, device_name: Optional[str], identity: bytes):
        """
        Starts advertising the configured service UUID and optionally a device name.
        The identity parameter is used to populate the Identity characteristic.

        Args:
            device_name: Optional device name to include in advertisement (None to omit).
                        Keep short (max 8 chars) to fit in 31-byte BLE advertisement packet.
            identity: 16-byte identity hash for the Identity characteristic.
        """
        pass

    @abstractmethod
    def stop_advertising(self):
        """Stops advertising."""
        pass

    @abstractmethod
    def connect(self, address: str):
        """
        Initiates a connection to a peer device (central role).
        Connection status is reported via on_device_connected/on_device_disconnected.
        """
        pass

    @abstractmethod
    def disconnect(self, address: str):
        """Disconnects from a peer device."""
        pass

    @abstractmethod
    def send(self, address: str, data: bytes):
        """
        Sends data to a connected peer.

        The driver implementation is responsible for choosing the correct underlying BLE
        operation (GATT Write for central role, or Notification for peripheral role)
        based on the current connection type for the given address. This method
        should ideally block or be awaitable until the send operation is confirmed
        by the BLE stack to ensure sequential transmission.
        """
        pass

    # --- GATT Characteristic Operations ---

    @abstractmethod
    def read_characteristic(self, address: str, char_uuid: str) -> bytes:
        """
        Reads a GATT characteristic value from a connected peer.
        Raises an exception if the operation fails.
        """
        pass

    @abstractmethod
    def write_characteristic(self, address: str, char_uuid: str, data: bytes):
        """
        Writes a value to a GATT characteristic on a connected peer.
        Raises an exception if the operation fails.
        """
        pass

    @abstractmethod
    def start_notify(self, address: str, char_uuid: str, callback: Callable[[bytes], None]):
        """
        Subscribes to notifications from a GATT characteristic on a connected peer.
        The callback will be invoked whenever a notification is received.
        """
        pass

    # --- Configuration & Queries ---

    @abstractmethod
    def get_local_address(self) -> str:
        """
        Returns the MAC address of the local Bluetooth adapter.
        Used for connection direction determination (MAC sorting).
        """
        pass

    @abstractmethod
    def get_peer_role(self, address: str) -> Optional[str]:
        """
        Returns the connection role for a connected peer.

        Args:
            address: The MAC address of the peer.

        Returns:
            A string ('central' or 'peripheral') or None if not connected.
        """
        pass

    @abstractmethod
    def set_service_discovery_delay(self, seconds: float):
        """
        Sets the delay between connection establishment and service discovery.
        This is a workaround for bluezero D-Bus registration timing issues.
        """
        pass

    @abstractmethod
    def set_power_mode(self, mode: str):
        """
        Sets the power mode for scanning operations.
        Valid modes: "aggressive", "balanced", "saver"
        """
        pass
