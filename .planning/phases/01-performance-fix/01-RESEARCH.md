# Phase 1: Performance Fix - Research

**Researched:** 2026-01-25
**Domain:** Android UI Performance / Jetpack Compose / Memory Leak Detection
**Confidence:** HIGH

## Summary

This phase targets UI stuttering and progressive performance degradation in Columba, especially on the Interface Discovery screen. The app uses Jetpack Compose with LazyColumn for the discovered interfaces list, integrates Python via Chaquopy for Reticulum networking, and follows Android MVVM architecture with Hilt DI.

Research reveals three major areas for investigation:
1. **Compose Performance Issues**: LazyColumn stuttering from unstable keys, excessive recomposition, or unstable state objects
2. **Memory Leaks**: Progressive degradation strongly suggests memory leaks from retained ViewModels, leaked coroutines, or Python/Chaquopy object retention
3. **Threading Problems**: Blocking main thread from Python calls, improper coroutine dispatcher usage, or service binding issues

**Primary recommendation:** Use Android Studio Profiler (CPU + Memory) first for diagnosis, add LeakCanary for automated leak detection, implement Sentry for ongoing monitoring. Fix all discovered issues (not just primary cause), leveraging established profiling methodology and Compose stability patterns.

## Standard Stack

### Core Performance Tools

| Tool | Version | Purpose | Why Standard |
|------|---------|---------|--------------|
| Android Studio Profiler | Built-in (2026) | CPU, Memory, Network profiling | Official Google tool, comprehensive system-level profiling |
| Macrobenchmark | androidx.benchmark.macro:1.x | Frame timing, jank measurement | Official Jetpack library for measuring scrolling performance |
| JankStats | androidx.metrics:metrics-performance:1.x | Real-time frame monitoring | Official library for production jank tracking |
| LeakCanary | com.squareup.leakcanary:leakcanary-android:2.x | Automated memory leak detection | Industry standard, auto-detects activity/fragment leaks |
| Sentry | io.sentry:sentry-android:7.3.0 | ANR/crash/slow frame reporting | Already in project (line 353, app/build.gradle.kts), production observability |

### Supporting Tools

| Tool | Version | Purpose | When to Use |
|------|---------|---------|-------------|
| Perfetto | System trace tool | System-wide profiling | Deep analysis of frame rendering pipeline |
| Baseline Profiles | Macrobenchmark-generated | AOT compilation hints | Post-fix optimization for startup/jank reduction |
| Compose Compiler Reports | Kotlin compiler flag | Stability inference analysis | When debugging why composables aren't skipping |

**Installation (LeakCanary only - others already available):**
```gradle
dependencies {
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.x.x")
}
```

## Architecture Patterns

### Recommended Investigation Workflow

```
1. Profiler Setup (CPU + Memory + Allocation Tracking)
   └── Profile multiple screens for scope assessment
       └── Focus on worst case (Interface Discovery)

2. Performance Characterization
   ├── Baseline measurement (first load)
   ├── Soak test (15-30 minutes continuous use)
   └── Memory growth analysis (heap dumps before/after)

3. Issue Categorization
   ├── Frame timing (CPU profiler, JankStats)
   ├── Memory growth (Memory profiler, LeakCanary)
   └── Threading (CPU profiler, method traces)

4. Root Cause Analysis
   └── Fix all discovered issues (not just primary)

5. Verification
   ├── Profiler comparison (before/after)
   └── Manual soak test (15-30 min, verify no degradation)
```

### Pattern 1: Profiler-First Investigation (Android Studio)

**What:** Use Android Studio's built-in profiler for initial diagnosis without code changes

**When to use:** First step for all performance investigations (aligns with user's decision: "Use profiler only")

**Example workflow:**
```bash
# 1. Start profiling session
# Android Studio -> Profiler -> Select app process
# Enable: CPU (Sample Java Methods), Memory (Record allocations)

# 2. Reproduce issue
# Navigate to Interface Discovery screen
# Scroll list
# Let run for 15-30 minutes

# 3. Capture data
# CPU: Record method trace during scroll
# Memory: Capture heap dumps at 0min, 15min, 30min
# Compare heap dumps for growth

# 4. Analyze
# CPU: Look for hot methods, main thread blocking
# Memory: Look for retained objects, growing collections
```

### Pattern 2: Stable Compose Keys for LazyColumn

**What:** Provide stable, unique keys to prevent unnecessary recomposition during scrolling

**When to use:** Any LazyColumn/LazyRow with dynamic data (Interface Discovery uses this correctly already)

**Current implementation (DiscoveredInterfacesScreen.kt:177):**
```kotlin
items(state.interfaces, key = { "${it.transportId ?: ""}:${it.name}:${it.type}" }) { iface ->
    DiscoveredInterfaceCard(/* ... */)
}
```

**Verification:** Keys are already stable and unique. If performance issues persist, keys are NOT the root cause.

### Pattern 3: Memory Leak Detection with LeakCanary

**What:** Auto-detect activity/fragment/ViewModel leaks without manual heap dump analysis

**When to use:** Any progressive performance degradation investigation

**Setup:**
```kotlin
// build.gradle.kts (app module)
dependencies {
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14.0")
}

// No code changes needed - auto-initializes on debug builds
// Leaks appear as notifications on device
```

**How it works:** Hooks into lifecycle events, uses WeakReference + ReferenceQueue to detect retained objects after DESTROY, triggers heap dump, shows leak trace.

### Pattern 4: Chaquopy Threading Best Practices

**What:** Ensure Python calls don't block main thread, manage GIL appropriately

**When to use:** When profiler shows Python-related blocking on main thread

**Best practices:**
```kotlin
// CORRECT: Call Python on background thread
viewModelScope.launch(Dispatchers.IO) {
    val result = reticulumProtocol.getDiscoveredInterfaces()
    // Result already uses IO dispatcher (line 79, DiscoveredInterfacesViewModel)
}

// WATCH OUT: CPython GIL limits parallelism
// If UI becomes unresponsive during background Python work:
// Consider Python: sys.setswitchinterval(0.001) # default 0.005
```

**Reference:** Chaquopy docs on threading (https://chaquo.com/chaquopy/doc/current/cross.html)

### Anti-Patterns to Avoid

- **No diagnostic logging during investigation**: User decided "Use profiler only — minimize code changes during investigation". Respect this constraint.
- **Testing only in debug builds**: Compose debug builds are notoriously slow. Always verify performance in release builds.
- **Stopping at first discovered issue**: User decided "Fix all issues found during investigation". Don't stop at the first problem.
- **Assuming Python is the problem**: Interface Discovery is a Kotlin UI around Python discovery. Profile first before assuming Python is slow.

## Don't Hand-Roll

Problems that have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Memory leak detection | Manual heap dump analysis | LeakCanary | Automates detection, provides leak traces, catches activity/fragment/ViewModel leaks |
| Frame jank measurement | Manual frame timing | JankStats + Macrobenchmark | Official libraries, integrated with profiler, production-ready |
| ANR detection | Manual watchdog thread | Sentry ANR reporting | Already in project (v7.3.0), uses Google Play data source, captures thread dumps |
| Compose stability analysis | Manual recomposition counting | Compose compiler reports | Built into compiler, shows exactly why composables aren't skipping |

**Key insight:** Android ecosystem has mature profiling/monitoring tools. Use them instead of building custom diagnostics.

## Common Pitfalls

### Pitfall 1: Unstable Compose State Objects

**What goes wrong:** Compose considers unstable types (mutable vars, collections without @Immutable) as "always changed", causing unnecessary recomposition even when data hasn't changed.

**Why it happens:** Collections (List, Set, Map) are always considered unstable by Compose. ViewModels from modules without Compose compiler are unstable.

**How to avoid:**
- Use `@Immutable` or `@Stable` annotations on data classes
- Replace `List<T>` with `kotlinx.collections.immutable.ImmutableList<T>`
- Check compiler reports: `./gradlew assembleDebug -Pcomposecompiler.enableIntrinsicRemember=true -Pcomposecompiler.reportsDestination=/tmp/compose_reports`

**Warning signs:**
- Composables re-executing on every frame during scroll
- CPU profiler shows high time in composition phase
- Compiler reports show "unstable" next to composable parameters

**Source:** [Android Compose Performance - Stability](https://developer.android.com/develop/ui/compose/performance/stability)

### Pitfall 2: Memory Leaks from Retained Coroutines

**What goes wrong:** Coroutines launched with long-lived scope (e.g., GlobalScope, Application scope) that reference short-lived objects (Activities, Fragments, ViewModels) prevent garbage collection.

**Why it happens:** Coroutine captures reference to ViewModel/Activity in lambda closure. If coroutine never completes, reference is never released.

**How to avoid:**
- Always use `viewModelScope` or `lifecycleScope` (auto-cancels on clear/destroy)
- Never use `GlobalScope` unless truly application-lifetime work
- Cancel Jobs explicitly in onCleared() if using manual Job management

**Warning signs:**
- Memory profiler shows growing heap over time
- LeakCanary reports ViewModel/Activity not garbage collected
- Heap dump shows coroutine continuation holding reference

**Source:** [Kotlin Coroutines on Android](https://developer.android.com/kotlin/coroutines)

### Pitfall 3: Main Thread Blocking from Python/Chaquopy

**What goes wrong:** Calling Python functions directly from main thread blocks UI, causing stuttering or ANRs (5+ second freeze).

**Why it happens:** Chaquopy calls are synchronous. Long-running Python functions (e.g., getDiscoveredInterfaces parsing large lists) block the caller thread.

**How to avoid:**
- Always call Chaquopy from `Dispatchers.IO` (seen in DiscoveredInterfacesViewModel:79)
- If Python thread is CPU-bound and UI still stutters, reduce `sys.setswitchinterval` to give UI thread more CPU slices

**Warning signs:**
- CPU profiler shows main thread blocked in Chaquopy JNI calls
- ANR dialogs appear during Python operations
- UI freezes correlate with Python function calls

**Source:** [Chaquopy Cross-language Issues](https://chaquo.com/chaquopy/doc/current/cross.html)

### Pitfall 4: LazyColumn Item Recomposition from Unstable Keys

**What goes wrong:** LazyColumn items recompose unnecessarily during scroll, causing stuttering.

**Why it happens:** Missing or unstable keys, unstable item content parameters, or unnecessary reads of external state.

**How to avoid:**
- Provide stable, unique keys (already done in DiscoveredInterfacesScreen:177)
- Ensure item content composables have stable parameters
- Avoid reading ViewModel state inside item lambda (hoist to items() call)

**Warning signs:**
- Scroll stuttering despite small list size (10-50 items)
- CPU profiler shows composition happening during scroll
- Compose compiler reports show item content composables are unstable

**Source:** [Compose Performance Best Practices - Lazy Layouts](https://developer.android.com/develop/ui/compose/performance/bestpractices)

### Pitfall 5: Progressive Memory Growth from SharedFlow Buffer

**What goes wrong:** SharedFlow with unlimited buffer grows indefinitely if events are produced faster than consumed.

**Why it happens:** `extraBufferCapacity` set too high or `replay` value causing retention of events.

**How to avoid:**
- Use `replay=0` for events that don't need replay (DiscoveredInterfacesViewModel uses this correctly)
- Limit `extraBufferCapacity` to reasonable values (seen: 100 for announces/packets, 10 for messages - reasonable)
- Monitor buffer usage in memory profiler

**Warning signs:**
- Memory profiler shows growing array/buffer backing SharedFlow
- Heap dump shows large collections in Flow implementation classes
- Memory growth correlates with event frequency (scrolling, network activity)

**Current state:** ServiceReticulumProtocol uses reasonable buffer sizes (extraBufferCapacity=100 for high-frequency events, replay=10 for messages).

## Code Examples

Verified patterns from official sources:

### Profiling LazyColumn Scrolling Performance (Macrobenchmark)

```kotlin
// Source: https://context7.com/android/performance-samples.git/llms.txt
import androidx.benchmark.macro.*
import androidx.test.uiautomator.*
import org.junit.Rule
import org.junit.Test

class DiscoveredInterfacesScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollDiscoveredInterfaces() {
        benchmarkRule.measureRepeated(
            packageName = "com.lxmf.messenger",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(), // Test without AOT
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                // Navigate to Interface Discovery screen
                pressHome()
                startActivityAndWait()
                // TODO: Navigate to discovered interfaces
            }
        ) {
            device.wait(Until.hasObject(By.scrollable(true)), 5000)
            val scrollable = device.findObject(By.scrollable(true))
            repeat(3) { scrollable.fling(Direction.DOWN) }
        }
    }
}
```

### Real-time Jank Monitoring with JankStats

```kotlin
// Source: https://context7.com/android/performance-samples.git/llms.txt
// Add to MainActivity or DiscoveredInterfacesScreen
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

class MainActivity : ComponentActivity() {
    private lateinit var jankStats: JankStats

    private val jankFrameListener = JankStats.OnFrameListener { frameData ->
        // frameData.isJank indicates if frame missed 16.67ms deadline
        if (frameData.isJank) {
            Log.w("Performance", "Jank detected: ${frameData.frameDurationUiNanos / 1_000_000}ms")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val metricsStateHolder = PerformanceMetricsState.getHolderForHierarchy(
            findViewById(android.R.id.content)
        )

        jankStats = JankStats.createAndTrack(window, jankFrameListener)
        metricsStateHolder.state?.putState("Activity", javaClass.simpleName)
    }

    override fun onResume() {
        super.onResume()
        jankStats.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jankStats.isTrackingEnabled = false
    }
}
```

### Stable Keys for LazyColumn (Already Implemented Correctly)

```kotlin
// Source: https://developer.android.com/develop/ui/compose/performance/bestpractices
// Current implementation in DiscoveredInterfacesScreen.kt:177 is CORRECT

LazyColumn {
    items(
        items = notes,
        key = { note ->
            // Return a stable, unique key for the note
            note.id
        }
    ) { note ->
        NoteRow(note)
    }
}

// Columba's implementation:
items(state.interfaces, key = { "${it.transportId ?: ""}:${it.name}:${it.type}" }) { iface ->
    DiscoveredInterfaceCard(/* ... */)
}
// ✓ Stable: Composite key from immutable fields
// ✓ Unique: transport ID + name + type uniquely identifies each interface
```

### Avoiding Main Thread Blocking with Coroutines

```kotlin
// Source: https://developer.android.com/kotlin/coroutines
// Current implementation in DiscoveredInterfacesViewModel is CORRECT

// ✓ CORRECT: Background thread for Python/blocking operations
fun loadDiscoveredInterfaces() {
    viewModelScope.launch(Dispatchers.IO) { // IO dispatcher for blocking calls
        val discovered = reticulumProtocol.getDiscoveredInterfaces()
        _state.update { it.copy(interfaces = discovered) }
    }
}

// ✗ WRONG: Would block main thread
fun loadDiscoveredInterfaces() {
    viewModelScope.launch { // Defaults to Dispatchers.Main!
        val discovered = reticulumProtocol.getDiscoveredInterfaces() // Blocks UI
        _state.update { it.copy(interfaces = discovered) }
    }
}
```

### Compose Stability Annotations

```kotlin
// Source: https://developer.android.com/develop/ui/compose/performance/stability

// ✗ UNSTABLE: Mutable var, List (always unstable)
data class UiState(
    var interfaces: List<DiscoveredInterface> = emptyList()
)

// ✓ STABLE: Immutable val, but List still unstable
// Compose will recompose even if list hasn't changed
data class UiState(
    val interfaces: List<DiscoveredInterface> = emptyList()
)

// ✓✓ MOST STABLE: Immutable collection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class UiState(
    val interfaces: ImmutableList<DiscoveredInterface> = persistentListOf()
)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual heap dump analysis | LeakCanary auto-detection | 2015+ | Automates leak detection, provides actionable traces |
| Custom frame timing | JankStats + FrameTimingMetric | 2021+ (Jetpack) | Standardized metrics, production-ready monitoring |
| ANR watchdog (false positives) | Sentry ANR v2 (Google Play data) | 2024+ (Sentry 7.x) | Matches Play Console reports, captures thread dumps |
| Baseline profiles (manual) | Macrobenchmark-generated | 2021+ | Automated generation, measurable startup improvement |
| RecyclerView | Jetpack Compose LazyColumn | 2021+ | Simpler API, declarative, but requires stability awareness |

**Deprecated/outdated:**
- **Manual systrace**: Replaced by Perfetto (better UI, more features)
- **LeakCanary v1 (watchdog ANR)**: Replaced by v2 (automatic detection)
- **Sentry ANR v1 (heuristics)**: Replaced by v2 (Google Play data source) - more accurate, fewer false positives

## Open Questions

Things that couldn't be fully resolved:

1. **What is the timeline for "gets slower over time"?**
   - What we know: Reporter (serialrf433) says progressive degradation, correlated with Interface Discovery enabled
   - What's unclear: Minutes? Hours? Specific trigger (scroll count, list size)?
   - Recommendation: Measure during profiling. Start timer when opening screen, note when stuttering begins. Check heap growth rate.

2. **Does slowdown happen without Discovery enabled?**
   - What we know: Reporter correlated with Interface Discovery screen, but causation not proven
   - What's unclear: Is it Discovery feature itself, or just the UI screen being open?
   - Recommendation: Test both scenarios during investigation: (1) Discovery enabled, (2) Discovery disabled but screen open. Compare memory/CPU profiles.

3. **Is the issue in Python Reticulum or Kotlin UI layer?**
   - What we know: Interface Discovery is Kotlin UI around Python RNS 1.1.0 discovery feature
   - What's unclear: Is Reticulum accumulating data? Is Kotlin not releasing references?
   - Recommendation: Profiler will reveal. If heap dump shows Python objects growing, fork Reticulum and fix. If Kotlin objects growing, fix in Columba.

4. **What is the typical Interface Discovery list size in production?**
   - What we know: User stated "10-50 items (moderate size)" in context
   - What's unclear: Does it grow unbounded over time? Does Reticulum cache old discoveries?
   - Recommendation: Log actual list sizes during soak test. If unbounded growth, implement size limit in Python or UI layer.

## Sources

### Primary (HIGH confidence)

- [/websites/developer_android_develop_ui_compose_performance](https://developer.android.com/develop/ui/compose/performance) - Compose stability, lazy layout optimization
- [/android/performance-samples](https://context7.com/android/performance-samples.git) - Macrobenchmark, JankStats, frame timing measurement
- [Android Studio Profiler Memory Leak Detection](https://developer.android.com/studio/profile/memory-profiler) - Heap dumps, allocation tracking
- [Kotlin Coroutines on Android](https://developer.android.com/kotlin/coroutines) - Dispatcher usage, avoiding main thread blocking
- [Chaquopy Threading Documentation](https://chaquo.com/chaquopy/doc/current/cross.html) - GIL behavior, thread-safety

### Secondary (MEDIUM confidence)

- [LeakCanary GitHub](https://github.com/square/leakcanary) - Setup, automated detection, verified 2.14.0 current version 2026
- [Sentry Android ANR Detection](https://docs.sentry.io/platforms/android/configuration/app-not-respond/) - ANR v2 implementation, Google Play data source
- [Compose Performance Codelab](https://developer.android.com/codelabs/jetpack-compose-performance) - Practical examples, debugging techniques

### Tertiary (LOW confidence)

- [Medium: LazyColumn Performance Issues](https://slack-chats.kotlinlang.org/t/29177084/lazycolumn-with-multiple-lazyrows-performance-issue-i-m-faci) - Community reports of nested LazyRow issues (LOW confidence - anecdotal)
- [Issue Tracker: LazyColumn Memory Leak](https://issuetracker.google.com/issues/230168389) - Reported leak in LazyColumn (LOW confidence - issue status unknown)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All tools are official Google/Jetpack or industry-standard (LeakCanary, Sentry already in project)
- Architecture: HIGH - Profiler-first workflow from official docs, Compose patterns from official performance guide
- Pitfalls: HIGH - All drawn from official documentation or well-documented issues (Compose stability, GIL, coroutine leaks)
- Chaquopy specifics: MEDIUM - Official docs exist but less comprehensive than Jetpack/Android docs

**Research date:** 2026-01-25
**Valid until:** 2026-02-25 (30 days - Android/Compose are stable, tooling changes slowly)

---

## Key Findings for Planner

1. **Tooling is mature**: Android Studio Profiler, LeakCanary, Sentry, Macrobenchmark are all production-ready. Don't build custom diagnostics.

2. **Progressive degradation = memory leak**: High probability this is a memory leak (ViewModel retention, coroutine leak, Python object retention).

3. **Compose stability is critical**: If DiscoveredInterfacesState or DiscoveredInterface types are unstable, unnecessary recomposition will cause stuttering.

4. **Current implementation is mostly correct**: LazyColumn keys are stable, coroutines use IO dispatcher, Flow buffers are bounded. Issue is likely subtle (leak, unstable type, Python accumulation).

5. **Python/Chaquopy is a wildcard**: GIL limitations, thread-safety, object lifecycle different from JVM. Profile first before assuming Python is the problem.

6. **User constraints are clear**: Profiler-only investigation (no diagnostic logging), fix all issues (not just first), refactoring acceptable, 15-30 min soak test required.
