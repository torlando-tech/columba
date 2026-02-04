"""
LXMF Wrapper for Columba Android App

This module provides a Python interface to LXMF (Lightweight Extensible Message Format)
for use in the Columba Android messenger via Chaquopy.

Usage from Kotlin:
    val python = Python.getInstance()
    val lxmfModule = python.getModule("lxmf_wrapper")
    val result = lxmfModule.callAttr("initialize_lxmf", storagePath, identityPath, rnsConfig, displayName)

Author: Columba Development
Version: 1.0.0
"""

import json
import os
import threading
import time
from typing import Optional, Dict, Any, List, Callable

try:
    import RNS
    import LXMF
except ImportError:
    print("ERROR: LXMF/RNS not installed. Run: pip install lxmf")
    raise


# ============================================================================
# Event Emitter
# ============================================================================

class EventEmitter:
    """Thread-safe event emitter for Python→Kotlin communication"""

    def __init__(self):
        self.kotlin_callback: Optional[Callable] = None
        self._lock = threading.Lock()

    def set_callback(self, callback: Callable[[str], None]):
        """Set Kotlin callback function (called from Kotlin)"""
        with self._lock:
            self.kotlin_callback = callback

    def emit(self, event_type: str, data: Dict[str, Any]):
        """Emit JSON event to Kotlin layer"""
        with self._lock:
            if self.kotlin_callback:
                try:
                    event = {
                        "type": event_type,
                        "timestamp": time.time(),
                        **data
                    }
                    self.kotlin_callback(json.dumps(event))
                except Exception as e:
                    print(f"ERROR emitting event: {e}")


# Global emitter
_emitter = EventEmitter()


def set_event_callback(callback):
    """
    Called from Kotlin to register event callback.

    Args:
        callback: Python callable that accepts a single string (JSON event)
    """
    _emitter.set_callback(callback)


# ============================================================================
# LXMF Wrapper
# ============================================================================

class LXMFWrapper:
    """Main LXMF wrapper handling messaging operations"""

    def __init__(self, storage_path: str, identity_path: str):
        self.storage_path = storage_path
        self.identity_path = identity_path
        self.identity: Optional[RNS.Identity] = None
        self.message_router: Optional[LXMF.LXMRouter] = None
        self.lxmf_destination: Optional[RNS.Destination] = None
        self._lock = threading.RLock()
        self._pending_messages: Dict[str, LXMF.LXMessage] = {}

    def initialize(self, rns_config_path: str, display_name: str,
                   stamp_cost: int = 0, delivery_limit: int = 128 * 1000) -> Dict[str, Any]:
        """
        Initialize Reticulum and LXMF router.

        Args:
            rns_config_path: Path to Reticulum config directory
            display_name: Display name for this LXMF destination
            stamp_cost: Computational stamp cost (0-255, 0=disabled)
            delivery_limit: Max message size in bytes (default 128KB)

        Returns:
            Dict with success flag, destination_hash, identity_hash, or error
        """
        with self._lock:
            try:
                # Ensure directories exist
                os.makedirs(os.path.dirname(self.identity_path), exist_ok=True)
                os.makedirs(self.storage_path, exist_ok=True)
                os.makedirs(rns_config_path, exist_ok=True)

                # Initialize Reticulum
                RNS.Reticulum(configdir=rns_config_path)

                # Load or create identity
                if os.path.exists(self.identity_path):
                    print(f"Loading existing identity from {self.identity_path}")
                    self.identity = RNS.Identity.from_file(self.identity_path)
                else:
                    print(f"Creating new identity at {self.identity_path}")
                    self.identity = RNS.Identity()
                    self.identity.to_file(self.identity_path)

                # Initialize LXMF router
                self.message_router = LXMF.LXMRouter(
                    identity=self.identity,
                    storagepath=self.storage_path,
                    autopeer=True,
                    delivery_limit=delivery_limit
                )

                # Register delivery callback for incoming messages
                self.message_router.register_delivery_callback(self._on_message_received)

                # Register delivery identity
                self.lxmf_destination = self.message_router.register_delivery_identity(
                    self.identity,
                    display_name=display_name,
                    stamp_cost=stamp_cost if stamp_cost > 0 else None
                )

                print(f"LXMF initialized. Destination: {self.lxmf_destination.hash.hex()}")

                return {
                    "success": True,
                    "destinationHash": self.lxmf_destination.hash.hex(),
                    "identityHash": self.identity.hash.hex()
                }

            except Exception as e:
                print(f"ERROR initializing LXMF: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "INITIALIZATION_FAILED"
                }

    def send_message(self, dest_hash: str, content: str,
                     title: str = "", method: int = 2,
                     fields: Optional[Dict] = None,
                     include_ticket: bool = False) -> Dict[str, Any]:
        """
        Send LXMF message.

        Args:
            dest_hash: Destination hash (hex string)
            content: Message content (UTF-8 string)
            title: Optional message title
            method: Delivery method (1=OPPORTUNISTIC, 2=DIRECT, 3=PROPAGATED)
            fields: Optional fields dictionary
            include_ticket: Include delivery ticket for recipient

        Returns:
            Dict with success, messageId, packedMessage, timestamp, state, or error
        """
        with self._lock:
            try:
                if not self.message_router:
                    return {
                        "success": False,
                        "error": "LXMF not initialized",
                        "errorCode": "NOT_INITIALIZED"
                    }

                # Convert hex hash to bytes
                try:
                    dest_hash_bytes = bytes.fromhex(dest_hash)
                except ValueError:
                    return {
                        "success": False,
                        "error": "Invalid destination hash format",
                        "errorCode": "INVALID_DESTINATION_HASH"
                    }

                # Recall destination identity
                dest_identity = RNS.Identity.recall(dest_hash_bytes)
                if not dest_identity:
                    # Request path if we don't have it
                    RNS.Transport.request_path(dest_hash_bytes)
                    return {
                        "success": False,
                        "error": "Unknown destination. Path requested.",
                        "errorCode": "UNKNOWN_DESTINATION"
                    }

                # Create destination
                dest = RNS.Destination(
                    dest_identity,
                    RNS.Destination.OUT,
                    RNS.Destination.SINGLE,
                    "lxmf", "delivery"
                )

                # Map method
                if method == 1:
                    desired_method = LXMF.LXMessage.OPPORTUNISTIC
                elif method == 2:
                    desired_method = LXMF.LXMessage.DIRECT
                elif method == 3:
                    desired_method = LXMF.LXMessage.PROPAGATED
                else:
                    return {
                        "success": False,
                        "error": f"Invalid method: {method}",
                        "errorCode": "INVALID_METHOD"
                    }

                # Create message
                lxm = LXMF.LXMessage(
                    dest,
                    self.lxmf_destination,
                    content,
                    title=title,
                    desired_method=desired_method,
                    fields=fields or {},
                    include_ticket=include_ticket
                )

                # For direct messages, enable fallback to propagation if configured
                if method == 2 and self.message_router.get_outbound_propagation_node():
                    lxm.try_propagation_on_fail = True

                # Store message reference
                msg_id = lxm.hash.hex()
                self._pending_messages[msg_id] = lxm

                # Register callbacks
                lxm.register_delivery_callback(
                    lambda msg: self._on_delivery_confirmed(msg_id, msg)
                )
                lxm.register_failed_callback(
                    lambda msg: self._on_delivery_failed(msg_id, msg)
                )

                # Send via router
                self.message_router.handle_outbound(lxm)

                # Pack for storage
                lxm.pack()

                print(f"Message sent: {msg_id[:8]}... to {dest_hash[:8]}...")

                return {
                    "success": True,
                    "messageId": msg_id,
                    "packedMessage": lxm.packed.hex(),
                    "timestamp": lxm.timestamp,
                    "state": lxm.state
                }

            except Exception as e:
                print(f"ERROR sending message: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "SEND_FAILED"
                }

    def announce(self) -> Dict[str, Any]:
        """
        Announce LXMF destination on the network.

        Returns:
            Dict with success flag or error
        """
        with self._lock:
            try:
                if not self.lxmf_destination:
                    return {
                        "success": False,
                        "error": "LXMF not initialized",
                        "errorCode": "NOT_INITIALIZED"
                    }

                self.lxmf_destination.announce()
                print(f"Announced destination: {self.lxmf_destination.hash.hex()}")

                return {"success": True}

            except Exception as e:
                print(f"ERROR announcing: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "ANNOUNCE_FAILED"
                }

    def set_propagation_node(self, node_hash: str) -> Dict[str, Any]:
        """
        Set active propagation node for store-and-forward messaging.

        Args:
            node_hash: Propagation node destination hash (hex string)

        Returns:
            Dict with success flag or error
        """
        with self._lock:
            try:
                if not self.message_router:
                    return {
                        "success": False,
                        "error": "LXMF not initialized",
                        "errorCode": "NOT_INITIALIZED"
                    }

                node_hash_bytes = bytes.fromhex(node_hash)
                self.message_router.set_outbound_propagation_node(node_hash_bytes)

                print(f"Set propagation node: {node_hash}")

                return {"success": True}

            except Exception as e:
                print(f"ERROR setting propagation node: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "SET_PROPAGATION_NODE_FAILED"
                }

    def request_messages(self, max_messages: int = 100) -> Dict[str, Any]:
        """
        Request messages from active propagation node.

        Args:
            max_messages: Maximum number of messages to retrieve

        Returns:
            Dict with success flag or error
        """
        with self._lock:
            try:
                if not self.message_router:
                    return {
                        "success": False,
                        "error": "LXMF not initialized",
                        "errorCode": "NOT_INITIALIZED"
                    }

                if not self.message_router.get_outbound_propagation_node():
                    return {
                        "success": False,
                        "error": "No propagation node configured",
                        "errorCode": "NO_PROPAGATION_NODE"
                    }

                self.message_router.request_messages_from_propagation_node(
                    self.identity,
                    max_messages=max_messages
                )

                print(f"Requesting up to {max_messages} messages from propagation node")

                return {"success": True}

            except Exception as e:
                print(f"ERROR requesting messages: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "REQUEST_MESSAGES_FAILED"
                }

    def get_propagation_state(self) -> Dict[str, Any]:
        """
        Get current propagation sync state and progress.

        Returns:
            Dict with state, progress, messagesReceived
        """
        with self._lock:
            try:
                if not self.message_router:
                    return {
                        "success": False,
                        "error": "LXMF not initialized",
                        "errorCode": "NOT_INITIALIZED"
                    }

                state = self.message_router.propagation_transfer_state
                progress = self.message_router.propagation_transfer_progress
                result = self.message_router.propagation_transfer_last_result or 0

                return {
                    "success": True,
                    "state": state,
                    "progress": progress,
                    "messagesReceived": result
                }

            except Exception as e:
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "GET_STATE_FAILED"
                }

    def shutdown(self) -> Dict[str, Any]:
        """
        Gracefully shutdown LXMF router.

        Returns:
            Dict with success flag
        """
        with self._lock:
            try:
                if self.message_router:
                    # TODO: LXMF.LXMRouter doesn't have explicit shutdown
                    # Just clear references
                    self.message_router = None
                    self.lxmf_destination = None
                    self._pending_messages.clear()

                print("LXMF shutdown complete")
                return {"success": True}

            except Exception as e:
                return {
                    "success": False,
                    "error": str(e),
                    "errorCode": "SHUTDOWN_FAILED"
                }

    # ========================================================================
    # Callbacks
    # ========================================================================

    def _on_message_received(self, message: LXMF.LXMessage):
        """Callback for received messages (called by LXMRouter)"""
        try:
            # Validate signature
            if not message.signature_validated:
                print(f"WARNING: Rejected message with invalid signature")
                return

            # Pack for storage
            message.pack()

            # Decode content safely
            try:
                content = message.content.decode("utf-8")
            except UnicodeDecodeError:
                content = message.content.decode("utf-8", errors="replace")

            try:
                title = message.title.decode("utf-8")
            except (UnicodeDecodeError, AttributeError):
                title = ""

            # Serialize fields
            fields_dict = {}
            if message.fields:
                for key, value in message.fields.items():
                    try:
                        # Convert bytes to hex if needed
                        if isinstance(value, bytes):
                            fields_dict[str(key)] = value.hex()
                        else:
                            fields_dict[str(key)] = str(value)
                    except:
                        pass

            # Emit event to Kotlin
            _emitter.emit("message_received", {
                "messageId": message.hash.hex(),
                "sourceHash": message.source_hash.hex(),
                "content": content,
                "title": title,
                "timestamp": message.timestamp,
                "fields": fields_dict,
                "packedMessage": message.packed.hex() if message.packed else ""
            })

            print(f"Message received from {message.source_hash.hex()[:8]}...")

        except Exception as e:
            print(f"ERROR processing received message: {e}")

    def _on_delivery_confirmed(self, msg_id: str, message: LXMF.LXMessage):
        """Callback for delivery confirmation"""
        try:
            _emitter.emit("delivery_confirmed", {
                "messageId": msg_id,
                "state": message.state
            })

            print(f"Message delivered: {msg_id[:8]}...")

            # Clean up reference
            self._pending_messages.pop(msg_id, None)

        except Exception as e:
            print(f"ERROR in delivery callback: {e}")

    def _on_delivery_failed(self, msg_id: str, message: LXMF.LXMessage):
        """Callback for delivery failure"""
        try:
            _emitter.emit("delivery_failed", {
                "messageId": msg_id,
                "state": message.state
            })

            print(f"Message failed: {msg_id[:8]}...")

            # Clean up reference
            self._pending_messages.pop(msg_id, None)

        except Exception as e:
            print(f"ERROR in failed callback: {e}")


# ============================================================================
# Module-Level Functions (Kotlin Interface)
# ============================================================================

# Global wrapper instance
_wrapper: Optional[LXMFWrapper] = None


def initialize_lxmf(storage_path: str, identity_path: str,
                    rns_config: str, display_name: str,
                    stamp_cost: int = 0) -> str:
    """
    Initialize LXMF router.

    Args:
        storage_path: Path for LXMF storage
        identity_path: Path to identity file
        rns_config: Path to Reticulum config directory
        display_name: Display name for destination
        stamp_cost: Computational stamp cost (0-255)

    Returns:
        JSON string with result
    """
    global _wrapper
    try:
        _wrapper = LXMFWrapper(storage_path, identity_path)
        result = _wrapper.initialize(rns_config, display_name, stamp_cost)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def send_message(dest_hash: str, content: str, title: str = "",
                 method: int = 2, fields_json: str = "{}",
                 include_ticket: bool = False) -> str:
    """
    Send LXMF message.

    Args:
        dest_hash: Destination hash (hex string)
        content: Message content
        title: Optional title
        method: Delivery method (1=OPPORTUNISTIC, 2=DIRECT, 3=PROPAGATED)
        fields_json: JSON string of fields
        include_ticket: Include delivery ticket

    Returns:
        JSON string with result
    """
    global _wrapper
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "errorCode": "NOT_INITIALIZED"
            })

        fields = json.loads(fields_json) if fields_json else {}
        result = _wrapper.send_message(dest_hash, content, title, method, fields, include_ticket)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def announce_destination() -> str:
    """Announce LXMF destination. Returns JSON string."""
    global _wrapper
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "errorCode": "NOT_INITIALIZED"
            })
        result = _wrapper.announce()
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def set_propagation_node(node_hash: str) -> str:
    """Set active propagation node. Returns JSON string."""
    global _wrapper
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "errorCode": "NOT_INITIALIZED"
            })
        result = _wrapper.set_propagation_node(node_hash)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def request_propagation_messages(max_messages: int = 100) -> str:
    """Request messages from propagation node. Returns JSON string."""
    global _wrapper
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "errorCode": "NOT_INITIALIZED"
            })
        result = _wrapper.request_messages(max_messages)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def get_propagation_status() -> str:
    """Get propagation sync status. Returns JSON string."""
    global _wrapper
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "errorCode": "NOT_INITIALIZED"
            })
        result = _wrapper.get_propagation_state()
        return json.dumps(result)
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


def shutdown_lxmf() -> str:
    """Shutdown LXMF router gracefully. Returns JSON string."""
    global _wrapper
    try:
        if _wrapper:
            result = _wrapper.shutdown()
            _wrapper = None
            return json.dumps(result)
        return json.dumps({"success": True})
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "errorCode": "EXCEPTION"
        })


# ============================================================================
# Test Function (for debugging)
# ============================================================================

def test_lxmf():
    """Test LXMF initialization (for debugging from Python)"""
    import tempfile

    temp_dir = tempfile.mkdtemp()
    storage = os.path.join(temp_dir, "lxmf_storage")
    identity = os.path.join(temp_dir, "identity")
    config = os.path.join(temp_dir, ".reticulum")

    print(f"Testing LXMF in {temp_dir}")

    result = initialize_lxmf(storage, identity, config, "Test User")
    print(f"Initialize result: {result}")

    result_dict = json.loads(result)
    if result_dict["success"]:
        print(f"Success! Destination: {result_dict['destinationHash']}")
    else:
        print(f"Failed: {result_dict['error']}")


if __name__ == "__main__":
    # Allow testing from Python directly
    test_lxmf()
