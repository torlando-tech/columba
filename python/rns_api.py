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

    def identify_nomadnet_link(self, dest_hash):
        """Identify ourselves on an existing NomadNet link (thin pass-through)."""
        import reticulum_wrapper
        import RNS

        if not isinstance(dest_hash, (bytes, bytearray)):
            dest_hash = bytes(dest_hash)
        dest_hash_hex = dest_hash.hex()

        wrapper = reticulum_wrapper._global_wrapper_instance
        if not wrapper:
            return {"success": False, "error": "Wrapper not initialized"}

        if not hasattr(wrapper, '_nomadnet_links'):
            return {"success": False, "error": "No active connections"}

        link = wrapper._nomadnet_links.get(dest_hash_hex)
        if link is None or link.status != RNS.Link.ACTIVE:
            if link is not None:
                wrapper._nomadnet_links.pop(dest_hash_hex, None)
            return {"success": False, "error": "No active link to this node. Load a page first."}

        if not wrapper.router or not wrapper.router.identity:
            return {"success": False, "error": "No local identity available"}

        # Track already-identified links by link_id
        if not hasattr(self, '_identified_links'):
            self._identified_links = set()
        link_id_hex = link.link_id.hex() if link.link_id else None
        if link_id_hex and link_id_hex in self._identified_links:
            return {"success": True, "already_identified": True}

        link.identify(wrapper.router.identity)

        if link_id_hex:
            self._identified_links.add(link_id_hex)
        return {"success": True, "already_identified": False}
