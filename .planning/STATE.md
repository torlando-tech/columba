# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.
**Current focus:** Phase 2 - Relay Selection Loop Fixes

## Current Position

Phase: 2 of 2 (Relay Selection Loop Fixes)
Plan: 1 of 3 complete
Status: In progress
Last activity: 2026-01-25 — Completed 02-01-PLAN.md (State machine for relay selection)

Progress: [████████░░] 67% (4/6 total plans across both phases)

## Performance Metrics

**Velocity:**
- Total plans completed: 4
- Average duration: 5m 36s
- Total execution time: 21m 45s

**By Phase:**

| Phase | Plans | Total Time | Avg/Plan |
|-------|-------|------------|----------|
| 01-performance-fix | 3/3 | 18m 42s | 6m 14s |
| 02-relay-loop-fix | 1/3 | 3m 3s | 3m 3s |

**Recent Trend:**
- Last 3 plans: 5m 17s (01-02), 8m 24s (01-03), 3m 3s (02-01)
- Trend: Decreasing (02-01 was straightforward state machine implementation)

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

### Pending Todos

2 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (performance)
  - ~1.4 MB/min growth in Python/Reticulum layer needs tracemalloc investigation
- **Make discovered interfaces page event-driven** (ui)
  - Pages don't update in real-time; user must re-navigate to see new data

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
Stopped at: Completed 02-01-PLAN.md - Relay selection state machine implemented
Resume file: None
Next: Continue Phase 2 with 02-02 (Sentry breadcrumbs for relay selection)
