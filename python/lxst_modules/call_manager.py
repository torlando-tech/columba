"""
Call manager wrapper for LXST Telephony primitive.

Provides Kotlin-friendly interface for managing voice calls over Reticulum.
Handles call state, callbacks, and audio pipeline lifecycle.

This module wraps LXST.Primitives.Telephony.Telephone to provide:
- Simplified API for Kotlin integration
- Callback registration for call state changes
- Audio bridge integration with KotlinAudioBridge

Usage:
    # Initialize from Kotlin via reticulum_wrapper
    wrapper.callAttr("initialize_call_manager")

    # Make a call
    call_manager = get_call_manager()
    call_manager.call("destination_hash_hex")
"""

import threading

try:
    import RNS
except ImportError:
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

# Global call manager instance
_call_manager = None
_call_manager_lock = threading.Lock()


def get_call_manager():
    """Get the global CallManager instance.

    Returns:
        CallManager instance or None if not initialized
    """
    with _call_manager_lock:
        return _call_manager


def initialize_call_manager(identity, audio_bridge, kotlin_call_bridge=None):
    """Initialize the global CallManager.

    Args:
        identity: RNS Identity for this node
        audio_bridge: KotlinAudioBridge instance
        kotlin_call_bridge: Optional CallBridge instance for state callbacks

    Returns:
        CallManager instance
    """
    global _call_manager
    with _call_manager_lock:
        if _call_manager is not None:
            RNS.log("CallManager already initialized", RNS.LOG_WARNING)
            return _call_manager

        _call_manager = CallManager(identity)
        _call_manager.initialize(audio_bridge, kotlin_call_bridge)
        return _call_manager


def shutdown_call_manager():
    """Shutdown the global CallManager."""
    global _call_manager
    with _call_manager_lock:
        if _call_manager is not None:
            _call_manager.teardown()
            _call_manager = None


class CallManager:
    """Manages LXST Telephone instance with Kotlin callbacks.

    Wraps LXST.Primitives.Telephony.Telephone with a Kotlin-friendly API
    for integration with Columba's call UI.
    """

    # Quality profiles matching LXST Profiles
    PROFILE_ULBW = 0x10  # Ultra Low Bandwidth (Codec2 700C)
    PROFILE_VLBW = 0x20  # Very Low Bandwidth (Codec2 1600)
    PROFILE_LBW = 0x30   # Low Bandwidth (Codec2 3200)
    PROFILE_MQ = 0x40    # Medium Quality (Opus)
    PROFILE_HQ = 0x50    # High Quality (Opus)
    PROFILE_SHQ = 0x60   # Super High Quality (Opus)
    PROFILE_LL = 0x70    # Low Latency (Opus)
    PROFILE_ULL = 0x80   # Ultra Low Latency (Opus)

    DEFAULT_PROFILE = PROFILE_MQ

    def __init__(self, identity):
        """Initialize CallManager.

        Args:
            identity: RNS Identity for this node
        """
        self.identity = identity
        self.telephone = None
        self._audio_bridge = None
        self._kotlin_call_bridge = None
        self._initialized = False

        # Internal state
        self._active_call_identity = None
        self._call_start_time = None

    def initialize(self, audio_bridge, kotlin_call_bridge=None):
        """Initialize the LXST Telephone with audio bridge.

        Args:
            audio_bridge: KotlinAudioBridge instance
            kotlin_call_bridge: Optional CallBridge for state callbacks
        """
        if self._initialized:
            RNS.log("CallManager already initialized", RNS.LOG_WARNING)
            return

        self._audio_bridge = audio_bridge
        self._kotlin_call_bridge = kotlin_call_bridge

        # Set up Chaquopy audio backend
        from .chaquopy_audio_backend import set_kotlin_audio_bridge
        set_kotlin_audio_bridge(audio_bridge)

        try:
            # Import LXST Telephony
            # Note: LXST must be installed or available in Python path
            from LXST.Primitives.Telephony import Telephone

            self.telephone = Telephone(
                self.identity,
                ring_time=60,
                wait_time=70,
                auto_answer=None,
            )

            # Wire up LXST callbacks to Kotlin callbacks
            self.telephone.set_ringing_callback(self._handle_ringing)
            self.telephone.set_established_callback(self._handle_established)
            self.telephone.set_ended_callback(self._handle_ended)
            self.telephone.set_busy_callback(self._handle_busy)
            self.telephone.set_rejected_callback(self._handle_rejected)

            self._initialized = True
            RNS.log("CallManager initialized with LXST Telephone", RNS.LOG_INFO)

        except ImportError as e:
            RNS.log(f"LXST not available, call functionality disabled: {e}", RNS.LOG_WARNING)
            self._initialized = False

        except Exception as e:
            RNS.log(f"Error initializing CallManager: {e}", RNS.LOG_ERROR)
            self._initialized = False

    def teardown(self):
        """Cleanup CallManager resources."""
        if self.telephone is not None:
            try:
                self.telephone.teardown()
            except Exception as e:
                RNS.log(f"Error tearing down Telephone: {e}", RNS.LOG_ERROR)
            self.telephone = None
        self._initialized = False
        RNS.log("CallManager torn down", RNS.LOG_INFO)

    def set_kotlin_call_bridge(self, bridge):
        """Set the Kotlin CallBridge for state callbacks.

        Args:
            bridge: CallBridge instance from Kotlin
        """
        self._kotlin_call_bridge = bridge
        RNS.log("Kotlin CallBridge set", RNS.LOG_DEBUG)

    # ===== Call Actions (called from Kotlin) =====

    def call(self, destination_hash_hex, profile=None):
        """Initiate an outgoing call.

        Args:
            destination_hash_hex: 32-character hex hash of destination identity
            profile: Optional quality profile (defaults to PROFILE_MQ)

        Returns:
            dict with "success" and optional "error" keys
        """
        if not self._initialized or self.telephone is None:
            RNS.log("Cannot call: CallManager not initialized", RNS.LOG_ERROR)
            return {"success": False, "error": "CallManager not initialized"}

        if profile is None:
            profile = self.DEFAULT_PROFILE

        try:
            # Convert hex string to bytes
            dest_hash = bytes.fromhex(destination_hash_hex)

            # Recall the identity from Reticulum
            identity = RNS.Identity.recall(dest_hash)
            if identity is None:
                RNS.log(f"Unknown identity: {destination_hash_hex[:16]}...", RNS.LOG_WARNING)
                return {"success": False, "error": "Unknown identity"}

            # Initiate the call
            self._active_call_identity = destination_hash_hex
            self.telephone.call(identity, profile=profile)
            RNS.log(f"Initiating call to {destination_hash_hex[:16]}...", RNS.LOG_INFO)
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
        if not self._initialized or self.telephone is None:
            RNS.log("Cannot answer: CallManager not initialized", RNS.LOG_ERROR)
            return False

        if self.telephone.active_call is None:
            RNS.log("Cannot answer: No active incoming call", RNS.LOG_WARNING)
            return False

        try:
            identity = self.telephone.active_call.get_remote_identity()
            if identity is None:
                RNS.log("Cannot answer: Unknown remote identity", RNS.LOG_ERROR)
                return False

            result = self.telephone.answer(identity)
            if result:
                self._active_call_identity = identity.hash.hex()
                RNS.log(f"Answered call from {self._active_call_identity[:16]}...", RNS.LOG_INFO)
            return result

        except Exception as e:
            RNS.log(f"Error answering call: {e}", RNS.LOG_ERROR)
            return False

    def hangup(self):
        """End the current call."""
        if not self._initialized or self.telephone is None:
            return

        try:
            self.telephone.hangup()
            RNS.log("Call hung up", RNS.LOG_INFO)
        except Exception as e:
            RNS.log(f"Error hanging up: {e}", RNS.LOG_ERROR)

    def mute_microphone(self, muted):
        """Mute or unmute the microphone.

        Args:
            muted: True to mute, False to unmute
        """
        if not self._initialized or self.telephone is None:
            return

        try:
            if muted:
                self.telephone.mute_transmit()
            else:
                self.telephone.unmute_transmit()
            RNS.log(f"Microphone muted: {muted}", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Error muting microphone: {e}", RNS.LOG_ERROR)

    def set_speaker(self, enabled):
        """Enable or disable speaker.

        Args:
            enabled: True for speaker, False for earpiece
        """
        if self._audio_bridge is not None:
            try:
                self._audio_bridge.setSpeakerphoneOn(enabled)
                RNS.log(f"Speaker enabled: {enabled}", RNS.LOG_DEBUG)
            except Exception as e:
                RNS.log(f"Error setting speaker: {e}", RNS.LOG_ERROR)

    def get_call_state(self):
        """Get current call state for UI.

        Returns:
            dict with call state information
        """
        if not self._initialized or self.telephone is None:
            return {
                "status": "unavailable",
                "is_active": False,
                "is_muted": False,
                "profile": None,
            }

        return {
            "status": self._get_status_string(),
            "is_active": self.telephone.active_call is not None,
            "is_muted": self.telephone.transmit_muted,
            "profile": self._get_profile_name(),
        }

    def _get_status_string(self):
        """Convert LXST call_status to string."""
        if self.telephone is None:
            return "unavailable"

        status = self.telephone.call_status
        status_map = {
            0x00: "busy",
            0x01: "rejected",
            0x02: "calling",
            0x03: "available",
            0x04: "ringing",
            0x05: "connecting",
            0x06: "established",
        }
        return status_map.get(status, "unknown")

    def _get_profile_name(self):
        """Get current profile name."""
        if self.telephone is None or self.telephone.active_profile is None:
            return None

        profile = self.telephone.active_profile
        profile_names = {
            self.PROFILE_ULBW: "Ultra Low Bandwidth",
            self.PROFILE_VLBW: "Very Low Bandwidth",
            self.PROFILE_LBW: "Low Bandwidth",
            self.PROFILE_MQ: "Medium Quality",
            self.PROFILE_HQ: "High Quality",
            self.PROFILE_SHQ: "Super High Quality",
            self.PROFILE_LL: "Low Latency",
            self.PROFILE_ULL: "Ultra Low Latency",
        }
        return profile_names.get(profile, "Unknown")

    # ===== LXST Callback Handlers =====

    def _handle_ringing(self, identity):
        """Handle incoming call ringing."""
        identity_hash = identity.hash.hex() if identity else None
        RNS.log(f"Incoming call from {identity_hash[:16] if identity_hash else 'unknown'}...", RNS.LOG_INFO)

        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onIncomingCall(identity_hash)
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of incoming call: {e}", RNS.LOG_ERROR)

    def _handle_established(self, identity):
        """Handle call established."""
        identity_hash = identity.hash.hex() if identity else None
        self._active_call_identity = identity_hash
        import time
        self._call_start_time = time.time()
        RNS.log(f"Call established with {identity_hash[:16] if identity_hash else 'unknown'}...", RNS.LOG_INFO)

        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onCallEstablished(identity_hash)
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of call established: {e}", RNS.LOG_ERROR)

    def _handle_ended(self, identity):
        """Handle call ended."""
        identity_hash = identity.hash.hex() if identity else self._active_call_identity
        RNS.log(f"Call ended with {identity_hash[:16] if identity_hash else 'unknown'}...", RNS.LOG_INFO)

        self._active_call_identity = None
        self._call_start_time = None

        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onCallEnded(identity_hash)
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of call ended: {e}", RNS.LOG_ERROR)

    def _handle_busy(self, identity):
        """Handle remote busy."""
        RNS.log("Remote is busy", RNS.LOG_INFO)

        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onCallBusy()
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of busy: {e}", RNS.LOG_ERROR)

    def _handle_rejected(self, identity):
        """Handle call rejected."""
        RNS.log("Call rejected", RNS.LOG_INFO)

        if self._kotlin_call_bridge is not None:
            try:
                self._kotlin_call_bridge.onCallRejected()
            except Exception as e:
                RNS.log(f"Error notifying Kotlin of rejection: {e}", RNS.LOG_ERROR)
