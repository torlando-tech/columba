# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-04)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.8.0 Kotlin LXST Audio Pipeline - Defining Requirements

## Current Position

Phase: 09 of 12 (Mixer & Pipeline)
Plan: 02 of 03 complete
Status: In progress
Last activity: 2026-02-04 — Completed 09-02-PLAN.md (ToneSource)

Progress: [█████████░░░] 75% — Phase 09 plan 2 complete (11/16 plans complete)

## Milestone Summary

**v0.8.0 Kotlin LXST Audio Pipeline - In Progress**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 07 | Codec Foundation | Base Codec class, Null/Opus/Codec2 codecs | **Complete** |
| 08 | Sources & Sinks | LineSource, LineSink wrapping KotlinAudioBridge | **Complete** |
| 09 | Mixer & Pipeline | Mixer, ToneSource, Pipeline | **In Progress** (2/3) |
| 10 | Telephony & Call | High-level call management | Not started |

## Accumulated Context

### Previous Milestones

**v0.7.4-beta Bug Fixes (Partial):**
- Phase 3 (ANR Elimination): Complete
- Phase 4 (Relay Loop Resolution): Complete
- Phase 5 (Memory Optimization): Infrastructure added, investigation deferred
- Phase 6 (Native Stability): Deferred to post-audio-fix

### Why v0.8.0 Now?

Voice call quality is fundamentally limited by JNI bridge latency:
- **Current**: 5760 bytes raw audio crosses JNI every 60ms → 30-50ms latency per frame
- **Target**: 20-60 bytes encoded packet crosses JNI → <1ms overhead

Root cause cannot be fixed without architectural change. User explicitly requested full LXST rewrite in Kotlin.

### Research Findings

**Available Kotlin/Android Codecs:**
- **Opus**: `cn.entertech.android:wuqi-opus:1.0.3` (theeasiestway/android-opus-codec via Maven Central, libopus 1.3.1)
- **Codec2**: `com.ustadmobile.codec2:codec2-android:0.9.2-1` (UstadMobile, only viable option)

**Existing Kotlin Code to Reuse:**
- `KotlinAudioBridge.kt` — AudioRecord/AudioTrack with ring buffers
- `KotlinAudioFilters.kt` — HighPass, LowPass, AGC (<1ms latency, already working)

### LXST Python Structure (to match)

```
LXST/
├── __init__.py (7 lines)
├── Sources.py (361 lines) — LineSource, OpusFileSource, backends
├── Sinks.py (348 lines) — LineSink, OpusFileSink, backends
├── Mixer.py (177 lines) — Audio mixing
├── Network.py (145 lines) — Packetizer, LinkSource
├── Pipeline.py (58 lines) — Pipeline orchestration
├── Filters.py (398 lines) — Already in Kotlin
├── Generators.py (134 lines) — ToneSource
├── Call.py (55 lines) — Legacy endpoint
├── Codecs/
│   ├── __init__.py (29 lines)
│   ├── Codec.py (62 lines)
│   ├── Opus.py (167 lines)
│   ├── Codec2.py (121 lines)
│   └── Raw.py (small)
└── Primitives/
    └── Telephony.py (732 lines) — Profiles, Signalling, Telephone
```

Total Python lines to port: ~2,700 (excluding libs, platforms)

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| Start v0.8.0 immediately | Audio quality is blocking user adoption | — |
| Defer v0.7.4 remaining phases | Memory issues less critical than audio quality | — |
| Match Python file structure | User explicitly requested identical organization | — |
| Float32 range [-1.0, 1.0] | Matches Python LXST, multiply by 32767 for int16 | 07-01 |
| Linear interpolation resampler | Sufficient quality for testing, can upgrade later | 07-01 |
| Little-endian int16 wire format | Python compatibility for decode-compatible packets | 07-01 |
| Use wuqi-opus from Maven Central | Reliable distribution vs JitPack instability | 07-02 |
| ShortArray to ByteArray conversion | JNI uses ShortArray, wire needs ByteArray for Python | 07-02 |
| Configuration-only unit tests | JNI libraries can't load in Robolectric | 07-02 |
| Override minSdk 26 in manifest | Opus library safe for API 24+ (basic JNI only) | 07-02 |
| Use git submodule for codec2_talkie | Not published to Maven, JitPack unreliable | 07-03 |
| Only build arm64-v8a ABI | Match Chaquopy Python configuration | 07-03 |
| PREFER_SETTINGS repository mode | Allow submodule project repos while preferring settings | 07-03 |
| 17 instrumented tests for Codec2 | 7 encode + 7 header + decode + comprehensive + mode-switch | 07-05 |
| Energy-based fidelity check | RMS energy ratio 0.1-10.0 for lossy codec round-trip validation | 07-06 |
| Match Python LXST class hierarchy | Source/LocalSource/RemoteSource, Sink/LocalSink/RemoteSink for future extensibility | 08-01 |
| Float32 internal audio format | [-1.0, 1.0] range for pipeline, convert to/from int16 at edges | 08-01 |
| Backpressure via canReceive() | Sink returns false when buffer near full, prevents overflow | 08-01 |
| Phase 8 loopback: encode→decode→sink | Source decodes locally for testing; Phase 9 will send encoded over network | 08-02 |
| Public setters on override var | Kotlin abstract var requires public setter in implementing class | 08-02 |
| 382Hz default tone frequency | Matches Python LXST Telephony.py, not 440Hz ITU-T standard | 09-02 |
| Float32 ToneSource output | Local playback path pushes decoded float32 to sink, encoding in transmit path | 09-02 |
| Double for phase accumulator | Avoids floating point drift over long dial tones | 09-02 |

### Blockers/Concerns

**Wire Compatibility:**
- Encoded packets must be decode-compatible with Python LXST (not bit-identical)
- ✓ Codec2 mode headers implemented (0x00-0x06)
- Opus packets must be decodable by Python pyogg with intelligible audio
- ShortArray/ByteArray conversion in Opus needs validation with real packets
- Codec2 encode/decode needs cross-implementation validation (Kotlin → Python)

**Integration Complexity:**
- `call_manager.py` wraps Python LXST Telephone — needs Kotlin bridge
- Signalling still goes through Python Reticulum links
- Must coordinate Kotlin audio thread with Python network thread

**Testing Limitations:**
- JNI encode/decode requires instrumented tests (actual device)
- Unit tests limited to configuration logic only
- Wire compatibility validation requires Python LXST integration testing

## Session Continuity

Last session: 2026-02-04
Stopped at: Completed 09-02-PLAN.md (ToneSource)
Resume file: None
Next: 09-03-PLAN.md (Pipeline orchestration)
