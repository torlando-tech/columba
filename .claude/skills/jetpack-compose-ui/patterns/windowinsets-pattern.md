# WindowInsets Pattern Transformations

## Pattern 1: Missing consumeWindowInsets

### ❌ Before (Double Padding)

```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text("Title") }) }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier.padding(innerPadding)
        // Missing: consumeWindowInsets()
    ) {
        items(data) { item ->
            Text(item) // Child composables might apply padding again
        }
    }
}
```

**Problem**: Without `consumeWindowInsets()`, child composables don't know padding was already applied, leading to double padding.

### ✅ After (Correct)

```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text("Title") }) }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .consumeWindowInsets(innerPadding) // ✅ Consume first
            .padding(innerPadding), // Then apply
        contentPadding = innerPadding
    ) {
        items(data) { item ->
            Text(item)
        }
    }
}
```

---

## Pattern 2: Missing IME Padding

### ❌ Before (Keyboard Covers TextField)

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    TextField(
        value = text,
        onValueChange = { text = it }
    )
}
```

**Problem**: No IME padding, keyboard covers TextField.

### ✅ After (Correct)

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .imePadding() // ✅ Adds padding when keyboard appears
) {
    TextField(
        value = text,
        onValueChange = { text = it }
    )
}
```

**Plus ensure**:
- `enableEdgeToEdge()` in Activity
- `android:windowSoftInputMode="adjustResize"` in manifest

---

## Pattern 3: Manual Padding on M3 Components

### ❌ Before (Double Padding)

```kotlin
TopAppBar(
    title = { Text("Title") },
    modifier = Modifier.statusBarsPadding() // ❌ M3 already handles this!
)
```

**Problem**: Material 3 components automatically handle their insets. Manual padding causes double padding.

### ✅ After (Correct)

```kotlin
TopAppBar(
    title = { Text("Title") }
    // No manual padding needed - M3 handles it automatically
)
```

**Components that auto-handle insets**:
- `TopAppBar` → status bar
- `NavigationBar` → navigation bar
- `BottomAppBar` → navigation bar

---

## Pattern 4: ModalBottomSheet with TextField

### ❌ Before (Double Padding or Keyboard Obscures Content)

```kotlin
ModalBottomSheet(
    onDismissRequest = { }
    // Uses default windowInsets
) {
    TextField(...)
}
```

**Problem**: Default insets cause double padding or incorrect keyboard handling.

### ✅ After (Correct)

```kotlin
ModalBottomSheet(
    onDismissRequest = { },
    windowInsets = WindowInsets(0), // ✅ Disable default
    modifier = Modifier
        .systemBarsPadding()
        .imePadding() // ✅ Manual IME padding
) {
    TextField(...)
}
```

---

## Pattern 5: Dialog with Content Behind System Bars

### ❌ Before (Content Obscured)

```kotlin
Dialog(onDismissRequest = {}) {
    Surface {
        Text("Dialog content")
    }
}
```

**Problem**: Dialogs create separate windows and don't inherit insets from activity.

### ✅ After (Correct)

```kotlin
Dialog(onDismissRequest = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars) // ✅ Apply manually
    ) {
        Text("Dialog content")
    }
}
```

---

## Pattern 6: Content Behind System Bars on Android 15

### ❌ Before (Text Obscured by Status Bar)

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    Text("This text is behind status bar!")
}
```

**Problem**: Android 15 enforces edge-to-edge, content draws behind system bars.

### ✅ After Option 1 (Safe Drawing)

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding() // ✅ Avoids all system UI
) {
    Text("This text is visible!")
}
```

### ✅ After Option 2 (Use Scaffold with M3 Components)

```kotlin
Scaffold(
    topBar = { TopAppBar(...) }, // ✅ Handles status bar
    bottomBar = { NavigationBar {...} } // ✅ Handles nav bar
) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding)) {
        Text("This text is visible!")
    }
}
```

---

## Pattern 7: Nested Scaffolds in Navigation

### ❌ Before (Double Padding)

```kotlin
// Parent Scaffold
Scaffold(
    bottomBar = { NavigationBar { ... } }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.padding(paddingValues) // ❌ Applied here
    ) {
        composable("home") {
            // Child Scaffold
            Scaffold(
                topBar = { TopAppBar(...) }
            ) { innerPadding ->
                LazyColumn(modifier = Modifier.padding(innerPadding)) { ... }
            }
        }
    }
}
```

**Problem**: Parent padding + NavHost padding + Child padding = Triple padding at bottom!

### ✅ After Option 1 (No Padding on NavHost - Recommended)

```kotlin
Scaffold(
    bottomBar = { NavigationBar { ... } }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home"
        // ✅ No padding on NavHost - let screens handle it
    ) {
        composable("home") {
            Scaffold(
                topBar = { TopAppBar(...) }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .consumeWindowInsets(innerPadding)
                        .padding(innerPadding)
                ) { ... }
            }
        }
    }
}
```

### ✅ After Option 2 (Use contentPadding)

```kotlin
Scaffold(
    bottomBar = { NavigationBar { ... } }
) { paddingValues ->
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            Scaffold(
                topBar = { TopAppBar(...) }
            ) { innerPadding ->
                LazyColumn(
                    contentPadding = innerPadding  // ✅ Internal padding
                ) { ... }
            }
        }
    }
}
```

**When to use**: Navigation architectures with bottom tabs where each screen has its own TopAppBar.

---

## Quick Reference

| Problem | Solution |
|---------|----------|
| Double padding | Add `.consumeWindowInsets(innerPadding)` |
| Keyboard covers TextField | Add `.imePadding()` |
| TopAppBar double padding | Remove manual `.statusBarsPadding()` (M3 handles it) |
| Bottom sheet keyboard issue | Set `windowInsets = WindowInsets(0)` + manual `.imePadding()` |
| Dialog content obscured | Add `.windowInsetsPadding(WindowInsets.systemBars)` |
| Content behind system bars | Use `.safeDrawingPadding()` or Scaffold with M3 |
| Nested Scaffolds double padding | Remove padding from NavHost, let child screens handle it |
