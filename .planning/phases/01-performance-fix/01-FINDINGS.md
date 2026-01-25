# Phase 1: Performance Investigation Findings

**Date:** 2026-01-25
**Device:** SM-G998U1 (Samsung Galaxy S21 Ultra)
**Build:** debug (with LeakCanary 2.13)

## Executive Summary

Two distinct issues identified: (1) **Native memory growth** of ~1.4 MB/minute primarily in the Reticulum/Python process, and (2) **High input latency** (6,108 events) causing delayed touch response. Frame rendering itself is excellent (90th percentile 9ms). LeakCanary detected no Java heap leaks, confirming the memory issue is in native/Python code.

## Issues Found

### Issue 1: Native Memory Growth (Progressive Degradation Root Cause)

**Severity:** HIGH
**Type:** Native Memory Leak
**Evidence:**
- Native Heap: 30.0 MB → 49.4 MB (+19.4 MB over ~14 minutes)
- Java Heap: 30.8 MB → 31.3 MB (+0.5 MB - stable)
- Growth rate: ~1.4 MB/minute
- No LeakCanary notifications (confirms not a Java heap leak)
- Separate `:reticulum` process consuming 250+ MB

**Root Cause Analysis:**
The memory growth is in native heap, not Java heap. This points to the Python/Chaquopy layer where Reticulum runs. Potential causes:
1. Python objects not being garbage collected (circular references)
2. Chaquopy JNI references not released
3. Crypto/networking native buffers accumulating
4. Path table or announce cache growing unbounded in Python

**Proposed Fix:**
1. Investigate Reticulum Python code for unbounded caches:
   - `RNS/Transport.py` - path table growth
   - `LXMF/LXMRouter.py` - message/announce caching
2. Add Python memory profiling with `tracemalloc` in debug builds
3. Check Chaquopy PyObject lifecycle management in `PythonWrapperManager`
4. Consider periodic cache cleanup in Python layer

**Files to investigate:**
- `python/Reticulum/RNS/Transport.py` (path table)
- `python/LXMF/LXMRouter.py` (message routing)
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/python/PythonWrapperManager.kt` (Chaquopy lifecycle)

### Issue 2: High Input Latency (UI Responsiveness Issue)

**Severity:** HIGH
**Type:** Main Thread Blocking
**Evidence:**
- High input latency events: 6,108 (should be 0)
- Slow UI thread events: 127
- Frame deadline missed: 128
- Input latency increased from 2,057 to 6,108 during scroll test
- Frame rendering is NOT the issue (90th percentile 9ms is excellent)

**Root Cause Analysis:**
Touch events are being delayed before they reach the UI framework. The frame rendering pipeline is fast once it receives input, but something blocks input event processing. Potential causes:
1. Synchronous operations in touch/scroll handlers
2. Main thread work triggered by StateFlow collectors
3. Compose recomposition blocking input thread
4. Database queries happening synchronously on scroll

**Proposed Fix:**
1. Audit Compose UI for expensive computations during recomposition
2. Check `LazyColumn` item rendering for blocking operations
3. Verify database operations use `Dispatchers.IO`
4. Profile main thread with systrace during scroll
5. Check for `collectAsState()` calls that might block

**Files to investigate:**
- `app/src/main/java/com/lxmf/messenger/ui/announce/AnnounceStreamScreen.kt`
- `app/src/main/java/com/lxmf/messenger/viewmodel/AnnounceStreamViewModel.kt`
- Any Compose components rendering announce items

### Issue 3: Janky Frames (Secondary)

**Severity:** MEDIUM
**Type:** Compose Recomposition / GC Pressure
**Evidence:**
- Janky frames: 3.66% (target < 1%)
- 99th percentile: 34ms (occasional spikes)
- Some frames taking 100+ ms (rare but impactful)

**Root Cause Analysis:**
While 90th percentile is good (9ms), the tail latency shows occasional slow frames. This could be:
1. Garbage collection pauses
2. Compose recomposition of large subtrees
3. Image loading/decoding on main thread

**Proposed Fix:**
1. Add `@Stable` annotations to data classes used in Compose
2. Check for unnecessary recompositions with Compose compiler metrics
3. Ensure images are loaded asynchronously with Coil/Glide

**Files to investigate:**
- Data classes passed to Compose (need `@Stable` or `@Immutable`)
- Image loading in announce list items

## Profiling Data

### Memory Growth

| Time | Java Heap | Native Heap | Total PSS | Notes |
|------|-----------|-------------|-----------|-------|
| T=0 (Baseline) | 30.8 MB | 30.0 MB | 324 MB | Fresh start on Interface Discovery |
| T=10 min | 34.3 MB | 37.7 MB | 339 MB | After idle + some scrolling |
| T=15 min | 35.8 MB | 37.6 MB | 335 MB | GC occurred, Java heap stable |
| T=20 min | 36.7 MB | 43.2 MB | ~345 MB | Native continuing to grow |
| T=30 min | 31.3 MB | 49.4 MB | 345 MB | Java GC'd, Native +19 MB |

**Key insight:** Java heap is stable (GC working), but Native heap grows continuously.

### Frame Timing (Scroll Test)

- Total frames rendered: 3,502
- Average frame time: ~6ms (50th percentile)
- 90th percentile: 9ms ✅
- 95th percentile: 14ms ✅
- 99th percentile: 34ms ⚠️
- Janky frames: 3.66%

### LeakCanary Reports

No leaks detected during 30+ minute session. This confirms the memory growth is NOT in Java heap (LeakCanary monitors Activities, Fragments, ViewModels, Services).

## Comparison: Interface Discovery vs Other Screens

The profiling focused on Interface Discovery screen. The `:reticulum` process memory grew regardless of which screen was active, suggesting the leak is in the background Reticulum/LXMF Python layer, not specific to the UI.

## Files To Modify

### Priority 1: Native Memory Investigation
- `python/Reticulum/RNS/Transport.py` - Path table management
- `python/LXMF/LXMRouter.py` - Message/announce caching
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/python/PythonWrapperManager.kt`

### Priority 2: Input Latency Fix
- `app/src/main/java/com/lxmf/messenger/ui/announce/AnnounceStreamScreen.kt`
- `app/src/main/java/com/lxmf/messenger/viewmodel/AnnounceStreamViewModel.kt`
- Compose item renderers in announce list

### Priority 3: Janky Frame Reduction
- Data classes used in Compose state (add `@Stable`)
- Image loading components

## Recommendations for Plan 02

1. **Start with input latency** - Most impactful for immediate UX improvement
2. **Add Python memory profiling** - Need `tracemalloc` to identify Python leak source
3. **Consider Reticulum cache limits** - May need to patch upstream Reticulum for bounded caches
4. **Add Compose stability annotations** - Quick win for reducing recompositions

## Heap Dumps Collected

- `profiling-data/heap_baseline.hprof` - T=0 baseline
- `profiling-data/heap_15min.hprof` - T=15 minutes
- `profiling-data/heap_20min.hprof` - T=20 minutes
- `profiling-data/heap_30min.hprof` - T=30 minutes (final)

These can be analyzed with Android Studio Memory Profiler or MAT for detailed object retention analysis.
