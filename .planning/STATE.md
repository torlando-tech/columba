# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.
**Current focus:** Phase 2 - Relay Selection Loop Fixes

## Current Position

Phase: 2 of 2 (Relay Selection Loop Fixes)
Plan: 3 of 3 complete
Status: Phase complete
Last activity: 2026-01-25 — Completed 02-03-PLAN.md (State machine and loop prevention tests)

Progress: [██████████] 100% (6/6 total plans across both phases)

## Performance Metrics

**Velocity:**
- Total plans completed: 6
- Average duration: 5m 50s
- Total execution time: 35m 1s

**By Phase:**

| Phase | Plans | Total Time | Avg/Plan |
|-------|-------|------------|----------|
| 01-performance-fix | 3/3 | 18m 42s | 6m 14s |
| 02-relay-loop-fix | 3/3 | 16m 19s | 5m 26s |

**Recent Trend:**
- Last 3 plans: 3m 3s (02-01), 5m 5s (02-02), 27m 11s (02-03)
- Trend: Variable (02-03 was comprehensive test addition - 9 new tests)

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Focus on #340 (performance) and #343 (relay loop) first — highest user impact
- Add Compose runtime dependency to data module for @Stable annotation (01-02)
- Defer Issue 1 (Native Memory Growth) to Plan 03 for Python instrumentation (01-02)
- Disable Sentry in debug builds to avoid noise during development (01-03)
- Sample 10% of transactions and profile 5% for production monitoring (01-03)
- Report janky frames via Sentry breadcrumbs for context in errors (01-03)
- Use 1000ms debounce to batch rapid Room invalidation triggers (02-01)
- Use 30-second cooldown after successful relay selection (02-01)
- User actions always cancel ongoing auto-selection and reset to IDLE state (02-01)
- 3+ selections in 60 seconds triggers loop detection warning (02-02)
- Exponential backoff: 2^(count-3) seconds, capped at 10 minutes (02-02)
- Send Sentry warning events when relay loop detected for diagnostics (02-02)
- Use MutableSharedFlow to simulate reactive announce updates in state machine tests (02-03)
- Test debounce with rapid emissions (100ms intervals) to verify batching (02-03)

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (performance)
  - ~1.4 MB/min growth in Python/Reticulum layer needs tracemalloc investigation
- **Make discovered interfaces page event-driven** (ui)
  - Pages don't update in real-time; user must re-navigate to see new data
- **Refactor PropagationNodeManager to extract components** (architecture)
  - Extract RelaySelectionStateMachine, LoopDetector; remove LargeClass suppression

Also pending from plans:
- Configure Sentry DSN for production monitoring (01-03)
- Deploy release build to verify Sentry data capture (01-03)

### Blockers/Concerns

- **Native memory growth (Issue 1):** ~1.4 MB/min in Python/Reticulum layer
  - Root cause likely in Transport.py path table or LXMRouter.py message cache
  - May require upstream Reticulum patches for bounded caches
  - Gathering data with profiling before implementing fix

## Session Continuity

Last session: 2026-01-25
Stopped at: Completed 02-03-PLAN.md - State machine and loop prevention tests
Resume file: None
Next: Phase 2 complete - All relay loop fix plans executed

## Phase 2 Completion Summary

**Phase 02 - Relay Loop Fix: COMPLETE**

All 3 plans executed successfully:
- 02-01: State machine for relay selection (3m 3s) ✓
- 02-02: Loop detection and exponential backoff (5m 5s) ✓
- 02-03: State machine and loop prevention tests (27m 11s) ✓

**Key outcomes:**
- Issue #343 (relay selection loop) addressed via state machine with guards
- Debounce (1s) prevents rapid Room invalidation triggers
- Cooldown (30s) prevents rapid re-selection
- Loop detection + exponential backoff handles edge cases
- Comprehensive test coverage (9 new tests) verifies behavior

**Testing confidence:** High - All PropagationNodeManager tests passing (32 total)

**Production readiness:**
- Ready for merge and release
- Sentry monitoring in place (from 01-03) will track relay selection events
- No pending blockers for this phase
