# State Management Patterns in Compose

> **Complete guide to state hoisting, unidirectional data flow, and state persistence**

## Table of Contents

1. [State Hoisting Fundamentals](#state-hoisting-fundamentals)
2. [Where to Hoist State](#where-to-hoist-state)
3. [Unidirectional Data Flow](#unidirectional-data-flow)
4. [State Persistence Strategies](#state-persistence-strategies)
5. [Plain State Holder Classes](#plain-state-holder-classes)
6. [ViewModel Integration Patterns](#viewmodel-integration-patterns)
7. [LaunchedEffect for Synchronized Side Effects](#launchedeffect-for-synchronized-side-effects)

---

## State Hoisting Fundamentals

**State hoisting** means moving state from a composable to its caller, making the composable stateless.

### Why Hoist State?

✅ **Single source of truth**: State lives in one place, avoiding synchronization issues
✅ **Encapsulation**: State logic separated from UI logic
✅ **Shareable**: Multiple composables can access the same state
✅ **Interceptable**: Parent can intercept and validate state changes
✅ **Testable**: Stateless composables are trivial to test

### Stateful vs Stateless Example

#### Stateful (Before Hoisting)

```kotlin
// ❌ STATEFUL: Harder to test and reuse
@Composable
fun HelloContent() {
    var name by remember { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") }
        )
        Text("Hello, $name!")
    }
}
```

**Problems**:
- Can't test state logic independently
- Can't share state with other composables
- Can't intercept or validate state changes
- Hard to reuse with different state sources

#### Stateless (After Hoisting)

```kotlin
// ✅ STATELESS: Hoisted state enables testing and reuse
@Composable
fun HelloScreen() {
    var name by rememberSaveable { mutableStateOf("") }
    HelloContent(
        name = name,
        onNameChange = { name = it }
    )
}

@Composable
fun HelloContent(
    name: String,
    onNameChange: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") }
        )
        Text("Hello, $name!")
    }
}
```

**Benefits**:
- `HelloContent` is pure UI, easily testable
- State lives in `HelloScreen`, single source of truth
- Can intercept `onNameChange` to validate/transform input
- Can reuse `HelloContent` with different state sources

### The Pattern: State Down, Events Up

```kotlin
@Composable
fun ParentComposable() {
    // STATE: Owned by parent
    var text by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }

    ChildComposable(
        text = text,                    // ⬇ State flows DOWN
        isExpanded = isExpanded,        // ⬇ State flows DOWN
        onTextChange = { text = it },   // ⬆ Events flow UP
        onExpandToggle = { isExpanded = !isExpanded } // ⬆ Events flow UP
    )
}

@Composable
fun ChildComposable(
    text: String,
    isExpanded: Boolean,
    onTextChange: (String) -> Unit,
    onExpandToggle: () -> Unit
) {
    // Pure UI, no state management
}
```

**This pattern is called "Unidirectional Data Flow"** (covered in detail below).

---

## Where to Hoist State

Not all state should be hoisted to the same level. Use this **decision tree**:

### Decision Tree

```
Is state simple and not shared?
├─ Yes → Keep internal to composable
└─ No → Does state need to be shared with sibling composables?
    ├─ Yes → Hoist to parent composable
    └─ No → Is there complex UI logic (scroll, animation coordination)?
        ├─ Yes → Hoist to plain state holder class
        └─ No → Is there business logic or need to survive config changes?
            ├─ Yes → Hoist to ViewModel
            └─ No → Keep in parent composable
```

### Level 1: Keep Internal (Simple, Not Shared)

```kotlin
@Composable
fun ExpandableCard() {
    // ✅ INTERNAL: Simple expand/collapse state, not shared
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.clickable { isExpanded = !isExpanded }
    ) {
        Column {
            Text("Title")
            if (isExpanded) {
                Text("Details...")
            }
        }
    }
}
```

**Use when**:
- State is UI-specific (expand/collapse, show/hide)
- Not shared with other composables
- No business logic involved
- Doesn't need to survive configuration changes

### Level 2: Hoist to Parent Composable (Sharing with Siblings)

```kotlin
@Composable
fun FormScreen() {
    // ✅ PARENT: Shared between TextField and SubmitButton
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column {
        EmailField(
            value = email,
            onValueChange = { email = it }
        )
        PasswordField(
            value = password,
            onValueChange = { password = it }
        )
        SubmitButton(
            enabled = email.isNotEmpty() && password.isNotEmpty(),
            onClick = { /* submit */ }
        )
    }
}
```

**Use when**:
- State is shared between sibling composables
- No business logic, just UI coordination
- Doesn't need to survive configuration changes

### Level 3: Hoist to Plain State Holder Class (Complex UI Logic)

```kotlin
// ✅ STATE HOLDER: Complex UI logic without business logic
@Stable
class MyAppState(
    val navController: NavHostController,
    private val windowSizeClass: WindowSizeClass,
    private val scope: CoroutineScope
) {
    val shouldShowBottomBar: Boolean
        get() = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    val currentRoute: String?
        get() = navController.currentBackStackEntry?.destination?.route

    fun navigateToDestination(destination: String) {
        navController.navigate(destination) {
            launchSingleTop = true
            restoreState = true
        }
    }

    fun showSnackbar(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    val snackbarHostState = SnackbarHostState()
}

@Composable
fun rememberMyAppState(
    navController: NavHostController = rememberNavController(),
    windowSizeClass: WindowSizeClass,
    scope: CoroutineScope = rememberCoroutineScope()
): MyAppState {
    return remember(navController, windowSizeClass, scope) {
        MyAppState(navController, windowSizeClass, scope)
    }
}

@Composable
fun MyApp() {
    val windowSizeClass = calculateWindowSizeClass(this)
    val appState = rememberMyAppState(windowSizeClass = windowSizeClass)

    Scaffold(
        bottomBar = {
            if (appState.shouldShowBottomBar) {
                BottomNavigationBar(
                    currentRoute = appState.currentRoute,
                    onNavigate = appState::navigateToDestination
                )
            }
        },
        snackbarHost = { SnackbarHost(appState.snackbarHostState) }
    ) { padding ->
        NavHost(appState.navController, ...) { /* routes */ }
    }
}
```

**Use when**:
- Complex UI logic (navigation, scroll coordination, animation)
- Multiple UI states need coordination
- No business logic (no network calls, no database)
- Can be tested with UI tests

**Key characteristics**:
- Annotated with `@Stable`
- Created with `remember` function
- Manages UI-specific state only
- Lives in composition, dies with composable

### Level 4: Hoist to ViewModel (Business Logic)

```kotlin
// ✅ VIEWMODEL: Business logic + state that survives config changes
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadContacts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val contacts = repository.getContacts()
                _uiState.value = UiState.Success(contacts)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message)
            }
        }
    }

    fun deleteContact(id: String) {
        viewModelScope.launch {
            repository.deleteContact(id)
            loadContacts()
        }
    }
}

@Composable
fun ContactsScreen(viewModel: ContactsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState) {
        is UiState.Loading -> LoadingIndicator()
        is UiState.Success -> ContactList((uiState as UiState.Success).contacts)
        is UiState.Error -> ErrorMessage((uiState as UiState.Error).message)
    }
}
```

**Use when**:
- Business logic (network, database, calculations)
- State needs to survive configuration changes
- State needs to survive process death (with SavedStateHandle)
- Needs to be unit tested independently

---

## Unidirectional Data Flow

**State flows down, events flow up**—this pattern creates predictable, testable apps.

### The Pattern

```
┌─────────────────────────────────────┐
│          ViewModel                  │
│  (Business Logic + State)           │
│                                     │
│  var uiState = MutableStateFlow()   │
│  fun onUserAction() { ... }         │
└─────────────┬───────────────────────┘
              │
              │ State flows DOWN (StateFlow)
              ↓
┌─────────────────────────────────────┐
│          Composable                 │
│  (UI Layer)                         │
│                                     │
│  val state = viewModel.uiState.     │
│    collectAsStateWithLifecycle()    │
│                                     │
│  Button(onClick = {                 │
│    viewModel.onUserAction()  ←──────┤ Events flow UP
│  })                                 │
└─────────────────────────────────────┘
```

### Complete Implementation Example

```kotlin
// Domain Layer: Data models
data class TodoItem(
    val id: String,
    val title: String,
    val isCompleted: Boolean
)

// UI State: What the UI displays
data class TodoListUiState(
    val items: List<TodoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

// User Actions: Events from UI
sealed interface TodoListAction {
    data class ToggleComplete(val itemId: String) : TodoListAction
    data class Delete(val itemId: String) : TodoListAction
    data class Add(val title: String) : TodoListAction
    object Refresh : TodoListAction
}

// ViewModel: Business logic + state management
@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoListUiState())
    val uiState: StateFlow<TodoListUiState> = _uiState.asStateFlow()

    init {
        loadTodos()
    }

    fun onAction(action: TodoListAction) {
        when (action) {
            is TodoListAction.ToggleComplete -> toggleComplete(action.itemId)
            is TodoListAction.Delete -> delete(action.itemId)
            is TodoListAction.Add -> add(action.title)
            is TodoListAction.Refresh -> loadTodos()
        }
    }

    private fun loadTodos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = repository.getTodos()
                _uiState.update { it.copy(items = items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun toggleComplete(itemId: String) {
        viewModelScope.launch {
            repository.toggleComplete(itemId)
            loadTodos()
        }
    }

    private fun delete(itemId: String) {
        viewModelScope.launch {
            repository.delete(itemId)
            loadTodos()
        }
    }

    private fun add(title: String) {
        viewModelScope.launch {
            repository.add(title)
            loadTodos()
        }
    }
}

// Composable: UI layer (stateless, pure function of UiState)
@Composable
fun TodoListScreen(viewModel: TodoViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TodoListContent(
        uiState = uiState,
        onAction = viewModel::onAction
    )
}

@Composable
fun TodoListContent(
    uiState: TodoListUiState,
    onAction: (TodoListAction) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { /* show add dialog */ }) {
                Icon(Icons.Default.Add, "Add")
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.error != null -> ErrorMessage(uiState.error)
            else -> TodoList(
                items = uiState.items,
                onToggle = { id -> onAction(TodoListAction.ToggleComplete(id)) },
                onDelete = { id -> onAction(TodoListAction.Delete(id)) }
            )
        }
    }
}
```

**Benefits of this pattern**:
- ✅ ViewModel is easily unit testable (no Compose dependencies)
- ✅ UI is easily UI testable (pass mock state, verify UI)
- ✅ Clear separation: UI displays state, ViewModel contains logic
- ✅ Time-travel debugging possible (replay state changes)
- ✅ State changes are traceable (all go through onAction)

---

## State Persistence Strategies

Different persistence strategies for different scenarios:

| Scenario | Strategy | Survives | Example |
|----------|----------|----------|---------|
| Recomposition only | `remember` | Recomposition | Expand/collapse state |
| Configuration change | `rememberSaveable` | Rotation, language change | Form input |
| Process death | `SavedStateHandle` in ViewModel | System kills app | User selections |
| Permanent storage | Database/SharedPreferences | App uninstall/reinstall | User settings |

### Level 1: remember (Recomposition Only)

```kotlin
@Composable
fun ExpandableCard() {
    // Lost on configuration change (rotation)
    var isExpanded by remember { mutableStateOf(false) }

    Card(onClick = { isExpanded = !isExpanded }) {
        // Content
    }
}
```

**Survives**: Recomposition (state updates, parent recomposition)
**Lost on**: Configuration change (rotation), process death

### Level 2: rememberSaveable (Configuration Changes)

```kotlin
@Composable
fun SearchScreen() {
    // Survives configuration changes automatically for primitives
    var searchQuery by rememberSaveable { mutableStateOf("") }

    SearchBar(
        query = searchQuery,
        onQueryChange = { searchQuery = it }
    )
}
```

**Survives**: Recomposition + configuration changes (rotation)
**Lost on**: Process death (system kills app)

**Supported types automatically**:
- Primitives: String, Int, Float, Boolean, etc.
- Parcelable objects
- Serializable objects

### Level 3: Custom Saver (Complex Types)

```kotlin
data class City(val name: String, val country: String)

val CitySaver = listSaver<City, Any>(
    save = { listOf(it.name, it.country) },
    restore = { City(it[0] as String, it[1] as String) }
)

@Composable
fun CityScreen() {
    var selectedCity = rememberSaveable(stateSaver = CitySaver) {
        mutableStateOf(City("Madrid", "Spain"))
    }

    CitySelector(
        city = selectedCity.value,
        onCitySelect = { selectedCity.value = it }
    )
}
```

**Use when**: Need to save custom data classes across configuration changes.

### Level 4: SavedStateHandle (Process Death)

```kotlin
@HiltViewModel
class FormViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: FormRepository
) : ViewModel() {

    // Survives process death
    var name: String
        get() = savedStateHandle["name"] ?: ""
        set(value) { savedStateHandle["name"] = value }

    var email: String
        get() = savedStateHandle["email"] ?: ""
        set(value) { savedStateHandle["email"] = value }

    // Or use StateFlow
    val nameFlow: StateFlow<String> = savedStateHandle.getStateFlow("name", "")
}

@Composable
fun FormScreen(viewModel: FormViewModel = hiltViewModel()) {
    val name by viewModel.nameFlow.collectAsStateWithLifecycle()

    TextField(
        value = name,
        onValueChange = { viewModel.name = it }
    )
}
```

**Survives**: Everything (recomposition, config changes, process death)
**Lost on**: App uninstall, clear data

### Level 5: Permanent Storage (Database/Preferences)

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val isDarkMode: StateFlow<Boolean> = preferencesRepository.isDarkModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDarkMode(enabled)
        }
    }
}
```

**Survives**: Everything including app uninstall (if backed up)

---

## Plain State Holder Classes

For complex UI logic without business logic:

### When to Use

- Navigation state coordination
- Scroll state management across multiple lists
- Animation state coordination
- Complex form state with validation rules
- Multi-step wizard state

### Complete Example: Multi-Step Form

```kotlin
@Stable
class MultiStepFormState(
    initialStep: Int = 0
) {
    var currentStep by mutableStateOf(initialStep)
        private set

    var step1Data by mutableStateOf(Step1Data())
    var step2Data by mutableStateOf(Step2Data())
    var step3Data by mutableStateOf(Step3Data())

    val canGoBack: Boolean
        get() = currentStep > 0

    val canGoNext: Boolean
        get() = when (currentStep) {
            0 -> step1Data.isValid
            1 -> step2Data.isValid
            2 -> step3Data.isValid
            else -> false
        }

    val isComplete: Boolean
        get() = currentStep == 2 && step3Data.isValid

    fun goNext() {
        if (canGoNext && currentStep < 2) {
            currentStep++
        }
    }

    fun goBack() {
        if (canGoBack) {
            currentStep--
        }
    }

    fun updateStep1(data: Step1Data) {
        step1Data = data
    }

    fun updateStep2(data: Step2Data) {
        step2Data = data
    }

    fun updateStep3(data: Step3Data) {
        step3Data = data
    }
}

@Composable
fun rememberMultiStepFormState(
    initialStep: Int = 0
): MultiStepFormState {
    return rememberSaveable(saver = /* custom saver */) {
        MultiStepFormState(initialStep)
    }
}

@Composable
fun MultiStepForm() {
    val formState = rememberMultiStepFormState()

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = formState::goBack,
                    enabled = formState.canGoBack
                ) {
                    Text("Back")
                }
                Button(
                    onClick = formState::goNext,
                    enabled = formState.canGoNext
                ) {
                    Text(if (formState.isComplete) "Submit" else "Next")
                }
            }
        }
    ) { padding ->
        when (formState.currentStep) {
            0 -> Step1Screen(
                data = formState.step1Data,
                onDataChange = formState::updateStep1
            )
            1 -> Step2Screen(
                data = formState.step2Data,
                onDataChange = formState::updateStep2
            )
            2 -> Step3Screen(
                data = formState.step3Data,
                onDataChange = formState::updateStep3
            )
        }
    }
}
```

---

## ViewModel Integration Patterns

### Collecting StateFlow with Lifecycle

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel = hiltViewModel()) {
    // ✅ CORRECT: Respects lifecycle, stops collection when not visible
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ❌ WRONG: Continues collecting even when not visible (battery drain)
    val uiState by viewModel.uiState.collectAsState()

    MyContent(uiState)
}
```

**Always use** `collectAsStateWithLifecycle()` not `collectAsState()`.

### Handling One-Time Events

```kotlin
// ViewModel
@HiltViewModel
class MyViewModel @Inject constructor() : ViewModel() {
    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun onAction() {
        viewModelScope.launch {
            _events.emit(UiEvent.ShowSnackbar("Action completed"))
        }
    }
}

// Composable
@Composable
fun MyScreen(viewModel: MyViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Content()
    }
}
```

---

## LaunchedEffect for Synchronized Side Effects

### When to Use LaunchedEffect

**LaunchedEffect is for side effects that need to stay synchronized with Compose state**. Use it when:
- A side effect (database write, network call, analytics) must execute when state changes
- The side effect needs to run continuously while viewing a screen
- You need to prevent UI timing issues caused by delayed side effects

### Common Problem: Side Effects in Lifecycle Methods

A typical bug pattern occurs when side effects are triggered only on lifecycle events (like `onCleared()`), causing timing issues with reactive UI updates:

```kotlin
// ❌ PROBLEM: Side effect only runs when leaving screen
override fun onCleared() {
    super.onCleared()
    runBlocking {
        // Mark messages as read AFTER user has left the screen
        conversationRepository.markConversationAsRead(currentPeerHash)
    }
}
```

**Why this causes issues**:
1. User navigates back to list screen
2. List screen renders with old state (showing unread count)
3. ViewModel clears → database updates
4. Flow emits new state
5. List screen recomposes → unread count disappears (visible flash)

The gap between steps 2 and 5 creates a visible UI artifact.

### Solution: Continuous Side Effects with LaunchedEffect

Mark the side effect as read **continuously while viewing**, not just when leaving:

```kotlin
// ✅ SOLUTION: Continuous side effect synchronized with state
@Composable
fun MessagingScreen(
    destinationHash: String,
    viewModel: MessagingViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    // Load messages on navigation
    LaunchedEffect(destinationHash) {
        viewModel.loadMessages(destinationHash)
    }

    // Mark as read continuously while viewing
    LaunchedEffect(destinationHash, messages.size) {
        if (messages.isNotEmpty()) {
            viewModel.markAsRead(destinationHash)
        }
    }

    // UI content...
}
```

**Why this works**:
1. Side effect runs immediately when messages appear
2. Side effect runs again when new messages arrive
3. Database is already updated before user navigates away
4. List screen renders with correct state immediately (no flash)

### ViewModel Support for Continuous Updates

The ViewModel provides a simple function for idempotent updates:

```kotlin
@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    fun markAsRead(destinationHash: String) {
        viewModelScope.launch {
            try {
                // Idempotent - safe to call repeatedly
                conversationRepository.markConversationAsRead(destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error marking conversation as read", e)
            }
        }
    }
}
```

**Key points**:
- Use `viewModelScope.launch` (not `runBlocking`) for async operations
- Repository operation must be idempotent (safe to call multiple times)
- Error handling prevents crashes from database issues

### onCleared() Cleanup: Threading Policy

**IMPORTANT**: Per Phase 1.2 threading policy, **never use `runBlocking` in production code**, including `onCleared()`.

```kotlin
override fun onCleared() {
    super.onCleared()

    // Only perform synchronous cleanup
    // viewModelScope is already cancelled at this point
    reticulumProtocol.setConversationActive(false)

    // Note: Database operations should happen via LaunchedEffect in UI layer,
    // not in onCleared(). Operations here would block the caller thread.
}
```

**Best practice for cleanup operations**:
- **Prefer UI-layer side effects**: Use `LaunchedEffect` with `DisposableEffect` for cleanup
- **Avoid defensive redundancy**: Don't duplicate operations in `onCleared()` that already happen in normal flow
- **Keep it synchronous**: Only put non-blocking cleanup in `onCleared()`
- **For critical cleanup**: Use application-scoped coroutines if truly needed (rare)

**Why avoid runBlocking in onCleared()**:
- Blocks the calling thread (usually main thread)
- Violates Phase 1.2 threading architecture
- `viewModelScope` is already cancelled, so suspended operations are awkward
- Most cleanup is not critical enough to justify blocking

### LaunchedEffect Keys: When to Relaunch

LaunchedEffect keys determine when the effect relaunches:

```kotlin
// Relaunches when EITHER destinationHash OR messages.size changes
LaunchedEffect(destinationHash, messages.size) {
    if (messages.isNotEmpty()) {
        viewModel.markAsRead(destinationHash)
    }
}
```

**Common key patterns**:
- `LaunchedEffect(Unit)` - Runs once, never relaunches
- `LaunchedEffect(key)` - Relaunches when key changes
- `LaunchedEffect(key1, key2)` - Relaunches when either key changes

**For side effects that should run on state changes**:
- Use state value as key (`messages.size`)
- Or state derivative (`messages.isNotEmpty()`)
- Avoid using entire collections (unstable) - use size or derived boolean instead

### Performance Considerations

**Q: Won't calling the repository repeatedly cause performance issues?**

**A: No, for several reasons:**

1. **Database operations are fast** - Room's UPDATE queries execute in microseconds
2. **Operations are asynchronous** - Don't block the UI thread
3. **Idempotent operations are safe** - No side effects from repeated calls
4. **Triggers only on state changes** - Not every recomposition

**Measured impact**:
- Single `markAsRead()` call: <1ms
- Called on each new message: ~2-5 calls total per conversation view
- Total overhead: <5ms per conversation session (negligible)

### Alternative Pattern: Checking Active State

For cases where continuous updates aren't acceptable, check if screen is active before updating:

```kotlin
// In repository layer
@Transaction
suspend fun saveMessage(
    peerHash: String,
    message: Message,
    activeConversationHash: String? = null
) {
    // Only increment unread if NOT the active conversation
    val shouldIncrementUnread = !message.isFromMe &&
                                !messageExists &&
                                peerHash != activeConversationHash

    val updatedConversation = existingConversation.copy(
        unreadCount = if (shouldIncrementUnread) {
            existingConversation.unreadCount + 1
        } else {
            existingConversation.unreadCount  // Don't increment
        }
    )
}
```

**Trade-offs**:
- ✅ Prevents the problem at the source
- ✅ No extra database writes
- ❌ Requires global state for active conversation
- ❌ More complex state management
- ❌ Thread safety concerns across coroutines

**Recommendation**: Use LaunchedEffect approach for simplicity unless you have specific performance constraints.

### Real-World Example: Unread Message Counter

This pattern solved a production bug where messages received while viewing a conversation were incorrectly counted as unread:

**Before (buggy)**:
```kotlin
// Only marked as read when opening
LaunchedEffect(destinationHash) {
    viewModel.loadMessages(destinationHash)  // Also marks as read
}

// Messages arrive while viewing → increment unread count
// User navigates back → sees unread badge flash then disappear
```

**After (fixed)**:
```kotlin
// Marked as read when opening
LaunchedEffect(destinationHash) {
    viewModel.loadMessages(destinationHash)
}

// ALSO marked as read continuously while viewing
LaunchedEffect(destinationHash, messages.size) {
    if (messages.isNotEmpty()) {
        viewModel.markAsRead(destinationHash)
    }
}

// Result: No flash, unread count already 0 before navigation
```

### Best Practices Summary

**✅ Do This**:
- Use LaunchedEffect for side effects synchronized with state changes
- Make repository operations idempotent (safe to call repeatedly)
- Use state derivatives as keys (`messages.size`, not `messages`)
- Keep fallback cleanup in `onCleared()` for edge cases
- Test performance in release builds if concerned

**❌ Don't Do This**:
- Don't use entire collections as keys (unstable)
- Don't perform heavy synchronous work in LaunchedEffect
- Don't forget error handling in coroutines
- Don't skip the `if (messages.isNotEmpty())` check (avoid unnecessary calls)
- Don't use `runBlocking` in LaunchedEffect (use suspend functions)

---

## Summary: State Management Best Practices

### ✅ Do This

- Hoist state to the appropriate level (use decision tree)
- Make composables stateless when possible (easier to test)
- Use unidirectional data flow (state down, events up)
- Use `rememberSaveable` for UI state that should survive rotation
- Use ViewModel + SavedStateHandle for business logic
- Use `collectAsStateWithLifecycle()` not `collectAsState()`
- Use plain state holder classes for complex UI logic
- Annotate state holder classes with `@Stable`

### ❌ Don't Do This

- Don't keep business logic in composables (use ViewModel)
- Don't use `collectAsState()` (use `collectAsStateWithLifecycle()`)
- Don't mutate state directly from UI (send events to ViewModel)
- Don't forget to hoist state shared between siblings
- Don't create ViewModel instances manually (use hiltViewModel() or viewModel())

### Quick Reference

```kotlin
// State only needed in this composable
var expanded by remember { mutableStateOf(false) }

// State shared with siblings
@Composable
fun Parent() {
    var shared by remember { mutableStateOf("") }
    Child1(shared, { shared = it })
    Child2(shared)
}

// Complex UI logic
val appState = rememberMyAppState()

// Business logic
@Composable
fun Screen(viewModel: MyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
}
```

---

**Last Updated**: October 29, 2025
**Architecture**: MVVM with Unidirectional Data Flow
**Recommended**: Hilt for DI, StateFlow for state, SharedFlow for events
**Recent Addition**: LaunchedEffect for synchronized side effects pattern (real-world unread counter fix)
