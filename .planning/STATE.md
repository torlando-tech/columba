# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-28)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.7.4-beta Bug Fixes - Phase 4 (Relay Loop Resolution) Complete

## Current Position

Phase: 4 of 6 (Relay Loop Resolution)
Plan: 01 of 01 complete
Status: Phase complete
Last activity: 2026-01-29 - Completed 04-01-PLAN.md

Progress: [████░░░░░░░░] 33% — Phase 4 complete

## Milestone Summary

**v0.7.4-beta Bug Fixes - In Progress**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 3 | ANR Elimination | ANR-01 | **Complete** |
| 4 | Relay Loop Resolution | RELAY-03 | **Complete** |
| 5 | Memory Optimization | MEM-01 | Not started |
| 6 | Native Stability Verification | NATIVE-01 | Not started |

## Performance Metrics

**Velocity:**
- Total plans completed: 2
- Average duration: ~54 min
- Total execution time: ~54 min (Phase 4)

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 3 | 1 | - | - |
| 4 | 1 | 54 min | 54 min |

*Updated after each plan completion*

## Accumulated Context

### Sentry Analysis (2026-01-29)

**COLUMBA-3 (Relay Loop):**
- Still happening on v0.7.3-beta despite fix
- Stacktrace: `PropagationNodeManager.recordSelection` line 840
- Seer suggests: Use `SharingStarted.WhileSubscribed` instead of eager StateFlow
- **FIXED in Phase 4** - Changed to WhileSubscribed(5000L), pending post-deployment verification

**COLUMBA-M (ANR):**
- `DebugViewModel.<init>` -> `loadIdentityData` -> `getOrCreateDestination`
- Makes synchronous IPC call to service during ViewModel init on main thread
- **FIXED in Phase 3**

**COLUMBA-E (OOM):**
- Known ~1.4 MB/min memory growth in Python/Reticulum layer

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| WhileSubscribed(5000L) for relay StateFlows | Standard Android timeout - survives screen rotation without restarting upstream | 04-01 |
| Keep state machine, debounce, loop detection | Defense-in-depth - WhileSubscribed addresses root cause, guards handle edge cases | 04-01 |

### Roadmap Evolution

v0.7.3 milestone complete. Next milestone (v0.7.4) will address:
- #338: Duplicate notifications after service restart
- #342: Location permission dialog regression
- Native memory growth investigation

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Patterns Established

- **WhileSubscribed(5000L)**: Standard timeout for Room-backed StateFlows that should stop collecting when UI is not observing
- **Turbine test pattern**: Keep collector active inside test block when testing code that accesses StateFlow.value with WhileSubscribed

### Blockers/Concerns

**Post-deployment verification needed:**
- RELAY-03-C: 48-hour zero "Relay selection loop detected" warnings in Sentry
- Fix is based on Sentry AI (Seer) recommendation + Android best practices

## Session Continuity

Last session: 2026-01-29
Stopped at: Completed 04-01-PLAN.md (Phase 4 complete)
Resume file: None
Next: Phase 5 (Memory Optimization) or Phase 6 (Native Stability Verification)
