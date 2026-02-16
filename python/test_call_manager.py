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
    STATUS_CONNECTING,
    FIELD_SIGNALLING,
    FIELD_FRAMES,
    RNS,
    umsgpack,
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
        """receive_audio_packet() should send msgpack-wrapped audio to remote after batch fills."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_link = MagicMock()
        mock_link.status = RNS.Link.ACTIVE  # ACTIVE
        manager.active_call = mock_link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            # Send TX_BATCH_SIZE packets to trigger batch flush
            for _ in range(CallManager.TX_BATCH_SIZE):
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


# ===== Helpers for callback tests =====

def _make_initialized_manager(**kwargs):
    """Create an initialized CallManager with optional bridges."""
    mock_identity = MagicMock()
    manager = CallManager(mock_identity)
    manager.initialize(
        kotlin_call_bridge=kwargs.get("call_bridge"),
        kotlin_network_bridge=kwargs.get("network_bridge"),
    )
    if "telephone_callback" in kwargs:
        manager.set_kotlin_telephone_callback(kwargs["telephone_callback"])
    return manager


def _make_active_link():
    """Create a mock RNS.Link that reports ACTIVE status."""
    link = MagicMock()
    link.status = RNS.Link.ACTIVE
    return link


# ===== Reticulum Callback Tests =====

class TestIncomingLinkEstablished:
    """Test __incoming_link_established callback."""

    def test_accepts_incoming_link_when_idle(self):
        """Should set callbacks on the link and send STATUS_AVAILABLE."""
        manager = _make_initialized_manager()
        link = _make_active_link()

        # Access private method via name mangling
        manager._CallManager__incoming_link_established(link)

        link.set_remote_identified_callback.assert_called_once()
        link.set_link_closed_callback.assert_called_once()

    def test_rejects_incoming_link_when_busy(self):
        """Should send STATUS_BUSY and teardown if already in a call."""
        manager = _make_initialized_manager()
        manager.active_call = _make_active_link()  # already in call

        new_link = _make_active_link()
        manager._CallManager__incoming_link_established(new_link)

        new_link.teardown.assert_called_once()

    def test_rejects_incoming_link_when_busy_flag_set(self):
        """Should reject if _busy flag is set."""
        manager = _make_initialized_manager()
        manager._busy = True

        link = _make_active_link()
        manager._CallManager__incoming_link_established(link)

        link.teardown.assert_called_once()


class TestCallerIdentified:
    """Test __caller_identified callback."""

    def test_accepts_identified_caller(self):
        """Should accept call and notify Kotlin when caller is identified."""
        mock_call_bridge = MagicMock()
        mock_callback = MagicMock()
        manager = _make_initialized_manager(
            call_bridge=mock_call_bridge,
            telephone_callback=mock_callback,
        )

        link = _make_active_link()
        identity = MagicMock()
        identity.hash.hex.return_value = "abcd1234deadbeef"

        manager._CallManager__caller_identified(link, identity)

        assert manager.active_call == link
        assert manager._active_call_identity == "abcd1234deadbeef"
        link.set_packet_callback.assert_called_once()
        mock_call_bridge.onIncomingCall.assert_called_once_with("abcd1234deadbeef")

    def test_rejects_caller_when_busy(self):
        """Should reject if already in a call."""
        manager = _make_initialized_manager()
        manager.active_call = _make_active_link()

        link = _make_active_link()
        identity = MagicMock()

        manager._CallManager__caller_identified(link, identity)

        link.teardown.assert_called_once()

    def test_rejects_caller_when_busy_flag(self):
        """Should reject if _busy flag is set."""
        manager = _make_initialized_manager()
        manager._busy = True

        link = _make_active_link()
        identity = MagicMock()

        manager._CallManager__caller_identified(link, identity)

        link.teardown.assert_called_once()

    def test_notifies_kotlin_callback_on_ringing(self):
        """Should notify Kotlin telephone callback with 'ringing' event."""
        mock_callback = MagicMock()
        manager = _make_initialized_manager(telephone_callback=mock_callback)

        link = _make_active_link()
        identity = MagicMock()
        identity.hash.hex.return_value = "abcd1234"

        manager._CallManager__caller_identified(link, identity)

        mock_callback.assert_called_once()
        args = mock_callback.call_args[0]
        assert args[0] == "ringing"
        assert args[1]["identity"] == "abcd1234"

    def test_handles_kotlin_bridge_exception(self):
        """Should not crash if Kotlin bridge raises."""
        mock_bridge = MagicMock()
        mock_bridge.onIncomingCall.side_effect = RuntimeError("bridge error")
        manager = _make_initialized_manager(call_bridge=mock_bridge)

        link = _make_active_link()
        identity = MagicMock()
        identity.hash.hex.return_value = "abcd1234"

        manager._CallManager__caller_identified(link, identity)
        # Should not raise — error is caught internally


class TestOutgoingLinkEstablished:
    """Test __outgoing_link_established callback."""

    def test_sets_packet_and_close_callbacks(self):
        """Should configure packet and close callbacks on the link."""
        manager = _make_initialized_manager()
        link = _make_active_link()

        manager._CallManager__outgoing_link_established(link)

        link.set_packet_callback.assert_called_once()
        link.set_link_closed_callback.assert_called_once()


class TestLinkClosed:
    """Test __link_closed callback."""

    def test_clears_active_call_when_matching(self):
        """Should clear active_call when closed link matches."""
        mock_bridge = MagicMock()
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(
            call_bridge=mock_bridge, network_bridge=mock_net_bridge,
        )
        link = _make_active_link()
        manager.active_call = link
        manager._active_call_identity = "abc123"

        manager._CallManager__link_closed(link)

        assert manager.active_call is None
        assert manager._active_call_identity is None
        mock_bridge.onCallEnded.assert_called_once_with("abc123")

    def test_ignores_non_matching_link(self):
        """Should ignore close if link doesn't match active_call."""
        manager = _make_initialized_manager()
        active = _make_active_link()
        other = _make_active_link()
        manager.active_call = active

        manager._CallManager__link_closed(other)

        assert manager.active_call is active  # not cleared

    def test_sends_available_signal_to_kotlin(self):
        """Should forward STATUS_AVAILABLE to Kotlin on link close."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)
        link = _make_active_link()
        manager.active_call = link

        manager._CallManager__link_closed(link)

        mock_net_bridge.onInboundSignal.assert_called_once_with(STATUS_AVAILABLE)

    def test_handles_bridge_exception(self):
        """Should not crash if Kotlin bridge raises on close."""
        mock_bridge = MagicMock()
        mock_bridge.onCallEnded.side_effect = RuntimeError("boom")
        manager = _make_initialized_manager(call_bridge=mock_bridge)
        link = _make_active_link()
        manager.active_call = link

        manager._CallManager__link_closed(link)  # should not raise
        assert manager.active_call is None


class TestPacketReceived:
    """Test __packet_received callback (msgpack unpacking + routing).

    Note: umsgpack is a MagicMock (conftest replaces sys.modules['RNS']),
    so we patch unpackb to return real dicts for these tests.
    """

    def test_forwards_audio_frame_to_kotlin(self):
        """Should unpack frames and forward to kotlin_network_bridge."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        frame_data = b'\x40\x00\x01\x02'
        payload = {FIELD_FRAMES: frame_data}

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = payload
            manager._CallManager__packet_received(b'dummy', MagicMock())

        mock_net_bridge.onInboundPacket.assert_called_once_with(bytes(frame_data))

    def test_forwards_multiple_frames(self):
        """Should forward each frame in a frame list."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        frames = [b'\x40\x01', b'\x40\x02']
        payload = {FIELD_FRAMES: frames}

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = payload
            manager._CallManager__packet_received(b'dummy', MagicMock())

        assert mock_net_bridge.onInboundPacket.call_count == 2

    def test_forwards_signal_to_kotlin(self):
        """Should unpack signalling and forward to Kotlin."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        payload = {FIELD_SIGNALLING: [STATUS_RINGING]}

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = payload
            manager._CallManager__packet_received(b'dummy', MagicMock())

        mock_net_bridge.onInboundSignal.assert_called_once_with(STATUS_RINGING)

    def test_handles_combined_audio_and_signal(self):
        """Should handle packet with both frames and signalling."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        payload = {
            FIELD_FRAMES: b'\x40\x01',
            FIELD_SIGNALLING: STATUS_ESTABLISHED,
        }

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = payload
            manager._CallManager__packet_received(b'dummy', MagicMock())

        mock_net_bridge.onInboundPacket.assert_called_once()
        mock_net_bridge.onInboundSignal.assert_called_once_with(STATUS_ESTABLISHED)

    def test_ignores_non_dict_packet(self):
        """Should silently ignore non-dict msgpack payloads."""
        manager = _make_initialized_manager()

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = [1, 2, 3]  # list, not dict
            manager._CallManager__packet_received(b'dummy', MagicMock())
        # Should not raise

    def test_handles_unpack_exception(self):
        """Should not crash on malformed data."""
        manager = _make_initialized_manager()

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.side_effect = ValueError("bad data")
            manager._CallManager__packet_received(b'\xff\xfe', MagicMock())
        # Should not raise

    def test_handles_bridge_exception_on_frame(self):
        """Should not crash if Kotlin bridge raises on frame forward."""
        mock_net_bridge = MagicMock()
        mock_net_bridge.onInboundPacket.side_effect = RuntimeError("boom")
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        payload = {FIELD_FRAMES: b'\x40\x01'}

        with patch('lxst_modules.call_manager.umsgpack') as mock_msgpack:
            mock_msgpack.unpackb.return_value = payload
            manager._CallManager__packet_received(b'dummy', MagicMock())
        # Should not raise


class TestHandleRemoteSignal:
    """Test _handle_remote_signal (signal routing logic)."""

    def test_available_signal_triggers_identify(self):
        """STATUS_AVAILABLE from remote should trigger link.identify()."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        manager._handle_remote_signal(STATUS_AVAILABLE)

        link.identify.assert_called_once_with(manager.identity)

    def test_available_signal_ignored_without_active_call(self):
        """STATUS_AVAILABLE should not crash when no active call."""
        manager = _make_initialized_manager()
        manager.active_call = None

        manager._handle_remote_signal(STATUS_AVAILABLE)
        # Should not raise

    def test_all_signals_forwarded_to_kotlin(self):
        """Every signal should be forwarded to Kotlin via network bridge."""
        mock_net_bridge = MagicMock()
        manager = _make_initialized_manager(network_bridge=mock_net_bridge)

        manager._handle_remote_signal(STATUS_RINGING)

        mock_net_bridge.onInboundSignal.assert_called_once_with(STATUS_RINGING)

    def test_non_available_signal_does_not_identify(self):
        """Signals other than AVAILABLE should not trigger identify."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        manager._handle_remote_signal(STATUS_ESTABLISHED)

        link.identify.assert_not_called()


class TestSendSignalToRemote:
    """Test _send_signal_to_remote (wire format)."""

    def test_sends_signal_over_active_link(self):
        """Should send msgpack-wrapped signal via RNS.Packet."""
        manager = _make_initialized_manager()
        link = _make_active_link()

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            manager._send_signal_to_remote(STATUS_RINGING, link)
            mock_rns.Packet.assert_called_once()

    def test_skips_inactive_link(self):
        """Should not send if link is not ACTIVE."""
        manager = _make_initialized_manager()
        link = MagicMock()
        link.status = MagicMock()  # not equal to RNS.Link.ACTIVE

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            manager._send_signal_to_remote(STATUS_RINGING, link)
            mock_rns.Packet.assert_not_called()

    def test_handles_send_exception(self):
        """Should not crash on send failure."""
        manager = _make_initialized_manager()
        link = _make_active_link()

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            mock_rns.Packet.side_effect = RuntimeError("send error")
            manager._send_signal_to_remote(STATUS_RINGING, link)
            # Should not raise


class TestReceiveAudioPacketFull:
    """Test receive_audio_packet with active link (wire format)."""

    def test_sends_msgpack_wrapped_audio(self):
        """Should wrap audio in LXST wire format and send via RNS.Packet after batch fills."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            # Send TX_BATCH_SIZE packets to trigger batch flush
            for _ in range(CallManager.TX_BATCH_SIZE):
                manager.receive_audio_packet(b'\x40\x00\x01')

            mock_rns.Packet.assert_called_once()
            call_args = mock_rns.Packet.call_args
            assert call_args[0][0] == link  # link is first arg

    def test_drops_when_link_not_active(self):
        """Should drop packet when link is not ACTIVE."""
        manager = _make_initialized_manager()
        link = MagicMock()
        link.status = MagicMock()  # not ACTIVE
        manager.active_call = link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            manager.receive_audio_packet(b'\x40\x00\x01')
            mock_rns.Packet.assert_not_called()

    def test_converts_non_bytes_to_bytes(self):
        """Should handle non-bytes input (Chaquopy jarray) and send after batch fills."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            # Pass lists (simulates jarray) — TX_BATCH_SIZE to trigger flush
            for _ in range(CallManager.TX_BATCH_SIZE):
                manager.receive_audio_packet([0x40, 0x00, 0x01])
            mock_rns.Packet.assert_called_once()

    def test_handles_send_exception(self):
        """Should not crash on send failure."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            mock_rns.Packet.side_effect = RuntimeError("send error")
            manager.receive_audio_packet(b'\x40\x00\x01')
            # Should not raise


class TestReceiveSignalFull:
    """Test receive_signal with active link."""

    def test_sends_signal_to_remote(self):
        """Should send signal to remote via _send_signal_to_remote."""
        manager = _make_initialized_manager()
        link = _make_active_link()
        manager.active_call = link

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Link.ACTIVE = RNS.Link.ACTIVE
            manager.receive_signal(STATUS_ESTABLISHED)
            mock_rns.Packet.assert_called_once()


class TestNotifyKotlin:
    """Test _notify_kotlin helper."""

    def test_calls_callback_with_event_and_identity(self):
        """Should invoke callback with event name and identity dict."""
        mock_callback = MagicMock()
        manager = _make_initialized_manager(telephone_callback=mock_callback)

        manager._notify_kotlin("ringing", "abc123")

        mock_callback.assert_called_once_with("ringing", {"identity": "abc123"})

    def test_includes_extra_data(self):
        """Should merge extra data into callback payload."""
        mock_callback = MagicMock()
        manager = _make_initialized_manager(telephone_callback=mock_callback)

        manager._notify_kotlin("established", "abc123", extra={"profile": 0x40})

        args = mock_callback.call_args[0]
        assert args[1]["identity"] == "abc123"
        assert args[1]["profile"] == 0x40

    def test_does_nothing_without_callback(self):
        """Should not crash when no callback is set."""
        manager = _make_initialized_manager()
        manager._notify_kotlin("ringing", "abc123")
        # Should not raise

    def test_handles_callback_exception(self):
        """Should not crash if callback raises."""
        mock_callback = MagicMock()
        mock_callback.side_effect = RuntimeError("callback error")
        manager = _make_initialized_manager(telephone_callback=mock_callback)

        manager._notify_kotlin("ringing", "abc123")
        # Should not raise


class TestIsAllowed:
    """Test _is_allowed (caller filtering)."""

    def test_allows_any_caller(self):
        """Current implementation allows all callers."""
        manager = _make_initialized_manager()
        identity = MagicMock()
        identity.hash.hex.return_value = "abc123"

        assert manager._is_allowed(identity) is True


class TestInitializeExceptionHandling:
    """Test initialize() error handling."""

    def test_initialize_handles_destination_exception(self):
        """Should handle exception during destination creation."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Destination.side_effect = RuntimeError("init error")
            mock_rns.LOG_ERROR = 3
            mock_rns.LOG_INFO = 1
            mock_rns.LOG_WARNING = 2
            manager.initialize()

        assert manager._initialized is False


class TestAnswerExceptionHandling:
    """Test answer() exception path."""

    def test_answer_handles_identity_exception(self):
        """Should return False if get_remote_identity raises."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_link = MagicMock()
        mock_link.get_remote_identity.side_effect = RuntimeError("ident error")
        manager.active_call = mock_link

        assert manager.answer() is False


class TestCallPathDiscovery:
    """Test call() path discovery edge cases."""

    def test_call_handles_general_exception(self):
        """call() should catch and report unexpected exceptions."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.side_effect = RuntimeError("unexpected")
            result = manager.call("abc123def456789012345678901234567890")

        assert result["success"] is False
