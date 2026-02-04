---
name: jetpack-compose-ui
description: Use when creating Jetpack Compose UI screens, handling WindowInsets/IME padding, implementing Material 3 components, managing edge-to-edge display on Android 15+, optimizing Compose performance with stability/remember, troubleshooting keyboard issues, state hoisting, or implementing unidirectional data flow. Provides battle-tested patterns, templates, and best practices for modern Compose development targeting SDK 35.
---

# Jetpack Compose UI Development Skill

## Overview

Comprehensive guidance for Jetpack Compose UI development on Android 15+ with mandatory edge-to-edge enforcement, Material 3 integration, proper WindowInsets handling, performance optimization through stability analysis, and production-ready state management patterns. Based on battle-tested patterns from 2025.

**Critical Context**: Android 15 (SDK 35) **automatically enforces edge-to-edge** for all apps. The Accompanist library suite is **fully deprecated**. Material 3 is production-ready (v1.4.0+). Proper WindowInsets handling is mandatory, not optional.

## When to Use This Skill

This skill should be activated automatically when:

**File patterns**:
- Editing or creating `*/ui/screens/*.kt`
- Editing or creating `*/ui/components/*.kt`
- Editing or creating `*/ui/theme/*.kt`
- Modifying `MainActivity.kt` for edge-to-edge setup
- Editing `build.gradle.kts` for Compose dependencies

**Keywords in request**:
- "Jetpack Compose", "Compose UI", "composable"
- "WindowInsets", "IME", "keyboard", "imePadding"
- "edge-to-edge", "system bars", "status bar", "navigation bar"
- "Material 3", "Material Design"
- "Compose performance", "recomposition", "stability"
- "state hoisting", "remember", "derivedStateOf"
- "LazyColumn", "LazyRow", "Scaffold"
- "TextField", "bottom sheet", "dialog"

**Task types**:
- Creating new Compose screens or components
- Implementing navigation between screens
- Handling keyboard/IME padding issues
- Setting up edge-to-edge display
- Fixing performance/jank issues
- Migrating Material 2 to Material 3
- Implementing proper state management
- Creating themes with dynamic color
- Troubleshooting UI issues

## Quick Reference: Common Scenarios

### "I need to create a new screen with proper edge-to-edge"
1. Read `docs/WINDOWINSETS_GUIDE.md` section "Complete edge-to-edge pattern"
2. Verify `enableEdgeToEdge()` is called in MainActivity
3. Use template: `templates/edge-to-edge-screen.kt`
4. Verify with: `checklists/android-15-checklist.md`
5. Test on Android 15 device

**Quick check**: TopAppBar handles status bar, content handles IME, NavigationBar handles nav bar

### "I need to handle the keyboard (IME padding)"
1. **CRITICAL**: Verify AndroidManifest has `android:windowSoftInputMode="adjustResize"`
2. Read `docs/WINDOWINSETS_GUIDE.md` section "IME padding mastery"
3. Apply `.imePadding()` modifier to scrollable content
4. For complex forms, use template: `templates/ime-handling-form.kt`
5. If issues persist, check `docs/TROUBLESHOOTING.md` section "Device-specific IME issues"

**Quick fix**: Add `Modifier.imePadding()` to your Scaffold content or form container

### "UI is slow/janky during scrolling or interaction"
1. Read `docs/PERFORMANCE_GUIDE.md` section "Understanding stability"
2. Check for unstable parameters (List, mutable properties)
3. Add `remember` with proper keys for calculations
4. Use `derivedStateOf` for threshold-based state
5. Generate compiler stability reports (see guide)
6. Use template: `templates/optimized-lazy-list.kt` for lists

**Quick fix**: Add stable keys to LazyColumn items: `items(items, key = { it.id })`

### "I need to implement Material 3 theme with dynamic colors"
1. Read `docs/MATERIAL3_GUIDE.md` section "Complete theme setup"
2. Use template: `templates/material3-theme.kt`
3. Generate color scheme at material.io/material-theme-builder
4. Update components (BottomNavigation → NavigationBar, etc.)
5. Test dark mode and dynamic color on Android 12+

**Quick start**: Copy `templates/material3-theme.kt` and customize ColorScheme

### "Bottom sheet keyboard handling is broken"
1. Read `docs/TROUBLESHOOTING.md` section "ModalBottomSheet double padding"
2. Use template: `templates/bottom-sheet-ime.kt`
3. Set `windowInsets = WindowInsets(0)` to disable default
4. Manually apply `.imePadding()` to content
5. Test with keyboard open/close cycles

**Quick fix**: `ModalBottomSheet(windowInsets = WindowInsets(0), modifier = Modifier.imePadding())`

### "I need to hoist state properly"
1. Read `docs/STATE_MANAGEMENT.md` section "State hoisting fundamentals"
2. Review pattern: `patterns/state-hoisting-pattern.md`
3. Move state up to appropriate level (parent composable or ViewModel)
4. Pass state down, callbacks up (unidirectional flow)
5. Use template: `templates/viewmodel-compose.kt` for ViewModel integration

**Quick pattern**: State in parent, pass as parameter + onEvent callback to child

### "TextField focus and keyboard coordination issues"
1. Read `docs/TROUBLESHOOTING.md` section "TextField focus and keyboard coordination"
2. Use `LocalFocusManager` to clear focus
3. Use `LocalSoftwareKeyboardController` to hide keyboard
4. Implement `BringIntoViewRequester` for complex forms
5. Check pattern: `patterns/windowinsets-pattern.md`

**Quick fix**: `LocalFocusManager.current.clearFocus()` hides keyboard + removes focus

### "I need to prevent unnecessary recompositions"
1. Read `docs/PERFORMANCE_GUIDE.md` section "Cache expensive calculations"
2. Wrap calculations in `remember` with proper keys
3. Use `derivedStateOf` for frequently changing state
4. Defer state reads to lambda modifiers (`Modifier.offset { }`)
5. Check stability with compiler reports

**Quick fix**: Wrap calculation in `remember(deps) { expensiveCalculation() }`

### "Content is hidden behind system bars"
1. Verify `enableEdgeToEdge()` in MainActivity
2. Add `.systemBarsPadding()` or `.safeDrawingPadding()`
3. For Scaffold, use `innerPadding` parameter properly
4. Remember: Material 3 TopAppBar/NavigationBar handle their own insets
5. Use pattern: `patterns/windowinsets-pattern.md`

**Quick fix**: Add `Modifier.systemBarsPadding()` to your root content

### "Migrating from Material 2 to Material 3"
1. Read `docs/MATERIAL3_GUIDE.md` section "Material 2 to Material 3 migration"
2. Check mapping table (backgroundColor → containerColor, etc.)
3. Update components (BottomNavigation → NavigationBar)
4. Update theme to use ColorScheme instead of Colors
5. Update typography (h1-h6 → display/headline scales)

**Key mappings**: BottomNavigation→NavigationBar, backgroundColor→containerColor, elevation→shadowElevation+tonalElevation

## Documentation Structure

### docs/ - Deep Technical Knowledge (5 comprehensive guides)

**WINDOWINSETS_GUIDE.md** (~650 lines)
- WindowInsets system (statusBars, navigationBars, ime, displayCutout, safeDrawing)
- Essential setup requirements (Activity, Manifest, modifiers)
- Scaffold integration patterns
- Advanced keyboard handling (imeNestedScroll, BringIntoViewRequester)
- Device-specific IME issues and solutions
- Keyboard visibility detection
- Edge-to-edge implementation (enableEdgeToEdge, system bar colors)
- Android 15 mandatory enforcement
- Display cutout and gesture navigation handling

**MATERIAL3_GUIDE.md** (~230 lines)
- Latest versions and BOM setup (2025.10.01)
- Complete theme setup with dynamic color (Material You)
- ColorScheme architecture (5 key colors, 40+ roles)
- Typography system (15 text styles)
- Core M3 components (Navigation, Buttons, Inputs, Containers)
- Material 2 to Material 3 migration guide with mappings
- Automatic inset handling in M3 components

**PERFORMANCE_GUIDE.md** (~330 lines)
- Three-phase system (Composition, Layout, Drawing)
- Single-pass measurement advantage
- Release builds with R8 (mandatory for accurate benchmarking)
- Baseline Profiles (~30% startup improvement)
- Caching with remember (proper keys)
- Stable keys for LazyColumn/LazyRow
- Understanding stability (Immutable, Stable, Unstable)
- Compiler stability reports
- derivedStateOf for threshold-based state
- Deferring state reads to layout/drawing phases
- Layout Inspector and composition tracing

**STATE_MANAGEMENT.md** (~200 lines)
- State hoisting fundamentals (stateless composables)
- Where to hoist state (decision tree)
- Unidirectional data flow implementation
- State persistence strategies (remember, rememberSaveable, SavedStateHandle)
- ViewModel integration patterns
- Plain state holder classes for complex UI logic

**TROUBLESHOOTING.md** (~320 lines)
- Never write state after reading (backwards write infinite loops)
- Recomposition loops with layout coordination
- Unstable Painter parameters
- IME padding on Samsung/API 29 devices
- Keyboard in DialogFragments (Compose 1.4.0 regression)
- ModalBottomSheet double keyboard padding
- Dialogs don't inherit WindowInsets
- Landscape navigation bars on sides
- Caption bars on freeform windows
- TextField focus and keyboard coordination

### templates/ - Production-Ready Copy-Paste Code (6 templates)

All templates include:
- "When to use" documentation
- Prerequisites checklist
- Fully working, commented code
- Common customization points
- Testing recommendations

**edge-to-edge-screen.kt**
- Complete screen with proper edge-to-edge setup
- TopAppBar handling status bar insets
- Content with IME padding
- NavigationBar handling nav bar insets
- Scaffold integration

**ime-handling-form.kt**
- Complex form with multiple TextFields
- BringIntoViewRequester implementation
- Focus management
- Keyboard dismiss handling
- Scroll coordination

**material3-theme.kt**
- Complete M3 theme setup
- Dynamic color support (Android 12+)
- Fallback color schemes
- Typography system (15 styles)
- Shape system
- System bar icon color control

**optimized-lazy-list.kt**
- LazyColumn with stable keys
- Proper remember usage
- derivedStateOf for scroll state
- consumeWindowInsets pattern
- Performance-optimized item composables

**bottom-sheet-ime.kt**
- ModalBottomSheet with TextField
- windowInsets = WindowInsets(0) pattern
- Manual IME padding
- Proper focus handling
- State management

**viewmodel-compose.kt**
- ViewModel + Compose integration
- StateFlow collection with lifecycle
- Unidirectional data flow
- Event handling pattern
- Hilt integration

### patterns/ - Before/After Transformation Examples (3 patterns)

**windowinsets-pattern.md**
- ❌ Common mistakes (double padding, no consumeWindowInsets)
- ✅ Correct patterns (proper modifier order, consuming insets)
- Scaffold integration
- Dialog and bottom sheet patterns

**stability-pattern.md**
- ❌ Unstable parameters preventing skipping
- ✅ Stable alternatives (immutable data classes, @Stable annotation)
- Collection stability (List → ImmutableList)
- Painter and complex type handling

**state-hoisting-pattern.md**
- ❌ Stateful composables (hard to test/reuse)
- ✅ Stateless composables (hoisted state)
- Where to hoist decisions
- ViewModel vs plain state holder

### checklists/ - Verification and Compliance (3 checklists)

**android-15-checklist.md**
- Edge-to-edge setup verification
- WindowInsets handling checklist
- Testing on Android 15 device
- Gesture navigation compatibility
- Display cutout handling

**performance-checklist.md**
- Stability verification
- remember usage audit
- LazyColumn keys check
- Compiler report analysis
- Benchmark results (target: 60 FPS)

**new-screen-checklist.md**
- Complete checklist for creating new screens
- Edge-to-edge compliance
- State management verification
- Navigation integration
- Testing requirements

### assets/ - Supporting Configuration Files

**compose-dependencies.gradle**
- Recommended dependency versions
- Compose BOM 2025.10.01
- All required androidx libraries
- Testing dependencies

**baseline-profile-rules.pro**
- ProGuard rules for Compose
- Baseline profile generation setup
- R8 optimization configuration

## Performance Targets

| Metric | Target | Verification Method |
|--------|--------|---------------------|
| Frame rate | 60 FPS (< 16ms/frame) | Layout Inspector recomposition counts |
| Scroll jank | < 5% dropped frames | Systrace during scroll |
| Startup time | < 3 seconds cold start | Macrobenchmark |
| Recomposition overhead | Minimal (smart skipping) | Composition tracing |
| Main thread blocking | < 16ms per operation | Profiler |

## Success Indicators

You'll know the skill is working when:

✅ **No keyboard issues** - IME padding works correctly on all devices (including Samsung)
✅ **Proper edge-to-edge** - Content not obscured by system bars on Android 15+
✅ **Smooth scrolling** - 60 FPS during list scrolling with no jank
✅ **Material 3 compliance** - All components using M3 APIs and theming
✅ **No unnecessary recompositions** - Stability analysis shows smart skipping
✅ **Proper state management** - Unidirectional flow with hoisted state
✅ **Production-ready code** - Passes all checklists and meets performance targets

## Integration with Other Tools and Skills

### Use with Columba Project Skills
- **kotlin-android-chaquopy-testing**: For creating UI tests with Compose Testing framework
- **columba-threading-redesign**: For proper coroutine usage in ViewModels with Compose lifecycle

### Use with MCP Servers
- **context7**: Fetch latest official Compose documentation
  ```
  Query: "androidx compose material3 latest version"
  ```
- **reticulum-manual**: When UI needs to display Reticulum network data properly

### Use with Slash Commands
- `/create-compose-screen [ScreenName]`: Scaffolds new screen with this skill's templates and patterns

## Common Pitfalls to Avoid

🚫 **Backwards writes** - Never modify state after reading it in composition
🚫 **Missing consumeWindowInsets()** - Always consume Scaffold padding to prevent double padding
🚫 **No IME padding** - Always add `.imePadding()` to content with TextFields
🚫 **Unstable parameters** - Use immutable data classes and stable collections
🚫 **No keys in LazyColumn** - Always provide stable, unique keys for list items
🚫 **Debug performance analysis** - Always benchmark in release builds with R8
🚫 **Expensive calculations in composition** - Always wrap in `remember` with proper keys
🚫 **Reading state too early** - Defer to lambda modifiers when possible (`Modifier.offset { }`)

## Version Requirements

**Minimum versions for all features**:
- androidx.compose:compose-bom: **2025.10.01** (or later)
- androidx.compose.material3:material3: **1.4.0+** (via BOM)
- androidx.activity:activity-compose: **1.9.0+** (for enableEdgeToEdge)
- androidx.compose.foundation:foundation: **1.2.0+** (for WindowInsets APIs)
- Kotlin: **1.9.0+**
- Target SDK: **35** (Android 15+)

**Deprecated/removed**:
- ❌ Accompanist System UI Controller (use enableEdgeToEdge instead)
- ❌ Accompanist Insets (use androidx WindowInsets instead)
- ❌ Material 2 (migrate to Material 3)
- ❌ WindowCompat.setDecorFitsSystemWindows pattern (use enableEdgeToEdge instead)

## Getting Help

If stuck or encountering issues:

1. **Check troubleshooting first**: `docs/TROUBLESHOOTING.md` covers 90% of common issues
2. **Review relevant pattern**: Look in `patterns/` for before/after examples
3. **Use appropriate template**: Copy from `templates/` and customize
4. **Verify with checklist**: Use `checklists/` to ensure nothing is missed
5. **Check compiler reports**: Generate stability reports to identify unstable parameters

## Project-Specific Context: Columba

**Current Status** (as of threading redesign phase):
- Using Material 3 with Compose BOM 2024.09.03
- Target SDK 35 (Android 15)
- Hilt for dependency injection
- Coroutines for async operations
- Room database with Flow

**Key UI Challenges**:
- MessagingScreen with complex IME coordination (message list + input bar)
- InterfaceConfigDialog with scrollable form fields
- Dynamic network status indicators (identicon, signal strength)
- Bottom sheet keyboard handling
- Dark theme with custom mesh network colors

**Recommended First Actions**:
1. Update Compose BOM to 2025.10.01
2. Migrate MainActivity to use `enableEdgeToEdge()`
3. Add `consumeWindowInsets()` to all Scaffold content areas
4. Implement custom IME padding for Samsung device compatibility
5. Expand typography system from 3 to 15 styles

---

**Last Updated**: October 2025
**Skill Version**: 1.0.0
**Based on**: Jetpack Compose BOM 2025.10.01, Material 3 v1.4.0, Android 15 (API 35)
