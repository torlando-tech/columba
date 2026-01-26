# Columba 0.7.2 Bug Fixes

## What This Is

Columba is an Android LXMF messenger built on the Reticulum mesh networking stack. It bridges Python (Reticulum/LXMF) with Kotlin via Chaquopy, supporting BLE, USB, and TCP interfaces for off-grid and resilient communication.

This milestone focuses on fixing two high-priority bugs reported after the 0.7.2 pre-release.

## Core Value

Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.

## Requirements

### Validated

- ✓ Multi-process architecture (UI + service) — existing
- ✓ LXMF messaging over Reticulum — existing
- ✓ BLE, USB (RNode), TCP interface support — existing
- ✓ Interface Discovery feature — existing (0.7.2)
- ✓ Auto-relay selection — existing

### Active

- [ ] **PERF-01**: App maintains responsive UI regardless of background operations
- [ ] **PERF-02**: No progressive performance degradation over app runtime
- [ ] **PERF-03**: Interface Discovery screen scrolls smoothly
- [ ] **RELAY-01**: Relay auto-selection does not loop (add/remove/add cycle)
- [ ] **RELAY-02**: Root cause of automatic relay unset identified and fixed

### Out of Scope

- #338 (duplicate notifications after restart) — deferred to next milestone
- #342 (location permission dialog regression) — deferred to next milestone
- New features — this is a bug fix milestone

## Context

**Bug Reports:**
- #340: Bad stuttering with Interface Discovery, app gets slower over time
- #343: MY RELAY auto-selection loop (40+ add/remove cycles observed)

**Performance Symptoms:**
- 2-3 second lag on button presses
- Especially pronounced on Interface Discovery screen
- Gets worse over time → suggests memory leak or accumulating work
- Present even on high-end devices

**Relay Loop Symptoms:**
- Same relay added/removed repeatedly
- Even manual "Unset as Relay" immediately triggers re-selection (by design)
- The bug is whatever is triggering the automatic UNSET

**Technical Context:**
- Kotlin/Compose UI with Hilt DI
- Service runs in separate `:reticulum` process
- Python Reticulum via Chaquopy
- Interface Discovery is new in 0.7.2

## Constraints

- **Platform**: Android 6.0+ (API 24), ARM64 only
- **Architecture**: Must not break multi-process service model
- **Testing**: Changes should be testable without requiring physical hardware where possible

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Focus on #340 and #343 first | Highest user impact, both high severity | — Pending |
| Defer #338 and #342 | Lower severity, can address in next milestone | — Pending |

---
*Last updated: 2026-01-24 after initialization*
