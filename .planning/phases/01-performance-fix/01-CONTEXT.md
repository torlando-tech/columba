# Phase 1: Performance Fix - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Investigate and fix UI stuttering and progressive performance degradation reported in #340. The issue is especially pronounced on the Interface Discovery screen but affects the app generally. Interface Discovery is a UI wrapper around the Python Reticulum library's discovery feature (introduced in RNS 1.1.0).

</domain>

<decisions>
## Implementation Decisions

### Investigation Approach
- Set up Android Studio profiler for investigation
- Cast a wider net first — profile multiple screens to understand the scope, then focus on worst cases
- Use profiler only — minimize code changes during investigation (no temporary diagnostic logging)
- Timeline for "gets slower over time" is unknown — will need to determine through profiling

### Fix Scope
- Fix all issues found during investigation, not just the primary cause
- Refactoring is acceptable if it's the proper fix, even if larger in scope
- Interface Discovery is a UI around Python Reticulum's discovery feature — if the issue is in Python, fork and fix (you already maintain Reticulum forks)

### Verification Method
- Both profiler comparison AND manual testing
- 15-30 minute soak test to verify no progressive degradation
- Verify on your high-end phone (if it's slow there, it's definitely a bug)
- Focus verification on fixed areas only — trust existing tests for regression
- No before/after recordings needed
- Your testing is sufficient — no need to ask reporter to verify
- Add Sentry/crash reporting for slow frames or ANRs going forward

### Claude's Discretion
- Specific profiling methodology
- Order of investigation across screens
- Which performance metrics to track

</decisions>

<specifics>
## Specific Ideas

- Interface Discovery comes from Python Reticulum 1.1.0 — Columba built UI around it
- Reporter (serialrf433) says it's correlated with Interface Discovery being enabled
- List typically has 10-50 items (moderate size)
- Not yet tested whether slowdown happens without Discovery enabled — worth checking during investigation

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-performance-fix*
*Context gathered: 2026-01-24*
