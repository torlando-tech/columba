# Compose Troubleshooting Guide

> **Quick solutions for the most common Compose issues and edge cases**

## Table of Contents

1. [Critical Mistakes](#critical-mistakes)
2. [WindowInsets and IME Issues](#windowinsets-and-ime-issues)
3. [Performance Problems](#performance-problems)
4. [Material 3 Migration Issues](#material-3-migration-issues)
5. [State Management Issues](#state-management-issues)
6. [Edge Cases and Advanced Scenarios](#edge-cases-and-advanced-scenarios)

---

## Critical Mistakes

### Never Write State After Reading It

**The most critical Compose mistake**—backwards writes cause infinite recomposition loops.

#### The Problem

```kotlin
// ❌ CRITICAL ERROR: Infinite loop
@Composable
fun BadComposable() {
    var count by remember { mutableIntStateOf(0) }

    Button(onClick = { count++ }) {
        Text("Recompose")
    }
    Text("$count") // Reads count

    count++ // ❌ WRITES AFTER READING - INFINITE LOOP
    // This triggers recomposition → reads count → writes count → triggers recomposition → ...
}
```

**What happens**:
1. Composable reads `count` to display it
2. Then writes to `count` (increments it)
3. Writing triggers recomposition
4. Recomposition reads `count` again
5. Then writes to `count` again
6. Loop repeats forever → App freezes/crashes

#### The Solution

```kotlin
// ✅ CORRECT: Write only in event handlers
@Composable
fun GoodComposable() {
    var count by remember { mutableIntStateOf(0) }

    Button(onClick = { count++ }) { // ✅ Write in onClick only
        Text("Recompose")
    }
    Text("$count") // Read is fine

    // ✅ No writes during composition
}
```

**Rule**: **Always write to state in response to events** (onClick, onValueChange, LaunchedEffect), **never during composition phase**.

---

### Avoid Recomposition Loops with Proper Layout

Using state to coordinate layout creates composition-layout cycles:

#### The Problem

```kotlin
// ❌ CREATES INFINITE LOOP
@Composable
fun BadLayout() {
    Box {
        var imageHeightPx by remember { mutableIntStateOf(0) }

        Image(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    imageHeightPx = size.height // ❌ Writes state in layout
                }
        )

        Text(
            modifier = Modifier.padding(
                top = with(LocalDensity.current) { imageHeightPx.toDp() }
            ),
            text = "Below image"
        )
    }
}
```

**What happens**:
1. Composition runs → `imageHeightPx` is 0
2. Layout runs → Image measures itself, writes new height to `imageHeightPx`
3. State change triggers recomposition
4. Composition runs → new padding calculated
5. Layout runs → Text position changes, potentially changing Image size
6. Loop repeats → Infinite composition-layout cycle

#### The Solution

```kotlin
// ✅ USE PROPER LAYOUT PRIMITIVES
@Composable
fun GoodLayout() {
    Column {
        Image(
            modifier = Modifier.fillMaxWidth()
        )
        Text("Below image") // Column handles positioning automatically
    }
}
```

**Fix**: Use Column, Row, Box, or custom layouts instead of state-based layout coordination.

---

## WindowInsets and IME Issues

### IME Padding Not Working

**Symptoms**: Keyboard covers TextField, no padding appears

**Checklist** (ALL THREE required):

```kotlin
// 1. ✅ Activity: Call enableEdgeToEdge()
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Required
        setContent { /* ... */ }
    }
}

// 2. ✅ Manifest: Add adjustResize (MOST COMMONLY MISSED)
// AndroidManifest.xml
<activity
    android:name=".MainActivity"
    android:windowSoftInputMode="adjustResize"/> <!-- CRITICAL -->

// 3. ✅ Compose: Apply imePadding modifier
Column(
    modifier = Modifier
        .fillMaxSize()
        .imePadding() // Required
) {
    TextField(...)
}
```

**If still not working after checklist**:
1. Test on physical device (emulator sometimes behaves differently)
2. Check for Samsung device (see device-specific issues below)
3. Verify no conflicting windowSoftInputMode in theme
4. Try custom IME padding modifier (see device-specific section)

---

### Device-Specific IME Issues (Samsung, API 29-)

**Problem**: Samsung devices and Android 10 and below sometimes show:
- Incorrect IME padding (too much or too little)
- Keyboard partially obscures TextField
- Extra padding when keyboard is dismissed

#### Custom IME Padding Modifier

```kotlin
fun Modifier.customImePadding(): Modifier = composed {
    val density = LocalDensity.current
    val windowInsets = WindowInsets.ime
    val imeBottom = windowInsets.getBottom(density)

    this.then(
        Modifier.padding(bottom = with(density) { imeBottom.toDp() })
    )
}

// Usage: Replace .imePadding() with .customImePadding()
Column(
    modifier = Modifier
        .fillMaxSize()
        .customImePadding() // Use custom modifier
) {
    TextField(...)
}
```

**When to use**: If standard `.imePadding()` doesn't work on specific devices.

---

### ModalBottomSheet Double Keyboard Padding

**Problem**: ModalBottomSheet with TextField shows double padding or keyboard doesn't push content up.

#### The Fix

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetWithForm() {
    ModalBottomSheet(
        onDismissRequest = { /* dismiss */ },
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(), // Manual IME padding
        windowInsets = WindowInsets(0) // ✅ CRITICAL: Disable default insets
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Enter text") }
            )
        }
    }
}
```

**Key**: Set `windowInsets = WindowInsets(0)` and manually apply `.imePadding()`.

---

### Keyboard Not Appearing in DialogFragments

**Issue**: Compose 1.4.0 introduced a breaking change where keyboard doesn't show in DialogFragments.

#### Solutions

1. **Avoid Compose 1.4.0** (use 1.3.x or 1.5.0+)
2. **Update to 1.5.0+** (issue fixed)
3. **Custom DialogWindowProvider** (if stuck on 1.4.0)

**Current status**: Fixed in Compose 1.5.0+. Upgrade recommended.

---

### Double Padding Visible

**Problem**: Content has excessive padding (too far from edges).

**Cause**: Not consuming Scaffold's `innerPadding`.

#### The Fix

```kotlin
// ❌ WRONG: Applying padding without consuming
Scaffold(
    topBar = { TopAppBar(...) }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier.padding(innerPadding) // Applied but not consumed
    ) {
        items(data) { item ->
            // Child composables might apply padding again
        }
    }
}

// ✅ CORRECT: Consume after applying
Scaffold(
    topBar = { TopAppBar(...) }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .consumeWindowInsets(innerPadding) // ✅ Consume first
            .padding(innerPadding), // Then apply
        contentPadding = innerPadding
    ) {
        items(data) { item -> /* ... */ }
    }
}
```

**Rule**: Always call `.consumeWindowInsets()` after applying Scaffold padding.

**Also see**: [Nested Scaffolds in Navigation Architecture](#nested-scaffolds-in-navigation-architecture) for the multi-Scaffold navigation pattern.

---

### Nested Scaffolds in Navigation Architecture

**Problem**: Excessive bottom padding on all screens in a NavHost-based navigation architecture with nested Scaffolds.

**Architecture causing this**:
```
Parent Scaffold (with bottomBar)
  └─ NavHost (receives paddingValues)
      └─ Child Scaffolds (each screen with its own topBar)
```

#### Symptoms

- All screens show extra bottom padding equal to the NavigationBar height
- Content appears "lifted" from the bottom
- Scrollable lists have unnecessary whitespace at the bottom
- Visible even when NavigationBar is conditionally hidden

#### Root Cause

**Double padding occurs** when:
1. Parent Scaffold provides `innerPadding` (includes bottomBar height)
2. NavHost wraps content with `Modifier.padding(innerPadding)`
3. Child Scaffolds on each screen apply their own padding
4. **Result**: Parent padding + Child padding = Double padding

#### The Problem Code

```kotlin
// ❌ WRONG: Double padding in navigation architecture
@Composable
fun ColumbaNavigation() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar { /* nav items */ }
        }
    ) { paddingValues ->  // ← Parent provides padding (includes bottomBar)
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(paddingValues) // ❌ Applied here
        ) {
            composable("home") {
                HomeScreen()  // ← Child Scaffold applies padding again
            }
            composable("settings") {
                SettingsScreen()  // ← Child Scaffold applies padding again
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Home") }) }
    ) { innerPadding ->  // ❌ This is DOUBLE the bottom padding
        LazyColumn(
            modifier = Modifier.padding(innerPadding)
        ) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```

**What's happening**:
1. Parent Scaffold's `paddingValues` includes NavigationBar height (80dp)
2. NavHost applies this padding: `Modifier.padding(paddingValues)`
3. HomeScreen's Scaffold provides ANOTHER `innerPadding` with default bottom padding
4. HomeScreen applies that padding too
5. **Result**: 80dp + 80dp = 160dp bottom padding

#### Solution Options

Choose the approach that fits your architecture:

##### Option 1: Don't Apply Padding to NavHost (Recommended)

**Best when**: You want each screen to handle its own edge-to-edge layout.

```kotlin
// ✅ CORRECT: Let child screens handle their own padding
Scaffold(
    bottomBar = {
        NavigationBar { /* nav items */ }
    }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home"
        // ✅ No padding applied to NavHost
    ) {
        composable("home") {
            HomeScreen()  // Handles its own layout
        }
    }
}

@Composable
fun HomeScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Home") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```

**Pros**:
- Each screen controls its own layout
- Screens can choose full-screen (no Scaffold) if needed
- Most flexible approach

**Cons**:
- Parent's bottomBar padding is not automatically applied
- Each screen must handle edge-to-edge correctly

##### Option 2: Pass Parent Padding to Child Screens

**Best when**: You want centralized padding management and all screens need the same bottom padding.

```kotlin
// ✅ CORRECT: Pass parent padding to children
Scaffold(
    bottomBar = {
        NavigationBar { /* nav items */ }
    }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home"
        // No padding on NavHost
    ) {
        composable("home") {
            HomeScreen(parentPadding = paddingValues)
        }
        composable("settings") {
            SettingsScreen(parentPadding = paddingValues)
        }
    }
}

@Composable
fun HomeScreen(parentPadding: PaddingValues) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Home") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(parentPadding)  // Apply parent padding
                .consumeWindowInsets(parentPadding)
                .padding(innerPadding)   // Apply local padding
                .consumeWindowInsets(innerPadding)
        ) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```

**Pros**:
- Explicit padding flow
- Easy to debug
- Child screens aware of parent constraints

**Cons**:
- More boilerplate (passing padding everywhere)
- Each screen composable needs padding parameter

##### Option 3: Use contentPadding Instead

**Best when**: Using scrollable content (LazyColumn, LazyRow) and you want internal padding.

```kotlin
// ✅ CORRECT: Use contentPadding for LazyColumn
Scaffold(
    bottomBar = {
        NavigationBar { /* nav items */ }
    }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen()
        }
    }
}

@Composable
fun HomeScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Home") }) }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding  // ✅ Internal padding
            // No Modifier.padding() needed
        ) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```

**Pros**:
- Clean modifier chain
- Scrollable content naturally handles padding
- No consumeWindowInsets needed for contentPadding

**Cons**:
- Only works with LazyColumn/LazyRow/LazyVerticalGrid
- Doesn't work for non-scrollable layouts

##### Option 4: Conditionally Show NavigationBar

**Best when**: Bottom navigation should hide on certain screens (like detail/messaging screens).

```kotlin
// ✅ CORRECT: Conditionally hide NavigationBar
@Composable
fun ColumbaNavigation() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState()
        .value?.destination?.route

    val showBottomBar = currentRoute !in listOf(
        "messaging", "detail", "fullscreen"
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar { /* nav items */ }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "home"
            // No padding on NavHost
        ) {
            composable("home") { HomeScreen() }
            composable("messaging") { MessagingScreen() } // No bottom bar
        }
    }
}
```

**Pros**:
- Different layouts for different screen types
- Better UX (more space for content on detail screens)
- No padding issues when bar is hidden

**Cons**:
- More complex navigation logic
- Need to track current route

#### When This Occurs

This issue appears in apps with:
- ✅ **Navigation architecture** with bottom/top-level navigation (bottom tabs, rail, drawer)
- ✅ **Parent Scaffold** providing app-level chrome (NavigationBar, top-level TopAppBar)
- ✅ **NavHost** managing screen navigation
- ✅ **Child Scaffolds** on individual screens (for screen-specific TopAppBar)

**Real-world examples**:
- Apps with bottom navigation tabs where each tab shows a different screen with its own toolbar
- Apps with navigation drawer where drawer screens have their own app bars
- Multi-pane adaptive layouts with nested navigation

#### Diagnosis Checklist

To confirm this is your issue:

1. **Visual check**: Measure bottom padding on screens
   - Does it equal 2× the NavigationBar height?
   - Is it consistent across all screens?

2. **Layout Inspector check**:
   - Open Layout Inspector in Android Studio
   - Select a LazyColumn or content container
   - Check padding values in Attributes panel
   - Look for duplicate padding modifiers

3. **Code check**:
   ```kotlin
   // Look for this pattern in your MainActivity/Navigation:
   Scaffold { paddingValues ->
       NavHost(
           modifier = Modifier.padding(paddingValues)  // ⚠️ Check here
       ) { ... }
   }
   ```

4. **Temporary test**: Remove parent Scaffold's bottomBar
   - Does the extra padding disappear?
   - If yes, you have nested Scaffold double padding

#### Related Issues

- [Double Padding Visible](#double-padding-visible) - Single Scaffold case
- [Content Behind System Bars](#content-behind-system-bars) - Missing padding (opposite problem)
- Pattern: [windowinsets-pattern.md](../patterns/windowinsets-pattern.md) - consumeWindowInsets examples

#### Advanced: Debugging Padding

To see exactly what padding is being applied:

```kotlin
LazyColumn(
    modifier = Modifier
        .then(
            Modifier.composed {
                val paddingValues = remember { mutableStateOf("") }
                this.then(
                    Modifier.layout { measurable, constraints ->
                        android.util.Log.d("PaddingDebug", "Constraints: $constraints")
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
                )
            }
        )
        .padding(innerPadding)
) { ... }
```

Or use Layout Inspector's "Show All Constraints" feature.

---

### Content Behind System Bars

**Problem**: Text or buttons are obscured by status bar or navigation bar on Android 15+.

#### Quick Fixes

```kotlin
// Option 1: Use safeDrawing for all system UI
Box(
    modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding() // Avoids all system UI
) {
    Content()
}

// Option 2: Use specific insets
Column(
    modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding() // Top
        .navigationBarsPadding() // Bottom
) {
    Content()
}

// Option 3: Let M3 components handle it
Scaffold(
    topBar = { TopAppBar(...) }, // Handles status bar automatically
    bottomBar = { NavigationBar {...} } // Handles nav bar automatically
) { innerPadding ->
    Content(modifier = Modifier.padding(innerPadding))
}
```

---

## Performance Problems

### List Scrolling is Janky

**Symptoms**: Dropped frames during LazyColumn/LazyRow scrolling.

#### Check 1: Stable Keys

```kotlin
// ❌ PROBLEM: No keys
LazyColumn {
    items(users) { user ->
        UserRow(user)
    }
}

// ✅ FIX: Add stable, unique keys
LazyColumn {
    items(users, key = { it.id }) { user ->
        UserRow(user)
    }
}
```

#### Check 2: Unstable Parameters

```kotlin
// ❌ PROBLEM: Unstable List parameter
@Composable
fun UserList(users: List<User>) { ... }

// ✅ FIX: Use ImmutableList
@Composable
fun UserList(users: ImmutableList<User>) { ... }

// Or in ViewModel
val users: StateFlow<List<User>> = ... // StateFlow handles stability
```

#### Check 3: Expensive Calculations

```kotlin
// ❌ PROBLEM: Sorting on every recomposition
LazyColumn {
    items(users.sortedBy { it.name }) { user -> ... }
}

// ✅ FIX: Cache with remember
val sortedUsers = remember(users) { users.sortedBy { it.name } }
LazyColumn {
    items(sortedUsers, key = { it.id }) { user -> ... }
}
```

---

### Composable Recomposes Too Much

**Symptom**: Layout Inspector shows high recomposition counts (>10 for static content).

#### Check 1: Unstable Parameters

Generate compiler reports:

```kotlin
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

Look for "unstable" parameters in `build/compose_compiler/*-composables.txt`.

#### Check 2: Reading State Too Early

```kotlin
// ❌ PROBLEM: Reading scroll state in composition
val scrollState = rememberScrollState()
val offset = with(LocalDensity.current) { scrollState.value.toDp() }
Box(Modifier.offset(y = offset)) { ... }

// ✅ FIX: Read in layout phase with lambda
val scrollState = rememberScrollState()
Box(Modifier.offset { IntOffset(0, scrollState.value) }) { ... }
```

#### Check 3: No derivedStateOf

```kotlin
// ❌ PROBLEM: Recomposes on every scroll pixel
val showButton = listState.firstVisibleItemIndex > 0

// ✅ FIX: Use derivedStateOf for thresholds
val showButton by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 0 }
}
```

---

### App Startup is Slow

#### Check 1: Release Build

```kotlin
// ❌ PROBLEM: Benchmarking in debug mode (5-10x slower)

// ✅ FIX: Always test performance in release builds
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}
```

#### Check 2: Baseline Profiles

```kotlin
// Add ProfileInstaller
dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
```

**Result**: ~30% startup improvement.

---

## Material 3 Migration Issues

### Double Padding on TopAppBar

**Problem**: TopAppBar has too much top padding after migrating to M3.

```kotlin
// ❌ WRONG: Manual padding with M3 TopAppBar
import androidx.compose.material3.TopAppBar

TopAppBar(
    title = { Text("Title") },
    modifier = Modifier.statusBarsPadding() // ❌ M3 already handles this
)

// ✅ CORRECT: Let M3 handle it
import androidx.compose.material3.TopAppBar

TopAppBar(
    title = { Text("Title") }
    // No manual padding needed
)
```

**Rule**: M3 components (TopAppBar, NavigationBar, BottomAppBar) **auto-handle insets**. Don't add manual padding.

---

### backgroundColor Parameter Not Found

**Problem**: `backgroundColor` parameter doesn't exist on M3 components.

```kotlin
// ❌ M2 API
Card(backgroundColor = Color.Blue) { ... }

// ✅ M3 API
Card(
    colors = CardDefaults.cardColors(
        containerColor = Color.Blue
    )
) { ... }
```

**Mapping**: `backgroundColor` → `containerColor` in M3.

---

### ContentAlpha Not Found

**Problem**: `ContentAlpha.medium` doesn't exist in M3.

```kotlin
// ❌ M2 API
Text(
    text = "Secondary",
    color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium)
)

// ✅ M3 API
Text(
    text = "Secondary",
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

**Mapping**: Use color roles instead of alpha:
- `ContentAlpha.high` → `onSurface`
- `ContentAlpha.medium` → `onSurfaceVariant`
- `ContentAlpha.disabled` → `onSurface` with 38% alpha

---

## State Management Issues

### State Not Surviving Rotation

**Problem**: Form input is lost when screen rotates.

```kotlin
// ❌ WRONG: remember doesn't survive config changes
var text by remember { mutableStateOf("") }

// ✅ FIX: Use rememberSaveable
var text by rememberSaveable { mutableStateOf("") }
```

---

### collectAsState() Continues in Background

**Problem**: Battery drain from collecting flow when app is in background.

```kotlin
// ❌ WRONG: Continues collecting even when not visible
val state by viewModel.uiState.collectAsState()

// ✅ CORRECT: Stops collecting when not visible
val state by viewModel.uiState.collectAsStateWithLifecycle()
```

**Rule**: Always use `collectAsStateWithLifecycle()` for ViewModel flows.

---

### Painter Parameters Cause Unnecessary Recomposition

**Problem**: Painter is not marked stable, causing unnecessary recompositions.

```kotlin
// ❌ UNSTABLE: Painter parameter
@Composable
fun MyImage(painter: Painter) {
    Image(painter = painter, contentDescription = null)
}

// ✅ STABLE: Pass URL or resource ID instead
@Composable
fun MyImage(@DrawableRes imageRes: Int) {
    val painter = painterResource(imageRes)
    Image(painter = painter, contentDescription = null)
}

// Or for URLs
@Composable
fun MyImage(url: String) {
    val painter = rememberAsyncImagePainter(url) // Coil
    Image(painter = painter, contentDescription = null)
}
```

---

## Edge Cases and Advanced Scenarios

### Dialogs Don't Inherit WindowInsets

**Problem**: Dialog content is obscured by system bars.

```kotlin
// ❌ PROBLEM: Dialog has no insets
Dialog(onDismissRequest = {}) {
    Surface {
        Text("Dialog content")
    }
}

// ✅ FIX: Apply insets manually
Dialog(onDismissRequest = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Text("Dialog content") // Now avoids system bars
    }
}
```

**Reason**: Dialogs create separate windows and don't receive insets from parent activity.

---

### Landscape Navigation Bars on Sides

**Problem**: Navigation bar appears on left/right in landscape, causing content to be cut off.

```kotlin
@Composable
fun LandscapeAwareContent() {
    val density = LocalDensity.current
    val navBarsLeft = WindowInsets.navigationBars.getLeft(density)
    val navBarsRight = WindowInsets.navigationBars.getRight(density)
    val navBarsBottom = WindowInsets.navigationBars.getBottom(density)

    Box(
        modifier = Modifier.padding(
            start = with(density) { navBarsLeft.toDp() },
            end = with(density) { navBarsRight.toDp() },
            bottom = with(density) { navBarsBottom.toDp() }
        )
    ) {
        Content() // Now respects nav bar position in any orientation
    }
}
```

---

### Caption Bars on Freeform Windows

**Problem**: Desktop mode and foldables in freeform mode have caption bars that `statusBarsPadding()` doesn't account for.

```kotlin
// ❌ INCOMPLETE: Doesn't handle caption bars
Box(Modifier.statusBarsPadding()) { ... }

// ✅ COMPLETE: Handles caption bars + status bars
Box(Modifier.systemBarsPadding()) { ... }

// Or use safeDrawing (recommended)
Box(Modifier.safeDrawingPadding()) { ... }
```

**Rule**: Use `systemBarsPadding()` or `safeDrawing` to handle caption bars automatically.

---

### TextField Focus and Keyboard Coordination

**Problem**: Need fine-grained control over focus and keyboard.

```kotlin
@Composable
fun TextFieldWithFullControl() {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide() // Hide keyboard
                focusManager.clearFocus()  // Clear focus
            }
        )
    )

    // Request focus programmatically
    LaunchedEffect(Unit) {
        delay(100) // Wait for composition
        focusRequester.requestFocus()
    }
}
```

**Important distinction**:
- `keyboardController?.hide()`: Only hides keyboard, leaves focus active
- `focusManager.clearFocus()`: Hides keyboard **AND** removes focus state

---

## Quick Diagnostic Checklist

### Keyboard Not Working
- [ ] `enableEdgeToEdge()` called in Activity?
- [ ] `android:windowSoftInputMode="adjustResize"` in manifest?
- [ ] `.imePadding()` applied to scrollable content?
- [ ] Tested on physical device (not just emulator)?
- [ ] Samsung device? Try custom IME modifier

### Content Behind System Bars
- [ ] Targeting SDK 35 (Android 15)?
- [ ] `enableEdgeToEdge()` called?
- [ ] Using M3 TopAppBar/NavigationBar (auto-handle insets)?
- [ ] Applied `.systemBarsPadding()` or `.safeDrawingPadding()`?

### Performance Issues
- [ ] Testing in release build (not debug)?
- [ ] LazyColumn items have stable, unique keys?
- [ ] Compiler reports show "restartable skippable"?
- [ ] Using immutable collections for parameters?
- [ ] Expensive calculations wrapped in `remember`?

### State Issues
- [ ] Using `rememberSaveable` for rotation persistence?
- [ ] Using `collectAsStateWithLifecycle()` for flows?
- [ ] Not writing state during composition phase?
- [ ] State hoisted to appropriate level?

---

## Getting Additional Help

If issue persists after checking this guide:

1. **Generate compiler reports** to see stability issues
2. **Use Layout Inspector** to see recomposition counts
3. **Capture systrace** to see exact performance bottleneck
4. **Review relevant docs** in this skill:
   - WindowInsets issues → `WINDOWINSETS_GUIDE.md`
   - Performance → `PERFORMANCE_GUIDE.md`
   - State management → `STATE_MANAGEMENT.md`
   - M3 migration → `MATERIAL3_GUIDE.md`

---

**Last Updated**: October 2025
**Covers**: Common issues in Compose BOM 2025.10.01, Material 3 v1.4.0, Android 15 SDK 35
