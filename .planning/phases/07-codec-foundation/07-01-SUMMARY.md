---
phase: 07-codec-foundation
plan: 01
subsystem: audio
tags: [kotlin, codec, audio-processing, lxst, sample-rate-conversion]

# Dependency graph
requires:
  - phase: 07-codec-foundation
    provides: Phase context and requirements
provides:
  - Abstract Codec base class with encode/decode interface
  - Null codec for passthrough testing (int16 PCM format)
  - Audio resampling utilities (linear interpolation SRC)
  - Helper functions for float32/int16 conversion
affects: [07-02-opus-codec, 07-03-codec2-codec, 08-sources-sinks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Codec base class with abstract encode/decode methods"
    - "Float32 audio samples in range [-1.0, 1.0]"
    - "Int16 PCM bytes (little-endian) for wire format"
    - "Linear interpolation resampler for rate conversion"

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Codec.kt
    - reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/codec/CodecTest.kt
  modified: []

key-decisions:
  - "Use float32 [-1.0, 1.0] range matching Python LXST (multiply by 32767 for int16)"
  - "Use linear interpolation resampler (sufficient quality for testing phase)"
  - "Wire format: little-endian int16 PCM for Python compatibility"

patterns-established:
  - "Codec.encode(): FloatArray → ByteArray (compressed)"
  - "Codec.decode(): ByteArray → FloatArray (decompressed)"
  - "Null codec provides identity transform for testing pipeline"
  - "resample() uses linear interpolation (will upgrade to libsamplerate later)"

# Metrics
duration: 2min
completed: 2026-02-04
---

# Phase 07 Plan 01: Codec Foundation Summary

**Base Codec infrastructure with Null passthrough codec and linear interpolation resampling**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-04T21:46:08Z
- **Completed:** 2026-02-04T21:48:35Z
- **Tasks:** 2
- **Files modified:** 2 (both created)

## Accomplishments
- Abstract Codec base class establishes encode/decode interface for Opus and Codec2
- Null codec provides working passthrough implementation for pipeline testing
- Resampling utilities enable sample rate conversion between codec rates (8kHz, 48kHz)
- Wire-compatible with Python LXST Codec.py structure (int16 little-endian format)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create Codec.kt base infrastructure** - `5fad0358` (feat)
2. **Task 2: Create unit tests for Codec base classes** - `4a7f17bc` (test)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Codec.kt` - Base Codec class, Null codec, resample utilities (197 lines)
- `reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/codec/CodecTest.kt` - Unit tests for Null codec and resampling (206 lines, 16 tests passing)

## Decisions Made

**Float32 range convention:**
- Using [-1.0, 1.0] range matching Python LXST
- Multiply by 32767 for int16 conversion (not 32768)
- This means -1.0f → -32767 (0x8001), not -32768 (0x8000)
- Matches Python's np.iinfo("int16").max behavior

**Resampler choice:**
- Linear interpolation for now (simple, sufficient for codec testing)
- Quality adequate for 48kHz → 8kHz downsampling in voice codecs
- Can upgrade to libsamplerate (Kaiser-windowed sinc) in Phase 8 if needed

**Wire format:**
- Little-endian int16 PCM bytes for Python compatibility
- Null codec encodes float32 → int16 PCM (no compression)
- Matches Python LXST Null codec behavior

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

**Test assertion correction:**
- Initial test expected -1.0f → -32768 (0x8000)
- Actual encoding: -1.0f → -32767 (0x8001) due to multiply-by-32767
- Fixed test expectations to match float32 scaling convention
- This is correct behavior matching Python LXST

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for Phase 07-02 (Opus Codec):**
- Codec base class provides encode/decode interface
- Null codec validates pipeline with passthrough testing
- Resampling utilities ready for Opus 48kHz ↔ 8kHz conversion
- Test infrastructure in place for Opus round-trip verification

**Ready for Phase 07-03 (Codec2 Codec):**
- Same Codec base class works for Codec2
- Resampling utilities support Codec2's 8kHz native rate
- Test patterns established for codec verification

**No blockers:** Foundation complete for codec implementations.

---
*Phase: 07-codec-foundation*
*Completed: 2026-02-04*
