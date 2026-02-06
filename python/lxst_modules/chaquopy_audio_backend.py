"""
Chaquopy-compatible audio backend for LXST on Android.

This module replaces LXST's Pyjnius-based soundcard.py with a Chaquopy-compatible
implementation that uses KotlinAudioBridge for Android audio access.

The API matches LXST's Platforms/android/soundcard.py to enable drop-in replacement.

Usage:
    # From Kotlin, set the bridge before audio operations:
    wrapper.callAttr("set_audio_bridge", KotlinAudioBridge.getInstance(context))

    # Then use standard LXST audio APIs:
    from lxst_modules.chaquopy_audio_backend import get_backend
    backend = get_backend()
    player = backend.get_player(samples_per_frame=960)
    recorder = backend.get_recorder(samples_per_frame=960)
"""

import numpy as np
import threading
import time

try:
    import RNS
except ImportError:
    # Fallback for testing without RNS
    class RNS:
        LOG_DEBUG = 0
        LOG_INFO = 1
        LOG_WARNING = 2
        LOG_ERROR = 3

        @staticmethod
        def log(msg, level=LOG_INFO):
            print(f"[RNS] {msg}")

# Global bridge reference (set by Kotlin via set_audio_bridge)
_kotlin_audio_bridge = None
_bridge_lock = threading.Lock()

# Flag to indicate Kotlin-native filters are active (disables Python/LXST filters)
# This is set when KotlinAudioBridge has filtersEnabled=true
_kotlin_filters_active = True  # Default to True since Kotlin filters are faster


def set_kotlin_audio_bridge(bridge):
    """Set the KotlinAudioBridge instance from Kotlin.

    Called by PythonWrapperManager.setupAudioBridge() during initialization.

    Args:
        bridge: KotlinAudioBridge instance from Kotlin
    """
    global _kotlin_audio_bridge
    with _bridge_lock:
        _kotlin_audio_bridge = bridge
        RNS.log("ChaquopyAudioBackend: Kotlin audio bridge set", RNS.LOG_DEBUG)


def get_kotlin_audio_bridge():
    """Get the current KotlinAudioBridge instance."""
    with _bridge_lock:
        return _kotlin_audio_bridge


def are_kotlin_filters_active():
    """Check if Kotlin-native filters are handling audio processing.

    When True, Python/LXST filters should be skipped to avoid double-filtering.
    Kotlin filters run in KotlinAudioBridge.recordingLoop() at <1ms latency,
    vs Python/CFFI filters at 20-50ms latency.
    """
    return _kotlin_filters_active


def set_kotlin_filters_active(active):
    """Set whether Kotlin-native filters are active.

    Args:
        active: True to use Kotlin filters (Python filters skipped),
                False to use Python/LXST filters
    """
    global _kotlin_filters_active
    _kotlin_filters_active = active
    RNS.log(f"Kotlin filters active: {active} (Python/LXST filters {'disabled' if active else 'enabled'})", RNS.LOG_INFO)



def get_backend():
    """Get the Chaquopy audio backend instance.

    Returns:
        ChaquopyAudioBackend instance
    """
    return ChaquopyAudioBackend()


class ChaquopyAudioBackend:
    """Audio backend using KotlinAudioBridge.

    Provides the same API as LXST's _AndroidAudio class but uses
    Chaquopy-compatible Kotlin bridge calls instead of Pyjnius.
    """

    SAMPLERATE = 48000
    CHANNELS = 1

    # Device type descriptions matching LXST's format
    DEFAULT_SINK = "Internal Speaker"
    DEFAULT_SOURCE = "Internal Microphone"

    def __init__(self, preferred_device=None, samplerate=SAMPLERATE):
        self.samplerate = samplerate
        self.preferred_device = preferred_device
        self._client_name = None

    @property
    def name(self):
        return self._client_name

    @name.setter
    def name(self, name):
        self._client_name = name

    def get_player(self, samples_per_frame=None, low_latency=None):
        """Get an audio player (output sink).

        Args:
            samples_per_frame: Number of samples per audio frame
            low_latency: Enable low-latency mode if True

        Returns:
            ChaquopyPlayer instance
        """
        return ChaquopyPlayer(
            samplerate=self.samplerate,
            samples_per_frame=samples_per_frame,
            low_latency=low_latency,
            preferred_device=self.preferred_device,
        )

    def get_recorder(self, samples_per_frame=None):
        """Get an audio recorder (input source).

        Args:
            samples_per_frame: Number of samples per audio frame

        Returns:
            ChaquopyRecorder instance
        """
        return ChaquopyRecorder(
            samplerate=self.samplerate,
            samples_per_frame=samples_per_frame,
            preferred_device=self.preferred_device,
        )

    @property
    def source_list(self):
        """Get list of available audio input devices."""
        bridge = get_kotlin_audio_bridge()
        if bridge is None:
            return [{"name": self.DEFAULT_SOURCE, "id": 0}]

        try:
            devices = bridge.getInputDevices()
            return [{"name": d.get("name", "Unknown"), "id": d.get("id", 0)} for d in devices]
        except Exception as e:
            RNS.log(f"Error getting input devices: {e}", RNS.LOG_ERROR)
            return [{"name": self.DEFAULT_SOURCE, "id": 0}]

    @property
    def sink_list(self):
        """Get list of available audio output devices."""
        bridge = get_kotlin_audio_bridge()
        if bridge is None:
            return [{"name": self.DEFAULT_SINK, "id": 0}]

        try:
            devices = bridge.getOutputDevices()
            return [{"name": d.get("name", "Unknown"), "id": d.get("id", 0)} for d in devices]
        except Exception as e:
            RNS.log(f"Error getting output devices: {e}", RNS.LOG_ERROR)
            return [{"name": self.DEFAULT_SINK, "id": 0}]

    def source_info(self, source_id):
        """Get info about a specific audio source."""
        return {
            "latency": 0,
            "configured_latency": 0,
            "channels": self.CHANNELS,
            "name": self.DEFAULT_SOURCE,
            "device.class": "sound",
            "device.api": "Chaquopy",
            "device.bus": "unknown",
        }

    def sink_info(self, sink_id):
        """Get info about a specific audio sink."""
        return {
            "latency": 0,
            "configured_latency": 0,
            "channels": self.CHANNELS,
            "name": self.DEFAULT_SINK,
            "device.class": "sound",
            "device.api": "Chaquopy",
            "device.bus": "unknown",
        }

    @property
    def server_info(self):
        """Get server info (for LXST compatibility)."""
        return {
            "server version": "1.0.0",
            "server name": "Chaquopy Audio",
            "default sink id": 0,
            "default source id": 0,
        }


class ChaquopyPlayer:
    """Audio player using KotlinAudioBridge.

    Provides the same API as LXST's _Player class.
    """

    TYPE_MAP_FACTOR = np.iinfo("int16").max

    def __init__(self, samplerate, samples_per_frame, low_latency, preferred_device):
        self.samplerate = samplerate
        self.samples_per_frame = samples_per_frame or 960  # 20ms at 48kHz
        self.low_latency = low_latency or False
        self.preferred_device = preferred_device
        self.channels = 1
        self._started = False

    def __enter__(self):
        bridge = get_kotlin_audio_bridge()
        if bridge is not None:
            try:
                bridge.startPlayback(
                    self.samplerate,
                    self.channels,
                    self.low_latency,
                )
                self._started = True
                RNS.log(f"ChaquopyPlayer: Started playback at {self.samplerate}Hz", RNS.LOG_DEBUG)
            except Exception as e:
                RNS.log(f"ChaquopyPlayer: Failed to start playback: {e}", RNS.LOG_ERROR)
        else:
            RNS.log("ChaquopyPlayer: No audio bridge available", RNS.LOG_WARNING)
        return self

    def __exit__(self, *args):
        if self._started:
            bridge = get_kotlin_audio_bridge()
            if bridge is not None:
                try:
                    bridge.stopPlayback()
                    RNS.log("ChaquopyPlayer: Stopped playback", RNS.LOG_DEBUG)
                except Exception as e:
                    RNS.log(f"ChaquopyPlayer: Error stopping playback: {e}", RNS.LOG_ERROR)
            self._started = False

    _play_count = 0
    _play_nonzero_count = 0

    def play(self, frame):
        """Play an audio frame.

        Args:
            frame: numpy array of float32 samples in range [-1, 1]
        """
        if not self._started:
            ChaquopyPlayer._play_count += 1
            if ChaquopyPlayer._play_count % 25 == 1:
                RNS.log(f"🔊 Player: NOT STARTED, dropping frame #{ChaquopyPlayer._play_count}", RNS.LOG_WARNING)
            return

        bridge = get_kotlin_audio_bridge()
        if bridge is None:
            ChaquopyPlayer._play_count += 1
            if ChaquopyPlayer._play_count % 25 == 1:
                RNS.log(f"🔊 Player: NO BRIDGE, dropping frame #{ChaquopyPlayer._play_count}", RNS.LOG_WARNING)
            return

        try:
            # Log periodically to understand audio flow
            ChaquopyPlayer._play_count += 1
            frame_max = abs(frame).max() if frame is not None and len(frame) > 0 else 0
            if frame_max > 0.001:
                ChaquopyPlayer._play_nonzero_count += 1
            if ChaquopyPlayer._play_count % 25 == 1:
                RNS.log(f"🔊 Player#{ChaquopyPlayer._play_count}: max={frame_max:.4f} nz={ChaquopyPlayer._play_nonzero_count} shape={frame.shape}", RNS.LOG_DEBUG)

            # Convert float32 [-1, 1] to int16 bytes
            samples = (frame * self.TYPE_MAP_FACTOR).astype(np.int16)
            audio_bytes = samples.tobytes()
            bridge.writeAudio(audio_bytes)
        except Exception as e:
            RNS.log(f"🔊 Player: ERROR: {e}", RNS.LOG_ERROR)

    def enable_low_latency(self):
        """Enable low-latency mode (restart required to take effect)."""
        self.low_latency = True

    @property
    def latency(self):
        """Return estimated latency in seconds."""
        return 0.020  # ~20ms estimate


class ChaquopyRecorder:
    """Audio recorder using KotlinAudioBridge.

    Provides the same API as LXST's _Recorder class.
    """

    TYPE_MAP_FACTOR = np.iinfo("int16").max
    _record_count = 0
    _record_nonzero_count = 0

    def __init__(self, samplerate, samples_per_frame, preferred_device):
        self.samplerate = samplerate
        self.samples_per_frame = samples_per_frame or 960  # 20ms at 48kHz
        self.preferred_device = preferred_device
        self.channels = 1
        self._started = False
        self._pending_chunk = np.zeros((0,), dtype="float32")

    def __enter__(self):
        bridge = get_kotlin_audio_bridge()
        if bridge is not None:
            try:
                bridge.startRecording(
                    self.samplerate,
                    self.channels,
                    self.samples_per_frame,
                )
                self._started = True
                RNS.log(f"ChaquopyRecorder: Started recording at {self.samplerate}Hz", RNS.LOG_DEBUG)
            except Exception as e:
                RNS.log(f"ChaquopyRecorder: Failed to start recording: {e}", RNS.LOG_ERROR)
        else:
            RNS.log("ChaquopyRecorder: No audio bridge available", RNS.LOG_WARNING)
        return self

    def __exit__(self, *args):
        if self._started:
            bridge = get_kotlin_audio_bridge()
            if bridge is not None:
                try:
                    bridge.stopRecording()
                    RNS.log("ChaquopyRecorder: Stopped recording", RNS.LOG_DEBUG)
                except Exception as e:
                    RNS.log(f"ChaquopyRecorder: Error stopping recording: {e}", RNS.LOG_ERROR)
            self._started = False

    def record(self, numframes=None):
        """Record audio frames.

        Args:
            numframes: Number of frames to record. If None, returns all available.

        Returns:
            numpy array of float32 samples shaped (numframes, channels)
        """
        if not self._started:
            return np.zeros((numframes or self.samples_per_frame, self.channels), dtype="float32")

        bridge = get_kotlin_audio_bridge()
        if bridge is None:
            return np.zeros((numframes or self.samples_per_frame, self.channels), dtype="float32")

        try:
            if numframes is None:
                # Return all available data
                java_bytes = bridge.readAudio(self.samples_per_frame)
                if java_bytes is None:
                    return np.reshape(
                        np.concatenate([self.flush().ravel(), self._record_chunk()]),
                        [-1, self.channels],
                    )
                # Convert Java byte[] to Python bytes for numpy compatibility
                audio_bytes = bytes(java_bytes)
                samples = np.frombuffer(audio_bytes, dtype=np.int16) / self.TYPE_MAP_FACTOR
                return np.reshape(samples.astype("float32"), [-1, self.channels])
            else:
                # Accumulate frames until we have enough
                captured_data = [self._pending_chunk]
                captured_frames = self._pending_chunk.shape[0] / self.channels

                if captured_frames >= numframes:
                    keep, self._pending_chunk = np.split(
                        self._pending_chunk, [int(numframes * self.channels)]
                    )
                    return np.reshape(keep, [-1, self.channels])

                while captured_frames < numframes:
                    chunk = self._record_chunk()
                    captured_data.append(chunk)
                    captured_frames += len(chunk) / self.channels

                to_split = int(len(chunk) - (captured_frames - numframes) * self.channels)
                captured_data[-1], self._pending_chunk = np.split(captured_data[-1], [to_split])
                return np.reshape(np.concatenate(captured_data), [-1, self.channels])

        except Exception as e:
            RNS.log(f"ChaquopyRecorder: Error recording: {e}", RNS.LOG_ERROR)
            return np.zeros((numframes or self.samples_per_frame, self.channels), dtype="float32")

    # Timing accumulators for JNI performance analysis
    _jni_timing = {'read_ms': 0.0, 'convert_ms': 0.0, 'samples': 0}

    def _record_chunk(self):
        """Read a single chunk from the bridge."""
        ChaquopyRecorder._record_count += 1

        bridge = get_kotlin_audio_bridge()
        if bridge is None:
            if ChaquopyRecorder._record_count % 25 == 1:
                RNS.log(f"🎙️ Rec#{ChaquopyRecorder._record_count}: NO BRIDGE", RNS.LOG_WARNING)
            return np.zeros((0,), dtype="float32")

        # TIME: JNI call to readAudio
        jni_start = time.time()
        java_bytes = bridge.readAudio(self.samples_per_frame)
        jni_time = (time.time() - jni_start) * 1000

        if java_bytes is None:
            if ChaquopyRecorder._record_count % 25 == 1:
                RNS.log(f"🎙️ Rec#{ChaquopyRecorder._record_count}: readAudio returned None (waited {jni_time:.1f}ms)", RNS.LOG_DEBUG)
            return np.zeros((0,), dtype="float32")

        # TIME: bytes() conversion and numpy processing
        convert_start = time.time()

        # Convert Java byte[] to Python bytes for numpy compatibility
        try:
            java_len = len(java_bytes)
            audio_bytes = bytes(java_bytes)
            py_len = len(audio_bytes)
        except Exception as e:
            if ChaquopyRecorder._record_count % 25 == 1:
                RNS.log(f"🎙️ Rec#{ChaquopyRecorder._record_count}: bytes() FAILED: {e}, type={type(java_bytes)}", RNS.LOG_ERROR)
            return np.zeros((0,), dtype="float32")

        samples = np.frombuffer(audio_bytes, dtype=np.int16) / self.TYPE_MAP_FACTOR
        result = samples.astype("float32")
        convert_time = (time.time() - convert_start) * 1000

        # Track timing
        ChaquopyRecorder._jni_timing['read_ms'] += jni_time
        ChaquopyRecorder._jni_timing['convert_ms'] += convert_time
        ChaquopyRecorder._jni_timing['samples'] += 1

        # Log less frequently to reduce overhead (every 100 frames instead of 25)
        sample_max = abs(result).max() if len(result) > 0 else 0
        if sample_max > 0.001:
            ChaquopyRecorder._record_nonzero_count += 1
        if ChaquopyRecorder._record_count % 100 == 1:
            n = ChaquopyRecorder._jni_timing['samples'] if ChaquopyRecorder._jni_timing['samples'] > 0 else 1
            avg_jni = ChaquopyRecorder._jni_timing['read_ms'] / n
            avg_conv = ChaquopyRecorder._jni_timing['convert_ms'] / n
            RNS.log(
                f"🎙️ Rec#{ChaquopyRecorder._record_count}: len={java_len} max={sample_max:.4f} | "
                f"⏱️ jni={avg_jni:.1f}ms conv={avg_conv:.1f}ms",
                RNS.LOG_DEBUG
            )

        return result

    def flush(self):
        """Flush pending audio data."""
        last_chunk = np.reshape(self._pending_chunk, [-1, self.channels])
        self._pending_chunk = np.zeros((0,), dtype="float32")
        return last_chunk

    @property
    def latency(self):
        """Return estimated latency in seconds."""
        return 0.020  # ~20ms estimate


# LXST-compatible module-level functions


def all_speakers():
    """Get all available speaker devices."""
    backend = get_backend()
    return [_Speaker(id=s["id"]) for s in backend.sink_list]


def default_speaker():
    """Get the default speaker device."""
    backend = get_backend()
    info = backend.server_info
    return _Speaker(id=info["default sink id"])


def get_speaker(id, low_latency=False):
    """Get a specific speaker by ID."""
    return _Speaker(id=id, low_latency=low_latency)


def all_microphones(include_loopback=False, exclude_monitors=True):
    """Get all available microphone devices."""
    backend = get_backend()
    return [_Microphone(id=m["id"]) for m in backend.source_list]


def default_microphone():
    """Get the default microphone device."""
    backend = get_backend()
    info = backend.server_info
    return _Microphone(id=info["default source id"])


def get_microphone(id, include_loopback=False, exclude_monitors=True):
    """Get a specific microphone by ID."""
    return _Microphone(id=id)


class _SoundCard:
    """Base class for audio devices (LXST compatibility)."""

    def __init__(self, *, id, low_latency=False):
        self._id = id
        self._low_latency = low_latency
        self._backend = get_backend()

    @property
    def channels(self):
        return self._get_info().get("channels", 1)

    @property
    def id(self):
        return self._id

    @property
    def name(self):
        return self._get_info().get("name", "Unknown")

    def _get_info(self):
        return self._backend.source_info(self._id)


class _Speaker(_SoundCard):
    """Speaker device wrapper (LXST compatibility)."""

    def __repr__(self):
        return f"<Speaker {self.name} ({self.channels} channels)>"

    def player(self, samplerate, channels=None, blocksize=None, low_latency=None):
        if channels is None:
            channels = self.channels
        return ChaquopyPlayer(
            samplerate=samplerate,
            samples_per_frame=blocksize,
            low_latency=low_latency or self._low_latency,
            preferred_device=self._id,
        )

    def play(self, data, samplerate, channels=None, blocksize=None):
        if channels is None:
            channels = self.channels
        with ChaquopyPlayer(
            samplerate=samplerate,
            samples_per_frame=blocksize,
            low_latency=False,
            preferred_device=self._id,
        ) as p:
            p.play(data)

    def _get_info(self):
        return self._backend.sink_info(self._id)


class _Microphone(_SoundCard):
    """Microphone device wrapper (LXST compatibility)."""

    def __repr__(self):
        return f"<Microphone {self.name} ({self.channels} channels)>"

    @property
    def isloopback(self):
        return False

    def recorder(self, samplerate, channels=None, blocksize=None):
        if channels is None:
            channels = self.channels
        return ChaquopyRecorder(
            samplerate=samplerate,
            samples_per_frame=blocksize,
            preferred_device=self._id,
        )

    def record(self, numframes, samplerate, channels=None, blocksize=None):
        if channels is None:
            channels = self.channels
        with ChaquopyRecorder(
            samplerate=samplerate,
            samples_per_frame=blocksize,
            preferred_device=self._id,
        ) as r:
            return r.record(numframes)


def get_name():
    """Get backend name."""
    return get_backend().name


def set_name(name):
    """Set backend name."""
    get_backend().name = name
