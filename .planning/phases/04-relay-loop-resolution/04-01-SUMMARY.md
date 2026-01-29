---
phase: 04-relay-loop-resolution
plan: 01
subsystem: service
tags: [kotlin, stateflow, coroutines, room, lifecycle, relay-management]

# Dependency graph
requires:
  - phase: v0.7.3 (prior release)
    provides: "PropagationNodeManager with state machine, loop detection, Sentry instrumentation"
provides:
  - "Lifecycle-aware StateFlow sharing that eliminates background database observation"
  - "WhileSubscribed(5000L) for relay StateFlows"
affects: [05-memory-optimization]

# Tech tracking
tech-stack:
  added: []
  patterns: ["WhileSubscribed(5000L) for Room-backed StateFlows"]

key-files:
  created: []
  modified:
    - "app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt"
    - "app/src/test/java/com/lxmf/messenger/service/PropagationNodeManagerTest.kt"

key-decisions:
  - "Use WhileSubscribed(5000L) per Android documentation - 5s timeout survives screen rotation"
  - "Keep state machine, debounce, and loop detection as defense-in-depth"

patterns-established:
  - "WhileSubscribed(5000L): Standard timeout for Room-backed StateFlows that should stop collecting when UI is not observing"
  - "Turbine test pattern: Keep collector active inside test block when testing code that accesses StateFlow.value with WhileSubscribed"

# Metrics
duration: 54min
completed: 2026-01-29
---

# Phase 4 Plan 01: StateFlow WhileSubscribed Summary

**Changed relay StateFlows from Eagerly to WhileSubscribed(5000L) to stop Room database observation when no UI observers present**

## Performance

- **Duration:** 54 min
- **Started:** 2026-01-29T17:14:05Z
- **Completed:** 2026-01-29T18:07:49Z
- **Tasks:** 3
- **Files modified:** 2

## Accomplishments
- Eliminated continuous Room database observation during background operation
- Changed all three relay StateFlows to lifecycle-aware sharing
- Updated 4 tests to work with WhileSubscribed behavior
- Preserved all v0.7.3 defense-in-depth mechanisms (state machine, debounce, loop detection)

## Task Commits

Each task was committed atomically:

1. **Task 1: Update StateFlow sharing strategy to WhileSubscribed** - `84dbb7ca` (fix)
2. **Task 2: Verify build and existing tests pass** - `dcbcccc2` (test)
3. **Task 3: Verify loop detection instrumentation intact** - No commit (verification only)

## Files Created/Modified
- `app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt` - Changed SharingStarted.Eagerly to WhileSubscribed(5000L) for currentRelayState, currentRelay, availableRelaysState
- `app/src/test/java/com/lxmf/messenger/service/PropagationNodeManagerTest.kt` - Fixed 4 tests to use Turbine pattern for WhileSubscribed StateFlows

## Decisions Made
- **5000L timeout**: Standard Android recommendation - survives configuration changes (screen rotation typically <2s) without restarting upstream unnecessarily
- **Test pattern**: Use Turbine to maintain active collector during test execution when testing code that accesses StateFlow.value, since WhileSubscribed requires active subscribers for .value to reflect latest state

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed test failures due to WhileSubscribed behavior**
- **Found during:** Task 2 (Verify build and tests pass)
- **Issue:** 4 tests failed because they accessed StateFlow.value after Turbine collector ended - with WhileSubscribed, .value returns initial value when no subscribers
- **Root cause analysis:** With Eagerly, StateFlow always had latest value. With WhileSubscribed(5000L), .value only reflects latest state when there's an active subscriber. Tests were ending their Turbine collectors before calling production code that accessed .value
- **Fix:** Changed tests to keep Turbine collector active during the test execution phase, ensuring production code sees correct state
- **Files modified:** PropagationNodeManagerTest.kt
- **Verification:** All 77 PropagationNodeManager tests pass
- **Committed in:** dcbcccc2 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Fix was necessary to verify code correctness. Tests now properly simulate production conditions where observeRelayChanges() maintains an active collector.

## Issues Encountered
- Test debugging took significant time due to background task execution hiding output - resolved by running tests directly with increased timeout

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- WhileSubscribed change complete and tested
- Loop detection instrumentation verified present
- Ready for post-deployment monitoring via Sentry (RELAY-03-C: 48-hour zero-warning metric)
- Phase 5 (Memory Optimization) can proceed independently

---
*Phase: 04-relay-loop-resolution*
*Completed: 2026-01-29*
