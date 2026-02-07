"""
Unit tests for CallManager (Reticulum telephony transport).

Tests network transport lifecycle, packet forwarding, signalling, and
Kotlin bridge integration. All audio/codec/state-machine tests live in
Kotlin (LXST-kt); Python only handles Reticulum link management.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock, call
import threading
import time


# Import the module under test
from lxst_modules.call_manager import (
    CallManager,
    get_call_manager,
    initialize_call_manager,
    shutdown_call_manager,
    _call_manager,
    _call_manager_lock,
    STATUS_BUSY,
    STATUS_AVAILABLE,
    STATUS_RINGING,
    STATUS_ESTABLISHED,
    FIELD_SIGNALLING,
    FIELD_FRAMES,
    RNS,
)


class TestCallManagerInitialization:
    """Test CallManager initialization and lifecycle."""

    def test_init_sets_identity(self):
        """CallManager should store the provided identity."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager.identity == mock_identity

    def test_init_has_no_active_call(self):
        """Initially, active_call should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager.active_call is None

    def test_init_has_no_destination(self):
        """Initially, destination should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager.destination is None

    def test_init_has_no_kotlin_call_bridge(self):
        """Initially, Kotlin call bridge should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._kotlin_call_bridge is None

    def test_init_has_no_kotlin_network_bridge(self):
        """Initially, Kotlin network bridge should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._kotlin_network_bridge is None

    def test_init_not_initialized(self):
        """Initially, _initialized should be False."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._initialized is False

    def test_init_no_active_call_identity(self):
        """Initially, no active call identity should be set."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._active_call_identity is None

    def test_init_no_call_start_time(self):
        """Initially, call start time should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._call_start_time is None

    def test_init_not_busy(self):
        """Initially, _busy should be False."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._busy is False


class TestCallManagerInitialize:
    """Test CallManager.initialize() method."""

    def test_initialize_stores_kotlin_call_bridge(self):
        """Initialize should store the Kotlin call bridge."""
        mock_identity = MagicMock()
        mock_bridge = MagicMock()
        manager = CallManager(mock_identity)
        manager.initialize(kotlin_call_bridge=mock_bridge)
        assert manager._kotlin_call_bridge == mock_bridge

    def test_initialize_stores_kotlin_network_bridge(self):
        """Initialize should store the Kotlin network bridge."""
        mock_identity = MagicMock()
        mock_network_bridge = MagicMock()
        manager = CallManager(mock_identity)
        manager.initialize(kotlin_network_bridge=mock_network_bridge)
        assert manager._kotlin_network_bridge == mock_network_bridge

    def test_initialize_sets_initialized_true(self):
        """Initialize should set _initialized to True."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.initialize()
        assert manager._initialized is True

    def test_initialize_creates_destination(self):
        """Initialize should create an RNS Destination."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.initialize()
        assert manager.destination is not None

    def test_initialize_warns_if_already_initialized(self):
        """Initialize should return early if already initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.initialize()
        first_dest = manager.destination

        manager.initialize()  # second call
        assert manager.destination is first_dest  # not recreated


class TestCallManagerTeardown:
    """Test CallManager.teardown() method."""

    def test_teardown_sets_initialized_false(self):
        """Teardown should set _initialized to False."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.teardown()
        assert manager._initialized is False

    def test_teardown_clears_active_call(self):
        """Teardown should set active_call to None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        manager.active_call = mock_link
        manager.teardown()
        assert manager.active_call is None

    def test_teardown_calls_link_teardown(self):
        """Teardown should call active_call.teardown() if link is active."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        manager.active_call = mock_link
        manager.teardown()
        mock_link.teardown.assert_called_once()

    def test_teardown_handles_link_exception(self):
        """Teardown should handle exceptions from link.teardown()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        mock_link.teardown.side_effect = RuntimeError("Test error")
        manager.active_call = mock_link

        manager.teardown()  # should not raise
        assert manager.active_call is None


class TestCallManagerSetBridges:
    """Test CallManager bridge setter methods."""

    def test_set_kotlin_call_bridge_stores_bridge(self):
        """set_kotlin_call_bridge should store the bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_bridge = MagicMock()
        manager.set_kotlin_call_bridge(mock_bridge)
        assert manager._kotlin_call_bridge == mock_bridge

    def test_set_kotlin_telephone_callback_stores_callback(self):
        """set_kotlin_telephone_callback should store the callback."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_callback = MagicMock()
        manager.set_kotlin_telephone_callback(mock_callback)
        assert manager._kotlin_telephone_callback == mock_callback


class TestCallManagerCall:
    """Test CallManager.call() method."""

    def test_call_returns_error_if_not_initialized(self):
        """call() should return error if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False
        result = manager.call("abc123")
        assert result["success"] is False
        assert "not initialized" in result["error"].lower()

    def test_call_returns_error_for_invalid_hash(self):
        """call() should return error for invalid hex hash."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        result = manager.call("not_valid_hex!")
        assert result["success"] is False

    def test_call_returns_error_for_unknown_identity(self):
        """call() should return error if identity not recalled."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.return_value = None
            result = manager.call("abc123def456789012345678901234567890")
        assert result["success"] is False
        assert "unknown" in result["error"].lower()

    def test_call_returns_success_on_link_establishment(self):
        """call() should return success when link is established."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.return_value = MagicMock()
            mock_rns.Destination.OUT = 1
            mock_rns.Destination.SINGLE = 2
            mock_rns.Transport.has_path.return_value = True

            result = manager.call("abc123def456789012345678901234567890")
        assert result["success"] is True

    def test_call_sets_active_call_identity(self):
        """call() should set _active_call_identity."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        dest_hash = "abc123def456789012345678901234567890"
        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.return_value = MagicMock()
            mock_rns.Destination.OUT = 1
            mock_rns.Destination.SINGLE = 2
            mock_rns.Transport.has_path.return_value = True

            manager.call(dest_hash)
        assert manager._active_call_identity == dest_hash


class TestCallManagerAnswer:
    """Test CallManager.answer() method."""

    def test_answer_returns_false_if_not_initialized(self):
        """answer() should return False if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False
        assert manager.answer() is False

    def test_answer_returns_false_if_no_active_call(self):
        """answer() should return False if no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.active_call = None
        assert manager.answer() is False

    def test_answer_returns_false_if_no_remote_identity(self):
        """answer() should return False if remote identity unknown."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_link = MagicMock()
        mock_link.get_remote_identity.return_value = None
        manager.active_call = mock_link
        assert manager.answer() is False

    def test_answer_returns_true_on_success(self):
        """answer() should return True when call is answered."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_link = MagicMock()
        mock_remote = MagicMock()
        mock_remote.hash.hex.return_value = "abc123"
        mock_link.get_remote_identity.return_value = mock_remote
        manager.active_call = mock_link

        assert manager.answer() is True
        assert manager._active_call_identity == "abc123"


class TestCallManagerHangup:
    """Test CallManager.hangup() method."""

    def test_hangup_clears_active_call(self):
        """hangup() should set active_call to None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        manager.active_call = mock_link
        manager.hangup()
        assert manager.active_call is None

    def test_hangup_tears_down_active_link(self):
        """hangup() should call teardown on the active link."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        manager.active_call = mock_link
        manager.hangup()
        mock_link.teardown.assert_called_once()

    def test_hangup_notifies_kotlin_bridge(self):
        """hangup() should notify Kotlin CallBridge of call end."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_bridge
        manager._active_call_identity = "abc123"

        manager.hangup()
        mock_bridge.onCallEnded.assert_called_once_with("abc123")

    def test_hangup_clears_call_identity_and_time(self):
        """hangup() should clear active call identity and start time."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._active_call_identity = "abc123"
        manager._call_start_time = time.time()

        manager.hangup()
        assert manager._active_call_identity is None
        assert manager._call_start_time is None

    def test_hangup_handles_no_active_call(self):
        """hangup() should not crash with no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.active_call = None
        manager.hangup()  # should not raise

    def test_hangup_handles_link_exception(self):
        """hangup() should handle exceptions from link.teardown()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE
        mock_link.teardown.side_effect = RuntimeError("Test error")
        manager.active_call = mock_link
        manager.hangup()  # should not raise
        assert manager.active_call is None


class TestCallManagerStubs:
    """Test stub methods (audio handled by Kotlin)."""

    def test_mute_microphone_does_not_crash(self):
        """mute_microphone() is a stub — should not crash."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.mute_microphone(True)
        manager.mute_microphone(False)

    def test_set_speaker_does_not_crash(self):
        """set_speaker() is a stub — should not crash."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.set_speaker(True)
        manager.set_speaker(False)


class TestCallManagerGetCallState:
    """Test CallManager.get_call_state() method."""

    def test_get_call_state_available_when_no_active_call(self):
        """get_call_state() should return 'available' with no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        result = manager.get_call_state()
        assert result["status"] == "available"
        assert result["is_active"] is False

    def test_get_call_state_active_when_call_in_progress(self):
        """get_call_state() should return 'active' during a call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.active_call = MagicMock()
        result = manager.get_call_state()
        assert result["status"] == "active"
        assert result["is_active"] is True

    def test_get_call_state_returns_expected_keys(self):
        """get_call_state() should return all expected keys."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        result = manager.get_call_state()
        assert "status" in result
        assert "is_active" in result
        assert "is_muted" in result
        assert "profile" in result


class TestCallManagerPacketForwarding:
    """Test audio and signal forwarding between Kotlin and Reticulum."""

    def test_receive_audio_packet_drops_when_no_active_call(self):
        """receive_audio_packet() should drop packets when no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.active_call = None
        manager.receive_audio_packet(b'\x40\x00\x01')  # should not raise

    def test_receive_audio_packet_sends_to_remote(self):
        """receive_audio_packet() should send msgpack-wrapped audio to remote."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE  # ACTIVE
        manager.active_call = mock_link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            manager.receive_audio_packet(b'\x40\x00\x01')
            mock_rns.Packet.assert_called_once()

    def test_receive_signal_drops_when_no_active_call(self):
        """receive_signal() should do nothing when no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager.active_call = None
        manager.receive_signal(STATUS_ESTABLISHED)  # should not raise

    def test_send_audio_packet_forwards_to_kotlin(self):
        """send_audio_packet() should forward to kotlin_network_bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_bridge = MagicMock()
        manager._kotlin_network_bridge = mock_bridge

        manager.send_audio_packet(b'\x40\x00\x01')
        mock_bridge.onInboundPacket.assert_called_once_with(b'\x40\x00\x01')

    def test_send_signal_forwards_to_kotlin(self):
        """send_signal() should forward to kotlin_network_bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_bridge = MagicMock()
        manager._kotlin_network_bridge = mock_bridge

        manager.send_signal(STATUS_RINGING)
        mock_bridge.onInboundSignal.assert_called_once_with(STATUS_RINGING)


class TestModuleLevelFunctions:
    """Test module-level functions for global CallManager."""

    def test_get_call_manager_returns_none_initially(self):
        """get_call_manager() should return None before initialization."""
        shutdown_call_manager()
        result = get_call_manager()
        assert result is None

    def test_initialize_call_manager_creates_manager(self):
        """initialize_call_manager() should create a CallManager."""
        shutdown_call_manager()
        mock_identity = MagicMock()
        result = initialize_call_manager(mock_identity)
        assert result is not None
        assert isinstance(result, CallManager)
        shutdown_call_manager()

    def test_initialize_call_manager_returns_existing_if_already_initialized(self):
        """initialize_call_manager() should return existing if already initialized."""
        shutdown_call_manager()
        mock_identity = MagicMock()
        result1 = initialize_call_manager(mock_identity)
        result2 = initialize_call_manager(mock_identity)
        assert result1 is result2
        shutdown_call_manager()

    def test_shutdown_call_manager_clears_global(self):
        """shutdown_call_manager() should clear global manager."""
        mock_identity = MagicMock()
        initialize_call_manager(mock_identity)
        shutdown_call_manager()
        assert get_call_manager() is None

    def test_initialize_passes_bridges(self):
        """initialize_call_manager() should pass bridges to CallManager."""
        shutdown_call_manager()
        mock_identity = MagicMock()
        mock_bridge = MagicMock()
        mock_net_bridge = MagicMock()
        result = initialize_call_manager(
            mock_identity,
            kotlin_call_bridge=mock_bridge,
            kotlin_network_bridge=mock_net_bridge,
        )
        assert result._kotlin_call_bridge == mock_bridge
        assert result._kotlin_network_bridge == mock_net_bridge
        shutdown_call_manager()
