---
phase: 10-network-bridge
plan: 03
subsystem: audio
tags: [remote-source, coroutines, packet-queue, codec-switching, dispatchers-io]

# Dependency graph
requires:
  - phase: 10-network-bridge/10-01
    provides: NetworkPacketBridge for packet callback registration
  - phase: 08-sources-sinks
    provides: RemoteSource base class and Sink interface
  - phase: 07-codec-foundation
    provides: Codec base class, Null, Opus, Codec2 codecs
provides:
  - LinkSource RemoteSource for receiving encoded packets from Python
  - Queue-based packet handling with backpressure (MAX_PACKETS=8)
  - Dynamic codec switching based on header byte
  - Processing coroutine on Dispatchers.IO
affects: [10-04-signalling, 11-telephony]

# Tech tracking
tech-stack:
  added: []
  patterns: [queue-backpressure, codec-header-dispatch, processing-coroutine]

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LinkSource.kt
  modified: []

key-decisions:
  - "Queue-based packet handling: onPacketReceived queues packet, processingLoop decodes and pushes"
  - "MAX_PACKETS=8 matching Mixer backpressure from Phase 9 context"
  - "Drop oldest packet on queue full (backpressure strategy)"
  - "RAW header (0x00) maps to Null codec for simplicity"
  - "Codec2 mode header handled internally by Codec2 decoder"

patterns-established:
  - "Inbound packet pattern: Fast callback queues, coroutine processes"
  - "Codec switching: Compare class types, create new instance on mismatch"

# Metrics
duration: 2min
completed: 2026-02-04
---

# Phase 10 Plan 03: LinkSource Summary

**RemoteSource receiving encoded packets from Python via NetworkPacketBridge with queue-based decoding and dynamic codec switching**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-04T23:36:44Z
- **Completed:** 2026-02-04T23:38:30Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created LinkSource extending RemoteSource for network audio reception
- Implemented queue-based packet handling with MAX_PACKETS=8 backpressure
- Added codec header byte parsing (0xFF=Null, 0x00=Raw/Null, 0x01=Opus, 0x02=Codec2)
- Supported dynamic codec switching when remote peer changes codec mid-call
- Processing coroutine on Dispatchers.IO for non-blocking decode

## Task Commits

Each task was committed atomically:

1. **Task 1: Create LinkSource class** - `6876b56d` (feat)

## Files Created/Modified
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LinkSource.kt` - RemoteSource for receiving encoded packets (194 lines)

## Decisions Made
- **Queue-based packet handling:** onPacketReceived is called by Python with GIL held, so it must be fast. Just queues packet, no decode, no logging. processingLoop coroutine does actual decode and sink push.
- **RAW header maps to Null:** Python LXST has both RAW (0x00) and NULL (0xFF) codecs. For simplicity, both map to Null codec (raw int16 PCM).
- **Codec2 mode header internal:** Codec2 embeds mode header in its encoded data. LinkSource strips outer codec type header, Codec2.decode() handles mode switching internally.
- **MAX_PACKETS=8:** Matches Mixer backpressure constant from Phase 9 context, provides bounded queue for packet processing.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- LinkSource ready for integration with Pipeline and Telephone
- Receives packets from Python via NetworkPacketBridge callback
- Decodes and pushes float32 frames to downstream Sink
- SignallingReceiver (10-04) will handle signalling separately

---
*Phase: 10-network-bridge*
*Completed: 2026-02-04*
