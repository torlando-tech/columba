# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.
**Current focus:** Phase 2.3 - Loading States for Tabs

## Current Position

Phase: 2.3 (Loading States for Tabs)
Plan: 1 of 1 complete
Status: Phase complete
Last activity: 2026-01-28 — Completed 02.3-01-PLAN.md (Add loading states to Chats and Contacts tabs)

Progress: [████████████] 100% (11/11 total plans: 6 from phases 1-2 + 2/2 from phase 2.1 + 2/2 from phase 2.2 + 1/1 from phase 2.3)

## Performance Metrics

**Velocity:**
- Total plans completed: 11
- Average duration: 7m 10s
- Total execution time: 78m 47s

**By Phase:**

| Phase | Plans | Total Time | Avg/Plan |
|-------|-------|------------|----------|
| 01-performance-fix | 3/3 | 18m 42s | 6m 14s |
| 02-relay-loop-fix | 3/3 | 16m 19s | 5m 26s |
| 02.1-clear-announces | 2/2 | 8m 12s | 4m 6s |
| 02.2-offline-maps | 2/2 | 26m 22s | 13m 11s |
| 02.3-loading-states | 1/1 | 9m 12s | 9m 12s |

**Recent Trend:**
- Last 3 plans: 18m 22s (02.2-01), 8m (02.2-02), 9m 12s (02.3-01)
- Trend: UI-only changes consistently fast (~9 min)

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
- Use fromJson() instead of fromUri() for local style files to avoid HTTP cache dependency (02.2-02)
- Fall back to HTTP style URL if cached style file doesn't exist (backward compatibility) (02.2-02)
- Use boolean isLoading flag pattern (consistent with MapViewModel) instead of sealed class UiState (02.3-01)
- Use map{} operator on Flow to wrap data with state, simpler than combine() (02.3-01)
- Loading state only for initial load, not during search filtering (02.3-01)

### Roadmap Evolution

- Phase 2.1 inserted after Phase 2: Clear Announces Preserves Contacts — #365 (URGENT)
  - "Clear All Announces" deletes contact announces, breaking ability to open new conversations
  - Fix: exempt My Contacts announces from the bulk delete
- Phase 2.2 inserted after Phase 2.1: Offline Map Tile Rendering — #354 (URGENT)
  - Downloaded offline maps stop rendering after extended offline period (days)
  - Likely cause: offline code path still uses network style URL, so MapLibre can't resolve layer definitions when fully offline
  - Fix: ensure offline style loading explicitly uses local tile data without network dependency
- Phase 2.3 inserted after Phase 2.2: Loading States for Tabs — #341 (URGENT) - **COMPLETE**
  - "No conversations yet" / "No contacts yet" flash while data loads, especially after onboarding Skip
  - Root cause: StateFlows use `initialValue = emptyList()`, UI checks `isEmpty()` without checking loading state
  - Fix: Add UiState wrapper with Loading state, check loading before showing empty (follow Map tab pattern)

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
Stopped at: Completed 02.3-01-PLAN.md - Add loading states to Chats and Contacts tabs
Resume file: None
Next: All planned phases complete (Phase 1, 2, 2.1, 2.2, 2.3)

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

## Phase 2.2 Completion Summary

**Phase 02.2 - Offline Map Tile Rendering: COMPLETE**

All 2 plans executed successfully:
- 02.2-01: Cache style JSON during download (18m 22s) ✓
- 02.2-02: Load cached style for offline rendering (8m) ✓

**Key outcomes:**
- Issue #354 (offline maps lose rendering after days) resolved
- Style JSON cached during offline map download to local files
- MapLibre loads style from local JSON files when offline (not HTTP URL)
- Full vector tile detail (roads, buildings, labels) renders indefinitely offline
- Graceful fallback for regions without cached style (backward compatibility)
- On-device verification confirmed working behavior

**Technical implementation:**
- Database migration 34→35 adds `localStylePath` column to offline map regions
- Smart style resolution: check cache file → fall back to HTTP URL
- MapScreen uses `Style.Builder().fromJson()` for offline local styles (not `fromUri()`)
- Network disabled (allowNetwork = false) for offline modes
- Non-blocking style caching with 5-second timeout

**Testing confidence:** High - On-device testing confirmed, all unit tests pass

**Production readiness:**
- Ready for merge and release
- Resolves critical offline UX bug (maps unusable after days offline)
- Safe database migration (nullable column addition)
- No regressions in online map functionality
- No pending blockers for this phase

## Phase 2.3 Completion Summary

**Phase 02.3 - Loading States for Tabs: COMPLETE**

All 1 plan executed successfully:
- 02.3-01: Add loading states to Chats and Contacts tabs (9m 12s) ✓

**Key outcomes:**
- Issue #341 (empty state flash) resolved
- ChatsState wraps conversations with isLoading flag
- ContactsState wraps grouped contacts with isLoading flag
- LoadingConversationsState and LoadingContactsState composables added
- Both screens check isLoading before showing empty state

**Technical implementation:**
- State wrapper pattern: `data class XState(val data: T, val isLoading: Boolean = true)`
- Flow transformation: `flow.map { XState(data = it, isLoading = false) }`
- UI check: `when { isLoading -> Loading; isEmpty -> Empty; else -> Content }`
- Consistent with MapViewModel boolean flag pattern

**Testing confidence:** High - Build verification passes, code patterns verified

**Production readiness:**
- Ready for merge and release
- Fixes confusing UX where users see "No conversations/contacts yet" flash
- No regressions in existing functionality
- Pattern established for any future tabs needing loading states
