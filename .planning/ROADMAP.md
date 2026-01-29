# Roadmap: Columba

## Milestones

- v0.7.3-beta (Phases 1-2.3) - shipped 2026-01-28
- **v0.7.4-beta Bug Fixes** (Phases 3-6) - in progress

## Phases

<details>
<summary>v0.7.3-beta (Phases 1-2.3) - SHIPPED 2026-01-28</summary>

See git history for v0.7.3-beta milestone.

</details>

### v0.7.4-beta Bug Fixes (In Progress)

**Milestone Goal:** Address critical production issues identified via Sentry monitoring.

- [x] **Phase 3: ANR Elimination** - Fix synchronous IPC on main thread
- [x] **Phase 4: Relay Loop Resolution** - Investigate and fix COLUMBA-3 regression
- [ ] **Phase 5: Memory Optimization** - Address OOM-causing memory growth
- [ ] **Phase 6: Native Stability Verification** - Verify MapLibre crashes after memory fix

## Phase Details

### Phase 3: ANR Elimination
**Goal**: ViewModel initialization never blocks the main thread
**Depends on**: Nothing (quick win, independent)
**Requirements**: ANR-01
**Success Criteria** (what must be TRUE):
  1. DebugViewModel initializes without blocking main thread (no ANR on screen open)
  2. All IPC calls to service occur on IO dispatcher
  3. Zero ANRs from IPC in Sentry for 48 hours post-release
**Plans**: 1 plan

Plans:
- [x] 03-01-PLAN.md - Move IPC methods to IO dispatcher with suspend functions

### Phase 4: Relay Loop Resolution
**Goal**: Relay selection completes without looping under any conditions
**Depends on**: Nothing (independent of Phase 3)
**Requirements**: RELAY-03
**Success Criteria** (what must be TRUE):
  1. Relay selection settles on a single node without add/remove/add cycles
  2. StateFlow uses `SharingStarted.WhileSubscribed` (per Seer recommendation)
  3. Zero "Relay selection loop detected" warnings in Sentry for 48 hours
**Plans**: 1 plan

Plans:
- [x] 04-01-PLAN.md - Change StateFlow sharing from Eagerly to WhileSubscribed(5000L)

### Phase 5: Memory Optimization
**Goal**: App runs indefinitely without OOM crashes
**Depends on**: Nothing (independent, but largest scope)
**Requirements**: MEM-01
**Success Criteria** (what must be TRUE):
  1. Memory growth rate reduced to sustainable level (measurable via profiling)
  2. App survives 5+ days continuous runtime without OOM (verified via extended testing)
  3. Zero OOM crashes in Sentry for 7 days post-release
**Plans**: 3 plans

Plans:
- [ ] 05-01-PLAN.md - Add memory profiling infrastructure (tracemalloc + build flag)
- [ ] 05-02-PLAN.md - Identify and fix memory leaks
- [ ] 05-03-PLAN.md - Verify fixes with extended runtime testing

### Phase 6: Native Stability Verification
**Goal**: Confirm MapLibre native crashes are resolved by memory optimization
**Depends on**: Phase 5 (crashes likely secondary to memory pressure)
**Requirements**: NATIVE-01
**Success Criteria** (what must be TRUE):
  1. No MapRenderer SIGSEGV crashes in extended testing
  2. MapLibre abort crashes eliminated or reduced to near-zero
  3. No recurring native crashes in Sentry for 7 days post-release
**Plans**: TBD

Plans:
- [ ] 06-01: Verify native stability after memory fix

## Progress

**Execution Order:** Phases execute in numeric order: 3 -> 4 -> 5 -> 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 3. ANR Elimination | 1/1 | Complete | 2026-01-29 |
| 4. Relay Loop Resolution | 1/1 | Complete | 2026-01-29 |
| 5. Memory Optimization | 0/3 | Not started | - |
| 6. Native Stability Verification | 0/1 | Not started | - |
