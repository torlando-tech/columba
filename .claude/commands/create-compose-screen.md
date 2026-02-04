---
description: Scaffold a new Jetpack Compose screen with proper edge-to-edge setup, ViewModel, and state management
---

# Create Compose Screen Command

## Instructions

You are helping create a new Jetpack Compose screen with production-ready patterns. Follow these steps:

### Step 1: Gather Information

Ask the user for:
1. **Screen name** (e.g., "UserProfile", "Settings", "ChatDetail")
2. **Screen purpose** (brief description: "Displays user profile information")
3. **Does it need a list?** (Yes/No - for LazyColumn with items)
4. **Does it have TextFields?** (Yes/No - for IME padding)
5. **Navigation source** (e.g., "from HomeScreen", "from bottom nav", "standalone")

### Step 2: Use the Jetpack Compose UI Skill

Activate the `jetpack-compose-ui` skill automatically to access templates and patterns.

### Step 3: Create File Structure

Create these files in the appropriate directory:

```
ui/screens/{screen_name_lowercase}/
├── {ScreenName}Screen.kt      # Main screen composable
├── {ScreenName}ViewModel.kt   # ViewModel with state management
└── {ScreenName}Contract.kt    # UiState, Actions, Events
```

### Step 4: Generate Code from Templates

Use the `edge-to-edge-screen.kt` template from the skill as the base, customizing:

**Screen Composable ({ScreenName}Screen.kt)**:
- Use edge-to-edge pattern from template
- Include TopAppBar with back navigation
- If has list: Use LazyColumn with stable keys
- If has TextFields: Add .imePadding() modifier
- Include loading, error, and success states

**ViewModel ({ScreenName}ViewModel.kt)**:
- Use viewmodel-compose.kt template
- Create UiState data class with relevant fields
- Implement unidirectional data flow (state down, events up)
- Add @HiltViewModel annotation
- Use StateFlow for state, SharedFlow for events

**Contract ({ScreenName}Contract.kt)**:
- Define UiState data class
- Define sealed interface for Actions
- Define sealed interface for Events (navigation, snackbar)

### Step 5: Verify Against Checklist

Use `checklists/new-screen-checklist.md` from the skill to verify:
- [ ] Edge-to-edge compliance (Android 15)
- [ ] IME padding (if has TextFields)
- [ ] Stable keys on list items (if has list)
- [ ] consumeWindowInsets applied
- [ ] collectAsStateWithLifecycle used
- [ ] Unidirectional data flow implemented

### Step 6: Add Navigation Integration

If user specified navigation source, add the route to NavHost:

```kotlin
composable("{screen_route}") {
    {ScreenName}Screen(
        onNavigateBack = { navController.navigateUp() }
    )
}
```

### Step 7: Summary

Provide the user with:
1. List of files created
2. What they need to do next (implement business logic, add to navigation)
3. Testing recommendations
4. Link to relevant checklist: `checklists/new-screen-checklist.md`

## Example Usage

```
User: /create-compose-screen
Agent: I'll help you create a new Compose screen. What's the screen name?
User: UserProfile
Agent: Great! What's the purpose of this screen?
User: Display user profile information with edit button
Agent: Does it need a scrollable list of items?
User: No, just profile info and a form
Agent: Does it have text input fields?
User: Yes, for editing profile
Agent: Where is this screen navigated from?
User: From the Settings screen

[Agent then creates files using templates, verifies against checklist, and provides summary]
```

## Important Reminders

- **Always use the jetpack-compose-ui skill** for accessing templates
- **Always include edge-to-edge setup** (enableEdgeToEdge, proper insets)
- **Always add IME padding** if screen has TextFields
- **Always use stable keys** on LazyColumn items
- **Always implement unidirectional data flow**
- **Always verify against new-screen-checklist.md**

## Notes

This command leverages the comprehensive `jetpack-compose-ui` skill which contains:
- Production-ready templates
- Edge-to-edge patterns
- Performance optimization patterns
- Complete checklists

The generated code will be Android 15+ compliant with proper WindowInsets handling, Material 3 components, and optimized performance.
