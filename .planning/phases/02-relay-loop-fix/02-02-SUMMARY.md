---
phase: 02-relay-loop-fix
plan: 02
subsystem: service
tags: [propagation-node, loop-detection, exponential-backoff, sentry, diagnostics]

# Dependency graph
requires:
  - phase: 01-performance-fix
    provides: Sentry integration for error tracking
  - phase: 02-01
    provides: RelaySelectionState state machine
provides:
  - Loop detection tracking 3+ selections in 60 seconds
  - BACKING_OFF state with exponential backoff (1s, 2s, 4s... max 10 min)
  - Sentry event emission when relay loop detected
  - Diagnostic logging with selection reasons
affects: [02-03, testing, relay-monitoring, sentry-dashboards]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Loop detection via sliding window of recent selections
    - Exponential backoff with capped maximum (2^n seconds, max 10 minutes)
    - Sentry integration for relay selection diagnostics

key-files:
  created: []
  modified:
    - app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt

key-decisions:
  - "3+ selections in 60 seconds triggers loop detection warning"
  - "Exponential backoff: 2^(count-3) seconds, capped at 10 minutes"
  - "Sentry events sent with WARNING level when loop detected"
  - "Selection reason logged for all paths: auto-select, relay-deleted, auto-select-enabled"

patterns-established:
  - "Loop detection pattern: sliding window with ArrayDeque, count within time window"
  - "Backoff calculation: 2^exponent * 1000ms, bounded by maxBackoffMs"
  - "Selection recording: call recordSelection() after onPropagationNodeAnnounce()"

# Metrics
duration: 5min
completed: 2026-01-25
---

# Phase 02 Plan 02: Loop Detection and Exponential Backoff Summary

**Loop detection with Sentry diagnostics prevents relay cycling by tracking selections and triggering exponential backoff (1s to 10min) when 3+ changes occur in 60 seconds**

## Performance

- **Duration:** 5 min
- **Started:** 2026-01-25T15:47:32Z
- **Completed:** 2026-01-25T15:52:10Z
- **Tasks:** 3
- **Files modified:** 1

## Accomplishments

- BACKING_OFF state added to RelaySelectionState enum for exponential backoff handling
- recordSelection() function tracks recent selections with timestamps in ArrayDeque
- Loop detection triggers when 3+ selections occur within 60 seconds, sending Sentry warning event
- Exponential backoff calculation: 2^(count-3) seconds, capped at 10 minutes maximum
- All relay selection paths (auto-select, relay-deleted, auto-select-enabled) now call recordSelection()

## Task Commits

Each task was committed atomically:

1. **Task 1: Add BACKING_OFF state and loop detection infrastructure** - `3ff216b6` (feat)
2. **Task 2: Add recordSelection function with loop detection logic** - `2979e654` (feat)
3. **Task 3: Integrate recordSelection into selection flow** - `6f364ea9` (feat)

## Files Created/Modified

- `app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt` - Added loop detection, exponential backoff, and Sentry diagnostics for relay selection

## Decisions Made

- **Loop threshold:** 3+ selections in 60 seconds triggers warning (per context decisions in 02-CONTEXT.md)
- **Backoff formula:** Exponential 2^(count-threshold) seconds (1s, 2s, 4s, 8s...) to quickly dampen loops
- **Max backoff:** 10 minutes cap prevents indefinite delays (per context decisions)
- **Sentry integration:** Capture warning-level message + breadcrumb for diagnostics when loop detected
- **Selection reasons:** Log reason string for all selection paths to aid debugging (startup, user action, loop recovery)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

**KSP cache corruption:** Initial compile attempts failed with "Storage already registered" errors from Kotlin Symbol Processing. Resolved by stopping Gradle daemon and clearing `app/build/kspCaches/` directory. This is a known Gradle/KSP issue unrelated to code changes.

## User Setup Required

None - no external service configuration required.

**Sentry events:** Loop detection will send events to Sentry (already configured in Phase 01-03). No additional setup needed, but team should monitor Sentry dashboard for "Relay selection loop detected" warnings.

## Next Phase Readiness

**Ready for 02-03:** Loop detection and backoff complete. Next plan will add comprehensive unit tests for state machine lifecycle.

**Verification needed:** Manual testing on device to confirm loop detection triggers correctly and exponential backoff prevents rapid cycling. Check Sentry dashboard for loop events during testing.

**No blockers:** Implementation follows defense-in-depth strategy. State machine (02-01) + loop detection (02-02) provide dual protection against relay cycling.

---
*Phase: 02-relay-loop-fix*
*Completed: 2026-01-25*
