# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.
**Current focus:** Phase 2.2 - Offline Map Tile Rendering

## Current Position

Phase: 2.2 (Offline Map Tile Rendering)
Plan: 1 of 2 complete
Status: In progress
Last activity: 2026-01-28 — Completed 02.2-01-PLAN.md (Cache style JSON during download)

Progress: [███████████░] 90% (9/10 total plans: 6 from phases 1-2 + 2/2 from phase 2.1 + 1/2 from phase 2.2)

## Performance Metrics

**Velocity:**
- Total plans completed: 9
- Average duration: 6m 49s
- Total execution time: 61m 35s

**By Phase:**

| Phase | Plans | Total Time | Avg/Plan |
|-------|-------|------------|----------|
| 01-performance-fix | 3/3 | 18m 42s | 6m 14s |
| 02-relay-loop-fix | 3/3 | 16m 19s | 5m 26s |
| 02.1-clear-announces | 2/2 | 8m 12s | 4m 6s |
| 02.2-offline-maps | 1/2 | 18m 22s | 18m 22s |

**Recent Trend:**
- Last 3 plans: 2m 58s (02.1-01), 5m 14s (02.1-02), 18m 22s (02.2-01)
- Trend: Longer for database migrations + test fixes, faster for focused changes

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
- Use SQL subquery (NOT IN) for contact-aware filtering instead of joins (02.1-01)
- Preserve original deleteAllAnnounces() for backward compatibility and testing (02.1-01)
- Fall back to deleteAllAnnounces() if no active identity (02.1-01)
- Launch style JSON fetch in separate coroutine (non-blocking) to avoid delaying UI updates (02.2-01)
- Use 5-second timeout on URL fetch to prevent infinite hangs in tests and slow networks (02.2-01)

### Roadmap Evolution

- Phase 2.1 inserted after Phase 2: Clear Announces Preserves Contacts — #365 (URGENT)
  - "Clear All Announces" deletes contact announces, breaking ability to open new conversations
  - Fix: exempt My Contacts announces from the bulk delete
- Phase 2.2 inserted after Phase 2.1: Offline Map Tile Rendering — #354 (URGENT)
  - Downloaded offline maps stop rendering after extended offline period (days)
  - Likely cause: offline code path still uses network style URL, so MapLibre can't resolve layer definitions when fully offline
  - Fix: ensure offline style loading explicitly uses local tile data without network dependency

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

Last session: 2026-01-28
Stopped at: Completed 02.2-01-PLAN.md - Cache style JSON during download
Resume file: None
Next: Plan 02.2-02 - Load cached style for offline rendering

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

## Phase 2.1 Completion Summary

**Phase 02.1 - Clear Announces Preserves Contacts: COMPLETE**

All 2 plans executed successfully:
- 02.1-01: Identity-aware announce deletion (2m 58s) ✓
- 02.1-02: Test contact-preserving deletion (5m 14s) ✓

**Key outcomes:**
- Issue #365 (Clear All deletes contacts) fixed via SQL subquery
- deleteAllAnnouncesExceptContacts preserves contact announces for active identity
- ViewModel routes to identity-aware delete, falls back to old method if no identity
- SQL behavior validated with 6 DAO tests using real Room database
- Identity-aware routing verified with 3 ViewModel tests using MockK

**Testing confidence:** High - All tests pass (DAO + ViewModel)

**Production readiness:**
- Ready for merge and release
- Fixes critical UX bug preventing users from opening conversations with saved contacts
- No pending blockers for this phase
