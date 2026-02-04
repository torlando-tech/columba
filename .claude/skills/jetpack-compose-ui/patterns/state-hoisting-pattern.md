# State Hoisting Pattern Transformations

## Pattern 1: Stateful to Stateless Composable

### ❌ Before (Stateful - Hard to Test/Reuse)

```kotlin
@Composable
fun SearchBar() {
    var query by remember { mutableStateOf("") }
    var isActive by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search...") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, "Clear")
                }
            }
        }
    )
}
```

**Problems**:
- Can't test state logic independently
- Can't share state with parent or siblings
- Can't intercept or validate state changes
- Hard to reuse with different state sources

### ✅ After (Stateless - Easy to Test/Reuse)

```kotlin
// Parent manages state
@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { searchResults(query) }

    SearchBar(
        query = query,
        onQueryChange = { query = it },
        onClear = { query = "" }
    )

    SearchResults(results)
}

// Child is pure UI
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search...") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, "Clear")
                }
            }
        }
    )
}
```

**Benefits**:
- `SearchBar` is easily testable (pass mock state)
- State is shared with `SearchResults`
- Parent can validate/transform input
- Can reuse `SearchBar` with ViewModel state

---

## Pattern 2: State in Composable to ViewModel

### ❌ Before (Business Logic in Composable)

```kotlin
@Composable
fun TodoScreen() {
    var todos by remember { mutableStateOf(listOf<Todo>()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        todos = fetchTodosFromApi() // ❌ Network call in composable!
        isLoading = false
    }

    if (isLoading) {
        CircularProgressIndicator()
    } else {
        TodoList(todos)
    }
}
```

**Problems**:
- Business logic in UI layer
- Can't unit test independently
- State lost on configuration change
- No separation of concerns

### ✅ After (Business Logic in ViewModel)

```kotlin
// ViewModel
@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState())
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    init {
        loadTodos()
    }

    private fun loadTodos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val todos = repository.getTodos()
                _uiState.update { it.copy(todos = todos, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }
}

// Composable
@Composable
fun TodoScreen(viewModel: TodoViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> CircularProgressIndicator()
        uiState.error != null -> ErrorMessage(uiState.error)
        else -> TodoList(uiState.todos)
    }
}
```

**Benefits**:
- Clean separation: ViewModel = business logic, Composable = UI
- Easily unit testable
- State survives configuration changes
- Proper error handling

---

## Pattern 3: Unidirectional Data Flow

### ❌ Before (Bidirectional, Hard to Debug)

```kotlin
@Composable
fun CounterScreen(viewModel: CounterViewModel) {
    val count by viewModel.count.collectAsState()

    Column {
        Text("Count: $count")
        Button(onClick = {
            viewModel.count.value++ // ❌ Direct state mutation
        }) {
            Text("Increment")
        }
    }
}
```

**Problems**:
- State changes are scattered
- Hard to debug (who changed what?)
- No clear event flow
- Can't log/intercept changes

### ✅ After (Unidirectional, Easy to Debug)

```kotlin
// ViewModel
@HiltViewModel
class CounterViewModel @Inject constructor() : ViewModel() {

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun onAction(action: CounterAction) {
        when (action) {
            CounterAction.Increment -> _count.value++
            CounterAction.Decrement -> _count.value--
            CounterAction.Reset -> _count.value = 0
        }
    }
}

sealed interface CounterAction {
    data object Increment : CounterAction
    data object Decrement : CounterAction
    data object Reset : CounterAction
}

// Composable
@Composable
fun CounterScreen(viewModel: CounterViewModel = hiltViewModel()) {
    val count by viewModel.count.collectAsStateWithLifecycle()

    Column {
        Text("Count: $count")
        Button(onClick = { viewModel.onAction(CounterAction.Increment) }) {
            Text("Increment")
        }
    }
}
```

**Benefits**:
- Clear event flow: UI → Action → ViewModel → State → UI
- Easy to log all actions
- Easy to debug state changes
- Single entry point for state mutations

---

## Pattern 4: Complex UI State to State Holder Class

### ❌ Before (Scattered UI Logic)

```kotlin
@Composable
fun WizardScreen() {
    var currentStep by remember { mutableStateOf(0) }
    var step1Data by remember { mutableStateOf(Step1Data()) }
    var step2Data by remember { mutableStateOf(Step2Data()) }
    var step3Data by remember { mutableStateOf(Step3Data()) }

    val canGoBack = currentStep > 0
    val canGoNext = when (currentStep) {
        0 -> step1Data.isValid
        1 -> step2Data.isValid
        2 -> step3Data.isValid
        else -> false
    }

    // Complex UI logic scattered throughout composable...
}
```

**Problems**:
- Complex UI logic mixed with UI composition
- Hard to test
- Hard to reuse
- Difficult to maintain

### ✅ After (State Holder Class)

```kotlin
// State Holder
@Stable
class WizardState(initialStep: Int = 0) {
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

    fun goNext() {
        if (canGoNext && currentStep < 2) currentStep++
    }

    fun goBack() {
        if (canGoBack) currentStep--
    }
}

@Composable
fun rememberWizardState(initialStep: Int = 0): WizardState {
    return remember { WizardState(initialStep) }
}

// Composable (much cleaner)
@Composable
fun WizardScreen() {
    val wizardState = rememberWizardState()

    // Simple, focused on UI composition
    Column {
        when (wizardState.currentStep) {
            0 -> Step1(wizardState.step1Data) { wizardState.step1Data = it }
            1 -> Step2(wizardState.step2Data) { wizardState.step2Data = it }
            2 -> Step3(wizardState.step3Data) { wizardState.step3Data = it }
        }

        Row {
            Button(
                onClick = wizardState::goBack,
                enabled = wizardState.canGoBack
            ) { Text("Back") }

            Button(
                onClick = wizardState::goNext,
                enabled = wizardState.canGoNext
            ) { Text("Next") }
        }
    }
}
```

**Benefits**:
- Clean separation of UI logic and composition
- Testable independently
- Reusable across screens
- Easy to maintain

---

## Decision Tree: Where to Hoist State?

```
Is state simple and not shared?
└─ Yes → Keep internal to composable (remember)

Does state need to be shared with sibling composables?
└─ Yes → Hoist to parent composable

Is there complex UI logic (navigation, scroll coordination)?
└─ Yes → Hoist to plain state holder class (@Stable)

Is there business logic (network, database)?
└─ Yes → Hoist to ViewModel

Does state need to survive configuration changes?
└─ Yes → Use rememberSaveable or ViewModel

Does state need to survive process death?
└─ Yes → Use ViewModel + SavedStateHandle
```

---

## Quick Reference

| Scenario | Solution |
|----------|----------|
| Simple expand/collapse | Keep internal with `remember` |
| Share between siblings | Hoist to parent composable |
| Complex UI logic | Plain state holder class |
| Business logic | ViewModel |
| Survive rotation | `rememberSaveable` or ViewModel |
| Survive process death | ViewModel + `SavedStateHandle` |

## Key Principles

1. **State down, events up** (unidirectional data flow)
2. **Composables should be functions of their parameters**
3. **Hoist state to the lowest common ancestor**
4. **Separate UI state from UI element state**
5. **Keep business logic in ViewModels, UI logic in state holders**
