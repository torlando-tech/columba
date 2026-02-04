# Jetpack Compose UI Development Skill

> **Battle-tested patterns and templates for modern Android UI development with Jetpack Compose, Material 3, and edge-to-edge display on Android 15+**

## What This Skill Provides

This skill equips Claude Code agents with comprehensive knowledge and ready-to-use templates for building production-quality Jetpack Compose UIs. It addresses the most challenging aspects of modern Android UI development:

- ✅ **WindowInsets & Edge-to-Edge**: Master the mandatory edge-to-edge requirements of Android 15
- ✅ **Keyboard (IME) Handling**: Solve keyboard padding issues across all devices, including Samsung quirks
- ✅ **Material 3 Implementation**: Complete theme setup with dynamic colors and proper component usage
- ✅ **Performance Optimization**: Understand stability, prevent unnecessary recompositions, achieve 60 FPS
- ✅ **State Management**: Implement proper state hoisting and unidirectional data flow
- ✅ **Troubleshooting Guide**: Quick solutions for the most common Compose issues

## Quick Start

### For Users (Developers)

This skill works automatically! When you ask Claude Code to work on Compose UI files or mention keywords like "Compose", "keyboard", "IME", "edge-to-edge", etc., this skill will automatically activate and guide the agent.

**Example requests that trigger this skill:**
- "Create a new messaging screen with proper keyboard handling"
- "Fix the bottom sheet keyboard padding issue"
- "Optimize this LazyColumn for better performance"
- "Migrate this screen to Material 3"
- "Implement edge-to-edge display for Android 15"

### For Claude Code Agents

When this skill activates, start with the **Quick Reference** section in `SKILL.md` to find the exact docs, templates, and checklists you need. Common workflow:

1. **Identify the scenario** in SKILL.md Quick Reference
2. **Read the relevant docs** section for understanding
3. **Copy the appropriate template** and customize
4. **Verify with the checklist** before considering the task complete

## Directory Structure

```
.claude/skills/jetpack-compose-ui/
├── SKILL.md                              # Skill metadata and quick reference (ALWAYS READ FIRST)
├── README.md                             # This file - getting started guide
│
├── docs/                                 # Deep technical knowledge (read for understanding)
│   ├── WINDOWINSETS_GUIDE.md            # WindowInsets, IME, edge-to-edge (~650 lines)
│   ├── MATERIAL3_GUIDE.md               # M3 theming, components, migration (~230 lines)
│   ├── PERFORMANCE_GUIDE.md             # Optimization, stability, profiling (~330 lines)
│   ├── STATE_MANAGEMENT.md              # State hoisting, ViewModels, flow (~200 lines)
│   └── TROUBLESHOOTING.md               # Common issues and solutions (~320 lines)
│
├── templates/                            # Copy-paste production code
│   ├── edge-to-edge-screen.kt           # Complete screen with proper insets
│   ├── ime-handling-form.kt             # Form with BringIntoViewRequester
│   ├── material3-theme.kt               # Full M3 theme setup
│   ├── optimized-lazy-list.kt           # Performance-optimized list
│   ├── bottom-sheet-ime.kt              # Bottom sheet with keyboard fix
│   └── viewmodel-compose.kt             # ViewModel integration pattern
│
├── patterns/                             # Before/after transformation examples
│   ├── windowinsets-pattern.md          # ❌ → ✅ insets handling
│   ├── stability-pattern.md             # ❌ → ✅ performance optimization
│   └── state-hoisting-pattern.md        # ❌ → ✅ state management
│
├── checklists/                           # Verification steps (ALWAYS CHECK BEFORE COMPLETING)
│   ├── android-15-checklist.md          # SDK 35 compliance verification
│   ├── performance-checklist.md         # Performance verification
│   └── new-screen-checklist.md          # Complete screen creation checklist
│
└── assets/                               # Supporting configuration files
    ├── compose-dependencies.gradle       # Recommended Gradle dependencies
    └── baseline-profile-rules.pro        # Performance optimization rules
```

## Usage Examples

### Example 1: Creating a New Screen

**User request**: "Create a new settings screen with edge-to-edge layout"

**Agent workflow**:
1. Read SKILL.md → "I need to create a new screen with proper edge-to-edge"
2. Read `docs/WINDOWINSETS_GUIDE.md` (edge-to-edge section)
3. Copy and customize `templates/edge-to-edge-screen.kt`
4. Verify with `checklists/new-screen-checklist.md`
5. Test on Android 15 device

### Example 2: Fixing Keyboard Issues

**User request**: "The keyboard is covering the text field in my form"

**Agent workflow**:
1. Read SKILL.md → "I need to handle the keyboard (IME padding)"
2. Verify AndroidManifest has `android:windowSoftInputMode="adjustResize"`
3. Read `docs/WINDOWINSETS_GUIDE.md` (IME padding mastery section)
4. Apply `.imePadding()` modifier to scrollable content
5. If complex form, use `templates/ime-handling-form.kt`
6. Test keyboard open/close cycles

### Example 3: Performance Optimization

**User request**: "This list is janky when scrolling"

**Agent workflow**:
1. Read SKILL.md → "UI is slow/janky during scrolling or interaction"
2. Read `docs/PERFORMANCE_GUIDE.md` (stability section)
3. Check pattern: `patterns/stability-pattern.md`
4. Add stable keys to LazyColumn items
5. Generate compiler stability report
6. Use `templates/optimized-lazy-list.kt` as reference
7. Verify with `checklists/performance-checklist.md`

## Key Concepts

### 1. Edge-to-Edge is Mandatory on Android 15

Android 15 (SDK 35) **automatically enforces** edge-to-edge display. Apps **must** handle WindowInsets properly or UI will be obscured by system bars.

**Required setup** (already done in Columba, but verify):
```kotlin
// In MainActivity.onCreate()
enableEdgeToEdge()  // Modern approach (activity-compose 1.8.0+)
```

### 2. The Three Critical IME Padding Steps

**ALL THREE are required** for keyboard padding to work:

1. **Activity**: Call `enableEdgeToEdge()` in MainActivity
2. **Manifest**: Add `android:windowSoftInputMode="adjustResize"`
3. **Compose**: Apply `.imePadding()` modifier to content

Missing **any one** of these causes keyboard padding failures.

### 3. Material 3 Components Auto-Handle Insets

Material 3 components like `TopAppBar` and `NavigationBar` **automatically** apply appropriate insets. Don't add manual padding to these components or you'll get double padding.

### 4. Stability Determines Performance

Compose **automatically skips** recomposing components whose inputs haven't changed, **BUT** only if inputs are **stable**.

- ✅ **Stable**: Primitives (Int, String), immutable data classes, `@Stable` annotated classes
- ❌ **Unstable**: Standard collections (List, Set, Map), mutable properties

**The #1 performance issue**: Unstable parameters preventing smart recomposition.

### 5. Modifier Order Matters

Order of modifiers is **semantic**, not arbitrary:

```kotlin
// ✅ CORRECT order
Modifier
    .padding(innerPadding)           // Apply Scaffold padding first
    .consumeWindowInsets(innerPadding)  // Consume those insets
    .imePadding()                    // Add IME padding

// ❌ WRONG order causes double padding or cut-off content
```

## Integration Points

### With Other Columba Skills

- **kotlin-android-chaquopy-testing**: Use for creating Compose UI tests
  ```kotlin
  // This skill provides the UI patterns
  // Testing skill provides the test patterns
  ```

- **columba-threading-redesign**: Use for proper ViewModel coroutine patterns
  ```kotlin
  // Threading skill: How to structure coroutines
  // UI skill: How to collect flows with lifecycle
  ```

### With MCP Servers

- **context7**: Fetch latest Compose documentation
  ```
  "Get androidx compose material3 latest version documentation"
  ```

- **reticulum-manual**: When UI displays Reticulum network data
  ```
  "How to display LXMF message structure in UI"
  ```

### With Slash Commands

- `/create-compose-screen [ScreenName]`: Scaffolds new screen with this skill's patterns

## Critical Context for 2025

### What's Changed
- ✅ **Accompanist fully deprecated** (August 2023) - use androidx APIs
- ✅ **Android 15 mandatory edge-to-edge** - cannot opt out long-term
- ✅ **Material 3 production-ready** (v1.4.0) - Material 2 is legacy
- ✅ **Compose BOM 2025.10.01** - latest stable versions

### Current Best Practices
- Use `enableEdgeToEdge()` not `WindowCompat.setDecorFitsSystemWindows()`
- Use androidx WindowInsets not Accompanist Insets
- Use Material 3 not Material 2
- Target SDK 35 (Android 15) for new apps

## Performance Targets

| Metric | Target | How to Verify |
|--------|--------|---------------|
| Frame rate | 60 FPS | Layout Inspector recomposition counts |
| Frame time | < 16ms | Systrace during interaction |
| Startup time | < 3s cold start | Macrobenchmark |
| Recompositions | Minimal (smart skipping) | Composition tracing |

## Success Indicators

✅ **Keyboard works everywhere** - No IME padding issues on any device
✅ **Proper edge-to-edge** - Content visible, not obscured by system bars
✅ **Smooth scrolling** - 60 FPS with no dropped frames
✅ **Material 3 compliant** - Using M3 components and theming
✅ **Optimized performance** - Smart recomposition with stable parameters
✅ **Clean state management** - Unidirectional data flow, hoisted state

## Troubleshooting Quick Reference

| Problem | Quick Fix | Detailed Guide |
|---------|-----------|----------------|
| Keyboard covering TextField | Add `.imePadding()` | docs/WINDOWINSETS_GUIDE.md |
| Content behind system bars | Add `.systemBarsPadding()` | docs/WINDOWINSETS_GUIDE.md |
| List scrolling is janky | Add stable keys | docs/PERFORMANCE_GUIDE.md |
| UI recomposes too much | Check stability | patterns/stability-pattern.md |
| Bottom sheet keyboard broken | Set `windowInsets = WindowInsets(0)` | templates/bottom-sheet-ime.kt |
| Double padding visible | Add `.consumeWindowInsets()` | patterns/windowinsets-pattern.md |
| State management unclear | Read state hoisting guide | docs/STATE_MANAGEMENT.md |

## Version Requirements

**Minimum versions** (as of October 2025):
```gradle
androidx.compose:compose-bom:2025.10.01
androidx.compose.material3:material3:1.4.0+ (via BOM)
androidx.activity:activity-compose:1.9.0+
androidx.compose.foundation:foundation:1.2.0+
Kotlin 1.9.0+
Target SDK 35 (Android 15+)
```

**Check** `assets/compose-dependencies.gradle` for complete dependency list.

## For Skill Developers

### Maintaining This Skill

This skill is based on the comprehensive `ui-guide.md` created in October 2025. To update:

1. **Version updates**: Update SKILL.md and MATERIAL3_GUIDE.md with new BOM versions
2. **API changes**: Update docs and templates when Compose APIs change
3. **New patterns**: Add new patterns to `patterns/` directory
4. **Bug fixes**: Update troubleshooting guide with new device-specific issues
5. **Performance**: Update baseline targets as hardware improves

### Adding New Templates

When adding templates:
1. Include "When to use" documentation at the top
2. List prerequisites clearly
3. Provide fully working, commented code
4. Mark customization points with `// TODO:` comments
5. Include testing recommendations

### Testing Changes

Before committing skill updates:
1. Verify all file paths in SKILL.md are correct
2. Test that code templates compile
3. Check that checklists are comprehensive
4. Validate against latest Android documentation
5. Test with Claude Code agent on real tasks

## Getting Help

**For users**: Just ask Claude Code! This skill works automatically.

**For developers maintaining this skill**:
- Review the original ui-guide.md for complete context
- Check official Android documentation at developer.android.com
- Test changes with real Compose development tasks
- Keep dependencies up-to-date with latest stable releases

## License & Attribution

This skill is based on battle-tested production patterns compiled from:
- Official Android Jetpack Compose documentation
- Material Design 3 guidelines
- Real-world production app development experience
- Community best practices and issue resolution

---

**Version**: 1.0.0
**Last Updated**: October 2025
**Maintained for**: Columba Android Project
**Target Platform**: Android 15+ (SDK 35) with Jetpack Compose
