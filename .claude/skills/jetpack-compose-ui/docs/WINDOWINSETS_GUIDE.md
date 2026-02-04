# WindowInsets and Edge-to-Edge Mastery

> **Complete guide to WindowInsets, IME padding, and edge-to-edge display for Android 15+**

## Table of Contents

1. [Core WindowInsets System](#core-windowinsets-system)
2. [Essential Setup Requirements](#essential-setup-requirements)
3. [Proper Scaffold Integration](#proper-scaffold-integration)
4. [Advanced Keyboard Handling](#advanced-keyboard-handling)
5. [Device-Specific IME Issues](#device-specific-ime-issues)
6. [Keyboard Visibility Detection](#keyboard-visibility-detection)
7. [System Bars and Edge-to-Edge](#system-bars-and-edge-to-edge)
8. [Android 15 Mandatory Edge-to-Edge](#android-15-mandatory-edge-to-edge)
9. [Complete Edge-to-Edge Pattern](#complete-edge-to-edge-pattern)
10. [Material 3 Automatic Inset Handling](#material-3-automatic-inset-handling)
11. [Scaffold contentWindowInsets Configuration](#scaffold-contentwindowinsets-configuration)
12. [Handling Gesture Navigation](#handling-gesture-navigation)
13. [Display Cutout Handling](#display-cutout-handling)

---

## Core WindowInsets System

The WindowInsets API provides **five primary inset types** that define system UI boundaries:

| Inset Type | Description | Common Use |
|------------|-------------|------------|
| `WindowInsets.statusBars` | Status bar area at top | TopAppBar padding |
| `WindowInsets.navigationBars` | Navigation buttons/gestures | Bottom content padding |
| `WindowInsets.ime` | Software keyboard | TextField scrolling into view |
| `WindowInsets.displayCutout` | Notches and punch holes | Critical content avoidance |
| `WindowInsets.systemBars` | Combines status + nav + caption | General system UI avoidance |

### Recommended "Safe" Insets

**These are more intelligent** and should be preferred:

- **`WindowInsets.safeDrawing`**: Protects content from being obscured by system UI ✅ **Most commonly used**
- **`WindowInsets.safeGestures`**: Prevents gesture conflicts with system navigation
- **`WindowInsets.safeContent`**: Combines both for comprehensive protection

### Usage Example

```kotlin
// Individual inset types
Box(Modifier.statusBarsPadding()) { /* Content avoids status bar */ }
Box(Modifier.navigationBarsPadding()) { /* Content avoids nav bar */ }
Box(Modifier.imePadding()) { /* Content avoids keyboard */ }

// Safe insets (recommended)
Box(Modifier.safeDrawingPadding()) { /* Content avoids all system UI */ }
Box(Modifier.windowInsetsPadding(WindowInsets.safeGestures)) { /* Avoids gesture conflicts */ }
```

---

## Essential Setup Requirements

**These three steps are absolutely mandatory** for proper IME handling. Miss any one and keyboard padding won't work:

### Step 1: Activity Setup

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // From androidx.activity 1.8.0+
        setContent {
            YourAppTheme {
                YourAppContent()
            }
        }
    }
}
```

**Dependency required**:
```gradle
implementation("androidx.activity:activity-compose:1.9.0")
```

### Step 2: AndroidManifest.xml Configuration

**This is the #1 cause of IME padding failures** when omitted:

```xml
<activity
    android:name=".MainActivity"
    android:windowSoftInputMode="adjustResize"/>
```

This system-level setting tells Android to **resize the window** when the keyboard appears, enabling WindowInsets to detect and report keyboard height.

### Step 3: Apply imePadding Modifier

```kotlin
@Composable
fun MyScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding() // Automatically adds padding when keyboard appears
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Enter text") }
        )
    }
}
```

### Verification Checklist

Before debugging IME issues, verify:
- ✅ `enableEdgeToEdge()` called in Activity.onCreate()
- ✅ `android:windowSoftInputMode="adjustResize"` in AndroidManifest
- ✅ `.imePadding()` modifier applied to content
- ✅ Tested on physical device (emulator sometimes behaves differently)

---

## Proper Scaffold Integration

Scaffold provides insets as PaddingValues but **does not apply them automatically**—you must consume them to prevent double padding.

### The Correct Pattern

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Title") })
        },
        bottomBar = {
            BottomAppBar { /* content */ }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .consumeWindowInsets(innerPadding) // CRITICAL: prevents double padding
                .padding(innerPadding)
                .imePadding(),
            contentPadding = innerPadding  // For LazyColumn internal padding
        ) {
            items(data) { item ->
                ItemRow(item)
            }
        }
    }
}
```

### Modifier Ordering is Critical

**Order matters!** Wrong order causes overlapping padding or content being cut off:

```kotlin
// ✅ CORRECT ORDER
Modifier
    .padding(innerPadding)           // Apply Scaffold padding first
    .consumeWindowInsets(innerPadding)  // Consume those insets
    .imePadding()                    // Then add IME padding

// ❌ WRONG ORDER - causes issues
Modifier
    .imePadding()                    // Wrong: IME before Scaffold
    .padding(innerPadding)           // Wrong: padding without consuming
```

### Why consumeWindowInsets() is Critical

Without `consumeWindowInsets()`, child composables don't know that padding has already been applied, leading to:
- Double padding (content too far from edges)
- Incorrect IME behavior
- Layout shifts when keyboard appears

---

## Advanced Keyboard Handling

### Automatic Keyboard Dismissal on Scroll

Use the experimental `imeNestedScroll()` modifier:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScrollableForm() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .imeNestedScroll() // Auto-dismisses keyboard when scrolling down
    ) {
        items(formFields) { field ->
            TextField(...)
        }
    }
}
```

**Behavior**: Keyboard automatically dismisses when user starts scrolling, improving UX for long forms.

### Bringing Focused TextFields into View

For complex forms where TextFields might be obscured, use `BringIntoViewRequester`:

```kotlin
@Composable
fun ComplexForm() {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        delay(300) // Wait for keyboard animation
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    )
}
```

**Use case**: Long forms where TextField might be partially obscured by keyboard.

### Complete Focus and Keyboard Management

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

    // Programmatically request focus when needed
    LaunchedEffect(Unit) {
        delay(100) // Wait for composition to settle
        focusRequester.requestFocus()
    }
}
```

**Important distinction**:
- `keyboardController?.hide()`: Only hides keyboard, leaves focus active
- `focusManager.clearFocus()`: Hides keyboard **AND** removes focus state

---

## Device-Specific IME Issues

### Samsung Devices and API 29 and Below

Samsung devices and older Android versions often exhibit **incorrect IME padding behavior**. Symptoms:
- Keyboard partially obscures TextField
- Extra padding when keyboard is dismissed
- Inconsistent behavior across devices

### Custom Position-Aware IME Modifier

This custom modifier solves device-specific issues:

```kotlin
fun Modifier.customImePadding(): Modifier = composed {
    val density = LocalDensity.current
    val windowInsets = WindowInsets.ime
    val imeBottom = windowInsets.getBottom(density)

    this.then(
        Modifier.padding(bottom = with(density) { imeBottom.toDp() })
    )
}

// Usage
@Composable
fun FormScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .customImePadding() // Use custom instead of .imePadding()
    ) {
        TextField(...)
    }
}
```

### ModalBottomSheet with TextField

ModalBottomSheet has known double-padding issues. Disable default insets and handle manually:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetWithForm() {
    ModalBottomSheet(
        onDismissRequest = { /* dismiss */ },
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
        windowInsets = WindowInsets(0) // Disable default insets
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

**Critical**: Set `windowInsets = WindowInsets(0)` to prevent double padding.

---

## Keyboard Visibility Detection

### The Most Reliable Approach

```kotlin
@Composable
fun keyboardAsState(): State<Boolean> {
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return rememberUpdatedState(isImeVisible)
}

// Usage
@Composable
fun AdaptiveScreen() {
    val isKeyboardOpen by keyboardAsState()

    Scaffold(
        bottomBar = {
            // Hide bottom nav when keyboard is open
            if (!isKeyboardOpen) {
                NavigationBar { /* items */ }
            }
        }
    ) { innerPadding ->
        Content(innerPadding)
    }
}
```

**Use cases**:
- Hiding bottom navigation when keyboard appears
- Adjusting layout based on keyboard state
- Showing/hiding FABs during text input
- Optimizing screen space for forms

**Note**: The official `WindowInsets.isImeVisible` API exists but remains limited. The pattern above is more reliable.

---

## System Bars and Edge-to-Edge

### Current Standard: enableEdgeToEdge()

**The Accompanist System UI Controller is fully deprecated** as of August 2023. The modern approach uses `enableEdgeToEdge()` from androidx.activity 1.8.0+:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
                detectDarkMode = { resources ->
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                }
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )

        setContent {
            YourAppTheme {
                YourAppContent()
            }
        }
    }
}
```

This single method replaces the previous `WindowCompat.setDecorFitsSystemWindows()` pattern and provides **better backwards compatibility** with automatic scrim handling on older Android versions.

### System Bar Icon Color Control

For dynamic icon colors that respond to theme changes:

```kotlin
@Composable
fun YourAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
```

**Explanation**:
- `isAppearanceLightStatusBars = true`: Dark icons on light background
- `isAppearanceLightStatusBars = false`: Light icons on dark background
- Use `SideEffect` to update when theme changes

---

## Android 15 Mandatory Edge-to-Edge

### Critical for Apps Targeting SDK 35

**Edge-to-edge is automatically enforced** on Android 15+ devices. Apps targeting SDK 35 **must** implement proper insets handling or UI will be obscured by system bars.

**What happens on Android 15**:
- System status bar becomes **transparent** by default
- Gesture navigation bars become **transparent**
- Three-button navigation becomes **translucent**
- Content draws behind system UI automatically

### The Temporary Opt-Out (DO NOT USE)

```xml
<!-- DO NOT USE THIS - will be removed in future Android versions -->
<application
    android:windowOptOutEdgeToEdgeEnforcement="true">
</application>
```

**Warning**: This temporary opt-out mechanism will be removed. **Do not rely on it**.

### Proper Android 15 Compliance

```kotlin
// 1. Use enableEdgeToEdge() in Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Mandatory for SDK 35+
        setContent { /* ... */ }
    }
}

// 2. Handle insets in all screens
@Composable
fun MyScreen() {
    Scaffold(
        topBar = { TopAppBar(...) }, // Auto-handles status bar
        bottomBar = { NavigationBar {...} } // Auto-handles nav bar
    ) { innerPadding ->
        Content(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        )
    }
}
```

---

## Complete Edge-to-Edge Pattern

The production-ready pattern that handles all edge cases:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeToEdgeScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My App") },
                // TopAppBar automatically handles status bar insets
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                // NavigationBar automatically handles navigation bar insets
                NavigationBarItem(
                    selected = true,
                    onClick = { /* navigate */ },
                    icon = { Icon(Icons.Default.Home, "Home") },
                    label = { Text("Home") }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = innerPadding
        ) {
            items(100) { index ->
                ListItem(
                    headlineContent = { Text("Item $index") }
                )
            }
        }
    }
}
```

**What this handles**:
- ✅ Status bar insets (via TopAppBar)
- ✅ Navigation bar insets (via NavigationBar)
- ✅ Keyboard insets (via imePadding)
- ✅ Proper padding consumption (no double padding)
- ✅ Scrollable content with proper insets

---

## Material 3 Automatic Inset Handling

Material 3 components **automatically apply appropriate insets** based on Material Design specifications:

| Component | Auto-Handles Insets | Manual Padding Needed |
|-----------|---------------------|------------------------|
| `TopAppBar` | ✅ Status bar | ❌ None |
| `BottomAppBar` | ✅ Navigation bar | ❌ None |
| `NavigationBar` | ✅ Navigation bar | ❌ None |
| `ModalBottomSheet` | ✅ Bottom + horizontal | ⚠️ IME (manual) |
| `Scaffold` | ❌ Provides as param | ✅ Apply to content |
| `LazyColumn` | ❌ None | ✅ Apply with modifier |

**Critical**: **Material 2 components do not have this behavior**—manual inset handling is required.

### Don't Add Manual Padding to M3 Components

```kotlin
// ❌ WRONG - TopAppBar already handles status bar
TopAppBar(
    title = { Text("Title") },
    modifier = Modifier.statusBarsPadding() // ❌ Double padding!
)

// ✅ CORRECT - let TopAppBar handle it
TopAppBar(
    title = { Text("Title") }
    // No manual padding needed
)
```

---

## Scaffold contentWindowInsets Configuration

The `contentWindowInsets` parameter controls how Scaffold provides insets to content:

### Default Behavior

```kotlin
// Uses systemBarsForVisualComponents by default
Scaffold { innerPadding ->
    Content(modifier = Modifier.padding(innerPadding))
}
```

### Full Edge-to-Edge with Manual Control

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0), // Disable default insets
    topBar = { TopAppBar(...) }
) { innerPadding ->
    Box(
        Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .systemBarsPadding() // Apply manually where needed
    ) {
        Content()
    }
}
```

**Use case**: When you need fine-grained control over which parts of your content respect which insets.

---

## Handling Gesture Navigation

For gesture-sensitive content like bottom sheets and swipeable carousels, use `WindowInsets.safeGestures` to avoid conflicts with system navigation gestures:

```kotlin
@Composable
fun SwipeableContent() {
    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.safeGestures)
            .fillMaxSize()
    ) {
        // Swipeable carousel, bottom sheet, etc.
        // Won't conflict with system back gesture
    }
}
```

### Landscape Mode Requires Special Attention

**Navigation bars appear on sides** instead of bottom in landscape:

```kotlin
@Composable
fun LandscapeAwareContent() {
    val orientation = LocalConfiguration.current.orientation

    LazyColumn(
        modifier = Modifier.then(
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            } else {
                Modifier // navigationBars handled by BottomBar in portrait
            }
        )
    ) {
        items(data) { item -> /* ... */ }
    }
}
```

### Getting Individual Edge Insets

```kotlin
@Composable
fun SideSpecificPadding() {
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
        Content()
    }
}
```

---

## Display Cutout Handling

Android 15+ interprets all cutout modes (DEFAULT, NEVER, SHORT_EDGES) as ALWAYS for non-floating windows. Handle cutouts explicitly:

### Built-in Helper

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .displayCutoutPadding() // Built-in helper
) {
    CriticalContent() // Won't be obscured by notch/punch-hole
}
```

### Manual Approach

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.displayCutout)
) {
    CriticalContent()
}
```

### Getting Cutout Dimensions

```kotlin
@Composable
fun CutoutAwareLayout() {
    val density = LocalDensity.current
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val cutoutBottom = WindowInsets.displayCutout.getBottom(density)

    // Use these values for custom layout calculations
    Column(
        modifier = Modifier.padding(
            top = with(density) { cutoutTop.toDp() }
        )
    ) {
        Content()
    }
}
```

---

## Summary: The Complete Checklist

Before considering WindowInsets implementation complete:

✅ **Activity Setup**:
- [ ] `enableEdgeToEdge()` called in onCreate()
- [ ] Targeting SDK 35 (Android 15+)

✅ **Manifest Configuration**:
- [ ] `android:windowSoftInputMode="adjustResize"` set

✅ **Theme Configuration**:
- [ ] System bar icon colors set properly for dark/light mode
- [ ] Using Material 3 components (auto-handle insets)

✅ **Screen Implementation**:
- [ ] Scaffold used for top-level structure
- [ ] `innerPadding` applied to content
- [ ] `consumeWindowInsets(innerPadding)` used
- [ ] `.imePadding()` on scrollable content with TextFields

✅ **Testing**:
- [ ] Tested on Android 15 device with gesture navigation
- [ ] Tested with keyboard open/close cycles
- [ ] Tested in landscape mode
- [ ] Tested on device with display cutout
- [ ] Tested dark and light modes

✅ **Advanced Features** (if needed):
- [ ] `imeNestedScroll()` for auto-dismiss
- [ ] `BringIntoViewRequester` for complex forms
- [ ] Custom IME modifier for Samsung compatibility
- [ ] Landscape-specific navigation bar handling

---

**Last Updated**: October 2025
**Target Platform**: Android 15+ (SDK 35)
**Minimum Dependencies**: androidx.compose.foundation 1.2.0+, androidx.activity-compose 1.9.0+
