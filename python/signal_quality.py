"""
Signal Quality Extraction for Columba

Extracts RSSI and SNR from Reticulum interfaces at message delivery time.
RNode interfaces provide both metrics; BLE provides RSSI only (Android limitation).

These are interface-wide values (latest received), not per-packet, captured at
message delivery time for display in message detail screen.
"""
from typing import Optional, Tuple
from logging_utils import log_debug


def extract_signal_metrics(interface_obj) -> Tuple[Optional[int], Optional[float]]:
    """
    Extract RSSI and SNR from a Reticulum interface object.

    Args:
        interface_obj: A Reticulum interface (RNodeInterface, AndroidBLEInterface, etc.)

    Returns:
        Tuple of (rssi_dbm: int or None, snr_db: float or None)
    """
    rssi = None
    snr = None

    if interface_obj is None:
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
