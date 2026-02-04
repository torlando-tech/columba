# Columba Threading Redesign Skill

A comprehensive Claude Code skill for systematically redesigning the Columba Android app's threading architecture across 5 implementation phases.

## Overview

This skill provides everything needed to transform the threading architecture from polling-based with blocking calls to a fully event-driven, non-blocking system. It includes:

- **5 Phase Implementation Guides** - Step-by-step instructions
- **5 Core Documentation Files** - Architecture, patterns, troubleshooting
- **5 Pattern Examples** - Before/after code transformations
- **4 Code Templates** - Boilerplate for tests and implementations
- **5 Phase Checklists** - Success criteria and verification

## Quick Start

### Activating the Skill

When working on threading-related tasks, tell Claude:

```
Use the columba-threading-redesign skill
```

Or simply mention you're working on:
- Threading refactoring
- Performance optimization
- Files in `service/` or `reticulum/protocol/` packages
- Anything related to the 5-phase threading redesign plan

### Starting Implementation

1. **Read the master plan first**: Open `THREADING_REDESIGN_PLAN.md` and `THREADING_ARCHITECTURE_ANALYSIS.md` in the root directory

2. **Review architecture overview**: `.claude/skills/columba-threading-redesign/docs/ARCHITECTURE_OVERVIEW.md`

3. **Start with Phase 1**: `.claude/skills/columba-threading-redesign/phases/phase-1-stabilization.md`

4. **Use checklist to track progress**: `.claude/skills/columba-threading-redesign/checklists/phase-1-checklist.md`

## Directory Structure

```
.claude/skills/columba-threading-redesign/
├── skill.md                    # Main skill definition
├── README.md                   # This file
├── docs/                       # Core documentation
│   ├── ARCHITECTURE_OVERVIEW.md       # Current vs target state
│   ├── KOTLIN_COROUTINE_PATTERNS.md   # Dispatcher rules, patterns
│   ├── CHAQUOPY_THREADING.md          # Python integration safety
│   ├── ANDROID_THREADING_RULES.md     # Main thread, binder constraints
│   └── TROUBLESHOOTING.md             # Common issues & solutions
├── phases/                     # Implementation guides
│   ├── README.md                      # How to use phase guides
│   ├── phase-1-stabilization.md       # Week 1: Foundation
│   ├── phase-2-event-driven.md        # Week 2: Eliminate polling
│   ├── phase-3-threading-arch.md      # Week 3: Proper dispatchers
│   ├── phase-4-ipc-simplify.md        # Week 4: Optimize IPC
│   └── phase-5-testing.md             # Ongoing: Tests & metrics
├── patterns/                   # Code transformation examples
│   ├── polling-to-flow.md             # Polling → StateFlow
│   ├── sync-to-async-ipc.md           # runBlocking → callbacks
│   ├── python-executor.md             # Single-threaded Python access
│   ├── stateflow-for-status.md        # Status distribution
│   └── dispatcher-strategy.md         # Which dispatcher when
├── templates/                  # Code boilerplate
│   ├── threading-test.kt              # Concurrent access tests
│   ├── python-safety-test.kt          # Python threading tests
│   ├── performance-metric.kt          # Metrics tracking
│   └── async-ipc-callback.aidl        # Async AIDL pattern
└── checklists/                 # Success criteria
    ├── phase-1-checklist.md           # Phase 1 verification
    ├── phase-2-checklist.md           # Phase 2 verification
    ├── phase-3-checklist.md           # Phase 3 verification
    ├── phase-4-checklist.md           # Phase 4 verification
    └── phase-5-checklist.md           # Phase 5 verification
```

## The 5 Phases

### Phase 1: Immediate Stabilization (Week 1) - CRITICAL
**Goal**: Verify Python threading safety, remove runBlocking

**Key Tasks**:
1. Create Python threading safety tests (1000+ calls)
2. Remove ALL runBlocking from IPC (ANR risk)
3. Fix database transaction nesting

**Success**: Zero runBlocking, all tests pass, < 3s initialization

### Phase 2: Replace Polling with Event-Driven (Week 2)
**Goal**: Eliminate CPU-wasting polling loops

**Key Tasks**:
1. Convert status to StateFlow
2. Smart polling with exponential backoff
3. Explicit service binding readiness

**Success**: 50% CPU reduction, instant updates (< 10ms)

### Phase 3: Threading Architecture Overhaul (Week 3)
**Goal**: Proper dispatcher usage throughout

**Key Tasks**:
1. Profile and optimize main thread
2. Create single-threaded Python executor
3. Audit all dispatcher usage

**Success**: Main thread < 16ms, correct dispatchers 100%

### Phase 4: Simplify Cross-Process Communication (Week 4)
**Goal**: Optimize IPC patterns

**Key Tasks**:
1. Service as sole initializer
2. Add sequence numbers to IPC
3. Evaluate architecture simplification

**Success**: IPC < 10ms, no initialization races

### Phase 5: Add Monitoring and Testing (Ongoing)
**Goal**: Comprehensive observability

**Key Tasks**:
1. Performance metrics dashboard
2. Threading test suite (90%+ coverage)
3. Complete documentation

**Success**: All metrics within targets, full test coverage

## Usage Examples

### When Implementing a Phase

```
I'm starting Phase 2 of the threading redesign.
Use the columba-threading-redesign skill to guide me through
converting status polling to StateFlow.
```

Claude will:
1. Reference phase-2-event-driven.md
2. Show examples from polling-to-flow.md
3. Use stateflow-for-status.md pattern
4. Check phase-2-checklist.md for success criteria

### When Encountering an Issue

```
I'm getting ANR errors in the service.
Use the columba-threading-redesign skill to help debug.
```

Claude will:
1. Check docs/TROUBLESHOOTING.md
2. Look for runBlocking in IPC calls
3. Suggest async with callbacks pattern
4. Reference sync-to-async-ipc.md

### When Writing Tests

```
I need to test Python threading safety.
Use the columba-threading-redesign skill to create tests.
```

Claude will:
1. Use templates/python-safety-test.kt
2. Reference phase-1-stabilization.md
3. Check success criteria from phase-1-checklist.md

## Key Concepts

### Research-Verified Safe Practices

✅ **Python off main thread is SAFE**
- Current fix (removing Handler.post()) is correct
- GIL provides thread safety
- No signal handler requirement on Android

✅ **GIL protects Python objects**
- Multiple Kotlin threads can call Python
- Only one executes at a time
- No explicit locking needed

### Critical Issues to Fix

❌ **runBlocking in binder threads** (HIGH - Causes ANRs)
- Blocks limited binder thread pool
- Can exhaust all 16 threads
- Must convert to async with callbacks

❌ **Polling loops** (MEDIUM - Battery drain)
- Burns CPU continuously
- Up to polling interval latency
- Must convert to StateFlow/SharedFlow

❌ **Arbitrary delays** (MEDIUM - Race workarounds)
- `delay(500)` after service binding
- Timing-dependent behavior
- Must use explicit readiness signals

## Performance Targets

All operations measured and verified:

| Metric | Current | Target | Phase |
|--------|---------|--------|-------|
| Initialization | ~2s ✅ | < 3s | 1 |
| IPC round-trip | Unknown | < 10ms | 1, 4 |
| Status propagation | ~100ms (polling) | < 10ms | 2 |
| Message delivery (active) | ~2s (polling) | < 500ms | 2 |
| CPU usage (idle) | ~5% | < 1% | 2 |
| Main thread blocking | Unknown | < 16ms | 3 |
| Frame rate | Unknown | 60 FPS | 3 |

## Integration with Other Tools

### Use Existing Claude Code Agents

**Plan Agent**: Architecture decisions
```
"Help me plan Phase 3 implementation"
"Should we use single-process or multi-process?"
```

**Explore Agent**: Find patterns in code
```
"Find all runBlocking calls in service layer"
"Where are we using polling instead of Flows?"
```

**General-Purpose Agent**: Complex refactoring
```
"Convert all status polling to StateFlow"
"Remove runBlocking from all IPC calls"
```

### Use MCP Servers

**context7**: Latest Kotlin docs
```
Get /jetbrains/kotlin coroutines documentation
Get /kotlin/kotlinx.coroutines dispatcher guidance
```

**reticulum-manual**: Python behavior
```
Query: "How does RNS handle threading?"
Query: "What are the callback patterns in Reticulum?"
```

## Success Indicators

You'll know the threading redesign is complete when:

- ✅ No ANRs in production
- ✅ 60 FPS maintained
- ✅ CPU usage < 1% when idle
- ✅ Battery life improved
- ✅ Initialization < 3 seconds consistently
- ✅ All tests pass reliably
- ✅ Code is easier to understand

## Common Questions

### Q: Can I skip phases?

**A: No.** Each phase builds on the previous. Phase 1 is the foundation - it must be solid before proceeding.

### Q: How long will this take?

**A: 4-5 weeks minimum** for Phases 1-4, Phase 5 ongoing. Don't rush - threading bugs are hard to debug.

### Q: What if I get stuck?

**A: Use the troubleshooting guide** (docs/TROUBLESHOOTING.md) or ask Claude with this skill activated.

### Q: Do I need to understand everything first?

**A: No.** Start with Phase 1, learn as you go. The guides provide all necessary context.

### Q: Is the current fix (removing Handler.post()) safe?

**A: Yes, proven safe.** See docs/CHAQUOPY_THREADING.md for research and evidence.

## Getting Help

If you encounter issues:

1. **Check troubleshooting**: `docs/TROUBLESHOOTING.md`
2. **Review pattern**: Find similar in `patterns/`
3. **Read phase guide**: Detailed steps in `phases/`
4. **Ask Claude**: Activate this skill and ask
5. **Check checklist**: Verify you've completed prerequisites

## Credits

This skill was created based on the comprehensive threading architecture analysis and redesign plan developed during the announce polling investigation session (2025-10-27).

**Source Documents**:
- `THREADING_REDESIGN_PLAN.md` - Master 5-phase plan
- `THREADING_ARCHITECTURE_ANALYSIS.md` - Problem discovery

---

## Ready to Begin?

1. Activate skill: "Use columba-threading-redesign skill"
2. Read: `phases/phase-1-stabilization.md`
3. Start: Follow Phase 1 implementation steps
4. Track: Use `checklists/phase-1-checklist.md`
5. Verify: Run all tests, check success criteria
6. Proceed: Move to Phase 2 when Phase 1 complete

**Good luck with the threading redesign!**

*This systematic approach will transform the architecture from unstable polling-based to a solid, event-driven, production-ready system.*
