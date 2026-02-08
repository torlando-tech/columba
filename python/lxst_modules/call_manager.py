"""
Call manager for Reticulum telephony transport.

Provides Kotlin-friendly interface for managing voice calls over Reticulum.
Handles link lifecycle, packet forwarding, and signalling using raw Reticulum
APIs with LXST-compatible msgpack wire format.

This is a pure network transport layer — all audio processing, codecs, and
state machine logic live in Kotlin. Python handles:
- Reticulum link establishment and teardown
- Identity verification and allow-list checking
- Msgpack packet framing/unframing over RNS.Link
- Forwarding packets and signals between Reticulum and Kotlin bridge

Wire format (LXST-compatible for Sideband interop):
- Audio:      {0x01: codec_header_byte + encoded_frame}
- Signalling: {0x00: [signal_byte]}

Usage:
    # Initialize from Kotlin via reticulum_wrapper
    wrapper.callAttr("initialize_call_manager")

    # Make a call
    call_manager = get_call_manager()
    call_manager.call("destination_hash_hex")
"""

import threading
import time

try:
    import RNS
    from RNS.vendor import umsgpack
except ImportError:  # pragma: no cover
    class RNS:
        LOG_DEBUG = 0
        LOG_INFO = 1
        LOG_WARNING = 2
        LOG_ERROR = 3

        @staticmethod
        def log(msg, level=LOG_INFO):
            print(f"[RNS] {msg}")

        class Identity:
            @staticmethod
            def recall(hash_bytes):
                return None

        class Destination:
            IN = 1
            OUT = 2
            SINGLE = 1
            PROVE_NONE = 0

            def __init__(self, *args, **kwargs):
                self.hash = b'\x00' * 16

            def set_proof_strategy(self, s):
                pass

            def set_link_established_callback(self, cb):
                pass

        class Transport:
            @staticmethod
            def has_path(h):
                return False

            @staticmethod
            def request_path(h):
                pass

        class Link:
            ACTIVE = 0x00

            def __init__(self, *args, **kwargs):
                self.status = self.ACTIVE

            def set_packet_callback(self, cb):
                pass

            def set_link_closed_callback(self, cb):
                pass

            def set_remote_identified_callback(self, cb):
                pass

            def identify(self, identity):
                pass

            def teardown(self):
                pass

            def get_remote_identity(self):
                return None

        class Packet:
            def __init__(self, *args, **kwargs):
                pass

            def send(self):
                return True

    import json
    class umsgpack:
        @staticmethod
        def packb(data):
            return json.dumps(data).encode()

        @staticmethod
        def unpackb(data):
            return json.loads(data)


# LXST-compatible constants (frozen wire format for Sideband interop)
APP_NAME = "lxst"
PRIMITIVE_NAME = "telephony"
FIELD_SIGNALLING = 0x00
FIELD_FRAMES = 0x01

# Signalling status codes (match Kotlin Signalling.kt exactly)
STATUS_BUSY = 0x00
STATUS_REJECTED = 0x01
STATUS_CALLING = 0x02
STATUS_AVAILABLE = 0x03
STATUS_RINGING = 0x04
STATUS_CONNECTING = 0x05
STATUS_ESTABLISHED = 0x06
PREFERRED_PROFILE = 0xFF

# Global call manager instance
_call_manager = None
_call_manager_lock = threading.Lock()


def get_call_manager():
    """Get the global CallManager instance."""
    with _call_manager_lock:
        return _call_manager


def initialize_call_manager(identity, kotlin_call_bridge=None, kotlin_network_bridge=None):
    """Initialize the global CallManager.

    Args:
        identity: RNS Identity for this node
        kotlin_call_bridge: Optional CallBridge instance for state callbacks
        kotlin_network_bridge: Optional NetworkPacketBridge for packet transfer

    Returns:
        CallManager instance
    """
    global _call_manager
    with _call_manager_lock:
        if _call_manager is not None:
            RNS.log("CallManager already initialized", RNS.LOG_WARNING)
            return _call_manager

        _call_manager = CallManager(identity)
        _call_manager.initialize(kotlin_call_bridge, kotlin_network_bridge)
        return _call_manager


def shutdown_call_manager():
    """Shutdown the global CallManager."""
    global _call_manager
    with _call_manager_lock:
        if _call_manager is not None:
            _call_manager.teardown()
            _call_manager = None


class CallManager:
    """Pure network transport for Reticulum telephony.

    Manages Reticulum link lifecycle and forwards packets/signals between
    the Reticulum network and Kotlin audio pipeline via Chaquopy bridge.
    All audio processing happens in Kotlin; Python is the network layer.
    """

    def __init__(self, identity):
        self.identity = identity
        self.active_call = None  # RNS.Link instance
        self.destination = None  # RNS.Destination for incoming calls
        self._busy = False
        self._kotlin_call_bridge = None
        self._kotlin_network_bridge = None
        self._kotlin_telephone_callback = None
        self._initialized = False
        self._active_call_identity = None
        self._call_start_time = None
        self._call_handler_lock = threading.Lock()

    def initialize(self, kotlin_call_bridge=None, kotlin_network_bridge=None):
        """Initialize Reticulum destination for incoming calls.

        Args:
            kotlin_call_bridge: CallBridge for UI state callbacks
            kotlin_network_bridge: NetworkPacketBridge for packet transfer
        """
        if self._initialized:
            RNS.log("CallManager already initialized", RNS.LOG_WARNING)
            return

        self._kotlin_call_bridge = kotlin_call_bridge
        self._kotlin_network_bridge = kotlin_network_bridge

        try:
            # Register destination for incoming calls
            self.destination = RNS.Destination(
                self.identity, RNS.Destination.IN, RNS.Destination.SINGLE,
                APP_NAME, PRIMITIVE_NAME
            )
            self.destination.set_proof_strategy(RNS.Destination.PROVE_NONE)
            self.destination.set_link_established_callback(self.__incoming_link_established)

            self._initialized = True
            RNS.log("CallManager initialized with raw Reticulum transport", RNS.LOG_INFO)

        except Exception as e:
            RNS.log(f"Error initializing CallManager: {e}", RNS.LOG_ERROR)
            self._initialized = False

    def teardown(self):
        """Cleanup CallManager resources."""
        if self.active_call is not None:
            try:
                if hasattr(self.active_call, 'status') and self.active_call.status == RNS.Link.ACTIVE:
                    self.active_call.teardown()
            except Exception as e:
                RNS.log(f"Error tearing down active call: {e}", RNS.LOG_ERROR)
            self.active_call = None
        self._initialized = False
        RNS.log("CallManager torn down", RNS.LOG_INFO)

    # ===== Bridge Setup =====

    def set_kotlin_call_bridge(self, bridge):
        """Set the Kotlin CallBridge for state callbacks."""
        self._kotlin_call_bridge = bridge
        RNS.log("Kotlin CallBridge set", RNS.LOG_DEBUG)

    def set_kotlin_telephone_callback(self, callback):
        """Set callback for notifying Kotlin Telephone of state changes."""
        self._kotlin_telephone_callback = callback
        RNS.log("Kotlin Telephone callback set", RNS.LOG_DEBUG)

    # ===== Call Actions (called from Kotlin) =====

    def call(self, destination_hash_hex, profile=None):
        """Initiate an outgoing call.

        Args:
            destination_hash_hex: 32-character hex hash of destination identity
            profile: Optional quality profile (unused — Kotlin manages profiles)

        Returns:
            dict with "success" and optional "error" keys
        """
        if not self._initialized:
            RNS.log("Cannot call: CallManager not initialized", RNS.LOG_ERROR)
            return {"success": False, "error": "CallManager not initialized"}

        try:
            identity_hash = bytes.fromhex(destination_hash_hex)
            identity = RNS.Identity.recall(identity_hash)
            if identity is None:
                RNS.log(f"Unknown identity: {destination_hash_hex[:16]}...", RNS.LOG_WARNING)
                return {"success": False, "error": "Unknown identity"}

            self._active_call_identity = destination_hash_hex

            # Create outgoing destination
            call_destination = RNS.Destination(
                identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
                APP_NAME, PRIMITIVE_NAME
            )

            # Path discovery with timeout
            outgoing_call_timeout = time.time() + 70  # Match LXST wait_time
            if not RNS.Transport.has_path(call_destination.hash):
                RNS.log(f"No path known, requesting path...", RNS.LOG_DEBUG)
                RNS.Transport.request_path(call_destination.hash)
                while not RNS.Transport.has_path(call_destination.hash) and time.time() < outgoing_call_timeout:
                    time.sleep(0.2)

            if not RNS.Transport.has_path(call_destination.hash):
                RNS.log(f"Path discovery timeout", RNS.LOG_WARNING)
                return {"success": False, "error": "Path discovery timeout"}

            # Establish link
            RNS.log(f"Establishing link to {destination_hash_hex[:16]}...", RNS.LOG_INFO)
            self.active_call = RNS.Link(
                call_destination,
                established_callback=self.__outgoing_link_established,
                closed_callback=self.__link_closed
            )

            return {"success": True}

        except ValueError as e:
            RNS.log(f"Invalid destination hash: {e}", RNS.LOG_ERROR)
            return {"success": False, "error": f"Invalid hash: {e}"}
        except Exception as e:
            RNS.log(f"Error initiating call: {e}", RNS.LOG_ERROR)
            return {"success": False, "error": str(e)}

    def answer(self):
        """Answer an incoming call.

        Returns:
            bool indicating success
        """
        if not self._initialized or self.active_call is None:
            RNS.log("Cannot answer: no active incoming call", RNS.LOG_WARNING)
            return False

        try:
            remote_identity = self.active_call.get_remote_identity()
            if remote_identity is None:
                RNS.log("Cannot answer: unknown remote identity", RNS.LOG_ERROR)
                return False

            self._active_call_identity = remote_identity.hash.hex()

            # Kotlin Telephone handles answer — signals are sent by Kotlin
            # via receive_signal() which calls _send_signal_to_remote()
            RNS.log(f"Answered call from {self._active_call_identity[:16]}...", RNS.LOG_INFO)
            return True

        except Exception as e:
            RNS.log(f"Error answering call: {e}", RNS.LOG_ERROR)
            return False

    def hangup(self):
        """End the current call."""
        RNS.log("hangup() called", RNS.LOG_INFO)

        if self.active_call is not None:
            try:
                if hasattr(self.active_call, 'status') and self.active_call.status == RNS.Link.ACTIVE:
                    self.active_call.teardown()
            except Exception as e:
                RNS.log(f"Error during hangup teardown: {e}", RNS.LOG_ERROR)
            self.active_call = None

        # Notify Kotlin that call ended
        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onCallEnded(self._active_call_identity)
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of hangup: {e}", RNS.LOG_ERROR)

        self._active_call_identity = None
        self._call_start_time = None

    # ===== Reticulum Link Callbacks =====

    def __incoming_link_established(self, link):
        """Handle new incoming link (someone is calling us)."""
        with self._call_handler_lock:
            if self.active_call is not None or self._busy:
                RNS.log("Incoming call, but line busy, signalling busy", RNS.LOG_DEBUG)
                self._send_signal_to_remote(STATUS_BUSY, link)
                link.teardown()
                return

            # Set callbacks for identity verification
            link.set_remote_identified_callback(self.__caller_identified)
            link.set_link_closed_callback(self.__link_closed)

            # Tell remote we're here (triggers their identify())
            self._send_signal_to_remote(STATUS_AVAILABLE, link)

    def __caller_identified(self, link, identity):
        """Handle caller identity verification."""
        with self._call_handler_lock:
            if self.active_call is not None or self._busy:
                RNS.log(f"Caller identified but line busy, signalling busy", RNS.LOG_DEBUG)
                self._send_signal_to_remote(STATUS_BUSY, link)
                link.teardown()
                return

            if not self._is_allowed(identity):
                RNS.log(f"Caller not allowed, signalling busy", RNS.LOG_DEBUG)
                self._send_signal_to_remote(STATUS_BUSY, link)
                link.teardown()
                return

            identity_hash = identity.hash.hex()
            RNS.log(f"Caller identified: {identity_hash[:16]}..., ringing", RNS.LOG_DEBUG)

            # Accept the call
            self.active_call = link
            self._active_call_identity = identity_hash

            # Set packet callback for audio/signalling
            link.set_packet_callback(self.__packet_received)

            # Tell Kotlin Telephone about the incoming call FIRST so the
            # state machine (isIncomingCall, callStatus) is ready before
            # any remote signals arrive. This enables answer() to work.
            self._notify_kotlin("ringing", identity_hash)

            # Send RINGING to remote (Python handles network signalling)
            self._send_signal_to_remote(STATUS_RINGING, link)

            # Notify Kotlin CallBridge directly for reliable UI notification.
            # _notify_kotlin above goes through a callback chain that may fail
            # silently; direct CallBridge call ensures the incoming call screen
            # always appears. Duplicate onIncomingCall is idempotent.
            if self._kotlin_call_bridge is not None:
                try:
                    self._kotlin_call_bridge.onIncomingCall(identity_hash)
                except Exception as e:
                    RNS.log(f"Error notifying Kotlin of incoming call: {e}", RNS.LOG_ERROR)

    def __outgoing_link_established(self, link):
        """Handle outgoing link established (we connected to remote)."""
        RNS.log(f"Outgoing link established", RNS.LOG_DEBUG)

        # Set packet callback for receiving audio/signalling
        link.set_packet_callback(self.__packet_received)
        link.set_link_closed_callback(self.__link_closed)

        # Handle signalling from remote (link is already our active_call)
        # The remote will send STATUS_AVAILABLE, then we identify

    def __link_closed(self, link):
        """Handle link closed (remote hung up or link failure)."""
        if link == self.active_call:
            RNS.log("Link closed, call ended", RNS.LOG_DEBUG)
            self.active_call = None

            # Notify Kotlin
            self._send_signal_to_kotlin(STATUS_AVAILABLE)

            if self._kotlin_call_bridge is not None:
                try:
                    self._kotlin_call_bridge.onCallEnded(self._active_call_identity)
                except Exception as e:
                    RNS.log(f"Error notifying Kotlin of link close: {e}", RNS.LOG_ERROR)

            self._active_call_identity = None
            self._call_start_time = None

    # ===== Packet Handling =====

    def __packet_received(self, data, packet):
        """Handle incoming packet from Reticulum link.

        Unpacks msgpack and forwards audio/signalling to Kotlin.
        """
        try:
            unpacked = umsgpack.unpackb(data)
            if not isinstance(unpacked, dict):
                return

            # Audio frames: {0x01: codec_header + frame_data}
            if FIELD_FRAMES in unpacked:
                frames = unpacked[FIELD_FRAMES]
                if not isinstance(frames, list):
                    frames = [frames]
                for frame in frames:
                    if self._kotlin_network_bridge is not None:
                        try:
                            self._kotlin_network_bridge.onInboundPacket(bytes(frame))
                        except Exception as e:
                            RNS.log(f"Error forwarding frame to Kotlin: {e}", RNS.LOG_ERROR)

            # Signalling: {0x00: [signal_byte]} or {0x00: signal_byte}
            if FIELD_SIGNALLING in unpacked:
                signalling = unpacked[FIELD_SIGNALLING]
                if not isinstance(signalling, list):
                    signalling = [signalling]
                for signal in signalling:
                    self._handle_remote_signal(signal)

        except Exception as e:
            RNS.log(f"Error processing incoming packet: {e}", RNS.LOG_ERROR)

    def _handle_remote_signal(self, signal):
        """Handle signal received from remote peer via Reticulum.

        Some signals need local processing (STATUS_AVAILABLE triggers identify),
        all signals are forwarded to Kotlin state machine.
        """
        RNS.log(f"Remote signal: 0x{signal:02x}", RNS.LOG_DEBUG)

        # STATUS_AVAILABLE from remote means we should identify ourselves
        if signal == STATUS_AVAILABLE and self.active_call is not None:
            RNS.log("Remote available, identifying...", RNS.LOG_DEBUG)
            self.active_call.identify(self.identity)

        # Forward ALL signals to Kotlin Telephone state machine
        self._send_signal_to_kotlin(signal)

    # ===== Network Bridge Methods (Kotlin <-> Reticulum) =====

    def send_audio_packet(self, packet_data):
        """Forward audio packet from Python to Kotlin (incoming audio).

        Called when audio arrives from Reticulum — but in the new architecture,
        __packet_received handles this directly. Kept for API compatibility.
        """
        if self._kotlin_network_bridge is not None:
            try:
                self._kotlin_network_bridge.onInboundPacket(packet_data)
            except Exception as e:
                RNS.log(f"Failed to send packet to Kotlin: {e}", RNS.LOG_ERROR)

    def send_signal(self, signal):
        """Send signal to Kotlin Telephone state machine.

        Args:
            signal: int signal value (STATUS_* or profile change)
        """
        self._send_signal_to_kotlin(signal)

    _rx_packet_count = 0  # TEMP: diagnostic counter

    def receive_audio_packet(self, packet_data):
        """Receive encoded audio from Kotlin, send to remote via Reticulum.

        Called by Kotlin Packetizer (via NetworkPacketBridge.sendPacket → Python).
        Wraps in LXST-compatible msgpack and sends over active link.

        Args:
            packet_data: bytes (codec header byte + encoded frame)
        """
        self._rx_packet_count += 1

        if self.active_call is None:
            if self._rx_packet_count <= 5:
                RNS.log(f"receive_audio_packet #{self._rx_packet_count}: active_call is None, dropping", RNS.LOG_WARNING)
            return

        try:
            if hasattr(self.active_call, 'status') and self.active_call.status != RNS.Link.ACTIVE:
                if self._rx_packet_count <= 5:
                    RNS.log(f"receive_audio_packet #{self._rx_packet_count}: link not ACTIVE (status={self.active_call.status}), dropping", RNS.LOG_WARNING)
                return

            # Convert jarray to bytes if needed (Chaquopy passes byte[] as jarray)
            if not isinstance(packet_data, bytes):
                packet_data = bytes(packet_data)

            frame_data = {FIELD_FRAMES: packet_data}
            packed = umsgpack.packb(frame_data)
            RNS.Packet(self.active_call, packed, create_receipt=False).send()

            if self._rx_packet_count <= 5 or self._rx_packet_count % 100 == 0:
                RNS.log(f"receive_audio_packet #{self._rx_packet_count}: sent {len(packed)} bytes to remote", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Error sending audio to remote #{self._rx_packet_count}: {e}", RNS.LOG_ERROR)

    def receive_signal(self, signal):
        """Receive signal from Kotlin, send to remote via Reticulum.

        Called by Kotlin SignallingReceiver (via NetworkPacketBridge.sendSignal → Python).
        Wraps in LXST-compatible msgpack and sends over active link.

        Args:
            signal: int signal value
        """
        if self.active_call is None:
            return

        self._send_signal_to_remote(signal, self.active_call)

    # ===== Stub Methods (audio handled by Kotlin) =====

    def mute_microphone(self, muted):
        """Stub — mute handled by Kotlin Telephone."""
        RNS.log(f"mute_microphone({muted}) — handled by Kotlin", RNS.LOG_DEBUG)

    def set_speaker(self, enabled):
        """Stub — speaker routing handled by Kotlin."""
        RNS.log(f"set_speaker({enabled}) — handled by Kotlin", RNS.LOG_DEBUG)

    def get_call_state(self):
        """Get current call state for UI."""
        return {
            "status": "active" if self.active_call is not None else "available",
            "is_active": self.active_call is not None,
            "is_muted": False,  # Kotlin manages mute state
            "profile": None,    # Kotlin manages profiles
        }

    # ===== Internal Helpers =====

    def _send_signal_to_remote(self, signal, link):
        """Send signal to remote peer via Reticulum link.

        Uses LXST-compatible wire format: {0x00: [signal]}
        """
        try:
            if hasattr(link, 'status') and link.status != RNS.Link.ACTIVE:
                RNS.log(f"Cannot send signal 0x{signal:02x}: link not active", RNS.LOG_DEBUG)
                return

            signal_data = {FIELD_SIGNALLING: [signal]}
            packed = umsgpack.packb(signal_data)
            RNS.Packet(link, packed, create_receipt=False).send()
            RNS.log(f"Sent signal 0x{signal:02x} to remote", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Error sending signal to remote: {e}", RNS.LOG_ERROR)

    def _send_signal_to_kotlin(self, signal):
        """Send signal to Kotlin Telephone via NetworkPacketBridge."""
        if self._kotlin_network_bridge is not None:
            try:
                self._kotlin_network_bridge.onInboundSignal(signal)
            except Exception as e:
                RNS.log(f"Failed to send signal to Kotlin: {e}", RNS.LOG_ERROR)

    def _notify_kotlin(self, event, identity_hash=None, extra=None):
        """Notify Kotlin Telephone callback of event."""
        if self._kotlin_telephone_callback:
            try:
                data = {'identity': identity_hash}
                if extra:
                    data.update(extra)
                self._kotlin_telephone_callback(event, data)
            except Exception as e:
                RNS.log(f"Error notifying Kotlin: {e}", RNS.LOG_ERROR)

    def _is_allowed(self, identity):
        """Check if caller identity is allowed.

        Returns True for all callers (allow-all). Contact-based filtering
        can be added in a future phase.
        """
        identity_hash = identity.hash.hex() if identity else "unknown"
        RNS.log(f"Allow check for {identity_hash[:16]}...: allowed", RNS.LOG_DEBUG)
        return True
