# Phase 5: Memory Optimization - Context

**Gathered:** 2026-01-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Profile Python/Reticulum memory usage, identify leaks causing ~1.4 MB/min growth, and implement fixes so the app survives 5+ days continuous runtime without OOM crashes. MapLibre native memory is out of scope (handled in Phase 6).

</domain>

<decisions>
## Implementation Decisions

### Profiling approach
- Claude chooses profiling tool (tracemalloc, memory_profiler, or other based on codebase)
- Start with local Python profiling, verify findings on physical device
- Profile both Python heap AND Kotlin-side memory
- Use command-line tools (`adb shell dumpsys meminfo`) rather than Android Studio profiler
- Follow Sentry best practices for Android memory profiling
- Upgrade Sentry SDK if needed to get memory profiling capabilities
- Best effort Sentry Python SDK integration - fall back to tracemalloc if complex
- Memory snapshots: fixed 5-minute intervals + event-triggered (foreground, network activity)
- Keep profiling instrumentation in codebase with developer-only toggle (hidden setting/build flag)
- Check Sentry OOM events first to see if existing memory context is useful

### Fix strategy
- Fix aggressiveness depends on severity: small leaks get patches, big leaks get proper fixes
- Target near-zero growth (< 0.1 MB/min) - effectively stable over days
- For Reticulum leaks: apply fixes to existing fork, but triple-check before concluding Reticulum itself is leaky
- Minimize API breakage - accept small interface changes but avoid major refactors

### Verification method
- Real-world monitoring: ship to beta testers, monitor Sentry for OOMs
- Success threshold based on current Sentry user count (check during research)
- Memory metrics are internal only - not surfaced to users
- No automated CI long-running tests

### Scope boundaries
- Focus: Python/Reticulum memory first, then Kotlin if issues remain
- Include: Chaquopy bridge layer - investigate potential PyObject reference leaks
- Exclude: MapLibre native memory (leave to Phase 6)
- Profiling toggle: developer-only (hidden setting or build flag, not user-facing)

### Claude's Discretion
- Specific profiling tool selection
- Snapshot interval tuning based on observed overhead
- Sentry SDK version to upgrade to
- Implementation details of profiling toggle

</decisions>

<specifics>
## Specific Ideas

- "Triple-check before concluding Reticulum itself has a memory leak" - be thorough before patching the fork
- Follow Sentry's Android memory profiling best practices
- Check existing Sentry OOM events for memory context before building new instrumentation

</specifics>

<deferred>
## Deferred Ideas

None - discussion stayed within phase scope

</deferred>

---

*Phase: 05-memory-optimization*
*Context gathered: 2026-01-29*
