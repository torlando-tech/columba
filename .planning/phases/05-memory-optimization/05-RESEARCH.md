# Phase 5: Memory Optimization - Research

**Researched:** 2026-01-29
**Domain:** Python/Android memory profiling and leak detection
**Confidence:** MEDIUM

## Summary

This phase addresses the ~1.4 MB/min memory growth causing OOM crashes in Columba's Python/Reticulum layer. The research identifies standard profiling tools, integration approaches, and verification strategies for fixing memory leaks in the Chaquopy Python-Kotlin bridge environment.

**Key findings:**
- Python's tracemalloc (stdlib) is the recommended profiling tool for initial leak detection, with snapshot comparison for leak identification
- Sentry Android SDK 8.29.0 supports memory profiling via UI profiling (trace/manual modes), but Python SDK integration with Chaquopy may be complex
- Three-layer profiling strategy needed: Python heap (tracemalloc), Kotlin-side PyObject references (manual inspection), and native memory (dumpsys meminfo)
- Multi-day verification requires real-world beta testing with Sentry OOM monitoring, not CI-based tests

**Primary recommendation:** Use tracemalloc for Python heap profiling with 5-minute interval snapshots, adb dumpsys meminfo for native memory tracking, and Sentry OOM monitoring for verification. Implement profiling toggle as developer-only build flag to keep instrumentation in codebase without user exposure.

## Standard Stack

The established libraries/tools for memory profiling in Python/Android environments:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| tracemalloc | Python 3.11 stdlib | Python heap memory profiling | Built-in, zero dependencies, low overhead, snapshot comparison |
| adb dumpsys meminfo | Android SDK | Native/Dalvik heap monitoring | Official Android debugging tool, shows process memory breakdown |
| Sentry Android SDK | 8.29.0 | OOM crash reporting with breadcrumbs | Already integrated, provides production memory context |
| LeakCanary | 2.14 | Java/Kotlin heap leak detection | Industry standard for Android, already in debug builds |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| memory_profiler | Latest PyPI | Line-by-line Python profiling | Deep investigation of specific functions (optional) |
| objgraph | Latest PyPI | Python object graph visualization | Circular reference debugging (if needed) |
| Perfetto | Android 10+ | Timeline-based memory profiling | Investigating memory spikes/patterns over time |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| tracemalloc | memory_profiler | memory_profiler requires external package, line-by-line is overkill for initial profiling |
| Sentry Python SDK | Custom telemetry | Chaquopy integration complexity unknown, tracemalloc fallback safer |
| dumpsys meminfo | Android Studio Profiler | User requirement: use command-line tools, not Android Studio |

**Installation:**
```bash
# tracemalloc is built-in to Python 3.11 (already in app via Chaquopy)
# Optional: Add memory_profiler or objgraph to requirements.txt if deep analysis needed
# pip install memory-profiler objgraph
```

## Architecture Patterns

### Recommended Profiling Architecture
```
app/
├── python/
│   ├── reticulum_wrapper.py          # Add memory snapshot hooks
│   └── memory_profiler.py            # NEW: tracemalloc snapshot manager
├── reticulum/src/main/java/
│   └── bridge/
│       └── MemoryProfiler.kt         # NEW: Kotlin-side dumpsys integration
└── app/src/main/java/
    └── settings/
        └── DeveloperSettings.kt      # Profiling toggle (hidden setting)
```

### Pattern 1: Snapshot-Based Memory Profiling
**What:** Periodic tracemalloc snapshots at fixed intervals + event-triggered snapshots (foreground, network activity)
**When to use:** Detecting gradual memory growth over hours/days
**Example:**
```python
# Source: https://docs.python.org/3/library/tracemalloc.html
import tracemalloc

# Start profiling at wrapper initialization
tracemalloc.start(nframe=10)  # Store 10 frames for callstack context

# Take periodic snapshots
snapshot1 = tracemalloc.take_snapshot()
# ... after 5 minutes of runtime ...
snapshot2 = tracemalloc.take_snapshot()

# Compare to find growing allocations
top_stats = snapshot2.compare_to(snapshot1, 'lineno')
for stat in top_stats[:10]:
    print(f"{stat.size_diff / 1024:.1f} KiB: {stat.traceback.format()[0]}")
```

### Pattern 2: Multi-Layer Memory Monitoring
**What:** Correlate Python heap (tracemalloc), native heap (dumpsys), and JNI references
**When to use:** Root cause analysis when leak source is unclear
**Example:**
```bash
# Source: https://perfetto.dev/docs/case-studies/memory
# Native/Dalvik heap breakdown
adb shell dumpsys meminfo com.lxmf.messenger | grep -A 20 "App Summary"

# Output interpretation:
# - "Native Heap" = C/C++ allocations (Python interpreter, Chaquopy JNI)
# - "Dalvik Heap" = Java/Kotlin allocations
# - Growth in Native Heap suggests Python/Chaquopy leak
```

### Pattern 3: Developer-Only Profiling Toggle
**What:** BuildConfig flag or hidden developer setting to enable profiling instrumentation
**When to use:** Keep profiling code in production builds without overhead
**Example:**
```kotlin
// In build.gradle.kts
buildTypes {
    debug {
        buildConfigField("Boolean", "ENABLE_MEMORY_PROFILING", "true")
    }
    release {
        buildConfigField("Boolean", "ENABLE_MEMORY_PROFILING", "false")
    }
}

// In MemoryProfiler.kt
if (BuildConfig.ENABLE_MEMORY_PROFILING) {
    // Start tracemalloc via Python bridge
    wrapper.callAttr("enable_memory_profiling")
}
```

### Anti-Patterns to Avoid
- **Continuous memory_profiler in production:** Excessive overhead (5-10%), use tracemalloc instead
- **Android Studio Profiler for multi-day monitoring:** Not designed for extended sessions, use dumpsys + Sentry
- **Ignoring Chaquopy PyObject references:** JNI global references won't be garbage collected, must be explicitly freed
- **Profiling without snapshots:** tracemalloc.get_traced_memory() only shows total, not allocation sources

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Memory snapshot comparison | Manual diff of allocation stats | tracemalloc.Snapshot.compare_to() | Handles filtering, sorting, size_diff calculation automatically |
| Memory leak visualization | Custom graph renderer | objgraph.show_refs() | Generates PNG with graphviz, handles circular references |
| OOM crash context | Custom crash reporter | Sentry OOM events with breadcrumbs | Already integrated, provides memory context, device info, release tracking |
| JNI reference leak detection | Manual PyObject ref counting | Android's libmemunreachable + heap dump | Android 10+ has native tools for unreachable native allocations |
| Long-running memory profiling | Custom logging loop | Perfetto trace or procstats | Efficient ring buffer, timeline visualization, kernel memory events |

**Key insight:** Memory profiling tools are deceptively complex. tracemalloc handles frame storage, filtering, and snapshot persistence. objgraph uses garbage collector internals to find retention paths. Don't reinvent these—each has edge cases (threads, native allocations, weak references) that took years to debug.

## Common Pitfalls

### Pitfall 1: Starting tracemalloc Too Late
**What goes wrong:** Allocations made before tracemalloc.start() are invisible, causing "leak" to appear elsewhere
**Why it happens:** Python imports (RNS, LXMF) allocate significant memory during module initialization
**How to avoid:** Call tracemalloc.start() as first line in reticulum_wrapper.py, before any RNS/LXMF imports
**Warning signs:** Snapshot shows no significant allocations despite visible memory growth in dumpsys

### Pitfall 2: Confusing Python Heap vs Native Heap
**What goes wrong:** tracemalloc shows stable Python heap, but native heap grows (not a Python leak, likely Chaquopy/JNI)
**Why it happens:** Python's C API uses malloc() for internal structures (not tracked by tracemalloc), PyObject references keep C memory alive
**How to avoid:** Always check dumpsys meminfo "Native Heap" alongside tracemalloc snapshots
**Warning signs:** Python allocations flat, but OOM crashes continue

### Pitfall 3: PyObject Reference Leaks in Chaquopy
**What goes wrong:** Kotlin code calls Python via Chaquopy, PyObject references not released, prevents garbage collection
**Why it happens:** Chaquopy creates JNI global references; if Kotlin doesn't call .close() on PyObject, reference persists forever
**How to avoid:** Audit all Kotlin code using Python.getInstance().getModule() for proper PyObject lifecycle (try-with-resources or explicit .close())
**Warning signs:** LeakCanary silent (Java heap fine), tracemalloc shows Python objects not freed, Native Heap grows

### Pitfall 4: Profiling Overhead Misinterpretation
**What goes wrong:** Memory profiling adds 5-10% overhead, creates false positives ("profiler itself is leaking")
**Why it happens:** tracemalloc stores frame objects, allocation metadata; memory_profiler decorators add significant RAM
**How to avoid:** Use tracemalloc.get_tracemalloc_memory() to measure profiler's overhead separately
**Warning signs:** Memory growth stops when profiling disabled, snapshots show profiler allocations dominating

### Pitfall 5: Snapshot Interval Too Aggressive
**What goes wrong:** 1-minute snapshots create I/O overhead, fill storage, obscure real leaks with profiler noise
**Why it happens:** Desire for "more data" without considering Android storage/CPU constraints
**How to avoid:** Start with 5-minute intervals; use event-triggered snapshots (foreground, network events) for precision
**Warning signs:** App jank during snapshot capture, logcat shows tracemalloc dominating CPU time

### Pitfall 6: Forgetting Reticulum Fork Boundary
**What goes wrong:** Conclude "Reticulum has a leak" without checking if issue is in Columba's patches or upstream
**Why it happens:** Reticulum code paths are complex, easy to blame external dependency
**How to avoid:** Test with upstream Reticulum (not Columba fork) to isolate leak source; check Columba patches in python/patches/RNS/
**Warning signs:** Leak in Reticulum code path, but no reports in Reticulum's issue tracker

## Code Examples

Verified patterns from official sources:

### Basic tracemalloc Setup
```python
# Source: https://docs.python.org/3/library/tracemalloc.html
import tracemalloc

# Enable at module load (before any allocations)
tracemalloc.start(nframe=10)  # Store 10 frames for useful tracebacks

# Check if profiling is active
if tracemalloc.is_tracing():
    current, peak = tracemalloc.get_traced_memory()
    print(f"Current: {current / 1024 / 1024:.1f} MiB, Peak: {peak / 1024 / 1024:.1f} MiB")
```

### Snapshot Comparison for Leak Detection
```python
# Source: https://docs.python.org/3/library/tracemalloc.html
import tracemalloc

tracemalloc.start()
snapshot1 = tracemalloc.take_snapshot()

# ... run app for 5 minutes ...

snapshot2 = tracemalloc.take_snapshot()

# Find top 10 growing allocations
top_stats = snapshot2.compare_to(snapshot1, 'lineno')
print("[ Top 10 Memory Growth ]")
for stat in top_stats[:10]:
    print(f"{stat.size_diff / 1024:.1f} KiB: {stat}")
```

### Filtering Irrelevant Allocations
```python
# Source: https://docs.python.org/3/library/tracemalloc.html
snapshot = tracemalloc.take_snapshot()

# Filter out standard library noise
snapshot = snapshot.filter_traces((
    tracemalloc.Filter(False, "<frozen importlib._bootstrap>"),
    tracemalloc.Filter(False, "<unknown>"),
))

top_stats = snapshot.statistics('lineno')
for stat in top_stats[:10]:
    print(stat)
```

### Android Native Memory Monitoring
```bash
# Source: https://developer.android.com/tools/dumpsys
# Basic memory breakdown
adb shell dumpsys meminfo com.lxmf.messenger

# Focus on heap sizes
adb shell dumpsys meminfo com.lxmf.messenger | grep -A 5 "TOTAL"

# Native Heap = Python interpreter + Chaquopy JNI
# Dalvik Heap = Java/Kotlin objects
# If Native Heap grows but Dalvik stable → Python/Chaquopy leak
```

### Sentry OOM Event Inspection
```kotlin
// Source: https://docs.sentry.io/platforms/android/
// Sentry automatically captures OOM breadcrumbs with memory context
// Check existing OOM events in Sentry dashboard:
// 1. Filter by event type: "Out of Memory"
// 2. Check "Memory" context for Native/Dalvik heap values
// 3. Review breadcrumbs for activity before crash (foreground, network)
// If memory context insufficient, upgrade SDK and enable profiling
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| memory_profiler for all profiling | tracemalloc for Python, dumpsys for native | Python 3.4+ (2014) | tracemalloc is stdlib, no dependencies, lower overhead |
| Android Studio Profiler for extended sessions | Perfetto + procstats for multi-day | Android 10 (2019) | Perfetto designed for long traces, ring buffers prevent overflow |
| Manual heap dump analysis | LeakCanary automated leak reports | LeakCanary 1.0 (2015) | Automatic detection, no manual heap dump needed |
| Sentry error-only monitoring | Sentry profiling + memory metrics | Sentry SDK 6.16.0 (2022) | Memory usage captured alongside crashes, continuous profiling available |
| JNI reference leak: manual inspection | libmemunreachable + HWASan | Android 10 (2019) | Native tools detect unreachable allocations automatically |

**Deprecated/outdated:**
- **memory_profiler for leak detection:** Still works, but tracemalloc is preferred (stdlib, lower overhead). Use memory_profiler only for line-by-line deep dives.
- **gc.get_objects() for leak hunting:** Deprecated for large apps (slow, incomplete). Use tracemalloc or objgraph instead.
- **Android Debug Database for memory queries:** Discontinued. Use Perfetto or Android Studio Profiler.

## Open Questions

Things that couldn't be fully resolved:

1. **Sentry Python SDK integration with Chaquopy**
   - What we know: Sentry Android SDK 8.29.0 supports memory profiling; Sentry Python SDK exists but Chaquopy integration is undocumented
   - What's unclear: Whether Sentry Python SDK can be installed via Chaquopy pip, if it conflicts with Android SDK, overhead implications
   - Recommendation: Start with tracemalloc fallback; attempt Sentry Python SDK integration only if time permits (best-effort per user decision)

2. **Optimal snapshot interval for 5-day runtime**
   - What we know: 5-minute intervals suggested in user decisions; Perfetto docs show variable intervals based on overhead
   - What's unclear: Actual overhead on Columba's hardware (may vary by device), storage requirements for multi-day snapshots
   - Recommendation: Start with 5 minutes, tune based on observed overhead (check CPU usage during snapshot capture via dumpsys cpuinfo)

3. **Chaquopy PyObject lifecycle best practices**
   - What we know: JNI global references must be explicitly freed; Python.getInstance() returns PyObject instances
   - What's unclear: Chaquopy's automatic reference counting behavior, when .close() is strictly necessary vs automatic cleanup
   - Recommendation: Audit all PyObject usage, add .close() in try-finally blocks proactively; verify with heap dumps

4. **Reticulum upstream memory behavior**
   - What we know: Reticulum 1.1.3 released Jan 2026; no known memory leak reports in GitHub issues
   - What's unclear: Whether Reticulum itself has memory leaks under Android/Chaquopy (most users run on Linux/macOS)
   - Recommendation: Test with unpatched upstream Reticulum to isolate leak source; if upstream leaks, report to maintainer before forking fix

5. **Success threshold for beta testing**
   - What we know: User decision "Success threshold based on current Sentry user count"
   - What's unclear: Current Sentry user count not provided in research context
   - Recommendation: Check Sentry dashboard during planning to determine beta tester count and acceptable OOM rate (e.g., zero OOMs for 7 days with N active users)

## Sources

### Primary (HIGH confidence)
- [Python tracemalloc documentation](https://docs.python.org/3/library/tracemalloc.html) - Official Python 3.11+ stdlib docs
- [Android dumpsys documentation](https://developer.android.com/tools/dumpsys) - Official Android SDK docs
- [Sentry Android profiling docs](https://docs.sentry.io/platforms/android/profiling/) - Sentry SDK 8.7.0+ profiling configuration
- [Perfetto memory debugging](https://perfetto.dev/docs/case-studies/memory) - Android native memory profiling

### Secondary (MEDIUM confidence)
- [LeakCanary documentation](https://square.github.io/leakcanary/) - Current version 2.14, Android heap leak detection
- [Sentry memory profiling changelog](https://sentry.io/changelog/2023-9-25-profiling-cpu-and-memory-usage-for-mobile-ios-android/) - Memory profiling feature announcement
- [Memory profiling in Python with tracemalloc](https://www.red-gate.com/simple-talk/development/python/memory-profiling-in-python-with-tracemalloc/) - Community guide
- [Python Memory Profiling in 2025](https://medium.com/@muruganantham52524/python-memory-profiling-in-2025-finding-leaks-with-tracemalloc-memray-and-objgraph-a0263274aea8) - Recent comparison of tools

### Tertiary (LOW confidence)
- [Chaquopy memory management best practices](https://proandroiddev.com/chaquopy-using-python-in-android-apps-dd5177c9ab6b) - Community article, not official Chaquopy docs
- [JNI reference leak detection](https://source.android.com/docs/core/tests/debug/native-memory) - Android native memory debugging (WebSearch only)
- Reticulum memory leak issues - NO results found (searched GitHub, community forums, documentation)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - tracemalloc and dumpsys are official tools with extensive documentation; Sentry Android SDK confirmed at version 8.29.0 in codebase
- Architecture: MEDIUM - Patterns verified via official docs, but Chaquopy integration specifics not fully documented (no official Chaquopy + tracemalloc guide found)
- Pitfalls: MEDIUM - Based on Python/Android best practices and WebSearch results; Chaquopy-specific pitfalls inferred from general JNI patterns
- Code examples: HIGH - All examples from official Python/Android documentation or Sentry docs

**Research date:** 2026-01-29
**Valid until:** 2026-02-28 (30 days - stable domain, Python 3.11 and Android SDK are mature)

**Research gaps:**
- Sentry Python SDK + Chaquopy integration not verified (no official documentation found)
- Reticulum-specific memory leak patterns unknown (no community reports found)
- Chaquopy PyObject lifecycle details underdocumented (inferred from JNI best practices)
- Current Sentry user count not available (needed for success threshold calculation during planning)
