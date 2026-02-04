---
phase: 10-network-bridge
plan: 02
subsystem: audio
tags: [kotlin-bridge, remotesink, codec-header, packetizer, network-transmit]

# Dependency graph
requires:
  - phase: 10-01
    provides: NetworkPacketBridge singleton with sendPacket() for non-blocking Python calls
  - phase: 08-sources-sinks
    provides: RemoteSink base class with handleFrame pattern
  - phase: 07-codec-foundation
    provides: Codec base class with Null, Opus, Codec2 implementations
provides:
  - Packetizer RemoteSink for Kotlin-to-Python audio transmission
  - Codec header byte prepending (0xFF=Null, 0x00=Raw, 0x01=Opus, 0x02=Codec2)
  - Non-blocking sendPacket integration
affects: [10-03-linksource, 10-04-signalling, 11-telephony]

# Tech tracking
tech-stack:
  added: []
  patterns: [codec-header-byte-format, hot-path-no-logging]

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/Packetizer.kt
  modified: []

key-decisions:
  - "Codec via property not source: var codec instead of accessing source.codec for clean transmit path"
  - "Silent exception in handleFrame: Drop frames on encode failure, don't disrupt audio thread"
  - "No synchronous logging in handleFrame: Critical for audio performance"

patterns-established:
  - "Wire format: [codec_header_byte (1 byte)] + [encoded_frame (N bytes)]"
  - "Header byte mapping: 0xFF=Null, 0x00=Raw, 0x01=Opus, 0x02=Codec2 (matches Python LXST)"

# Metrics
duration: 1min
completed: 2026-02-04
---

# Phase 10 Plan 02: Packetizer Summary

**RemoteSink that encodes float32 frames and sends to Python Reticulum with codec header byte prepended**

## Performance

- **Duration:** 1 min
- **Started:** 2026-02-04T23:36:25Z
- **Completed:** 2026-02-04T23:37:29Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created Packetizer extending RemoteSink matching Python LXST Network.py
- Implemented codec header byte constants (CODEC_NULL=0xFF, CODEC_RAW=0x00, CODEC_OPUS=0x01, CODEC_CODEC2=0x02)
- handleFrame encodes float32 with codec, prepends header byte, calls bridge.sendPacket
- No synchronous logging in audio hot path (handleFrame)
- canReceive returns true when running (network handles backpressure)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create Packetizer class** - `5de4fe9f` (feat)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/Packetizer.kt` - RemoteSink for outbound packet transmission (163 lines)

## Decisions Made
- **Codec via property:** Using `var codec: Codec?` property instead of accessing codec from source. This provides a clean interface for the transmit path where Pipeline sets the codec directly.
- **Silent exception handling:** Encode exceptions in handleFrame silently drop frames - logging would block audio thread and encode failures are recoverable (next frame will succeed).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Packetizer ready for Pipeline integration (can be used as sink for transmit path)
- LinkSource (10-03) will be the matching RemoteSource for inbound packets
- SignallingReceiver (10-04) will use NetworkPacketBridge for signalling

---
*Phase: 10-network-bridge*
*Completed: 2026-02-04*
