# Columba

## What This Is

Columba is an Android LXMF messenger built on the Reticulum mesh networking stack. It bridges Python (Reticulum/LXMF) with Kotlin via Chaquopy, supporting BLE, USB, and TCP interfaces for off-grid and resilient communication.

## Core Value

Reliable off-grid messaging with a polished, responsive user experience.

## Requirements

### Validated

- ✓ Multi-process architecture (UI + service) — existing
- ✓ LXMF messaging over Reticulum — existing
- ✓ BLE, USB (RNode), TCP interface support — existing
- ✓ Interface Discovery feature — v0.7.2
- ✓ Auto-relay selection — existing
- ✓ **PERF-01**: App maintains responsive UI regardless of background operations — v0.7.3
- ✓ **PERF-02**: No progressive performance degradation over app runtime — v0.7.3
- ✓ **PERF-03**: Interface Discovery screen scrolls smoothly — v0.7.3
- ✓ **RELAY-01**: Relay auto-selection does not loop (add/remove/add cycle) — v0.7.3
- ✓ **RELAY-02**: Root cause of automatic relay unset identified and fixed — v0.7.3
- ✓ **ANNOUNCE-01**: Clear All Announces preserves contacts in My Contacts — v0.7.3
- ✓ **OFFLINE-MAP-01**: Offline maps render correctly after extended offline periods — v0.7.3
- ✓ **UX-LOADING-01**: Show loading indicators instead of flashing empty states — v0.7.3

### Active

- [ ] **NOTF-01**: No duplicate notifications after service restart for already-read messages (#338)
- [ ] **PERM-01**: Location permission dialog stays dismissed until app restart (#342)
- [ ] Native memory growth investigation (~1.4 MB/min in Python layer)

### Out of Scope

- iOS version — Android-first approach
- Desktop version — mobile focus

## Context

**Current State (v0.7.3):**
- ~205K lines of Kotlin
- Tech stack: Kotlin, Compose, Hilt, Room, Chaquopy (Python bridge)
- Sentry performance monitoring integrated (10% transactions, 5% profiling)
- 137 commits, 107 files changed since v0.7.2-beta

**Known Issues:**
- Native memory growth (~1.4 MB/min) in Python/Reticulum layer — needs tracemalloc investigation
- PropagationNodeManager is large class — could extract RelaySelectionStateMachine

## Constraints

- **Platform**: Android 6.0+ (API 24), ARM64 only
- **Architecture**: Must not break multi-process service model
- **Testing**: Changes should be testable without requiring physical hardware where possible

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Focus on #340 and #343 first | Highest user impact, both high severity | ✓ Fixed in v0.7.3 |
| Defer #338 and #342 | Lower severity, can address in next milestone | — Active for v0.7.4 |
| State machine for relay selection | Explicit states prevent re-entrancy bugs | ✓ Loop eliminated |
| 1s debounce + 30s cooldown | Prevents rapid Room invalidation triggers | ✓ No cascading |
| Exponential backoff on loop detection | Graceful degradation if edge cases occur | ✓ Good |
| @Stable annotations for Compose | Reduces unnecessary recompositions | ✓ Smooth scrolling |
| SQL subquery for contact-aware delete | More efficient than app-side filtering | ✓ Good |
| Cache MapLibre style JSON locally | Enables indefinite offline map rendering | ✓ Good |
| Boolean isLoading flag pattern | Consistent with existing MapViewModel | ✓ Good |

---
*Last updated: 2026-01-28 after v0.7.3 milestone*
