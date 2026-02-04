---
phase: 10-network-bridge
plan: 01
subsystem: audio
tags: [chaquopy, coroutines, kotlin-bridge, python-interop, dispatchers-io]

# Dependency graph
requires:
  - phase: 09-mixer-pipeline
    provides: Mixer and Pipeline for audio routing
  - phase: 08-sources-sinks
    provides: Source/Sink base classes with handleFrame pattern
provides:
  - NetworkPacketBridge singleton for Kotlin-Python packet handoff
  - sendPacket() with Dispatchers.IO for non-blocking outbound
  - onPythonPacketReceived() for fast inbound callback
  - sendSignal()/onPythonSignalReceived() for signalling
affects: [10-02-packetizer, 10-03-linksource, 10-04-signalling, 11-telephony]

# Tech tracking
tech-stack:
  added: []
  patterns: [singleton-bridge, coroutine-dispatch-io, callback-fast-path]

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/NetworkPacketBridge.kt
  modified: []

key-decisions:
  - "No Log.d in hot path - logging blocks audio thread causing choppiness"
  - "Dispatchers.IO for Python calls - avoids blocking audio thread on GIL"
  - "Silent exception handling in sendPacket - packet loss acceptable for fire-and-forget"
  - "Callback-only inbound path - no processing in onPythonPacketReceived to release GIL fast"

patterns-established:
  - "Hot path rule: No synchronous I/O (logging, network) in packet handling methods"
  - "Inbound callback: Simply invoke registered callback, decode/mix on caller thread"
  - "Outbound dispatch: Launch coroutine on Dispatchers.IO for Python calls"

# Metrics
duration: 2min
completed: 2026-02-04
---

# Phase 10 Plan 01: NetworkPacketBridge Summary

**NetworkPacketBridge singleton for low-level Kotlin-Python packet handoff using Dispatchers.IO coroutines**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-04T23:32:53Z
- **Completed:** 2026-02-04T23:34:13Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created NetworkPacketBridge singleton following KotlinAudioBridge pattern
- Implemented sendPacket() and sendSignal() with Dispatchers.IO for non-blocking Python calls
- Implemented onPythonPacketReceived() and onPythonSignalReceived() with fast callback invocation
- Setup methods for Python handler and Kotlin callbacks
- No synchronous logging in packet hot path (critical for audio performance)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create NetworkPacketBridge singleton** - `2987b700` (feat)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/NetworkPacketBridge.kt` - Low-level packet handoff between Kotlin and Python (221 lines)

## Decisions Made
- **No Log.d in hot path:** All Log.i calls are in setup/teardown methods only. sendPacket and onPythonPacketReceived have zero logging to avoid blocking audio thread.
- **Silent exception handling:** Exceptions in sendPacket are caught silently - packet loss is acceptable for fire-and-forget audio.
- **Callback-only inbound:** onPythonPacketReceived simply invokes the registered callback without any processing - decoding happens on the caller's thread after GIL is released.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- NetworkPacketBridge ready for Packetizer (10-02) and LinkSource (10-03)
- Python side needs corresponding handler with send_audio_packet() and send_signal() methods
- SignallingReceiver (10-04) will use sendSignal/onPythonSignalReceived

---
*Phase: 10-network-bridge*
*Completed: 2026-02-04*
