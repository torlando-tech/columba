---
phase: 08-audio-sources-sinks
plan: 02
subsystem: audio
tags: [lxst, line-source, microphone, kotlin, audio-capture, coroutines]

# Dependency graph
requires:
  - phase: 08-audio-sources-sinks
    plan: 01
    provides: Source/Sink base classes with float32 conversion helpers
  - phase: 07-codec-foundation
    provides: Codec interface with encode/decode methods
provides:
  - LineSource class for microphone capture via KotlinAudioBridge
  - Frame size adjustment logic matching codec constraints
  - Coroutine-based audio capture loop with backpressure handling
affects: [08-04-file-sources, 08-05-network-integration, 09-packetizer]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Coroutine-based capture loop with SupervisorJob"
    - "Frame size adjustment based on codec constraints (frameQuantaMs, frameMaxMs, validFrameMs)"
    - "Push model with backpressure via canReceive() check"
    - "Phase 8 loopback: encode→decode→sink for local testing"

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSource.kt
  modified: []

key-decisions:
  - "Phase 8 loopback pattern: Source encodes then immediately decodes before pushing to sink (matches Sink API expectation of float32)"
  - "Remove private setters on sampleRate/channels to match abstract var declaration in Source base class"
  - "Bridge filters stay enabled - LineSource only applies gain multiplier on top of bridge's BPF/LPF/AGC"

patterns-established:
  - "Codec constraint adjustment: quantize→clamp→snap to valid sizes"
  - "AtomicBoolean for thread-safe running flag in coroutine-based audio loops"
  - "Graceful null handling from bridge.readAudio() with brief delay"

# Metrics
duration: 2min
completed: 2026-02-04
---

# Phase 08 Plan 02: LineSource Implementation Summary

**Microphone capture wrapping KotlinAudioBridge with codec-aware frame sizing**

## Performance

- **Duration:** 2 min 14 sec
- **Started:** 2026-02-04T22:04:09Z
- **Completed:** 2026-02-04T22:06:23Z
- **Tasks:** 2
- **Files created:** 1 (LineSource.kt)

## Accomplishments
- Created LineSource class extending LocalSource for microphone capture
- Implemented frame size adjustment matching Python LXST logic (quantize, clamp, snap)
- Coroutine-based capture loop using Dispatchers.IO + SupervisorJob
- Gain application before encoding
- Phase 8 loopback: encode→decode→sink pattern for local testing
- Backpressure handling via sink.canReceive() check

## Task Commits

Each task was committed atomically:

1. **Task 1: Create LineSource.kt with initialization and frame size adjustment** - `2b9a54c9` (feat)
2. **Task 2: Verify LineSource integrates with existing bridge and codec** - (no commit, verification only)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSource.kt` - Microphone capture class (224 lines)

## Decisions Made

**Phase 8 Loopback Pattern:**
- Sink.handleFrame() expects float32 decoded audio (not encoded bytes)
- For Phase 8 testing, LineSource encodes then immediately decodes before pushing to sink
- In production (Phase 9), encoded bytes will go over network and decode happens on remote side
- This matches the Sink API contract established in 08-01

**Property Visibility:**
- Removed `private set` modifiers on sampleRate and channels
- Base class Source declares `abstract var` which requires public setters
- Kotlin doesn't allow weakening access privilege from abstract declaration

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added decode step before sink.handleFrame()**

- **Found during:** Task 1 implementation
- **Issue:** Plan showed `sink.handleFrame(encoded, this)` passing encoded bytes, but Sink.handleFrame() signature expects `FloatArray` (decoded audio)
- **Fix:** Added `codec.decode(encoded)` step before pushing to sink for Phase 8 loopback testing
- **Files modified:** LineSource.kt (lines 191-201)
- **Commit:** 2b9a54c9
- **Rationale:** The Sink API contract (from 08-01) expects float32 decoded audio. For Phase 8 local testing, source must decode. For Phase 9 production, encoded bytes go over network and decode happens remotely.

**2. [Rule 1 - Bug] Removed private setters on sampleRate/channels**

- **Found during:** Task 1 compilation
- **Issue:** Compilation error "Cannot weaken access privilege private for 'accessor' in 'Source'"
- **Fix:** Removed `private set` modifiers to match abstract var declaration
- **Files modified:** LineSource.kt (lines 53-54)
- **Commit:** 2b9a54c9 (amended before push)
- **Rationale:** Kotlin abstract var requires public setter in implementing class

## Issues Encountered

**Compilation Error (resolved):**
- Initial implementation had `private set` on override var properties
- Kotlin doesn't allow restricting access on properties that implement abstract vars
- Fixed by removing private set modifiers

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for LineSink (08-03):**
- LineSource successfully pushes decoded float32 frames to sink
- Backpressure pattern established (canReceive check before handleFrame)
- Coroutine lifecycle pattern established (start/stop/isRunning with AtomicBoolean)

**Ready for File Sources (08-04):**
- Frame size adjustment logic can be reused for file-based sources
- Codec constraint handling pattern established

**Phase 9 Network Integration:**
- For production, remove decode step and send encoded bytes over network
- LineSource.ingestJob() lines 184-201 have comments marking Phase 8 vs Phase 9 boundaries

**Known limitation:**
- Phase 8 uses local loopback (encode→decode→sink) for testing
- Phase 9 will separate encode (local) from decode (remote) with network transmission in between

---
*Phase: 08-audio-sources-sinks*
*Completed: 2026-02-04*
