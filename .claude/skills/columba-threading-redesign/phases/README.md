# Phase Implementation Guides

This directory contains detailed, step-by-step implementation guides for each phase of the threading architecture redesign.

## Implementation Order

Phases must be completed in sequence. Do not skip ahead.

1. **Phase 1: Immediate Stabilization** (Week 1) - CRITICAL
   - Verify Python threading safety
   - Remove runBlocking from IPC
   - Fix database transaction nesting

2. **Phase 2: Replace Polling with Event-Driven** (Week 2)
   - Status updates → StateFlow
   - Smart polling for announces/messages
   - Service binding readiness

3. **Phase 3: Threading Architecture Overhaul** (Week 3)
   - Service process threading model
   - Coroutine dispatcher strategy
   - Python integration threading

4. **Phase 4: Simplify Cross-Process Communication** (Week 4)
   - Clear initialization ownership
   - IPC pattern improvements
   - Architecture simplification evaluation

5. **Phase 5: Add Monitoring and Testing** (Ongoing)
   - Performance metrics
   - Threading test suite
   - Documentation

## How to Use These Guides

### Before Starting a Phase

1. Read the entire phase guide
2. Review referenced pattern examples (`../patterns/`)
3. Check success criteria in checklist (`../checklists/`)
4. Ensure previous phase is complete

### During Implementation

1. Follow steps in order
2. Use provided code examples
3. Add tests for each change
4. Verify measurements match targets
5. Document inline as you go

### After Completing a Phase

1. Run all tests (unit, integration, threading)
2. Verify all success criteria met
3. Check metrics against targets
4. Update documentation
5. Commit with clear message

## Success Criteria

Each phase has specific, measurable outcomes. Don't proceed to next phase until all criteria met.

### Phase 1
- ✅ 10+ concurrent Python calls pass
- ✅ 1000 rapid Python invocations succeed
- ✅ Zero runBlocking in production code
- ✅ No nested transaction warnings

### Phase 2
- ✅ 50% CPU reduction (idle)
- ✅ Status propagation < 10ms
- ✅ No polling loops for status
- ✅ No arbitrary delays

### Phase 3
- ✅ Main thread blocking < 16ms
- ✅ All Python calls use executor
- ✅ 100% correct dispatcher usage
- ✅ No frame drops

### Phase 4
- ✅ IPC latency < 10ms consistently
- ✅ Only service initializes
- ✅ All messages have sequence numbers
- ✅ No initialization races

### Phase 5
- ✅ All critical paths instrumented
- ✅ 90%+ test coverage
- ✅ Stress tests pass
- ✅ Architecture documented

## Risk Management

### High-Risk Changes

- **Phase 1.2** (Remove runBlocking): Could break IPC
  - Mitigation: Extensive testing, feature flag
- **Phase 2.1** (Polling → Flows): Could miss events
  - Mitigation: Verify all events captured
- **Phase 3.3** (Python executor): Could serialize unnecessarily
  - Mitigation: Measure performance impact

### Rollback Strategy

- Keep old code behind feature flag
- Can revert individual phases
- Comprehensive logging for diagnosis
- Beta test before production

## Getting Help

If stuck during implementation:
1. Check `../docs/TROUBLESHOOTING.md`
2. Review pattern in `../patterns/`
3. Consult `../docs/` for concepts
4. Use Plan agent for guidance
5. Use Explore agent to find examples

---

*Proceed to phase-1-stabilization.md to begin implementation.*
