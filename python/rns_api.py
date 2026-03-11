# rns_api.py — Thin pass-through to RNS/LXMF. NO business logic.
#
# Strangler Fig Phase 0: This is the seed file for migrating away from
# reticulum_wrapper.py. All new Python-facing functionality goes here
# as thin pass-throughs. Business logic goes in Kotlin.
from interface_lookup import format_interface_name
from logging_utils import log_debug


class RnsApi:
    def __init__(self):
        pass

    def get_next_hop_interface_name(self, dest_hash):
        """Return formatted interface name for next hop to destination, or None."""
        try:
            import RNS
            # Convert Chaquopy jarray to Python bytes for RNS dict key lookups
            if not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if RNS.Transport.has_path(dest_hash):
                iface = RNS.Transport.next_hop_interface(dest_hash)
                if iface is None:
                    return None
                return format_interface_name(iface)
        except Exception as e:
            log_debug("RnsApi", "get_next_hop_interface_name", f"lookup failed: {e}")
        return None
