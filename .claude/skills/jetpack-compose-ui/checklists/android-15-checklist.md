# Android 15 (SDK 35) Compliance Checklist

## Overview

Android 15 **automatically enforces edge-to-edge** for all apps. This checklist ensures your UI handles system bars, keyboard, and display cutouts correctly.

---

## 1. Activity Setup

### enableEdgeToEdge() Called

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // ✅ Required for SDK 35+
        setContent { /* ... */ }
    }
}
```

- [ ] `enableEdgeToEdge()` called in `onCreate()` **before** `setContent()`
- [ ] Using androidx.activity:activity-compose **1.9.0+**
- [ ] No calls to deprecated `WindowCompat.setDecorFitsSystemWindows()`

---

## 2. Manifest Configuration

### AndroidManifest.xml

```xml
<activity
    android:name=".MainActivity"
    android:windowSoftInputMode="adjustResize"/> <!-- ✅ CRITICAL for IME -->
```

- [ ] `android:windowSoftInputMode="adjustResize"` set on MainActivity
- [ ] NOT using `android:windowOptOutEdgeToEdgeEnforcement` (deprecated)
- [ ] Target SDK set to 35

---

## 3. Top Bar (Status Bar Area)

### Using Material 3 TopAppBar (Recommended)

```kotlin
Scaffold(
    topBar = {
        TopAppBar(title = { Text("Title") })
        // Automatically handles status bar insets
    }
) { /* ... */ }
```

- [ ] Using M3 `TopAppBar` (auto-handles insets)
- [ ] NOT manually adding `.statusBarsPadding()` to TopAppBar
- [ ] TopAppBar title and icons visible, not obscured
- [ ] Tested in both light and dark mode

### OR Using Custom Top Content

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding() // Manual padding for custom content
) { /* ... */ }
```

- [ ] Applied `.statusBarsPadding()` or `.safeDrawingPadding()`
- [ ] Top content not obscured by status bar

---

## 4. Bottom Bar (Navigation Bar Area)

### Using Material 3 NavigationBar (Recommended)

```kotlin
Scaffold(
    bottomBar = {
        NavigationBar { /* items */ }
        // Automatically handles navigation bar insets
    }
) { /* ... */ }
```

- [ ] Using M3 `NavigationBar` or `BottomAppBar` (auto-handles insets)
- [ ] NOT manually adding `.navigationBarsPadding()`
- [ ] All navigation items visible and tappable
- [ ] Tested in both portrait and landscape

### OR Using Custom Bottom Content

```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .navigationBarsPadding() // Manual padding
) { /* ... */ }
```

- [ ] Applied `.navigationBarsPadding()` or `.safeDrawingPadding()`
- [ ] Bottom content not obscured by navigation bar

---

## 5. Content Area (Scaffold Integration)

### Proper Scaffold Pattern

```kotlin
Scaffold(
    topBar = { TopAppBar(...) },
    bottomBar = { NavigationBar {...} }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .consumeWindowInsets(innerPadding) // ✅ CRITICAL
            .imePadding(), // ✅ For keyboard
        contentPadding = innerPadding
    ) { /* ... */ }
}
```

- [ ] `innerPadding` parameter used
- [ ] `.consumeWindowInsets(innerPadding)` applied
- [ ] `.imePadding()` applied to scrollable content with TextFields
- [ ] `contentPadding = innerPadding` on LazyColumn/LazyRow
- [ ] No visible double padding

---

## 6. Keyboard (IME) Handling

### IME Padding Applied

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .imePadding() // ✅ Handles keyboard automatically
) {
    TextField(...)
}
```

- [ ] `.imePadding()` applied to content with TextFields
- [ ] `android:windowSoftInputMode="adjustResize"` in manifest (from step 2)
- [ ] TextField scrolls into view when focused
- [ ] Keyboard doesn't obscure focused TextField
- [ ] Tested on Samsung device (if available)
- [ ] Tested on API 29 and below (if targeting older devices)

---

## 7. Display Cutout Handling

### Cutout Padding

```kotlin
Box(
    Modifier
        .displayCutoutPadding() // OR
        .windowInsetsPadding(WindowInsets.displayCutout)
) { /* ... */ }
```

- [ ] Critical UI not obscured by notch/punch-hole
- [ ] Tested on device with display cutout
- [ ] Applied `.displayCutoutPadding()` where needed

---

## 8. Gesture Navigation Compatibility

### Safe Gesture Areas

```kotlin
Box(
    Modifier.windowInsetsPadding(WindowInsets.safeGestures)
) {
    // Swipeable content that won't conflict with back gesture
}
```

- [ ] Swipeable content doesn't conflict with system back gesture
- [ ] Used `.safeGestures` for swipeable carousels/bottom sheets
- [ ] Tested with gesture navigation enabled

---

## 9. Landscape Mode

### Side Navigation Bars

```kotlin
val orientation = LocalConfiguration.current.orientation
LazyColumn(
    modifier = Modifier.then(
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
        } else Modifier
    )
) { /* ... */ }
```

- [ ] Content not obscured in landscape mode
- [ ] Navigation bars on sides handled (if applicable)
- [ ] TopAppBar and content layouts work in landscape

---

## 10. Testing Verification

### Manual Testing Checklist

#### On Android 15+ Device
- [ ] Gesture navigation: Content not obscured, gestures work
- [ ] 3-button navigation: Content not obscured, buttons work
- [ ] Portrait mode: All UI visible and functional
- [ ] Landscape mode: All UI visible and functional
- [ ] Dark mode: System bar icons visible (light icons on dark bars)
- [ ] Light mode: System bar icons visible (dark icons on light bars)
- [ ] Keyboard open: TextField visible, content scrolls correctly
- [ ] Keyboard closed: No extra padding, layout correct
- [ ] Display cutout (if device has one): Content not obscured

#### Edge Cases
- [ ] Dialog content not obscured by system bars
- [ ] ModalBottomSheet with TextField: Keyboard handling correct
- [ ] Fullscreen content (like video): Can hide system bars if needed
- [ ] AlertDialog: Content visible and positioned correctly

---

## 11. Common Issues Checklist

### Troubleshooting

If content is obscured:
- [ ] Verified `enableEdgeToEdge()` is called
- [ ] Checked for missing `.systemBarsPadding()` or `.safeDrawingPadding()`
- [ ] Verified M3 components used (not M2)
- [ ] Checked for manual padding on M3 components (should remove)

If keyboard obscures TextField:
- [ ] Verified `android:windowSoftInputMode="adjustResize"` in manifest
- [ ] Verified `.imePadding()` applied
- [ ] Tried custom IME padding modifier (for Samsung/old devices)
- [ ] Verified no conflicting windowSoftInputMode in theme

If double padding visible:
- [ ] Added `.consumeWindowInsets(innerPadding)` after applying Scaffold padding
- [ ] Removed manual padding from M3 TopAppBar/NavigationBar
- [ ] Verified correct modifier order: consume, then pad

---

## Final Sign-Off

- [ ] All items above checked and verified
- [ ] Tested on physical Android 15+ device
- [ ] Tested on emulator with gesture navigation
- [ ] No content obscured by system UI
- [ ] Keyboard handling works correctly
- [ ] Ready for production
