"""
Reticulum Wrapper for Kotlin Integration
Provides a simplified interface to Reticulum/LXMF that Kotlin can call via Chaquopy.
"""

from typing import Optional, Dict, List, Callable
import json
import struct
import threading
import time
import os
import shutil
import sys
import importlib
import importlib.util
import traceback
from logging_utils import log_debug, log_info, log_warning, log_error, log_separator

# umsgpack is available via RNS dependencies (bundled with Chaquopy on Android)
try:
    import umsgpack
except ImportError:
    umsgpack = None  # Will be available at runtime on Android

# Note: RNS/LXMF imports are deferred until after patches are deployed
# This ensures Python loads the patched code, not the original buggy code
RETICULUM_AVAILABLE = False
RNS = None
LXMF = None


# ============================================================================
# Global Exception Handler
# ============================================================================
# Catches unhandled exceptions in any thread and logs them before the crash.
# This helps diagnose issues that would otherwise silently kill the service.

def _global_exception_handler(exc_type, exc_value, exc_traceback):
    """
    Global exception handler that logs unhandled exceptions.
    Installed via sys.excepthook to catch crashes before process dies.
    """
    # Let KeyboardInterrupt and SystemExit pass through normally
    if issubclass(exc_type, (KeyboardInterrupt, SystemExit)):
        sys.__excepthook__(exc_type, exc_value, exc_traceback)
        return

    # Format and log the exception
    exc_text = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    log_error("GLOBAL", "excepthook", f"Unhandled {exc_type.__name__}: {exc_value}")
    log_error("GLOBAL", "excepthook", f"Traceback:\n{exc_text}")

    # Also print to stderr as a fallback
    print(f"FATAL: Unhandled {exc_type.__name__}: {exc_value}", file=sys.stderr)
    print(exc_text, file=sys.stderr)


# Install global exception handler at module load time
sys.excepthook = _global_exception_handler


# ============================================================================
# LXMF Field Constants (from LXMF specification)
# ============================================================================
FIELD_TELEMETRY = 0x02        # Standard telemetry field for Sideband interoperability
FIELD_COLUMBA_META = 0x70     # Custom field for Columba-specific metadata (cease signals, etc.)
FIELD_ICON_APPEARANCE = 0x04  # Icon appearance [name, fg_bytes(3), bg_bytes(3)] for Sideband/MeshChat interoperability
FIELD_FILE_ATTACHMENTS = 0x05 # LXMF standard field for file attachments
FIELD_IMAGE = 0x06            # LXMF standard field for images
FIELD_AUDIO = 0x07            # LXMF standard field for audio
LEGACY_LOCATION_FIELD = 7     # Legacy field ID for backwards compatibility

# Sensor IDs (from Sideband sense.py)
SID_TIME = 0x01
SID_LOCATION = 0x02


# ============================================================================
# Telemetry Pack/Unpack Helpers (Sideband Telemeter format)
# ============================================================================

def pack_location_telemetry(lat: float, lon: float, accuracy: float, timestamp_ms: int,
                            altitude: float = 0.0, speed: float = 0.0, bearing: float = 0.0) -> bytes:
    """
    Pack location data in Sideband Telemeter format for FIELD_TELEMETRY.

    This format is compatible with Sideband's sense.py Location sensor.

    Args:
        lat: Latitude in decimal degrees (WGS84)
        lon: Longitude in decimal degrees (WGS84)
        accuracy: Horizontal accuracy in meters
        timestamp_ms: Unix timestamp in milliseconds
        altitude: Altitude in meters (default 0.0)
        speed: Speed in km/h (default 0.0)
        bearing: Bearing/heading in degrees (default 0.0)

    Returns:
        msgpack-packed bytes for FIELD_TELEMETRY
    """
    # Lazy import - umsgpack is available after RNS is loaded on Android
    global umsgpack
    if umsgpack is None:
        import umsgpack as _umsgpack
        umsgpack = _umsgpack

    timestamp_s = int(timestamp_ms / 1000)

    # Pack location data exactly as Sideband's Location.pack() does (sense.py:880-897)
    location_packed = [
        struct.pack("!i", int(round(lat, 6) * 1e6)),       # latitude in microdegrees
        struct.pack("!i", int(round(lon, 6) * 1e6)),       # longitude in microdegrees
        struct.pack("!i", int(round(altitude, 2) * 1e2)),  # altitude in centimeters
        struct.pack("!I", int(round(speed, 2) * 1e2)),     # speed in cm/s (unsigned)
        struct.pack("!i", int(round(bearing, 2) * 1e2)),   # bearing in centi-degrees
        struct.pack("!H", int(round(accuracy, 2) * 1e2)),  # accuracy in centimeters (unsigned short)
        timestamp_s,                                        # last_update timestamp
    ]

    telemetry = {
        SID_TIME: timestamp_s,
        SID_LOCATION: location_packed,
    }

    return umsgpack.packb(telemetry)


def unpack_location_telemetry(packed_data: bytes) -> Optional[Dict]:
    """
    Unpack Sideband Telemeter format from FIELD_TELEMETRY to Columba JSON format.

    Args:
        packed_data: msgpack-packed bytes from FIELD_TELEMETRY

    Returns:
        Dict with location data in Columba format, or None if unpacking fails
    """
    # Lazy import - umsgpack is available after RNS is loaded on Android
    global umsgpack
    if umsgpack is None:
        import umsgpack as _umsgpack
        umsgpack = _umsgpack

    try:
        telemetry = umsgpack.unpackb(packed_data)

        if SID_LOCATION not in telemetry:
            return None

        loc = telemetry[SID_LOCATION]
        if len(loc) < 7:
            return None

        # Unpack exactly as Sideband's Location.unpack() does (sense.py:899-914)
        lat = struct.unpack("!i", loc[0])[0] / 1e6
        lon = struct.unpack("!i", loc[1])[0] / 1e6
        altitude = struct.unpack("!i", loc[2])[0] / 1e2
        speed = struct.unpack("!I", loc[3])[0] / 1e2
        bearing = struct.unpack("!i", loc[4])[0] / 1e2
        accuracy = struct.unpack("!H", loc[5])[0] / 1e2
        last_update = loc[6]

        return {
            "type": "location_share",
            "lat": lat,
            "lng": lon,
            "acc": accuracy,
            "ts": last_update * 1000,  # Convert to milliseconds
            "altitude": altitude,
            "speed": speed,
            "bearing": bearing,
        }
    except Exception as e:
        log_warning("TelemetryHelper", "unpack_location_telemetry",
                   f"Failed to unpack telemetry: {e}")
        return None


def get_hello_message() -> str:
    """
    Simple test function to verify Python integration.
    Returns a greeting message with Reticulum availability status.
    """
    global RETICULUM_AVAILABLE
    if RETICULUM_AVAILABLE:
        return "Hello from Python! Reticulum is available."
    else:
        return "Hello from Python! (Mock mode - Reticulum not available)"


# Global wrapper instance for AndroidBLEDriver to access KotlinBLEBridge
_global_wrapper_instance = None


class AnnounceHandler:
    """
    Wrapper class for announce callbacks that implements RNS.Transport requirements.

    RNS.Transport.register_announce_handler requires:
    1. An object with an 'aspect_filter' attribute
    2. A 'received_announce(destination_hash, announced_identity, app_data)' callable

    This class wraps our internal _announce_handler method to meet these requirements.
    """

    def __init__(self, aspect_filter, callback):
        """
        Initialize the announce handler wrapper.

        Args:
            aspect_filter: The aspect to filter for (e.g., "lxmf.delivery", "call.audio")
                          Use None to receive ALL announces
            callback: The actual callback function to invoke when announces are received
                     Signature: callback(aspect, destination_hash, announced_identity, app_data, announce_packet_hash)
        """
        self.aspect_filter = aspect_filter
        self.callback = callback

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash=None):
        """
        Called by RNS.Transport when an announce is received.

        Args:
            destination_hash: The destination hash that announced
            announced_identity: The RNS.Identity object of the announcing peer
            app_data: Application-specific data included in the announce
            announce_packet_hash: Hash of the announce packet (optional, for future use)
        """
        # Pass aspect to callback so it knows which aspect this announce is for
        self.callback(self.aspect_filter, destination_hash, announced_identity, app_data, announce_packet_hash)


class ReticulumWrapper:
    """Main wrapper class for Reticulum operations"""

    def __init__(self, storage_path: str):
        global _global_wrapper_instance

        self.storage_path = storage_path
        self.reticulum = None
        self.router = None
        self.message_callbacks = []
        self.announce_callbacks = []
        self.link_callbacks = []
        self.destinations = {}  # Track destinations by hash
        self.initialized = False
        self.failed_interfaces = []  # Track interfaces that failed to initialize
        self.rns_thread = None
        self.pending_announces = []  # Queue for announces waiting to be retrieved
        self.announce_lock = threading.Lock()
        self.seen_message_hashes = set()  # Track which messages we've already processed
        self.local_lxmf_destination = None  # Our local LXMF delivery destination
        self.last_announce_poll_time = 0  # Track last poll time for announce_table polling
        self.seen_announce_hashes = set()  # Track which announces we've already processed from announce_table
        self.identities = {}  # Local cache of recalled identities (identity_hash_hex -> RNS.Identity)
        self.active_propagation_node = None  # Currently active propagation node destination hash (bytes)

        # BLE interface support (Android-specific - driver-based architecture)
        self.ble_interface = None  # AndroidBLEInterface instance (if enabled)
        self.transport_identity_hash = None  # 16-byte Transport identity hash (for BLE Protocol v2.2)
        self.kotlin_ble_bridge = None  # KotlinBLEBridge instance (passed from Kotlin)

        # RNode interface support (Bluetooth Classic or BLE to RNode LoRa hardware)
        self.rnode_interface = None  # ColumbaRNodeInterface instance (if enabled)
        self.kotlin_rnode_bridge = None  # KotlinRNodeBridge instance (passed from Kotlin)
        self._pending_rnode_config = None  # Stored RNode config during initialization
        self._rnode_init_lock = threading.Lock()  # Lock to prevent concurrent RNode initialization
        self._rnode_initializing = False  # Flag to track if RNode initialization is in progress

        # Delivery status callback support (for event-driven message status updates)
        self.kotlin_delivery_status_callback = None  # Callback to Kotlin for delivery status events

        # Message received callback support (Phase 2.2 - event-driven message notifications)
        self.kotlin_message_received_callback = None  # Callback to Kotlin when LXMF message received

        # Location telemetry callback support (Phase 3 - location sharing over LXMF)
        self.kotlin_location_received_callback = None  # Callback to Kotlin when location telemetry received

        # Reaction received callback support (emoji reactions to messages)
        self.kotlin_reaction_received_callback = None  # Callback to Kotlin when reaction received

        # General Reticulum bridge for protocol-level callbacks (announces, link events, etc.)
        self.kotlin_reticulum_bridge = None  # KotlinReticulumBridge instance (passed from Kotlin)

        # Opportunistic message timeout tracking
        # When opportunistic messages are sent but recipient is offline, they get stuck in SENT state
        # forever waiting for a delivery receipt. This tracking dict + timer provides a timeout
        # mechanism to trigger propagation fallback for undelivered opportunistic messages.
        self._opportunistic_messages = {}  # {msg_hash_hex: {'message': lxmf_message, 'sent_time': timestamp}}
        self._opportunistic_timeout_seconds = 30  # Timeout before falling back to propagation
        self._opportunistic_check_interval = 10  # How often to check for timeouts (seconds)
        self._opportunistic_timer = None  # Timer thread reference

        # Propagation node fallback tracking
        # When the selected relay is offline and propagation fails, we request alternative relays
        # from Kotlin. Messages wait in pending dict until alternative is provided or all exhausted.
        self.kotlin_request_alternative_relay_callback = None  # Callback to request alternative relay
        self._pending_relay_fallback_messages = {}  # {msg_hash_hex: lxmf_message} - waiting for alternative
        self._max_relay_retries = 3  # Maximum number of alternative relays to try

        # Pending file notifications - sent only after propagation succeeds
        # When direct delivery fails for file attachments and we fall back to propagation,
        # we track the message here. The notification is sent only when propagation succeeds.
        self._pending_file_notifications = {}  # {msg_hash_hex: lxmf_message}

        # Native stamp generator callback (Kotlin)
        # Used to bypass Python multiprocessing issues on Android
        self.kotlin_stamp_generator_callback = None

        # Propagation sync state callback (for real-time sync progress updates)
        # Invoked when LXMF propagation state changes (idle, receiving, complete, etc.)
        self.kotlin_propagation_state_callback = None
        self._last_propagation_state = None  # For change detection
        self._last_propagation_progress = 0.0  # For progress change detection during transfers

        # Service heartbeat tracking (Sideband-inspired process monitoring)
        # Python updates timestamp every second; Kotlin monitors for stale heartbeats
        # If heartbeat is stale > 10 seconds, Kotlin should restart the service
        self._heartbeat_timestamp = 0.0  # Updated every second when running
        self._heartbeat_thread = None  # Thread reference for heartbeat loop

        # Service maintenance tracking (Sideband-inspired interface recovery)
        # Periodically checks for failed interfaces and attempts reinit
        self._maintenance_thread = None  # Thread reference for maintenance loop
        self._last_interface_reinit_attempt = 0.0  # Timestamp of last reinit attempt
        self._interface_reinit_interval = 60.0  # Retry failed interfaces every 60 seconds

        # Set global instance so AndroidBLEDriver can access it
        _global_wrapper_instance = self

        # Announce handlers - register multiple aspect-specific handlers
        # Following MeshChat's pattern to properly distinguish announce types
        self._announce_handlers = {
            "lxmf.delivery": AnnounceHandler("lxmf.delivery", self._announce_handler),
            "lxmf.propagation": AnnounceHandler("lxmf.propagation", self._announce_handler),
            "call.audio": AnnounceHandler("call.audio", self._announce_handler),
            "nomadnetwork.node": AnnounceHandler("nomadnetwork.node", self._announce_handler),
            "rmsp.maps": AnnounceHandler("rmsp.maps", self._announce_handler),
        }

        # RMSP client for map tile fetching over Reticulum
        self._rmsp_client = None

        # Shared instance state
        self.is_shared_instance = False  # True if connected to external shared RNS instance

        # Don't initialize here - wait for explicit initialize() call
        log_info("ReticulumWrapper", "__init__", f"Created with storage path: {storage_path}")

    def set_ble_bridge(self, bridge):
        """
        Set the KotlinBLEBridge instance for BLE operations.
        Should be called from Kotlin before initialize().

        Args:
            bridge: KotlinBLEBridge instance from Kotlin
        """
        self.kotlin_ble_bridge = bridge
        log_info("ReticulumWrapper", "set_ble_bridge", "KotlinBLEBridge instance set")

    def set_rnode_bridge(self, bridge):
        """
        Set the KotlinRNodeBridge instance for RNode operations.
        Should be called from Kotlin before initialize().

        Args:
            bridge: KotlinRNodeBridge instance from Kotlin
        """
        self.kotlin_rnode_bridge = bridge
        log_info("ReticulumWrapper", "set_rnode_bridge", "KotlinRNodeBridge instance set")

    def get_paired_rnodes(self) -> Dict:
        """
        Get list of paired Bluetooth devices that might be RNodes.

        Uses the KotlinRNodeBridge to query paired devices.
        Returns devices that appear to be RNodes based on naming patterns.

        Returns:
            Dict with:
            - success: boolean
            - devices: list of device name strings
            - error: optional error message
        """
        try:
            if self.kotlin_rnode_bridge is None:
                return {'success': False, 'devices': [], 'error': 'KotlinRNodeBridge not set'}

            devices = self.kotlin_rnode_bridge.getPairedRNodes()
            device_list = list(devices) if devices else []

            log_info("ReticulumWrapper", "get_paired_rnodes", f"Found {len(device_list)} paired RNode(s)")
            return {'success': True, 'devices': device_list}

        except Exception as e:
            log_error("ReticulumWrapper", "get_paired_rnodes", f"ERROR getting paired RNodes: {e}")
            return {'success': False, 'devices': [], 'error': str(e)}

    def set_reticulum_bridge(self, bridge):
        """
        Set the KotlinReticulumBridge instance for general protocol callbacks.
        Should be called from Kotlin before initialize().

        This bridge handles non-BLE specific events like announces, link events,
        and other protocol-level notifications that work across all interfaces.

        Args:
            bridge: KotlinReticulumBridge instance from Kotlin
        """
        self.kotlin_reticulum_bridge = bridge
        log_info("ReticulumWrapper", "set_reticulum_bridge", "KotlinReticulumBridge instance set")

    def set_delivery_status_callback(self, callback):
        """
        Set callback to be invoked when LXMF message delivery status changes.
        Uses the same pattern as BLE bridge callbacks for event-driven updates.

        Callback signature: callback(status_json: str)

        Status JSON format:
        {
            "message_hash": "abc123...",  # Hex string of message hash
            "status": "delivered" | "failed",
            "timestamp": 1234567890000  # Milliseconds since epoch
        }

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_delivery_status_callback = callback
        log_info("ReticulumWrapper", "set_delivery_status_callback", "Delivery status callback registered")

    def set_message_received_callback(self, callback):
        """
        Set callback to be invoked when LXMF messages are received (Phase 2.2).
        Eliminates need for message polling by providing event-driven notifications.

        Callback signature: callback(message_json: str)

        Message JSON format:
        {
            "message_hash": "abc123...",      # Hex string of message hash
            "source_hash": "def456...",       # Hex string of source identity hash
            "destination_hash": "ghi789...",  # Hex string of destination hash
            "timestamp": 1234567890000,       # Milliseconds since epoch
            "content_length": 1234            # Length of message content in bytes
        }

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_message_received_callback = callback
        log_info("ReticulumWrapper", "set_message_received_callback",
                "✅ Message received callback registered (event-driven architecture enabled)")

    def set_location_received_callback(self, callback):
        """
        Set callback to be invoked when location telemetry is received (Phase 3 - Location Sharing).
        Location telemetry is sent via LXMF field 7 as JSON.

        Callback signature: callback(location_json: str)

        Location JSON format:
        {
            "source_hash": "abc123...",      # Hex string of sender identity hash
            "type": "location_share",        # Always "location_share"
            "lat": 37.7749,                  # Latitude (WGS84)
            "lng": -122.4194,                # Longitude (WGS84)
            "acc": 10.0,                     # Accuracy in meters
            "ts": 1234567890000,             # Timestamp when captured (millis)
            "expires": 1234570890000         # When sharing ends (millis), null for indefinite
        }

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_location_received_callback = callback
        log_info("ReticulumWrapper", "set_location_received_callback",
                "✅ Location received callback registered (location sharing enabled)")

    def set_reaction_received_callback(self, callback):
        """
        Set callback to be invoked when an emoji reaction is received.

        Reactions are sent via LXMF Field 16 with the following keys:
        - reaction_to: Message ID being reacted to
        - emoji: The emoji reaction (e.g., "👍", "❤️", "😂")
        - sender: Identity hash of the reaction sender

        Callback signature: callback(reaction_json: str)

        Reaction JSON format:
        {
            "reaction_to": "abc123...",          # Message ID being reacted to
            "emoji": "👍",                        # The emoji reaction
            "sender": "def456...",               # Sender identity hash (hex)
            "source_hash": "ghi789...",          # Source destination hash (hex)
            "timestamp": 1234567890000           # Milliseconds since epoch
        }

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_reaction_received_callback = callback
        log_info("ReticulumWrapper", "set_reaction_received_callback",
                "✅ Reaction received callback registered (emoji reactions enabled)")

    def set_kotlin_request_alternative_relay_callback(self, callback):
        """
        Set callback to request alternative relay when propagation fails.

        When a message fails to deliver via the current propagation node (relay is offline),
        this callback is invoked to request Kotlin to find an alternative relay.

        Callback signature: callback(request_json: str)

        Request JSON format:
        {
            "message_hash": "abc123...",     # Hex string of message hash needing retry
            "exclude_relays": ["def456..."], # List of relay hashes to exclude (already tried)
            "timestamp": 1234567890000       # Milliseconds since epoch
        }

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_request_alternative_relay_callback = callback
        log_info("ReticulumWrapper", "set_kotlin_request_alternative_relay_callback",
                "✅ Alternative relay callback registered (relay fallback enabled)")

    def set_stamp_generator_callback(self, callback):
        """
        Set native Kotlin callback for stamp generation.

        This bypasses Python multiprocessing-based stamp generation which fails on Android
        due to lack of sem_open support and aggressive process killing by Android.

        Callback signature: callback(workblock: bytes, stamp_cost: int) -> (stamp: bytes, rounds: int)

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_stamp_generator_callback = callback

        # Register with LXMF's LXStamper module
        try:
            from LXMF import LXStamper
            LXStamper.set_external_generator(callback)
            log_info("ReticulumWrapper", "set_stamp_generator_callback",
                    "✅ Native stamp generator registered with LXMF")
        except Exception as e:
            log_error("ReticulumWrapper", "set_stamp_generator_callback",
                     f"Failed to register stamp generator: {e}")

    def set_propagation_state_callback(self, callback):
        """
        Set callback for propagation sync state changes.

        This callback is invoked whenever the LXMF propagation state changes
        (e.g., idle -> path_requested -> receiving -> complete).
        Used by Kotlin to show real-time sync progress.

        Callback signature: callback(state_json: str) -> None
        state_json contains: {"state": int, "state_name": str, "progress": float, "messages_received": int}

        Args:
            callback: PyObject callable from Kotlin (passed via Chaquopy)
        """
        self.kotlin_propagation_state_callback = callback
        log_info("ReticulumWrapper", "set_propagation_state_callback",
                "Propagation state callback registered")

    def _clear_stale_ble_paths(self):
        """
        Clear stale BLE paths from Transport.path_table on startup.

        Bug workaround: Reticulum core loads path table entries from storage
        with timestamp=0 (or very old timestamps), causing paths to immediately
        expire. This prevents LXMF message delivery as messages wait for paths
        that are constantly expiring and being recreated.

        This workaround clears any BLE paths with invalid timestamps on startup,
        forcing fresh path discovery via announces.
        """
        try:
            if not hasattr(RNS.Transport, 'path_table') or not RNS.Transport.path_table:
                return

            current_time = time.time()
            stale_threshold = 60  # Paths older than 60 seconds are considered stale
            stale_paths = []

            # Scan for stale BLE paths
            for dest_hash, entry in list(RNS.Transport.path_table.items()):
                try:
                    timestamp = entry[0]  # IDX_PT_TIMESTAMP
                    receiving_interface = entry[5] if len(entry) > 5 else None  # IDX_PT_RVCD_IF

                    # Check if this is a BLE path
                    if receiving_interface and "BLE" in str(type(receiving_interface).__name__):
                        # Check for timestamp=0 bug or very old timestamps
                        if timestamp == 0:
                            stale_paths.append((dest_hash, timestamp, "timestamp=0 (Unix epoch bug)"))
                        elif (current_time - timestamp) > stale_threshold:
                            stale_paths.append((dest_hash, timestamp, f"age={(current_time - timestamp):.0f}s (stale from previous session)"))
                except (IndexError, TypeError) as e:
                    # Malformed path entry
                    log_debug("ReticulumWrapper", "_clear_stale_ble_paths", f"Skipping malformed path table entry: {e}")
                    continue

            # Remove stale paths
            if stale_paths:
                log_info("ReticulumWrapper", "_clear_stale_ble_paths", f"Bug workaround: Found {len(stale_paths)} stale BLE path(s) to clear")
                for dest_hash, old_timestamp, reason in stale_paths:
                    RNS.Transport.path_table.pop(dest_hash)
                    log_debug("ReticulumWrapper", "_clear_stale_ble_paths", f"Cleared stale BLE path for {dest_hash.hex()[:16]}... - {reason}")
                log_info("ReticulumWrapper", "_clear_stale_ble_paths", "Stale path cleanup complete. Fresh paths will be discovered via announces.")
            else:
                log_debug("ReticulumWrapper", "_clear_stale_ble_paths", "No stale BLE paths found in path table")

        except Exception as e:
            log_warning("ReticulumWrapper", "_clear_stale_ble_paths", f"Error during stale path cleanup (non-fatal): {e}")

    def check_shared_instance_available(self, host: str = "127.0.0.1", port: int = 37428, timeout: float = 1.0) -> bool:
        """
        Check if a shared Reticulum instance is available via TCP.

        RNS shared instances listen on 127.0.0.1:37428 by default for TCP clients.
        This method attempts to connect to that port to detect if another app
        (e.g., Sideband) is already running a shared Reticulum instance.

        This method is callable from Kotlin via the service AIDL interface.

        Args:
            host: Host to check (default: localhost)
            port: Port to check (default: 37428, RNS shared instance default)
            timeout: Connection timeout in seconds

        Returns:
            True if a shared instance appears to be available, False otherwise
        """
        import socket

        try:
            log_info("ReticulumWrapper", "check_shared_instance_available",
                     f"Checking for shared instance at {host}:{port}...")

            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)

            result = sock.connect_ex((host, port))
            sock.close()

            if result == 0:
                log_info("ReticulumWrapper", "check_shared_instance_available",
                         f"✓ Shared instance detected at {host}:{port}")
                return True
            else:
                log_info("ReticulumWrapper", "check_shared_instance_available",
                         f"No shared instance found at {host}:{port} (error code: {result})")
                return False

        except socket.timeout:
            log_info("ReticulumWrapper", "check_shared_instance_available",
                     f"Connection to {host}:{port} timed out - no shared instance")
            return False
        except Exception as e:
            log_warning("ReticulumWrapper", "check_shared_instance_available",
                        f"Error checking shared instance: {e}")
            return False

    def _create_config_file(self, interfaces: List[Dict], use_shared_instance: bool = False, rpc_key: str = None, enable_transport: bool = True):
        """
        Create an RNS config file with the specified interfaces.

        Args:
            interfaces: List of interface configuration dictionaries
            use_shared_instance: If True, configure as client to shared instance (no local interfaces)
            rpc_key: Optional RPC key (hex string) for shared instance authentication.
                     Required on Android when connecting to another app's shared instance
                     (e.g., Sideband) because apps have separate config directories.
            enable_transport: If True (default), enables transport mode to forward traffic for the mesh.
                              If False, only handles own traffic without relaying for other peers.
        """
        from datetime import datetime

        config_path = os.path.join(self.storage_path, "config")
        log_debug("ReticulumWrapper", "_create_config_file", f"Creating config file at: {config_path}")
        log_debug("ReticulumWrapper", "_create_config_file", f"Number of interfaces: {len(interfaces)}")
        log_debug("ReticulumWrapper", "_create_config_file", f"Use shared instance: {use_shared_instance}")

        # Generate timestamp for config file
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        if use_shared_instance:
            # Shared instance client mode - connect to existing RNS instance via TCP
            # CRITICAL: On Android, we MUST use TCP because domain sockets don't work
            # between different apps due to sandboxing. Without shared_instance_type = tcp,
            # RNS defaults to domain sockets and won't find Sideband's shared instance.
            transport_value = "yes" if enable_transport else "no"
            config_lines = [
                "################################################################################",
                "# SHARED INSTANCE MODE",
                "# ",
                "# Columba is configured to connect to an external shared Reticulum instance",
                "# (e.g., Sideband) running on this device. Interfaces are managed by that app.",
                "################################################################################",
                "",
                "# Reticulum Configuration for Columba (Shared Instance Client)",
                f"# Generated: {timestamp}",
                "# Mode: Shared instance client",
                "",
                "[reticulum]",
                f"  enable_transport = {transport_value}",
                "  share_instance = yes",
                "  shared_instance_type = tcp",
                "  shared_instance_port = 37428",
            ]
            # Add RPC key if provided (required on Android for inter-app shared instance)
            # Export from Sideband: Connectivity → Share Instance Access
            if rpc_key:
                config_lines.append(f"  rpc_key = {rpc_key}")
                log_info("ReticulumWrapper", "_create_config_file", "Added RPC key to config")
            else:
                log_warning("ReticulumWrapper", "_create_config_file",
                           "No RPC key provided - RPC calls to shared instance may fail")
            config_lines.extend([
                "",
                "# No interfaces defined - using shared instance's interfaces",
                "[interfaces]"
            ])
        else:
            # Standalone mode - create our own RNS instance with specified interfaces
            transport_value = "yes" if enable_transport else "no"
            config_lines = [
                "################################################################################",
                "# DO NOT EDIT THIS FILE MANUALLY",
                "# ",
                "# This file is automatically generated from the app's Interface Management UI.",
                "# Any manual changes will be overwritten when the app restarts.",
                "# ",
                "# To manage network interfaces:",
                "#   1. Open Columba app",
                "#   2. Go to Settings tab",
                "#   3. Tap 'Network Interfaces'",
                "#   4. Use the UI to add, edit, or configure interfaces",
                "################################################################################",
                "",
                "# Reticulum Configuration for Columba",
                f"# Generated: {timestamp}",
                f"# Interfaces: {len(interfaces)}",
                "",
                "[reticulum]",
                f"  enable_transport = {transport_value}",  # Enable/disable transport mode (mesh forwarding)
                "  share_instance = no",
                "",
                "[interfaces]"
            ]

        # Add each interface (only for standalone mode - shared instance uses external interfaces)
        if use_shared_instance:
            log_debug("ReticulumWrapper", "_create_config_file", "Skipping interface definitions - using shared instance")
        for iface in ([] if use_shared_instance else interfaces):
            iface_type = iface.get("type")
            iface_name = iface.get("name", "Unnamed Interface")
            log_debug("ReticulumWrapper", "_create_config_file", f"Adding interface: {iface_name} ({iface_type})")

            config_lines.append(f"  # {iface_name}")
            config_lines.append(f"  [[{iface_name}]]")

            if iface_type == "AutoInterface":
                config_lines.append("    type = AutoInterface")
                config_lines.append("    enabled = yes")

                # Add optional AutoInterface parameters
                group_id = iface.get("group_id", "")
                if group_id:
                    config_lines.append(f"    group_id = {group_id}")

                discovery_scope = iface.get("discovery_scope", "link")
                if discovery_scope != "link":
                    config_lines.append(f"    discovery_scope = {discovery_scope}")

                # Only write ports if explicitly set (None = use RNS defaults)
                discovery_port = iface.get("discovery_port")
                if discovery_port is not None:
                    config_lines.append(f"    discovery_port = {discovery_port}")

                data_port = iface.get("data_port")
                if data_port is not None:
                    config_lines.append(f"    data_port = {data_port}")

                mode = iface.get("mode", "full")
                if mode != "full":
                    config_lines.append(f"    mode = {mode}")

            elif iface_type == "TCPClient":
                config_lines.append("    type = TCPClientInterface")
                config_lines.append("    enabled = yes")

                target_host = iface.get("target_host", "127.0.0.1")
                config_lines.append(f"    target_host = {target_host}")

                target_port = iface.get("target_port", 4242)
                config_lines.append(f"    target_port = {target_port}")

                kiss_framing = iface.get("kiss_framing", False)
                if kiss_framing:
                    config_lines.append("    kiss_framing = True")

                # IFAC parameters
                network_name = iface.get("network_name")
                if network_name:
                    config_lines.append(f"    network_name = {network_name}")

                passphrase = iface.get("passphrase")
                if passphrase:
                    config_lines.append(f"    passphrase = {passphrase}")

                mode = iface.get("mode", "full")
                if mode != "full":
                    config_lines.append(f"    mode = {mode}")

            elif iface_type == "TCPServer":
                config_lines.append("    type = TCPServerInterface")
                config_lines.append("    enabled = yes")

                listen_ip = iface.get("listen_ip", "0.0.0.0")
                config_lines.append(f"    listen_ip = {listen_ip}")

                listen_port = iface.get("listen_port", 4242)
                config_lines.append(f"    listen_port = {listen_port}")

                mode = iface.get("mode", "full")
                if mode != "full":
                    config_lines.append(f"    mode = {mode}")

            elif iface_type == "RNode":
                connection_mode = iface.get("connection_mode", "classic")

                if connection_mode == "tcp":
                    # TCP/WiFi RNode - uses Android RNodeInterface with tcp_host parameter
                    # Port 7633 is hardcoded in RNS TCPConnection class
                    tcp_host = iface.get("tcp_host", "")

                    # Validate TCP host - skip this interface if empty
                    if not tcp_host or not tcp_host.strip():
                        log_error("ReticulumWrapper", "_create_config_file",
                                f"Skipping RNode TCP interface '{iface_name}': tcp_host is empty")
                        continue

                    config_lines.append("    type = RNodeInterface")
                    config_lines.append("    enabled = yes")
                    config_lines.append(f"    tcp_host = {tcp_host}")

                    # LoRa parameters
                    frequency = iface.get("frequency", 915000000)
                    bandwidth = iface.get("bandwidth", 125000)
                    tx_power = iface.get("tx_power", 7)
                    spreading_factor = iface.get("spreading_factor", 7)
                    coding_rate = iface.get("coding_rate", 5)

                    config_lines.append(f"    frequency = {frequency}")
                    config_lines.append(f"    bandwidth = {bandwidth}")
                    config_lines.append(f"    txpower = {tx_power}")
                    config_lines.append(f"    spreadingfactor = {spreading_factor}")
                    config_lines.append(f"    codingrate = {coding_rate}")

                    # Optional airtime limits
                    st_alock = iface.get("st_alock")
                    lt_alock = iface.get("lt_alock")
                    if st_alock is not None:
                        config_lines.append(f"    airtime_limit_short = {st_alock}")
                    if lt_alock is not None:
                        config_lines.append(f"    airtime_limit_long = {lt_alock}")

                    mode = iface.get("mode", "full")
                    if mode != "full":
                        config_lines.append(f"    interface_mode = {mode}")

                    log_info("ReticulumWrapper", "_create_config_file",
                            f"RNode TCP config written: tcp_host={tcp_host}")
                else:
                    # Bluetooth RNode - handled specially via ColumbaRNodeInterface
                    # Don't write to config file - standard RNodeInterface uses jnius which doesn't work with Chaquopy
                    # Store the config for later use by ColumbaRNodeInterface
                    self._pending_rnode_config = {
                        "name": iface.get("name", "RNode LoRa"),
                        "target_device_name": iface.get("target_device_name", iface.get("port", "")),
                        "connection_mode": connection_mode,
                        "frequency": iface.get("frequency", 915000000),
                        "bandwidth": iface.get("bandwidth", 125000),
                        "tx_power": iface.get("tx_power", 7),
                        "spreading_factor": iface.get("spreading_factor", 7),
                        "coding_rate": iface.get("coding_rate", 5),
                        "st_alock": iface.get("st_alock"),
                        "lt_alock": iface.get("lt_alock"),
                        "mode": iface.get("mode", "full"),
                        "enable_framebuffer": iface.get("enable_framebuffer", True),  # Display Columba logo on RNode
                    }
                    log_info("ReticulumWrapper", "_create_config_file",
                            f"RNode Bluetooth config stored for ColumbaRNodeInterface: {self._pending_rnode_config['target_device_name']}")
                    continue  # Skip writing to config file for Bluetooth

            elif iface_type == "AndroidBLE":
                config_lines.append("    type = AndroidBLE")
                config_lines.append("    enabled = yes")

                device_name = iface.get("device_name", "Reticulum-Android")
                config_lines.append(f"    device_name = {device_name}")

                max_connections = iface.get("max_connections", 7)
                config_lines.append(f"    max_connections = {max_connections}")

                mode = iface.get("mode", "full")
                if mode != "full":
                    config_lines.append(f"    mode = {mode}")

            else:
                log_warning("ReticulumWrapper", "_create_config_file", f"WARNING: Unknown interface type: {iface_type}")
                continue

            config_lines.append("")  # Empty line between interfaces

        config_content = "\n".join(config_lines)
        log_debug("ReticulumWrapper", "_create_config_file", f"Generated config:\n{config_content}")

        try:
            # Ensure storage directory exists
            os.makedirs(self.storage_path, exist_ok=True)

            # Write config file
            with open(config_path, 'w') as f:
                f.write(config_content)

            log_info("ReticulumWrapper", "_create_config_file", f"Config file created successfully")
            return True
        except Exception as e:
            log_error("ReticulumWrapper", "_create_config_file", f"ERROR creating config file: {e}")
            import traceback
            traceback.print_exc()
            return False

    def initialize(
        self,
        config_json: str,
        identity_file_path: Optional[str] = None,
        incoming_message_limit_kb: int = 1024
    ) -> Dict:
        """
        Initialize Reticulum with the given configuration.

        Args:
            config_json: JSON string containing configuration with:
                - storagePath: str
                - enabledInterfaces: list of interface configs
                - logLevel: str (CRITICAL, ERROR, WARNING, INFO, DEBUG, VERBOSE)
                - allowAnonymous: bool
            identity_file_path: Optional path to a specific identity file to load.
                               If None, uses default_identity file (backward compatible).
            incoming_message_limit_kb: Maximum incoming message size in KB (default 1024 = 1MB).
                                       Set to 131072 (128MB) for effectively unlimited.

        Returns:
            Dict with 'success' and optional 'error' keys
        """
        log_separator("ReticulumWrapper", "initialize")
        log_info("ReticulumWrapper", "initialize", "Initialization started")
        log_separator("ReticulumWrapper", "initialize")

        try:
            if self.initialized:
                log_error("ReticulumWrapper", "initialize", "Already initialized")
                return {"success": False, "error": "Already initialized"}

            # CRITICAL: Deploy RNS patches BEFORE importing RNS
            # This ensures Python loads patched code instead of cached buggy code
            global RETICULUM_AVAILABLE, RNS, LXMF

            if not RETICULUM_AVAILABLE:
                log_info("ReticulumWrapper", "initialize", "Deploying RNS patches BEFORE first import")
                try:
                    import pkgutil
                    # Find RNS location WITHOUT importing it
                    rns_spec = importlib.util.find_spec("RNS")
                    if rns_spec and rns_spec.origin:
                        rns_module_path = os.path.dirname(rns_spec.origin)
                        log_debug("ReticulumWrapper", "initialize", f"RNS module path: {rns_module_path}")

                        # CRITICAL: Delete Python bytecode cache before deploying patches
                        # Python may use cached .pyc files instead of our patched .py files
                        pycache_dir = os.path.join(rns_module_path, '__pycache__')
                        if os.path.isdir(pycache_dir):
                            log_info("ReticulumWrapper", "initialize", "Clearing Python bytecode cache (__pycache__)")
                            shutil.rmtree(pycache_dir, ignore_errors=True)

                        # Also delete any standalone .pyc files
                        for pyc_file in ['Destination.pyc', '__init__.pyc']:
                            pyc_path = os.path.join(rns_module_path, pyc_file)
                            if os.path.exists(pyc_path):
                                os.remove(pyc_path)
                                log_debug("ReticulumWrapper", "initialize", f"Deleted {pyc_file}")

                        # Deploy patches - list of (resource_path, dest_subpath) tuples
                        patch_files = [
                            ('Destination.py', 'Destination.py'),
                            ('__init__.py', '__init__.py'),
                        ]
                        patches_applied = 0

                        for resource_subpath, dest_subpath in patch_files:
                            try:
                                patch_resource_path = f"patches/RNS/{resource_subpath}"
                                patch_data = pkgutil.get_data(__name__.split('.')[0], patch_resource_path)

                                if patch_data:
                                    patch_dest = os.path.join(rns_module_path, dest_subpath)
                                    # Ensure parent directory exists
                                    os.makedirs(os.path.dirname(patch_dest), exist_ok=True)
                                    with open(patch_dest, 'wb') as dest:
                                        dest.write(patch_data)

                                    log_info("ReticulumWrapper", "initialize", f"✓ Applied patch: {dest_subpath}")
                                    patches_applied += 1
                            except Exception as e:
                                log_warning("ReticulumWrapper", "initialize", f"Failed to apply patch {dest_subpath}: {e}")

                        if patches_applied > 0:
                            log_info("ReticulumWrapper", "initialize", f"Successfully applied {patches_applied} RNS patch(es)")
                except Exception as e:
                    log_error("ReticulumWrapper", "initialize", f"ERROR deploying RNS patches: {e}")
                    import traceback
                    log_error("ReticulumWrapper", "initialize", f"Traceback: {traceback.format_exc()}")

                # NOW import RNS and LXMF for the first time (will load patched code)
                log_info("ReticulumWrapper", "initialize", "Importing RNS and LXMF (will use patched code)")
                try:
                    import RNS as _RNS
                    import LXMF as _LXMF
                    RNS = _RNS
                    LXMF = _LXMF
                    RETICULUM_AVAILABLE = True
                    log_info("ReticulumWrapper", "initialize", "✓ RNS and LXMF imported successfully")

                    # Configure TCPClientInterface for asynchronous startup
                    # This prevents blocking when TCP targets are unreachable
                    # The interface's initial_connect() already handles success/failure
                    # and spawns reconnect threads - we just move it off the main thread
                    try:
                        from RNS.Interfaces.TCPInterface import TCPClientInterface
                        TCPClientInterface.SYNCHRONOUS_START = False
                        log_info("ReticulumWrapper", "initialize", "✓ TCPClientInterface configured for async startup")
                    except (ImportError, AttributeError) as tcp_err:
                        log_warning("ReticulumWrapper", "initialize", f"Could not configure async TCP startup: {tcp_err}")

                except ImportError as e:
                    RETICULUM_AVAILABLE = False
                    log_error("ReticulumWrapper", "initialize", f"Failed to import RNS/LXMF: {e}")
                    return {"success": False, "error": f"Reticulum not available: {e}"}

            log_debug("ReticulumWrapper", "initialize", f"RETICULUM_AVAILABLE = {RETICULUM_AVAILABLE}")
            if not RETICULUM_AVAILABLE:
                log_error("ReticulumWrapper", "initialize", "Reticulum not available")
                return {"success": False, "error": "Reticulum not available"}

            log_debug("ReticulumWrapper", "initialize", f"Parsing config JSON (length: {len(config_json)})")
            log_debug("ReticulumWrapper", "initialize", f"Config JSON: {config_json}")

            config = json.loads(config_json)
            log_info("ReticulumWrapper", "initialize", "Config parsed successfully")
            log_debug("ReticulumWrapper", "initialize", f"Config keys: {list(config.keys())}")
            log_debug("ReticulumWrapper", "initialize", f"storagePath: {config.get('storagePath')}")
            log_debug("ReticulumWrapper", "initialize", f"logLevel: {config.get('logLevel')}")
            log_debug("ReticulumWrapper", "initialize", f"enabledInterfaces: {config.get('enabledInterfaces')}")

            # Extract identity_file_path from config if provided
            # This allows Kotlin to pass it via JSON config instead of as a separate parameter
            if not identity_file_path and 'identity_file_path' in config:
                identity_file_path = config['identity_file_path']
                log_info("ReticulumWrapper", "initialize", f"Identity file path from config: {identity_file_path}")

            # Extract display_name from config if provided
            display_name = config.get('display_name', 'Anonymous Peer')
            log_info("ReticulumWrapper", "initialize", f"Display name from config: {display_name}")

            # Extract prefer_own_instance setting (defaults to False)
            prefer_own_instance = config.get('prefer_own_instance', False)
            log_info("ReticulumWrapper", "initialize", f"Prefer own instance: {prefer_own_instance}")

            # Extract rpc_key for shared instance authentication (optional)
            # On Android, apps have separate config directories, so RPC key must be shared
            # Export from Sideband: Connectivity → Share Instance Access
            rpc_key = config.get('rpc_key', None)
            if rpc_key:
                log_info("ReticulumWrapper", "initialize", "RPC key provided for shared instance auth")
            else:
                log_debug("ReticulumWrapper", "initialize", "No RPC key provided")

            # Extract enable_transport setting (defaults to True)
            # When True, this device forwards traffic for the mesh network
            # When False, only handles its own traffic
            enable_transport = config.get('enable_transport', True)
            log_info("ReticulumWrapper", "initialize", f"Transport node enabled: {enable_transport}")

            # Check for shared instance if user doesn't prefer their own
            use_shared_instance = False
            if not prefer_own_instance:
                log_info("ReticulumWrapper", "initialize", "Checking for shared Reticulum instance...")
                if self.check_shared_instance_available():
                    use_shared_instance = True
                    log_info("ReticulumWrapper", "initialize", "✓ Will connect to shared instance")
                else:
                    log_info("ReticulumWrapper", "initialize", "No shared instance found - will create own instance")
            else:
                log_info("ReticulumWrapper", "initialize", "User prefers own instance - skipping shared instance check")

            # Store shared instance status
            self.is_shared_instance = use_shared_instance

            # Create config file from interface configurations
            log_info("ReticulumWrapper", "initialize", "Creating RNS config file from interface configurations")
            enabled_interfaces = config.get('enabledInterfaces', [])
            # Respect user's choice - if they want 0 interfaces, allow it
            # RNS will run without interfaces (no network connectivity)

            if not self._create_config_file(enabled_interfaces, use_shared_instance=use_shared_instance, rpc_key=rpc_key, enable_transport=enable_transport):
                return {"success": False, "error": "Failed to create config file"}

            # Set log level
            log_info("ReticulumWrapper", "initialize", "Setting RNS log level")
            log_level_map = {
                "CRITICAL": RNS.LOG_CRITICAL,
                "ERROR": RNS.LOG_ERROR,
                "WARNING": RNS.LOG_WARNING,
                "INFO": RNS.LOG_INFO,
                "DEBUG": RNS.LOG_DEBUG,
                "VERBOSE": RNS.LOG_VERBOSE,
                "EXTREME": RNS.LOG_EXTREME
            }
            log_level_str = config.get("logLevel", "DEBUG").upper()
            log_level = log_level_map.get(log_level_str, RNS.LOG_DEBUG)
            log_debug("ReticulumWrapper", "initialize", f"Setting RNS.loglevel to {log_level_str} ({log_level})")
            RNS.loglevel = log_level

            # Add ble-reticulum to path for imports
            # Note: When running under Chaquopy, the external/ble-reticulum/src is bundled in sourceSets
            try:
                _ble_reticulum_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'external', 'ble-reticulum', 'src'))
                if os.path.exists(_ble_reticulum_path) and _ble_reticulum_path not in sys.path:
                    sys.path.insert(0, _ble_reticulum_path)
            except (OSError, FileNotFoundError):
                # Under Chaquopy, __file__ may be a virtual path - modules are bundled and directly importable
                pass

            # Deploy custom AndroidBLE interface to RNS interfaces directory
            # This allows RNS to discover and load it like any built-in interface
            log_info("ReticulumWrapper", "initialize", "Deploying AndroidBLE custom interface and driver")
            try:
                interfaces_dir = os.path.join(self.storage_path, "interfaces")
                os.makedirs(interfaces_dir, exist_ok=True)
                drivers_dir = os.path.join(interfaces_dir, "drivers")
                os.makedirs(drivers_dir, exist_ok=True)
                log_debug("ReticulumWrapper", "initialize", f"Interfaces directory: {interfaces_dir}")
                log_debug("ReticulumWrapper", "initialize", f"Drivers directory: {drivers_dir}")

                # Deploy BLE interface files from bundled ble_modules directory
                # Import ble_modules to trigger extraction from APK (Chaquopy requirement)
                log_debug("ReticulumWrapper", "initialize", "Deploying BLE modules from bundled sources")
                import pkgutil
                import ble_reticulum  # Triggers extraction of package files from APK to filesystem
                import ble_modules  # Android-specific BLE modules

                # Deploy bluetooth_driver base interface
                log_debug("ReticulumWrapper", "initialize", "Deploying bluetooth_driver from bundled source")
                bluetooth_driver_bytes = pkgutil.get_data('ble_reticulum', 'bluetooth_driver.py')
                bluetooth_driver_dest = os.path.join(interfaces_dir, "bluetooth_driver.py")
                with open(bluetooth_driver_dest, 'wb') as f:
                    f.write(bluetooth_driver_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed bluetooth_driver to {bluetooth_driver_dest}")

                # Deploy linux_bluetooth_driver
                log_debug("ReticulumWrapper", "initialize", "Deploying linux_bluetooth_driver from bundled source")
                linux_bluetooth_driver_bytes = pkgutil.get_data('ble_reticulum', 'linux_bluetooth_driver.py')
                linux_bluetooth_driver_dest = os.path.join(interfaces_dir, "linux_bluetooth_driver.py")
                with open(linux_bluetooth_driver_dest, 'wb') as f:
                    f.write(linux_bluetooth_driver_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed linux_bluetooth_driver to {linux_bluetooth_driver_dest}")

                # Deploy BLEFragmentation
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEFragmentation from bundled source")
                ble_fragmentation_bytes = pkgutil.get_data('ble_reticulum', 'BLEFragmentation.py')
                ble_fragmentation_dest = os.path.join(interfaces_dir, "BLEFragmentation.py")
                with open(ble_fragmentation_dest, 'wb') as f:
                    f.write(ble_fragmentation_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed BLEFragmentation to {ble_fragmentation_dest}")

                # Deploy BLEGATTServer
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEGATTServer from bundled source")
                ble_gatt_server_bytes = pkgutil.get_data('ble_reticulum', 'BLEGATTServer.py')
                ble_gatt_server_dest = os.path.join(interfaces_dir, "BLEGATTServer.py")
                with open(ble_gatt_server_dest, 'wb') as f:
                    f.write(ble_gatt_server_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed BLEGATTServer to {ble_gatt_server_dest}")

                # Deploy BLEInterface
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEInterface from bundled source")
                ble_interface_bytes = pkgutil.get_data('ble_reticulum', 'BLEInterface.py')
                ble_interface_dest = os.path.join(interfaces_dir, "BLEInterface.py")
                with open(ble_interface_dest, 'wb') as f:
                    f.write(ble_interface_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed BLEInterface to {ble_interface_dest}")

                # Deploy AndroidBLEInterface
                log_debug("ReticulumWrapper", "initialize", "Deploying AndroidBLEInterface from bundled source")
                android_ble_interface_bytes = pkgutil.get_data('ble_modules', 'android_ble_interface.py')
                android_ble_interface_dest = os.path.join(interfaces_dir, "AndroidBLE.py")
                with open(android_ble_interface_dest, 'wb') as f:
                    f.write(android_ble_interface_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed AndroidBLEInterface to {android_ble_interface_dest}")

                # Deploy AndroidBLEDriver
                log_debug("ReticulumWrapper", "initialize", "Deploying AndroidBLEDriver from bundled source")
                android_ble_driver_bytes = pkgutil.get_data('ble_modules', 'android_ble_driver.py')
                android_ble_driver_dest = os.path.join(drivers_dir, "android_ble_driver.py")
                with open(android_ble_driver_dest, 'wb') as f:
                    f.write(android_ble_driver_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed AndroidBLEDriver to {android_ble_driver_dest}")

                # Create __init__.py in drivers directory
                init_dest = os.path.join(drivers_dir, "__init__.py")
                with open(init_dest, 'w') as f:
                    f.write("# Drivers package\n")
                log_debug("ReticulumWrapper", "initialize", f"✓ Created {init_dest}")

            except Exception as e:
                log_error("ReticulumWrapper", "initialize", f"ERROR deploying AndroidBLE: {type(e).__name__}: {e}")
                import traceback
                log_error("ReticulumWrapper", "initialize", f"Traceback: {traceback.format_exc()}")
                # Non-fatal - continue, but interface won't be discovered

            # Fix for runtimes where socket.if_nametoindex() doesn't work reliably
            # Windows lacks the function entirely, Android/Chaquopy stubs it but it may fail on real interfaces
            try:
                from RNS.Interfaces import AutoInterface
                import RNS.vendor.platformutils as platformutils

                _use_fallback = False
                _reason = None

                if platformutils.is_windows():
                    _use_fallback = True
                    _reason = "Windows"
                elif platformutils.is_android():
                    # Chaquopy may stub socket.if_nametoindex but it can fail on real network interfaces
                    # Always use netinfo fallback on Android for reliability
                    _use_fallback = True
                    _reason = "Android/Chaquopy"
                else:
                    # Test if socket.if_nametoindex works on this platform
                    import socket as _test_socket
                    try:
                        _test_socket.if_nametoindex("lo")
                    except (OSError, AttributeError):
                        _use_fallback = True
                        _reason = "socket.if_nametoindex unavailable"

                if _use_fallback:
                    _original_interface_name_to_index = AutoInterface.AutoInterface.interface_name_to_index

                    def _patched_interface_name_to_index(self, ifname):
                        """Use netinfo fallback when socket.if_nametoindex() is unavailable."""
                        return self.netinfo.interface_names_to_indexes()[ifname]

                    AutoInterface.AutoInterface.interface_name_to_index = _patched_interface_name_to_index
                    log_info("ReticulumWrapper", "initialize",
                             f"✓ Using netinfo fallback for interface_name_to_index ({_reason})")

            except Exception as e:
                log_warning("ReticulumWrapper", "initialize",
                          f"Could not check/patch interface_name_to_index (non-fatal): {type(e).__name__}: {e}")

            # Initialize Reticulum - it will load config from the config file we created
            log_info("ReticulumWrapper", "initialize", "Creating RNS.Reticulum instance")
            log_debug("ReticulumWrapper", "initialize", f"configdir = {self.storage_path}")

            # Track which interfaces failed to initialize
            self.failed_interfaces = []

            # Proactively check if AutoInterface ports are available
            # This prevents RNS from calling sys.exit(255) on port conflicts
            has_autointerface = any(
                iface.get('type') == 'AutoInterface'
                for iface in enabled_interfaces
            )
            log_debug("ReticulumWrapper", "initialize", f"has_autointerface = {has_autointerface}")

            # Pre-check if AutoInterface ports are available
            # AutoInterface uses IPv6 multicast (discovery) and IPv6 link-local (data)
            if has_autointerface:
                log_info("ReticulumWrapper", "initialize", "Pre-checking AutoInterface port availability...")
                try:
                    import socket

                    # AutoInterface uses:
                    # - Port 29716 for discovery (IPv6 multicast)
                    # - Port 42671 for data (IPv6 link-local)
                    # Check both by trying to bind an IPv6 UDP socket

                    ports_to_check = [29716, 42671]
                    port_conflict = False

                    for test_port in ports_to_check:
                        log_debug("ReticulumWrapper", "initialize", f"Testing IPv6 UDP bind to port {test_port}...")
                        try:
                            # Use IPv6 socket since AutoInterface uses IPv6
                            # Don't use SO_REUSEADDR - we want to detect conflicts
                            sock = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
                            # Bind to all IPv6 interfaces
                            sock.bind(('::', test_port))
                            sock.close()
                            log_debug("ReticulumWrapper", "initialize", f"Port {test_port} is available")
                        except OSError as port_error:
                            if port_error.errno == 98:  # Address already in use
                                log_warning("ReticulumWrapper", "initialize",
                                            f"⚠️ Port {test_port} is already in use - another Reticulum app may be running")
                                port_conflict = True
                                self.failed_interfaces.append({
                                    "name": "AutoInterface",
                                    "error": f"Port {test_port} already in use (another Reticulum app may be running)",
                                    "recoverable": True
                                })
                                break
                            else:
                                log_debug("ReticulumWrapper", "initialize", f"Port check failed with error: {port_error}")

                    if port_conflict:
                        log_info("ReticulumWrapper", "initialize", "Disabling AutoInterface to prevent crash...")
                        self._remove_autointerface_from_config()
                        has_autointerface = False
                        log_info("ReticulumWrapper", "initialize", "AutoInterface removed from config")

                except Exception as check_error:
                    log_debug("ReticulumWrapper", "initialize", f"Port pre-check failed: {check_error}")
                    # Non-critical, continue

            # Now initialize RNS
            log_info("ReticulumWrapper", "initialize", "Calling RNS.Reticulum()...")
            try:
                self.reticulum = RNS.Reticulum(configdir=self.storage_path)
                log_info("ReticulumWrapper", "initialize", "RNS.Reticulum created successfully")
            except SystemExit as e:
                # This shouldn't happen now that we pre-check, but keep as safety net
                log_warning("ReticulumWrapper", "initialize", f"RNS called sys.exit({e.code}) - likely interface failure")
                raise
            except OSError as e:
                log_error("ReticulumWrapper", "initialize", f"OSError during RNS init: {e}")
                raise

            # Clear stale BLE paths (bug workaround)
            log_info("ReticulumWrapper", "initialize", "Clearing stale BLE paths from previous session")
            self._clear_stale_ble_paths()

            # Extract Transport identity for BLE Protocol v2
            log_info("ReticulumWrapper", "initialize", "Extracting Transport identity hash for BLE")
            transport_identity = RNS.Transport.identity
            if transport_identity:
                transport_identity_hash = transport_identity.hash  # 16 bytes
                log_debug("ReticulumWrapper", "initialize", f"Transport identity hash: {transport_identity_hash.hex()}")

                # Store for later retrieval
                self.transport_identity_hash = transport_identity_hash
                log_info("ReticulumWrapper", "initialize", "Transport identity stored for BLE")
            else:
                log_warning("ReticulumWrapper", "initialize", "Could not get Transport identity")
                self.transport_identity_hash = None

            # Verify loaded interfaces match expectations
            loaded_count = len(RNS.Transport.interfaces)
            expected_count = len(enabled_interfaces)
            log_info("ReticulumWrapper", "initialize", f"RNS loaded with {loaded_count} interfaces")

            if loaded_count != expected_count:
                log_separator("ReticulumWrapper", "initialize")
                log_warning("ReticulumWrapper", "initialize", "Interface count mismatch!")
                log_warning("ReticulumWrapper", "initialize", f"Expected: {expected_count} interfaces")
                log_warning("ReticulumWrapper", "initialize", f"Loaded: {loaded_count} interfaces")
                log_warning("ReticulumWrapper", "initialize", "This may indicate a configuration problem")
                log_separator("ReticulumWrapper", "initialize")
            else:
                log_info("ReticulumWrapper", "initialize", f"✓ Verified: {loaded_count} interface(s) loaded as expected")

            # List loaded interfaces
            for idx, iface in enumerate(RNS.Transport.interfaces):
                log_debug("ReticulumWrapper", "initialize", f"Interface {idx}: {iface} ({type(iface).__name__})")

            # Register announce handlers for different aspects
            log_separator("ReticulumWrapper", "initialize")
            log_info("ReticulumWrapper", "initialize", "Registering aspect-specific announce handlers...")

            try:
                for aspect, handler in self._announce_handlers.items():
                    RNS.Transport.register_announce_handler(handler)
                    log_info("ReticulumWrapper", "initialize", f"✅ Registered handler for aspect: {aspect}")
            except Exception as e:
                log_warning("ReticulumWrapper", "initialize", f"⚠️ Announce handler registration failed: {e}")
                log_warning("ReticulumWrapper", "initialize", "This is expected in Chaquopy - Transport will still process announces automatically")

            log_separator("ReticulumWrapper", "initialize")

            # Load identity for LXMF (use provided path or default)
            if identity_file_path:
                log_info("ReticulumWrapper", "initialize", f"Loading identity from specified path: {identity_file_path}")
                identity_path = identity_file_path
            else:
                log_info("ReticulumWrapper", "initialize", "Loading default identity for LXMF")
                identity_path = os.path.join(self.storage_path, "default_identity")

            default_identity = None

            if os.path.exists(identity_path):
                try:
                    default_identity = RNS.Identity.from_file(identity_path)
                    log_info("ReticulumWrapper", "initialize", f"Loaded identity: {default_identity.hash.hex()[:16]}")
                except Exception as e:
                    log_error("ReticulumWrapper", "initialize", f"Could not load identity from {identity_path}: {e}")
                    # If a specific path was provided and failed, don't fall back - raise error
                    if identity_file_path:
                        raise Exception(f"Failed to load identity from {identity_path}: {e}")

            if not default_identity:
                # If a specific identity file path was provided but doesn't exist, fail clearly
                # (Kotlin should have recovered the file from keyData before calling initialize)
                if identity_file_path:
                    raise Exception(f"identity_file_missing:{identity_path}")

                # Create a new identity only if no specific path was requested
                log_info("ReticulumWrapper", "initialize", f"No identity found, creating new identity at {identity_path}")
                default_identity = RNS.Identity()
                try:
                    # Ensure the directory exists
                    os.makedirs(os.path.dirname(identity_path), exist_ok=True)
                    default_identity.to_file(identity_path)
                    log_info("ReticulumWrapper", "initialize", f"Saved new identity: {default_identity.hash.hex()[:16]}")
                except Exception as e:
                    log_error("ReticulumWrapper", "initialize", f"Could not save identity to {identity_path}: {e}")
                    raise Exception(f"Failed to create identity: {e}")

            # Initialize LXMF router with the default identity
            log_info("ReticulumWrapper", "initialize", "Creating LXMF router with default identity")
            log_info("ReticulumWrapper", "initialize", f"Incoming message limit: {incoming_message_limit_kb}KB")
            self.router = LXMF.LXMRouter(
                storagepath=self.storage_path,
                identity=default_identity,
                autopeer=True,
                delivery_limit=incoming_message_limit_kb,
                propagation_limit=incoming_message_limit_kb
            )
            log_info("ReticulumWrapper", "initialize", "LXMF router created")

            # Create local LXMF destination for receiving messages
            log_info("ReticulumWrapper", "initialize", "Creating local LXMF delivery destination")
            log_debug("ReticulumWrapper", "initialize", f"Using identity hash: {default_identity.hash.hex()[:16]}")
            self.local_lxmf_destination = self.router.register_delivery_identity(
                default_identity,
                display_name=display_name
            )
            # Store display name for use in announces
            self.display_name = display_name
            # Store default identity for use in propagation node requests
            self.default_identity = default_identity
            log_info("ReticulumWrapper", "initialize", f"Local LXMF destination: {self.local_lxmf_destination.hexhash}")
            log_debug("ReticulumWrapper", "initialize", f"(Identity hash: {default_identity.hash.hex()}, Dest hash: {self.local_lxmf_destination.hexhash})")

            # Register delivery callback to capture incoming messages
            log_info("ReticulumWrapper", "initialize", "Registering delivery callback for incoming messages")
            self.router.register_delivery_callback(self._on_lxmf_delivery)
            log_info("ReticulumWrapper", "initialize", "✅ Delivery callback registered")

            # Add LXMF destination to tracking dict so it can be announced
            self.destinations[self.local_lxmf_destination.hexhash] = self.local_lxmf_destination
            log_debug("ReticulumWrapper", "initialize", "Added LXMF destination to tracking dict")

            # Set last poll time to current time to only return new announces after initialization
            self.last_announce_poll_time = time.time()
            log_debug("ReticulumWrapper", "initialize", "Set last_announce_poll_time to current time")

            self.initialized = True

            # Start background service threads (Sideband-inspired)
            # These run while self.initialized is True and exit cleanly on shutdown
            self._start_opportunistic_timer()  # Opportunistic message timeout checker
            self._start_heartbeat_thread()     # Heartbeat for Kotlin health monitoring
            self._start_maintenance_thread()   # Interface recovery and maintenance

            log_separator("ReticulumWrapper", "initialize")
            log_info("ReticulumWrapper", "initialize", "Reticulum initialized successfully")
            log_info("ReticulumWrapper", "initialize", f"Shared instance mode: {self.is_shared_instance}")
            log_separator("ReticulumWrapper", "initialize")
            return {"success": True, "is_shared_instance": self.is_shared_instance}

        except Exception as e:
            log_separator("ReticulumWrapper", "initialize")
            log_error("ReticulumWrapper", "initialize", "ERROR initializing Reticulum")
            log_error("ReticulumWrapper", "initialize", f"Exception type: {type(e).__name__}")
            log_error("ReticulumWrapper", "initialize", f"Exception message: {str(e)}")
            log_separator("ReticulumWrapper", "initialize")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def _remove_autointerface_from_config(self):
        """
        Remove AutoInterface from the RNS config file.
        This is used when AutoInterface fails to bind (address already in use)
        to retry initialization without it.
        """
        config_path = os.path.join(self.storage_path, "config")

        if not os.path.exists(config_path):
            log_warning("ReticulumWrapper", "_remove_autointerface_from_config", "Config file not found")
            return

        try:
            # Read current config
            with open(config_path, 'r') as f:
                lines = f.readlines()

            # Remove AutoInterface section
            new_lines = []
            skip_section = False
            for line in lines:
                # Check if we're entering AutoInterface section
                if line.strip().startswith("[[") and "Auto Discovery" in line:
                    skip_section = True
                    log_debug("ReticulumWrapper", "_remove_autointerface_from_config", "Removing AutoInterface section")
                    continue

                # Check if we're leaving the section (new section starts)
                if skip_section and line.strip().startswith("[["):
                    skip_section = False

                # Keep line if not in AutoInterface section
                if not skip_section:
                    new_lines.append(line)

            # Write modified config back
            with open(config_path, 'w') as f:
                f.writelines(new_lines)

            log_info("ReticulumWrapper", "_remove_autointerface_from_config", "✓ AutoInterface removed from config file")

        except Exception as e:
            log_error("ReticulumWrapper", "_remove_autointerface_from_config", f"Failed to modify config: {e}")
            raise

    def _setup_interface(self, iface_config: Dict):
        """Set up a network interface based on configuration"""
        iface_type = iface_config.get("type")

        if iface_type == "AutoInterface":
            log_info("ReticulumWrapper", "_setup_interface", "Setting up AutoInterface")
            # AutoInterface automatically discovers peers on local network
            auto_iface = RNS.Interfaces.AutoInterface.AutoInterface(
                RNS.Transport,
                "AutoInterface"
            )
            auto_iface.OUT = True
            RNS.Transport.interfaces.append(auto_iface)

        elif iface_type == "TCPClientInterface":
            log_info("ReticulumWrapper", "_setup_interface", f"Setting up TCPClientInterface: {iface_config}")
            target_host = iface_config.get("host", "127.0.0.1")
            target_port = iface_config.get("port", 4242)
            tcp_iface = RNS.Interfaces.TCPInterface.TCPClientInterface(
                RNS.Transport,
                "TCPClientInterface",
                target_host,
                target_port
            )
            tcp_iface.OUT = True
            RNS.Transport.interfaces.append(tcp_iface)

        elif iface_type == "UDPInterface":
            log_info("ReticulumWrapper", "_setup_interface", f"Setting up UDPInterface: {iface_config}")
            port = iface_config.get("port", 4242)
            udp_iface = RNS.Interfaces.UDPInterface.UDPInterface(
                RNS.Transport,
                "UDPInterface",
                port
            )
            udp_iface.OUT = True
            RNS.Transport.interfaces.append(udp_iface)

        else:
            log_info("ReticulumWrapper", "_setup_interface", f"Unknown interface type: {iface_type}")

    def _announce_handler(self, aspect, destination_hash, announced_identity, app_data, announce_packet_hash=None):
        """
        Internal handler for announces from RNS.
        This is called by RNS when an announce is received.

        Args:
            aspect: The aspect filter that matched this announce (e.g., "lxmf.delivery", "call.audio")
            destination_hash: The destination hash that announced
            announced_identity: The RNS.Identity object of the announcing peer
            app_data: Application-specific data included in the announce
            announce_packet_hash: Hash of the announce packet (optional, for future use)
        """
        log_separator("ReticulumWrapper", "_announce_handler", "!", 60)
        log_info("ReticulumWrapper", "_announce_handler", "🔔 _announce_handler CALLED! (CALLBACK PATH WORKING)")
        log_info("ReticulumWrapper", "_announce_handler", f"Aspect: {aspect}")
        log_info("ReticulumWrapper", "_announce_handler", f"Destination: {destination_hash.hex()[:16]}...")
        log_separator("ReticulumWrapper", "_announce_handler", "!", 60)
        try:
            log_debug("ReticulumWrapper", "_announce_handler", f"Announce received from: {destination_hash.hex()}")
            log_debug("ReticulumWrapper", "_announce_handler", f"Aspect: {aspect}")
            log_debug("ReticulumWrapper", "_announce_handler", f"Has identity: {announced_identity is not None}")
            log_debug("ReticulumWrapper", "_announce_handler", f"App data: {app_data}")

            # Get hop count
            hops = RNS.Transport.hops_to(destination_hash)
            if hops is None or hops == RNS.Transport.PATHFINDER_M:
                hops = 0  # Direct or unknown

            # Get receiving interface from announce_table packet
            receiving_interface = None
            try:
                if hasattr(RNS.Transport, 'announce_table') and destination_hash in RNS.Transport.announce_table:
                    announce_entry = RNS.Transport.announce_table[destination_hash]
                    if len(announce_entry) > 5:
                        packet = announce_entry[5]  # IDX_AT_PACKET
                        if packet and hasattr(packet, 'receiving_interface'):
                            interface_obj = packet.receiving_interface
                            if interface_obj:
                                # Use class name to identify interface type (reliable, not user-configured)
                                class_name = type(interface_obj).__name__
                                if "AutoInterface" in class_name:
                                    receiving_interface = "AutoInterface"
                                else:
                                    receiving_interface = class_name
            except Exception as e:
                log_debug("ReticulumWrapper", "_announce_handler",
                         f"Could not extract interface: {e}")

            # Extract display name using LXMF's canonical implementation
            # Use the correct function based on aspect:
            # - lxmf.delivery: display_name_from_app_data() for peer names
            # - lxmf.propagation: pn_name_from_app_data() for propagation node names
            display_name = None
            if LXMF is not None and app_data:
                try:
                    if aspect == "lxmf.propagation":
                        display_name = LXMF.pn_name_from_app_data(app_data)
                        log_debug("ReticulumWrapper", "_announce_handler",
                                  f"LXMF.pn_name_from_app_data returned: {display_name}")
                    else:
                        display_name = LXMF.display_name_from_app_data(app_data)
                        log_debug("ReticulumWrapper", "_announce_handler",
                                  f"LXMF.display_name_from_app_data returned: {display_name}")
                except Exception as e:
                    log_debug("ReticulumWrapper", "_announce_handler",
                              f"LXMF name extraction failed: {e}")

            # Extract stamp cost using LXMF's canonical functions
            stamp_cost = None
            stamp_cost_flexibility = None
            peering_cost = None
            if LXMF is not None and app_data:
                try:
                    if aspect == "lxmf.propagation":
                        stamp_cost = LXMF.pn_stamp_cost_from_app_data(app_data)
                        # Also extract flexibility and peering cost for propagation nodes
                        if LXMF.pn_announce_data_is_valid(app_data):
                            from RNS.vendor import umsgpack
                            data = umsgpack.unpackb(app_data)
                            stamp_cost_flexibility = int(data[5][1])
                            peering_cost = int(data[5][2])
                        else:
                            # Log why validation failed - helps diagnose auto-selection issues
                            log_info("ReticulumWrapper", "_announce_handler",
                                     f"⚠️ Propagation node {destination_hash[:16]}... has invalid/deprecated announce data "
                                     f"(pn_announce_data_is_valid=False), stamp_cost_flexibility will be NULL")
                        log_debug("ReticulumWrapper", "_announce_handler",
                                  f"PN stamp cost: {stamp_cost}, flex: {stamp_cost_flexibility}, peer: {peering_cost}")
                    else:
                        stamp_cost = LXMF.stamp_cost_from_app_data(app_data)
                        log_debug("ReticulumWrapper", "_announce_handler",
                                  f"Peer stamp cost: {stamp_cost}")
                except Exception as e:
                    log_debug("ReticulumWrapper", "_announce_handler",
                              f"Stamp cost extraction failed: {e}")

            # Create announce event dict (Transport already stores identity/app_data)
            announce_event = {
                'destination_hash': destination_hash,
                'identity_hash': destination_hash,  # For single destinations
                'public_key': announced_identity.get_public_key() if announced_identity else b'',
                'app_data': app_data if app_data else b'',
                'display_name': display_name,  # Pre-parsed by LXMF (may be None)
                'stamp_cost': stamp_cost,  # Pre-parsed by LXMF (may be None)
                'stamp_cost_flexibility': stamp_cost_flexibility,  # For propagation nodes
                'peering_cost': peering_cost,  # For propagation nodes
                'aspect': aspect,  # Include aspect (e.g., "lxmf.delivery", "call.audio")
                'hops': hops,
                'timestamp': int(time.time() * 1000),  # milliseconds
                'interface': receiving_interface,  # Add interface name
            }

            # Handle RMSP map server announces specially
            if aspect == "rmsp.maps" and app_data:
                try:
                    # Parse RMSP announce and register server
                    self.parse_rmsp_announce(destination_hash, announced_identity, app_data, hops)

                    # Also parse RMSP-specific fields for Kotlin
                    # Import umsgpack from RNS vendor (same as used elsewhere in this function)
                    from RNS.vendor import umsgpack as rmsp_msgpack

                    rmsp_data = rmsp_msgpack.unpackb(app_data)
                    announce_event['rmsp_server_name'] = rmsp_data.get('n', 'Unknown')
                    announce_event['rmsp_version'] = rmsp_data.get('v', '0.0.0')
                    announce_event['rmsp_coverage'] = rmsp_data.get('c', [])
                    announce_event['rmsp_zoom_range'] = rmsp_data.get('z', [0, 15])
                    announce_event['rmsp_formats'] = rmsp_data.get('f', ['pmtiles'])
                    announce_event['rmsp_layers'] = rmsp_data.get('l', ['osm'])
                    announce_event['rmsp_updated'] = rmsp_data.get('u', 0)
                    announce_event['rmsp_size'] = rmsp_data.get('s')

                    log_info("ReticulumWrapper", "_announce_handler",
                            f"RMSP server announce: {announce_event['rmsp_server_name']}")
                except Exception as e:
                    log_warning("ReticulumWrapper", "_announce_handler",
                               f"Failed to parse RMSP announce data: {e}")

            # Store in pending queue for Kotlin to retrieve
            with self.announce_lock:
                self.pending_announces.append(announce_event)

            # Notify Kotlin immediately via bridge (event-driven announce delivery)
            if self.kotlin_reticulum_bridge:
                try:
                    # Defensive check: ensure bridge is still valid
                    if not hasattr(self.kotlin_reticulum_bridge, 'notifyAnnounceReceived'):
                        log_error("ReticulumWrapper", "_announce_handler",
                                  f"Bridge object corrupted: {type(self.kotlin_reticulum_bridge)} = {self.kotlin_reticulum_bridge}")
                        self.kotlin_reticulum_bridge = None
                    else:
                        self.kotlin_reticulum_bridge.notifyAnnounceReceived()
                except Exception as e:
                    log_error("ReticulumWrapper", "_announce_handler",
                              f"Kotlin announce notification failed: {e}")

            # Also call any registered callbacks (for compatibility)
            for callback in self.announce_callbacks:
                try:
                    callback(announce_event)
                except Exception as e:
                    log_error("ReticulumWrapper", "_announce_handler", f"Error in announce callback: {e}")
                    import traceback
                    traceback.print_exc()

        except Exception as e:
            log_error("ReticulumWrapper", "_announce_handler", f"Error in announce handler: {e}")
            import traceback
            traceback.print_exc()

    def shutdown(self) -> Dict:
        """Shutdown Reticulum properly, cleaning up all resources"""
        try:
            log_separator("ReticulumWrapper", "shutdown", "=", 60)
            log_debug("ReticulumWrapper", "shutdown", "shutdown() called")
            log_separator("ReticulumWrapper", "shutdown", "=", 60)

            if not self.initialized:
                log_info("ReticulumWrapper", "shutdown", "Not initialized, nothing to shutdown")
                return {"success": True}

            # Step 1: Deregister announce handler
            if RETICULUM_AVAILABLE and self.reticulum:
                try:
                    log_debug("ReticulumWrapper", "shutdown", "Deregistering announce handlers")
                    for aspect, handler in self._announce_handlers.items():
                        RNS.Transport.deregister_announce_handler(handler)
                        log_debug("ReticulumWrapper", "shutdown", f"Deregistered handler for aspect: {aspect}")
                except Exception as e:
                    log_debug("ReticulumWrapper", "shutdown", f"Note: couldn't deregister announce handlers: {e}")

            # Step 2: Clean up LXMF router
            if self.router:
                log_debug("ReticulumWrapper", "shutdown", "Cleaning up LXMF router")
                try:
                    # Clear any message callbacks
                    if hasattr(self.router, 'message_received_callback'):
                        self.router.message_received_callback = None
                except Exception as e:
                    log_error("ReticulumWrapper", "shutdown", f"Warning - error cleaning up LXMF router: {e}")
                self.router = None
                log_debug("ReticulumWrapper", "shutdown", "LXMF router cleaned up")

            # Step 3: Detach RNS interfaces
            # Note: We don't try to stop daemon threads - they'll keep running until process ends
            # Just detach interfaces to release resources
            if RETICULUM_AVAILABLE and self.reticulum:
                try:
                    log_debug("ReticulumWrapper", "shutdown", f"Detaching {len(RNS.Transport.interfaces)} interface(s)")
                    for iface in list(RNS.Transport.interfaces):
                        try:
                            if hasattr(iface, 'detach'):
                                iface.detach()
                        except Exception as e:
                            log_warning("ReticulumWrapper", "shutdown", f"Warning - couldn't detach interface {iface}: {e}")
                except Exception as e:
                    log_error("ReticulumWrapper", "shutdown", f"Warning - error detaching interfaces: {e}")

            # Step 4: Clear RNS singleton instance and Transport global state (critical!)
            # RNS uses class variables for singletons and global state tracking
            # We MUST clear these or reinitialize will fail
            if RETICULUM_AVAILABLE:
                try:
                    log_debug("ReticulumWrapper", "shutdown", "Clearing RNS.Reticulum singleton instance")
                    # Access the private class variable to clear the singleton
                    RNS.Reticulum._Reticulum__instance = None
                    log_debug("ReticulumWrapper", "shutdown", "RNS singleton cleared")
                except Exception as e:
                    log_warning("ReticulumWrapper", "shutdown", f"Warning - couldn't clear RNS singleton: {e}")

                try:
                    log_debug("ReticulumWrapper", "shutdown", "Clearing RNS.Transport global state")
                    # Clear Transport owner (prevents reinit)
                    if hasattr(RNS.Transport, 'owner'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing Transport.owner")
                        RNS.Transport.owner = None
                    # Clear Transport's interface lists
                    if hasattr(RNS.Transport, 'interfaces'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing {len(RNS.Transport.interfaces)} interfaces")
                        RNS.Transport.interfaces.clear()
                    # CRITICAL: Clear local client interfaces (prevents shared instance connection)
                    if hasattr(RNS.Transport, 'local_client_interfaces'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing {len(RNS.Transport.local_client_interfaces)} local client interfaces")
                        RNS.Transport.local_client_interfaces.clear()
                    # Clear local client caches
                    if hasattr(RNS.Transport, 'local_client_rssi_cache'):
                        RNS.Transport.local_client_rssi_cache.clear()
                    if hasattr(RNS.Transport, 'local_client_snr_cache'):
                        RNS.Transport.local_client_snr_cache.clear()
                    if hasattr(RNS.Transport, 'local_client_q_cache'):
                        RNS.Transport.local_client_q_cache.clear()
                    # Clear Transport's destination registries
                    if hasattr(RNS.Transport, 'destinations'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing {len(RNS.Transport.destinations)} registered destinations")
                        RNS.Transport.destinations.clear()
                    if hasattr(RNS.Transport, 'destination_table'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing destination_table with {len(RNS.Transport.destination_table)} entries")
                        RNS.Transport.destination_table.clear()
                    if hasattr(RNS.Transport, 'announce_table'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing announce_table with {len(RNS.Transport.announce_table)} entries")
                        RNS.Transport.announce_table.clear()
                    if hasattr(RNS.Transport, 'held_announces'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing held_announces")
                        RNS.Transport.held_announces.clear()
                    if hasattr(RNS.Transport, 'announce_handlers'):
                        log_debug("ReticulumWrapper", "shutdown", f"Clearing announce_handlers")
                        RNS.Transport.announce_handlers.clear()
                    log_info("ReticulumWrapper", "shutdown", "RNS.Transport global state cleared successfully")
                except Exception as e:
                    log_warning("ReticulumWrapper", "shutdown", f"Warning - couldn't clear Transport state: {e}")
                    import traceback
                    traceback.print_exc()

            # Step 5: Clear all wrapper state
            log_debug("ReticulumWrapper", "shutdown", "Clearing wrapper state")
            self.reticulum = None
            self.initialized = False
            self.announce_callbacks.clear()
            self.message_callbacks.clear()
            self.link_callbacks.clear()
            self.destinations.clear()
            self.identities.clear()
            self.pending_announces.clear()
            self.seen_announce_hashes.clear()
            self.seen_message_hashes.clear()

            # Step 6: Force garbage collection
            # Note: When service restarts, the process is killed so threads stop automatically
            # This cleanup is mainly for when shutdown() is called without restart
            log_debug("ReticulumWrapper", "shutdown", "Running garbage collection...")
            import gc
            gc.collect()
            log_debug("ReticulumWrapper", "shutdown", "Garbage collection complete")

            log_separator("ReticulumWrapper", "shutdown", "=", 60)
            log_debug("ReticulumWrapper", "shutdown", "Shutdown complete")
            log_separator("ReticulumWrapper", "shutdown", "=", 60)
            return {"success": True}
        except Exception as e:
            log_error("ReticulumWrapper", "shutdown", f"Error shutting down: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def get_status(self) -> str:
        """Get current network status"""
        if not RETICULUM_AVAILABLE or not self.reticulum:
            return "SHUTDOWN"
        # TODO: Implement proper status checking
        return "READY"

    def create_identity(self) -> Dict:
        """
        Create a new Reticulum identity.

        Returns:
            Dict with 'hash', 'public_key', and 'private_key' as byte arrays
        """
        try:
            if not RETICULUM_AVAILABLE:
                # Mock identity for testing
                return {
                    'hash': os.urandom(16),
                    'public_key': os.urandom(32),
                    'private_key': os.urandom(32)
                }

            identity = RNS.Identity()
            return {
                'hash': identity.hash,
                'public_key': identity.get_public_key(),
                'private_key': identity.get_private_key()
            }
        except Exception as e:
            raise RuntimeError(f"Failed to create identity: {e}")

    def load_identity(self, path: str) -> Dict:
        """Load an identity from file"""
        try:
            if not RETICULUM_AVAILABLE:
                raise NotImplementedError("Mock mode")

            identity = RNS.Identity.from_file(path)
            return {
                'hash': identity.hash,
                'public_key': identity.get_public_key(),
                'private_key': identity.get_private_key()
            }
        except Exception as e:
            raise RuntimeError(f"Failed to load identity: {e}")

    def save_identity(self, private_key: bytes, path: str) -> Dict:
        """Save an identity to file"""
        try:
            if not RETICULUM_AVAILABLE:
                return {"success": True}

            # Reconstruct identity from private key and save
            identity = RNS.Identity()
            identity.load_private_key(private_key)
            identity.to_file(path)
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def create_destination(
        self,
        identity_dict: Dict,
        direction: str,
        dest_type: str,
        app_name: str,
        aspects: List[str]
    ) -> Dict:
        """Create a destination"""
        try:
            if not RETICULUM_AVAILABLE:
                import hashlib
                # Mock destination
                dest_str = app_name + "".join(aspects)
                dest_hash = hashlib.sha256(dest_str.encode()).digest()[:16]
                return {
                    'hash': dest_hash,
                    'hex_hash': dest_hash.hex(),
                }

            # Reconstruct identity from dict
            identity = RNS.Identity()
            identity.load_private_key(identity_dict['private_key'])

            # Map direction and type
            rns_direction = RNS.Destination.IN if direction == "IN" else RNS.Destination.OUT

            if dest_type == "SINGLE":
                rns_type = RNS.Destination.SINGLE
            elif dest_type == "GROUP":
                rns_type = RNS.Destination.GROUP
            else:
                rns_type = RNS.Destination.PLAIN

            # Create destination
            destination = RNS.Destination(
                identity,
                rns_direction,
                rns_type,
                app_name,
                *aspects
            )

            # Store destination for later use (use hex hash as key)
            self.destinations[destination.hexhash] = destination

            return {
                'hash': destination.hash,
                'hex_hash': destination.hexhash,
            }
        except Exception as e:
            raise RuntimeError(f"Failed to create destination: {e}")

    def announce_destination(self, dest_hash, app_data=None) -> Dict:
        """Announce a destination on the network"""
        try:
            if not RETICULUM_AVAILABLE or not self.initialized:
                return {"success": False, "error": "Reticulum not initialized"}

            # Convert hash to bytes if it's a jarray/list (from Chaquopy)
            if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)

            # Convert app_data to bytes if it's a jarray/list (from Chaquopy)
            if app_data is not None:
                if hasattr(app_data, '__iter__') and not isinstance(app_data, (bytes, bytearray, str)):
                    app_data = bytes(app_data)
            else:
                # Use stored display_name as default app_data for LXMF announces
                app_data = self.display_name.encode('utf-8') if self.display_name else None

            # Convert hash to hex for dict lookup
            hex_hash = dest_hash.hex()
            log_debug("ReticulumWrapper", "announce_destination", f"Looking up destination with hash: {hex_hash}")

            # Try our tracking dict first
            destination = self.destinations.get(hex_hash)

            # If not found, check if it's the LXMF destination
            if not destination and self.local_lxmf_destination:
                if hex_hash == self.local_lxmf_destination.hexhash:
                    log_debug("ReticulumWrapper", "announce_destination", f"Using local LXMF destination for announce")
                    destination = self.local_lxmf_destination

            if not destination:
                log_debug("ReticulumWrapper", "announce_destination", f"Destination not found. Available: {list(self.destinations.keys())}, LXMF: {self.local_lxmf_destination.hexhash if self.local_lxmf_destination else 'None'}")
                return {"success": False, "error": f"Destination not found (hash: {hex_hash})"}

            # Announce the destination
            log_debug("ReticulumWrapper", "announce_destination", f"Announcing destination {hex_hash[:16]}... with app_data: {app_data}")
            destination.announce(app_data=app_data)
            log_info("ReticulumWrapper", "announce_destination", f"✅ Announced destination: {hex_hash[:16]}")
            return {"success": True}

        except Exception as e:
            log_error("ReticulumWrapper", "announce_destination", f"Error announcing destination: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def send_packet(self, dest_hash: bytes, data: bytes, packet_type: str = "DATA") -> Dict:
        """Send a packet to a destination"""
        try:
            if not RETICULUM_AVAILABLE:
                return {
                    'receipt_hash': os.urandom(32),
                    'delivered': True,
                    'timestamp': int(1000 * __import__('time').time())
                }

            # TODO: Implement packet sending
            # This requires maintaining a map of destination objects
            return {
                'receipt_hash': b'',
                'delivered': False,
                'timestamp': int(1000 * __import__('time').time())
            }
        except Exception as e:
            raise RuntimeError(f"Failed to send packet: {e}")

    def register_message_callback(self, callback: Callable):
        """Register a callback for incoming messages"""
        self.message_callbacks.append(callback)
        if RETICULUM_AVAILABLE and self.router:
            self.router.register_delivery_callback(self._on_message)

    def _on_lxmf_delivery(self, lxmf_message):
        """
        Delivery callback for LXMF router - called when messages are received.

        Phase 2.2 Enhancement: Now invokes Kotlin callback for event-driven notifications.
        Also adds to pending_inbound queue for backward compatibility with polling.
        """
        log_separator("ReticulumWrapper", "_on_lxmf_delivery", "!", 80)
        log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"📨 _on_lxmf_delivery CALLED! Message received!")
        log_separator("ReticulumWrapper", "_on_lxmf_delivery", "!", 80)
        try:
            log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"Message from: {lxmf_message.source_hash.hex()[:16]}")
            log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"Message to: {lxmf_message.destination_hash.hex()[:16]}")
            log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"Content length: {len(lxmf_message.content)} bytes")

            # ✅ PHASE 3: Check for location telemetry FIRST
            # Location-only messages should NOT be added to the regular message queue
            # Priority: FIELD_TELEMETRY (0x02) > FIELD_COLUMBA_META (0x70) > Legacy field 7
            is_location_only = False

            # Check if message has text content (needed for empty message filtering)
            content = lxmf_message.content
            has_text_content = False
            if content:
                if isinstance(content, bytes):
                    has_text_content = len(content.strip()) > 0
                elif isinstance(content, str):
                    has_text_content = len(content.strip()) > 0

            if self.kotlin_location_received_callback and hasattr(lxmf_message, 'fields') and lxmf_message.fields:

                location_event = None
                telemetry_source = None

                # Priority 1: Check FIELD_TELEMETRY (0x02) - Sideband-compatible format
                if FIELD_TELEMETRY in lxmf_message.fields:
                    telemetry_source = "FIELD_TELEMETRY"
                    try:
                        packed_data = lxmf_message.fields[FIELD_TELEMETRY]
                        location_event = unpack_location_telemetry(packed_data)
                        if location_event:
                            log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                    f"📍 Sideband-compatible telemetry received in FIELD_TELEMETRY (0x02)")
                    except Exception as e:
                        log_warning("ReticulumWrapper", "_on_lxmf_delivery",
                                   f"Failed to unpack FIELD_TELEMETRY: {e}")

                # Priority 2: Check FIELD_COLUMBA_META (0x70) for cease signals
                if FIELD_COLUMBA_META in lxmf_message.fields:
                    try:
                        meta_data = lxmf_message.fields[FIELD_COLUMBA_META]
                        if isinstance(meta_data, bytes):
                            meta_data = meta_data.decode('utf-8')
                        if isinstance(meta_data, str):
                            meta = json.loads(meta_data)

                            # Check for cease signal
                            if meta.get('cease', False):
                                log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                        f"📍 Cease signal received via FIELD_COLUMBA_META")
                                location_event = {
                                    "type": "location_share",
                                    "cease": True,
                                    "ts": int(time.time() * 1000),
                                }
                                telemetry_source = "FIELD_COLUMBA_META (cease)"

                            # Merge Columba-specific metadata if we have a location event
                            elif location_event:
                                if 'expires' in meta:
                                    location_event['expires'] = meta['expires']
                                if 'approxRadius' in meta:
                                    location_event['approxRadius'] = meta['approxRadius']
                    except Exception as e:
                        log_warning("ReticulumWrapper", "_on_lxmf_delivery",
                                   f"Failed to parse FIELD_COLUMBA_META: {e}")

                # Priority 3: Fallback to legacy field 7 for backwards compatibility
                if location_event is None and LEGACY_LOCATION_FIELD in lxmf_message.fields:
                    telemetry_source = "Legacy field 7"
                    try:
                        legacy_data = lxmf_message.fields[LEGACY_LOCATION_FIELD]
                        log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                f"📍 Legacy location telemetry received in field 7")

                        # Legacy format: JSON string as bytes or string
                        if isinstance(legacy_data, bytes):
                            location_json = legacy_data.decode('utf-8')
                        elif isinstance(legacy_data, str):
                            location_json = legacy_data
                        else:
                            location_json = None

                        if location_json:
                            location_event = json.loads(location_json)
                    except Exception as e:
                        log_warning("ReticulumWrapper", "_on_lxmf_delivery",
                                   f"Failed to parse legacy field 7: {e}")

                # Process the location event if we have one
                if location_event:
                    if not has_text_content:
                        is_location_only = True
                        log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                f"📍 Location-only message detected ({telemetry_source}), skipping message queue")

                    # Add source hash and invoke callback
                    location_event['source_hash'] = lxmf_message.source_hash.hex()

                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                             f"Location: lat={location_event.get('lat')}, lng={location_event.get('lng')}, cease={location_event.get('cease', False)}")

                    try:
                        self.kotlin_location_received_callback(json.dumps(location_event))
                        log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                "✅ Location callback invoked successfully")
                    except Exception as e:
                        log_error("ReticulumWrapper", "_on_lxmf_delivery",
                                 f"⚠️ Error invoking location callback: {e}")
                        import traceback
                        traceback.print_exc()

            # ✅ Check for emoji reaction (Field 16 with reaction_to key)
            # Reactions are lightweight messages with empty content and reaction data in Field 16
            APP_EXTENSIONS_FIELD = 16
            is_reaction = False

            if hasattr(lxmf_message, 'fields') and lxmf_message.fields:
                if APP_EXTENSIONS_FIELD in lxmf_message.fields:
                    field_16 = lxmf_message.fields[APP_EXTENSIONS_FIELD]
                    if isinstance(field_16, dict) and 'reaction_to' in field_16:
                        # This is a reaction message
                        is_reaction = True
                        log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                f"😀 Reaction detected in field {APP_EXTENSIONS_FIELD}")

                        # Process reaction
                        try:
                            reaction_event = {
                                'reaction_to': field_16.get('reaction_to', ''),
                                'emoji': field_16.get('emoji', ''),
                                'sender': field_16.get('sender', ''),
                                'source_hash': lxmf_message.source_hash.hex(),
                                'timestamp': int(time.time() * 1000)
                            }

                            log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                     f"Reaction: {reaction_event['emoji']} to message {reaction_event['reaction_to'][:16]}... from {reaction_event['sender'][:16]}...")

                            # Invoke Kotlin callback if registered
                            if self.kotlin_reaction_received_callback:
                                self.kotlin_reaction_received_callback(json.dumps(reaction_event))
                                log_info("ReticulumWrapper", "_on_lxmf_delivery",
                                        "✅ Reaction callback invoked successfully")
                            else:
                                log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                         "No reaction callback registered - reaction will be processed via polling")

                        except Exception as e:
                            log_error("ReticulumWrapper", "_on_lxmf_delivery",
                                     f"⚠️ Error processing reaction: {e}")
                            import traceback
                            traceback.print_exc()

            # Skip regular message processing for location-only or reaction-only messages
            if is_location_only or is_reaction:
                skip_reason = "location-only" if is_location_only else "reaction-only"
                log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                         f"Skipping regular message processing for {skip_reason} message")
                return
            
            # Skip truly empty messages (probe messages for link speed measurement)
            # These have no text content and no meaningful fields (image, file, telemetry, etc.)
            meaningful_fields = {
                FIELD_TELEMETRY,           # 0x02 - Location/sensor data
                FIELD_FILE_ATTACHMENTS,    # 0x05
                FIELD_IMAGE,               # 0x06
                FIELD_AUDIO,               # 0x07
            }
            has_meaningful_fields = False
            if hasattr(lxmf_message, 'fields') and lxmf_message.fields:
                has_meaningful_fields = any(f in lxmf_message.fields for f in meaningful_fields)
            
            if not has_text_content and not has_meaningful_fields:
                log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                         f"Skipping empty probe message from {lxmf_message.source_hash.hex()[:16]}")
                return

            # Add to pending_inbound queue (maintains backward compatibility with polling)
            if not hasattr(self.router, 'pending_inbound'):
                log_warning("ReticulumWrapper", "_on_lxmf_delivery", "Warning: Router has no pending_inbound, creating one")
                self.router.pending_inbound = []

            if lxmf_message not in self.router.pending_inbound:
                # Capture hop count and receiving interface at delivery time
                # For opportunistic messages: LXMF captures from packet directly (path_table may not exist)
                # For link-based messages: use path_table lookup (always populated during link setup)
                try:
                    source_hash = lxmf_message.source_hash

                    # Track whether we captured hops and interface
                    captured_hops = False
                    captured_interface = False

                    # First check if LXMF captured receiving_interface and hops from the packet
                    # (set by our patched delivery_packet for opportunistic messages)
                    # Only use if it's a real interface (has string name), not a Mock auto-attribute
                    receiving_interface = getattr(lxmf_message, 'receiving_interface', None)
                    if receiving_interface is not None:
                        # Use class name to identify interface type (reliable, not user-configured)
                        class_name = type(receiving_interface).__name__
                        # AutoInterfacePeer -> AutoInterface, otherwise use class name directly
                        if "AutoInterface" in class_name:
                            interface_type = "AutoInterface"
                        else:
                            interface_type = class_name
                        lxmf_message._columba_interface = interface_type
                        captured_interface = True
                        log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                 f"📡 Got interface from LXMF message (opportunistic): {interface_type}")

                    receiving_hops = getattr(lxmf_message, 'receiving_hops', None)
                    if isinstance(receiving_hops, int):
                        lxmf_message._columba_hops = receiving_hops
                        captured_hops = True
                        log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                 f"📡 Got hops from LXMF message (opportunistic): {receiving_hops}")

                    # Fallback to path_table for link-based messages or if LXMF didn't capture
                    if not captured_interface or not captured_hops:
                        if RNS.Transport.has_path(source_hash):
                            # Only capture hops from path_table if we don't already have them
                            if not captured_hops:
                                hops = RNS.Transport.hops_to(source_hash)
                                if hops is not None and hops >= 0:
                                    lxmf_message._columba_hops = hops
                                    captured_hops = True
                                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                             f"📡 Captured hop count from path_table: {hops}")

                            # Only capture interface from path_table if we don't already have it
                            if not captured_interface:
                                path_entry = RNS.Transport.path_table.get(source_hash)
                                if path_entry is not None and len(path_entry) > 5 and path_entry[5] is not None:
                                    interface_obj = path_entry[5]
                                    # Use class name to identify interface type (reliable, not user-configured)
                                    class_name = type(interface_obj).__name__
                                    if "AutoInterface" in class_name:
                                        interface_type = "AutoInterface"
                                    else:
                                        interface_type = class_name
                                    lxmf_message._columba_interface = interface_type
                                    captured_interface = True
                                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                             f"📡 Captured interface from path_table: {interface_type}")
                except Exception as e:
                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                             f"⚠️ Could not capture hop count/interface: {e}")

                self.router.pending_inbound.append(lxmf_message)
                log_info("ReticulumWrapper", "_on_lxmf_delivery", f"✅ Added message to pending_inbound queue (now has {len(self.router.pending_inbound)} messages)")
            else:
                log_debug("ReticulumWrapper", "_on_lxmf_delivery", "Message already in pending_inbound")

            # Parse icon appearance from message fields (Sideband/MeshChat interoperability)
            icon_appearance = None
            if hasattr(lxmf_message, 'fields') and lxmf_message.fields and FIELD_ICON_APPEARANCE in lxmf_message.fields:
                try:
                    icon_data = lxmf_message.fields[FIELD_ICON_APPEARANCE]
                    if isinstance(icon_data, list) and len(icon_data) >= 3:
                        icon_appearance = {
                            'icon_name': icon_data[0],
                            'foreground_color': icon_data[1].hex() if isinstance(icon_data[1], bytes) else icon_data[1],
                            'background_color': icon_data[2].hex() if isinstance(icon_data[2], bytes) else icon_data[2],
                        }
                        log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                                 f"Parsed icon appearance: {icon_appearance['icon_name']}")
                except Exception as e:
                    log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"Failed to parse icon appearance: {e}")

            # ✅ PHASE 2.2: Invoke Kotlin callback with FULL message data (truly event-driven)
            # Send complete message so Kotlin doesn't need to poll
            if self.kotlin_message_received_callback:
                try:
                    # Build full message event with all data
                    content = lxmf_message.content.decode('utf-8') if isinstance(lxmf_message.content, bytes) else str(lxmf_message.content)
                    message_event = {
                        'message_hash': lxmf_message.hash.hex() if lxmf_message.hash else "unknown",
                        'content': content,
                        'source_hash': lxmf_message.source_hash.hex(),
                        'destination_hash': lxmf_message.destination_hash.hex(),
                        'timestamp': int(lxmf_message.timestamp * 1000) if lxmf_message.timestamp else int(time.time() * 1000),
                        'icon_appearance': icon_appearance,
                        'full_message': True,  # Flag indicating this has full data, no polling needed
                    }
                    # Add hop count and receiving interface if captured
                    # Use getattr with explicit default to avoid MagicMock issues in tests
                    columba_hops = getattr(lxmf_message, '_columba_hops', None)
                    if isinstance(columba_hops, int):
                        message_event['hops'] = columba_hops
                    columba_interface = getattr(lxmf_message, '_columba_interface', None)
                    if isinstance(columba_interface, str):
                        message_event['receiving_interface'] = columba_interface

                    # Get sender's public key from RNS identity cache
                    try:
                        source_identity = RNS.Identity.recall(lxmf_message.source_hash)
                        if source_identity is not None:
                            public_key = source_identity.get_public_key()
                            # Only add if it's actual bytes (not a Mock object)
                            if isinstance(public_key, bytes):
                                message_event['public_key'] = public_key.hex()
                    except Exception as e:
                        log_debug("ReticulumWrapper", "_on_lxmf_delivery", f"Could not get public key: {e}")

                    # Extract LXMF fields (attachments, reactions, etc.)
                    if hasattr(lxmf_message, 'fields') and lxmf_message.fields:
                        fields_serialized = {}
                        for key, value in lxmf_message.fields.items():
                            if key == 5 and isinstance(value, list):
                                # Field 5: file attachments
                                serialized_attachments = []
                                for attachment in value:
                                    if isinstance(attachment, (list, tuple)) and len(attachment) >= 2:
                                        filename, file_data = attachment[0], attachment[1]
                                        if isinstance(file_data, bytes):
                                            serialized_attachments.append({
                                                'filename': str(filename),
                                                'data': file_data.hex(),
                                                'size': len(file_data)
                                            })
                                if serialized_attachments:
                                    fields_serialized['5'] = serialized_attachments
                            elif key == 16 and isinstance(value, dict):
                                # Field 16: app extensions (reactions, replies)
                                fields_serialized['16'] = value
                            elif key == 4 and isinstance(value, list) and len(value) >= 3:
                                # Field 4: icon appearance (already parsed above)
                                pass
                            elif key in (6, 7) and isinstance(value, (list, tuple)) and len(value) >= 2:
                                # Field 6/7: image/audio
                                if isinstance(value[1], bytes):
                                    fields_serialized[str(key)] = [value[0], value[1].hex()]
                            else:
                                fields_serialized[str(key)] = str(value)
                        if fields_serialized:
                            message_event['fields'] = json.dumps(fields_serialized)

                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                             f"Invoking Kotlin callback with full message data ({len(content)} chars)...")
                    self.kotlin_message_received_callback(json.dumps(message_event))
                    log_info("ReticulumWrapper", "_on_lxmf_delivery",
                            "✅ Kotlin callback invoked with full message (event-driven)")
                except Exception as e:
                    log_error("ReticulumWrapper", "_on_lxmf_delivery",
                             f"⚠️ Error invoking Kotlin callback: {e}")
                    import traceback
                    traceback.print_exc()
                    # Continue anyway - message is in queue for polling fallback
            else:
                log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                         "No Kotlin callback registered - relying on polling")

        except Exception as e:
            log_error("ReticulumWrapper", "_on_lxmf_delivery", f"Error in delivery callback: {e}")
            import traceback
            traceback.print_exc()

    def _on_message(self, message):
        """Internal callback handler for LXMF messages"""
        msg_dict = {
            'content': message.content,
            'source': message.source_hash,
            'destination': message.destination_hash,
            'timestamp': message.timestamp
        }
        for callback in self.message_callbacks:
            try:
                callback(msg_dict)
            except Exception as e:
                log_error("ReticulumWrapper", "_on_message", f"Error in message callback: {e}")

    def register_announce_callback(self, callback: Callable):
        """
        Register a callback for network announces.
        The callback will be called with a dict containing:
        - destination_hash: bytes
        - identity_hash: bytes
        - public_key: bytes
        - app_data: bytes
        - hops: int
        - timestamp: int (milliseconds)
        """
        log_info("ReticulumWrapper", "register_announce_callback", f"Registering announce callback: {callback}")
        self.announce_callbacks.append(callback)
        return {"success": True}

    def get_pending_announces(self) -> List[Dict]:
        """
        Retrieve all pending announces and clear the queue.
        This is called by Kotlin to poll for new announces.

        Returns:
            List of announce event dicts
        """
        with self.announce_lock:
            announces = self.pending_announces.copy()
            self.pending_announces.clear()
            return announces

    def poll_received_announces(self) -> List[Dict]:
        """
        Poll for received announces from Transport's announce_table.

        Transport automatically processes announces and stores them. We just
        poll for new entries since last check.

        Returns:
            List of new announce event dicts since last poll
        """
        if not RETICULUM_AVAILABLE or not self.initialized:
            log_debug("ReticulumWrapper", "poll_received_announces", "Skipping: not initialized")
            return []

        try:
            new_announces = []

            # Poll announce_table for new announces (Transport manages this automatically)
            if not hasattr(RNS.Transport, 'announce_table'):
                log_warning("ReticulumWrapper", "poll_received_announces", "RNS.Transport has no announce_table attribute!")
                return []

            table_size = len(RNS.Transport.announce_table)
            log_debug("ReticulumWrapper", "poll_received_announces", f"Polling announce_table with {table_size} entries, {len(self.seen_announce_hashes)} already seen")

            current_time = time.time()

            # Check each announce in the table
            for dest_hash in list(RNS.Transport.announce_table.keys()):
                hash_hex = dest_hash.hex()

                # Skip if we've already seen this announce
                if hash_hex in self.seen_announce_hashes:
                    continue

                # Mark as seen
                self.seen_announce_hashes.add(hash_hex)

                # Try to recall identity and app_data (Transport stored these)
                announced_identity = None
                try:
                    announced_identity = RNS.Identity.recall(dest_hash)
                except:
                    pass

                app_data = b''
                try:
                    app_data = RNS.Identity.recall_app_data(dest_hash)
                except:
                    pass

                # Get hops from Transport
                hops = RNS.Transport.hops_to(dest_hash)
                if hops is None or hops == RNS.Transport.PATHFINDER_M:
                    hops = 0

                # Get receiving interface from announce_table packet
                receiving_interface = None
                try:
                    announce_entry = RNS.Transport.announce_table.get(dest_hash)
                    if announce_entry is not None and len(announce_entry) > 5:
                        packet = announce_entry[5]  # IDX_AT_PACKET
                        if packet and hasattr(packet, 'receiving_interface'):
                            interface_obj = packet.receiving_interface
                            if interface_obj:
                                # Use class name to identify interface type (reliable, not user-configured)
                                class_name = type(interface_obj).__name__
                                if "AutoInterface" in class_name:
                                    receiving_interface = "AutoInterface"
                                else:
                                    receiving_interface = class_name
                except Exception as e:
                    log_debug("ReticulumWrapper", "poll_received_announces",
                             f"Could not extract interface: {e}")

                # Create simple announce event
                # NOTE: dest_hash is the DESTINATION hash (e.g., "lxmf.delivery", "nomadnetwork.node")
                # identity_hash is the raw IDENTITY hash (16 bytes from the public key)
                # They are NOT the same! identity_hash = identity.hash, dest_hash = hash(identity + app + aspect)
                identity_hash = announced_identity.hash if announced_identity else dest_hash

                announce_event = {
                    'destination_hash': dest_hash,
                    'identity_hash': identity_hash,
                    'public_key': announced_identity.get_public_key() if announced_identity else b'',
                    'app_data': app_data,
                    'hops': hops,
                    'timestamp': int(current_time * 1000),
                    'interface': receiving_interface,
                }

                new_announces.append(announce_event)
                log_info("ReticulumWrapper", "poll_received_announces",
                        f"✅ NEW ANNOUNCE: {hash_hex[:16]}... (hops={hops}, app_data={len(app_data)}B)")

            self.last_announce_poll_time = current_time
            return new_announces

        except Exception as e:
            log_error("ReticulumWrapper", "poll_received_announces", f"Error polling announces: {e}")
            import traceback
            traceback.print_exc()
            return []

    def send_lxmf_message(self, dest_hash: bytes, content: str, source_identity_private_key: bytes, image_data: bytes = None, image_format: str = None, file_attachments: list = None, icon_name: str = None, icon_fg_color: str = None, icon_bg_color: str = None) -> Dict:
        """
        Send an LXMF message to a destination.

        Args:
            dest_hash: Identity hash bytes (16 bytes) - will be converted to LXMF destination hash
            content: Message content string
            source_identity_private_key: Private key of sender identity
            image_data: Optional image data bytes
            image_format: Optional image format (e.g., 'jpg', 'png', 'webp')
            file_attachments: Optional list of [filename, bytes] tuples for file attachments (Field 5)
            icon_name: Optional icon name for FIELD_ICON_APPEARANCE (Sideband/MeshChat interop)
            icon_fg_color: Optional foreground color hex string (3 bytes RGB)
            icon_bg_color: Optional background color hex string (3 bytes RGB)

        Returns:
            Dict with 'success', 'message_hash', 'timestamp' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Convert jarray to bytes if needed
            if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if hasattr(source_identity_private_key, '__iter__') and not isinstance(source_identity_private_key, (bytes, bytearray)):
                source_identity_private_key = bytes(source_identity_private_key)

            log_separator("ReticulumWrapper", "send_lxmf_message", "=", 80)
            log_debug("ReticulumWrapper", "send_lxmf_message", f"========== LXMF MESSAGE SEND (V4 - Hash Conversion Fix) ==========")
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Received identity hash: {dest_hash.hex()}")
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Hash length: {len(dest_hash)} bytes")
            log_separator("ReticulumWrapper", "send_lxmf_message", "=", 80)

            # Reconstruct source identity from private key
            source_identity = RNS.Identity()
            try:
                source_identity.load_private_key(source_identity_private_key)
                log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Loaded source identity, hash={source_identity.hash.hex()[:16]}")
            except Exception as e:
                log_error("ReticulumWrapper", "send_lxmf_message", f"❌ ERROR loading private key: {e}")
                raise

            # Get our local LXMF destination hash (sender)
            if not self.local_lxmf_destination:
                raise ValueError("Local LXMF destination not created")

            source_dest_hash = self.local_lxmf_destination.hash
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Our LXMF destination hash: {source_dest_hash.hex()}")

            # NOTE: The UI can pass either an identity hash OR a destination hash
            # We need to try both recall methods to handle both cases
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Attempting to recall identity from hash {dest_hash.hex()[:16]}...")

            # === HASH TYPE DIAGNOSTICS ===
            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)
            log_info("ReticulumWrapper", "send_lxmf_message", f"🔍 HASH TYPE ANALYSIS:")
            log_info("ReticulumWrapper", "send_lxmf_message", f"  Input hash length: {len(dest_hash)} bytes")
            log_info("ReticulumWrapper", "send_lxmf_message", f"  Input hash: {dest_hash.hex()}")
            if len(dest_hash) == 16:
                log_info("ReticulumWrapper", "send_lxmf_message", "  Type: Identity hash (16 bytes)")
            elif len(dest_hash) == 32:
                log_info("ReticulumWrapper", "send_lxmf_message", "  Type: LXMF Destination hash (32 bytes)")
                # Try to extract identity hash from first 16 bytes
                potential_identity_hash = dest_hash[:16]
                log_info("ReticulumWrapper", "send_lxmf_message", f"  Potential identity hash (first 16 bytes): {potential_identity_hash.hex()}")
            else:
                log_warning("ReticulumWrapper", "send_lxmf_message", f"  Type: UNKNOWN ({len(dest_hash)} bytes)")
            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)

            # First, try to recall the identity from the hash
            recipient_identity = None
            dest_hash_hex = dest_hash.hex()

            try:
                # Try as destination hash first (this is what LXMF uses)
                recipient_identity = RNS.Identity.recall(dest_hash)
                if recipient_identity:
                    log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Recalled identity from destination hash via RNS.Identity.recall()")
                else:
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"Not found as destination hash, trying as identity hash...")
                    # Try with from_identity_hash=True
                    recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                    if recipient_identity:
                        log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Recalled identity from identity hash via RNS.Identity.recall()")
            except Exception as e:
                log_error("ReticulumWrapper", "send_lxmf_message", f"Error recalling identity from Reticulum: {e}")

            # If Reticulum recall failed, try our local cache
            if not recipient_identity and dest_hash_hex in self.identities:
                recipient_identity = self.identities[dest_hash_hex]
                log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Retrieved identity from local cache")

            if not recipient_identity:
                # Request path from network (triggers announces from peers who know destination)
                log_info("ReticulumWrapper", "send_lxmf_message",
                         f"Identity not found, requesting path to {dest_hash.hex()[:16]}...")
                try:
                    RNS.Transport.request_path(dest_hash)
                except Exception as e:
                    log_warning("ReticulumWrapper", "send_lxmf_message", f"Error requesting path: {e}")

                # Wait up to 5 seconds for path response
                for attempt in range(10):
                    time.sleep(0.5)
                    recipient_identity = RNS.Identity.recall(dest_hash)
                    if not recipient_identity:
                        recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                    if recipient_identity:
                        log_info("ReticulumWrapper", "send_lxmf_message",
                                 f"✅ Identity resolved after path request (attempt {attempt + 1})")
                        break

                if not recipient_identity:
                    error_msg = f"Cannot send message: Recipient identity {dest_hash.hex()[:16]} not known. Path requested but no response received."
                    log_error("ReticulumWrapper", "send_lxmf_message", f"❌ {error_msg}")
                    return {"success": False, "error": error_msg}

            # Create outgoing LXMF destination object from the recalled identity
            # The router.handle_outbound() REQUIRES a destination object, not just a hash!
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Creating outgoing LXMF destination object...")
            recipient_lxmf_destination = RNS.Destination(
                recipient_identity,
                RNS.Destination.OUT,    # OUT for outgoing messages
                RNS.Destination.SINGLE,
                "lxmf",                 # App name
                "delivery"              # Aspect
            )
            log_info("ReticulumWrapper", "send_lxmf_message", f"Created destination object with hash: {recipient_lxmf_destination.hash.hex()}")
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Original hash received:  {dest_hash.hex()}")
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Destination object hash: {recipient_lxmf_destination.hash.hex()}")
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Are they equal? {dest_hash == recipient_lxmf_destination.hash}")

            # Prepare fields dictionary for attachments
            fields = None

            # LXMF field 6 = IMAGE, format: [format_string, bytes_data]
            if image_data and image_format:
                # Convert jarray to bytes if needed
                if hasattr(image_data, '__iter__') and not isinstance(image_data, (bytes, bytearray)):
                    image_data = bytes(image_data)

                fields = {
                    6: [image_format, image_data]
                }
                log_info("ReticulumWrapper", "send_lxmf_message", f"📎 Attaching image: {len(image_data)} bytes, format={image_format}")

            # LXMF field 5 = FILE_ATTACHMENTS, format: list of [filename, bytes_data]
            if file_attachments:
                if fields is None:
                    fields = {}

                # Convert each attachment to proper format
                field_5_data = []
                for attachment in file_attachments:
                    # Handle different input formats
                    if isinstance(attachment, (list, tuple)) and len(attachment) >= 2:
                        filename = attachment[0]
                        data = attachment[1]
                    elif isinstance(attachment, dict):
                        filename = attachment.get('filename', 'unknown')
                        data = attachment.get('data', b'')
                    else:
                        log_warning("ReticulumWrapper", "send_lxmf_message", f"Skipping invalid attachment format: {type(attachment)}")
                        continue

                    # Convert jarray to bytes if needed
                    if hasattr(data, '__iter__') and not isinstance(data, (bytes, bytearray)):
                        data = bytes(data)

                    field_5_data.append([filename, data])
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"📎 File attachment: {filename} ({len(data)} bytes)")

                if field_5_data:
                    fields[5] = field_5_data
                    log_info("ReticulumWrapper", "send_lxmf_message", f"📎 Attaching {len(field_5_data)} file(s)")

            # Add icon appearance to outgoing messages if provided (Sideband/MeshChat interop)
            # Format: [icon_name, fg_bytes(3), bg_bytes(3)] - same as Sideband
            if icon_name and icon_fg_color and icon_bg_color:
                if fields is None:
                    fields = {}
                fg_bytes = bytes.fromhex(icon_fg_color)
                bg_bytes = bytes.fromhex(icon_bg_color)
                fields[FIELD_ICON_APPEARANCE] = [
                    icon_name,
                    fg_bytes,
                    bg_bytes
                ]
                log_info("ReticulumWrapper", "send_lxmf_message",
                        f"📎 Adding icon appearance: {icon_name}, fg={icon_fg_color} ({fg_bytes.hex()}), bg={icon_bg_color} ({bg_bytes.hex()})")

            # Create LXMF message using destination OBJECTS
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Creating LXMessage with destination objects...")
            lxmf_message = LXMF.LXMessage(
                destination=recipient_lxmf_destination,  # ✅ Destination OBJECT!
                source=self.local_lxmf_destination,      # ✅ Our local LXMF destination OBJECT!
                content=content.encode('utf-8'),
                title="",  # Optional
                fields=fields
            )

            log_info("ReticulumWrapper", "send_lxmf_message", f"✅ LXMessage created successfully!")

            # Register delivery status callbacks (event-driven architecture - no polling!)
            try:
                lxmf_message.register_delivery_callback(self._on_message_delivered)
                lxmf_message.register_failed_callback(self._on_message_failed)
                log_debug("ReticulumWrapper", "send_lxmf_message", "Delivery status callbacks registered")
            except Exception as e:
                log_warning("ReticulumWrapper", "send_lxmf_message",
                           f"Could not register delivery callbacks: {e}")

            # Announce our LXMF destination before sending to ensure recipient has our identity
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Announcing our LXMF destination before sending...")
            try:
                self.local_lxmf_destination.announce(app_data=self.display_name.encode('utf-8'))
                log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Announced our LXMF destination with display name: {self.display_name}")
            except Exception as e:
                log_warning("ReticulumWrapper", "send_lxmf_message", f"Warning: Could not announce before sending: {e}")

            # === PRE-SEND DIAGNOSTICS ===
            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)
            log_debug("ReticulumWrapper", "send_lxmf_message", "=== PRE-SEND ROUTE ANALYSIS ===")

            dest_lxmf_hash = recipient_lxmf_destination.hash
            dest_identity_hash = recipient_identity.hash
            has_path = False

            try:
                import RNS.Transport as Transport
                path_table = Transport.path_table if hasattr(Transport, 'path_table') else {}

                log_debug("ReticulumWrapper", "send_lxmf_message", f"Target identity hash: {dest_identity_hash.hex()[:16]}...")
                log_debug("ReticulumWrapper", "send_lxmf_message", f"Target LXMF dest hash: {dest_lxmf_hash.hex()[:16]}...")
                log_debug("ReticulumWrapper", "send_lxmf_message", f"Path table has {len(path_table)} entries")

                path_info = path_table.get(dest_lxmf_hash)
                if path_info is not None:
                    has_path = True
                    log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Path exists to {dest_lxmf_hash.hex()[:16]} - will send via path-based routing")
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"   Path info: {path_info}")
                else:
                    log_info("ReticulumWrapper", "send_lxmf_message", f"ℹ️  No path to {dest_lxmf_hash.hex()[:16]} - LXMF will establish Link")
                    log_info("ReticulumWrapper", "send_lxmf_message", f"   📎 Link-based delivery provides reliable transport with automatic retries")

                # Log all known paths for debugging
                if len(path_table) > 0:
                    all_hashes = [h.hex()[:16] for h in list(path_table.keys())]
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"Known paths in table: {all_hashes[:10]}")
                else:
                    log_debug("ReticulumWrapper", "send_lxmf_message", "Path table is empty (paths may have expired)")

            except Exception as e:
                log_warning("ReticulumWrapper", "send_lxmf_message", f"Could not check path_table: {e}")

            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)

            # Send via router (LXMF handles routing intelligently)
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Handing message to LXMF router...")
            self.router.handle_outbound(lxmf_message)

            # Hash is populated after handle_outbound
            msg_hash = lxmf_message.hash if lxmf_message.hash else b'unknown'
            log_debug("ReticulumWrapper", "send_lxmf_message", f"Message hash: {msg_hash.hex()[:16] if msg_hash != b'unknown' else 'unknown'}")

            # Check if message transitioned to SENT state (0x04)
            # LXMF.LXMessage.SENT means message was successfully transmitted to the network
            try:
                if hasattr(lxmf_message, 'state') and lxmf_message.state == LXMF.LXMessage.SENT:
                    log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Message state: SENT (0x04) - transmitted to network")
                    self._on_message_sent(lxmf_message)
                else:
                    current_state = lxmf_message.state if hasattr(lxmf_message, 'state') else 'unknown'
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"Message state after send: {current_state}")
            except Exception as e:
                log_warning("ReticulumWrapper", "send_lxmf_message", f"Could not check message state: {e}")

            # === POST-SEND DIAGNOSTICS ===
            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)
            log_debug("ReticulumWrapper", "send_lxmf_message", "=== POST-SEND DELIVERY STATUS ===")

            # Check if Link was established
            try:
                link_established = False
                active_link = None

                # Check for active links to this destination
                if hasattr(RNS.Transport, 'active_links'):
                    for link in RNS.Transport.active_links:
                        if hasattr(link, 'destination') and link.destination:
                            if link.destination.hash == dest_lxmf_hash or link.destination.hash == dest_identity_hash:
                                link_established = True
                                active_link = link
                                break

                if link_established and active_link:
                    log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Active Link established to destination")
                    log_info("ReticulumWrapper", "send_lxmf_message", f"   📎 Link ID: {active_link.link_id.hex()[:16]}... (Link ensures reliable delivery)")
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"   Link state: {active_link.status if hasattr(active_link, 'status') else 'ACTIVE'}")
                elif has_path:
                    log_info("ReticulumWrapper", "send_lxmf_message", f"✅ Sent via path-based routing")
                else:
                    log_debug("ReticulumWrapper", "send_lxmf_message", f"   Link may be establishing... (check logs for Link registration)")

            except Exception as e:
                log_debug("ReticulumWrapper", "send_lxmf_message", f"Could not check Link status: {e}")

            # Check router's outbound queue
            try:
                if hasattr(self.router, 'pending_outbound'):
                    outbound_count = len(self.router.pending_outbound) if self.router.pending_outbound else 0
                    if outbound_count > 0:
                        log_info("ReticulumWrapper", "send_lxmf_message", f"📤 Router queue: {outbound_count} messages pending delivery")
                        log_info("ReticulumWrapper", "send_lxmf_message", f"   ⏳ Messages will be delivered when Link establishes or path becomes available")
                    else:
                        log_debug("ReticulumWrapper", "send_lxmf_message", f"Router queue: empty (message sent immediately)")
                else:
                    log_debug("ReticulumWrapper", "send_lxmf_message", "Router does not expose pending_outbound queue")
            except Exception as e:
                log_debug("ReticulumWrapper", "send_lxmf_message", f"Could not check pending_outbound: {e}")

            log_separator("ReticulumWrapper", "send_lxmf_message", "-", 60)

            log_info("ReticulumWrapper", "send_lxmf_message", f"✅ LXMF message sent successfully!")
            log_separator("ReticulumWrapper", "send_lxmf_message", "=", 80)

            return {
                "success": True,
                "message_hash": lxmf_message.hash if lxmf_message.hash else b'',
                "timestamp": int(time.time() * 1000),
                "destination_hash": recipient_lxmf_destination.hash  # Return actual LXMF destination hash used
            }

        except Exception as e:
            log_separator("ReticulumWrapper", "send_lxmf_message", "=", 80)
            log_error("ReticulumWrapper", "send_lxmf_message", f"❌ ERROR sending LXMF message: {e}")
            import traceback
            traceback.print_exc()
            log_separator("ReticulumWrapper", "send_lxmf_message", "=", 80)
            return {"success": False, "error": str(e)}

    # ==================== LOCATION TELEMETRY ====================

    def send_location_telemetry(self, dest_hash: bytes, location_json: str, source_identity_private_key: bytes) -> Dict:
        """
        Send location telemetry to a destination via LXMF FIELD_TELEMETRY (0x02).

        Uses Sideband's msgpack-packed Telemeter format for interoperability.
        Cease signals are sent via FIELD_COLUMBA_META (0x70) for Columba clients.

        Args:
            dest_hash: Identity hash bytes (16 bytes) of the recipient
            location_json: JSON string with location data (lat, lng, acc, ts, expires, cease)
            source_identity_private_key: Private key of sender identity

        Returns:
            Dict with 'success', 'message_hash', 'timestamp' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Convert jarray to bytes if needed
            if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if hasattr(source_identity_private_key, '__iter__') and not isinstance(source_identity_private_key, (bytes, bytearray)):
                source_identity_private_key = bytes(source_identity_private_key)

            log_info("ReticulumWrapper", "send_location_telemetry",
                     f"📍 Sending location telemetry to {dest_hash.hex()[:16]}...")

            # Reconstruct source identity from private key
            source_identity = RNS.Identity()
            try:
                source_identity.load_private_key(source_identity_private_key)
            except Exception as e:
                log_error("ReticulumWrapper", "send_location_telemetry", f"❌ ERROR loading private key: {e}")
                raise

            # Get our local LXMF destination
            if not self.local_lxmf_destination:
                raise ValueError("Local LXMF destination not created")

            # Recall recipient identity
            recipient_identity = RNS.Identity.recall(dest_hash)
            if not recipient_identity:
                recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)

            # Try local cache
            dest_hash_hex = dest_hash.hex()
            if not recipient_identity and dest_hash_hex in self.identities:
                recipient_identity = self.identities[dest_hash_hex]

            if not recipient_identity:
                error_msg = f"Recipient identity {dest_hash.hex()[:16]} not known"
                log_error("ReticulumWrapper", "send_location_telemetry", f"❌ {error_msg}")
                return {"success": False, "error": error_msg}

            # Create outgoing LXMF destination
            recipient_lxmf_destination = RNS.Destination(
                recipient_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery"
            )

            # Parse and validate location JSON
            try:
                location_data = json.loads(location_json)
                log_debug("ReticulumWrapper", "send_location_telemetry",
                          f"Location data: lat={location_data.get('lat')}, lng={location_data.get('lng')}, cease={location_data.get('cease', False)}")
            except json.JSONDecodeError as e:
                return {"success": False, "error": f"Invalid location JSON: {e}"}

            # Build LXMF fields based on message type
            fields = {}

            is_cease = location_data.get('cease', False)

            if is_cease:
                # Cease message: only send Columba-specific metadata (Sideband doesn't understand cease)
                # Sideband will just let the location become stale naturally
                fields[FIELD_COLUMBA_META] = json.dumps({"cease": True})
                log_debug("ReticulumWrapper", "send_location_telemetry", "Sending cease signal via FIELD_COLUMBA_META")
            else:
                # Normal location update: use Sideband-compatible Telemeter format
                packed_telemetry = pack_location_telemetry(
                    lat=location_data['lat'],
                    lon=location_data['lng'],
                    accuracy=location_data.get('acc', 0.0),
                    timestamp_ms=location_data.get('ts', int(time.time() * 1000)),
                    altitude=location_data.get('altitude', 0.0),
                    speed=location_data.get('speed', 0.0),
                    bearing=location_data.get('bearing', 0.0),
                )
                fields[FIELD_TELEMETRY] = packed_telemetry

                # Also include Columba-specific metadata for enhanced Columba-to-Columba features
                columba_meta = {}
                if location_data.get('expires'):
                    columba_meta['expires'] = location_data['expires']
                if location_data.get('approxRadius', 0) > 0:
                    columba_meta['approxRadius'] = location_data['approxRadius']
                if columba_meta:
                    fields[FIELD_COLUMBA_META] = json.dumps(columba_meta)

                log_debug("ReticulumWrapper", "send_location_telemetry",
                          f"Sending Sideband-compatible telemetry in FIELD_TELEMETRY (0x02)")

            # Create LXMF message with location telemetry
            lxmf_message = LXMF.LXMessage(
                destination=recipient_lxmf_destination,
                source=self.local_lxmf_destination,
                content="".encode('utf-8'),  # Empty content - location is in FIELD_TELEMETRY
                title="",
                fields=fields
            )

            # Register delivery callbacks
            try:
                lxmf_message.register_delivery_callback(self._on_message_delivered)
                lxmf_message.register_failed_callback(self._on_message_failed)
            except Exception as e:
                log_warning("ReticulumWrapper", "send_location_telemetry",
                            f"Could not register delivery callbacks: {e}")

            # Send via router
            self.router.handle_outbound(lxmf_message)

            log_info("ReticulumWrapper", "send_location_telemetry",
                     f"✅ Location telemetry sent to {dest_hash.hex()[:16]}")

            return {
                "success": True,
                "message_hash": lxmf_message.hash.hex() if lxmf_message.hash else "",
                "timestamp": int(time.time() * 1000),
                "destination_hash": recipient_lxmf_destination.hash.hex() if recipient_lxmf_destination.hash else ""
            }

        except Exception as e:
            log_error("ReticulumWrapper", "send_location_telemetry", f"❌ ERROR sending location telemetry: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    # ==================== MESSAGE SIZE LIMITS ====================

    def set_incoming_message_size_limit(self, limit_kb: int) -> Dict:
        """
        Set the incoming message size limit.

        This controls the maximum size of LXMF messages that can be received,
        both for direct delivery and propagation node transfers. Both limits
        are kept in sync for simplicity.

        Args:
            limit_kb: Size limit in KB (e.g., 1024 for 1MB, 131072 for 128MB "unlimited")

        Returns:
            Dict with 'success' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Set both limits to keep direct and propagation transfers in sync
            self.router.delivery_per_transfer_limit = limit_kb
            self.router.propagation_per_transfer_limit = limit_kb
            log_info("ReticulumWrapper", "set_incoming_message_size_limit",
                     f"Set incoming message limit to {limit_kb}KB (delivery and propagation)")

            return {"success": True}
        except Exception as e:
            log_error("ReticulumWrapper", "set_incoming_message_size_limit", f"Error: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    # ==================== PROPAGATION NODE SUPPORT ====================

    def set_outbound_propagation_node(self, dest_hash: bytes) -> Dict:
        """
        Set the propagation node to use for PROPAGATED delivery.

        Args:
            dest_hash: 16-byte destination hash of the propagation node, or None to clear

        Returns:
            Dict with 'success' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Convert jarray to bytes if needed
            if dest_hash is not None:
                if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                    dest_hash = bytes(dest_hash)

            if dest_hash is None:
                self.router.set_outbound_propagation_node(None)
                self.active_propagation_node = None
                log_info("ReticulumWrapper", "set_outbound_propagation_node", "Cleared propagation node")
            else:
                self.router.set_outbound_propagation_node(dest_hash)
                self.active_propagation_node = dest_hash
                log_info("ReticulumWrapper", "set_outbound_propagation_node",
                        f"Set propagation node to {dest_hash.hex()[:16]}...")

            return {"success": True}
        except Exception as e:
            log_error("ReticulumWrapper", "set_outbound_propagation_node", f"Error: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def get_outbound_propagation_node(self) -> Dict:
        """
        Get the currently configured propagation node.

        Returns:
            Dict with 'success', 'propagation_node' (hex string or None) or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            node = self.router.get_outbound_propagation_node()
            return {
                "success": True,
                "propagation_node": node.hex() if node else None
            }
        except Exception as e:
            log_error("ReticulumWrapper", "get_outbound_propagation_node", f"Error: {e}")
            return {"success": False, "error": str(e)}

    def request_messages_from_propagation_node(self, identity_private_key: bytes = None, max_messages: int = 256) -> Dict:
        """
        Request/sync messages from the configured propagation node.

        This is the key method for RECEIVING messages that were sent via propagation.
        When messages are sent to a propagation node, the recipient must explicitly
        request them. Call this method periodically (e.g., every 30 seconds) to
        retrieve waiting messages.

        Args:
            identity_private_key: Optional private key bytes to use for requesting messages.
                                  If None, uses the default identity.
            max_messages: Maximum number of messages to retrieve (default 256)

        Returns:
            Dict with 'success' or 'error', and 'state' indicating the transfer state
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Check if propagation node is configured
            if not self.active_propagation_node:
                return {
                    "success": False,
                    "error": "No propagation node configured",
                    "errorCode": "NO_PROPAGATION_NODE"
                }

            # Get or create identity for requesting messages
            if identity_private_key is not None:
                # Convert jarray to bytes if needed
                if hasattr(identity_private_key, '__iter__') and not isinstance(identity_private_key, (bytes, bytearray)):
                    identity_private_key = bytes(identity_private_key)

                # Load identity from private key
                identity = RNS.Identity.from_bytes(identity_private_key)
                log_info("ReticulumWrapper", "request_messages_from_propagation_node",
                        f"Using provided identity: {identity.hash.hex()[:16]}...")
            else:
                # Use default identity
                identity = self.default_identity
                log_info("ReticulumWrapper", "request_messages_from_propagation_node",
                        f"Using default identity: {identity.hash.hex()[:16]}...")

            log_info("ReticulumWrapper", "request_messages_from_propagation_node",
                    f"📡 Requesting up to {max_messages} messages from propagation node {self.active_propagation_node.hex()[:16]}...")

            # Reset last propagation state and progress to force callback on any change.
            # This is critical: if heartbeat loop is in 1-second idle mode, we might miss
            # fast state transitions. By resetting to None/0, we ensure the next state
            # (including COMPLETE) will be detected as a change and trigger the callback.
            self._last_propagation_state = None
            self._last_propagation_progress = 0.0

            # Request messages from the propagation node
            self.router.request_messages_from_propagation_node(identity, max_messages=max_messages)

            return {
                "success": True,
                "state": self.router.propagation_transfer_state
            }
        except Exception as e:
            log_error("ReticulumWrapper", "request_messages_from_propagation_node", f"Error: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def get_propagation_state(self) -> Dict:
        """
        Get the current propagation sync state and progress.

        State values:
            0 (PR_IDLE): Inactive
            1 (PR_PATH_REQUESTED): Path discovery in progress
            2 (PR_LINK_ESTABLISHING): Connection pending
            3 (PR_LINK_ESTABLISHED): Connected and ready
            4 (PR_REQUEST_SENT): Message list requested
            5 (PR_RECEIVING): Messages downloading
            7 (PR_COMPLETE): Transfer finished

        Returns:
            Dict with 'success', 'state', 'progress', 'messages_received' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            state = self.router.propagation_transfer_state
            progress = self.router.propagation_transfer_progress

            # propagation_transfer_last_result contains the number of messages received
            # in the last completed transfer
            last_result = getattr(self.router, 'propagation_transfer_last_result', None) or 0

            # Map state to human-readable string using LXMF constants
            state_names = {
                LXMF.LXMRouter.PR_IDLE: "idle",
                LXMF.LXMRouter.PR_PATH_REQUESTED: "path_requested",
                LXMF.LXMRouter.PR_LINK_ESTABLISHING: "link_establishing",
                LXMF.LXMRouter.PR_LINK_ESTABLISHED: "link_established",
                LXMF.LXMRouter.PR_REQUEST_SENT: "request_sent",
                LXMF.LXMRouter.PR_RECEIVING: "receiving",
                LXMF.LXMRouter.PR_RESPONSE_RECEIVED: "response_received",
                LXMF.LXMRouter.PR_COMPLETE: "complete",
                LXMF.LXMRouter.PR_NO_PATH: "no_path",
                LXMF.LXMRouter.PR_LINK_FAILED: "link_failed",
                LXMF.LXMRouter.PR_TRANSFER_FAILED: "transfer_failed",
                LXMF.LXMRouter.PR_NO_IDENTITY_RCVD: "no_identity_rcvd",
                LXMF.LXMRouter.PR_NO_ACCESS: "no_access",
            }
            state_name = state_names.get(state, f"unknown_{state}")

            return {
                "success": True,
                "state": state,
                "state_name": state_name,
                "progress": progress,
                "messages_received": last_result
            }
        except Exception as e:
            log_error("ReticulumWrapper", "get_propagation_state", f"Error: {e}")
            return {"success": False, "error": str(e)}

    def send_lxmf_message_with_method(self, dest_hash: bytes, content: str, source_identity_private_key: bytes,
                                       delivery_method: str = "direct", try_propagation_on_fail: bool = True,
                                       image_data: bytes = None, image_format: str = None,
                                       image_data_path: str = None,
                                       file_attachments: list = None, file_attachment_paths: list = None,
                                       reply_to_message_id: str = None,
                                       icon_name: str = None, icon_fg_color: str = None, icon_bg_color: str = None) -> Dict:
        """
        Send an LXMF message with explicit delivery method.

        Args:
            dest_hash: Identity hash bytes (16 bytes)
            content: Message content string
            source_identity_private_key: Private key of sender identity
            delivery_method: "opportunistic", "direct", or "propagated"
            try_propagation_on_fail: If True and direct fails, retry via propagation
            image_data: Optional image data bytes (for small images)
            image_format: Optional image format (e.g., 'jpg', 'png', 'webp')
            image_data_path: Optional file path for large images to bypass Binder IPC limits.
                             File is read from disk and deleted after reading.
            file_attachments: Optional list of [filename, bytes] pairs for Field 5 (small files)
            file_attachment_paths: Optional list of [filename, path] pairs for large files
                                   Files are read from disk to bypass Android Binder IPC limits.
                                   Temp files are deleted after reading.
            reply_to_message_id: Optional message ID being replied to (stored in Field 16)
            icon_name: Optional icon name for FIELD_ICON_APPEARANCE (Sideband/MeshChat interop)
            icon_fg_color: Optional foreground color hex string (3 bytes RGB)
            icon_bg_color: Optional background color hex string (3 bytes RGB)

        Returns:
            Dict with 'success', 'message_hash', 'timestamp', 'delivery_method' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized", "delivery_method": None}

            # Convert jarray to bytes if needed
            if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if hasattr(source_identity_private_key, '__iter__') and not isinstance(source_identity_private_key, (bytes, bytearray)):
                source_identity_private_key = bytes(source_identity_private_key)

            log_separator("ReticulumWrapper", "send_lxmf_message_with_method", "=", 80)
            log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                    f"Sending message with method={delivery_method}, try_propagation_on_fail={try_propagation_on_fail}")

            # Map delivery method string to LXMF constant
            method_map = {
                "opportunistic": LXMF.LXMessage.OPPORTUNISTIC,
                "direct": LXMF.LXMessage.DIRECT,
                "propagated": LXMF.LXMessage.PROPAGATED,
            }

            lxmf_method = method_map.get(delivery_method.lower(), LXMF.LXMessage.DIRECT)

            # Check size for OPPORTUNISTIC - max 295 bytes content
            content_bytes = content.encode('utf-8')
            if lxmf_method == LXMF.LXMessage.OPPORTUNISTIC:
                if len(content_bytes) > 295:
                    log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                               f"Content too large for OPPORTUNISTIC ({len(content_bytes)} bytes > 295), falling back to DIRECT")
                    lxmf_method = LXMF.LXMessage.DIRECT
                if image_data or image_data_path or file_attachments:
                    log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                               "OPPORTUNISTIC doesn't support attachments, falling back to DIRECT")
                    lxmf_method = LXMF.LXMessage.DIRECT

            # Check if PROPAGATED requires a propagation node - fall back to DIRECT if not available
            # The message will retry via propagation when direct fails (if try_propagation_on_fail=True)
            if lxmf_method == LXMF.LXMessage.PROPAGATED and not self.active_propagation_node:
                log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                           "No propagation node set, falling back to DIRECT (will retry via propagation later)")
                lxmf_method = LXMF.LXMessage.DIRECT

            # Reconstruct source identity
            source_identity = RNS.Identity()
            source_identity.load_private_key(source_identity_private_key)

            # Recall recipient identity
            recipient_identity = RNS.Identity.recall(dest_hash)
            if not recipient_identity:
                recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
            if not recipient_identity and dest_hash.hex() in self.identities:
                recipient_identity = self.identities[dest_hash.hex()]

            if not recipient_identity:
                # Request path from network (triggers announces from peers who know destination)
                log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                         f"Identity not found, requesting path to {dest_hash.hex()[:16]}...")
                try:
                    RNS.Transport.request_path(dest_hash)
                except Exception as e:
                    log_warning("ReticulumWrapper", "send_lxmf_message_with_method", f"Error requesting path: {e}")

                # Wait up to 5 seconds for path response
                for attempt in range(10):
                    time.sleep(0.5)
                    recipient_identity = RNS.Identity.recall(dest_hash)
                    if not recipient_identity:
                        recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                    if recipient_identity:
                        log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                                 f"✅ Identity resolved after path request (attempt {attempt + 1})")
                        break

                if not recipient_identity:
                    return {"success": False, "error": f"Recipient identity {dest_hash.hex()[:16]} not known. Path requested but no response received.", "delivery_method": None}

            # Create destination
            recipient_lxmf_destination = RNS.Destination(
                recipient_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery"
            )

            # If image_data_path is provided, read from file (for large images bypassing Binder IPC)
            if image_data_path and image_format:
                import os
                try:
                    with open(image_data_path, 'rb') as f:
                        image_data = f.read()
                    log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                             f"📎 Read large image from temp file: {len(image_data)} bytes")
                except Exception as e:
                    log_error("ReticulumWrapper", "send_lxmf_message_with_method",
                              f"Failed to read image from temp file: {e}")
                    return {"success": False, "error": f"Failed to read image file: {e}", "delivery_method": None}
                finally:
                    # Always try to delete temp file (best effort cleanup)
                    try:
                        if os.path.exists(image_data_path):
                            os.remove(image_data_path)
                            log_debug("ReticulumWrapper", "send_lxmf_message_with_method",
                                      f"Deleted temp image file: {image_data_path}")
                    except Exception as del_err:
                        log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                                   f"Failed to delete temp image file: {del_err}")

            # Prepare fields if image or file attachments provided
            fields = None
            if image_data and image_format:
                if hasattr(image_data, '__iter__') and not isinstance(image_data, (bytes, bytearray)):
                    image_data = bytes(image_data)
                fields = {FIELD_IMAGE: [image_format, image_data]}
                log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                        f"📎 Attaching image: {len(image_data)} bytes, format={image_format}, "
                        f"field_key={FIELD_IMAGE}, format_type={type(image_format).__name__}, "
                        f"data_type={type(image_data).__name__}")

            # Add file attachments to Field 5 if provided
            converted_attachments = []

            # Process small file attachments (bytes passed via Binder)
            if file_attachments:
                # Convert Java ArrayList to Python list if needed
                if hasattr(file_attachments, 'toArray'):
                    file_attachments = list(file_attachments.toArray())
                elif hasattr(file_attachments, '__iter__') and not isinstance(file_attachments, (list, tuple)):
                    file_attachments = list(file_attachments)
                # Convert each attachment: [filename, bytes]
                for attachment in file_attachments:
                    # Convert Java List to Python list if needed
                    if hasattr(attachment, 'toArray'):
                        attachment = list(attachment.toArray())
                    elif hasattr(attachment, '__iter__') and not isinstance(attachment, (list, tuple)):
                        attachment = list(attachment)
                    if len(attachment) >= 2:
                        filename = str(attachment[0])
                        data = attachment[1]
                        # Convert jarray to bytes if needed
                        if hasattr(data, '__iter__') and not isinstance(data, (bytes, bytearray)):
                            data = bytes(data)
                        converted_attachments.append([filename, data])

            # Process large file attachments (read from disk paths)
            # These files were written to temp by Kotlin to bypass Binder IPC limits
            if file_attachment_paths:
                # Convert Java ArrayList to Python list if needed
                if hasattr(file_attachment_paths, 'toArray'):
                    file_attachment_paths = list(file_attachment_paths.toArray())
                elif hasattr(file_attachment_paths, '__iter__') and not isinstance(file_attachment_paths, (list, tuple)):
                    file_attachment_paths = list(file_attachment_paths)

                for path_info in file_attachment_paths:
                    # Convert Java List to Python list if needed
                    if hasattr(path_info, 'toArray'):
                        path_info = list(path_info.toArray())
                    elif hasattr(path_info, '__iter__') and not isinstance(path_info, (list, tuple)):
                        path_info = list(path_info)

                    if len(path_info) >= 2:
                        filename = str(path_info[0])
                        file_path = str(path_info[1])
                        try:
                            with open(file_path, 'rb') as f:
                                data = f.read()
                            converted_attachments.append([filename, data])
                            log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                                    f"📎 Read large file from disk: {filename} ({len(data)} bytes)")
                            # Delete the temp file after reading
                            try:
                                import os
                                os.remove(file_path)
                                log_debug("ReticulumWrapper", "send_lxmf_message_with_method",
                                        f"🗑️ Deleted temp file: {file_path}")
                            except Exception as del_err:
                                log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                                           f"Failed to delete temp file {file_path}: {del_err}")
                        except Exception as read_err:
                            log_error("ReticulumWrapper", "send_lxmf_message_with_method",
                                     f"Failed to read file from {file_path}: {read_err}")

            if converted_attachments:
                if fields is None:
                    fields = {}
                fields[5] = converted_attachments
                total_size = sum(len(a[1]) for a in converted_attachments)
                log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                        f"📎 Attaching {len(converted_attachments)} file(s): {total_size} bytes total")

            # Add Field 16 (app extensions) for reply_to and future features
            # Field 16 is a dict that can contain: {"reply_to": "message_id", "reactions": {...}, etc.}
            if reply_to_message_id:
                if fields is None:
                    fields = {}
                # Build app extensions dict
                app_extensions = {"reply_to": reply_to_message_id}
                fields[16] = app_extensions
                log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                        f"📎 Replying to message: {reply_to_message_id[:16]}...")

            # Add icon appearance to outgoing messages if provided (Sideband/MeshChat interop)
            # Format: [icon_name, fg_bytes(3), bg_bytes(3)] - same as Sideband
            if icon_name and icon_fg_color and icon_bg_color:
                if fields is None:
                    fields = {}
                fg_bytes = bytes.fromhex(icon_fg_color)
                bg_bytes = bytes.fromhex(icon_bg_color)
                fields[FIELD_ICON_APPEARANCE] = [
                    icon_name,
                    fg_bytes,
                    bg_bytes
                ]
                log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                        f"📎 Adding icon appearance: {icon_name}, fg={icon_fg_color} ({fg_bytes.hex()}), bg={icon_bg_color} ({bg_bytes.hex()})")

            # Create LXMF message with specified delivery method
            lxmf_message = LXMF.LXMessage(
                destination=recipient_lxmf_destination,
                source=self.local_lxmf_destination,
                content=content_bytes,
                title="",
                fields=fields,
                desired_method=lxmf_method
            )

            # Store retry flag on message for use in _on_message_failed
            if try_propagation_on_fail and self.active_propagation_node and lxmf_method != LXMF.LXMessage.PROPAGATED:
                lxmf_message.try_propagation_on_fail = True
            else:
                lxmf_message.try_propagation_on_fail = False

            log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                    f"LXMessage created with desired_method={lxmf_method}")

            # Register callbacks
            lxmf_message.register_delivery_callback(self._on_message_delivered)
            lxmf_message.register_failed_callback(self._on_message_failed)

            # Send via router
            self.router.handle_outbound(lxmf_message)

            msg_hash = lxmf_message.hash if lxmf_message.hash else b''
            log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                    f"✅ Message {msg_hash.hex()[:16] if msg_hash else 'unknown'}... handed to router")

            # Check if message transitioned to SENT state (0x04) immediately
            # This happens for PROPAGATED messages when the relay accepts them
            try:
                if hasattr(lxmf_message, 'state') and lxmf_message.state == LXMF.LXMessage.SENT:
                    log_info("ReticulumWrapper", "send_lxmf_message_with_method",
                            f"✅ Message state: SENT (0x04) - transmitted to network")
                    self._on_message_sent(lxmf_message)
                else:
                    current_state = lxmf_message.state if hasattr(lxmf_message, 'state') else 'unknown'
                    log_debug("ReticulumWrapper", "send_lxmf_message_with_method",
                             f"Message state after send: {current_state}")
            except Exception as e:
                log_warning("ReticulumWrapper", "send_lxmf_message_with_method",
                           f"Could not check message state: {e}")

            # Track opportunistic messages for timeout fallback
            # If an opportunistic message doesn't get delivered within the timeout period,
            # we'll trigger the failure callback to initiate propagation fallback
            if lxmf_method == LXMF.LXMessage.OPPORTUNISTIC and lxmf_message.try_propagation_on_fail and msg_hash:
                self._opportunistic_messages[msg_hash.hex()] = {
                    'message': lxmf_message,
                    'sent_time': time.time()
                }
                log_debug("ReticulumWrapper", "send_lxmf_message_with_method",
                         f"Tracking opportunistic message {msg_hash.hex()[:16]}... for timeout fallback")
                # Ensure timer is running
                self._start_opportunistic_timer()

            # Track PROPAGATED messages with file attachments for pending notification
            # When propagation succeeds, we'll send a lightweight notification to the recipient
            if lxmf_method == LXMF.LXMessage.PROPAGATED and msg_hash:
                if hasattr(lxmf_message, 'fields') and lxmf_message.fields and 5 in lxmf_message.fields:
                    self._pending_file_notifications[msg_hash.hex()] = lxmf_message
                    log_debug("ReticulumWrapper", "send_lxmf_message_with_method",
                             f"Tracking {msg_hash.hex()[:16]}... for pending file notification after propagation")

            # Map method back to string for return
            method_names = {
                LXMF.LXMessage.OPPORTUNISTIC: "opportunistic",
                LXMF.LXMessage.DIRECT: "direct",
                LXMF.LXMessage.PROPAGATED: "propagated",
            }

            return {
                "success": True,
                "message_hash": msg_hash,
                "timestamp": int(time.time() * 1000),
                "delivery_method": method_names.get(lxmf_method, "unknown"),
                "destination_hash": recipient_lxmf_destination.hash
            }

        except Exception as e:
            log_error("ReticulumWrapper", "send_lxmf_message_with_method", f"❌ ERROR: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e), "delivery_method": None}

    def send_reaction(self, dest_hash: bytes, target_message_id: str, emoji: str,
                      source_identity_private_key: bytes) -> Dict:
        """
        Send an emoji reaction to a message via LXMF.

        Reactions are sent as lightweight LXMF messages with Field 16 containing:
        - reaction_to: The message ID being reacted to
        - emoji: The emoji reaction
        - sender: The sender's identity hash (for aggregation on receiver side)

        Args:
            dest_hash: Identity hash bytes (16 bytes) of the message recipient
            target_message_id: The message ID being reacted to
            emoji: The emoji reaction (e.g., "👍", "❤️", "😂")
            source_identity_private_key: Private key of sender identity

        Returns:
            Dict with 'success', 'message_hash', 'timestamp' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
                return {"success": False, "error": "LXMF not initialized"}

            # Convert jarray to bytes if needed
            if hasattr(dest_hash, '__iter__') and not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if hasattr(source_identity_private_key, '__iter__') and not isinstance(source_identity_private_key, (bytes, bytearray)):
                source_identity_private_key = bytes(source_identity_private_key)

            log_separator("ReticulumWrapper", "send_reaction", "=", 80)
            log_info("ReticulumWrapper", "send_reaction",
                    f"Sending reaction '{emoji}' to message {target_message_id[:16]}...")

            # Reconstruct source identity from private key
            source_identity = RNS.Identity()
            source_identity.load_private_key(source_identity_private_key)
            sender_hash_hex = source_identity.hash.hex()
            log_debug("ReticulumWrapper", "send_reaction",
                     f"Loaded source identity, hash={sender_hash_hex[:16]}")

            # Recall recipient identity
            recipient_identity = RNS.Identity.recall(dest_hash)
            if not recipient_identity:
                recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
            if not recipient_identity and dest_hash.hex() in self.identities:
                recipient_identity = self.identities[dest_hash.hex()]

            if not recipient_identity:
                # Request path from network
                log_info("ReticulumWrapper", "send_reaction",
                         f"Identity not found, requesting path to {dest_hash.hex()[:16]}...")
                try:
                    RNS.Transport.request_path(dest_hash)
                except Exception as e:
                    log_warning("ReticulumWrapper", "send_reaction", f"Error requesting path: {e}")

                # Wait up to 5 seconds for path response
                wait_start = time.time()
                while time.time() - wait_start < 5:
                    recipient_identity = RNS.Identity.recall(dest_hash)
                    if not recipient_identity:
                        recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                    if recipient_identity:
                        break
                    time.sleep(0.1)

                if not recipient_identity:
                    return {"success": False, "error": f"Recipient identity {dest_hash.hex()[:16]} not known"}

            # Create destination
            recipient_lxmf_destination = RNS.Destination(
                recipient_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery"
            )

            # Build Field 16 with reaction data
            # Format: {"reaction_to": "msg_id", "emoji": "👍", "sender": "sender_hash_hex"}
            app_extensions = {
                "reaction_to": target_message_id,
                "emoji": emoji,
                "sender": sender_hash_hex
            }
            fields = {16: app_extensions}

            log_debug("ReticulumWrapper", "send_reaction",
                     f"Field 16: {app_extensions}")

            # Reactions are small, use OPPORTUNISTIC for fast delivery
            # Empty content - Sideband doesn't support reactions anyway
            lxmf_message = LXMF.LXMessage(
                destination=recipient_lxmf_destination,
                source=self.local_lxmf_destination,
                content=b"",  # Empty content - reaction data is in fields
                title="",
                fields=fields,
                desired_method=LXMF.LXMessage.OPPORTUNISTIC
            )

            # Register callbacks
            lxmf_message.register_delivery_callback(self._on_message_delivered)
            lxmf_message.register_failed_callback(self._on_message_failed)

            # Send via router
            self.router.handle_outbound(lxmf_message)

            msg_hash = lxmf_message.hash if lxmf_message.hash else b''
            log_info("ReticulumWrapper", "send_reaction",
                    f"✅ Reaction {msg_hash.hex()[:16] if msg_hash else 'unknown'}... sent")

            return {
                "success": True,
                "message_hash": msg_hash,
                "timestamp": int(time.time() * 1000),
                "target_message_id": target_message_id,
                "emoji": emoji
            }

        except Exception as e:
            log_error("ReticulumWrapper", "send_reaction", f"❌ ERROR: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def _on_message_delivered(self, lxmf_message):
        """
        Callback invoked by LXMF when a sent message acknowledgment is received.

        For DIRECT messages: Called when recipient sends back proof of delivery.
        For PROPAGATED messages: Called when relay accepts the message (NOT end recipient).

        LXMF sets different states before calling this callback:
        - DELIVERED (0x08): Direct delivery confirmed by recipient
        - SENT (0x04): Propagated to relay, awaiting recipient sync

        Args:
            lxmf_message: The LXMF.LXMessage that was acknowledged
        """
        try:
            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"

            # Determine status based on LXMF message state
            # LXMF sets state=DELIVERED for direct, state=SENT for propagated
            if lxmf_message.state == LXMF.LXMessage.DELIVERED:
                status = 'delivered'
                log_info("ReticulumWrapper", "_on_message_delivered",
                        f"✅ Message {msg_hash[:16]}... DELIVERED (confirmed by recipient)")
            else:
                # state == SENT means propagated (relay accepted, recipient unknown)
                status = 'propagated'
                log_info("ReticulumWrapper", "_on_message_delivered",
                        f"📤 Message {msg_hash[:16]}... PROPAGATED (stored on relay)")

                # If this message was tracked for pending file notification, send it now
                # The notification tells the recipient to sync with the relay to get the file
                if msg_hash in self._pending_file_notifications:
                    tracked_message = self._pending_file_notifications.pop(msg_hash)
                    log_info("ReticulumWrapper", "_on_message_delivered",
                            f"📬 Sending pending file notification now that propagation confirmed")
                    self._send_pending_file_notification(tracked_message)

            # Remove from opportunistic tracking (if it was being tracked)
            if msg_hash in self._opportunistic_messages:
                del self._opportunistic_messages[msg_hash]
                log_debug("ReticulumWrapper", "_on_message_delivered",
                         f"Removed {msg_hash[:16]}... from opportunistic tracking ({status})")

            # Create status event for Kotlin
            status_event = {
                'message_hash': msg_hash,
                'status': status,
                'timestamp': int(time.time() * 1000)
            }

            # Invoke Kotlin callback if registered (same pattern as BLE bridge)
            if self.kotlin_delivery_status_callback:
                try:
                    import json
                    self.kotlin_delivery_status_callback(json.dumps(status_event))
                    log_debug("ReticulumWrapper", "_on_message_delivered",
                             "Kotlin callback invoked successfully")
                except Exception as e:
                    log_error("ReticulumWrapper", "_on_message_delivered",
                             f"Error invoking Kotlin callback: {e}")
            else:
                log_warning("ReticulumWrapper", "_on_message_delivered",
                           "No Kotlin callback registered - delivery status not reported")

        except Exception as e:
            log_error("ReticulumWrapper", "_on_message_delivered",
                     f"Error in delivery callback: {e}")
            import traceback
            traceback.print_exc()

    def _on_message_failed(self, lxmf_message):
        """
        Callback invoked by LXMF when a sent message delivery fails.
        This is called when delivery times out or is otherwise unsuccessful.

        Handles three cases:
        1. First failure with try_propagation_on_fail=True: Retry via current propagation node
        2. Propagation retry failed, retries < max: Request alternative relay from Kotlin
        3. Max retries exceeded or no alternatives: Fail message permanently

        Args:
            lxmf_message: The LXMF.LXMessage that failed
        """
        try:
            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"

            # Remove from opportunistic tracking (if it was being tracked)
            if msg_hash in self._opportunistic_messages:
                del self._opportunistic_messages[msg_hash]
                log_debug("ReticulumWrapper", "_on_message_failed",
                         f"Removed {msg_hash[:16]}... from opportunistic tracking (failed)")

            # Initialize tracking attributes if not present
            if not hasattr(lxmf_message, 'propagation_retry_attempted'):
                lxmf_message.propagation_retry_attempted = False
            if not hasattr(lxmf_message, 'tried_relays'):
                lxmf_message.tried_relays = []

            # Case 1: First failure - try propagation if enabled (Sideband pattern)
            if (hasattr(lxmf_message, 'try_propagation_on_fail') and
                lxmf_message.try_propagation_on_fail and
                self.active_propagation_node and
                not lxmf_message.propagation_retry_attempted):

                log_info("ReticulumWrapper", "_on_message_failed",
                        f"📡 Message {msg_hash[:16]}... direct delivery failed, retrying via propagation node")

                # Mark that we've attempted propagation and track the relay
                lxmf_message.propagation_retry_attempted = True
                lxmf_message.tried_relays.append(self.active_propagation_node)

                # Clear retry flag to prevent infinite loop
                lxmf_message.try_propagation_on_fail = False
                # Reset delivery attempts
                lxmf_message.delivery_attempts = 0
                # Clear packed state so message can be re-packed
                lxmf_message.packed = None
                # Clear propagation-specific state for fresh stamp generation
                lxmf_message.propagation_packed = None
                lxmf_message.propagation_stamp = None
                # Request deferred stamp generation - propagation nodes require valid stamps
                lxmf_message.defer_propagation_stamp = True
                # Switch to PROPAGATED delivery
                lxmf_message.desired_method = LXMF.LXMessage.PROPAGATED

                # Re-submit to router (will go through pending_deferred_stamps for stamp generation)
                self.router.handle_outbound(lxmf_message)

                # If message has file attachments, track it for notification AFTER propagation succeeds
                # We don't send the notification immediately - wait until the relay confirms receipt
                if hasattr(lxmf_message, 'fields') and lxmf_message.fields and 5 in lxmf_message.fields:
                    self._pending_file_notifications[msg_hash] = lxmf_message
                    log_debug("ReticulumWrapper", "_on_message_failed",
                             f"Tracking {msg_hash[:16]}... for pending file notification after propagation")

                # Notify Kotlin of retry (status = "retrying_propagated")
                if self.kotlin_delivery_status_callback:
                    try:
                        import json
                        status_event = {
                            'message_hash': msg_hash,
                            'status': 'retrying_propagated',
                            'timestamp': int(time.time() * 1000)
                        }
                        self.kotlin_delivery_status_callback(json.dumps(status_event))
                        log_debug("ReticulumWrapper", "_on_message_failed",
                                 "Kotlin callback invoked with retrying_propagated status")
                    except Exception as e:
                        log_error("ReticulumWrapper", "_on_message_failed",
                                 f"Error invoking Kotlin callback: {e}")
                return  # Don't report as failed - we're retrying

            # Case 2: Propagation retry failed - try alternative relay if available
            if (lxmf_message.propagation_retry_attempted and
                len(lxmf_message.tried_relays) < self._max_relay_retries and
                self.kotlin_request_alternative_relay_callback):

                log_info("ReticulumWrapper", "_on_message_failed",
                        f"📡 Message {msg_hash[:16]}... propagation failed, requesting alternative relay "
                        f"(tried {len(lxmf_message.tried_relays)}/{self._max_relay_retries})")

                # Store message for later retry when alternative is provided
                self._pending_relay_fallback_messages[msg_hash] = lxmf_message

                # Request alternative from Kotlin (exclude already tried relays)
                import json
                request = {
                    'message_hash': msg_hash,
                    'exclude_relays': [r.hex() if isinstance(r, bytes) else str(r) for r in lxmf_message.tried_relays],
                    'timestamp': int(time.time() * 1000)
                }
                try:
                    self.kotlin_request_alternative_relay_callback(json.dumps(request))
                    log_debug("ReticulumWrapper", "_on_message_failed",
                             f"Requested alternative relay for {msg_hash[:16]}...")
                except Exception as e:
                    log_error("ReticulumWrapper", "_on_message_failed",
                             f"Error requesting alternative relay: {e}")
                    # Fall through to permanent failure
                    del self._pending_relay_fallback_messages[msg_hash]

                # Notify Kotlin of status
                if self.kotlin_delivery_status_callback:
                    try:
                        status_event = {
                            'message_hash': msg_hash,
                            'status': 'retrying_alternative_relay',
                            'tried_count': len(lxmf_message.tried_relays),
                            'timestamp': int(time.time() * 1000)
                        }
                        self.kotlin_delivery_status_callback(json.dumps(status_event))
                    except Exception as e:
                        log_error("ReticulumWrapper", "_on_message_failed",
                                 f"Error notifying status: {e}")
                return  # Don't report as failed yet - waiting for alternative

            # Case 3: Max retries exceeded or no callback - fail permanently
            if len(lxmf_message.tried_relays) >= self._max_relay_retries:
                reason = 'max_relay_retries_exceeded'
            else:
                reason = 'delivery_failed'
            self._fail_message_permanently(lxmf_message, reason)

        except Exception as e:
            log_error("ReticulumWrapper", "_on_message_failed",
                     f"Error in failed callback: {e}")
            import traceback
            traceback.print_exc()

    def _fail_message_permanently(self, lxmf_message, reason: str):
        """
        Mark a message as permanently failed and notify Kotlin.

        Args:
            lxmf_message: The LXMF.LXMessage that failed
            reason: Reason for failure (e.g., 'max_relay_retries_exceeded', 'no_relays_available')
        """
        msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"

        log_error("ReticulumWrapper", "_fail_message_permanently",
                 f"❌ Message {msg_hash[:16]}... FAILED permanently: {reason}")

        # Remove from pending if present
        if msg_hash in self._pending_relay_fallback_messages:
            del self._pending_relay_fallback_messages[msg_hash]

        # Notify Kotlin with failure reason
        if self.kotlin_delivery_status_callback:
            try:
                import json
                status_event = {
                    'message_hash': msg_hash,
                    'status': 'failed',
                    'reason': reason,
                    'timestamp': int(time.time() * 1000)
                }
                self.kotlin_delivery_status_callback(json.dumps(status_event))
                log_debug("ReticulumWrapper", "_fail_message_permanently",
                         "Kotlin callback invoked successfully")
            except Exception as e:
                log_error("ReticulumWrapper", "_fail_message_permanently",
                         f"Error invoking Kotlin callback: {e}")
        else:
            log_warning("ReticulumWrapper", "_fail_message_permanently",
                       "No Kotlin callback registered - failure status not reported")

    def _extract_file_summary(self, lxmf_message) -> dict:
        """
        Extract summary of file attachments from an LXMF message.

        Args:
            lxmf_message: LXMF.LXMessage with fields

        Returns:
            Dict with first_filename, file_count, total_size, or None if no attachments
        """
        try:
            if not hasattr(lxmf_message, 'fields') or not lxmf_message.fields:
                return None

            # Field 5 = FILE_ATTACHMENTS: list of [filename, bytes] tuples
            if 5 not in lxmf_message.fields:
                return None

            attachments = lxmf_message.fields[5]
            if not attachments or not isinstance(attachments, list):
                return None

            first_filename = "file"
            file_count = 0
            total_size = 0

            for attachment in attachments:
                if isinstance(attachment, (list, tuple)) and len(attachment) >= 2:
                    filename = str(attachment[0]) if attachment[0] else "file"
                    data = attachment[1]
                    size = len(data) if isinstance(data, (bytes, bytearray)) else 0

                    if file_count == 0:
                        first_filename = filename

                    file_count += 1
                    total_size += size

            if file_count == 0:
                return None

            return {
                'first_filename': first_filename,
                'file_count': file_count,
                'total_size': total_size
            }

        except Exception as e:
            log_error("ReticulumWrapper", "_extract_file_summary",
                     f"Error extracting file summary: {e}")
            return None

    def _send_pending_file_notification(self, lxmf_message):
        """
        Send a lightweight notification to recipient that a file is coming via propagation.

        This is called when direct delivery fails and we fall back to propagation for a
        message with file attachments. The notification lets the recipient know they
        need to sync with the relay to receive the file.

        Args:
            lxmf_message: The original LXMF.LXMessage being retried via propagation
        """
        try:
            # Extract file summary
            file_summary = self._extract_file_summary(lxmf_message)
            if not file_summary:
                log_debug("ReticulumWrapper", "_send_pending_file_notification",
                         "No file attachments found, skipping notification")
                return

            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"

            # Build notification with Field 16 (APP_EXTENSIONS_FIELD)
            import json
            notification_data = {
                "pending_file_notification": {
                    "original_message_id": msg_hash,
                    "filename": file_summary['first_filename'],
                    "file_count": file_summary['file_count'],
                    "total_size": file_summary['total_size'],
                    "timestamp": int(time.time() * 1000)
                }
            }
            fields = {16: notification_data}

            # Create notification message - use OPPORTUNISTIC for fast delivery
            notification_msg = LXMF.LXMessage(
                destination=lxmf_message.destination,
                source=lxmf_message.source,
                content=b"",  # Empty content - notification only
                title="",
                fields=fields
            )
            notification_msg.desired_method = LXMF.LXMessage.OPPORTUNISTIC

            # Submit to router
            self.router.handle_outbound(notification_msg)

            log_info("ReticulumWrapper", "_send_pending_file_notification",
                    f"📤 Sent pending file notification for {msg_hash[:16]}... "
                    f"({file_summary['file_count']} files, {file_summary['total_size']} bytes)")

        except Exception as e:
            log_error("ReticulumWrapper", "_send_pending_file_notification",
                     f"Error sending notification: {e}")
            import traceback
            traceback.print_exc()

    def on_alternative_relay_received(self, relay_hash):
        """
        Called from Kotlin when an alternative relay is provided.

        When propagation to the current relay fails, Kotlin is asked to find an alternative.
        This method is called with the result - either a new relay hash or None if none available.

        Args:
            relay_hash: 16-byte destination hash of alternative relay (bytes or jarray),
                       or None if no alternatives available
        """
        try:
            if not self._pending_relay_fallback_messages:
                log_warning("ReticulumWrapper", "on_alternative_relay_received",
                           "No pending messages for relay fallback")
                return

            if relay_hash is None:
                # No alternatives available - fail all pending messages
                log_warning("ReticulumWrapper", "on_alternative_relay_received",
                           f"No alternative relays available, failing {len(self._pending_relay_fallback_messages)} messages")
                for msg_hash, message in list(self._pending_relay_fallback_messages.items()):
                    self._fail_message_permanently(message, 'no_relays_available')
                self._pending_relay_fallback_messages.clear()
                return

            # Convert jarray from Java if needed
            if hasattr(relay_hash, '__iter__') and not isinstance(relay_hash, (bytes, bytearray)):
                relay_hash = bytes(relay_hash)

            relay_hex = relay_hash.hex() if isinstance(relay_hash, bytes) else str(relay_hash)
            log_info("ReticulumWrapper", "on_alternative_relay_received",
                    f"📡 Received alternative relay: {relay_hex[:16]}..., retrying {len(self._pending_relay_fallback_messages)} messages")

            # Update active propagation node (directly set to bypass init checks in fallback)
            self.active_propagation_node = relay_hash
            # Also update router if available
            if self.initialized and self.router:
                try:
                    self.router.set_outbound_propagation_node(relay_hash)
                except Exception as e:
                    log_warning("ReticulumWrapper", "on_alternative_relay_received",
                               f"Could not update router propagation node: {e}")

            # Retry all pending messages with new relay
            for msg_hash, message in list(self._pending_relay_fallback_messages.items()):
                # Track this relay attempt
                if not hasattr(message, 'tried_relays'):
                    message.tried_relays = []
                message.tried_relays.append(relay_hash)

                # Reset for fresh retry
                message.delivery_attempts = 0
                message.packed = None
                message.propagation_packed = None
                message.propagation_stamp = None
                message.defer_propagation_stamp = True
                message.desired_method = LXMF.LXMessage.PROPAGATED

                # Re-submit to router
                self.router.handle_outbound(message)

                # Notify Kotlin
                if self.kotlin_delivery_status_callback:
                    try:
                        import json
                        status = {
                            'message_hash': msg_hash,
                            'status': 'retrying_propagated',
                            'relay_hash': relay_hex,
                            'timestamp': int(time.time() * 1000)
                        }
                        self.kotlin_delivery_status_callback(json.dumps(status))
                    except Exception as e:
                        log_error("ReticulumWrapper", "on_alternative_relay_received",
                                 f"Error notifying status: {e}")

            self._pending_relay_fallback_messages.clear()

        except Exception as e:
            log_error("ReticulumWrapper", "on_alternative_relay_received",
                     f"Error handling alternative relay: {e}")
            import traceback
            traceback.print_exc()

    def _on_message_sent(self, lxmf_message):
        """
        Called when a sent message reaches SENT state (0x04).
        This means the message was successfully transmitted to the network,
        but delivery proof has not yet been received.

        Note: This is NOT a callback from LXMF (no such callback exists).
        We check the message state directly after handle_outbound().

        Args:
            lxmf_message: The LXMF.LXMessage that reached SENT state
        """
        try:
            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"
            log_info("ReticulumWrapper", "_on_message_sent",
                    f"📤 Message {msg_hash[:16]}... SENT to network!")

            # Create status event for Kotlin
            status_event = {
                'message_hash': msg_hash,
                'status': 'sent',
                'timestamp': int(time.time() * 1000)
            }

            # Invoke Kotlin callback if registered (same pattern as delivery/failed)
            if self.kotlin_delivery_status_callback:
                try:
                    import json
                    self.kotlin_delivery_status_callback(json.dumps(status_event))
                    log_debug("ReticulumWrapper", "_on_message_sent",
                             "Kotlin callback invoked successfully")
                except Exception as e:
                    log_error("ReticulumWrapper", "_on_message_sent",
                             f"Error invoking Kotlin callback: {e}")
            else:
                log_warning("ReticulumWrapper", "_on_message_sent",
                           "No Kotlin callback registered - sent status not reported")

        except Exception as e:
            log_error("ReticulumWrapper", "_on_message_sent",
                     f"Error in sent callback: {e}")
            import traceback
            traceback.print_exc()

    def _start_opportunistic_timer(self):
        """
        Start the timer thread that checks for opportunistic message timeouts.
        This is called when the first opportunistic message is tracked, or during initialize().
        The timer thread is a daemon thread and will exit cleanly when the app shuts down.
        """
        if self._opportunistic_timer is None or not self._opportunistic_timer.is_alive():
            self._opportunistic_timer = threading.Thread(
                target=self._opportunistic_timeout_loop,
                daemon=True
            )
            self._opportunistic_timer.start()
            log_info("ReticulumWrapper", "_start_opportunistic_timer",
                    "Started opportunistic message timeout checker")

    def _opportunistic_timeout_loop(self):
        """
        Background loop that periodically checks for timed-out opportunistic messages.
        Runs every _opportunistic_check_interval seconds while self.initialized is True.
        """
        log_debug("ReticulumWrapper", "_opportunistic_timeout_loop", "Timeout loop started")
        while self.initialized:
            time.sleep(self._opportunistic_check_interval)
            if self._opportunistic_messages:  # Only check if there are messages to track
                self._check_opportunistic_timeouts()
        log_debug("ReticulumWrapper", "_opportunistic_timeout_loop", "Timeout loop exiting (not initialized)")

    def _check_opportunistic_timeouts(self):
        """
        Check for opportunistic messages that have timed out waiting for delivery.
        For any that have exceeded the timeout threshold, trigger the failure callback
        to initiate propagation fallback.

        This is the key fix for the issue where opportunistic messages to offline
        recipients get stuck in SENT state forever.
        """
        now = time.time()
        timed_out = []

        # Collect timed-out messages (iterate over copy to avoid modification during iteration)
        for msg_hash, tracking in list(self._opportunistic_messages.items()):
            elapsed = now - tracking['sent_time']
            if elapsed >= self._opportunistic_timeout_seconds:
                timed_out.append((msg_hash, tracking['message']))

        # Process timed-out messages
        for msg_hash, lxmf_message in timed_out:
            log_info("ReticulumWrapper", "_check_opportunistic_timeouts",
                    f"⏱️ Opportunistic message {msg_hash[:16]}... timed out after "
                    f"{self._opportunistic_timeout_seconds}s, triggering propagation fallback")
            # Remove from tracking dict (will also be removed in _on_message_failed, but do it here
            # to prevent any race condition where the message could be processed twice)
            if msg_hash in self._opportunistic_messages:
                del self._opportunistic_messages[msg_hash]
            # Trigger the failure callback which handles propagation fallback
            self._on_message_failed(lxmf_message)

    # ========================================================================
    # Service Heartbeat (Sideband-inspired process monitoring)
    # ========================================================================

    def _start_heartbeat_thread(self):
        """
        Start the heartbeat thread that updates the timestamp every second.
        Kotlin monitors this timestamp and restarts the service if it becomes stale.
        This is called during initialize() after setting self.initialized = True.
        """
        if self._heartbeat_thread is None or not self._heartbeat_thread.is_alive():
            self._heartbeat_thread = threading.Thread(
                target=self._heartbeat_loop,
                daemon=True
            )
            self._heartbeat_thread.start()
            log_info("ReticulumWrapper", "_start_heartbeat_thread",
                    "Started service heartbeat thread (1s interval)")

    def _get_propagation_state_name(self, state: int) -> str:
        """Map LXMF propagation state integer to human-readable name."""
        state_names = {
            LXMF.LXMRouter.PR_IDLE: "idle",
            LXMF.LXMRouter.PR_PATH_REQUESTED: "path_requested",
            LXMF.LXMRouter.PR_LINK_ESTABLISHING: "link_establishing",
            LXMF.LXMRouter.PR_LINK_ESTABLISHED: "link_established",
            LXMF.LXMRouter.PR_REQUEST_SENT: "request_sent",
            LXMF.LXMRouter.PR_RECEIVING: "receiving",
            LXMF.LXMRouter.PR_RESPONSE_RECEIVED: "response_received",
            LXMF.LXMRouter.PR_COMPLETE: "complete",
            LXMF.LXMRouter.PR_NO_PATH: "no_path",
            LXMF.LXMRouter.PR_LINK_FAILED: "link_failed",
            LXMF.LXMRouter.PR_TRANSFER_FAILED: "transfer_failed",
            LXMF.LXMRouter.PR_NO_IDENTITY_RCVD: "no_identity_rcvd",
            LXMF.LXMRouter.PR_NO_ACCESS: "no_access",
        }
        return state_names.get(state, f"unknown_{state}")

    def _check_propagation_state_change(self):
        """
        Check if LXMF propagation state or progress changed and notify Kotlin if so.
        Called from heartbeat loop at higher frequency during active sync.

        Fires callback when:
        - State changes (idle → receiving → complete, etc.)
        - Progress changes by more than 1% during active transfer (state 5 = RECEIVING)
        """
        if not self.router or not self.kotlin_propagation_state_callback:
            return

        try:
            current_state = self.router.propagation_transfer_state
            progress = getattr(self.router, 'propagation_transfer_progress', 0.0) or 0.0
            messages_received = getattr(self.router, 'propagation_transfer_last_result', 0) or 0

            # Determine if we should send a callback:
            # 1. State changed
            # 2. Progress changed by more than 1% during active receiving (state 5)
            state_changed = current_state != self._last_propagation_state
            progress_changed = (current_state == 5 and  # STATE_RECEIVING
                              abs(progress - self._last_propagation_progress) >= 0.01)

            if state_changed or progress_changed:
                self._last_propagation_state = current_state
                self._last_propagation_progress = progress

                state_info = {
                    "state": current_state,
                    "state_name": self._get_propagation_state_name(current_state),
                    "progress": progress,
                    "messages_received": messages_received
                }
                self.kotlin_propagation_state_callback(json.dumps(state_info))

                if state_changed:
                    log_debug("ReticulumWrapper", "_check_propagation_state_change",
                             f"Propagation state changed: {state_info['state_name']} ({current_state})")
                else:
                    log_debug("ReticulumWrapper", "_check_propagation_state_change",
                             f"Propagation progress: {progress:.1%}")
        except Exception as e:
            log_error("ReticulumWrapper", "_check_propagation_state_change", f"Error: {e}")

    def _heartbeat_loop(self):
        """
        Background loop that updates the heartbeat timestamp.
        Also monitors propagation state changes for real-time sync progress.
        Uses faster interval (100ms) during active sync, slower (1s) when idle.
        """
        log_debug("ReticulumWrapper", "_heartbeat_loop", "Heartbeat loop started")
        while self.initialized:
            self._heartbeat_timestamp = time.time()

            # Check propagation state changes (for real-time sync progress)
            self._check_propagation_state_change()

            # Use faster interval during active sync (100ms), slower when idle (1s)
            if (self.router and
                self._last_propagation_state is not None and
                self._last_propagation_state not in (0, 7, 0xf0, 0xf1, 0xf2, 0xf3, 0xf4)):
                # Active sync in progress - check more frequently
                time.sleep(0.1)
            else:
                # Idle or complete - normal heartbeat interval
                time.sleep(1)
        log_debug("ReticulumWrapper", "_heartbeat_loop", "Heartbeat loop exiting (not initialized)")

    def get_heartbeat(self) -> float:
        """
        Get the current heartbeat timestamp.
        Kotlin calls this periodically to check if the Python process is responsive.

        Returns:
            Unix timestamp of the last heartbeat, or 0.0 if not initialized
        """
        return self._heartbeat_timestamp

    # ========================================================================
    # Service Maintenance (Sideband-inspired interface recovery)
    # ========================================================================

    def _start_maintenance_thread(self):
        """
        Start the maintenance thread that periodically checks for failed interfaces
        and attempts to reinitialize them. This is called during initialize().
        """
        if self._maintenance_thread is None or not self._maintenance_thread.is_alive():
            self._maintenance_thread = threading.Thread(
                target=self._maintenance_loop,
                daemon=True
            )
            self._maintenance_thread.start()
            log_info("ReticulumWrapper", "_start_maintenance_thread",
                    f"Started service maintenance thread ({self._interface_reinit_interval}s interval)")

    def _maintenance_loop(self):
        """
        Background loop that performs periodic maintenance tasks:
        1. Checks for failed interfaces and attempts reinit
        2. Could be extended for other maintenance tasks

        Runs while self.initialized is True.
        """
        log_debug("ReticulumWrapper", "_maintenance_loop", "Maintenance loop started")
        while self.initialized:
            time.sleep(1)  # Check every second, but only reinit per interval

            # Check if it's time to retry failed interfaces
            now = time.time()
            if (len(self.failed_interfaces) > 0 and
                now - self._last_interface_reinit_attempt >= self._interface_reinit_interval):
                self._retry_failed_interfaces()
                self._last_interface_reinit_attempt = now

        log_debug("ReticulumWrapper", "_maintenance_loop", "Maintenance loop exiting (not initialized)")

    def _retry_failed_interfaces(self):
        """
        Attempt to reinitialize interfaces that failed during startup.
        This gives the service a chance to recover from transient failures.
        """
        if not self.failed_interfaces:
            return

        log_info("ReticulumWrapper", "_retry_failed_interfaces",
                f"Attempting to reinitialize {len(self.failed_interfaces)} failed interface(s)")

        # For now, just log the failed interfaces - full reinit would require
        # more complex logic to re-parse the config and recreate interfaces
        for iface_info in self.failed_interfaces:
            iface_type = iface_info.get('type', 'Unknown')
            error = iface_info.get('error', 'Unknown error')
            log_info("ReticulumWrapper", "_retry_failed_interfaces",
                    f"  - {iface_type}: {error}")

        # TODO: Implement actual interface reinit when we have the config available
        # For now, clearing the list prevents repeated logging
        # A more complete implementation would:
        # 1. Store the original config for failed interfaces
        # 2. Attempt to recreate the interface
        # 3. On success, add to RNS.Transport.interfaces
        # 4. On failure, keep in failed_interfaces for next retry

    def get_transport_identity_hash(self) -> bytes:
        """
        Get the Reticulum Transport identity hash for BLE Protocol v2.
        This is the 16-byte identity hash used for stable peer identification.

        Returns:
            16-byte identity hash, or None if not available
        """
        if not self.initialized or self.transport_identity_hash is None:
            log_warning("ReticulumWrapper", "get_transport_identity_hash", "WARNING: get_transport_identity_hash called before initialization")
            return None

        log_debug("ReticulumWrapper", "get_transport_identity_hash", f"Returning transport identity hash: {self.transport_identity_hash.hex()}")
        return self.transport_identity_hash

    def get_lxmf_identity(self) -> Dict:
        """
        Get the LXMF router's identity.
        This should be used for both announces and messaging to ensure consistency.

        Returns:
            Dict with identity data (hash, public_key, private_key)
        """
        if not RETICULUM_AVAILABLE or not self.router:
            return {"error": "LXMF router not initialized"}

        try:
            identity = self.router.identity
            return {
                'hash': identity.hash,
                'public_key': identity.get_public_key(),
                'private_key': identity.get_private_key()
            }
        except Exception as e:
            log_error("ReticulumWrapper", "get_lxmf_identity", f"Error getting LXMF identity: {e}")
            return {"error": str(e)}

    def recall_identity(self, destination_hash_hex: str) -> Dict:
        """
        Attempt to recall an identity from Reticulum's local cache by destination hash.

        This checks the known_destinations cache for a previously seen identity
        that announced with this destination hash.

        Args:
            destination_hash_hex: The destination hash as a hex string (32 chars)

        Returns:
            Dict with:
                - {"found": True, "public_key": "hex..."} if identity is found
                - {"found": False} if identity is not in cache
                - {"error": "..."} if an error occurred
        """
        try:
            if not RETICULUM_AVAILABLE:
                return {"found": False, "error": "Reticulum not available"}

            # Convert hex string to bytes
            dest_hash = bytes.fromhex(destination_hash_hex)
            log_debug("ReticulumWrapper", "recall_identity", f"Attempting to recall identity for dest hash: {destination_hash_hex[:16]}...")

            # Try to recall the identity from Reticulum's cache
            identity = RNS.Identity.recall(dest_hash)

            if identity:
                public_key = identity.get_public_key()
                log_info("ReticulumWrapper", "recall_identity", f"Found identity in cache for {destination_hash_hex[:16]}...")
                return {
                    "found": True,
                    "public_key": public_key.hex()
                }
            else:
                log_debug("ReticulumWrapper", "recall_identity", f"No identity found in cache for {destination_hash_hex[:16]}...")
                return {"found": False}

        except ValueError as e:
            log_error("ReticulumWrapper", "recall_identity", f"Invalid hex string: {e}")
            return {"found": False, "error": f"Invalid hex string: {e}"}
        except Exception as e:
            log_error("ReticulumWrapper", "recall_identity", f"Error recalling identity: {e}")
            return {"found": False, "error": str(e)}

    def store_peer_identity(self, identity_hash: bytes, public_key: bytes) -> Dict:
        """
        Store a peer's identity in Reticulum's identity store so it can be recalled later.
        This is crucial for allowing message sending after app restarts.

        Args:
            identity_hash: The identity hash of the peer (16 bytes)
            public_key: The public key of the peer (32 bytes)

        Returns:
            Dict with 'success' boolean and optional 'error' message
        """
        try:
            if not RETICULUM_AVAILABLE:
                return {"success": False, "error": "Reticulum not available"}

            # Convert jarray to bytes if needed
            if hasattr(identity_hash, '__iter__') and not isinstance(identity_hash, (bytes, bytearray)):
                identity_hash = bytes(identity_hash)
            if hasattr(public_key, '__iter__') and not isinstance(public_key, (bytes, bytearray)):
                public_key = bytes(public_key)

            # NOTE: identity_hash parameter is now the actual IDENTITY hash (16 bytes from public key)
            # NOT a destination hash (which would be 16 bytes derived from identity + app + aspect)
            log_debug("ReticulumWrapper", "store_peer_identity", f"Storing peer identity {identity_hash.hex()[:16]}... with public key (len={len(public_key)})")

            # Create an Identity instance from the public key
            identity = RNS.Identity(create_keys=False)
            identity.load_public_key(public_key)

            actual_identity_hash = identity.hash
            log_info("ReticulumWrapper", "store_peer_identity", f"Created identity with hash: {actual_identity_hash.hex()[:16]}")
            log_debug("ReticulumWrapper", "store_peer_identity", f"Expected identity hash from DB: {identity_hash.hex()[:16]}")

            # Check if the identity hash matches what's in the database
            # If not, log warning but USE THE ACTUAL HASH (public key is source of truth)
            if actual_identity_hash != identity_hash:
                log_warning("ReticulumWrapper", "store_peer_identity",
                          f"⚠️ Identity hash mismatch: DB has {identity_hash.hex()[:16]} but public key hashes to {actual_identity_hash.hex()[:16]}")
                log_warning("ReticulumWrapper", "store_peer_identity",
                          f"⚠️ Using actual hash from public key. Database may have stale/incorrect hash.")
                # Continue with actual hash - don't fail the restoration

            # Store the identity using Reticulum's internal mechanisms
            # Since Transport.identity_table isn't publicly accessible, we need to use
            # the methods that Reticulum provides for identity storage
            try:
                # Create and register the LXMF destination
                # This is what Reticulum caches when announces arrive
                lxmf_destination = RNS.Destination(
                    identity,
                    RNS.Destination.OUT,
                    RNS.Destination.SINGLE,
                    "lxmf", "delivery"
                )
                lxmf_dest_hash = lxmf_destination.hash
                log_info("ReticulumWrapper", "store_peer_identity", f"Created LXMF destination with hash: {lxmf_dest_hash.hex()[:16]}")

                # Try to register this destination with Reticulum
                # Option 1: Try registering with the Transport layer
                try:
                    # Register the destination hash in Transport so it can be recalled
                    RNS.Transport.register_destination(lxmf_destination)
                    log_debug("ReticulumWrapper", "store_peer_identity", f"Registered destination with Transport.register_destination()")
                except AttributeError:
                    log_debug("ReticulumWrapper", "store_peer_identity", f"Transport.register_destination() not available")
                except Exception as reg_err:
                    log_error("ReticulumWrapper", "store_peer_identity", f"Error calling register_destination: {reg_err}")

                # Option 2: Store in our own cache for this session
                # Store in self.identities for local recall
                # IMPORTANT: Store using MULTIPLE hashes to handle all lookup scenarios:
                # 1. Actual identity hash (computed from public key)
                # 2. Database identity hash (may differ due to data issues)
                # 3. LXMF destination hash (for send_lxmf_message lookup)
                actual_identity_hash_hex = actual_identity_hash.hex()
                db_identity_hash_hex = identity_hash.hex()  # Hash from database (may be wrong)
                lxmf_dest_hash_hex = lxmf_dest_hash.hex()

                self.identities[actual_identity_hash_hex] = identity  # Store by actual identity hash
                self.identities[lxmf_dest_hash_hex] = identity  # Store by LXMF dest hash (for send lookup)

                # ALSO store by database hash if it differs (handles data integrity issues)
                if actual_identity_hash != identity_hash:
                    self.identities[db_identity_hash_hex] = identity
                    log_debug("ReticulumWrapper", "store_peer_identity", f"  - by DB hash (mismatched): {db_identity_hash_hex[:16]}")

                log_debug("ReticulumWrapper", "store_peer_identity", f"Stored identity in local cache:")
                log_debug("ReticulumWrapper", "store_peer_identity", f"  - by actual identity hash: {actual_identity_hash_hex[:16]}")
                log_debug("ReticulumWrapper", "store_peer_identity", f"  - by LXMF dest hash: {lxmf_dest_hash_hex[:16]}")

                # Option 3: Try to make Reticulum cache it by calling recall
                # This might trigger internal caching
                test_recall = RNS.Identity.recall(lxmf_dest_hash)
                if test_recall:
                    log_info("ReticulumWrapper", "store_peer_identity", f"✅ Identity already recallable")
                else:
                    log_warning("ReticulumWrapper", "store_peer_identity", f"Warning: Identity not yet recallable via RNS.Identity.recall()")

                log_info("ReticulumWrapper", "store_peer_identity", f"✅ Stored peer identity and LXMF destination")
                return {"success": True}

            except Exception as e:
                log_debug("ReticulumWrapper", "store_peer_identity", f"Could not store identity: {e}")
                import traceback
                traceback.print_exc()
                return {"success": False, "error": str(e)}

        except Exception as e:
            log_error("ReticulumWrapper", "store_peer_identity", f"Error storing peer identity: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    def restore_all_peer_identities(self, peer_data) -> Dict:
        """
        Restore multiple peer identities at once (e.g., on app startup).

        Args:
            peer_data: JSON string or List of dicts with 'identity_hash' and 'public_key' keys

        Returns:
            Dict with 'success' count and 'errors' list
        """
        try:
            if not RETICULUM_AVAILABLE:
                return {"success_count": 0, "errors": ["Reticulum not available"]}

            # Parse JSON string if needed
            if isinstance(peer_data, str):
                import json
                peer_data = json.loads(peer_data)

            log_debug("ReticulumWrapper", "restore_all_peer_identities", f"restore_all_peer_identities called with {len(peer_data)} peers")

            success_count = 0
            errors = []

            for i, peer in enumerate(peer_data):
                try:
                    identity_hash_str = peer.get('identity_hash')
                    public_key_str = peer.get('public_key')

                    if not identity_hash_str:
                        errors.append(f"Peer {i}: missing identity_hash (keys: {list(peer.keys())})")
                        continue
                    if not public_key_str:
                        errors.append(f"Peer {i}: missing public_key")
                        continue

                    # Convert hex string to bytes
                    identity_hash = bytes.fromhex(identity_hash_str)

                    # Decode base64 string to bytes
                    import base64
                    public_key = base64.b64decode(public_key_str)

                    result = self.store_peer_identity(identity_hash, public_key)
                    if result.get('success'):
                        success_count += 1
                        if i < 3:  # Log first few successes
                            log_info("ReticulumWrapper", "restore_all_peer_identities", f"Successfully restored peer {i}: {identity_hash_str[:16]}")
                    else:
                        error_msg = f"Failed to restore {identity_hash_str[:16]}: {result.get('error')}"
                        errors.append(error_msg)
                        log_error("ReticulumWrapper", "restore_all_peer_identities", f"{error_msg}")
                except Exception as e:
                    error_msg = f"Error processing peer {i}: {e}"
                    errors.append(error_msg)
                    log_error("ReticulumWrapper", "restore_all_peer_identities", f"{error_msg}")

            log_info("ReticulumWrapper", "restore_all_peer_identities", f"Restored {success_count} peer identities, {len(errors)} errors")
            return {"success_count": success_count, "errors": errors}

        except Exception as e:
            log_error("ReticulumWrapper", "restore_all_peer_identities", f"Error restoring peer identities: {e}")
            return {"success_count": 0, "errors": [str(e)]}

    def bulk_restore_announce_identities(self, announce_data) -> Dict:
        """
        Bulk restore announce identities by directly populating Identity.known_destinations.
        For announces, we have destination_hash directly - no computation needed.
        This is much faster than creating full RNS Identity/Destination objects.

        Args:
            announce_data: JSON string or List of dicts with 'destination_hash' and 'public_key' keys

        Returns:
            Dict with 'success_count' and 'errors' list
        """
        try:
            if not RETICULUM_AVAILABLE:
                return {"success_count": 0, "errors": ["Reticulum not available"]}

            # Parse JSON string if needed
            if isinstance(announce_data, str):
                import json
                announce_data = json.loads(announce_data)

            log_debug("ReticulumWrapper", "bulk_restore_announce_identities",
                     f"Bulk restoring {len(announce_data)} announce identities")

            import time
            import base64

            success_count = 0
            errors = []
            expected_key_size = RNS.Identity.KEYSIZE // 8  # Convert bits to bytes (512 bits = 64 bytes)

            for i, announce in enumerate(announce_data):
                try:
                    dest_hash_str = announce.get('destination_hash')
                    public_key_str = announce.get('public_key')

                    if not dest_hash_str:
                        errors.append(f"Announce {i}: missing destination_hash")
                        continue
                    if not public_key_str:
                        errors.append(f"Announce {i}: missing public_key")
                        continue

                    # Convert hex string to bytes
                    dest_hash = bytes.fromhex(dest_hash_str)

                    # Decode base64 string to bytes
                    public_key = base64.b64decode(public_key_str)

                    # Validate public key size
                    if len(public_key) != expected_key_size:
                        errors.append(f"Announce {i}: Invalid public key size {len(public_key)}, expected {expected_key_size}")
                        continue

                    # Directly populate Identity.known_destinations
                    # Format: [timestamp, packet_hash, public_key, app_data]
                    RNS.Identity.known_destinations[dest_hash] = [
                        time.time(),  # timestamp
                        None,         # packet_hash (not needed for recall)
                        public_key,   # public key bytes
                        None          # app_data
                    ]

                    # Also store in local identities cache for wrapper lookups
                    # Create a lightweight identity object for local cache
                    identity = RNS.Identity(create_keys=False)
                    identity.load_public_key(public_key)
                    self.identities[dest_hash_str] = identity

                    success_count += 1

                except Exception as e:
                    errors.append(f"Error processing announce {i}: {e}")

            log_info("ReticulumWrapper", "bulk_restore_announce_identities",
                    f"Bulk restored {success_count} announce identities, {len(errors)} errors")
            return {"success_count": success_count, "errors": errors}

        except Exception as e:
            log_error("ReticulumWrapper", "bulk_restore_announce_identities",
                     f"Error bulk restoring announce identities: {e}")
            return {"success_count": 0, "errors": [str(e)]}

    def bulk_restore_peer_identities(self, peer_data) -> Dict:
        """
        Bulk restore peer identities using lightweight hash computation.
        For peer identities, we must compute destination_hash from identity_hash.
        This is faster than creating full RNS Identity/Destination objects.

        Args:
            peer_data: JSON string or List of dicts with 'identity_hash' and 'public_key' keys

        Returns:
            Dict with 'success_count' and 'errors' list
        """
        try:
            if not RETICULUM_AVAILABLE:
                return {"success_count": 0, "errors": ["Reticulum not available"]}

            # Parse JSON string if needed
            if isinstance(peer_data, str):
                import json
                peer_data = json.loads(peer_data)

            log_debug("ReticulumWrapper", "bulk_restore_peer_identities",
                     f"Bulk restoring {len(peer_data)} peer identities")

            import time
            import base64

            success_count = 0
            errors = []
            expected_key_size = RNS.Identity.KEYSIZE // 8  # Convert bits to bytes

            # Precompute the LXMF name_hash (constant for all LXMF delivery destinations)
            # This is: hash("lxmf.delivery")[:NAME_HASH_LENGTH//8]
            lxmf_name = "lxmf.delivery"
            lxmf_name_hash = RNS.Identity.full_hash(lxmf_name.encode("utf-8"))[:(RNS.Identity.NAME_HASH_LENGTH // 8)]

            for i, peer in enumerate(peer_data):
                try:
                    identity_hash_str = peer.get('identity_hash')
                    public_key_str = peer.get('public_key')

                    if not identity_hash_str:
                        errors.append(f"Peer {i}: missing identity_hash")
                        continue
                    if not public_key_str:
                        errors.append(f"Peer {i}: missing public_key")
                        continue

                    # Decode base64 string to bytes
                    public_key = base64.b64decode(public_key_str)

                    # Validate public key size
                    if len(public_key) != expected_key_size:
                        errors.append(f"Peer {i}: Invalid public key size {len(public_key)}, expected {expected_key_size}")
                        continue

                    # Compute the identity hash from the public key (this is the authoritative hash)
                    # Identity hash = truncated_hash(public_key)
                    actual_identity_hash = RNS.Identity.truncated_hash(public_key)

                    # Compute the LXMF delivery destination hash
                    # dest_hash = full_hash(name_hash + identity_hash)[:TRUNCATED_HASHLENGTH//8]
                    addr_hash_material = lxmf_name_hash + actual_identity_hash
                    dest_hash = RNS.Identity.full_hash(addr_hash_material)[:RNS.Reticulum.TRUNCATED_HASHLENGTH // 8]

                    # Directly populate Identity.known_destinations
                    # Format: [timestamp, packet_hash, public_key, app_data]
                    RNS.Identity.known_destinations[dest_hash] = [
                        time.time(),  # timestamp
                        None,         # packet_hash (not needed for recall)
                        public_key,   # public key bytes
                        None          # app_data
                    ]

                    # Also store in local identities cache for wrapper lookups
                    identity = RNS.Identity(create_keys=False)
                    identity.load_public_key(public_key)

                    # Store by multiple keys for lookup flexibility
                    self.identities[actual_identity_hash.hex()] = identity
                    self.identities[dest_hash.hex()] = identity

                    success_count += 1

                except Exception as e:
                    errors.append(f"Error processing peer {i}: {e}")

            log_info("ReticulumWrapper", "bulk_restore_peer_identities",
                    f"Bulk restored {success_count} peer identities, {len(errors)} errors")
            return {"success_count": success_count, "errors": errors}

        except Exception as e:
            log_error("ReticulumWrapper", "bulk_restore_peer_identities",
                     f"Error bulk restoring peer identities: {e}")
            return {"success_count": 0, "errors": [str(e)]}

    def get_lxmf_destination(self) -> Dict:
        """
        Get the local LXMF delivery destination hash.

        Returns:
            Dict with destination data
        """
        if not RETICULUM_AVAILABLE or not self.local_lxmf_destination:
            return {"error": "LXMF destination not created"}

        return {
            'hash': self.local_lxmf_destination.hash,
            'hex_hash': self.local_lxmf_destination.hexhash
        }

    def poll_received_messages(self) -> List[Dict]:
        """
        Fetch and clear pending LXMF messages from the queue.

        Called by:
        - Startup drain: catches messages that arrived before callback registration
        - Event-driven fetch: retrieves message when callback notification fires

        Returns:
            List of received message dicts
        """
        if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
            return []

        try:
            new_messages = []

            # Check pending inbound messages
            if hasattr(self.router, 'pending_inbound') and self.router.pending_inbound:
                log_info("ReticulumWrapper", "poll_received_messages", f"📬 Found {len(self.router.pending_inbound)} pending message(s)")

                for lxmf_message in list(self.router.pending_inbound):
                    try:
                        msg_hash = lxmf_message.hash.hex()
                        if msg_hash in self.seen_message_hashes:
                            continue

                        self.seen_message_hashes.add(msg_hash)

                        # Extract message data
                        message_event = {
                            'message_hash': msg_hash,
                            'content': lxmf_message.content.decode('utf-8') if isinstance(lxmf_message.content, bytes) else str(lxmf_message.content),
                            'source_hash': lxmf_message.source_hash,
                            'destination_hash': lxmf_message.destination_hash,
                            'timestamp': int(lxmf_message.timestamp * 1000) if lxmf_message.timestamp else int(time.time() * 1000)
                        }

                        # Use hop count and interface captured at delivery time
                        # (values are stored on message object in _on_lxmf_delivery)
                        if hasattr(lxmf_message, '_columba_hops'):
                            message_event['hops'] = lxmf_message._columba_hops
                            log_debug("ReticulumWrapper", "poll_received_messages",
                                     f"📡 Hop count (captured at delivery): {lxmf_message._columba_hops}")
                        if hasattr(lxmf_message, '_columba_interface'):
                            message_event['receiving_interface'] = lxmf_message._columba_interface
                            log_debug("ReticulumWrapper", "poll_received_messages",
                                     f"📡 Receiving interface (captured at delivery): {lxmf_message._columba_interface}")

                        # Try to get sender's public key from RNS identity cache
                        try:
                            source_identity = RNS.Identity.recall(lxmf_message.source_hash)
                            if source_identity is not None:
                                public_key = source_identity.get_public_key()
                                if public_key:
                                    message_event['public_key'] = public_key
                                    log_debug("ReticulumWrapper", "poll_received_messages",
                                             f"✅ Found public key for sender {lxmf_message.source_hash.hex()[:16]}")
                            else:
                                log_debug("ReticulumWrapper", "poll_received_messages",
                                         f"⚠️ No identity found for sender {lxmf_message.source_hash.hex()[:16]}")
                        except Exception as e:
                            log_debug("ReticulumWrapper", "poll_received_messages",
                                     f"⚠️ Error getting public key: {e}")

                        # Extract LXMF fields (attachments, images, etc.)
                        if hasattr(lxmf_message, 'fields') and lxmf_message.fields:
                            fields_serialized = {}
                            for key, value in lxmf_message.fields.items():
                                # Handle different LXMF field formats
                                # Field 5 (FILE_ATTACHMENTS): list of [filename, bytes] tuples
                                # Field 6 (IMAGE): ['format', bytes] e.g. ['jpg', b'\xff\xd8...']
                                # Field 7 (AUDIO): ['format', bytes]

                                if key == 5 and isinstance(value, list):
                                    # Field 5 is a list of [filename, bytes] tuples
                                    serialized_attachments = []
                                    for attachment in value:
                                        if isinstance(attachment, (list, tuple)) and len(attachment) >= 2:
                                            filename = attachment[0]
                                            file_data = attachment[1]
                                            if isinstance(file_data, bytes):
                                                serialized_attachments.append({
                                                    'filename': str(filename),
                                                    'data': file_data.hex(),
                                                    'size': len(file_data)
                                                })
                                                log_debug("ReticulumWrapper", "poll_received_messages",
                                                         f"Field 5: file '{filename}' ({len(file_data)} bytes)")
                                    if serialized_attachments:
                                        fields_serialized['5'] = serialized_attachments
                                        log_info("ReticulumWrapper", "poll_received_messages",
                                                f"📎 Field 5: extracted {len(serialized_attachments)} file attachment(s)")

                                elif key == 16 and isinstance(value, dict):
                                    # Field 16 is app extensions dict: {"reply_to": "...", "reactions": {...}, etc.}
                                    fields_serialized['16'] = value

                                    # Check for reaction message (has reaction_to key)
                                    if 'reaction_to' in value:
                                        # Mark message as a reaction for easy identification by Kotlin
                                        message_event['is_reaction'] = True
                                        message_event['reaction_to'] = value.get('reaction_to', '')
                                        message_event['reaction_emoji'] = value.get('emoji', '')
                                        message_event['reaction_sender'] = value.get('sender', '')
                                        log_info("ReticulumWrapper", "poll_received_messages",
                                                f"😀 Field 16: reaction '{value.get('emoji', '')}' to message {value['reaction_to'][:16]}...")
                                    elif 'reply_to' in value:
                                        log_debug("ReticulumWrapper", "poll_received_messages",
                                                 f"Field 16: reply to message {value['reply_to'][:16]}...")
                                    else:
                                        log_debug("ReticulumWrapper", "poll_received_messages",
                                                 f"Field 16: app extensions with keys {list(value.keys())}")

                                elif key == 4 and isinstance(value, list) and len(value) >= 3:
                                    # Field 4 (FIELD_ICON_APPEARANCE): [icon_name, fg_rgb, bg_rgb]
                                    try:
                                        icon_appearance = {
                                            'icon_name': value[0],
                                            'foreground_color': value[1].hex() if isinstance(value[1], bytes) else value[1],
                                            'background_color': value[2].hex() if isinstance(value[2], bytes) else value[2],
                                        }
                                        message_event['icon_appearance'] = icon_appearance
                                        fields_serialized['4'] = icon_appearance
                                        log_debug("ReticulumWrapper", "poll_received_messages",
                                                 f"Field 4: icon appearance '{icon_appearance['icon_name']}'")
                                    except Exception as e:
                                        log_debug("ReticulumWrapper", "poll_received_messages",
                                                 f"Failed to parse icon appearance: {e}")
                                        fields_serialized[str(key)] = str(value)

                                elif isinstance(value, (list, tuple)) and len(value) >= 2:
                                    # Image/audio format: [format_string, bytes_data]
                                    if isinstance(value[1], bytes):
                                        fields_serialized[str(key)] = value[1].hex()
                                        log_debug("ReticulumWrapper", "poll_received_messages",
                                                 f"Field {key}: extracted {len(value[1])} bytes ({value[0] if value[0] else 'unknown'} format)")
                                    else:
                                        fields_serialized[str(key)] = str(value)
                                elif isinstance(value, bytes):
                                    fields_serialized[str(key)] = value.hex()
                                else:
                                    fields_serialized[str(key)] = str(value)
                            message_event['fields'] = fields_serialized
                            log_info("ReticulumWrapper", "poll_received_messages", f"📎 Message has {len(fields_serialized)} field(s): {list(fields_serialized.keys())}")

                        new_messages.append(message_event)
                        log_debug("ReticulumWrapper", "poll_received_messages", f"📨 Found new message from {lxmf_message.source_hash.hex()[:16]}")

                    except Exception as e:
                        log_error("ReticulumWrapper", "poll_received_messages", f"Error processing message: {e}")

            if new_messages:
                log_debug("ReticulumWrapper", "poll_received_messages", f"poll_received_messages() returning {len(new_messages)} new messages")
                # CRITICAL FIX: Clear processed messages from pending_inbound queue
                # This prevents messages from being stuck in the queue forever
                if hasattr(self.router, 'pending_inbound') and self.router.pending_inbound:
                    log_debug("ReticulumWrapper", "poll_received_messages", f"Clearing {len(self.router.pending_inbound)} messages from pending_inbound queue")
                    self.router.pending_inbound.clear()

            return new_messages

        except Exception as e:
            log_error("ReticulumWrapper", "poll_received_messages", f"Error polling messages: {e}")
            import traceback
            traceback.print_exc()
            return []

    def has_path(self, dest_hash: bytes) -> bool:
        """Check if a path to destination exists"""
        if not RETICULUM_AVAILABLE or not self.reticulum:
            return True  # Mock mode

        return RNS.Transport.has_path(dest_hash)

    def request_path(self, dest_hash: bytes) -> Dict:
        """Request a path to a destination"""
        try:
            if not RETICULUM_AVAILABLE:
                return {"success": True}

            RNS.Transport.request_path(dest_hash)
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def get_hop_count(self, dest_hash: bytes) -> Optional[int]:
        """Get hop count to destination"""
        if not RETICULUM_AVAILABLE or not self.reticulum:
            return 3  # Mock value

        try:
            if RNS.Transport.has_path(dest_hash):
                return RNS.Transport.hops_to(dest_hash)
            return None
        except Exception as e:
            log_error("ReticulumWrapper", "get_hop_count", f"Error: {e}")
            return None

    def probe_link_speed(self, dest_hash: bytes, timeout_seconds: float = 10.0,
                         delivery_method: str = "direct") -> Dict:
        """
        Probe the link speed to a destination by checking existing links or
        establishing one via establish_link().

        This provides link speed data for adaptive image compression.

        Args:
            dest_hash: Destination hash as bytes (16 bytes)
            timeout_seconds: How long to wait for link establishment (default 10s)
            delivery_method: "direct" or "propagated" - affects which link to check/establish

        Returns:
            Dict with:
            - status: "success", "no_path", "no_identity", "timeout", or "error"
            - establishment_rate_bps: Bits/sec from link handshake (or None)
            - expected_rate_bps: Bits/sec from actual transfers (or None)
            - rtt_seconds: Round-trip time in seconds (or None)
            - hops: Number of hops to destination (or None)
            - link_reused: True if an existing active link was used
            - delivery_method: "direct" or "propagated" - which method was used
        """
        if not RETICULUM_AVAILABLE or not self.router:
            return {
                "status": "not_initialized",
                "establishment_rate_bps": None,
                "expected_rate_bps": None,
                "rtt_seconds": None,
                "hops": None,
                "link_reused": False,
                "delivery_method": delivery_method,
                "next_hop_bitrate_bps": None,
                "link_mtu": None
            }

        try:
            # Convert jarray to bytes (Chaquopy passes Kotlin ByteArray as jarray)
            dest_hash = bytes(dest_hash)
            dest_hash_hex = dest_hash.hex()
            log_info("ReticulumWrapper", "probe_link_speed",
                     f"Probing link speed to {dest_hash_hex[:16]}... (method: {delivery_method})")

            # Helper to find link in both direct_links and backchannel_links
            def find_link(dest_hash_bytes, dest_hash_hex_str):
                """Find a link by checking direct_links and backchannel_links."""
                # Check direct_links first
                if dest_hash_bytes in self.router.direct_links:
                    return self.router.direct_links[dest_hash_bytes]
                if dest_hash_hex_str in self.router.direct_links:
                    return self.router.direct_links[dest_hash_hex_str]
                # Check backchannel_links (incoming links from peer)
                if dest_hash_bytes in self.router.backchannel_links:
                    return self.router.backchannel_links[dest_hash_bytes]
                if dest_hash_hex_str in self.router.backchannel_links:
                    return self.router.backchannel_links[dest_hash_hex_str]
                return None

            # Helper to get next hop interface bitrate
            def get_next_hop_bitrate() -> int:
                try:
                    if RNS.Transport.has_path(dest_hash):
                        next_hop_iface = RNS.Transport.next_hop_interface(dest_hash)
                        if next_hop_iface and hasattr(next_hop_iface, 'bitrate'):
                            return next_hop_iface.bitrate
                except Exception:
                    pass
                return None

            # Helper to get link stats
            def get_link_stats(link, reused: bool, method: str) -> Dict:
                return {
                    "status": "success",
                    "establishment_rate_bps": link.get_establishment_rate(),
                    "expected_rate_bps": link.get_expected_rate(),
                    "rtt_seconds": link.rtt,
                    "hops": RNS.Transport.hops_to(dest_hash) if RNS.Transport.has_path(dest_hash) else None,
                    "link_reused": reused,
                    "delivery_method": method,
                    "next_hop_bitrate_bps": get_next_hop_bitrate(),
                    "link_mtu": link.get_mtu() if hasattr(link, 'get_mtu') else None
                }

            # 1. If propagated delivery, check propagation link AND backchannel link
            if delivery_method == "propagated":
                # Check if there's a backchannel link with expected_rate from the recipient
                # This tells us actual measured throughput from prior transfers with this peer
                backchannel_expected_rate = None
                backchannel_link = find_link(dest_hash, dest_hash_hex)
                if backchannel_link is not None and backchannel_link.status == RNS.Link.ACTIVE:
                    backchannel_expected_rate = backchannel_link.get_expected_rate()
                    if backchannel_expected_rate:
                        log_info("ReticulumWrapper", "probe_link_speed",
                                 f"Found backchannel expected_rate: {backchannel_expected_rate} bps")

                if self.router.outbound_propagation_link is not None:
                    link = self.router.outbound_propagation_link
                    if link.status == RNS.Link.ACTIVE:
                        log_info("ReticulumWrapper", "probe_link_speed",
                                 f"Using existing propagation link")
                        stats = get_link_stats(link, True, "propagated")
                        # Use backchannel expected_rate if available (more relevant for this peer)
                        if backchannel_expected_rate:
                            stats["expected_rate_bps"] = backchannel_expected_rate
                        return stats

                # No active propagation link - return heuristics only
                # Use status="success" since heuristic data is valid for compression recommendations
                log_info("ReticulumWrapper", "probe_link_speed",
                         f"No active propagation link, returning heuristics")
                return {
                    "status": "success",
                    "establishment_rate_bps": None,
                    "expected_rate_bps": backchannel_expected_rate,  # Include if available
                    "rtt_seconds": None,
                    "hops": None,
                    "link_reused": False,
                    "delivery_method": "propagated",
                    "next_hop_bitrate_bps": get_next_hop_bitrate(),
                    "link_mtu": None
                }

            # 2. Check for existing active link (direct or backchannel)
            link = find_link(dest_hash, dest_hash_hex)
            if link is not None and link.status == RNS.Link.ACTIVE:
                log_info("ReticulumWrapper", "probe_link_speed",
                         f"Reusing existing link to {dest_hash_hex[:16]}")
                return get_link_stats(link, True, "direct")

            # 3. No existing link - use establish_link() to create one
            log_info("ReticulumWrapper", "probe_link_speed",
                     f"No existing link, establishing link to {dest_hash_hex[:16]}...")

            result = self.establish_link(dest_hash, timeout_seconds)

            if result.get("success"):
                # Link established - get stats from the link
                link = find_link(dest_hash, dest_hash_hex)
                if link is not None and link.status == RNS.Link.ACTIVE:
                    return get_link_stats(link, result.get("already_existed", False), "direct")
                else:
                    # Link reported success but we can't find it - return what we have
                    return {
                        "status": "success",
                        "establishment_rate_bps": result.get("establishment_rate_bps"),
                        "expected_rate_bps": None,
                        "rtt_seconds": None,
                        "hops": RNS.Transport.hops_to(dest_hash) if RNS.Transport.has_path(dest_hash) else None,
                        "link_reused": result.get("already_existed", False),
                        "delivery_method": "direct",
                        "next_hop_bitrate_bps": get_next_hop_bitrate(),
                        "link_mtu": None
                    }
            else:
                # Link establishment failed - check if we have heuristic data
                hops = RNS.Transport.hops_to(dest_hash) if RNS.Transport.has_path(dest_hash) else None
                next_hop_bps = get_next_hop_bitrate()

                # If we have useful heuristic data (hop count or next hop bitrate),
                # return "success" so the UI can show recommendations based on that.
                # Only return failure status when we have no useful info.
                if hops is not None or next_hop_bps is not None:
                    log_info("ReticulumWrapper", "probe_link_speed",
                             f"Link failed but have heuristics: hops={hops}, next_hop_bps={next_hop_bps}")
                    return {
                        "status": "success",
                        "establishment_rate_bps": None,
                        "expected_rate_bps": None,
                        "rtt_seconds": None,
                        "hops": hops,
                        "link_reused": False,
                        "delivery_method": "direct",
                        "next_hop_bitrate_bps": next_hop_bps,
                        "link_mtu": None
                    }

                # No heuristic data - return actual failure status
                error = result.get("error", "unknown")
                if "Identity not known" in error:
                    status = "no_identity"
                elif "No path" in error:
                    status = "no_path"
                elif "Timeout" in error:
                    status = "timeout"
                else:
                    status = "failed"

                return {
                    "status": status,
                    "establishment_rate_bps": None,
                    "expected_rate_bps": None,
                    "rtt_seconds": None,
                    "hops": None,
                    "link_reused": False,
                    "delivery_method": "direct",
                    "next_hop_bitrate_bps": None,
                    "link_mtu": None
                }

        except Exception as e:
            log_error("ReticulumWrapper", "probe_link_speed", f"Error: {e}")
            import traceback
            traceback.print_exc()
            return {
                "status": "error",
                "error": str(e),
                "establishment_rate_bps": None,
                "expected_rate_bps": None,
                "rtt_seconds": None,
                "hops": None,
                "link_reused": False,
                "delivery_method": delivery_method,
                "next_hop_bitrate_bps": None,
                "link_mtu": None
            }

    def establish_link(self, dest_hash: bytes, timeout_seconds: float = 10.0) -> Dict:
        """
        Establish a link to a destination for real-time connectivity.
        
        This is used to:
        1. Show "Online" status when link is active
        2. Enable instant link speed probing for transfer time estimates
        
        Args:
            dest_hash: Destination hash as bytes (16 bytes)
            timeout_seconds: How long to wait for link establishment
            
        Returns:
            Dict with:
            - success: True if link is now active
            - link_active: True if link is active
            - establishment_rate_bps: Link speed in bits/sec (if active)
            - error: Error message (if failed)
        """
        if not RETICULUM_AVAILABLE or not self.router:
            return {"success": False, "link_active": False, "error": "Not initialized"}
        
        try:
            dest_hash = bytes(dest_hash)
            dest_hash_hex = dest_hash.hex()
            log_info("ReticulumWrapper", "establish_link", 
                     f"Establishing link to {dest_hash_hex[:16]}...")
            
            # Check if link already exists (in direct_links OR backchannel_links)
            # Links are bidirectional, so either direction counts
            link = None
            if dest_hash in self.router.direct_links:
                link = self.router.direct_links[dest_hash]
            elif dest_hash_hex in self.router.direct_links:
                link = self.router.direct_links[dest_hash_hex]
            # Also check backchannel_links (incoming links from peer)
            if link is None and dest_hash in self.router.backchannel_links:
                link = self.router.backchannel_links[dest_hash]
            elif link is None and dest_hash_hex in self.router.backchannel_links:
                link = self.router.backchannel_links[dest_hash_hex]

            # Fallback: Check Transport.active_links for incoming links not yet in backchannel_links
            if link is None and hasattr(RNS.Transport, 'active_links'):
                for active_link in RNS.Transport.active_links:
                    if active_link.status == RNS.Link.ACTIVE:
                        try:
                            remote_identity = active_link.get_remote_identity()
                            if remote_identity:
                                remote_dest = RNS.Destination(
                                    remote_identity,
                                    RNS.Destination.OUT,
                                    RNS.Destination.SINGLE,
                                    "lxmf",
                                    "delivery"
                                )
                                if remote_dest.hash == dest_hash or remote_dest.hash.hex() == dest_hash_hex:
                                    log_info("ReticulumWrapper", "establish_link",
                                             f"Found existing incoming link via Transport.active_links")
                                    link = active_link
                                    break
                        except Exception:
                            pass

            # Helper to get next hop interface bitrate
            def get_next_hop_bitrate(hash_to_check) -> int:
                try:
                    if RNS.Transport.has_path(hash_to_check):
                        next_hop_iface = RNS.Transport.next_hop_interface(hash_to_check)
                        if next_hop_iface and hasattr(next_hop_iface, 'bitrate'):
                            return next_hop_iface.bitrate
                except Exception:
                    pass
                return None

            # Helper to build full link stats response
            def get_full_link_stats(link, already_existed: bool, hash_for_path) -> Dict:
                return {
                    "success": True,
                    "link_active": True,
                    "establishment_rate_bps": link.get_establishment_rate(),
                    "expected_rate_bps": link.get_expected_rate(),
                    "rtt_seconds": link.rtt,
                    "hops": RNS.Transport.hops_to(hash_for_path) if RNS.Transport.has_path(hash_for_path) else None,
                    "link_mtu": link.mtu if hasattr(link, 'mtu') else None,
                    "next_hop_bitrate_bps": get_next_hop_bitrate(hash_for_path),
                    "already_existed": already_existed
                }

            if link is not None:
                if link.status == RNS.Link.ACTIVE:
                    log_info("ReticulumWrapper", "establish_link",
                             f"Link already active to {dest_hash_hex[:16]} (existing)")
                    return get_full_link_stats(link, True, dest_hash)
                else:
                    # Clean up stale link from both dicts before proceeding
                    self.router.direct_links.pop(dest_hash, None)
                    self.router.direct_links.pop(dest_hash_hex, None)
                    self.router.backchannel_links.pop(dest_hash, None)
                    self.router.backchannel_links.pop(dest_hash_hex, None)
                    link = None
            
            # No existing link - try to establish one
            # The recipient's LXMF router will accept incoming links to lxmf.delivery
            
            # DEBUG: Log target hash
            log_debug("ReticulumWrapper", "establish_link",
                     f"Target dest_hash: {dest_hash_hex}")
            
            # Try to recall identity using multiple methods (matching send_lxmf_message pattern)
            recipient_identity = None
            try:
                # Try as destination hash first (this is what LXMF uses)
                recipient_identity = RNS.Identity.recall(dest_hash)
                if recipient_identity:
                    log_debug("ReticulumWrapper", "establish_link",
                             "Recalled identity from destination hash")
                else:
                    # Try with from_identity_hash=True
                    recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                    if recipient_identity:
                        log_debug("ReticulumWrapper", "establish_link",
                                 "Recalled identity from identity hash")
            except Exception as e:
                log_debug("ReticulumWrapper", "establish_link",
                         f"Error recalling identity from Reticulum: {e}")

            # Fallback to local cache
            if not recipient_identity and dest_hash_hex in self.identities:
                recipient_identity = self.identities[dest_hash_hex]
                log_debug("ReticulumWrapper", "establish_link",
                         "Retrieved identity from local cache")

            if not recipient_identity:
                log_warning("ReticulumWrapper", "establish_link",
                           f"Cannot establish link - identity not known for {dest_hash_hex[:16]}")
                return {"success": False, "link_active": False, "error": "Identity not known"}
            
            # DEBUG: Log recalled identity hash
            log_debug("ReticulumWrapper", "establish_link",
                     f"Recalled identity hash: {recipient_identity.hash.hex()}")
            
            # Create destination for link (same as LXMF uses for DIRECT delivery)
            recipient_dest = RNS.Destination(
                recipient_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery"
            )
            
            # DEBUG: Log created destination hash and compare
            created_hash = recipient_dest.hash.hex()
            hashes_match = recipient_dest.hash == dest_hash
            log_debug("ReticulumWrapper", "establish_link",
                     f"Created dest.hash: {created_hash}")
            log_debug("ReticulumWrapper", "establish_link",
                     f"Hashes match: {hashes_match}")

            # Always check for existing link using created destination hash
            # (links are stored under recipient_dest.hash, which may differ from input dest_hash)
            link = None
            if recipient_dest.hash in self.router.direct_links:
                link = self.router.direct_links[recipient_dest.hash]
            elif recipient_dest.hash in self.router.backchannel_links:
                link = self.router.backchannel_links[recipient_dest.hash]
            if link is not None:
                if link.status == RNS.Link.ACTIVE:
                    log_info("ReticulumWrapper", "establish_link",
                            f"Link already active (found via created hash) to {created_hash[:16]}")
                    return get_full_link_stats(link, True, recipient_dest.hash)
                else:
                    # Clean up stale link before proceeding
                    self.router.direct_links.pop(recipient_dest.hash, None)
                    self.router.backchannel_links.pop(recipient_dest.hash, None)
                    link = None

            # Check if path exists to the created destination
            # Use recipient_dest.hash since that's where we'll create the link
            has_path = RNS.Transport.has_path(recipient_dest.hash)
            log_debug("ReticulumWrapper", "establish_link",
                     f"Transport.has_path({recipient_dest.hash.hex()[:16]}): {has_path}")

            # Only request path if we don't have one (avoid unnecessary network traffic)
            # If the existing path is stale, link establishment will fail and we can retry
            if not has_path:
                log_debug("ReticulumWrapper", "establish_link",
                         f"Requesting path to {recipient_dest.hash.hex()[:16]}...")
                RNS.Transport.request_path(recipient_dest.hash)

            # Wait for path if we don't have one
            if not has_path:
                for _ in range(10):
                    time.sleep(0.5)
                    if RNS.Transport.has_path(recipient_dest.hash):
                        log_debug("ReticulumWrapper", "establish_link", "Path discovered")
                        has_path = True
                        break
                if not has_path:
                    log_warning("ReticulumWrapper", "establish_link",
                               f"No path available to {recipient_dest.hash.hex()[:16]}")
                    return {"success": False, "link_active": False, "error": "No path available"}
            
            if not hashes_match:
                log_warning("ReticulumWrapper", "establish_link",
                           f"HASH MISMATCH! Target={dest_hash_hex[:16]}, Created={created_hash[:16]}")
            
            # Establish link using RNS.Link - matching LXMF's exact approach
            log_info("ReticulumWrapper", "establish_link",
                     f"Establishing new link to {dest_hash_hex[:16]}...")
            
            # Create a flag to track establishment
            link_established = [False]
            link_rate = [None]
            
            def on_link_established(link):
                log_info("ReticulumWrapper", "establish_link", 
                         f"Link callback: link to {dest_hash_hex[:16]} established!")
                link_established[0] = True
                link_rate[0] = link.get_establishment_rate()
            
            # Create link with callback (like LXMF does)
            link = RNS.Link(recipient_dest, established_callback=on_link_established)
            
            # Store in router.direct_links using created destination hash
            # (LXMF uses lxmessage.get_destination().hash which is the created destination's hash)
            self.router.direct_links[recipient_dest.hash] = link
            log_debug("ReticulumWrapper", "establish_link",
                     f"Stored link in router.direct_links with key {recipient_dest.hash.hex()[:16]}")

            # Wait for link establishment with timeout
            # Use try/finally to ensure cleanup on all exit paths
            try:
                start_time = time.time()
                while time.time() - start_time < timeout_seconds:
                    if link_established[0] or link.status == RNS.Link.ACTIVE:
                        log_info("ReticulumWrapper", "establish_link",
                                 f"Link established to {dest_hash_hex[:16]} in {time.time() - start_time:.2f}s")

                        # Identify ourselves on the link so the remote peer's LXMRouter
                        # adds this to their backchannel_links (via delivery_remote_identified callback)
                        try:
                            if self.router.identity:
                                link.identify(self.router.identity)
                                log_debug("ReticulumWrapper", "establish_link",
                                         f"Identified ourselves on link to enable backchannel")
                        except Exception as e:
                            log_warning("ReticulumWrapper", "establish_link",
                                       f"Failed to identify on link: {e}")

                        return get_full_link_stats(link, False, recipient_dest.hash)
                    elif link.status == RNS.Link.CLOSED:
                        log_warning("ReticulumWrapper", "establish_link",
                                   f"Link closed during establishment to {dest_hash_hex[:16]}")
                        return {"success": False, "link_active": False, "error": "Link closed"}
                    time.sleep(0.1)

                # Timeout
                link.teardown()
                log_info("ReticulumWrapper", "establish_link",
                         f"Link establishment timed out to {dest_hash_hex[:16]} (peer may be offline)")
                return {"success": False, "link_active": False, "error": "Timeout"}
            finally:
                # Clean up failed/inactive links from direct_links
                # Only remove if it's still our link (not replaced by concurrent call)
                if link.status != RNS.Link.ACTIVE:
                    if self.router.direct_links.get(recipient_dest.hash) is link:
                        self.router.direct_links.pop(recipient_dest.hash, None)
            
        except Exception as e:
            # Clean up stored link if variables are in scope
            try:
                if recipient_dest and recipient_dest.hash in self.router.direct_links:
                    try:
                        link.teardown()
                    except:
                        pass
                    self.router.direct_links.pop(recipient_dest.hash, None)
            except NameError:
                pass  # Variables not yet defined
            log_error("ReticulumWrapper", "establish_link", f"Error: {e}")
            return {"success": False, "link_active": False, "error": str(e)}

    def close_link(self, dest_hash: bytes) -> Dict:
        """
        Close an active link to a destination.

        Called when conversation has been inactive for too long.

        Args:
            dest_hash: Destination hash as bytes (16 bytes)

        Returns:
            Dict with:
            - success: True if link was closed or didn't exist
            - was_active: True if link was active before closing
        """
        if not RETICULUM_AVAILABLE or not self.router:
            return {"success": True, "was_active": False}

        try:
            dest_hash = bytes(dest_hash)
            dest_hash_hex = dest_hash.hex()

            # Find link - try input hash first
            link = None
            link_key = None
            if dest_hash in self.router.direct_links:
                link = self.router.direct_links[dest_hash]
                link_key = dest_hash
            elif dest_hash_hex in self.router.direct_links:
                link = self.router.direct_links[dest_hash_hex]
                link_key = dest_hash_hex

            # If not found, try via created destination hash (handles mismatch case)
            if link is None:
                recipient_identity = RNS.Identity.recall(dest_hash)
                if not recipient_identity:
                    recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                if not recipient_identity and dest_hash_hex in self.identities:
                    recipient_identity = self.identities[dest_hash_hex]

                if recipient_identity:
                    recipient_dest = RNS.Destination(
                        recipient_identity,
                        RNS.Destination.OUT,
                        RNS.Destination.SINGLE,
                        "lxmf",
                        "delivery"
                    )
                    if recipient_dest.hash in self.router.direct_links:
                        link = self.router.direct_links[recipient_dest.hash]
                        link_key = recipient_dest.hash

            if link is None:
                log_debug("ReticulumWrapper", "close_link",
                         f"No link found to {dest_hash_hex[:16]}")
                return {"success": True, "was_active": False}

            was_active = link.status == RNS.Link.ACTIVE

            if was_active:
                log_info("ReticulumWrapper", "close_link",
                        f"Closing link to {dest_hash_hex[:16]}")
                link.teardown()

            # Remove from direct_links
            if link_key and link_key in self.router.direct_links:
                self.router.direct_links.pop(link_key)

            return {"success": True, "was_active": was_active}

        except Exception as e:
            log_error("ReticulumWrapper", "close_link", f"Error: {e}")
            return {"success": False, "was_active": False, "error": str(e)}

    def get_link_status(self, dest_hash: bytes) -> Dict:
        """
        Check if a link is active to a destination.

        Checks both direct_links (outgoing) and backchannel_links (incoming).
        A link is bidirectional, so either direction counts as "active".

        Args:
            dest_hash: Destination hash as bytes (16 bytes)

        Returns:
            Dict with:
            - active: True if link is currently active
            - establishment_rate_bps: Link speed in bits/sec (if active)
        """
        if not RETICULUM_AVAILABLE or not self.router:
            return {"active": False}

        try:
            dest_hash = bytes(dest_hash)
            dest_hash_hex = dest_hash.hex()

            # Helper to find link in a dictionary
            def find_link_in_dict(links_dict, hash_bytes, hash_hex):
                if hash_bytes in links_dict:
                    return links_dict[hash_bytes]
                if hash_hex in links_dict:
                    return links_dict[hash_hex]
                return None

            # Check direct_links (links we initiated)
            link = find_link_in_dict(self.router.direct_links, dest_hash, dest_hash_hex)

            # Check backchannel_links (links peer initiated to us)
            if link is None:
                link = find_link_in_dict(self.router.backchannel_links, dest_hash, dest_hash_hex)

            # Fallback: Check RNS.Transport.active_links for incoming links
            # This is rarely needed since link.identify() populates backchannel_links
            if link is None and hasattr(RNS.Transport, 'active_links'):
                for active_link in RNS.Transport.active_links:
                    if active_link.status == RNS.Link.ACTIVE:
                        try:
                            remote_identity = active_link.get_remote_identity()
                            if remote_identity:
                                remote_dest = RNS.Destination(
                                    remote_identity,
                                    RNS.Destination.OUT,
                                    RNS.Destination.SINGLE,
                                    "lxmf",
                                    "delivery"
                                )
                                if remote_dest.hash == dest_hash or remote_dest.hash.hex() == dest_hash_hex:
                                    link = active_link
                                    break
                        except Exception:
                            pass

            # If not found, try via created destination hash (handles mismatch case)
            if link is None:
                recipient_identity = RNS.Identity.recall(dest_hash)
                if not recipient_identity:
                    recipient_identity = RNS.Identity.recall(dest_hash, from_identity_hash=True)
                if not recipient_identity and dest_hash_hex in self.identities:
                    recipient_identity = self.identities[dest_hash_hex]

                if recipient_identity:
                    recipient_dest = RNS.Destination(
                        recipient_identity,
                        RNS.Destination.OUT,
                        RNS.Destination.SINGLE,
                        "lxmf",
                        "delivery"
                    )
                    created_hash = recipient_dest.hash
                    created_hex = created_hash.hex()
                    # Check both dictionaries with created hash
                    link = find_link_in_dict(self.router.direct_links, created_hash, created_hex)
                    if link is None:
                        link = find_link_in_dict(self.router.backchannel_links, created_hash, created_hex)

            if link is not None and link.status == RNS.Link.ACTIVE:
                # Helper to get next hop interface bitrate
                def get_next_hop_bitrate(hash_to_check) -> int:
                    try:
                        if RNS.Transport.has_path(hash_to_check):
                            next_hop_iface = RNS.Transport.next_hop_interface(hash_to_check)
                            if next_hop_iface and hasattr(next_hop_iface, 'bitrate'):
                                return next_hop_iface.bitrate
                    except Exception:
                        pass
                    return None

                return {
                    "active": True,
                    "establishment_rate_bps": link.get_establishment_rate(),
                    "expected_rate_bps": link.get_expected_rate(),
                    "rtt_seconds": link.rtt,
                    "hops": RNS.Transport.hops_to(dest_hash) if RNS.Transport.has_path(dest_hash) else None,
                    "link_mtu": link.mtu if hasattr(link, 'mtu') else None,
                    "next_hop_bitrate_bps": get_next_hop_bitrate(dest_hash),
                }

            return {"active": False}

        except Exception as e:
            log_error("ReticulumWrapper", "get_link_status", f"Error: {e}")
            return {"active": False, "error": str(e)}

    def get_debug_info(self) -> Dict:
        """
        Get comprehensive debug information about Reticulum status.

        Returns:
            Dict with debug information including:
            - initialized: bool
            - reticulum_available: bool
            - interfaces: list of interface info
            - transport_status: transport state info
        """
        info = {
            'initialized': self.initialized,
            'reticulum_available': RETICULUM_AVAILABLE,
            'storage_path': self.storage_path,
            'failed_interfaces': self.failed_interfaces,  # Interfaces that failed to initialize
        }

        if RETICULUM_AVAILABLE and self.reticulum:
            try:
                # Get interface information
                interfaces = []
                for iface in RNS.Transport.interfaces:
                    iface_info = {
                        'name': str(iface),
                        'type': type(iface).__name__,
                        'online': hasattr(iface, 'online') and iface.online if hasattr(iface, 'online') else True,
                    }
                    interfaces.append(iface_info)
                info['interfaces'] = interfaces

                # Transport information
                # Check actual transport enabled status from RNS
                info['transport_enabled'] = RNS.Reticulum.transport_enabled()
                info['transport_identity'] = RNS.Transport.identity != None

            except Exception as e:
                info['error'] = f"Error getting debug info: {e}"
                log_error("ReticulumWrapper", "get_debug_info", f"Error in get_debug_info: {e}")
                import traceback
                traceback.print_exc()
        else:
            info['interfaces'] = []
            info['transport_enabled'] = False

        return info

    def get_failed_interfaces(self) -> str:
        """
        Get list of interfaces that failed to initialize.

        Returns:
            JSON string containing list of failed interfaces with error details.
            Each entry has: name, error, and optionally recoverable flag.
        """
        return json.dumps(self.failed_interfaces if hasattr(self, 'failed_interfaces') else [])

    def get_path_table(self) -> List[str]:
        """
        Get list of destination hashes from the RNS path table.
        Returns hex-encoded destination hashes for all known paths.

        Returns:
            List of hex-encoded destination hashes (e.g., ["abc123...", "def456..."])
        """
        if not RETICULUM_AVAILABLE or not self.reticulum:
            return []  # Return empty list in mock mode

        try:
            destination_hashes = []
            # Access RNS.Transport.path_table directly
            # path_table is a dict where keys are destination hashes (bytes)
            for dest_hash in RNS.Transport.path_table:
                # Convert bytes to hex string for Kotlin compatibility
                destination_hashes.append(dest_hash.hex())

            log_debug("ReticulumWrapper", "get_path_table",
                     f"Retrieved {len(destination_hashes)} paths from path table")
            return destination_hashes

        except Exception as e:
            log_error("ReticulumWrapper", "get_path_table",
                     f"Error getting path table: {e}")
            import traceback
            traceback.print_exc()
            return []

    def get_local_identity_info(self) -> Optional[Dict]:
        """
        Get information about the local identity if one has been created.

        Returns:
            Dict with identity info or None if no identity exists
        """
        # For now, we don't have a persistent "local" identity
        # This would be implemented when we add identity management
        return None

    def create_and_announce_test_destination(self, app_name: str = "columba") -> Dict:
        """
        Create a test destination and announce it.
        Useful for debugging to verify announces are working.

        Args:
            app_name: Application name for the destination

        Returns:
            Dict with 'success', 'dest_hash', 'hex_hash' or 'error'
        """
        try:
            if not RETICULUM_AVAILABLE or not self.initialized:
                return {"success": False, "error": "Reticulum not initialized"}

            # Create a test identity
            test_identity = RNS.Identity()

            # Create a destination
            destination = RNS.Destination(
                test_identity,
                RNS.Destination.IN,
                RNS.Destination.SINGLE,
                app_name,
                "debug"
            )

            # Store it (use hex hash as key)
            self.destinations[destination.hexhash] = destination

            # Announce with debug app data
            app_data = b"Columba Debug Test"
            destination.announce(app_data=app_data)

            log_info("ReticulumWrapper", "create_and_announce_test_destination", f"Test destination announced: {destination.hexhash}")

            return {
                "success": True,
                "dest_hash": destination.hash,
                "hex_hash": destination.hexhash,
                "identity_hash": test_identity.hash,
                "app_data": app_data
            }

        except Exception as e:
            log_error("ReticulumWrapper", "create_and_announce_test_destination", f"Error creating test destination: {e}")
            import traceback
            traceback.print_exc()
            return {"success": False, "error": str(e)}

    # ========== Threading Safety Test Methods ==========
    # These methods are used to verify Python/Chaquopy threading safety

    def echo(self, message: str) -> str:
        """
        Simple echo method for testing Python threading safety.
        Returns the input message unchanged.

        Thread-safe: Yes (no shared state access)
        GIL: Automatically serializes Python bytecode execution
        """
        return message

    def simple_method(self, value: int) -> int:
        """
        Simple method for stress testing Python calls.
        Returns the input value unchanged.

        Thread-safe: Yes (no shared state access)
        GIL: Automatically serializes Python bytecode execution
        """
        return value

    def sleep(self, seconds: float) -> None:
        """
        Sleep for specified seconds - used to test long-running operations.
        Tests that long operations don't block other threads from calling Python.

        Thread-safe: Yes (time.sleep releases GIL)
        GIL: Released during sleep, allowing other threads to execute
        """
        time.sleep(seconds)

    # ========== BLE Interface Support Methods ==========
    # These methods enable Android BLE interface integration

    # ========== DEPRECATED BLE Methods (driver-based architecture) ==========
    # These methods are no longer used with the new driver-based architecture
    # but are kept as stubs for backward compatibility during transition.

    def ble_packet_received(self, address: str, data: bytes) -> None:
        """
        DEPRECATED: No longer used with driver-based architecture.

        BLE packets are now handled directly by AndroidBLEDriver callbacks.
        This method is kept for backward compatibility but does nothing.
        """
        log_warning("ReticulumWrapper", "ble_packet_received",
                   "DEPRECATED: ble_packet_received() called but no longer used in driver-based architecture")

    def poll_ble_incoming(self) -> List[Dict]:
        """
        DEPRECATED: No longer used with driver-based architecture.

        BLE interface now uses event-driven callbacks instead of polling.
        This method is kept for backward compatibility but returns empty list.
        """
        log_warning("ReticulumWrapper", "poll_ble_incoming",
                   "DEPRECATED: poll_ble_incoming() called but no longer used in driver-based architecture")
        return []

    def send_via_ble(self, address: str, data: bytes) -> Dict:
        """
        DEPRECATED: No longer used with driver-based architecture.

        AndroidBLEInterface now sends directly via AndroidBLEDriver.
        This method is kept for backward compatibility but returns failure.
        """
        log_warning("ReticulumWrapper", "send_via_ble",
                   "DEPRECATED: send_via_ble() called but no longer used in driver-based architecture")
        return {'success': False, 'error': 'Method deprecated - use driver-based architecture'}

    def set_kotlin_ble_callback(self, callback) -> None:
        """
        DEPRECATED: No longer used with driver-based architecture.

        BLE communication now uses KotlinBLEBridge directly via Chaquopy.
        This method is kept for backward compatibility but does nothing.
        """
        log_warning("ReticulumWrapper", "set_kotlin_ble_callback",
                   "DEPRECATED: set_kotlin_ble_callback() called but no longer used in driver-based architecture")

    def initialize_ble_interface(self) -> Dict:
        """
        Initialize the Android BLE interface with driver-based architecture.

        RNS has already loaded the AndroidBLEInterface from config/interfaces directory.
        This method finds that instance and starts it. The interface will create its
        own AndroidBLEDriver internally to access the Kotlin BLE bridge.

        Returns:
            Dict with 'success' boolean and optional 'error' string
        """
        try:
            if not self.initialized:
                return {'success': False, 'error': 'Reticulum not initialized'}

            # Find the AndroidBLEInterface instance that RNS already created
            # It should be in RNS.Transport.interfaces list
            # Use name-based matching since the class loaded by RNS is from a different module path
            ble_interface = None
            for interface in RNS.Transport.interfaces:
                # Match by class name since isinstance() won't work across module boundaries
                if type(interface).__name__ == 'AndroidBLEInterface':
                    ble_interface = interface
                    log_debug("ReticulumWrapper", "initialize_ble_interface", f"Found AndroidBLEInterface: {interface}")
                    break

            if not ble_interface:
                log_warning("ReticulumWrapper", "initialize_ble_interface", "WARNING: AndroidBLEInterface not found in RNS.Transport.interfaces")
                log_debug("ReticulumWrapper", "initialize_ble_interface", f"Available interfaces: {[type(i).__name__ for i in RNS.Transport.interfaces]}")
                return {'success': False, 'error': 'AndroidBLEInterface not loaded by RNS'}

            # Store reference to BLE interface
            self.ble_interface = ble_interface

            log_info("ReticulumWrapper", "initialize_ble_interface", f"✓ AndroidBLEInterface found and connected")
            log_debug("ReticulumWrapper", "initialize_ble_interface", f"Interface name: {ble_interface.name}")

            # Start the BLE interface with driver-based architecture
            if not ble_interface.online:
                log_info("ReticulumWrapper", "initialize_ble_interface", f"Starting AndroidBLEInterface with driver...")
                ble_interface.start()
                log_info("ReticulumWrapper", "initialize_ble_interface", f"✅ AndroidBLEInterface started, online={ble_interface.online}")
            else:
                log_warning("ReticulumWrapper", "initialize_ble_interface", f"AndroidBLEInterface already online, skipping start()")

            return {'success': True}

        except Exception as e:
            log_error("ReticulumWrapper", "initialize_ble_interface", f"ERROR initializing BLE interface bridge: {e}")
            import traceback
            traceback.print_exc()
            return {'success': False, 'error': str(e)}

    def initialize_rnode_interface(self) -> Dict:
        """
        Initialize the RNode interface with Kotlin bridge architecture.

        Unlike BLE interface which is loaded by RNS from config, RNode interface
        is created directly here using ColumbaRNodeInterface. This is because
        the standard RNodeInterface uses jnius which is incompatible with Chaquopy.

        The RNode config was stored in _pending_rnode_config during config creation.

        Returns:
            Dict with 'success' boolean and optional 'error' string
        """
        # Prevent concurrent initialization (race condition fix)
        # Acquire lock before checking/setting initialization flag to prevent race condition
        # (Double-check locking without proper memory barriers is broken in Python)
        with self._rnode_init_lock:
            if self._rnode_initializing:
                log_info("ReticulumWrapper", "initialize_rnode_interface",
                        "RNode initialization already in progress, skipping duplicate call")
                return {'success': True, 'message': 'Initialization already in progress'}
            self._rnode_initializing = True

        try:
            if not self.initialized:
                return {'success': False, 'error': 'Reticulum not initialized'}

            # Check if we already have an RNode interface that just needs reconnecting
            if self.rnode_interface is not None:
                if not self.rnode_interface.online:
                    log_info("ReticulumWrapper", "initialize_rnode_interface",
                            "Reconnecting existing offline RNode interface...")
                    if self.rnode_interface.start():
                        log_info("ReticulumWrapper", "initialize_rnode_interface",
                                f"✅ RNode interface reconnected, online={self.rnode_interface.online}")
                        return {'success': True, 'message': 'RNode interface reconnected'}
                    else:
                        return {'success': False, 'error': 'Failed to reconnect RNode interface'}
                else:
                    log_info("ReticulumWrapper", "initialize_rnode_interface",
                            "RNode interface already online, skipping")
                    return {'success': True, 'message': 'RNode interface already online'}

            # Check if we have pending RNode config (for initial creation)
            if not hasattr(self, '_pending_rnode_config') or self._pending_rnode_config is None:
                log_info("ReticulumWrapper", "initialize_rnode_interface", "No RNode config pending, skipping")
                return {'success': True, 'message': 'No RNode interface configured'}

            # Check if Kotlin bridge is available
            if self.kotlin_rnode_bridge is None:
                return {'success': False, 'error': 'KotlinRNodeBridge not set. Call set_rnode_bridge() first.'}

            log_info("ReticulumWrapper", "initialize_rnode_interface",
                    f"Creating ColumbaRNodeInterface for {self._pending_rnode_config['target_device_name']}")

            # Import ColumbaRNodeInterface
            from rnode_interface import ColumbaRNodeInterface

            # Create the RNode interface
            # Note: ColumbaRNodeInterface gets kotlin_rnode_bridge from owner (self) via _get_kotlin_bridge()
            self.rnode_interface = ColumbaRNodeInterface(
                owner=self,
                name=self._pending_rnode_config['name'],
                config=self._pending_rnode_config
            )

            # Set up error callback to surface RNode errors to Kotlin/UI
            def on_rnode_error(error_code, error_message):
                log_error("ReticulumWrapper", "RNodeError", f"RNode error ({error_code}): {error_message}")
                if self.kotlin_rnode_bridge:
                    try:
                        self.kotlin_rnode_bridge.notifyError(error_code, error_message)
                    except Exception as e:
                        log_error("ReticulumWrapper", "RNodeError", f"Failed to notify Kotlin: {e}")

            self.rnode_interface.setOnErrorReceived(on_rnode_error)

            # Set up online status callback to notify Kotlin when interface comes online
            def on_online_status_change(is_online):
                log_info("ReticulumWrapper", "RNodeStatus",
                        f"████ RNODE ONLINE STATUS CHANGED ████ online={is_online}")
                if self.kotlin_rnode_bridge:
                    try:
                        self.kotlin_rnode_bridge.notifyOnlineStatusChanged(is_online)
                    except Exception as e:
                        log_error("ReticulumWrapper", "RNodeStatus",
                                f"Failed to notify Kotlin of online status: {e}")

            if hasattr(self.rnode_interface, 'setOnOnlineStatusChanged'):
                self.rnode_interface.setOnOnlineStatusChanged(on_online_status_change)
                log_debug("ReticulumWrapper", "initialize_rnode_interface",
                        "Set online status callback")

            # Start the interface FIRST before registering with Transport
            # This ensures we catch any fatal initialization errors before committing
            log_info("ReticulumWrapper", "initialize_rnode_interface", "Starting ColumbaRNodeInterface...")
            start_success = self.rnode_interface.start()

            # Register with RNS Transport after starting
            # The interface is registered even if start() returns False because:
            # 1. The interface has auto-reconnect capability
            # 2. start() failure may be transient (Bluetooth not ready, device not in range)
            # 3. RNS Transport checks online status before sending
            RNS.Transport.interfaces.append(self.rnode_interface)
            log_info("ReticulumWrapper", "initialize_rnode_interface",
                    f"Registered ColumbaRNodeInterface with RNS Transport (start_success={start_success})")

            if start_success:
                log_info("ReticulumWrapper", "initialize_rnode_interface",
                        f"✅ ColumbaRNodeInterface started successfully, online={self.rnode_interface.online}")
            else:
                # Interface failed to start initially, but it has auto-reconnect capability
                # Don't return failure - the interface is registered and will auto-reconnect
                log_warning("ReticulumWrapper", "initialize_rnode_interface",
                        "Initial RNode connection failed, but interface registered with auto-reconnect enabled")

            # Clear the pending config
            self._pending_rnode_config = None

            return {'success': True}

        except Exception as e:
            log_error("ReticulumWrapper", "initialize_rnode_interface", f"ERROR initializing RNode interface: {e}")
            import traceback
            traceback.print_exc()
            return {'success': False, 'error': str(e)}

        finally:
            self._rnode_initializing = False

    # ========== Identity Management Methods ==========

    def _resolve_identity_file_path(self, identity_hash: str) -> Optional[str]:
        """
        Resolve an identity hash to its actual file path.

        Handles both legacy 'default_identity' files and new 'identity_{hash}' files.

        Args:
            identity_hash: 32-char hex hash of the identity

        Returns:
            Absolute file path if found, None otherwise
        """
        # First try the new format: identity_{hash}
        new_format_path = os.path.join(self.storage_path, f"identity_{identity_hash}")
        if os.path.exists(new_format_path):
            return new_format_path

        # Check if it's the default_identity file
        default_identity_path = os.path.join(self.storage_path, "default_identity")
        log_debug("ReticulumWrapper", "_resolve_identity_file_path", f"Checking default_identity at {default_identity_path}")
        if os.path.exists(default_identity_path):
            try:
                identity = RNS.Identity.from_file(default_identity_path)
                file_hash = identity.hash.hex()
                log_debug("ReticulumWrapper", "_resolve_identity_file_path", f"default_identity hash: {file_hash[:16]}, looking for: {identity_hash[:16]}")
                if file_hash == identity_hash:
                    log_debug("ReticulumWrapper", "_resolve_identity_file_path", f"Match found: {default_identity_path}")
                    return default_identity_path
            except Exception as e:
                log_error("ReticulumWrapper", "_resolve_identity_file_path", f"Failed to load default_identity: {e}")

        log_debug("ReticulumWrapper", "_resolve_identity_file_path", f"No file found for hash {identity_hash[:16]}")
        return None

    def create_identity(self, display_name: str) -> Dict:
        """
        Create a new Reticulum identity and save it to a file.

        Args:
            display_name: User-friendly name for the identity (not stored in file, used by caller)

        Returns:
            Dict with:
            - identity_hash: 32-char hex hash of the identity
            - destination_hash: LXMF destination hash
            - file_path: Path to the saved identity file
            - key_data: Raw 64-byte private key data for backup
            - display_name: Echo of the provided display name
        """
        try:
            log_info("ReticulumWrapper", "create_identity", f"Creating new identity for '{display_name}'")

            # Create new identity
            identity = RNS.Identity()
            identity_hash = identity.hash.hex()

            # Save to file with identity hash in filename
            file_path = os.path.join(self.storage_path, f"identity_{identity_hash}")
            identity.to_file(file_path)

            log_info("ReticulumWrapper", "create_identity", f"Identity saved: {identity_hash[:16]}... -> {file_path}")

            # Read the key data from the file for backup purposes
            with open(file_path, 'rb') as f:
                key_data = f.read()

            # Create LXMF destination to get destination hash
            # Create an RNS.Destination with LXMF aspects
            temp_destination = RNS.Destination(
                identity,
                RNS.Destination.IN,
                RNS.Destination.SINGLE,
                "lxmf", "delivery"
            )
            destination_hash = temp_destination.hash.hex()

            log_info("ReticulumWrapper", "create_identity", f"LXMF destination hash: {destination_hash}")

            return {
                'identity_hash': identity_hash,
                'destination_hash': destination_hash,
                'file_path': file_path,
                'key_data': key_data,
                'display_name': display_name
            }

        except Exception as e:
            log_error("ReticulumWrapper", "create_identity", f"Failed to create identity: {e}")
            import traceback
            traceback.print_exc()
            return {'error': str(e)}

    def list_identity_files(self) -> List[Dict]:
        """
        Scan storage directory for identity files.

        Returns:
            List of dicts, each containing:
            - identity_hash: 32-char hex hash
            - file_path: Absolute path to identity file
        """
        try:
            log_info("ReticulumWrapper", "list_identity_files", f"Scanning for identity files in {self.storage_path}")

            identities = []

            # Check for old default_identity file
            default_identity_path = os.path.join(self.storage_path, "default_identity")
            if os.path.exists(default_identity_path):
                try:
                    identity = RNS.Identity.from_file(default_identity_path)
                    identities.append({
                        'identity_hash': identity.hash.hex(),
                        'file_path': default_identity_path
                    })
                    log_debug("ReticulumWrapper", "list_identity_files", f"Found default_identity: {identity.hash.hex()[:16]}...")
                except Exception as e:
                    log_warning("ReticulumWrapper", "list_identity_files", f"Could not load default_identity: {e}")

            # Scan for identity_* files
            for filename in os.listdir(self.storage_path):
                if filename.startswith('identity_'):
                    file_path = os.path.join(self.storage_path, filename)
                    try:
                        identity = RNS.Identity.from_file(file_path)
                        identities.append({
                            'identity_hash': identity.hash.hex(),
                            'file_path': file_path
                        })
                        log_debug("ReticulumWrapper", "list_identity_files", f"Found {filename}: {identity.hash.hex()[:16]}...")
                    except Exception as e:
                        log_warning("ReticulumWrapper", "list_identity_files", f"Could not load {filename}: {e}")

            log_info("ReticulumWrapper", "list_identity_files", f"Found {len(identities)} identity file(s)")
            return identities

        except Exception as e:
            log_error("ReticulumWrapper", "list_identity_files", f"Failed to list identity files: {e}")
            import traceback
            traceback.print_exc()
            return []

    def delete_identity_file(self, identity_hash: str) -> Dict:
        """
        Remove an identity file from storage.

        Args:
            identity_hash: 32-char hex hash of the identity to delete

        Returns:
            Dict with 'success' boolean and optional 'error' string
        """
        try:
            log_info("ReticulumWrapper", "delete_identity_file", f"Deleting identity {identity_hash[:16]}...")

            file_path = self._resolve_identity_file_path(identity_hash)

            if file_path:
                # Securely wipe file before deleting (overwrite with random data)
                try:
                    file_size = os.path.getsize(file_path)
                    with open(file_path, 'wb') as f:
                        f.write(os.urandom(file_size))
                        f.flush()
                        os.fsync(f.fileno())
                except Exception as e:
                    log_warning("ReticulumWrapper", "delete_identity_file", f"Could not securely wipe file: {e}")

                # Delete the file
                os.remove(file_path)
                log_info("ReticulumWrapper", "delete_identity_file", f"Identity file deleted: {file_path}")
                return {'success': True}
            else:
                log_warning("ReticulumWrapper", "delete_identity_file", f"Identity file not found for hash: {identity_hash[:16]}...")
                return {'success': False, 'error': 'File not found'}

        except Exception as e:
            log_error("ReticulumWrapper", "delete_identity_file", f"Failed to delete identity file: {e}")
            import traceback
            traceback.print_exc()
            return {'success': False, 'error': str(e)}

    def import_identity_file(self, file_data: bytes, display_name: str) -> Dict:
        """
        Import an identity from raw file data.

        Args:
            file_data: Raw bytes of the identity file
            display_name: User-friendly name for the identity

        Returns:
            Dict with:
            - identity_hash: 32-char hex hash
            - destination_hash: LXMF destination hash
            - file_path: Path where identity was saved
            - display_name: Echo of provided display name
        """
        try:
            log_info("ReticulumWrapper", "import_identity_file", f"Importing identity for '{display_name}'")

            # Write to temporary file
            temp_path = os.path.join(self.storage_path, "temp_identity_import")
            with open(temp_path, 'wb') as f:
                f.write(file_data)

            # Load identity from temp file to validate and get hash
            try:
                identity = RNS.Identity.from_file(temp_path)
                identity_hash = identity.hash.hex()

                log_info("ReticulumWrapper", "import_identity_file", f"Loaded identity: {identity_hash[:16]}...")

                # Move to final location with proper filename
                final_path = os.path.join(self.storage_path, f"identity_{identity_hash}")

                # Check if identity already exists
                if os.path.exists(final_path):
                    os.remove(temp_path)
                    log_warning("ReticulumWrapper", "import_identity_file", f"Identity already exists: {identity_hash[:16]}...")
                    return {'error': f'Identity already exists: {identity_hash}'}

                os.rename(temp_path, final_path)
                log_info("ReticulumWrapper", "import_identity_file", f"Identity imported: {final_path}")

                # Get LXMF destination hash
                temp_destination = RNS.Destination(
                    identity,
                    RNS.Destination.IN,
                    RNS.Destination.SINGLE,
                    "lxmf", "delivery"
                )
                destination_hash = temp_destination.hash.hex()

                return {
                    'identity_hash': identity_hash,
                    'destination_hash': destination_hash,
                    'file_path': final_path,
                    'key_data': file_data,  # Original file bytes for backup
                    'display_name': display_name
                }

            except Exception as e:
                # Clean up temp file on error
                if os.path.exists(temp_path):
                    os.remove(temp_path)
                raise Exception(f"Invalid identity file: {e}")

        except Exception as e:
            log_error("ReticulumWrapper", "import_identity_file", f"Failed to import identity: {e}")
            import traceback
            traceback.print_exc()
            return {'error': str(e)}

    def export_identity_file(self, identity_hash: str, file_path: str = None) -> bytes:
        """
        Read an identity file and return its raw bytes for export.

        Args:
            identity_hash: 32-char hex hash of the identity to export
            file_path: Optional direct path to the identity file (preferred if available)

        Returns:
            bytes: Raw identity file data, or empty bytes on error
        """
        try:
            log_info("ReticulumWrapper", "export_identity_file", f"Exporting identity {identity_hash[:16]}...")

            # Use provided file_path if available, otherwise try to resolve
            if not file_path:
                file_path = self._resolve_identity_file_path(identity_hash)

            if not file_path or not os.path.exists(file_path):
                log_error("ReticulumWrapper", "export_identity_file", f"Identity file not found for hash: {identity_hash[:16]}...")
                return bytes()

            with open(file_path, 'rb') as f:
                file_data = f.read()

            log_info("ReticulumWrapper", "export_identity_file", f"Exported {len(file_data)} bytes")
            return file_data

        except Exception as e:
            log_error("ReticulumWrapper", "export_identity_file", f"Failed to export identity: {e}")
            import traceback
            traceback.print_exc()
            return bytes()

    def recover_identity_file(self, identity_hash: str, key_data: bytes, file_path: str) -> Dict:
        """
        Recover an identity file from backup key data stored in the database.
        Used when the identity file is missing but key_data was backed up.

        Args:
            identity_hash: Expected 32-char hex hash of the identity
            key_data: Raw 64-byte identity key data from database backup
            file_path: Path where identity file should be restored

        Returns:
            Dict with:
            - success: True if recovery succeeded
            - file_path: Path where identity was restored
            - error: Error message if recovery failed
        """
        try:
            log_info("ReticulumWrapper", "recover_identity_file",
                     f"Recovering identity {identity_hash[:16]}... to {file_path}")

            if not key_data or len(key_data) != 64:
                return {'success': False, 'error': f'Invalid key_data: expected 64 bytes, got {len(key_data) if key_data else 0}'}

            # Write to temporary file first to validate
            temp_path = os.path.join(self.storage_path, "temp_identity_recovery")
            with open(temp_path, 'wb') as f:
                f.write(key_data)

            # Validate by loading it
            try:
                identity = RNS.Identity.from_file(temp_path)
                recovered_hash = identity.hash.hex()

                if recovered_hash != identity_hash:
                    os.remove(temp_path)
                    log_error("ReticulumWrapper", "recover_identity_file",
                             f"Hash mismatch: expected {identity_hash[:16]}, got {recovered_hash[:16]}")
                    return {'success': False, 'error': f'Hash mismatch: expected {identity_hash}, got {recovered_hash}'}

                # Ensure parent directory exists
                parent_dir = os.path.dirname(file_path)
                if parent_dir and not os.path.exists(parent_dir):
                    os.makedirs(parent_dir, exist_ok=True)

                # Move to final location
                os.rename(temp_path, file_path)
                log_info("ReticulumWrapper", "recover_identity_file",
                         f"Identity recovered successfully: {file_path}")

                return {'success': True, 'file_path': file_path}

            except Exception as e:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
                raise Exception(f"Invalid key_data: {e}")

        except Exception as e:
            log_error("ReticulumWrapper", "recover_identity_file", f"Failed to recover identity: {e}")
            import traceback
            traceback.print_exc()
            return {'success': False, 'error': str(e)}

    # ============================================================================
    # RMSP (Reticulum Map Service Protocol) Methods
    # ============================================================================

    def _get_rmsp_client(self):
        """
        Get or create the RMSP client instance.
        Lazy initialization to avoid importing until needed.
        """
        if self._rmsp_client is None:
            try:
                from rmsp_client import get_rmsp_client
                self._rmsp_client = get_rmsp_client()
                self._rmsp_client.initialize()
                log_info("ReticulumWrapper", "_get_rmsp_client", "RMSP client initialized")
            except ImportError as e:
                log_error("ReticulumWrapper", "_get_rmsp_client", f"Failed to import rmsp_client: {e}")
                return None
            except Exception as e:
                log_error("ReticulumWrapper", "_get_rmsp_client", f"Failed to initialize RMSP client: {e}")
                return None
        return self._rmsp_client

    def parse_rmsp_announce(self, destination_hash: bytes, identity, app_data: bytes, hops: int = 0) -> Dict:
        """
        Parse RMSP announce data and register the server.

        Args:
            destination_hash: Server's destination hash
            identity: RNS Identity object
            app_data: Announce app data (msgpack-encoded RMSP server info)
            hops: Number of network hops

        Returns:
            Dict with server info or error
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return {'success': False, 'error': 'RMSP client not available'}

            server = client.parse_rmsp_announce(destination_hash, identity, app_data, hops)
            if server:
                log_info("ReticulumWrapper", "parse_rmsp_announce",
                        f"Registered RMSP server: {server.name}")
                return {'success': True, 'server': server.to_dict()}
            else:
                return {'success': False, 'error': 'Failed to parse RMSP announce'}
        except Exception as e:
            log_error("ReticulumWrapper", "parse_rmsp_announce", f"Error: {e}")
            return {'success': False, 'error': str(e)}

    def get_rmsp_servers(self) -> List[Dict]:
        """
        Get all known RMSP map servers.

        Returns:
            List of server info dicts
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return []
            return client.get_servers()
        except Exception as e:
            log_error("ReticulumWrapper", "get_rmsp_servers", f"Error: {e}")
            return []

    def get_rmsp_servers_for_geohash(self, geohash: str) -> List[Dict]:
        """
        Get RMSP servers that cover the given geohash area.

        Args:
            geohash: Geohash string for the area of interest

        Returns:
            List of server info dicts that cover this area
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return []
            return client.get_servers_for_geohash(geohash)
        except Exception as e:
            log_error("ReticulumWrapper", "get_rmsp_servers_for_geohash", f"Error: {e}")
            return []

    def get_nearest_rmsp_servers(self, limit: int = 5) -> List[Dict]:
        """
        Get nearest RMSP servers by hop count.

        Args:
            limit: Maximum number of servers to return

        Returns:
            List of server info dicts sorted by hop count
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return []
            return client.get_nearest_servers(limit)
        except Exception as e:
            log_error("ReticulumWrapper", "get_nearest_rmsp_servers", f"Error: {e}")
            return []

    def query_rmsp_server(self, destination_hash_hex: str, geohash: str,
                          zoom_range: List[int] = None, format: str = None,
                          timeout: float = 30.0) -> Dict:
        """
        Query an RMSP server for available map data.

        Args:
            destination_hash_hex: Server destination hash as hex string
            geohash: Geohash area to query
            zoom_range: Optional [min_zoom, max_zoom]
            format: Optional format (pmtiles, micro)
            timeout: Request timeout in seconds

        Returns:
            QueryResponse dict
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return {'available': False, 'geohash': geohash, 'error_message': 'RMSP client not available'}
            return client.query_server(destination_hash_hex, geohash, zoom_range, format, timeout)
        except Exception as e:
            log_error("ReticulumWrapper", "query_rmsp_server", f"Error: {e}")
            return {'available': False, 'geohash': geohash, 'error_message': str(e)}

    def fetch_rmsp_tiles(self, destination_hash_hex: str, public_key: bytes,
                         geohash: str, zoom_range: List[int] = None,
                         format: str = None, timeout: float = 3600.0) -> bytes:
        """
        Fetch tile data from an RMSP server.

        Args:
            destination_hash_hex: Server destination hash as hex string
            public_key: Server's RNS identity public key (for establishing link)
            geohash: Geohash area to fetch
            zoom_range: Optional [min_zoom, max_zoom]
            format: Optional format (pmtiles, micro)
            timeout: Request timeout in seconds

        Returns:
            Raw tile data bytes, or empty bytes on failure
        """
        try:
            client = self._get_rmsp_client()
            if client is None:
                return bytes()
            data = client.fetch_tiles(destination_hash_hex, public_key, geohash, zoom_range, format, timeout)
            return data if data else bytes()
        except Exception as e:
            log_error("ReticulumWrapper", "fetch_rmsp_tiles", f"Error: {e}")
            return bytes()

    def clear_rmsp_servers(self):
        """Clear all known RMSP servers."""
        try:
            client = self._get_rmsp_client()
            if client:
                client.clear_servers()
                log_info("ReticulumWrapper", "clear_rmsp_servers", "Cleared all RMSP servers")
        except Exception as e:
            log_error("ReticulumWrapper", "clear_rmsp_servers", f"Error: {e}")
