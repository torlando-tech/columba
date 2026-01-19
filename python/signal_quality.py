"""
Signal Quality Extraction for Columba

Extracts RSSI and SNR from Reticulum interfaces at message delivery time.
RNode interfaces provide both metrics; BLE provides RSSI only (Android limitation).

These are interface-wide values (latest received), not per-packet, captured at
message delivery time for display in message detail screen.
"""
from typing import Optional, Tuple
from logging_utils import log_debug


def _extract_ble_peer_rssi(peer_interface) -> Optional[int]:
    """
    Extract RSSI from a BLEPeerInterface by querying its parent's driver.

    BLEPeerInterface is a per-peer sub-interface created by BLEInterface.
    It has peer_address and parent_interface attributes that let us
    query the Kotlin bridge for this specific peer's RSSI.

    Args:
        peer_interface: A BLEPeerInterface instance

    Returns:
        RSSI in dBm, or None if unavailable
    """
    try:
        # Get the peer's BLE address
        peer_address = getattr(peer_interface, 'peer_address', None)
        if not peer_address:
            log_debug("SignalQuality", "_extract_ble_peer_rssi",
                     "BLEPeerInterface has no peer_address")
            return None

        # Get the parent interface (AndroidBLEInterface)
        parent = getattr(peer_interface, 'parent_interface', None)
        if not parent:
            log_debug("SignalQuality", "_extract_ble_peer_rssi",
                     "BLEPeerInterface has no parent_interface")
            return None

        # Get the driver from parent
        driver = getattr(parent, 'driver', None)
        if not driver:
            log_debug("SignalQuality", "_extract_ble_peer_rssi",
                     "Parent interface has no driver")
            return None

        # Query RSSI for this peer
        rssi = driver.get_peer_rssi(peer_address)
        log_debug("SignalQuality", "_extract_ble_peer_rssi",
                 f"Got RSSI {rssi} for peer {peer_address}")
        return rssi

    except Exception as e:
        log_debug("SignalQuality", "_extract_ble_peer_rssi",
                 f"Failed to get BLE peer RSSI: {e}")
        return None


def extract_signal_metrics(interface_obj) -> Tuple[Optional[int], Optional[float]]:
    """
    Extract RSSI and SNR from a Reticulum interface object.

    Args:
        interface_obj: A Reticulum interface (RNodeInterface, AndroidBLEInterface, etc.)
                      Also handles BLEPeerInterface which has parent_interface.

    Returns:
        Tuple of (rssi_dbm: int or None, snr_db: float or None)
    """
    rssi = None
    snr = None

    if interface_obj is None:
        return rssi, snr

    # Handle BLEPeerInterface specially - it's a per-peer sub-interface
    # that has parent_interface pointing to the main AndroidBLEInterface
    interface_name = type(interface_obj).__name__
    if interface_name == 'BLEPeerInterface':
        rssi = _extract_ble_peer_rssi(interface_obj)
        if rssi is not None:
            log_debug("SignalQuality", "extract",
                     f"Got RSSI {rssi} dBm from BLEPeerInterface")
        # BLE doesn't have SNR
        return rssi, snr

    # Extract RSSI if interface supports it (RNode, BLE)
    if hasattr(interface_obj, 'get_rssi'):
        try:
            val = interface_obj.get_rssi()
            if val is not None:
                rssi = int(val)
                log_debug("SignalQuality", "extract",
                         f"Got RSSI {rssi} dBm from {type(interface_obj).__name__}")
        except Exception as e:
            log_debug("SignalQuality", "extract", f"Failed to get RSSI: {e}")

    # Extract SNR if interface supports it (RNode only - BLE doesn't have SNR)
    if hasattr(interface_obj, 'get_snr'):
        try:
            val = interface_obj.get_snr()
            if val is not None:
                snr = float(val)
                log_debug("SignalQuality", "extract",
                         f"Got SNR {snr} dB from {type(interface_obj).__name__}")
        except Exception as e:
            log_debug("SignalQuality", "extract", f"Failed to get SNR: {e}")

    return rssi, snr


def add_signal_to_message_event(
    message_event: dict,
    rssi: Optional[int],
    snr: Optional[float]
) -> None:
    """
    Add RSSI and SNR to a message event dict if values are available.

    Args:
        message_event: The message event dict to modify in-place
        rssi: RSSI in dBm (or None)
        snr: SNR in dB (or None)
    """
    if rssi is not None:
        message_event['rssi'] = rssi
    if snr is not None:
        message_event['snr'] = snr
