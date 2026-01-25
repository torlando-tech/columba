---
created: 2026-01-25T09:45
title: Investigate native memory growth using Python profiling
area: performance
files:
  - python/Reticulum/RNS/Transport.py
  - python/LXMF/LXMRouter.py
  - reticulum/src/main/java/com/lxmf/messenger/reticulum/python/PythonWrapperManager.kt
  - .planning/phases/01-performance-fix/01-FINDINGS.md
---

## Problem

During Phase 1 performance investigation, profiling revealed **native memory growth of ~1.4 MB/minute** in the Reticulum/Python layer:

- Native Heap: 30.0 MB → 49.4 MB (+19.4 MB over ~14 minutes)
- Java Heap remained stable (30.8 MB → 31.3 MB)
- LeakCanary found no Java leaks, confirming the issue is in native/Python code
- The `:reticulum` process consumes 250+ MB

**Suspected causes:**
1. Python objects not being garbage collected (circular references)
2. Chaquopy JNI references not released
3. Unbounded path table growth in `RNS/Transport.py`
4. Message/announce cache growth in `LXMF/LXMRouter.py`

**Evidence:** See `.planning/phases/01-performance-fix/01-FINDINGS.md` for full profiling data and heap dumps in `profiling-data/`.

## Solution

1. Add Python memory profiling using `tracemalloc` in debug builds
2. Identify top memory consumers over time
3. If in upstream Reticulum:
   - Consider adding cache limits (LRU eviction)
   - May need to patch/fork Reticulum for bounded data structures
4. If in Chaquopy bridge:
   - Review `PythonWrapperManager.kt` for PyObject lifecycle issues
   - Ensure proper cleanup of JNI references

**Alternative workaround:** Periodic Python GC trigger or process restart if fix is complex.
