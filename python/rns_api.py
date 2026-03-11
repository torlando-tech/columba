# rns_api.py — Thin pass-through to RNS/LXMF. NO business logic.
#
# Strangler Fig Phase 0: This is the seed file for migrating away from
# reticulum_wrapper.py. All new Python-facing functionality goes here
# as thin pass-throughs. Business logic goes in Kotlin.
from interface_lookup import format_interface_name


class RnsApi:
    def __init__(self, reticulum_instance=None, router=None):
        self._reticulum = reticulum_instance
        self._router = router

    def set_reticulum(self, reticulum_instance, router):
        self._reticulum = reticulum_instance
        self._router = router

    def get_next_hop_interface_name(self, dest_hash):
        """Return formatted interface name for next hop to destination, or None."""
        try:
            import RNS
            if RNS.Transport.has_path(dest_hash):
                iface = RNS.Transport.next_hop_interface(dest_hash)
                if iface is None:
                    return None
                return format_interface_name(iface)
        except Exception:
            pass
        return None

    def get_active_propagation_node_hash(self):
        """Return the currently set outbound propagation node hash, or None."""
        try:
            if self._router and hasattr(self._router, 'get_outbound_propagation_node'):
                return self._router.get_outbound_propagation_node()
        except Exception:
            pass
        return None
