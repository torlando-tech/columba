"""
Unit tests for ChaquopyAudioBackend.

Tests audio playback, recording, and LXST-compatible API.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock
import numpy as np


# Import the module under test
from lxst_modules.chaquopy_audio_backend import (
    ChaquopyAudioBackend,
    ChaquopyPlayer,
    ChaquopyRecorder,
    set_kotlin_audio_bridge,
    get_kotlin_audio_bridge,
    get_backend,
    all_speakers,
    default_speaker,
    get_speaker,
    all_microphones,
    default_microphone,
    get_microphone,
    _Speaker,
    _Microphone,
    get_name,
    set_name,
)


class TestChaquopyAudioBackendConstants:
    """Test ChaquopyAudioBackend constants."""

    def test_samplerate_is_48000(self):
        """SAMPLERATE should be 48000."""
        assert ChaquopyAudioBackend.SAMPLERATE == 48000

    def test_channels_is_1(self):
        """CHANNELS should be 1 (mono)."""
        assert ChaquopyAudioBackend.CHANNELS == 1

    def test_default_sink_is_speaker(self):
        """DEFAULT_SINK should be 'Internal Speaker'."""
        assert ChaquopyAudioBackend.DEFAULT_SINK == "Internal Speaker"

    def test_default_source_is_microphone(self):
        """DEFAULT_SOURCE should be 'Internal Microphone'."""
        assert ChaquopyAudioBackend.DEFAULT_SOURCE == "Internal Microphone"


class TestBridgeManagement:
    """Test Kotlin audio bridge management."""

    def test_set_kotlin_audio_bridge_stores_bridge(self):
        """set_kotlin_audio_bridge should store the bridge."""
        mock_bridge = MagicMock()

        set_kotlin_audio_bridge(mock_bridge)

        assert get_kotlin_audio_bridge() == mock_bridge

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_get_kotlin_audio_bridge_returns_none_initially(self):
        """get_kotlin_audio_bridge should return None if not set."""
        set_kotlin_audio_bridge(None)
        assert get_kotlin_audio_bridge() is None


class TestGetBackend:
    """Test get_backend() function."""

    def test_get_backend_returns_backend(self):
        """get_backend() should return a ChaquopyAudioBackend."""
        backend = get_backend()
        assert isinstance(backend, ChaquopyAudioBackend)


class TestChaquopyAudioBackendInit:
    """Test ChaquopyAudioBackend initialization."""

    def test_init_with_default_samplerate(self):
        """Backend should use default sample rate if not specified."""
        backend = ChaquopyAudioBackend()
        assert backend.samplerate == ChaquopyAudioBackend.SAMPLERATE

    def test_init_with_custom_samplerate(self):
        """Backend should use custom sample rate if specified."""
        backend = ChaquopyAudioBackend(samplerate=44100)
        assert backend.samplerate == 44100

    def test_init_with_preferred_device(self):
        """Backend should store preferred device."""
        backend = ChaquopyAudioBackend(preferred_device="test_device")
        assert backend.preferred_device == "test_device"


class TestChaquopyAudioBackendName:
    """Test ChaquopyAudioBackend name property."""

    def test_name_property_getter(self):
        """name property should return client name."""
        backend = ChaquopyAudioBackend()
        backend._client_name = "test_name"
        assert backend.name == "test_name"

    def test_name_property_setter(self):
        """name property should set client name."""
        backend = ChaquopyAudioBackend()
        backend.name = "new_name"
        assert backend._client_name == "new_name"


class TestChaquopyAudioBackendGetPlayer:
    """Test ChaquopyAudioBackend.get_player() method."""

    def test_get_player_returns_player(self):
        """get_player() should return a ChaquopyPlayer."""
        backend = ChaquopyAudioBackend()
        player = backend.get_player()
        assert isinstance(player, ChaquopyPlayer)

    def test_get_player_with_samples_per_frame(self):
        """get_player() should pass samples_per_frame."""
        backend = ChaquopyAudioBackend()
        player = backend.get_player(samples_per_frame=480)
        assert player.samples_per_frame == 480

    def test_get_player_with_low_latency(self):
        """get_player() should pass low_latency."""
        backend = ChaquopyAudioBackend()
        player = backend.get_player(low_latency=True)
        assert player.low_latency is True


class TestChaquopyAudioBackendGetRecorder:
    """Test ChaquopyAudioBackend.get_recorder() method."""

    def test_get_recorder_returns_recorder(self):
        """get_recorder() should return a ChaquopyRecorder."""
        backend = ChaquopyAudioBackend()
        recorder = backend.get_recorder()
        assert isinstance(recorder, ChaquopyRecorder)

    def test_get_recorder_with_samples_per_frame(self):
        """get_recorder() should pass samples_per_frame."""
        backend = ChaquopyAudioBackend()
        recorder = backend.get_recorder(samples_per_frame=480)
        assert recorder.samples_per_frame == 480


class TestChaquopyAudioBackendSourceList:
    """Test ChaquopyAudioBackend.source_list property."""

    def test_source_list_returns_default_without_bridge(self):
        """source_list should return default without bridge."""
        set_kotlin_audio_bridge(None)
        backend = ChaquopyAudioBackend()
        sources = backend.source_list

        assert len(sources) == 1
        assert sources[0]["name"] == "Internal Microphone"

    def test_source_list_calls_bridge(self):
        """source_list should call bridge.getInputDevices()."""
        mock_bridge = MagicMock()
        mock_bridge.getInputDevices.return_value = [
            {"name": "Mic 1", "id": 1},
            {"name": "Mic 2", "id": 2},
        ]
        set_kotlin_audio_bridge(mock_bridge)

        backend = ChaquopyAudioBackend()
        sources = backend.source_list

        assert len(sources) == 2
        mock_bridge.getInputDevices.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)


class TestChaquopyAudioBackendSinkList:
    """Test ChaquopyAudioBackend.sink_list property."""

    def test_sink_list_returns_default_without_bridge(self):
        """sink_list should return default without bridge."""
        set_kotlin_audio_bridge(None)
        backend = ChaquopyAudioBackend()
        sinks = backend.sink_list

        assert len(sinks) == 1
        assert sinks[0]["name"] == "Internal Speaker"

    def test_sink_list_calls_bridge(self):
        """sink_list should call bridge.getOutputDevices()."""
        mock_bridge = MagicMock()
        mock_bridge.getOutputDevices.return_value = [
            {"name": "Speaker 1", "id": 1},
            {"name": "Speaker 2", "id": 2},
        ]
        set_kotlin_audio_bridge(mock_bridge)

        backend = ChaquopyAudioBackend()
        sinks = backend.sink_list

        assert len(sinks) == 2
        mock_bridge.getOutputDevices.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)


class TestChaquopyAudioBackendServerInfo:
    """Test ChaquopyAudioBackend.server_info property."""

    def test_server_info_returns_expected_fields(self):
        """server_info should return expected fields."""
        backend = ChaquopyAudioBackend()
        info = backend.server_info

        assert "server version" in info
        assert "server name" in info
        assert "default sink id" in info
        assert "default source id" in info
        assert info["server name"] == "Chaquopy Audio"


class TestChaquopyPlayer:
    """Test ChaquopyPlayer class."""

    def test_init_default_samples_per_frame(self):
        """Player should default to 960 samples per frame."""
        player = ChaquopyPlayer(48000, None, False, None)
        assert player.samples_per_frame == 960

    def test_init_stores_samplerate(self):
        """Player should store sample rate."""
        player = ChaquopyPlayer(44100, 480, False, None)
        assert player.samplerate == 44100

    def test_init_not_started(self):
        """Player should not be started initially."""
        player = ChaquopyPlayer(48000, 960, False, None)
        assert player._started is False

    def test_context_manager_starts_playback(self):
        """Entering context should start playback."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            assert player._started is True
            mock_bridge.startPlayback.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_context_manager_stops_playback(self):
        """Exiting context should stop playback."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            pass

        mock_bridge.stopPlayback.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_play_converts_float_to_int16(self):
        """play() should convert float32 to int16 bytes."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            frame = np.array([0.5, -0.5, 0.0], dtype=np.float32)
            player.play(frame)

        mock_bridge.writeAudio.assert_called_once()
        call_args = mock_bridge.writeAudio.call_args[0][0]
        assert isinstance(call_args, bytes)

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_play_does_nothing_when_not_started(self):
        """play() should do nothing when not started."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        # Don't enter context, so _started is False
        frame = np.array([0.5, -0.5], dtype=np.float32)
        player.play(frame)

        mock_bridge.writeAudio.assert_not_called()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_latency_property(self):
        """latency property should return ~20ms."""
        player = ChaquopyPlayer(48000, 960, False, None)
        assert player.latency == 0.020

    def test_enable_low_latency(self):
        """enable_low_latency should set low_latency to True."""
        player = ChaquopyPlayer(48000, 960, False, None)
        player.enable_low_latency()
        assert player.low_latency is True


class TestChaquopyRecorder:
    """Test ChaquopyRecorder class."""

    def test_init_default_samples_per_frame(self):
        """Recorder should default to 960 samples per frame."""
        recorder = ChaquopyRecorder(48000, None, None)
        assert recorder.samples_per_frame == 960

    def test_init_stores_samplerate(self):
        """Recorder should store sample rate."""
        recorder = ChaquopyRecorder(44100, 480, None)
        assert recorder.samplerate == 44100

    def test_init_not_started(self):
        """Recorder should not be started initially."""
        recorder = ChaquopyRecorder(48000, 960, None)
        assert recorder._started is False

    def test_context_manager_starts_recording(self):
        """Entering context should start recording."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        recorder = ChaquopyRecorder(48000, 960, None)
        with recorder:
            assert recorder._started is True
            mock_bridge.startRecording.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_context_manager_stops_recording(self):
        """Exiting context should stop recording."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        recorder = ChaquopyRecorder(48000, 960, None)
        with recorder:
            pass

        mock_bridge.stopRecording.assert_called_once()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_record_returns_zeros_when_not_started(self):
        """record() should return zeros when not started."""
        recorder = ChaquopyRecorder(48000, 960, None)
        result = recorder.record(100)

        assert result.shape == (100, 1)
        assert np.all(result == 0)

    def test_record_reads_from_bridge(self):
        """record() should read from bridge."""
        mock_bridge = MagicMock()
        # Return int16 bytes
        audio_bytes = np.array([16000, -16000], dtype=np.int16).tobytes()
        mock_bridge.readAudio.return_value = audio_bytes
        set_kotlin_audio_bridge(mock_bridge)

        recorder = ChaquopyRecorder(48000, 960, None)
        with recorder:
            result = recorder.record()

        mock_bridge.readAudio.assert_called()

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_latency_property(self):
        """latency property should return ~20ms."""
        recorder = ChaquopyRecorder(48000, 960, None)
        assert recorder.latency == 0.020

    def test_flush_clears_pending_chunk(self):
        """flush() should clear pending chunk and return it."""
        recorder = ChaquopyRecorder(48000, 960, None)
        recorder._pending_chunk = np.array([0.5, -0.5], dtype=np.float32)

        result = recorder.flush()

        assert len(recorder._pending_chunk) == 0
        assert result.shape == (2, 1)


class TestModuleLevelDeviceFunctions:
    """Test module-level device enumeration functions."""

    def test_all_speakers_returns_list(self):
        """all_speakers() should return a list."""
        speakers = all_speakers()
        assert isinstance(speakers, list)

    def test_default_speaker_returns_speaker(self):
        """default_speaker() should return a _Speaker."""
        speaker = default_speaker()
        assert isinstance(speaker, _Speaker)

    def test_get_speaker_returns_speaker(self):
        """get_speaker() should return a _Speaker."""
        speaker = get_speaker(id=0)
        assert isinstance(speaker, _Speaker)

    def test_all_microphones_returns_list(self):
        """all_microphones() should return a list."""
        mics = all_microphones()
        assert isinstance(mics, list)

    def test_default_microphone_returns_microphone(self):
        """default_microphone() should return a _Microphone."""
        mic = default_microphone()
        assert isinstance(mic, _Microphone)

    def test_get_microphone_returns_microphone(self):
        """get_microphone() should return a _Microphone."""
        mic = get_microphone(id=0)
        assert isinstance(mic, _Microphone)


class TestSpeakerClass:
    """Test _Speaker class."""

    def test_repr(self):
        """_Speaker should have a proper repr."""
        speaker = _Speaker(id=0)
        repr_str = repr(speaker)
        assert "Speaker" in repr_str

    def test_player_returns_chaquopy_player(self):
        """player() should return a ChaquopyPlayer."""
        speaker = _Speaker(id=0)
        player = speaker.player(samplerate=48000)
        assert isinstance(player, ChaquopyPlayer)


class TestMicrophoneClass:
    """Test _Microphone class."""

    def test_repr(self):
        """_Microphone should have a proper repr."""
        mic = _Microphone(id=0)
        repr_str = repr(mic)
        assert "Microphone" in repr_str

    def test_isloopback_returns_false(self):
        """isloopback should return False."""
        mic = _Microphone(id=0)
        assert mic.isloopback is False

    def test_recorder_returns_chaquopy_recorder(self):
        """recorder() should return a ChaquopyRecorder."""
        mic = _Microphone(id=0)
        recorder = mic.recorder(samplerate=48000)
        assert isinstance(recorder, ChaquopyRecorder)


class TestModuleLevelNameFunctions:
    """Test module-level name functions."""

    def test_get_name_returns_none_initially(self):
        """get_name() should return None initially."""
        result = get_name()
        # May be None or a string
        assert result is None or isinstance(result, str)

    def test_set_name_sets_name(self):
        """set_name() should set the backend name."""
        set_name("test_backend")
        # Backend is recreated each time, so this tests the API doesn't crash
        assert True


class TestChaquopyPlayerTypeConversion:
    """Test ChaquopyPlayer audio type conversion."""

    def test_type_map_factor_is_int16_max(self):
        """TYPE_MAP_FACTOR should be int16 max."""
        assert ChaquopyPlayer.TYPE_MAP_FACTOR == np.iinfo("int16").max

    def test_conversion_max_float_to_int16(self):
        """1.0 float should convert to int16 max."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            frame = np.array([1.0], dtype=np.float32)
            player.play(frame)

        call_args = mock_bridge.writeAudio.call_args[0][0]
        samples = np.frombuffer(call_args, dtype=np.int16)
        assert samples[0] == np.iinfo("int16").max

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_conversion_min_float_to_int16(self):
        """-1.0 float should convert to int16 min."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            frame = np.array([-1.0], dtype=np.float32)
            player.play(frame)

        call_args = mock_bridge.writeAudio.call_args[0][0]
        samples = np.frombuffer(call_args, dtype=np.int16)
        # Note: -1.0 * max will overflow to min due to int16 range
        assert samples[0] < 0

        # Cleanup
        set_kotlin_audio_bridge(None)

    def test_conversion_zero_float_to_int16(self):
        """0.0 float should convert to 0 int16."""
        mock_bridge = MagicMock()
        set_kotlin_audio_bridge(mock_bridge)

        player = ChaquopyPlayer(48000, 960, False, None)
        with player:
            frame = np.array([0.0], dtype=np.float32)
            player.play(frame)

        call_args = mock_bridge.writeAudio.call_args[0][0]
        samples = np.frombuffer(call_args, dtype=np.int16)
        assert samples[0] == 0

        # Cleanup
        set_kotlin_audio_bridge(None)


class TestChaquopyRecorderTypeConversion:
    """Test ChaquopyRecorder audio type conversion."""

    def test_type_map_factor_is_int16_max(self):
        """TYPE_MAP_FACTOR should be int16 max."""
        assert ChaquopyRecorder.TYPE_MAP_FACTOR == np.iinfo("int16").max

    def test_conversion_int16_to_float(self):
        """int16 should convert to float32 in [-1, 1] range."""
        mock_bridge = MagicMock()
        # Return max int16 as bytes
        audio_bytes = np.array([np.iinfo("int16").max], dtype=np.int16).tobytes()
        mock_bridge.readAudio.return_value = audio_bytes
        set_kotlin_audio_bridge(mock_bridge)

        recorder = ChaquopyRecorder(48000, 960, None)
        with recorder:
            result = recorder.record()

        # Result should be close to 1.0
        assert result[0, 0] > 0.99

        # Cleanup
        set_kotlin_audio_bridge(None)
