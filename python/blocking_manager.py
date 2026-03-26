"""
Peer Blocking & Blackhole Manager for Columba.

Handles two distinct layers of protection:
  1. Block (LXMF-level): Silently drops incoming messages from a peer
     via LXMRouter.ignore_destination(). In-memory only — Kotlin DB
     is the source of truth and restores at startup.
  2. Blackhole (Reticulum Transport-level): Drops path entries and
     invalidates announces for a peer's identity. Reticulum handles
     persistence, cleanup, and remote blackhole list sharing natively.

Instantiated lazily by ReticulumWrapper._get_blocking_manager().
"""

from logging_utils import log_info, log_error

try:
    import RNS
    RNS_AVAILABLE = True
except ImportError:
    RNS_AVAILABLE = False


class BlockingManager:
    def __init__(self, router, reticulum):
        """
        Args:
            router: LXMF.LXMRouter instance (for ignore_destination)
            reticulum: RNS.Reticulum instance (for blackhole_identity)
        """
        self.router = router
        self.reticulum = reticulum

    # ── LXMF Block (destination hash) ────────────────────────────────

    def block_destination(self, destination_hash_hex):
        """Block a destination via LXMRouter ignore list."""
        try:
            if self.router is None:
                return {"success": False, "error": "Router not initialized"}
            dest_hash = bytes.fromhex(destination_hash_hex)
            self.router.ignore_destination(dest_hash)
            log_info("BlockingManager", "block_destination",
                     f"Blocked LXMF destination: {destination_hash_hex[:16]}")
            return {"success": True}
        except Exception as e:
            log_error("BlockingManager", "block_destination", f"Error: {e}")
            return {"success": False, "error": str(e)}

    def unblock_destination(self, destination_hash_hex):
        """Remove a destination from LXMRouter ignore list."""
        try:
            if self.router is None:
                return {"success": False, "error": "Router not initialized"}
            dest_hash = bytes.fromhex(destination_hash_hex)
            self.router.unignore_destination(dest_hash)
            log_info("BlockingManager", "unblock_destination",
                     f"Unblocked LXMF destination: {destination_hash_hex[:16]}")
            return {"success": True}
        except Exception as e:
            log_error("BlockingManager", "unblock_destination", f"Error: {e}")
            return {"success": False, "error": str(e)}

    def restore_blocked_destinations(self, hashes_list):
        """Restore LXMF blocked destinations from Kotlin DB at startup."""
        if self.router is None:
            return {"success": False, "error": "Router not initialized"}
        count = 0
        errors = 0
        for hex_hash in hashes_list:
            try:
                dest_hash = bytes.fromhex(hex_hash)
                self.router.ignore_destination(dest_hash)
                count += 1
            except Exception as e:
                log_error("BlockingManager", "restore_blocked_destinations",
                          f"Failed to restore hash {hex_hash[:16]}: {e}")
                errors += 1
        log_info("BlockingManager", "restore_blocked_destinations",
                 f"Restored {count} LXMF block(s), {errors} error(s)")
        return {"success": True, "restored_count": count, "errors": errors}

    # ── Reticulum Blackhole (identity hash) ──────────────────────────

    def blackhole_identity(self, identity_hash_hex):
        """Blackhole an identity at the Reticulum transport level.
        Reticulum handles: persistence, path removal, announce filtering, cleanup."""
        try:
            if self.reticulum is None:
                return {"success": False, "error": "Reticulum not initialized"}
            identity_hash = bytes.fromhex(identity_hash_hex)
            result = self.reticulum.blackhole_identity(identity_hash)
            success = result is True
            log_info("BlockingManager", "blackhole_identity",
                     f"Blackholed identity: {identity_hash_hex[:16]} (result={result})")
            return {"success": success, "result": str(result)}
        except Exception as e:
            log_error("BlockingManager", "blackhole_identity", f"Error: {e}")
            return {"success": False, "error": str(e)}

    def unblackhole_identity(self, identity_hash_hex):
        """Lift blackhole for an identity."""
        try:
            if self.reticulum is None:
                return {"success": False, "error": "Reticulum not initialized"}
            identity_hash = bytes.fromhex(identity_hash_hex)
            result = self.reticulum.unblackhole_identity(identity_hash)
            success = result is True
            log_info("BlockingManager", "unblackhole_identity",
                     f"Unblackholed identity: {identity_hash_hex[:16]} (result={result})")
            return {"success": success, "result": str(result)}
        except Exception as e:
            log_error("BlockingManager", "unblackhole_identity", f"Error: {e}")
            return {"success": False, "error": str(e)}

    # ── Transport Status ─────────────────────────────────────────────

    def is_transport_enabled(self):
        """Check if Reticulum transport mode is currently enabled."""
        try:
            if not RNS_AVAILABLE:
                return {"success": False, "error": "RNS not available"}
            return {"success": True, "enabled": RNS.Reticulum.transport_enabled()}
        except Exception as e:
            log_error("BlockingManager", "is_transport_enabled", f"Error: {e}")
            return {"success": False, "error": str(e)}
