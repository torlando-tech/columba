# rns_api.py — Thin pass-through to RNS/LXMF. NO business logic.
#
# Strangler Fig Phase 0: This is the seed file for migrating away from
# reticulum_wrapper.py. All new Python-facing functionality goes here
# as thin pass-throughs. Business logic goes in Kotlin.
from collections import deque
import threading
import time

from interface_lookup import format_interface_name
from logging_utils import log_debug, log_info, log_warning, log_error


class RnsApi:
    MAX_IDENTIFIED_LINKS = 200
    MAX_CACHED_LINKS = 8  # Live RNS.Link objects hold buffers; keep this small

    def __init__(self):
        self._cancel_flag = False
        self._request_status = ""
        self._identified_links = deque(maxlen=self.MAX_IDENTIFIED_LINKS)

    def get_request_status(self):
        """Return current request phase status for UI display."""
        return self._request_status

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

    # ===========================================
    # NomadNet Page Browser
    # ===========================================

    def _get_wrapper(self):
        """Get the global ReticulumWrapper instance."""
        import reticulum_wrapper
        return reticulum_wrapper._global_wrapper_instance

    def _ensure_link_cache(self, wrapper):
        """Ensure the wrapper has a link cache dict."""
        if not hasattr(wrapper, '_nomadnet_links'):
            wrapper._nomadnet_links = {}

    def _evict_cached_link(self, dest_hash_hex):
        """Remove a cached link after failure/timeout so next request gets a fresh one."""
        wrapper = self._get_wrapper()
        if wrapper and hasattr(wrapper, '_nomadnet_links'):
            old_link = wrapper._nomadnet_links.pop(dest_hash_hex, None)
            if old_link is not None:
                log_info("RnsApi", "request_nomadnet_page",
                         f"Evicted stale link to {dest_hash_hex[:16]}")
                try:
                    old_link.teardown()
                except Exception:
                    pass

    def get_download_progress(self):
        """Return current file download progress (0.0-1.0), or -1.0 if idle."""
        return getattr(self, '_download_progress', -1.0)

    def request_nomadnet_page(self, dest_hash, path="/page/index.mu",
                              form_data_json=None, timeout_seconds=45.0,
                              download_dir=None):
        """
        Request a page from a NomadNet node.

        Creates or reuses a link to the nomadnetwork.node destination,
        then sends a page request over that link.

        Args:
            dest_hash: Destination hash as bytes (16 bytes)
            path: Page path (e.g., "/page/index.mu")
            form_data_json: Optional JSON string of form field values
            timeout_seconds: Total timeout for the operation

        Returns:
            Dict with:
            - success: True if page was received
            - content: Page content as UTF-8 string
            - path: Requested path
            - error: Error message (if failed)
        """
        import RNS

        wrapper = self._get_wrapper()
        if not wrapper or not wrapper.router:
            return {"success": False, "error": "Not initialized"}

        def _status(msg):
            self._request_status = msg

        try:
            dest_hash = bytes(dest_hash)
            dest_hash_hex = dest_hash.hex()
            # Sanitize path — strip any colon prefix that wasn't handled by the caller
            if path and path.startswith(":"):
                path = path[1:]
            if not path or not path.startswith("/"):
                path = "/page/index.mu"
            log_info("RnsApi", "request_nomadnet_page",
                     f"Requesting page {path} from {dest_hash_hex[:16]}...")

            # Use absolute deadline — each phase gets whatever time remains
            deadline = time.time() + timeout_seconds

            self._ensure_link_cache(wrapper)

            # Reset cancellation flag
            self._cancel_flag = False

            # Parse form data if provided
            request_data = None
            if form_data_json:
                try:
                    import json
                    raw_data = json.loads(form_data_json)
                    if isinstance(raw_data, dict) and len(raw_data) > 0:
                        # Prefix field names with "field_" for NomadNet convention
                        request_data = {}
                        for key, value in raw_data.items():
                            if not key.startswith("field_") and not key.startswith("var_"):
                                request_data["field_" + key] = value
                            else:
                                request_data[key] = value
                except Exception as e:
                    log_warning("RnsApi", "request_nomadnet_page",
                               f"Failed to parse form data: {e}")

            # Check for existing cached link FIRST — avoids identity recall
            # when we already have an active connection to the node
            link = wrapper._nomadnet_links.get(dest_hash_hex)
            if link is not None and link.status != RNS.Link.ACTIVE:
                # Stale link, remove it
                log_debug("RnsApi", "request_nomadnet_page",
                         f"Stale link to {dest_hash_hex[:16]}, will re-establish")
                wrapper._nomadnet_links.pop(dest_hash_hex, None)
                link = None

            # Only recall identity and establish link if no cached active link
            if link is not None:
                _status("Reusing connection...")
                log_info("RnsApi", "request_nomadnet_page",
                         f"Reusing cached active link to {dest_hash_hex[:16]}")

            if link is None:
                link = self._establish_link(wrapper, dest_hash, dest_hash_hex, deadline, _status)
                if isinstance(link, dict):
                    return link  # Error dict

                # Cache the link, evicting oldest if at capacity
                if len(wrapper._nomadnet_links) >= self.MAX_CACHED_LINKS:
                    oldest_key = next(iter(wrapper._nomadnet_links))
                    old_link = wrapper._nomadnet_links.pop(oldest_key, None)
                    if old_link is not None:
                        try:
                            old_link.teardown()
                        except Exception:
                            pass
                wrapper._nomadnet_links[dest_hash_hex] = link

            # Make page request over the link
            _status("Requesting page...")
            return self._send_page_request(link, path, request_data, dest_hash_hex, deadline,
                                           download_dir=download_dir)

        except Exception as e:
            log_error("RnsApi", "request_nomadnet_page", f"Error: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}
        finally:
            self._request_status = ""

    def _establish_link(self, wrapper, dest_hash, dest_hash_hex, deadline, _status=None):
        """Establish a new RNS link to a NomadNet node.

        Matches NomadNet TUI's proven sequence (Browser.py __load):
        path first → identity recall → destination → link.
        Always uses the original destination hash from the caller,
        never a recomputed one.

        Returns an active RNS.Link on success, or an error dict on failure.
        """
        import RNS

        if _status is None:
            _status = lambda msg: None

        # ── Phase 1: Ensure path ──
        # Match NomadNet TUI: check has_path FIRST, request only if missing.
        # The path response is a cached announce that populates BOTH the
        # path table AND known_destinations, so identity recall after this
        # is guaranteed to succeed.
        if not RNS.Transport.has_path(dest_hash):
            _status("Looking up path...")
            log_info("RnsApi", "request_nomadnet_page",
                     f"No path to {dest_hash_hex[:16]}, requesting... "
                     f"(hash_len={len(dest_hash)}, "
                     f"interfaces={len(RNS.Transport.interfaces)}, "
                     f"is_connected={RNS.Reticulum.get_instance().is_connected_to_shared_instance})")
            RNS.Transport.request_path(dest_hash)

            path_timeout = min(
                RNS.Transport.first_hop_timeout(dest_hash) + 15,
                deadline - time.time() - 10,
            )
            path_deadline = time.time() + max(path_timeout, 5)
            log_debug("RnsApi", "request_nomadnet_page",
                     f"Waiting {path_timeout:.0f}s for path (first_hop_timeout="
                     f"{RNS.Transport.first_hop_timeout(dest_hash):.1f}s)")
            while not RNS.Transport.has_path(dest_hash) and time.time() < path_deadline:
                if self._cancel_flag:
                    return {"success": False, "error": "Cancelled"}
                time.sleep(0.25)

            if not RNS.Transport.has_path(dest_hash):
                return {"success": False, "error": "No path to node. It may be offline or unreachable."}

        hops = RNS.Transport.hops_to(dest_hash)
        _status(f"Path found ({hops} hops)")
        log_info("RnsApi", "request_nomadnet_page",
                 f"Path to {dest_hash_hex[:16]} available (hops={hops})")

        # ── Phase 2: Recall identity ──
        # After path arrival the identity is in known_destinations.
        # Fallbacks mirror the old code for edge cases (identity hash
        # lookup, wrapper cache).
        recipient_identity = RNS.Identity.recall(dest_hash)
        if not recipient_identity:
            recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
        if not recipient_identity and dest_hash_hex in wrapper.identities:
            recipient_identity = wrapper.identities[dest_hash_hex]

        if not recipient_identity:
            return {"success": False, "error": "Path available but identity unknown. The node may use a different address format."}

        # ── Phase 3: Create destination and verify hash ──
        node_dest = RNS.Destination(
            recipient_identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "nomadnetwork",
            "node"
        )

        if node_dest.hash != dest_hash:
            log_warning("RnsApi", "request_nomadnet_page",
                       f"Destination hash mismatch! passed={dest_hash_hex} computed={node_dest.hash.hex()}")

        # ── Phase 4: Establish link ──
        _status(f"Connecting ({hops} hops)...")
        log_info("RnsApi", "request_nomadnet_page",
                 f"Creating link to {dest_hash_hex[:16]} (hops={hops})")

        link_established = threading.Event()
        link_closed_reason = [None]

        def on_link_established(established_link):
            log_info("RnsApi", "request_nomadnet_page",
                     f"Link established to {dest_hash_hex[:16]} (RTT={established_link.rtt})")
            link_established.set()

        def on_link_closed(closed_link):
            reason = getattr(closed_link, 'teardown_reason', None)
            reason_str = {0x01: "TIMEOUT", 0x02: "INITIATOR_CLOSED", 0x03: "DESTINATION_CLOSED"}.get(reason, str(reason))
            log_warning("RnsApi", "request_nomadnet_page",
                       f"Link to {dest_hash_hex[:16]} closed during establishment (reason={reason_str}, status={closed_link.status})")
            link_closed_reason[0] = reason
            link_established.set()

        link = RNS.Link(node_dest,
                        established_callback=on_link_established,
                        closed_callback=on_link_closed)

        est_timeout = getattr(link, 'establishment_timeout', None)
        log_info("RnsApi", "request_nomadnet_page",
                 f"Link establishment_timeout={est_timeout:.1f}s" if est_timeout else "Link establishment_timeout=unknown")

        link_wait = max(deadline - time.time() - 10, 5.0)
        log_debug("RnsApi", "request_nomadnet_page",
                 f"Waiting up to {link_wait:.0f}s for link to {dest_hash_hex[:16]}")
        link_established.wait(timeout=link_wait)

        if self._cancel_flag:
            try:
                link.teardown()
            except Exception:
                pass
            return {"success": False, "error": "Cancelled"}

        if link_closed_reason[0] is not None or link.status != RNS.Link.ACTIVE:
            status_str = str(link.status) if link else "unknown"
            reason = link_closed_reason[0]
            log_warning("RnsApi", "request_nomadnet_page",
                       f"Link to {dest_hash_hex[:16]} failed (status={status_str}, teardown_reason={reason})")
            try:
                link.teardown()
            except Exception:
                pass
            if reason == 0x03:
                return {"success": False, "error": "Connection closed by node"}
            elif reason == 0x01 or link.status == RNS.Link.PENDING:
                return {"success": False, "error": f"Connection timed out ({hops} hops). Node may be offline or unreachable."}
            else:
                return {"success": False, "error": f"Connection failed (status={status_str})"}

        return link

    def _send_page_request(self, link, path, request_data, dest_hash_hex, deadline,
                           download_dir=None):
        """Send a page request over an established link.

        Returns a result dict with success/content/error.
        """
        response_event = threading.Event()
        response_data = [None]
        response_error = [None]
        response_metadata = [None]

        def response_received(request_receipt):
            try:
                response_metadata[0] = request_receipt.metadata
                if request_receipt.metadata:
                    # File response — read data NOW while handle is still open
                    # (RNS closes the handle after this callback returns)
                    raw = request_receipt.response
                    if hasattr(raw, 'read'):
                        response_data[0] = raw.read()
                        raw.close()
                    else:
                        response_data[0] = bytes(raw) if raw else b""
                    log_info("RnsApi", "request_nomadnet_page",
                             f"File response received for {path} ({len(response_data[0])} bytes)")
                else:
                    response_data[0] = request_receipt.response
                    log_info("RnsApi", "request_nomadnet_page",
                             f"Page received: {len(response_data[0])} bytes")
            except Exception as e:
                response_error[0] = str(e)
            response_event.set()

        def request_failed(request_receipt=None):
            response_error[0] = "Request failed"
            log_warning("RnsApi", "request_nomadnet_page",
                       f"Page request failed for {path}")
            response_event.set()

        self._download_progress = -1.0

        def progress_update(request_receipt):
            self._download_progress = request_receipt.progress

        log_debug("RnsApi", "request_nomadnet_page",
                 f"Sending request for path: {path}")

        receipt = link.request(
            path,
            data=request_data,
            response_callback=response_received,
            failed_callback=request_failed,
            progress_callback=progress_update,
        )

        if not receipt:
            log_warning("RnsApi", "request_nomadnet_page",
                       f"link.request() returned False for {path}")
            self._evict_cached_link(dest_hash_hex)
            return {"success": False, "error": "Request could not be sent"}

        # Wait for response, polling cancel flag every 0.5s
        while not response_event.is_set() and time.time() < deadline:
            if self._cancel_flag:
                try:
                    receipt.cancel()
                except Exception:
                    pass
                return {"success": False, "error": "Cancelled"}
            response_event.wait(timeout=0.5)

        if response_error[0]:
            self._evict_cached_link(dest_hash_hex)
            return {"success": False, "error": response_error[0]}

        if response_data[0] is None:
            log_warning("RnsApi", "request_nomadnet_page",
                       f"Page request timed out for {path} on {dest_hash_hex[:16]}")
            self._evict_cached_link(dest_hash_hex)
            return {"success": False, "error": "Page request timed out"}

        # Check if this is a file response (has metadata with filename)
        if response_metadata[0] is not None:
            return self._save_file_response(
                response_data[0], response_metadata[0], path, download_dir)

        # Decode response
        try:
            content = response_data[0].decode("utf-8")
        except Exception:
            content = str(response_data[0])

        return {
            "success": True,
            "type": "page",
            "content": content,
            "path": path
        }

    def _save_file_response(self, file_data, metadata, path, download_dir):
        """Save a file response (already read as bytes) to disk and return result dict."""
        import os

        name_raw = metadata.get("name", b"download") if isinstance(metadata, dict) else b"download"
        if isinstance(name_raw, bytes):
            filename = name_raw.decode("utf-8", errors="replace")
        else:
            filename = str(name_raw)

        # Sanitize filename to prevent path traversal
        filename = os.path.basename(filename)
        if not filename:
            filename = "download"

        if not download_dir:
            download_dir = "/tmp/nomadnet_downloads"
        os.makedirs(download_dir, exist_ok=True)
        save_path = os.path.join(download_dir, filename)

        try:
            with open(save_path, "wb") as out_file:
                out_file.write(file_data)

            file_size = os.path.getsize(save_path)
            log_info("RnsApi", "request_nomadnet_page",
                     f"File saved: {filename} ({file_size} bytes)")

            return {
                "success": True,
                "type": "file",
                "file_path": save_path,
                "file_name": filename,
                "file_size": file_size,
                "path": path,
            }
        except Exception as e:
            log_error("RnsApi", "request_nomadnet_page",
                     f"Failed to save file {filename}: {e}")
            return {"success": False, "error": f"Failed to save file: {e}"}

    def cancel_nomadnet_page_request(self):
        """Set cancellation flag for any in-progress NomadNet page request."""
        self._cancel_flag = True

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
        link_id_hex = link.link_id.hex() if link.link_id else None
        if link_id_hex and link_id_hex in self._identified_links:
            return {"success": True, "already_identified": True}

        link.identify(wrapper.router.identity)

        if link_id_hex:
            self._identified_links.append(link_id_hex)
        return {"success": True, "already_identified": False}
