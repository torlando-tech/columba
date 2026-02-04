# New Compose Screen Creation Checklist

## Overview

Complete checklist for creating a new production-ready Compose screen with proper edge-to-edge, state management, and performance optimization.

---

## 1. Project Setup

### Dependencies

- [ ] Compose BOM **2025.10.01** (or later)
- [ ] Material 3 (via BOM)
- [ ] androidx.activity:activity-compose **1.9.0+**
- [ ] Hilt for dependency injection (if using ViewModels)
- [ ] Navigation Compose (if using navigation)

### Build Configuration

- [ ] `enableEdgeToEdge()` called in MainActivity
- [ ] `android:windowSoftInputMode="adjustResize"` in manifest
- [ ] Target SDK **35** (Android 15+)

---

## 2. File Structure

### Create Files

```
ui/screens/yourscreen/
├── YourScreen.kt           # Screen composable
├── YourScreenContent.kt    # Content composable (optional, can be in same file)
├── YourViewModel.kt        # ViewModel
└── YourScreenContract.kt   # UiState, Actions, Events (optional)
```

- [ ] Screen composable file created
- [ ] ViewModel file created
- [ ] Contract file created (or state defined in ViewModel)

---

## 3. ViewModel Implementation

### State Management

```kotlin
@HiltViewModel
class YourViewModel @Inject constructor(
    private val repository: YourRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(YourUiState())
    val uiState: StateFlow<YourUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<YourEvent>()
    val events: SharedFlow<YourEvent> = _events.asSharedFlow()

    fun onAction(action: YourAction) {
        when (action) {
            // Handle actions
        }
    }
}
```

- [ ] ViewModel created with `@HiltViewModel`
- [ ] `StateFlow` for UI state (not `MutableState`)
- [ ] `SharedFlow` for one-time events (navigation, snackbar)
- [ ] Single `onAction()` entry point for user actions
- [ ] Unidirectional data flow implemented

### UiState Design

```kotlin
data class YourUiState(
    val data: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

- [ ] UiState is immutable data class
- [ ] Contains all data needed to render UI
- [ ] Has loading, error, and success states
- [ ] Uses immutable collections where possible

---

## 4. Screen Composable

### Entry Point

```kotlin
@Composable
fun YourScreen(
    onNavigateBack: () -> Unit,
    viewModel: YourViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Event handling
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Handle events
            }
        }
    }

    YourScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onAction = viewModel::onAction
    )
}
```

- [ ] Screen function created
- [ ] Using `hiltViewModel()` for ViewModel
- [ ] Using `collectAsStateWithLifecycle()` for state
- [ ] `LaunchedEffect` for event handling
- [ ] Stateless content composable called

---

## 5. Content Composable (Edge-to-Edge)

### Scaffold Setup

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourScreenContent(
    uiState: YourUiState,
    onNavigateBack: () -> Unit,
    onAction: (YourAction) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Title") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(Modifier.padding(innerPadding))
            uiState.error != null -> ErrorState(uiState.error, Modifier.padding(innerPadding))
            else -> SuccessState(uiState.data, innerPadding, onAction)
        }
    }
}
```

- [ ] Using `Scaffold` for top-level structure
- [ ] TopAppBar configured (auto-handles status bar)
- [ ] Navigation icon configured
- [ ] Loading, error, and success states handled
- [ ] `innerPadding` parameter used

---

## 6. Content Implementation

### LazyColumn Pattern

```kotlin
@Composable
fun SuccessState(
    data: List<Item>,
    contentPadding: PaddingValues,
    onAction: (YourAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding) // ✅ CRITICAL
            .imePadding(), // ✅ If using TextFields
        contentPadding = contentPadding
    ) {
        items(
            items = data,
            key = { it.id } // ✅ CRITICAL for performance
        ) { item ->
            ItemRow(item, onAction)
        }
    }
}
```

- [ ] `.consumeWindowInsets(contentPadding)` applied
- [ ] `.imePadding()` applied (if using TextFields)
- [ ] `contentPadding` passed to LazyColumn
- [ ] **Stable, unique keys** on all items
- [ ] Item composables have stable parameters

---

## 7. Android 15 Compliance

### Edge-to-Edge Checklist

- [ ] `enableEdgeToEdge()` called in Activity
- [ ] TopAppBar handles status bar (automatic with M3)
- [ ] Content uses `innerPadding` from Scaffold
- [ ] IME padding applied if using TextFields
- [ ] Tested on Android 15+ device
- [ ] No content obscured by system bars
- [ ] Gesture navigation works correctly

---

## 8. Performance Optimization

### Stability

```kotlin
// ✅ Use ImmutableList for parameters
@Composable
fun ItemList(items: ImmutableList<Item>) { }

// In ViewModel
val items: StateFlow<ImmutableList<Item>> = repository.itemsFlow
    .map { it.toImmutableList() }
    .stateIn(...)
```

- [ ] Collection parameters use `ImmutableList`
- [ ] Data classes use `val` not `var`
- [ ] Expensive calculations wrapped in `remember`
- [ ] LazyColumn items have stable keys
- [ ] Compiler reports show "restartable skippable"

### Verification

- [ ] Tested in **release build**
- [ ] 60 FPS during scrolling
- [ ] Layout Inspector shows low recomposition counts
- [ ] No jank or dropped frames

---

## 9. State Management Verification

### Unidirectional Flow

```
User Action → onAction(Action) → ViewModel → uiState → UI
```

- [ ] State flows down (via StateFlow)
- [ ] Events flow up (via onAction)
- [ ] No direct state mutation from UI
- [ ] All state changes go through ViewModel

---

## 10. Navigation Integration

### Compose Navigation

```kotlin
// In NavHost
composable("your_screen") {
    YourScreen(
        onNavigateBack = { navController.navigateUp() }
    )
}

// To navigate to this screen
navController.navigate("your_screen")
```

- [ ] Route added to NavHost
- [ ] Navigation callbacks implemented
- [ ] Back navigation works correctly
- [ ] Deep links configured (if needed)

---

## 11. Testing

### Unit Tests (ViewModel)

```kotlin
@Test
fun `onAction LoadData updates state correctly`() {
    // Given
    val viewModel = YourViewModel(fakeRepository)

    // When
    viewModel.onAction(YourAction.LoadData)

    // Then
    assertEquals(expectedState, viewModel.uiState.value)
}
```

- [ ] ViewModel unit tests created
- [ ] All actions tested
- [ ] State transitions tested
- [ ] Error handling tested

### UI Tests (Optional)

```kotlin
@Test
fun displaysList() {
    composeTestRule.setContent {
        YourScreenContent(
            uiState = YourUiState(data = testData),
            onNavigateBack = {},
            onAction = {}
        )
    }

    composeTestRule.onNodeWithText("Expected Text").assertIsDisplayed()
}
```

- [ ] UI tests created (if critical screen)
- [ ] Happy path tested
- [ ] Error state tested
- [ ] Loading state tested

---

## 12. Documentation

### Code Comments

- [ ] Complex logic documented
- [ ] TODOs added for future improvements
- [ ] Edge cases explained

### README Update (if applicable)

- [ ] New screen documented in project README
- [ ] Navigation flow updated
- [ ] Screenshots added (if design-critical)

---

## 13. Code Review Checklist

### Before Submitting PR

- [ ] No hardcoded strings (use string resources)
- [ ] No magic numbers (use constants or dimension resources)
- [ ] No TODOs that should be fixed now
- [ ] No commented-out code
- [ ] No debug logs left in
- [ ] Proper error handling
- [ ] Accessibility content descriptions added

### Accessibility

- [ ] All icons have `contentDescription`
- [ ] Interactive elements have proper semantics
- [ ] Tested with TalkBack (if critical)

---

## 14. Final Verification

### Manual Testing

On Android 15+ device:
- [ ] Portrait mode works correctly
- [ ] Landscape mode works correctly
- [ ] Dark mode looks good
- [ ] Light mode looks good
- [ ] Keyboard handling correct (if using TextFields)
- [ ] No content obscured by system bars
- [ ] Navigation works correctly
- [ ] Loading states display correctly
- [ ] Error states display correctly
- [ ] Pull-to-refresh works (if applicable)

### Performance Testing

- [ ] Smooth 60 FPS scrolling
- [ ] No ANR (Application Not Responding)
- [ ] Quick screen load time
- [ ] No memory leaks

---

## Template Checklist Summary

Quick overview of critical items:

**Must Have**:
- [ ] ViewModel with StateFlow
- [ ] Unidirectional data flow
- [ ] Scaffold with TopAppBar
- [ ] consumeWindowInsets + imePadding
- [ ] LazyColumn with stable keys
- [ ] collectAsStateWithLifecycle
- [ ] ImmutableList for collections
- [ ] Tested on Android 15+
- [ ] 60 FPS performance verified

**Good to Have**:
- [ ] Unit tests
- [ ] UI tests
- [ ] Proper documentation
- [ ] Accessibility annotations
- [ ] Error state handling
- [ ] Loading state handling
- [ ] Pull-to-refresh (if applicable)

---

## Final Sign-Off

- [ ] All critical items checked
- [ ] Android 15 compliance verified
- [ ] Performance targets met
- [ ] Tests passing
- [ ] Ready for code review
