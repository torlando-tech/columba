# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-04)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.8.0 Kotlin LXST Audio Pipeline - Defining Requirements

## Current Position

Phase: Not started (defining requirements)
Plan: —
Status: Defining requirements
Last activity: 2026-02-04 — Milestone v0.8.0 started

Progress: [░░░░░░░░░░░░] 0% — Requirements phase

## Milestone Summary

**v0.8.0 Kotlin LXST Audio Pipeline - Initializing**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| TBD | TBD | TBD | Not started |

*Phases will be defined after requirements approval.*

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
- **Opus**: `io.rebble.cobble:opus-jni:1.3.0` (android-opus-codec, production-ready)
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

### Blockers/Concerns

**Wire Compatibility:**
- Encoded packets must be decode-compatible with Python LXST (not bit-identical)
- Codec2 mode headers must match (0x00-0x06)
- Opus packets must be decodable by Python pyogg with intelligible audio

**Integration Complexity:**
- `call_manager.py` wraps Python LXST Telephone — needs Kotlin bridge
- Signalling still goes through Python Reticulum links
- Must coordinate Kotlin audio thread with Python network thread

## Session Continuity

Last session: 2026-02-04
Stopped at: Milestone initialization, PROJECT.md updated
Resume file: None
Next: Continue requirements definition → `/gsd:new-milestone` completion
