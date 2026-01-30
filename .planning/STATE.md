# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-28)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.7.4-beta Bug Fixes - Phase 4 (Relay Loop Resolution) Complete

## Current Position

Phase: 5 of 6 (Memory Optimization)
Plan: 01 of 03 complete
Status: Executing Wave 2
Last activity: 2026-01-29 - Completed 05-01-PLAN.md

Progress: [██████░░░░░░] 50% — Phase 5 in progress (2/4 phases complete)

## Milestone Summary

**v0.7.4-beta Bug Fixes - In Progress**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 3 | ANR Elimination | ANR-01 | **Complete** |
| 4 | Relay Loop Resolution | RELAY-03 | **Complete** |
| 5 | Memory Optimization | MEM-01 | **In Progress** (1/3 plans) |
| 6 | Native Stability Verification | NATIVE-01 | Not started |

## Performance Metrics

**Velocity:**
- Total plans completed: 3
- Average duration: ~32 min
- Total execution time: ~63 min (Phases 4-5)

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 3 | 1 | - | - |
| 4 | 1 | 54 min | 54 min |
| 5 | 1/3 | 9 min | 9 min |

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
- **INSTRUMENTED in Phase 5** - Memory profiling infrastructure added (tracemalloc + native heap monitoring)
- Investigation pending in next phase

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| WhileSubscribed(5000L) for relay StateFlows | Standard Android timeout - survives screen rotation without restarting upstream | 04-01 |
| Keep state machine, debounce, loop detection | Defense-in-depth - WhileSubscribed addresses root cause, guards handle edge cases | 04-01 |
| Use tracemalloc instead of memory_profiler | tracemalloc is stdlib (no dependencies), lower overhead, sufficient for leak detection | 05-01 |
| 5-minute snapshot interval | Balances detection speed with overhead; leak grows at ~1.4 MB/min so 5min = ~7MB delta | 05-01 |
| Debug-only via BuildConfig flag | Zero overhead in release builds; profiling instrumentation stays in codebase for future debugging | 05-01 |

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
- **BuildConfig feature flags**: Clean pattern for debug-only functionality with zero release overhead
- **Synchronized multi-layer monitoring**: Align Python and Android monitoring intervals for easy correlation

### Blockers/Concerns

**Post-deployment verification needed:**
- RELAY-03-C: 48-hour zero "Relay selection loop detected" warnings in Sentry
- Fix is based on Sentry AI (Seer) recommendation + Android best practices

## Session Continuity

Last session: 2026-01-29
Stopped at: Phase 5 complete (memory profiling infrastructure added)
Resume file: None
Next: `/gsd:discuss-phase 6` or `/gsd:plan-phase 6`
