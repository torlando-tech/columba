# Columba

## What This Is

Columba is an Android LXMF messenger built on the Reticulum mesh networking stack. It bridges Python (Reticulum/LXMF) with Kotlin via Chaquopy, supporting BLE, USB, and TCP interfaces for off-grid and resilient communication.

## Core Value

Reliable off-grid messaging with a polished, responsive user experience.

## Current Milestone: v0.8.0 Kotlin LXST Audio Pipeline

**Goal:** Rewrite the LXST audio pipeline in Kotlin to eliminate JNI bridge latency, sending only encoded packets to Python Reticulum.

**Target features:**
- Native Kotlin audio capture/playback (already have KotlinAudioBridge)
- Native Kotlin audio filters (already have KotlinAudioFilters)
- Native Kotlin Opus/Codec2 encoding/decoding
- Thin Python bridge for network packet transmission only

**Motivation:** Current architecture passes raw audio samples (~5760 bytes/frame) across JNI bridge every 60ms, adding 30-50ms latency per frame. This causes choppy audio and pops. By moving encoding to Kotlin, only small encoded packets (~20-60 bytes) need to cross to Python for Reticulum network transmission.

## Requirements

### Validated

- ✓ Multi-process architecture (UI + service) — existing
- ✓ LXMF messaging over Reticulum — existing
- ✓ BLE, USB (RNode), TCP interface support — existing
- ✓ Interface Discovery feature — v0.7.2
- ✓ Auto-relay selection — existing
- ✓ **PERF-01**: App maintains responsive UI regardless of background operations — v0.7.3
- ✓ **PERF-02**: No progressive performance degradation over app runtime — v0.7.3
- ✓ **PERF-03**: Interface Discovery screen scrolls smoothly — v0.7.3
- ✓ **RELAY-01**: Relay auto-selection does not loop (add/remove/add cycle) — v0.7.3
- ✓ **RELAY-02**: Root cause of automatic relay unset identified and fixed — v0.7.3
- ✓ **ANNOUNCE-01**: Clear All Announces preserves contacts in My Contacts — v0.7.3
- ✓ **OFFLINE-MAP-01**: Offline maps render correctly after extended offline periods — v0.7.3
- ✓ **UX-LOADING-01**: Show loading indicators instead of flashing empty states — v0.7.3

### Active (v0.8.0 - Kotlin LXST)

- [ ] **AUDIO-01**: Voice calls work with Kotlin-native audio processing (<5ms filter latency)
- [ ] **AUDIO-02**: Opus encoding/decoding works natively in Kotlin
- [ ] **AUDIO-03**: Codec2 encoding/decoding works natively in Kotlin
- [ ] **AUDIO-04**: Audio pipeline maintains same file/method structure as Python LXST
- [ ] **AUDIO-05**: Only encoded packets (~20-60 bytes) cross JNI bridge to Python
- [ ] **AUDIO-06**: All quality profiles (ULBW through ULL) work correctly
- [ ] **AUDIO-07**: Voice call audio has no choppy/delayed artifacts on LAN calls

### Deferred

- [ ] **NOTF-01**: No duplicate notifications after service restart for already-read messages (#338)
- [ ] **PERM-01**: Location permission dialog stays dismissed until app restart (#342)
- [ ] Native memory growth investigation (~1.4 MB/min in Python layer)

### Out of Scope

- iOS version — Android-first approach
- Desktop version — mobile focus
- Non-Android LXST implementations — this is Android-specific

## Context

**Current State (v0.7.3):**
- ~205K lines of Kotlin
- Tech stack: Kotlin, Compose, Hilt, Room, Chaquopy (Python bridge)
- Sentry performance monitoring integrated (10% transactions, 5% profiling)

**Existing Kotlin Audio Code:**
- `KotlinAudioBridge.kt` — Audio record/playback via AudioRecord/AudioTrack APIs
- `KotlinAudioFilters.kt` — HighPass, LowPass, AGC filters (<1ms latency)

**LXST Python Files to Rewrite (keeping same structure):**
- Sources.py (361 lines) — LineSource, OpusFileSource, backends
- Sinks.py (348 lines) — LineSink, OpusFileSink, backends
- Mixer.py (177 lines) — Audio mixing with gain/mute
- Network.py (145 lines) — Packetizer, LinkSource, SignallingReceiver
- Pipeline.py (58 lines) — Pipeline orchestration
- Filters.py (398 lines) — HighPass, LowPass, BandPass, AGC (already in Kotlin)
- Generators.py (134 lines) — ToneSource for dial tones
- Codecs/Codec.py (62 lines) — Base codec, resample utilities
- Codecs/Opus.py (167 lines) — Opus encoder/decoder
- Codecs/Codec2.py (121 lines) — Codec2 encoder/decoder
- Primitives/Telephony.py (732 lines) — Telephone, Profiles, Signalling
- Call.py (55 lines) — CallEndpoint (legacy)

## Constraints

- **Platform**: Android 6.0+ (API 24), ARM64 only
- **Architecture**: Must not break multi-process service model
- **Compatibility**: Encoded packets must be wire-compatible with Python LXST
- **Testing**: Audio quality should be testable with loopback calls

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Match Python file/method structure | Easier to maintain parity, review, and debug | — |
| Use android-opus-codec AAR | Production-ready, well-maintained | — |
| Use UstadMobile/Codec2-Android | Only viable Android Codec2 implementation | — |
| Reuse KotlinAudioBridge/Filters | Already working, <1ms filter latency | — |
| Python only receives encoded packets | Eliminates raw audio JNI overhead | — |
| Keep Python for Reticulum networking | Rewriting Reticulum in Kotlin out of scope | — |

---
*Last updated: 2026-02-04 — Starting v0.8.0 Kotlin LXST milestone*
