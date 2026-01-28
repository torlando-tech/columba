# Roadmap: Columba 0.7.2 Bug Fixes

## Overview

This milestone addresses two high-priority bugs reported after the 0.7.2 pre-release: performance degradation (#340) and relay auto-selection loop (#343). Each bug is addressed as a complete investigation-to-fix cycle within its own phase, allowing independent progress on either issue.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: Performance Fix** - Investigate and fix UI stuttering and progressive degradation
- [ ] **Phase 2: Relay Loop Fix** - Investigate and fix the relay auto-selection loop
- [x] **Phase 2.1: Clear Announces Preserves Contacts** - Fix Clear All Announces to exempt My Contacts (#365) (INSERTED)

## Phase Details

### Phase 1: Performance Fix
**Goal**: App runs smoothly without progressive degradation, especially on Interface Discovery screen
**Depends on**: Nothing (first phase)
**Requirements**: PERF-01, PERF-02, PERF-03
**Success Criteria** (what must be TRUE):
  1. User can scroll Interface Discovery screen without visible stuttering or lag
  2. User can leave app running for extended periods (30+ minutes) without UI responsiveness degrading
  3. User can interact with buttons and UI elements with immediate (<200ms) response
  4. Memory usage remains stable over time (no unbounded growth visible in profiler)
**Plans**: 3 plans in 2 waves

Plans:
- [x] 01-01-PLAN.md - Setup & Investigation (LeakCanary + profiling session + FINDINGS.md)
- [x] 01-02-PLAN.md - Apply fixes based on findings + verification profiling
- [x] 01-03-PLAN.md - Add Sentry performance monitoring for ongoing observability

### Phase 2: Relay Loop Fix
**Goal**: Relay auto-selection works correctly without add/remove cycling
**Depends on**: Nothing (independent of Phase 1)
**Requirements**: RELAY-01, RELAY-02
**Success Criteria** (what must be TRUE):
  1. User sees relay selected once and it stays selected (no repeated add/remove in logs)
  2. User can manually unset relay, and system re-selects a relay exactly once (not in a loop)
  3. Relay selection logs show clean single-selection behavior, not 40+ cycles
**Plans**: 3 plans in 2 waves

Plans:
- [ ] 02-01-PLAN.md - Add state machine to PropagationNodeManager (IDLE/SELECTING/STABLE)
- [ ] 02-02-PLAN.md - Add loop detection, backoff, and Sentry diagnostics (depends on 02-01)
- [ ] 02-03-PLAN.md - Add unit tests for state machine (depends on 02-01)

### Phase 2.1: Clear Announces Preserves Contacts (INSERTED)
**Goal**: "Clear All Announces" in the Network tab deletes all announces *except* those belonging to contacts in My Contacts, preserving the ability to open new conversations with saved contacts
**Depends on**: Nothing (independent fix)
**Requirements**: ANNOUNCE-01
**Issue**: [#365](https://github.com/torlando-tech/columba/issues/365)
**Success Criteria** (what must be TRUE):
  1. User can tap "Clear All Announces" and all non-contact announces are removed
  2. Announces belonging to contacts in My Contacts are preserved after clearing
  3. User can still open a new conversation with any saved contact after clearing announces
  4. "Node not found" error no longer appears when tapping a contact after clearing announces
**Plans**: 2 plans in 2 waves

Plans:
- [x] 02.1-01-PLAN.md — Fix DAO, Repository, ViewModel, and UI to preserve contact announces
- [x] 02.1-02-PLAN.md — Add DAO and ViewModel tests for contact-preserving deletion (depends on 02.1-01)

## Progress

**Execution Order:**
Phases 1 and 2 are independent and can be worked in any order.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Performance Fix | 3/3 | ✓ Complete | 2026-01-25 |
| 2. Relay Loop Fix | 0/3 | Not started | - |
| 2.1. Clear Announces Preserves Contacts (INSERTED) | 2/2 | ✓ Complete | 2026-01-27 |
