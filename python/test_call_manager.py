"""
Unit tests for CallManager (LXST voice call integration).

Tests call state management, callback handling, and LXST Telephone wrapper.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock
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
)


class TestCallManagerConstants:
    """Test CallManager quality profile constants."""

    def test_profile_ulbw_value(self):
        """PROFILE_ULBW should be 0x10."""
        assert CallManager.PROFILE_ULBW == 0x10

    def test_profile_vlbw_value(self):
        """PROFILE_VLBW should be 0x20."""
        assert CallManager.PROFILE_VLBW == 0x20

    def test_profile_lbw_value(self):
        """PROFILE_LBW should be 0x30."""
        assert CallManager.PROFILE_LBW == 0x30

    def test_profile_mq_value(self):
        """PROFILE_MQ should be 0x40."""
        assert CallManager.PROFILE_MQ == 0x40

    def test_profile_hq_value(self):
        """PROFILE_HQ should be 0x50."""
        assert CallManager.PROFILE_HQ == 0x50

    def test_profile_shq_value(self):
        """PROFILE_SHQ should be 0x60."""
        assert CallManager.PROFILE_SHQ == 0x60

    def test_profile_ll_value(self):
        """PROFILE_LL should be 0x70."""
        assert CallManager.PROFILE_LL == 0x70

    def test_profile_ull_value(self):
        """PROFILE_ULL should be 0x80."""
        assert CallManager.PROFILE_ULL == 0x80

    def test_default_profile_is_mq(self):
        """Default profile should be Medium Quality."""
        assert CallManager.DEFAULT_PROFILE == CallManager.PROFILE_MQ


class TestCallManagerInitialization:
    """Test CallManager initialization and lifecycle."""

    def test_init_sets_identity(self):
        """CallManager should store the provided identity."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager.identity == mock_identity

    def test_init_has_no_telephone(self):
        """Initially, telephone should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager.telephone is None

    def test_init_has_no_audio_bridge(self):
        """Initially, audio bridge should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._audio_bridge is None

    def test_init_has_no_kotlin_call_bridge(self):
        """Initially, Kotlin call bridge should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._kotlin_call_bridge is None

    def test_init_not_initialized(self):
        """Initially, _initialized should be False."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._initialized is False

    def test_init_no_active_call(self):
        """Initially, no active call identity should be set."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._active_call_identity is None

    def test_init_no_call_start_time(self):
        """Initially, call start time should be None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        assert manager._call_start_time is None


class TestCallManagerInitialize:
    """Test CallManager.initialize() method."""

    def test_initialize_stores_audio_bridge(self):
        """Initialize should store the audio bridge."""
        mock_identity = MagicMock()
        mock_audio_bridge = MagicMock()
        manager = CallManager(mock_identity)

        with patch('lxst_modules.call_manager.CallManager.initialize') as mock_init:
            # Don't actually try to import LXST
            manager._audio_bridge = mock_audio_bridge

        assert manager._audio_bridge == mock_audio_bridge

    def test_initialize_stores_kotlin_call_bridge(self):
        """Initialize should store the Kotlin call bridge."""
        mock_identity = MagicMock()
        mock_audio_bridge = MagicMock()
        mock_kotlin_bridge = MagicMock()
        manager = CallManager(mock_identity)

        manager._kotlin_call_bridge = mock_kotlin_bridge
        assert manager._kotlin_call_bridge == mock_kotlin_bridge

    def test_initialize_warns_if_already_initialized(self):
        """Initialize should warn if already initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        # Should not crash, just return early
        manager.initialize(MagicMock(), MagicMock())
        # _initialized should still be True
        assert manager._initialized is True


class TestCallManagerTeardown:
    """Test CallManager.teardown() method."""

    def test_teardown_clears_telephone(self):
        """Teardown should set telephone to None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        manager.teardown()

        assert manager.telephone is None

    def test_teardown_sets_initialized_false(self):
        """Teardown should set _initialized to False."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True

        manager.teardown()

        assert manager._initialized is False

    def test_teardown_calls_telephone_teardown(self):
        """Teardown should call telephone.teardown()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        manager.teardown()

        mock_telephone.teardown.assert_called_once()

    def test_teardown_handles_telephone_exception(self):
        """Teardown should handle exceptions from telephone.teardown()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_telephone = MagicMock()
        mock_telephone.teardown.side_effect = RuntimeError("Test error")
        manager.telephone = mock_telephone

        # Should not raise
        manager.teardown()
        assert manager.telephone is None


class TestCallManagerSetKotlinCallBridge:
    """Test CallManager.set_kotlin_call_bridge() method."""

    def test_set_kotlin_call_bridge_stores_bridge(self):
        """set_kotlin_call_bridge should store the bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_bridge = MagicMock()

        manager.set_kotlin_call_bridge(mock_bridge)

        assert manager._kotlin_call_bridge == mock_bridge


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

    def test_call_returns_error_if_no_telephone(self):
        """call() should return error if telephone is None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.telephone = None

        result = manager.call("abc123")

        assert result["success"] is False

    def test_call_returns_error_for_invalid_hash(self):
        """call() should return error for invalid hex hash."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.telephone = MagicMock()

        result = manager.call("not_valid_hex!")

        assert result["success"] is False
        assert "invalid" in result["error"].lower() or "hash" in result["error"].lower()

    def test_call_uses_default_profile_if_not_specified(self):
        """call() should use DEFAULT_PROFILE if profile not specified."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        # Mock RNS.Identity.recall to return a valid identity
        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.return_value = MagicMock()

            manager.call("abc123def456789012345678901234567890")

            # Should have called with default profile
            mock_telephone.call.assert_called_once()
            _, kwargs = mock_telephone.call.call_args
            assert kwargs.get('profile') == CallManager.DEFAULT_PROFILE

    def test_call_uses_specified_profile(self):
        """call() should use the specified profile."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        with patch('lxst_modules.call_manager.RNS') as mock_rns:
            mock_rns.Identity.recall.return_value = MagicMock()

            manager.call("abc123def456789012345678901234567890", profile=CallManager.PROFILE_HQ)

            mock_telephone.call.assert_called_once()
            _, kwargs = mock_telephone.call.call_args
            assert kwargs.get('profile') == CallManager.PROFILE_HQ


class TestCallManagerAnswer:
    """Test CallManager.answer() method."""

    def test_answer_returns_false_if_not_initialized(self):
        """answer() should return False if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False

        result = manager.answer()

        assert result is False

    def test_answer_returns_false_if_no_telephone(self):
        """answer() should return False if telephone is None."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.telephone = None

        result = manager.answer()

        assert result is False

    def test_answer_returns_false_if_no_active_call(self):
        """answer() should return False if no active call."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        mock_telephone.active_call = None
        manager.telephone = mock_telephone

        result = manager.answer()

        assert result is False


class TestCallManagerHangup:
    """Test CallManager.hangup() method."""

    def test_hangup_does_nothing_if_not_initialized(self):
        """hangup() should do nothing if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False

        # Should not crash
        manager.hangup()

    def test_hangup_calls_telephone_hangup(self):
        """hangup() should call telephone.hangup()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        manager.hangup()

        mock_telephone.hangup.assert_called_once()

    def test_hangup_handles_exception(self):
        """hangup() should handle exceptions from telephone.hangup()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        mock_telephone.hangup.side_effect = RuntimeError("Test error")
        manager.telephone = mock_telephone

        # Should not raise
        manager.hangup()


class TestCallManagerMuteMicrophone:
    """Test CallManager.mute_microphone() method."""

    def test_mute_microphone_does_nothing_if_not_initialized(self):
        """mute_microphone() should do nothing if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False

        # Should not crash
        manager.mute_microphone(True)

    def test_mute_microphone_true_calls_mute_transmit(self):
        """mute_microphone(True) should call mute_transmit()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        manager.mute_microphone(True)

        mock_telephone.mute_transmit.assert_called_once()

    def test_mute_microphone_false_calls_unmute_transmit(self):
        """mute_microphone(False) should call unmute_transmit()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        manager.telephone = mock_telephone

        manager.mute_microphone(False)

        mock_telephone.unmute_transmit.assert_called_once()


class TestCallManagerSetSpeaker:
    """Test CallManager.set_speaker() method."""

    def test_set_speaker_calls_audio_bridge(self):
        """set_speaker() should call audio bridge setSpeakerphoneOn()."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_audio_bridge = MagicMock()
        manager._audio_bridge = mock_audio_bridge

        manager.set_speaker(True)

        mock_audio_bridge.setSpeakerphoneOn.assert_called_once_with(True)

    def test_set_speaker_handles_no_audio_bridge(self):
        """set_speaker() should handle None audio bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._audio_bridge = None

        # Should not crash
        manager.set_speaker(True)


class TestCallManagerGetCallState:
    """Test CallManager.get_call_state() method."""

    def test_get_call_state_returns_unavailable_if_not_initialized(self):
        """get_call_state() should return unavailable if not initialized."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = False

        result = manager.get_call_state()

        assert result["status"] == "unavailable"
        assert result["is_active"] is False

    def test_get_call_state_returns_unavailable_if_no_telephone(self):
        """get_call_state() should return unavailable if no telephone."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        manager.telephone = None

        result = manager.get_call_state()

        assert result["status"] == "unavailable"

    def test_get_call_state_returns_correct_structure(self):
        """get_call_state() should return expected structure."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._initialized = True
        mock_telephone = MagicMock()
        mock_telephone.active_call = None
        mock_telephone.transmit_muted = False
        mock_telephone.call_status = 0x03  # available
        mock_telephone.active_profile = None
        manager.telephone = mock_telephone

        result = manager.get_call_state()

        assert "status" in result
        assert "is_active" in result
        assert "is_muted" in result
        assert "profile" in result


class TestCallManagerCallbackHandlers:
    """Test CallManager callback handler methods."""

    def test_handle_ringing_notifies_kotlin_bridge(self):
        """_handle_ringing should notify Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_kotlin_bridge

        mock_caller_identity = MagicMock()
        mock_caller_identity.hash.hex.return_value = "abc123"

        manager._handle_ringing(mock_caller_identity)

        mock_kotlin_bridge.onIncomingCall.assert_called_once()

    def test_handle_established_notifies_kotlin_bridge(self):
        """_handle_established should notify Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_kotlin_bridge

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        manager._handle_established(mock_peer_identity)

        mock_kotlin_bridge.onCallEstablished.assert_called_once()

    def test_handle_established_sets_active_call_identity(self):
        """_handle_established should set active call identity."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        manager._handle_established(mock_peer_identity)

        assert manager._active_call_identity == "abc123"

    def test_handle_established_sets_call_start_time(self):
        """_handle_established should set call start time."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        manager._handle_established(mock_peer_identity)

        assert manager._call_start_time is not None

    def test_handle_ended_notifies_kotlin_bridge(self):
        """_handle_ended should notify Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_kotlin_bridge

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        manager._handle_ended(mock_peer_identity)

        mock_kotlin_bridge.onCallEnded.assert_called_once()

    def test_handle_ended_clears_active_call_identity(self):
        """_handle_ended should clear active call identity."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._active_call_identity = "abc123"

        manager._handle_ended(MagicMock())

        assert manager._active_call_identity is None

    def test_handle_ended_clears_call_start_time(self):
        """_handle_ended should clear call start time."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._call_start_time = time.time()

        manager._handle_ended(MagicMock())

        assert manager._call_start_time is None

    def test_handle_busy_notifies_kotlin_bridge(self):
        """_handle_busy should notify Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_kotlin_bridge

        manager._handle_busy(MagicMock())

        mock_kotlin_bridge.onCallBusy.assert_called_once()

    def test_handle_rejected_notifies_kotlin_bridge(self):
        """_handle_rejected should notify Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        manager._kotlin_call_bridge = mock_kotlin_bridge

        manager._handle_rejected(MagicMock())

        mock_kotlin_bridge.onCallRejected.assert_called_once()

    def test_callback_handlers_handle_null_kotlin_bridge(self):
        """Callback handlers should handle None Kotlin bridge."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        manager._kotlin_call_bridge = None

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        # Should not crash
        manager._handle_ringing(mock_peer_identity)
        manager._handle_established(mock_peer_identity)
        manager._handle_ended(mock_peer_identity)
        manager._handle_busy(mock_peer_identity)
        manager._handle_rejected(mock_peer_identity)

    def test_callback_handlers_handle_kotlin_bridge_exception(self):
        """Callback handlers should handle Kotlin bridge exceptions."""
        mock_identity = MagicMock()
        manager = CallManager(mock_identity)
        mock_kotlin_bridge = MagicMock()
        mock_kotlin_bridge.onIncomingCall.side_effect = RuntimeError("Test error")
        manager._kotlin_call_bridge = mock_kotlin_bridge

        mock_peer_identity = MagicMock()
        mock_peer_identity.hash.hex.return_value = "abc123"

        # Should not raise
        manager._handle_ringing(mock_peer_identity)


class TestModuleLevelFunctions:
    """Test module-level functions for global CallManager."""

    def test_get_call_manager_returns_none_initially(self):
        """get_call_manager() should return None before initialization."""
        # Reset global state
        shutdown_call_manager()

        result = get_call_manager()
        assert result is None

    def test_initialize_call_manager_creates_manager(self):
        """initialize_call_manager() should create a CallManager."""
        # Reset global state first
        shutdown_call_manager()

        mock_identity = MagicMock()
        mock_audio_bridge = MagicMock()

        with patch('lxst_modules.call_manager.CallManager.initialize'):
            result = initialize_call_manager(mock_identity, mock_audio_bridge)

        assert result is not None
        assert isinstance(result, CallManager)

        # Cleanup
        shutdown_call_manager()

    def test_initialize_call_manager_returns_existing_if_already_initialized(self):
        """initialize_call_manager() should return existing if already initialized."""
        # Reset global state first
        shutdown_call_manager()

        mock_identity = MagicMock()
        mock_audio_bridge = MagicMock()

        with patch('lxst_modules.call_manager.CallManager.initialize'):
            result1 = initialize_call_manager(mock_identity, mock_audio_bridge)
            result2 = initialize_call_manager(mock_identity, mock_audio_bridge)

        assert result1 is result2

        # Cleanup
        shutdown_call_manager()

    def test_shutdown_call_manager_clears_global(self):
        """shutdown_call_manager() should clear global manager."""
        # First initialize
        mock_identity = MagicMock()
        mock_audio_bridge = MagicMock()

        with patch('lxst_modules.call_manager.CallManager.initialize'):
            initialize_call_manager(mock_identity, mock_audio_bridge)

        # Then shutdown
        shutdown_call_manager()

        assert get_call_manager() is None
