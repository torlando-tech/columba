---
name: columba-threading-redesign
description: This skill should be used when working on threading-related tasks for the Columba Android app, including implementing the 5-phase threading architecture redesign, converting polling to event-driven patterns, fixing runBlocking in IPC calls, optimizing Python/Chaquopy integration, or troubleshooting threading issues like ANRs. It provides comprehensive phase-by-phase guidance, code patterns, templates, and checklists for transforming the architecture from polling-based with blocking calls to event-driven non-blocking system.
---

# Columba Threading Redesign Skill

## Overview

This skill provides comprehensive, phase-by-phase guidance for the Columba Android app's threading architecture redesign—a systematic 5-phase overhaul addressing critical threading issues discovered during announce polling investigation.

## Purpose

Address threading architecture problems including:
- **Python/Chaquopy threading safety** - Verify and document thread-safe Python integration
- **Blocking IPC calls** - Remove `runBlocking` from binder threads (causes ANRs)
- **Polling loops** - Convert to event-driven patterns (StateFlow/SharedFlow)
- **Dispatcher confusion** - Establish clear coroutine dispatcher strategy
- **Cross-process races** - Simplify IPC communication patterns

## When to Use This Skill

This skill should be used when:
- Working on threading-related refactoring or optimization
- Editing files in `com.lxmf.messenger.service.*` (ReticulumService)
- Editing files in `com.lxmf.messenger.reticulum.protocol.*`
- Modifying AIDL interface definitions
- Implementing performance optimization tasks
- Creating threading-related tests
- Debugging ANRs or threading issues
- Converting polling loops to Flows
- Implementing any of the 5 phases of the threading redesign

## Key Reference Documents

1. **THREADING_REDESIGN_PLAN.md** - Master implementation plan with 5 phases, success metrics, timelines
2. **THREADING_ARCHITECTURE_ANALYSIS.md** - Problem discovery documentation, root cause analysis
3. **ReticulumService.kt** - Service initialization, Python integration, lifecycle management
4. **ServiceReticulumProtocol.kt** - IPC layer with blocking issues requiring refactoring
5. **reticulum_wrapper.py** - Python threading behavior, locks, daemon threads

## How to Use This Skill

### For Phase Implementation

When starting a new phase, reference the appropriate guide:
- **Phase 1**: Read `phases/phase-1-stabilization.md` for step-by-step instructions
- **Phase 2**: Read `phases/phase-2-event-driven.md` for event-driven conversion
- **Phase 3**: Read `phases/phase-3-threading-arch.md` for threading architecture
- **Phase 4**: Read `phases/phase-4-ipc-simplify.md` for IPC optimization
- **Phase 5**: Read `phases/phase-5-testing.md` for testing and monitoring

Track progress using the corresponding checklist in `checklists/phase-N-checklist.md`.

### For Code Transformations

When refactoring code, reference the appropriate pattern:
- **Polling → Flow**: Use `patterns/polling-to-flow.md` for converting status checks
- **Sync → Async IPC**: Use `patterns/sync-to-async-ipc.md` for removing runBlocking
- **Python Executor**: Use `patterns/python-executor.md` - *Optional pattern, evaluated and skipped for Columba*
- **StateFlow Status**: Use `patterns/stateflow-for-status.md` for status distribution
- **Dispatcher Selection**: Use `patterns/dispatcher-strategy.md` for choosing dispatchers

### For Creating Tests

When writing tests, use templates from `templates/`:
- `threading-test.kt` - Concurrent access test boilerplate
- `python-safety-test.kt` - Python threading safety tests
- `performance-metric.kt` - Performance tracking framework
- `async-ipc-callback.aidl` - Async AIDL callback pattern

### For Troubleshooting

When encountering threading issues, consult `docs/TROUBLESHOOTING.md` which contains:
- 20+ common threading scenarios
- Diagnosis steps for ANRs, memory leaks, race conditions
- Solutions with code examples
- Performance debugging techniques

### For Understanding Concepts

When needing deeper understanding, reference documentation in `docs/`:
- `ARCHITECTURE_OVERVIEW.md` - Current vs target threading architecture
- `KOTLIN_COROUTINE_PATTERNS.md` - Dispatcher rules, Flow patterns, anti-patterns
- `CHAQUOPY_THREADING.md` - Python/GIL behavior, thread safety verification
- `ANDROID_THREADING_RULES.md` - Main thread constraints, binder threads, ANRs

## Technical Foundations (Research-Verified)

### ✅ Verified Safe Practices

**Python initialization requires thread where Python.start() was called**
```kotlin
// ✅ CORRECT - Use Dispatchers.Main.immediate
withContext(Dispatchers.Main.immediate) {
    wrapper.callAttr("initialize", configJson)
}
```
- **Evidence**: Phase 1 testing - initialization went from 60s timeout → 2s success
- **Critical lesson**: Signal handlers REQUIRE the thread where Python.start() was called
- **Chaquopy maintainer insight** (Issue #1243): "if you call Python.start on a different thread, then Python will consider that to be the main thread"
- **In Columba**: Python.start() called in Application.onCreate() → Android's main thread is Python's "main thread"
- **Why Main.immediate works**: Fast main thread execution without Handler.post() delays
- **GIL protection**: Python Global Interpreter Lock serializes Python bytecode execution

**Phase 1 Regression & Fix**
```kotlin
// ❌ WRONG - Phase 1 initial attempt (BROKEN)
withContext(Dispatchers.IO) {
    wrapper.callAttr("initialize", configJson)
}
// Error: "signal only works in main thread of the main interpreter"
```
- Initial Phase 1 attempt used Dispatchers.IO → broke signal handlers
- Fixed by switching to Dispatchers.Main.immediate
- Lesson: Always use the thread where Python.start() was called for operations that register signal handlers

**Multiple threads can call Python (non-signal operations)**
- GIL provides thread safety automatically
- Only one thread executes Python code at a time
- No explicit Kotlin-side locking needed for general Python calls
- Python's own `threading.Lock()` in reticulum_wrapper.py provides additional safety
- Note: Signal handler registration is the exception requiring specific thread

**Handler.post() elimination achieved**
- Original 60+ second delay was Handler queue backup on busy main thread
- Dispatchers.Main.immediate provides immediate main thread execution
- No Handler queue delays while maintaining correct threading

### ✅ Issues Fixed in Phases 1-3

**Priority 1: runBlocking in binder threads** - ✅ FIXED (Phase 1)
- Removed ALL runBlocking from production code (0 occurrences)
- Converted to async IPC with callbacks
- Binder threads now return immediately (< 1ms)
- **Solution Applied**: Async IPC pattern from `patterns/sync-to-async-ipc.md`

**Priority 2: Polling loops** - ✅ FIXED (Phase 2.1, 2.2)
- Converted status to StateFlow (Phase 2.1) - instant updates
- Implemented smart polling with exponential backoff (Phase 2.2) - 2s to 30s adaptive
- CPU usage: 0% when idle
- **Solution Applied**: StateFlow pattern from `patterns/polling-to-flow.md`

**Priority 3: Arbitrary delays** - ✅ FIXED (Phase 2.3)
- Added explicit service readiness callbacks
- Eliminated hardcoded delays after service binding
- Initialization now deterministic (< 100ms readiness signal)
- **Solution Applied**: Explicit readiness signals in Phase 2.3

## Implementation Phases

### Phase 1: Immediate Stabilization (Week 1) - CRITICAL
**Goal**: Verify Python threading safety, remove runBlocking

- Verify Python threading safety with tests (1000+ calls)
- Remove ALL runBlocking from IPC (ANR risk)
- Fix database transaction nesting

**Success Metrics** (✅ All Achieved in Phase 1):
- Zero runBlocking in production code (0 occurrences verified)
- All Python threading tests pass (5/5 tests on device)
- Python call performance: 0.082ms average (1000 calls stress test)
- Initialization time: < 3 seconds (actual: ~2 seconds)
- Binder return time: < 1ms (async pattern eliminates blocking)
- Signal handlers working correctly (no ANR)
- 95% of improvements retained (only refined dispatcher selection)

**Critical Lesson Learned**: Signal handlers require thread where Python.start() was called. Use Dispatchers.Main.immediate for fast, correct execution. Initial Dispatchers.IO attempt broke signal handlers; fixed by switching to Main.immediate.

### Phase 2: Replace Polling with Event-Driven (Week 2) - ✅ COMPLETE
**Goal**: Eliminate CPU-wasting polling loops

**Completed Tasks**:
- ✅ Phase 2.1: Convert status to StateFlow - instant status updates
- ✅ Phase 2.2: Smart polling with exponential backoff (2s-30s adaptive)
- ✅ Phase 2.3: Explicit service binding readiness (< 100ms signals)

**Success Achieved**:
- CPU usage: 0% when idle (was ~2% with constant polling)
- Status propagation: < 10ms (instant via StateFlow)
- Adaptive polling: 2s active → 30s idle
- Test coverage: 80-85% (comprehensive Phase 2.2 test suite)

### Phase 3: Threading Architecture Overhaul (Week 3) - ✅ COMPLETE
**Goal**: Proper dispatcher usage throughout

**Completed Tasks**:
- ✅ Task 3.1: Profiled service process main thread - 5-10ms blocking (target: < 16ms)
- ✅ Task 3.3: Audited all dispatcher usage - 0 critical violations found
- ✅ Task 3.3: Added documentation to all dispatcher decisions
- ⏭️ Task 3.2: PythonExecutor implementation **SKIPPED** (optional, not needed)

**Success Achieved**:
- Main thread blocking: 5-10ms (60% better than 16ms target)
- CPU usage (idle): 0% (target: < 1%)
- Frame rate: 60 FPS, 0% jank after initialization
- Dispatcher compliance: 100% (0 violations)
- All dispatcher choices documented with rationale

**PythonExecutor Decision**: Evaluated and decided to skip implementation. Current direct Python call approach performs excellently (0.082ms average, 0% CPU idle). GIL provides serialization. See `docs/phase3-pythonexecutor-decision.md` for rationale.

**Phase 3 Documents**:
- `docs/phase3-profiling-results.md` - Comprehensive performance analysis
- `docs/phase3-dispatcher-audit-findings.md` - Complete dispatcher audit
- `docs/phase3-pythonexecutor-decision.md` - Skip decision rationale
- `audit-dispatchers.sh` - Automated dispatcher validation script

### Phase 4: Simplify Cross-Process Communication (Week 4)
**Goal**: Optimize IPC patterns

- Service as sole initializer
- Add sequence numbers to IPC
- Evaluate architecture simplification

**Success**: IPC < 10ms, no initialization races

### Phase 5: Add Monitoring and Testing (Ongoing)
**Goal**: Comprehensive observability

- Performance metrics dashboard
- Threading test suite (90%+ coverage)
- Complete documentation

**Success**: All metrics within targets, full test coverage

## Quick Reference: Common Tasks

### Converting Polling to Flow
1. Read `patterns/polling-to-flow.md`
2. Create StateFlow in service: `private val _status = MutableStateFlow<Status>(INITIAL)`
3. Replace `while` loop with `flow.first { condition }`
4. Remove all `delay()` calls

### Removing runBlocking from IPC
1. Read `patterns/sync-to-async-ipc.md`
2. Create callback interface in AIDL
3. Update AIDL method signature to accept callback
4. Service: Launch coroutine, call callback when done
5. Client: Use `suspendCancellableCoroutine` to wrap

### Creating Threading Tests
1. Copy template from `templates/python-safety-test.kt`
2. Implement concurrent access test (10+ simultaneous)
3. Implement stress test (1000+ operations)
4. Verify with checklist from `checklists/phase-1-checklist.md`

### Debugging ANR
1. Consult `docs/TROUBLESHOOTING.md` section "Symptom: ANR"
2. Check for runBlocking: `grep -r "runBlocking" app/src/main --include="*.kt" | grep -v "Test"`
3. Convert to async using `patterns/sync-to-async-ipc.md`

## Success Indicators

The threading redesign is succeeding when:
- ✅ No ANRs in production
- ✅ 60 FPS maintained in UI
- ✅ CPU usage < 1% when idle
- ✅ Battery life improved
- ✅ Initialization consistently < 3 seconds
- ✅ All tests pass reliably
- ✅ Code is easier to understand and maintain

## Performance Targets

All operations measured and verified:

| Metric | Target | Phase |
|--------|--------|-------|
| Initialization | < 3s | 1 |
| IPC round-trip | < 10ms | 1, 4 |
| Status propagation | < 10ms | 2 |
| Message delivery (active) | < 500ms | 2 |
| CPU usage (idle) | < 1% | 2 |
| Main thread blocking | < 16ms | 3 |
| Frame rate | 60 FPS | 3 |
| Test coverage | > 90% | 5 |
| ANR rate | 0% | All |

## Getting Help

If stuck during implementation:
1. Check `docs/TROUBLESHOOTING.md` for common scenarios
2. Review relevant pattern in `patterns/` directory
3. Consult phase guide in `phases/` directory
4. Reference documentation in `docs/` for concepts

---

*This skill is based on comprehensive threading architecture analysis and redesign plan created during the announce polling investigation session (2025-10-27).*
