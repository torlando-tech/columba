"""
Reticulum Wrapper for Kotlin Integration
Provides a simplified interface to Reticulum/LXMF that Kotlin can call via Chaquopy.
"""

from typing import Optional, Dict, List, Callable
import json
import threading
import time
import os
import shutil
import sys
import importlib
import importlib.util
from logging_utils import log_debug, log_info, log_warning, log_error, log_separator

# Note: RNS/LXMF imports are deferred until after patches are deployed
# This ensures Python loads the patched code, not the original buggy code
RETICULUM_AVAILABLE = False
RNS = None
LXMF = None


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

        # BLE interface support (Android-specific - driver-based architecture)
        self.ble_interface = None  # AndroidBLEInterface instance (if enabled)
        self.transport_identity_hash = None  # 16-byte Transport identity hash (for BLE Protocol v2.2)
        self.kotlin_ble_bridge = None  # KotlinBLEBridge instance (passed from Kotlin)

        # Delivery status callback support (for event-driven message status updates)
        self.kotlin_delivery_status_callback = None  # Callback to Kotlin for delivery status events

        # Message received callback support (Phase 2.2 - event-driven message notifications)
        self.kotlin_message_received_callback = None  # Callback to Kotlin when LXMF message received

        # General Reticulum bridge for protocol-level callbacks (announces, link events, etc.)
        self.kotlin_reticulum_bridge = None  # KotlinReticulumBridge instance (passed from Kotlin)

        # Set global instance so AndroidBLEDriver can access it
        _global_wrapper_instance = self

        # Announce handlers - register multiple aspect-specific handlers
        # Following MeshChat's pattern to properly distinguish announce types
        self._announce_handlers = {
            "lxmf.delivery": AnnounceHandler("lxmf.delivery", self._announce_handler),
            "lxmf.propagation": AnnounceHandler("lxmf.propagation", self._announce_handler),
            "call.audio": AnnounceHandler("call.audio", self._announce_handler),
            "nomadnetwork.node": AnnounceHandler("nomadnetwork.node", self._announce_handler),
        }

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

    def _create_config_file(self, interfaces: List[Dict]):
        """
        Create an RNS config file with the specified interfaces.

        Args:
            interfaces: List of interface configuration dictionaries
        """
        from datetime import datetime

        config_path = os.path.join(self.storage_path, "config")
        log_debug("ReticulumWrapper", "_create_config_file", f"Creating config file at: {config_path}")
        log_debug("ReticulumWrapper", "_create_config_file", f"Number of interfaces: {len(interfaces)}")

        # Generate timestamp for config file
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # Start with warning header and base Reticulum configuration
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
            "  enable_transport = yes",  # Enable Transport to cache announces in path_table
            "  share_instance = no",
            "",
            "[interfaces]"
        ]

        # Add each interface
        for iface in interfaces:
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

                discovery_port = iface.get("discovery_port", 48555)
                if discovery_port != 48555:
                    config_lines.append(f"    discovery_port = {discovery_port}")

                data_port = iface.get("data_port", 49555)
                if data_port != 49555:
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
                config_lines.append("    type = RNodeInterface")
                config_lines.append("    enabled = yes")

                port = iface.get("port", "/dev/ttyUSB0")
                config_lines.append(f"    port = {port}")

                frequency = iface.get("frequency", 915000000)
                config_lines.append(f"    frequency = {frequency}")

                bandwidth = iface.get("bandwidth", 125000)
                config_lines.append(f"    bandwidth = {bandwidth}")

                tx_power = iface.get("tx_power", 7)
                config_lines.append(f"    txpower = {tx_power}")

                spreading_factor = iface.get("spreading_factor", 7)
                config_lines.append(f"    spreadingfactor = {spreading_factor}")

                coding_rate = iface.get("coding_rate", 5)
                config_lines.append(f"    codingrate = {coding_rate}")

                mode = iface.get("mode", "full")
                if mode != "full":
                    config_lines.append(f"    mode = {mode}")

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

    def initialize(self, config_json: str, identity_file_path: Optional[str] = None) -> Dict:
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

                        # Deploy patches
                        patch_files = ['Destination.py', '__init__.py']
                        patches_applied = 0

                        for patch_file in patch_files:
                            try:
                                patch_resource_path = f"patches/RNS/{patch_file}"
                                patch_data = pkgutil.get_data(__name__.split('.')[0], patch_resource_path)

                                if patch_data:
                                    patch_dest = os.path.join(rns_module_path, patch_file)
                                    with open(patch_dest, 'wb') as dest:
                                        dest.write(patch_data)

                                    log_info("ReticulumWrapper", "initialize", f"✓ Applied patch: {patch_file}")
                                    patches_applied += 1
                            except Exception as e:
                                log_warning("ReticulumWrapper", "initialize", f"Failed to apply patch {patch_file}: {e}")

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

            # Create config file from interface configurations
            log_info("ReticulumWrapper", "initialize", "Creating RNS config file from interface configurations")
            enabled_interfaces = config.get('enabledInterfaces', [])
            # Respect user's choice - if they want 0 interfaces, allow it
            # RNS will run without interfaces (no network connectivity)

            if not self._create_config_file(enabled_interfaces):
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
            # DIAGNOSTIC: Temporarily force EXTREME log level to debug packet processing
            log_level = RNS.LOG_EXTREME
            log_info("ReticulumWrapper", "initialize", "🔍 DIAGNOSTIC MODE: RNS.loglevel forced to EXTREME for packet debugging")
            log_debug("ReticulumWrapper", "initialize", f"Setting RNS.loglevel to {log_level}")
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
                import ble_modules  # Triggers extraction of package files from APK to filesystem

                # Deploy bluetooth_driver base interface
                log_debug("ReticulumWrapper", "initialize", "Deploying bluetooth_driver from bundled source")
                bluetooth_driver_bytes = pkgutil.get_data('ble_modules', 'bluetooth_driver.py')
                bluetooth_driver_dest = os.path.join(interfaces_dir, "bluetooth_driver.py")
                with open(bluetooth_driver_dest, 'wb') as f:
                    f.write(bluetooth_driver_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed bluetooth_driver to {bluetooth_driver_dest}")

                # Deploy linux_bluetooth_driver
                log_debug("ReticulumWrapper", "initialize", "Deploying linux_bluetooth_driver from bundled source")
                linux_bluetooth_driver_bytes = pkgutil.get_data('ble_modules', 'linux_bluetooth_driver.py')
                linux_bluetooth_driver_dest = os.path.join(interfaces_dir, "linux_bluetooth_driver.py")
                with open(linux_bluetooth_driver_dest, 'wb') as f:
                    f.write(linux_bluetooth_driver_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed linux_bluetooth_driver to {linux_bluetooth_driver_dest}")

                # Deploy BLEFragmentation
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEFragmentation from bundled source")
                ble_fragmentation_bytes = pkgutil.get_data('ble_modules', 'BLEFragmentation.py')
                ble_fragmentation_dest = os.path.join(interfaces_dir, "BLEFragmentation.py")
                with open(ble_fragmentation_dest, 'wb') as f:
                    f.write(ble_fragmentation_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed BLEFragmentation to {ble_fragmentation_dest}")

                # Deploy BLEGATTServer
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEGATTServer from bundled source")
                ble_gatt_server_bytes = pkgutil.get_data('ble_modules', 'BLEGATTServer.py')
                ble_gatt_server_dest = os.path.join(interfaces_dir, "BLEGATTServer.py")
                with open(ble_gatt_server_dest, 'wb') as f:
                    f.write(ble_gatt_server_bytes)
                log_info("ReticulumWrapper", "initialize", f"✓ Deployed BLEGATTServer to {ble_gatt_server_dest}")

                # Deploy BLEInterface
                log_debug("ReticulumWrapper", "initialize", "Deploying BLEInterface from bundled source")
                ble_interface_bytes = pkgutil.get_data('ble_modules', 'BLEInterface.py')
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

            # DIAGNOSTIC: Test socket.if_nametoindex availability
            try:
                import socket as _diag_socket
                log_info("ReticulumWrapper", "initialize", "=== DIAGNOSTIC: Testing socket.if_nametoindex ===")
                log_info("ReticulumWrapper", "initialize", f"Has attribute: {hasattr(_diag_socket, 'if_nametoindex')}")
                if hasattr(_diag_socket, 'if_nametoindex'):
                    log_info("ReticulumWrapper", "initialize", f"Function: {_diag_socket.if_nametoindex}")
                    try:
                        result = _diag_socket.if_nametoindex('lo')
                        log_info("ReticulumWrapper", "initialize", f"SUCCESS: if_nametoindex('lo') = {result}")
                    except OSError as e:
                        log_info("ReticulumWrapper", "initialize", f"OSError raised: {e}")
                    except Exception as e:
                        log_info("ReticulumWrapper", "initialize", f"Exception ({type(e).__name__}): {e}")
                else:
                    log_info("ReticulumWrapper", "initialize", "if_nametoindex NOT available")
            except Exception as e:
                log_warning("ReticulumWrapper", "initialize", f"Diagnostic failed: {e}")

            # TEMPORARILY DISABLED FOR TESTING - Remove this comment to re-enable
            # # Android fix: Patch AutoInterface to avoid socket leaks
            # # Chaquopy 16.0.0 stubs socket.if_nametoindex to raise OSError (not implemented)
            # # This causes OSError on Android, leading to unclosed sockets in AutoInterface.peer_announce()
            # # The fix uses the netinfo fallback (same as Windows) instead of socket.if_nametoindex()
            # try:
            #     from RNS.Interfaces import AutoInterface
            #     import RNS.vendor.platformutils as platformutils
            #
            #     # Store original method reference
            #     _original_interface_name_to_index = AutoInterface.AutoInterface.interface_name_to_index
            #
            #     def _patched_interface_name_to_index(self, ifname):
            #         """
            #         Patched version that uses netinfo fallback on Android.
            #         Same approach as Windows platform, avoiding socket.if_nametoindex() on Android.
            #         """
            #         if platformutils.is_windows() or platformutils.is_android():
            #             return self.netinfo.interface_names_to_indexes()[ifname]
            #         # Fall back to original implementation for other platforms
            #         return _original_interface_name_to_index(self, ifname)
            #
            #     # Apply the monkey-patch
            #     AutoInterface.AutoInterface.interface_name_to_index = _patched_interface_name_to_index
            #     log_info("ReticulumWrapper", "initialize", "✓ Applied Android AutoInterface socket leak fix")
            #
            # except Exception as e:
            #     log_warning("ReticulumWrapper", "initialize",
            #               f"Could not patch AutoInterface (non-fatal): {type(e).__name__}: {e}")
            #     # Continue anyway - worst case we get socket warnings but functionality works

            # Initialize Reticulum - it will load config from the config file we created
            log_info("ReticulumWrapper", "initialize", "Creating RNS.Reticulum instance")
            log_debug("ReticulumWrapper", "initialize", f"configdir = {self.storage_path}")

            # Track which interfaces failed to initialize
            self.failed_interfaces = []

            try:
                self.reticulum = RNS.Reticulum(configdir=self.storage_path)
                log_info("ReticulumWrapper", "initialize", "RNS.Reticulum created successfully")
            except OSError as e:
                if "Address already in use" in str(e) or "Errno 98" in str(e):
                    log_warning("ReticulumWrapper", "initialize", "⚠️ AutoInterface bind failed (address in use)")
                    log_warning("ReticulumWrapper", "initialize", "This usually means another Reticulum app (e.g., Sideband) is running")
                    log_info("ReticulumWrapper", "initialize", "Retrying initialization without AutoInterface...")

                    # Track that AutoInterface failed
                    self.failed_interfaces.append({
                        "name": "AutoInterface",
                        "error": "Address already in use - another Reticulum app may be running"
                    })

                    # Remove AutoInterface from config and retry
                    self._remove_autointerface_from_config()
                    self.reticulum = RNS.Reticulum(configdir=self.storage_path)
                    log_info("ReticulumWrapper", "initialize", "✅ RNS.Reticulum created successfully (without AutoInterface)")
                else:
                    # Different error - re-raise
                    log_error("ReticulumWrapper", "initialize", f"Failed to create RNS.Reticulum: {e}")
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
                # Create a new identity if it doesn't exist
                # This happens on first install or if the identity file was deleted
                log_info("ReticulumWrapper", "initialize", f"Identity not found at {identity_path}, creating new identity")
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
            self.router = LXMF.LXMRouter(
                storagepath=self.storage_path,
                identity=default_identity,
                autopeer=True
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
            log_separator("ReticulumWrapper", "initialize")
            log_info("ReticulumWrapper", "initialize", "Reticulum initialized successfully")
            log_separator("ReticulumWrapper", "initialize")
            return {"success": True}

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
                                receiving_interface = str(interface_obj)
            except Exception as e:
                log_debug("ReticulumWrapper", "_announce_handler",
                         f"Could not extract interface: {e}")

            # Create announce event dict (Transport already stores identity/app_data)
            announce_event = {
                'destination_hash': destination_hash,
                'identity_hash': destination_hash,  # For single destinations
                'public_key': announced_identity.get_public_key() if announced_identity else b'',
                'app_data': app_data if app_data else b'',
                'aspect': aspect,  # Include aspect (e.g., "lxmf.delivery", "call.audio")
                'hops': hops,
                'timestamp': int(time.time() * 1000),  # milliseconds
                'interface': receiving_interface,  # Add interface name
            }

            # Store in pending queue for Kotlin to retrieve
            with self.announce_lock:
                self.pending_announces.append(announce_event)

            # Notify Kotlin immediately via bridge (event-driven announce delivery)
            if self.kotlin_reticulum_bridge:
                try:
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
            self.announce_app_data.clear()
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

            # Add to pending_inbound queue (maintains backward compatibility with polling)
            if not hasattr(self.router, 'pending_inbound'):
                log_warning("ReticulumWrapper", "_on_lxmf_delivery", "Warning: Router has no pending_inbound, creating one")
                self.router.pending_inbound = []

            if lxmf_message not in self.router.pending_inbound:
                self.router.pending_inbound.append(lxmf_message)
                log_info("ReticulumWrapper", "_on_lxmf_delivery", f"✅ Added message to pending_inbound queue (now has {len(self.router.pending_inbound)} messages)")
            else:
                log_debug("ReticulumWrapper", "_on_lxmf_delivery", "Message already in pending_inbound")

            # ✅ PHASE 2.2: Invoke Kotlin callback for instant notification (event-driven)
            # Same pattern as delivery status callbacks which work reliably
            if self.kotlin_message_received_callback:
                try:
                    import json
                    import time

                    message_event = {
                        'message_hash': lxmf_message.hash.hex() if lxmf_message.hash else "unknown",
                        'source_hash': lxmf_message.source_hash.hex(),
                        'destination_hash': lxmf_message.destination_hash.hex(),
                        'timestamp': int(time.time() * 1000),
                        'content_length': len(lxmf_message.content) if lxmf_message.content else 0
                    }

                    log_debug("ReticulumWrapper", "_on_lxmf_delivery",
                             "Invoking Kotlin callback for instant notification...")
                    self.kotlin_message_received_callback(json.dumps(message_event))
                    log_info("ReticulumWrapper", "_on_lxmf_delivery",
                            "✅ Kotlin callback invoked successfully (event-driven notification sent)")
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
                    announce_entry = RNS.Transport.announce_table[dest_hash]
                    if len(announce_entry) > 5:
                        packet = announce_entry[5]  # IDX_AT_PACKET
                        if packet and hasattr(packet, 'receiving_interface'):
                            interface_obj = packet.receiving_interface
                            if interface_obj:
                                receiving_interface = str(interface_obj)
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

    def send_lxmf_message(self, dest_hash: bytes, content: str, source_identity_private_key: bytes, image_data: bytes = None, image_format: str = None) -> Dict:
        """
        Send an LXMF message to a destination.

        Args:
            dest_hash: Identity hash bytes (16 bytes) - will be converted to LXMF destination hash
            content: Message content string
            source_identity_private_key: Private key of sender identity
            image_data: Optional image data bytes
            image_format: Optional image format (e.g., 'jpg', 'png', 'webp')

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
                error_msg = f"Cannot send message: Recipient identity {dest_hash.hex()[:16]} not known. Please wait for announce or request path."
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

            # Prepare fields dictionary if image is provided
            fields = None
            if image_data and image_format:
                # Convert jarray to bytes if needed
                if hasattr(image_data, '__iter__') and not isinstance(image_data, (bytes, bytearray)):
                    image_data = bytes(image_data)

                # LXMF field 6 = IMAGE, format: [format_string, bytes_data]
                fields = {
                    6: [image_format, image_data]
                }
                log_info("ReticulumWrapper", "send_lxmf_message", f"📎 Attaching image: {len(image_data)} bytes, format={image_format}")

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

                if dest_lxmf_hash in path_table:
                    has_path = True
                    path_info = path_table[dest_lxmf_hash]
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

    def _on_message_delivered(self, lxmf_message):
        """
        Callback invoked by LXMF when a sent message is successfully delivered.
        This is called when the recipient sends back a cryptographic proof of delivery.

        Args:
            lxmf_message: The LXMF.LXMessage that was delivered
        """
        try:
            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"
            log_info("ReticulumWrapper", "_on_message_delivered",
                    f"✅ Message {msg_hash[:16]}... DELIVERED!")

            # Create status event for Kotlin
            status_event = {
                'message_hash': msg_hash,
                'status': 'delivered',
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

        Args:
            lxmf_message: The LXMF.LXMessage that failed
        """
        try:
            msg_hash = lxmf_message.hash.hex() if lxmf_message.hash else "unknown"
            log_error("ReticulumWrapper", "_on_message_failed",
                     f"❌ Message {msg_hash[:16]}... FAILED!")

            # Create status event for Kotlin
            status_event = {
                'message_hash': msg_hash,
                'status': 'failed',
                'timestamp': int(time.time() * 1000)
            }

            # Invoke Kotlin callback if registered (same pattern as BLE bridge)
            if self.kotlin_delivery_status_callback:
                try:
                    import json
                    self.kotlin_delivery_status_callback(json.dumps(status_event))
                    log_debug("ReticulumWrapper", "_on_message_failed",
                             "Kotlin callback invoked successfully")
                except Exception as e:
                    log_error("ReticulumWrapper", "_on_message_failed",
                             f"Error invoking Kotlin callback: {e}")
            else:
                log_warning("ReticulumWrapper", "_on_message_failed",
                           "No Kotlin callback registered - failure status not reported")

        except Exception as e:
            log_error("ReticulumWrapper", "_on_message_failed",
                     f"Error in failed callback: {e}")
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

            log_error("ReticulumWrapper", "restore_all_peer_identities", f"Restored {success_count} peer identities, {len(errors)} errors")
            return {"success_count": success_count, "errors": errors}

        except Exception as e:
            log_error("ReticulumWrapper", "restore_all_peer_identities", f"Error restoring peer identities: {e}")
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
        Poll for received LXMF messages.
        Accesses LXMRouter's internal message queue - bypasses broken delivery callbacks.

        Returns:
            List of received message dicts
        """
        log_info("ReticulumWrapper", "poll_received_messages", f"poll_received_messages() called - RETICULUM_AVAILABLE={RETICULUM_AVAILABLE}, initialized={self.initialized}, router={self.router is not None}")
        if not RETICULUM_AVAILABLE or not self.initialized or not self.router:
            log_debug("ReticulumWrapper", "poll_received_messages", f"poll_received_messages() returning early - conditions not met")
            return []

        try:
            new_messages = []

            # Debug: Check what attributes the router has
            router_attrs = [attr for attr in dir(self.router) if not attr.startswith('_')]
            log_debug("ReticulumWrapper", "poll_received_messages", f"Router has these public attributes: {router_attrs[:10]}...")

            # Check if pending_inbound exists
            has_pending = hasattr(self.router, 'pending_inbound')
            log_debug("ReticulumWrapper", "poll_received_messages", f"Router has 'pending_inbound' attribute: {has_pending}")

            if has_pending:
                pending_count = len(self.router.pending_inbound) if self.router.pending_inbound else 0
                log_debug("ReticulumWrapper", "poll_received_messages", f"pending_inbound has {pending_count} messages")

            # Check pending inbound messages
            if hasattr(self.router, 'pending_inbound') and self.router.pending_inbound:
                log_info("ReticulumWrapper", "poll_received_messages", f"✅ Checking pending_inbound, has {len(self.router.pending_inbound)} messages")

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

                        # Extract LXMF fields (attachments, images, etc.)
                        if hasattr(lxmf_message, 'fields') and lxmf_message.fields:
                            fields_serialized = {}
                            for key, value in lxmf_message.fields.items():
                                # Handle different LXMF field formats
                                # Field 6 (IMAGE): ['format', bytes] e.g. ['jpg', b'\xff\xd8...']
                                # Field 7 (AUDIO): ['format', bytes]
                                # Field 5 (FILE_ATTACHMENTS): list of [filename, bytes]
                                if isinstance(value, (list, tuple)) and len(value) >= 2:
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

                        # === PATH TABLE DIAGNOSTIC AFTER MESSAGE RECEIPT ===
                        try:
                            import RNS.Transport as Transport
                            source_hash = lxmf_message.source_hash
                            source_hex = source_hash.hex()[:16]

                            log_separator("ReticulumWrapper", "poll_received_messages", "=", 60)
                            log_info("ReticulumWrapper", "poll_received_messages", f"🔍 PATH TABLE CHECK AFTER RECEIVING MESSAGE FROM {source_hex}")

                            # Check if sender is in path_table
                            if hasattr(Transport, 'path_table'):
                                if source_hash in Transport.path_table:
                                    log_info("ReticulumWrapper", "poll_received_messages", f"✅ Sender {source_hex} IS in path_table!")
                                    path_info = Transport.path_table[source_hash]
                                    log_debug("ReticulumWrapper", "poll_received_messages", f"Path info: {path_info}")
                                else:
                                    log_warning("ReticulumWrapper", "poll_received_messages", f"⚠️  Sender {source_hex} NOT in path_table!")

                                # Log all current paths
                                path_count = len(Transport.path_table)
                                log_debug("ReticulumWrapper", "poll_received_messages", f"Current path_table has {path_count} entries")
                                if path_count > 0:
                                    all_paths = [h.hex()[:16] for h in Transport.path_table.keys()]
                                    log_debug("ReticulumWrapper", "poll_received_messages", f"All paths: {all_paths}")

                            log_separator("ReticulumWrapper", "poll_received_messages", "=", 60)
                        except Exception as e:
                            log_warning("ReticulumWrapper", "poll_received_messages", f"Error checking path_table after receipt: {e}")
                        # === END PATH TABLE DIAGNOSTIC ===

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

        # TODO: Implement hop count retrieval
        return None

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
                info['transport_enabled'] = True
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
