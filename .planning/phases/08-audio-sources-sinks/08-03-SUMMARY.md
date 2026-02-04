---
phase: 08-audio-sources-sinks
plan: 03
subsystem: audio
tags: [lxst, audio-pipeline, kotlin, sink, playback, speaker, queue-buffering]

# Dependency graph
requires:
  - phase: 08-01
    provides: LocalSink base class and float32ToBytes conversion
  - component: KotlinAudioBridge
    provides: AudioTrack wrapper for speaker playback
provides:
  - LineSink class for speaker playback with queue-based buffering
  - Auto-start playback when buffer reaches threshold
  - Underrun handling without silence insertion
  - Backpressure via canReceive() to prevent overflow
affects: [08-04-packetizer, 09-network-bridge, 10-telephony]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LinkedBlockingQueue for lock-free thread-safe buffering"
    - "Coroutine-based digest job for audio playback loop"
    - "Non-blocking offer() with oldest frame dropping on overflow"
    - "Underrun timeout stops playback (no silence insertion)"
    - "Lag dropping prevents increasing latency"

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSink.kt
  modified: []

key-decisions:
  - "LinkedBlockingQueue instead of manual locks - simpler and lock-free"
  - "Non-blocking offer with oldest drop - prevents blocking source thread"
  - "Underrun stops playback after timeout - cleaner than silence insertion"
  - "Lag dropping at bufferMaxHeight - prevents latency accumulation"
  - "Sample rate auto-detection from source - flexible configuration"

patterns-established:
  - "Queue-based buffering pattern: MAX_FRAMES depth with backpressure threshold"
  - "Auto-start pattern: digestJob launches when buffer reaches AUTOSTART_MIN"
  - "Underrun timeout pattern: track duration, stop after FRAME_TIMEOUT_FRAMES"
  - "Lag protection: drop oldest frames when queue exceeds threshold"

# Metrics
duration: 2min
completed: 2026-02-04
---

# Phase 08 Plan 03: LineSink Implementation Summary

**Speaker playback with queue buffering, autostart, and underrun handling**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-04T22:04:11Z
- **Completed:** 2026-02-04T22:05:50Z
- **Tasks:** 2
- **Files modified:** 1 (created)

## Accomplishments
- Implemented LineSink wrapping KotlinAudioBridge for speaker playback
- LinkedBlockingQueue buffering with MAX_FRAMES=6, backpressure at 3
- Auto-start playback when buffer reaches AUTOSTART_MIN (1 frame)
- Underrun handling: stops playback after FRAME_TIMEOUT_FRAMES timeout
- Lag dropping: removes oldest frames when queue exceeds threshold
- Low-latency mode support via bridge parameter
- Matches Python LXST Sinks.py:118-219 pattern exactly

## Task Commits

Each task was committed atomically:

1. **Task 1: Create LineSink.kt with queue-based buffering and autostart** - `14e55086` (feat)
2. **Task 2: Verify LineSink integrates with KotlinAudioBridge** - (no commit, verification only)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSink.kt` - Speaker playback with queue buffering, autostart, underrun handling (223 lines)

## Decisions Made
None - plan executed exactly as written. All key decisions (queue size, backpressure threshold, underrun timeout) were inherited from Python LXST and 08-RESEARCH.md.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None - compilation succeeded on first attempt. KotlinAudioBridge API matched expectations from 08-RESEARCH.md review.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for Packetizer (08-04) and future network integration:**
- LineSink.handleFrame() accepts decoded float32 audio from any source
- LineSink.canReceive() enables backpressure for smooth flow control
- Sample rate auto-detection from source works for any codec
- Low-latency mode ready for real-time voice calls

**Implementation highlights:**
- **Queue buffering:** Lock-free LinkedBlockingQueue prevents blocking source threads
- **Auto-start:** Playback begins when buffer has AUTOSTART_MIN frames (1)
- **Underrun handling:** Stops cleanly after timeout instead of inserting silence
- **Lag protection:** Drops oldest frames if queue grows beyond bufferMaxHeight
- **Flexible configuration:** Auto-detects sample rate/channels from source, or explicit configure()

**Testing readiness:**
- Compiles successfully with KotlinAudioBridge integration
- All abstract methods implemented from LocalSink base class
- Ready for instrumented testing with LineSource loopback (Phase 8 plan 08-06)

---
*Phase: 08-audio-sources-sinks*
*Completed: 2026-02-04*
