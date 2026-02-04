# Material Design 3 Implementation Guide

> **Complete guide to implementing Material 3 with dynamic colors, proper theming, and component migration**

## Table of Contents

1. [Latest Version Landscape](#latest-version-landscape)
2. [BOM Setup and Dependency Management](#bom-setup-and-dependency-management)
3. [Complete Theme Setup with Dynamic Color](#complete-theme-setup-with-dynamic-color)
4. [ColorScheme Architecture](#colorscheme-architecture)
5. [Typography System](#typography-system)
6. [Core M3 Components](#core-m3-components)
7. [Material 2 to Material 3 Migration](#material-2-to-material-3-migration)

---

## Latest Version Landscape

**Material 3 version 1.4.0** is stable as of the October 2025 BOM.

### Current Versions (October 2025)

- **Compose BOM**: `2025.10.01` (latest stable)
- **Material 3**: `1.4.0` (via BOM)
- **Compose UI**: `1.9.3` (via BOM)
- **Compose Foundation**: Synchronized via BOM

### Three BOM Variants

Since September 2024, three BOM variants are available:

| BOM Variant | What It Includes | Use When |
|-------------|------------------|----------|
| `compose-bom` | Latest **stable** only | ✅ Production apps (recommended) |
| `compose-bom-beta` | Latest **beta** or stable | Testing upcoming features |
| `compose-bom-alpha` | Latest **alpha**, beta, or stable | Experimental development |

**Recommendation**: Use `compose-bom` for production apps.

---

## BOM Setup and Dependency Management

### Complete build.gradle.kts Configuration

```kotlin
// build.gradle.kts (Module level)
dependencies {
    // Compose BOM - manages all Compose versions
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // No version numbers needed when using BOM
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")

    // Activity Compose for enableEdgeToEdge()
    implementation("androidx.activity:activity-compose:1.9.0")

    // Navigation (if using Compose Navigation)
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // ViewModel integration
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // For testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

### Benefits of Using BOM

✅ **Version synchronization**: All Compose libraries guaranteed compatible
✅ **Simplified updates**: Update BOM version, all libraries update
✅ **No version conflicts**: BOM resolves compatible versions automatically
✅ **Easier maintenance**: Single version number to track

### Checking BOM Contents

View what versions the BOM provides:

```bash
./gradlew :app:dependencies | grep compose
```

---

## Complete Theme Setup with Dynamic Color

The production-ready theme implementation supporting Material You:

```kotlin
@Composable
fun MyAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Enable Material You
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Dynamic color available on Android 12+ (API 31+)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
```

### Defining Custom Color Schemes

```kotlin
// Light color scheme
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),

    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),

    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),

    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),

    // Surface containers (M3 elevation system)
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
)

// Dark color scheme
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),

    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),

    // Surface containers
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
)
```

### Dynamic Color Extraction

Material You automatically extracts colors from the user's wallpaper on Android 12+ devices, creating **tonal palettes** with 13 variants per key color.

**Key points**:
- ✅ Always provide **fallback color schemes** for Android 11 and below
- ✅ Test with different wallpapers on Android 12+ devices
- ✅ Ensure your brand is still recognizable with dynamic colors
- ⚠️ Some users disable dynamic colors in system settings

---

## ColorScheme Architecture

M3 ColorScheme includes **5 key colors** with extensive **color roles** for each:

### The Five Key Colors

1. **Primary**: Main brand color, high-emphasis actions
2. **Secondary**: Less prominent actions, supporting content
3. **Tertiary**: Contrasting accents, special highlights
4. **Error**: Errors, warnings, destructive actions
5. **Neutral**: Surfaces, backgrounds, outlines

### Critical Color Roles

| Role | Purpose | Example Usage |
|------|---------|---------------|
| `primary` | High-emphasis actions | FAB, primary buttons |
| `onPrimary` | Text/icons on primary | Button text |
| `primaryContainer` | Less prominent primary | Filled tonal button |
| `onPrimaryContainer` | Text on primary container | Tonal button text |
| `surface` | Component backgrounds | Cards, sheets, dialogs |
| `onSurface` | Text on surfaces | Body text |
| `surfaceVariant` | Lower-emphasis surfaces | List item backgrounds |
| `onSurfaceVariant` | Text on surface variants | Secondary text |
| `surfaceContainer` | Default container elevation | Card background |
| `surfaceContainerLowest` | Lowest elevation | Bottom sheet background |
| `surfaceContainerHighest` | Highest elevation | Elevated card |
| `outline` | Borders, dividers | TextField outline |
| `outlineVariant` | Low-emphasis borders | Disabled borders |

### Surface Container Hierarchy

M3 replaces elevation-based tinting from M2 with explicit surface containers:

```kotlin
// From lowest to highest elevation
surfaceContainerLowest   // Level 0
surfaceContainerLow      // Level 1
surfaceContainer         // Level 2 (default)
surfaceContainerHigh     // Level 3
surfaceContainerHighest  // Level 4
```

**Usage**:
```kotlin
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    )
) { /* content */ }
```

### Generate Color Schemes Efficiently

Use the Material Theme Builder at **material.io/material-theme-builder**:

1. Upload your brand colors or logo
2. Tool generates complete light/dark color schemes
3. Export as Kotlin code
4. Copy directly into your theme file

---

## Typography System

M3 simplifies typography to **15 text styles** organized into 5 categories:

### Complete Typography Definition

```kotlin
val AppTypography = Typography(
    // Display - largest text
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // Headline - large, emphasis
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Title - medium emphasis
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body - main content
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // Label - buttons, labels
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

### Using Custom Fonts

```kotlin
val RobotoFontFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold)
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        // ... other properties
    ),
    // Set fontFamily on each TextStyle individually
)
```

**Key difference from M2**: Typography class doesn't have `defaultFontFamily` parameter. Set `fontFamily` on individual `TextStyle` instances.

---

## Core M3 Components

### Navigation Components

| Component | Purpose | Auto-Handles Insets |
|-----------|---------|---------------------|
| `NavigationBar` | Bottom navigation (replaces BottomNavigation) | ✅ Navigation bar |
| `NavigationRail` | Side navigation for tablets/landscape | ✅ Navigation bar sides |
| `NavigationDrawer` | Modal and permanent variants | ⚠️ Manual for content |
| `TabRow` | Primary and secondary variants | ❌ Manual |

**Example - NavigationBar**:
```kotlin
NavigationBar {
    NavigationBarItem(
        selected = selectedIndex == 0,
        onClick = { selectedIndex = 0 },
        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
        label = { Text("Home") }
    )
    NavigationBarItem(
        selected = selectedIndex == 1,
        onClick = { selectedIndex = 1 },
        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
        label = { Text("Settings") }
    )
}
```

### Button Variants

Clear hierarchy from highest to lowest emphasis:

| Button Type | Emphasis Level | Use Case |
|-------------|----------------|----------|
| `Button` (filled) | Highest | Primary action, most important |
| `FilledTonalButton` | High | Important but not primary |
| `ElevatedButton` | Medium | Secondary action |
| `OutlinedButton` | Medium | Secondary action, needs boundary |
| `TextButton` | Lowest | Tertiary actions, dialogs |

**IconButton has matching variants**: `IconButton`, `FilledIconButton`, `FilledTonalIconButton`, `OutlinedIconButton`.

### Input Components

All stable and support Material 3 theming:

- **`TextField`**: Filled text input (default style)
- **`OutlinedTextField`**: Outlined text input (common for forms)
- **`DatePicker`**: ✅ Stable in 1.4.0
- **`DateRangePicker`**: ✅ Stable in 1.4.0
- **`TimePicker`**: ✅ Stable

### Container Components

| Component | Purpose | Variants |
|-----------|---------|----------|
| `Card` | Content container | Elevated, Outlined, default (filled) |
| `ModalBottomSheet` | Modal overlay from bottom | Handles insets automatically |
| `Scaffold` | Top-level layout structure | Provides innerPadding |
| `Surface` | Custom containers with elevation | Shadow + tonal elevation |

### New in 1.3.0-1.4.0

- **`Carousel`**: Image galleries with paging
- **`WideNavigationRail`**: Large screen navigation
- **`ShortNavigationBar`**: Large screen bottom nav
- **Enhanced tooltip positioning**: Better placement logic
- **Pull-to-refresh API stabilized**: `PullToRefreshBox`
- **Motion theming**: `MotionScheme` for consistent animations

---

## Material 2 to Material 3 Migration

### Critical Migration Mappings

| M2 Concept | M3 Replacement | Type of Change |
|------------|----------------|----------------|
| `backgroundColor` | `containerColor` | Parameter rename |
| `BottomNavigation` | `NavigationBar` | Component rename |
| `BottomNavigationItem` | `NavigationBarItem` | Component rename |
| `Surface(elevation)` | `Surface(shadowElevation, tonalElevation)` | Separate elevation types |
| `ContentAlpha.medium` | `onSurfaceVariant` color role | Removed concept |
| `ContentAlpha.disabled` | `onSurface` with 38% alpha | Removed concept |
| `ScaffoldState` | `SnackbarHostState` directly | Simplified API |
| `Colors` (M2) | `ColorScheme` (M3) | New color system |

### Typography Mappings

Systematic pattern from M2 to M3:

| M2 Typography | M3 Typography | Notes |
|---------------|---------------|-------|
| `h1` | `displayLarge` | Largest display text |
| `h2` | `displayMedium` | |
| `h3` | `displaySmall` | |
| `h4` | `headlineLarge` | Page titles |
| `h5` | `headlineMedium` | |
| `h6` | `headlineSmall` | |
| `subtitle1` | `titleLarge` | Section titles |
| `subtitle2` | `titleMedium` | |
| `body1` | `bodyLarge` | Main content |
| `body2` | `bodyMedium` | |
| `caption` | `bodySmall` | Small annotations |
| `button` | `labelLarge` | Button text |
| `overline` | `labelSmall` | Small labels |

### Phased Migration Strategy

**Phase 1: Add M3 alongside M2** (1-2 days)
```gradle
implementation("androidx.compose.material:material") // Keep M2
implementation("androidx.compose.material3:material3") // Add M3
```

**Phase 2: Migrate theme setup** (1 day)
```kotlin
// Create M3 theme
@Composable
fun MyAppM3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = AppTypography,
        content = content
    )
}

// Use in app
setContent {
    MyAppM3Theme {
        YourApp()
    }
}
```

**Phase 3: Migrate components screen by screen** (1-2 weeks)
- Start with new screens (use M3 from start)
- Migrate existing screens one at a time
- Update imports from `androidx.compose.material` → `androidx.compose.material3`
- Update component names (BottomNavigation → NavigationBar)
- Update parameter names (backgroundColor → containerColor)

**Phase 4: Remove M2 dependency** (1 day)
```gradle
// implementation("androidx.compose.material:material") // Remove this line
implementation("androidx.compose.material3:material3")
```

### Common Migration Issues

**1. Double padding with TopAppBar**
```kotlin
// ❌ PROBLEM: M2 TopAppBar needed manual padding
androidx.compose.material.TopAppBar(
    modifier = Modifier.statusBarsPadding() // M2 pattern
)

// ✅ SOLUTION: M3 TopAppBar handles it automatically
androidx.compose.material3.TopAppBar(
    // No manual padding needed
)
```

**2. Elevation changes**
```kotlin
// ❌ M2: Single elevation value
Surface(elevation = 4.dp) { }

// ✅ M3: Separate shadow and tonal elevation
Surface(
    shadowElevation = 4.dp,   // Actual shadow
    tonalElevation = 2.dp     // Tint amount
) { }
```

**3. Color alpha changes**
```kotlin
// ❌ M2: ContentAlpha
Text(
    text = "Secondary",
    color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium)
)

// ✅ M3: Dedicated color roles
Text(
    text = "Secondary",
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

### Migration Checklist

Before considering migration complete:

- [ ] All screens using M3 components
- [ ] Theme using `ColorScheme` not `Colors`
- [ ] Typography using M3 scale (15 styles)
- [ ] All `backgroundColor` → `containerColor`
- [ ] All `BottomNavigation` → `NavigationBar`
- [ ] All `ContentAlpha` removed
- [ ] Edge-to-edge working with M3 auto-insets
- [ ] M2 dependency removed from build.gradle
- [ ] All imports updated to `material3`
- [ ] Tested dark and light modes
- [ ] Tested dynamic color on Android 12+

---

## Summary: M3 Implementation Best Practices

### ✅ Do This

- Use the Compose BOM for version management
- Implement dynamic color with fallback schemes
- Define all 15 typography styles
- Use surface container hierarchy for elevation
- Let M3 components handle their own insets
- Generate color schemes with Material Theme Builder
- Test on Android 12+ for dynamic colors
- Provide light and dark color schemes

### ❌ Don't Do This

- Don't add manual status bar padding to TopAppBar (it handles it)
- Don't use `backgroundColor` (use `containerColor`)
- Don't use ContentAlpha (use color roles)
- Don't mix M2 and M3 components in the same screen
- Don't skip testing dark mode
- Don't forget fallback colors for Android < 12

### Quick Reference

```kotlin
// Complete M3 setup
@Composable
fun MyApp() {
    MyAppTheme { // Theme with ColorScheme + Typography
        Scaffold(
            topBar = { TopAppBar(...) }, // Auto-handles insets
            bottomBar = { NavigationBar {...} } // Auto-handles insets
        ) { innerPadding ->
            Content(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            )
        }
    }
}
```

---

**Last Updated**: October 2025
**Material 3 Version**: 1.4.0
**Compose BOM**: 2025.10.01
**Migration Guide**: developer.android.com/develop/ui/compose/designsystems/material2-material3
