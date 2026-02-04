# Compose Performance Verification Checklist

## Overview

Ensure your Compose UI achieves **60 FPS** with minimal recompositions. Target: **< 16ms frame time** on mid-range devices.

---

## 1. Build Configuration

### Release Build with R8

```kotlin
// build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

- [ ] **Testing in release build** (debug is 5-10x slower)
- [ ] `isMinifyEnabled = true`
- [ ] ProGuard/R8 optimization enabled
- [ ] Baseline profile included (default or custom)

---

## 2. Compiler Reports

### Generate and Review

```kotlin
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

- [ ] Compiler reports generated (`./gradlew assembleRelease`)
- [ ] Reviewed `*-composables.txt` file
- [ ] All critical composables show **"restartable skippable"**
- [ ] No unexpected **"restartable unskippable"** composables
- [ ] All **"unstable"** parameters investigated and fixed

---

## 3. LazyColumn/LazyRow Optimization

### Keys and ContentType

```kotlin
LazyColumn {
    items(
        items = data,
        key = { item -> item.id }, // ✅ Required
        contentType = { item -> item.type } // ✅ Helps recycling
    ) { item ->
        ItemRow(item)
    }
}
```

- [ ] **All items have stable, unique keys**
- [ ] Keys use actual ID (not hashCode or index)
- [ ] `contentType` provided for heterogeneous lists
- [ ] Using `LazyColumn` not regular `Column` for large lists

---

## 4. State and Stability

### Immutable Collections

```kotlin
// ✅ Stable
@Composable
fun UserList(users: ImmutableList<User>) { }

// ❌ Unstable
@Composable
fun UserList(users: List<User>) { }
```

- [ ] Collection parameters use `ImmutableList`/`ImmutableSet`
- [ ] OR collections wrapped in `@Stable` class
- [ ] OR collections managed in ViewModel with `StateFlow`
- [ ] Data classes use `val` not `var` properties

### Expensive Calculations Cached

```kotlin
val sortedData = remember(data, sortKey) {
    data.sortedBy { sortKey(it) }
}
```

- [ ] Expensive calculations wrapped in `remember` with proper keys
- [ ] OR calculations moved to ViewModel
- [ ] No repeated calculations in composition

---

## 5. derivedStateOf Usage

### Threshold-Based State

```kotlin
val showButton by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 3 }
}
```

- [ ] Used `derivedStateOf` for scroll threshold states
- [ ] Used `derivedStateOf` for filtered/aggregated states
- [ ] NOT reading frequently changing state directly in composition

---

## 6. Deferring State Reads

### Lambda Modifiers

```kotlin
// ✅ Reads in layout phase
Modifier.offset { IntOffset(x, y) }

// ❌ Reads in composition phase
Modifier.offset(x.dp, y.dp)
```

- [ ] Scroll-based offsets use lambda: `Modifier.offset { }`
- [ ] Animations use `Modifier.graphicsLayer { }` not direct modifiers
- [ ] Frequently changing state deferred to layout/drawing phases

---

## 7. Recomposition Verification

### Layout Inspector

- [ ] Layout Inspector recomposition counts enabled
- [ ] **Static content**: < 10 recompositions
- [ ] **List items**: Only changed items recompose
- [ ] **Scroll**: No unnecessary recompositions during scroll

### Composition Tracing

- [ ] Captured systrace during interaction
- [ ] No composition functions > 16ms
- [ ] No unexpected recomposition spikes

---

## 8. List Performance

### LazyColumn Benchmarks

- [ ] Smooth 60 FPS during scroll (no jank)
- [ ] Frame time < 16ms consistently
- [ ] No dropped frames during fling
- [ ] List loads incrementally (not all at once)

### Item Composables

```kotlin
@Composable
fun ItemRow(item: Item) {
    // Skips recomposition when item hasn't changed
}
```

- [ ] Item composables have stable parameters
- [ ] Item composables are **"restartable skippable"**
- [ ] No expensive operations in item composables
- [ ] Using `remember` for item-level calculations

---

## 9. ViewModel Integration

### Proper Flow Collection

```kotlin
// ✅ Correct
val state by viewModel.uiState.collectAsStateWithLifecycle()

// ❌ Wrong
val state by viewModel.uiState.collectAsState()
```

- [ ] Using `collectAsStateWithLifecycle()` not `collectAsState()`
- [ ] Expensive operations in ViewModel, not composables
- [ ] StateFlow used for state, not mutable state in ViewModel

---

## 10. Macrobenchmark Results

### Quantitative Metrics

```kotlin
@Test
fun scrollBenchmark() {
    benchmarkRule.measureRepeated(
        metrics = listOf(FrameTimingMetric()),
        iterations = 5
    ) {
        // Scroll test
    }
}
```

- [ ] Macrobenchmark tests created
- [ ] **Frame duration CPU**: < 16ms average
- [ ] **Frame overrun**: < 5% of frames
- [ ] **Startup time**: < 3 seconds cold start
- [ ] Results stable across multiple runs

---

## 11. Memory and Resource

### Resource Usage

- [ ] No memory leaks detected (LeakCanary)
- [ ] LaunchedEffect properly cancelled
- [ ] No retained composables after navigation
- [ ] Images properly cached/released

---

## 12. Common Performance Issues

### Troubleshooting Checklist

If UI is janky:
- [ ] Checked if testing in **release build** (not debug)
- [ ] Verified **stable keys** on all LazyColumn items
- [ ] Generated **compiler reports** to find unstable parameters
- [ ] Used Layout Inspector to identify high recomposition counts
- [ ] Captured systrace to find bottleneck

If list scrolling is slow:
- [ ] Added **stable, unique keys** to items
- [ ] Used **ImmutableList** for list parameter
- [ ] Verified item composables are **skippable**
- [ ] Checked for expensive operations in item composables
- [ ] Used `contentType` for heterogeneous lists

If composables recompose too much:
- [ ] Reviewed compiler reports for **unstable** parameters
- [ ] Checked for **collections without keys**
- [ ] Looked for **expensive calculations without remember**
- [ ] Verified **derivedStateOf** used for threshold states
- [ ] Checked for **backwards writes** (writing state during composition)

---

## Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| Frame rate | 60 FPS | Layout Inspector |
| Frame time | < 16ms | Macrobenchmark FrameTimingMetric |
| Recomposition count (static) | < 10 | Layout Inspector |
| Recomposition count (list items) | Changed items only | Layout Inspector |
| Startup time | < 3s cold | Macrobenchmark StartupTimingMetric |
| Frame overrun | < 5% | Macrobenchmark FrameTimingMetric |

---

## Final Sign-Off

- [ ] All items above checked and verified
- [ ] Tested in **release build** with R8
- [ ] **60 FPS** achieved during scrolling
- [ ] **< 16ms frame time** consistently
- [ ] Compiler reports show **"restartable skippable"**
- [ ] Layout Inspector shows **low recomposition counts**
- [ ] Ready for production
