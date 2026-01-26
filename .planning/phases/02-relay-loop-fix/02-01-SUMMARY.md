---
phase: 02-relay-loop-fix
plan: 01
subsystem: service
tags: [propagation-node, state-machine, flow, debounce, relay-selection]

# Dependency graph
requires:
  - phase: 01-performance-fix
    provides: Performance monitoring infrastructure (Sentry)
provides:
  - RelaySelectionState state machine preventing feedback loops
  - Debounced Flow observer for relay announces
  - User action precedence over auto-selection
affects: [02-02, 02-03, testing, relay-selection]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - State machine for preventing reactive Flow feedback loops
    - Debouncing database Flow emissions to batch rapid updates

key-files:
  created: []
  modified:
    - app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt

key-decisions:
  - "Use 1000ms debounce to batch rapid Room invalidation triggers"
  - "Use 30-second cooldown after successful selection to prevent rapid re-selection"
  - "User actions always cancel ongoing auto-selection and reset to IDLE state"

patterns-established:
  - "State machine pattern: IDLE → SELECTING → STABLE → IDLE (with cooldown)"
  - "Guard clause at Flow collection: check state before executing logic"
  - "User action precedence: manual operations cancel auto-selection immediately"

# Metrics
duration: 3min
completed: 2026-01-25
---

# Phase 02 Plan 01: Relay Selection State Machine Summary

**State machine with debouncing prevents relay auto-selection feedback loop via IDLE/SELECTING/STABLE states and 30-second cooldown**

## Performance

- **Duration:** 3 min
- **Started:** 2026-01-25T15:40:41Z
- **Completed:** 2026-01-25T15:43:40Z
- **Tasks:** 3
- **Files modified:** 1

## Accomplishments

- RelaySelectionState enum with IDLE, SELECTING, STABLE states prevents re-entrant selection
- observePropagationNodeAnnounces() debounced to batch rapid Room invalidation triggers
- User manual selection immediately cancels ongoing auto-selection and resets state

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RelaySelectionState enum and state tracking** - `4cd6cdda` (feat)
2. **Task 2: Add debounce and state guard to observePropagationNodeAnnounces** - `2ebe30b9` (feat)
3. **Task 3: Add user action precedence for state machine** - `454c4ce3` (feat)

## Files Created/Modified

- `app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt` - Added state machine to prevent relay selection feedback loop

## Decisions Made

- **Debounce duration:** 1000ms (1 second) to batch rapid Room invalidation triggers while remaining responsive
- **Cooldown duration:** 30 seconds after successful selection to prevent rapid re-selection cycles
- **User action precedence:** Manual relay selection always cancels auto-selection and resets to IDLE state immediately

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - implementation straightforward, all unit tests passed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for 02-02:** State machine foundation complete, ready to add Sentry breadcrumbs for relay selection monitoring.

**Verification needed:** Manual testing required to confirm feedback loop is resolved (deploy to device, monitor logs for repeated selection cycles).

**No blockers:** Implementation aligns with research findings, state machine correctly guards against re-entrant selection logic.

---
*Phase: 02-relay-loop-fix*
*Completed: 2026-01-25*
