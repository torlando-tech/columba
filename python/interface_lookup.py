"""
Interface Lookup for Columba

Resolves the receiving interface name for an announce by looking up RNS
internal tables. Checks announce_table first (populated when transport is
enabled), then falls back to path_table (always populated for every announce).

This fixes interface identification on non-transport nodes where the
announce_table is never populated by RNS.
"""
from typing import Optional
from logging_utils import log_debug


def format_interface_name(interface_obj) -> Optional[str]:
    """
    Format an RNS interface object into a "ClassName[UserConfiguredName]" string.

    Args:
        interface_obj: A Reticulum interface object, or None.

    Returns:
        Formatted string like "TCPInterface[Testnet/1.2.3.4:4242]", or None.
    """
    if not interface_obj:
        return None
    class_name = type(interface_obj).__name__
    user_name = getattr(interface_obj, 'name', None)
    if user_name and user_name != class_name:
        return f"{class_name}[{user_name}]"
    return class_name


def get_receiving_interface(destination_hash, announce_table=None, path_table=None) -> Optional[str]:
    """
    Extract the receiving interface name for an announce destination hash.

    Checks announce_table first (has the full packet, populated when transport
    is enabled), then falls back to path_table (always populated for every
    announce regardless of transport mode).

    Args:
        destination_hash: Raw bytes destination hash.
        announce_table: RNS.Transport.announce_table dict (or None).
        path_table: RNS.Transport.path_table dict (or None).

    Returns:
        Formatted interface name string, or None if unavailable.
    """
    try:
        # Try announce_table first (populated when transport is enabled)
        if announce_table is not None:
            announce_entry = announce_table.get(destination_hash)
            if announce_entry is not None and len(announce_entry) > 5:
                packet = announce_entry[5]  # IDX_AT_PACKET
                if packet and hasattr(packet, 'receiving_interface'):
                    name = format_interface_name(packet.receiving_interface)
                    if name:
                        return name

        # Fallback to path_table (always populated, stores interface directly at index 5)
        if path_table is not None:
            path_entry = path_table.get(destination_hash)
            if path_entry is not None and len(path_entry) > 5:
                interface_obj = path_entry[5]  # IDX_PT_RVCD_IF
                name = format_interface_name(interface_obj)
                if name:
                    return name

    except Exception as e:
        log_debug("InterfaceLookup", "get_receiving_interface",
                 f"Could not extract interface: {e}")

    return None
