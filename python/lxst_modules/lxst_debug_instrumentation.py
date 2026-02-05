"""
LXST Audio Pipeline Debug Instrumentation

This module monkey-patches LXST's audio pipeline classes to add debug logging.
Import this module AFTER LXST is imported to instrument the pipeline.

Instrumented classes:
- LXST.Sources.LineSource - microphone capture
- LXST.Mixer.Mixer - audio mixing
- LXST.Network.Packetizer - network transmission
- LXST.Network.LinkSource - network reception
"""

import functools

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


# Global counters for logging every Nth frame
_counters = {
    'ls_ingest': 0,
    'ls_nonzero': 0,
    'mix_handle': 0,
    'mix_job': 0,
    'pkt_tx': 0,
    'rx': 0,
}


def install_instrumentation():
    """Install debug instrumentation on LXST audio pipeline classes."""

    try:
        # Import LXST classes
        from LXST.Sources import LineSource
        from LXST.Mixer import Mixer
        from LXST.Network import Packetizer, LinkSource

        RNS.log("🔧 Installing LXST audio pipeline instrumentation...", RNS.LOG_DEBUG)

        # === Instrument LineSource.__ingest_job ===
        original_ingest_job = LineSource._LineSource__ingest_job

        # Timing accumulators for performance analysis
        _timing = {
            'record_ms': 0.0,
            'filter_ms': 0.0,
            'encode_ms': 0.0,
            'total_ms': 0.0,
            'samples': 0,
        }

        # Adaptive filter bypass - disable filters if they're too slow
        _filter_bypass = {
            'enabled': False,  # Start with filters enabled
            'slow_count': 0,   # Count of consecutive slow frames
            'threshold_ms': 15.0,  # Filter time threshold (ms)
            'consecutive_slow': 5,  # How many slow frames before bypass
            'logged': False,
        }

        @functools.wraps(original_ingest_job)
        def instrumented_ingest_job(self):
            import time
            import numpy as np

            with self.recording_lock:
                frame_samples = None
                if not RNS.vendor.platformutils.is_darwin():
                    backend_samples_per_frame = self.samples_per_frame
                else:
                    backend_samples_per_frame = None

                with self.backend.get_recorder(samples_per_frame=backend_samples_per_frame) as recorder:
                    started = time.time()
                    ease_in_completed = True if self.ease_in <= 0.0 else False
                    skip_completed = True if self._LineSource__skip <= 0.0 else False

                    while self.should_run:
                        loop_start = time.time()

                        # TIME: Recording (includes JNI call to readAudio)
                        record_start = time.time()
                        frame_samples = recorder.record(numframes=self.samples_per_frame)
                        record_time = (time.time() - record_start) * 1000
                        _timing['record_ms'] += record_time

                        _counters['ls_ingest'] += 1

                        # Check if Kotlin LXST is handling audio
                        try:
                            from lxst_modules.chaquopy_audio_backend import is_kotlin_audio_active
                            if is_kotlin_audio_active():
                                if _counters['ls_ingest'] % 100 == 1:
                                    RNS.log(f"🎤 LS#{_counters['ls_ingest']}: Kotlin audio active, skipping ingest", RNS.LOG_DEBUG)
                                time.sleep(self.frame_time)  # Maintain timing
                                continue  # Skip this frame
                        except ImportError:
                            pass  # Not on Android

                        raw_max = 0
                        if frame_samples is not None and len(frame_samples) > 0:
                            raw_max = abs(frame_samples).max()
                            if raw_max > 0.001:
                                _counters['ls_nonzero'] += 1

                        if not skip_completed:
                            if time.time() - started > self._LineSource__skip:
                                skip_completed = True
                                started = time.time()
                                RNS.log(f"🎤 LineSource: skip phase done", RNS.LOG_DEBUG)
                        else:
                            pre_filt = raw_max

                            # TIME: Filters with adaptive bypass for Android performance
                            filter_start = time.time()
                            filter_count = len(self.filters) if self.filters else 0

                            # Check if Kotlin-native filters are active (much faster: <1ms vs 20-50ms)
                            kotlin_filters_active = False
                            try:
                                from lxst_modules.chaquopy_audio_backend import are_kotlin_filters_active
                                kotlin_filters_active = are_kotlin_filters_active()
                            except ImportError:
                                pass  # Not on Android/Chaquopy

                            # Skip Python/LXST filters if Kotlin filters are handling it
                            # This avoids double-filtering and eliminates Python/CFFI latency
                            if kotlin_filters_active:
                                if not _filter_bypass.get('kotlin_logged', False):
                                    RNS.log(f"🔧 Python/LXST filters DISABLED - Kotlin filters active (<1ms latency)", RNS.LOG_INFO)
                                    _filter_bypass['kotlin_logged'] = True
                            elif self.filters is not None and not _filter_bypass['enabled']:
                                for f in self.filters:
                                    frame_samples = f.handle_frame(frame_samples, self.samplerate)

                            filter_time = (time.time() - filter_start) * 1000
                            _timing['filter_ms'] += filter_time

                            # Adaptive bypass: if filters consistently slow, disable them
                            # Only applies when Kotlin filters are NOT active
                            if not kotlin_filters_active and filter_time > _filter_bypass['threshold_ms']:
                                _filter_bypass['slow_count'] += 1
                                if _filter_bypass['slow_count'] >= _filter_bypass['consecutive_slow'] and not _filter_bypass['enabled']:
                                    _filter_bypass['enabled'] = True
                                    RNS.log(f"⚠️ Filters too slow ({filter_time:.1f}ms > {_filter_bypass['threshold_ms']}ms), "
                                            f"enabling adaptive bypass for better audio quality", RNS.LOG_WARNING)
                            else:
                                _filter_bypass['slow_count'] = 0  # Reset on fast frame

                            post_filt = 0
                            if frame_samples is not None and len(frame_samples) > 0:
                                post_filt = abs(frame_samples).max()

                            if self._LineSource__gain != 1.0:
                                frame_samples *= self._LineSource__gain

                            post_gain = 0
                            if frame_samples is not None and len(frame_samples) > 0:
                                post_gain = abs(frame_samples).max()

                            # TIME: Encoding
                            encode_start = time.time()
                            if self.codec:
                                frame = self.codec.encode(frame_samples)
                                if self.sink and self.sink.can_receive(from_source=self):
                                    self.sink.handle_frame(frame, self)
                                elif _counters['ls_ingest'] % 50 == 1:
                                    RNS.log(f"🎤 LS: sink blocked!", RNS.LOG_WARNING)
                            encode_time = (time.time() - encode_start) * 1000
                            _timing['encode_ms'] += encode_time

                            # Total loop time
                            loop_time = (time.time() - loop_start) * 1000
                            _timing['total_ms'] += loop_time
                            _timing['samples'] += 1

                            # Log every 100th frame with timing breakdown (reduced for performance)
                            if _counters['ls_ingest'] % 100 == 1:
                                n = _timing['samples'] if _timing['samples'] > 0 else 1
                                avg_record = _timing['record_ms'] / n
                                avg_filter = _timing['filter_ms'] / n
                                avg_encode = _timing['encode_ms'] / n
                                avg_total = _timing['total_ms'] / n
                                RNS.log(
                                    f"🎤 LS#{_counters['ls_ingest']}: "
                                    f"raw={raw_max:.4f} filt={post_filt:.4f} nz={_counters['ls_nonzero']} | "
                                    f"⏱️ rec={avg_record:.1f}ms flt={avg_filter:.1f}ms enc={avg_encode:.1f}ms tot={avg_total:.1f}ms "
                                    f"(n={n} filters={len(self.filters) if self.filters else 0})",
                                    RNS.LOG_DEBUG
                                )

                            if not ease_in_completed:
                                d = time.time() - started
                                self._LineSource__gain = (d / self.ease_in) * self._LineSource__target_gain
                                if self._LineSource__gain >= self._LineSource__target_gain:
                                    self._LineSource__gain = self._LineSource__target_gain
                                    ease_in_completed = True

        LineSource._LineSource__ingest_job = instrumented_ingest_job
        RNS.log("🔧 LineSource.__ingest_job instrumented", RNS.LOG_DEBUG)

        # === Instrument Mixer.handle_frame ===
        original_mixer_handle = Mixer.handle_frame

        @functools.wraps(original_mixer_handle)
        def instrumented_mixer_handle(self, frame, source, decoded=False):
            _counters['mix_handle'] += 1

            # Call original first to decode frame if needed
            original_mixer_handle(self, frame, source, decoded=decoded)

            # Log the frame that was just added
            if source in self.incoming_frames and len(self.incoming_frames[source]) > 0:
                # Get the last frame that was just added
                last_frame = self.incoming_frames[source][-1] if len(self.incoming_frames[source]) > 0 else None
                if last_frame is not None and len(last_frame) > 0:
                    frame_max = abs(last_frame).max()
                    if _counters['mix_handle'] % 50 == 1:
                        RNS.log(
                            f"🎛️ MIX.h#{_counters['mix_handle']}: "
                            f"max={frame_max:.4f} qlen={len(self.incoming_frames[source])} "
                            f"decoded={decoded}",
                            RNS.LOG_DEBUG
                        )

        Mixer.handle_frame = instrumented_mixer_handle
        RNS.log("🔧 Mixer.handle_frame instrumented", RNS.LOG_DEBUG)

        # === Instrument Mixer._mixer_job ===
        original_mixer_job = Mixer._mixer_job

        @functools.wraps(original_mixer_job)
        def instrumented_mixer_job(self):
            import time
            import numpy as np

            with self.mixer_lock:
                while self.should_run:
                    if self.sink and self.sink.can_receive():
                        source_count = 0
                        mixed_frame = None

                        for source in self.incoming_frames.copy():
                            if len(self.incoming_frames[source]) > 0:
                                next_frame = self.incoming_frames[source].popleft()
                                if source_count == 0:
                                    mixed_frame = next_frame * self._mixing_gain
                                else:
                                    mixed_frame = mixed_frame + next_frame * self._mixing_gain
                                source_count += 1

                        if source_count > 0:
                            _counters['mix_job'] += 1
                            mixed_frame = np.clip(mixed_frame, -1.0, 1.0)
                            mix_max = abs(mixed_frame).max()

                            if _counters['mix_job'] % 50 == 1:
                                RNS.log(
                                    f"🎛️ MIX.j#{_counters['mix_job']}: "
                                    f"max={mix_max:.4f} srcs={source_count} "
                                    f"codec={self.codec is not None}",
                                    RNS.LOG_DEBUG
                                )

                            if RNS.loglevel >= RNS.LOG_DEBUG:
                                if mixed_frame.max() >= 1.0 or mixed_frame.min() <= -1.0:
                                    RNS.log(f"Signal clipped on {self}", RNS.LOG_WARNING)

                            if self.codec:
                                self.sink.handle_frame(self.codec.encode(mixed_frame), self)
                            else:
                                self.sink.handle_frame(mixed_frame, self)
                        else:
                            time.sleep(self.frame_time * 0.1)
                    else:
                        time.sleep(self.frame_time * 0.1)

        Mixer._mixer_job = instrumented_mixer_job
        RNS.log("🔧 Mixer._mixer_job instrumented", RNS.LOG_DEBUG)

        # === Instrument Packetizer.handle_frame ===
        original_pkt_handle = Packetizer.handle_frame

        @functools.wraps(original_pkt_handle)
        def instrumented_pkt_handle(self, frame, source=None):
            _counters['pkt_tx'] += 1

            if type(self.destination) == RNS.Link and not self.destination.status == RNS.Link.ACTIVE:
                RNS.log(f"📡 PKT: link not active, dropping frame", RNS.LOG_DEBUG)
                return

            if _counters['pkt_tx'] % 50 == 1:
                RNS.log(
                    f"📡 PKT.tx#{_counters['pkt_tx']}: "
                    f"len={len(frame) if frame else 0} "
                    f"codec={type(self.source.codec).__name__ if self.source and self.source.codec else 'None'}",
                    RNS.LOG_DEBUG
                )

            original_pkt_handle(self, frame, source)

        Packetizer.handle_frame = instrumented_pkt_handle
        RNS.log("🔧 Packetizer.handle_frame instrumented", RNS.LOG_DEBUG)

        # === Instrument LinkSource._packet ===
        original_rx_packet = LinkSource._packet

        @functools.wraps(original_rx_packet)
        def instrumented_rx_packet(self, data, packet):
            _counters['rx'] += 1

            if _counters['rx'] % 50 == 1:
                RNS.log(
                    f"📥 RX#{_counters['rx']}: "
                    f"datalen={len(data) if data else 0} "
                    f"sink={self.sink is not None}",
                    RNS.LOG_DEBUG
                )

            original_rx_packet(self, data, packet)

        LinkSource._packet = instrumented_rx_packet
        RNS.log("🔧 LinkSource._packet instrumented", RNS.LOG_DEBUG)

        # === Add missing attributes to LinkSource ===
        original_ls_init = LinkSource.__init__

        @functools.wraps(original_ls_init)
        def instrumented_ls_init(self, link, signalling_receiver, sink=None):
            original_ls_init(self, link, signalling_receiver, sink)
            # Add default attributes that Mixer expects
            if not hasattr(self, 'channels'):
                self.channels = 1
            if not hasattr(self, 'samplerate'):
                self.samplerate = 48000
            if not hasattr(self, 'bitdepth'):
                self.bitdepth = 32

        LinkSource.__init__ = instrumented_ls_init
        RNS.log("🔧 LinkSource.__init__ instrumented with default attributes", RNS.LOG_DEBUG)

        RNS.log("🔧 LXST audio pipeline instrumentation installed!", RNS.LOG_INFO)
        return True

    except ImportError as e:
        RNS.log(f"🔧 Could not install LXST instrumentation: {e}", RNS.LOG_WARNING)
        return False
    except Exception as e:
        RNS.log(f"🔧 Error installing LXST instrumentation: {e}", RNS.LOG_ERROR)
        import traceback
        traceback.print_exc()
        return False


def get_counters():
    """Get current instrumentation counters."""
    return dict(_counters)


def reset_counters():
    """Reset all instrumentation counters."""
    for key in _counters:
        _counters[key] = 0
