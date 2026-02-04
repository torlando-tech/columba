# Compose Performance Optimization Guide

> **Master performance optimization through stability, proper state management, and profiling**

## Table of Contents

1. [The Three-Phase System](#the-three-phase-system)
2. [Single-Pass Measurement Advantage](#single-pass-measurement-advantage)
3. [Essential Performance Configuration](#essential-performance-configuration)
4. [Cache Expensive Calculations](#cache-expensive-calculations)
5. [Provide Stable Keys for Lists](#provide-stable-keys-for-lists)
6. [Understanding Stability and Skipping](#understanding-stability-and-skipping)
7. [Check Stability with Compiler Reports](#check-stability-with-compiler-reports)
8. [Use derivedStateOf](#use-derivedstateOf)
9. [Defer State Reads to Later Phases](#defer-state-reads-to-later-phases)
10. [Performance Analysis Tools](#performance-analysis-tools)

---

## The Three-Phase System

Compose's layout operates through **three distinct phases** that execute in order:

### Phase 1: Composition (What UI to show)
- Runs composable functions
- Creates/updates UI tree
- Reads state to determine what to display
- **Most expensive phase**

### Phase 2: Layout (Where to place UI)
- **Measurement**: Measures each composable
- **Placement**: Positions each composable
- Reads layout-specific state (size, position)
- **Medium cost**

### Phase 3: Drawing (How it renders)
- UI elements draw into Canvas
- Reads visual state (colors, transformations)
- **Least expensive phase**

### Critical Performance Insight

**Reading state in later phases skips earlier phases entirely**, dramatically improving performance for frequently changing values:

```kotlin
// ❌ SUBOPTIMAL: Reading in Composition triggers all 3 phases
@Composable
fun AnimatedBox() {
    val offsetX = animateFloatAsState(targetValue)
    Box(Modifier.offset(x = offsetX.value.dp)) // Reads in composition
}
// Recomposition → Layout → Drawing (all 3 phases)

// ✅ OPTIMIZED: Reading in Layout skips Composition
@Composable
fun AnimatedBox() {
    val offsetX = animateFloatAsState(targetValue)
    Box(Modifier.offset { IntOffset(offsetX.value.toInt(), 0) }) // Reads in layout
}
// Layout → Drawing (only 2 phases, skips composition)
```

**Rule of thumb**: Defer state reads to the latest possible phase.

---

## Single-Pass Measurement Advantage

Unlike the View system's RelativeLayout which can measure children multiple times, Compose **measures children exactly once**.

### This Enables Efficient Deep Nesting

```kotlin
// ✅ PERFECTLY FINE - Deep nesting has no performance penalty in Compose
Column {
    Row {
        Box {
            Column {
                Row {
                    Text("Deep nesting is OK!")
                }
            }
        }
    }
}
```

**Implication**: Structure your UI for **clarity and maintainability** without worrying about depth.

### The Measurement Order

1. **Parents measure before children** (parent knows available space)
2. **Children report their size back to parent**
3. **Parents are sized based on children**
4. **Parents are placed after children** (now knows final size)

**Result**: Efficient, predictable layout without multiple measurement passes.

---

## Essential Performance Configuration

### Release Builds with R8 are Mandatory

**Debug mode imposes 5-10x performance costs**. Always benchmark in release mode:

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

**Why R8 matters**:
- Removes unused code (tree shaking)
- Optimizes bytecode
- Inlines functions aggressively
- Enables Compose compiler optimizations

### Baseline Profiles

Provide **~30% startup improvement** through AOT compilation:

```kotlin
// build.gradle.kts (Module level)
dependencies {
    // Compose ships with default baseline profiles
    // For app-specific optimization:
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
```

**Create custom baseline profiles** using Macrobenchmark for optimal results (see Performance Analysis Tools section).

### Enabling Compose Compiler Reports

```kotlin
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

Generates stability analysis reports in `build/compose_compiler/`.

---

## Cache Expensive Calculations

**The most common performance mistake**: Recalculating values on every recomposition.

### The Problem

```kotlin
// ❌ PROBLEM: Sorts on EVERY recomposition (potentially thousands of times)
@Composable
fun ContactList(contacts: List<Contact>, comparator: Comparator<Contact>) {
    LazyColumn {
        items(contacts.sortedWith(comparator)) { contact ->
            ContactRow(contact)
        }
    }
}
```

Every time this composable recomposes (parent state change, navigation, etc.), `sortedWith()` executes again, even if `contacts` and `comparator` haven't changed.

### The Solution: remember

```kotlin
// ✅ SOLUTION: Cache sorted list, only recalculate when inputs change
@Composable
fun ContactList(contacts: List<Contact>, comparator: Comparator<Contact>) {
    val sortedContacts = remember(contacts, comparator) {
        contacts.sortedWith(comparator)
    }

    LazyColumn {
        items(sortedContacts) { contact ->
            ContactRow(contact)
        }
    }
}
```

**How remember works**:
- First composition: Executes lambda, caches result
- Subsequent recompositions: Returns cached result if keys unchanged
- If keys change: Re-executes lambda, caches new result

### Best Practice: Move Calculations to ViewModel

Even better than `remember` in composition:

```kotlin
// ✅ BEST: Calculate in ViewModel, emit as state
@HiltViewModel
class ContactViewModel @Inject constructor(
    repository: ContactRepository
) : ViewModel() {
    val sortedContacts = repository.contactsFlow
        .map { contacts -> contacts.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun ContactList(viewModel: ContactViewModel = hiltViewModel()) {
    val sortedContacts by viewModel.sortedContacts.collectAsStateWithLifecycle()

    LazyColumn {
        items(sortedContacts) { contact ->
            ContactRow(contact)
        }
    }
}
```

**Benefits**:
- Calculation happens once in ViewModel
- Survives configuration changes
- Can be unit tested
- Compose layer is simpler

### When to Use remember

Use `remember` when:
- Calculation depends on Compose-specific values (LocalDensity, LocalConfiguration)
- Creating UI-specific state holders (ScrollState, FocusRequester)
- Calculation is simple and doesn't need testing

**Don't use remember** when:
- Business logic calculation (belongs in ViewModel)
- Expensive async operations (use ViewModel + coroutines)
- State needs to survive process death (use SavedStateHandle)

---

## Provide Stable Keys for Lists

**Without keys, Compose cannot identify moved items** and will recompose all items when the list changes.

### The Problem

```kotlin
// ❌ PROBLEM: No keys - entire list recomposes on any change
LazyColumn {
    items(notes) { note ->
        NoteRow(note)
    }
}
```

What happens when a note is added/removed/moved:
1. Compose doesn't know which items are the same
2. **All items recompose** (expensive)
3. Scroll position may be lost
4. No item animations possible

### The Solution: Stable, Unique Keys

```kotlin
// ✅ SOLUTION: Provide stable, unique key for each item
LazyColumn {
    items(
        items = notes,
        key = { note -> note.id } // Stable, unique identifier
    ) { note ->
        NoteRow(note)
    }
}
```

**Benefits**:
- Only changed items recompose
- Scroll position preserved
- Item animations work correctly
- Dramatic performance improvement for large lists

### Key Requirements

Keys must be:
- **Stable**: Same key for same logical item across recompositions
- **Unique**: Different keys for different items
- **Consistent**: Same key for same item even after list reordering

```kotlin
// ✅ GOOD KEYS
items(users, key = { it.id }) // Database ID
items(messages, key = { it.uuid }) // UUID
items(files, key = { it.absolutePath }) // File path

// ❌ BAD KEYS
items(users, key = { it.hashCode() }) // Hashcode can change
items(users, key = { it.name }) // Not unique (duplicates possible)
items(users, key = { System.currentTimeMillis() }) // Not stable
```

### contentType for Additional Optimization

```kotlin
LazyColumn {
    items(
        items = feed,
        key = { item -> item.id },
        contentType = { item -> item.type } // "text", "image", "video"
    ) { item ->
        when (item.type) {
            "text" -> TextPost(item)
            "image" -> ImagePost(item)
            "video" -> VideoPost(item)
        }
    }
}
```

**Benefit**: Compose can reuse compositions of the same type, further improving performance.

---

## Understanding Stability and Skipping

Compose **automatically skips** recomposing composables whose inputs haven't changed, **BUT** only if inputs are **stable**.

### The Three Stability Levels

#### 1. Immutable (Best Performance)
Properties can **never** change after object creation.

```kotlin
// ✅ IMMUTABLE - Compose can safely skip
data class Contact(
    val name: String,
    val number: String
)
// All properties are val (immutable) and types are primitives

// ✅ IMMUTABLE - primitives are always immutable
String, Int, Float, Boolean, Double, Long
```

#### 2. Stable (Good Performance)
Properties **can** change, but Compose is **notified** of all changes.

```kotlin
// ✅ STABLE - Compose is notified of changes
var count by remember { mutableStateOf(0) }
// MutableState notifies Compose of changes

// ✅ STABLE - explicitly marked with @Stable annotation
@Stable
class MyStateHolder(private val scope: CoroutineScope) {
    var value by mutableStateOf("")
    // Compose knows this is stable despite being a class
}
```

#### 3. Unstable (Poor Performance)
Compose **cannot determine** if values changed.

```kotlin
// ❌ UNSTABLE - mutable properties
data class Contact(
    var name: String,
    var number: String
)
// var makes class unstable

// ❌ UNSTABLE - standard collections
@Composable
fun ContactList(contacts: List<Contact>) { }
// List is not marked @Stable, so Compose treats it as unstable
```

### Why Collections are Unstable

**All standard Kotlin collections (List, Set, Map) are considered unstable** regardless of content:

```kotlin
// ❌ UNSTABLE - even though it's a read-only List
@Composable
fun UserList(users: List<User>) {
    // This will NOT skip recomposition even if users hasn't changed
}
```

**Reason**: List interface doesn't guarantee immutability. Could be `mutableListOf()` underneath.

### Solutions for Collection Stability

#### Option 1: Use Kotlinx Immutable Collections

```kotlin
// ✅ STABLE - explicitly immutable
@Composable
fun UserList(users: ImmutableList<User>) {
    // Will skip recomposition if users hasn't changed
}

// Dependency
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5")
}

// Convert regular lists
val regularList = listOf(1, 2, 3)
val immutableList = regularList.toImmutableList()
```

#### Option 2: Annotate Wrapper Class with @Stable

```kotlin
// ✅ STABLE - explicitly marked
@Stable
data class UserCollection(val users: List<User>)

@Composable
fun UserList(collection: UserCollection) {
    // Will skip recomposition if collection hasn't changed
}
```

#### Option 3: Keep Collections in ViewModel

```kotlin
// ✅ BEST PRACTICE - ViewModel manages collections
@HiltViewModel
class UsersViewModel @Inject constructor(
    repository: UserRepository
) : ViewModel() {
    val users: StateFlow<List<User>> = repository.users
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
}

@Composable
fun UserScreen(viewModel: UsersViewModel = hiltViewModel()) {
    val users by viewModel.users.collectAsStateWithLifecycle()
    // StateFlow handles stability notification correctly
}
```

---

## Check Stability with Compiler Reports

Generate detailed stability analysis to identify performance issues:

```kotlin
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

### Generated Reports

After building, check `build/compose_compiler/`:

| File | Contents |
|------|----------|
| `<module>-classes.txt` | Class stability analysis |
| `<module>-composables.txt` | Composable restartability/skippability |
| `<module>-composables.csv` | Spreadsheet-friendly format |

### Reading the Reports

**Look for these indicators**:

```
// ✅ GOOD - Will skip when inputs unchanged
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun ContactRow(
  stable contact: Contact
)

// ❌ PROBLEM - Will always recompose
restartable scheme("[androidx.compose.ui.UiComposable]") fun ContactList(
  unstable contacts: List<Contact>
)
```

**Terms**:
- **restartable**: Composable can recompose when state changes
- **skippable**: Composable will skip if inputs unchanged (GOOD)
- **unskippable**: Composable always recomposes (BAD - fix this)
- **stable**: Parameter is stable (GOOD)
- **unstable**: Parameter is unstable (BAD - fix this)

### Fixing Unstable Parameters

When you see "unstable" in reports:

1. **Check if it's a collection**: Use ImmutableList or @Stable wrapper
2. **Check for var properties**: Make them val
3. **Check for complex types**: Annotate with @Stable if appropriate
4. **Check for function types**: Wrap in remember at call site

---

## Use derivedStateOf

For rapidly changing state where UI should only update at specific thresholds:

### The Problem

```kotlin
// ❌ PROBLEM: Recomposes on EVERY scroll event (60+ per second)
@Composable
fun ScrollableScreen() {
    val listState = rememberLazyListState()
    val showButton = listState.firstVisibleItemIndex > 0 // Reads scroll state

    AnimatedVisibility(visible = showButton) {
        ScrollToTopButton()
    }
}
```

Every scroll pixel triggers recomposition, even though `showButton` value only changes at a threshold.

### The Solution: derivedStateOf

```kotlin
// ✅ SOLUTION: Only recomposes when boolean value changes
@Composable
fun ScrollableScreen() {
    val listState = rememberLazyListState()

    val showButton by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    AnimatedVisibility(visible = showButton) {
        ScrollToTopButton()
    }
}
```

**How derivedStateOf helps**:
- Scroll position changes 60+ times per second
- Boolean `showButton` only changes twice: false→true and true→false
- Recompositions reduced from 60+/second to 2 total

### When to Use derivedStateOf

Use when:
- **State changes frequently** but derived value changes infrequently
- Threshold-based values (scroll position → boolean)
- Aggregated values (list of items → count)
- Filtered values (large list → subset)

**Examples**:
```kotlin
// Scroll threshold
val showFab by remember {
    derivedStateOf { scrollState.value > 100 }
}

// Item count
val itemCount by remember {
    derivedStateOf { items.size }
}

// First visible item
val currentSection by remember {
    derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    }
}
```

---

## Defer State Reads to Later Phases

### The Performance Hierarchy

Reading state in different phases has vastly different costs:

| Phase | Triggers | Cost | Use For |
|-------|----------|------|---------|
| Composition | Composition + Layout + Drawing | Highest | What to show |
| Layout | Layout + Drawing | Medium | Where to place |
| Drawing | Drawing only | Lowest | Visual changes |

### Deferring to Layout Phase

Use **lambda-based modifiers** to defer state reads:

```kotlin
// ❌ SUBOPTIMAL: Reads in composition phase
@Composable
fun ParallaxImage() {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    Image(
        modifier = Modifier.offset(
            with(density) {
                (listState.firstVisibleItemScrollOffset / 2).toDp()
            }
        )
    )
}
// Every scroll pixel: Composition → Layout → Drawing

// ✅ OPTIMIZED: Reads in layout phase via lambda
@Composable
fun ParallaxImage() {
    val listState = rememberLazyListState()

    Image(
        modifier = Modifier.offset {
            IntOffset(x = 0, y = listState.firstVisibleItemScrollOffset / 2)
        }
    )
}
// Every scroll pixel: Layout → Drawing (skips composition!)
```

**Performance impact**: Skipping composition can be **5-10x faster** for high-frequency updates.

### Lambda-Based Modifiers

Use these to read state in layout/drawing phases:

| Instead of | Use Lambda Version | Phase |
|------------|-------------------|-------|
| `Modifier.offset(x, y)` | `Modifier.offset { IntOffset }` | Layout |
| `Modifier.background(color)` | `Modifier.drawBehind { }` | Drawing |
| `Modifier.padding(value)` | `Modifier.layout { }` | Layout |
| `Modifier.scale(scale)` | `Modifier.graphicsLayer { scaleX; scaleY }` | Drawing |
| `Modifier.rotate(degrees)` | `Modifier.graphicsLayer { rotationZ }` | Drawing |
| `Modifier.alpha(alpha)` | `Modifier.graphicsLayer { alpha }` | Drawing |

### Deferring with Lambda Parameters

Push state reading down the composable tree to minimize recomposition scope:

```kotlin
// ❌ PROBLEM: Entire Box recomposes when scroll changes
@Composable
fun SnackDetail() {
    Box(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState(0)
        Title(snack, scroll.value) // Reads scroll.value here - Box recomposes
    }
}

// ✅ SOLUTION: Only Title recomposes
@Composable
fun SnackDetail() {
    Box(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState(0)
        Title(snack) { scroll.value } // Lambda defers reading
    }
}

@Composable
private fun Title(snack: Snack, scrollProvider: () -> Int) {
    val offset = with(LocalDensity.current) { scrollProvider().toDp() }
    Column(modifier = Modifier.offset(y = offset)) {
        // Title content
    }
}
```

**Benefit**: Box doesn't recompose on scroll, only Title does.

---

## Performance Analysis Tools

### 1. Layout Inspector (Primary Tool)

**Access**: Android Studio → View → Tool Windows → Layout Inspector

**Features**:
- Visual recomposition count overlays
- Shows how many times each composable recomposes
- Red indicates problems (high recomposition count)
- Identify components that should recompose but don't

**How to use**:
1. Run app in debug mode
2. Open Layout Inspector
3. Enable "Recomposition Counts" in settings
4. Interact with UI
5. Look for high counts (>10 is suspicious for static content)

### 2. Composition Tracing (Best Source of Information)

Traces show composable function execution in the system profiler with precise timing data.

**How to capture**:
1. Android Studio → View → Tool Windows → Profiler
2. Start new profiling session
3. Select "CPU" profiler
4. Choose "Trace System Calls"
5. Interact with UI
6. Stop recording
7. Look for `recompose:` and `measure:` calls

**What to look for**:
- Long-running composition functions (>16ms is janky)
- Unexpected recompositions
- Layout/measurement bottlenecks

### 3. Macrobenchmark (Quantitative Analysis)

Measure actual performance metrics on real devices:

```kotlin
@Test
fun scrollBenchmark() {
    benchmarkRule.measureRepeated(
        packageName = "com.example.myapp",
        metrics = listOf(
            FrameTimingMetric(),
            StartupTimingMetric()
        ),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Perform scroll interaction
        val list = device.findObject(By.res("message_list"))
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
}
```

**Metrics**:
- `frameDurationCpuMs`: CPU time per frame (target: <16ms for 60fps)
- `frameOverrunMs`: How much frames miss their deadline
- `startupMs`: App startup time

### 4. Compiler Reports

Already covered above. Quick checklist:

- [ ] Generate reports with `composeCompiler { reportsDestination = ... }`
- [ ] Check `*-composables.txt` for "unstable" parameters
- [ ] Look for "restartable unskippable" (should be "skippable")
- [ ] Fix unstable parameters (use immutable collections, @Stable)

---

## Performance Optimization Checklist

Before considering performance optimization complete:

### Build Configuration
- [ ] Tested in **release build** with R8 enabled
- [ ] ProGuard rules include Compose optimization
- [ ] Baseline profiles included (default or custom)

### State Management
- [ ] Expensive calculations wrapped in `remember` with proper keys
- [ ] Or better: Calculations moved to ViewModel
- [ ] `derivedStateOf` used for threshold-based state
- [ ] State reads deferred to lambda modifiers where possible

### List Optimization
- [ ] All LazyColumn/LazyRow items have stable, unique keys
- [ ] `contentType` provided for heterogeneous lists
- [ ] Large lists virtualized (not regular Column/Row)

### Stability
- [ ] Compiler reports generated and reviewed
- [ ] All "unstable" parameters fixed (use immutable collections)
- [ ] All composables show "restartable skippable"
- [ ] Complex state classes annotated with @Stable

### Profiling
- [ ] Layout Inspector shows reasonable recomposition counts (<10 for static content)
- [ ] Composition tracing shows no >16ms composition functions
- [ ] Macrobenchmark confirms 60 FPS during scrolling
- [ ] No ANR (Application Not Responding) errors

### Architecture
- [ ] Business logic in ViewModel, not composables
- [ ] StateFlow used for ViewModel → UI state
- [ ] Unidirectional data flow implemented
- [ ] No blocking calls on main thread

---

## Summary: Performance Best Practices

### ✅ Do This

- Always test performance in **release builds** with R8
- Use `remember` with proper keys for calculations
- Provide stable, unique keys for all list items
- Use immutable collections or @Stable annotation
- Defer state reads to layout/drawing phases with lambdas
- Use `derivedStateOf` for threshold-based state
- Generate and review compiler stability reports
- Profile with Layout Inspector and Composition Tracing
- Keep business logic in ViewModels

### ❌ Don't Do This

- Don't benchmark in debug mode (5-10x slower)
- Don't use mutable properties in data classes (unstable)
- Don't forget keys on LazyColumn/LazyRow items
- Don't use standard collections for composable parameters (use Immutable)
- Don't read rapidly changing state in composition phase
- Don't perform expensive calculations in composables without `remember`
- Don't ignore compiler reports showing "unstable" parameters

### Quick Wins

1. **Add keys to lists**: 10-line change, 10x performance improvement
2. **Use ImmutableList**: Single import, massive skipping improvements
3. **Defer scroll-based state**: Use lambda modifiers for parallax effects
4. **Generate compiler reports**: Find all stability issues in 30 seconds

---

**Last Updated**: October 2025
**Target**: 60 FPS (16ms per frame) on mid-range devices
**Measurement Tool**: Release builds with R8 + Macrobenchmark
