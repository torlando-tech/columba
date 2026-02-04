/**
 * ViewModel + Compose Integration Template
 *
 * FEATURES:
 * ✅ Unidirectional data flow (state down, events up)
 * ✅ StateFlow for UI state
 * ✅ SharedFlow for one-time events
 * ✅ collectAsStateWithLifecycle for proper lifecycle handling
 * ✅ Hilt integration
 * ✅ Error handling
 *
 * ARCHITECTURE:
 * ViewModel: Business logic + state management
 * Composable: Pure UI, function of UiState
 */

package com.example.yourapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ==============================================================================
// VIEWMODEL
// ==============================================================================

@HiltViewModel
class ExampleViewModel @Inject constructor(
    private val repository: ExampleRepository
) : ViewModel() {

    // UI State - what the screen displays
    private val _uiState = MutableStateFlow(ExampleUiState())
    val uiState: StateFlow<ExampleUiState> = _uiState.asStateFlow()

    // One-time events (navigation, snackbar, dialogs)
    private val _events = MutableSharedFlow<ExampleEvent>()
    val events: SharedFlow<ExampleEvent> = _events.asSharedFlow()

    init {
        loadData()
    }

    // Single entry point for all user actions
    fun onAction(action: ExampleAction) {
        when (action) {
            is ExampleAction.LoadData -> loadData()
            is ExampleAction.ItemClicked -> handleItemClick(action.id)
            is ExampleAction.Delete -> delete(action.id)
            is ExampleAction.Refresh -> refresh()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = repository.getItems()
                _uiState.update {
                    it.copy(
                        items = items,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Unknown error",
                        isLoading = false
                    )
                }
                _events.emit(ExampleEvent.ShowError(e.message ?: "Unknown error"))
            }
        }
    }

    private fun handleItemClick(id: String) {
        viewModelScope.launch {
            _events.emit(ExampleEvent.NavigateToDetail(id))
        }
    }

    private fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteItem(id)
                _events.emit(ExampleEvent.ShowSnackbar("Item deleted"))
                loadData() // Reload after delete
            } catch (e: Exception) {
                _events.emit(ExampleEvent.ShowError(e.message ?: "Delete failed"))
            }
        }
    }

    private fun refresh() {
        loadData()
    }
}

// ==============================================================================
// STATE AND EVENTS
// ==============================================================================

/**
 * UI State - represents what the screen displays
 */
data class ExampleUiState(
    val items: List<ExampleItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * User Actions - events from UI to ViewModel
 */
sealed interface ExampleAction {
    data object LoadData : ExampleAction
    data class ItemClicked(val id: String) : ExampleAction
    data class Delete(val id: String) : ExampleAction
    data object Refresh : ExampleAction
}

/**
 * One-time Events - from ViewModel to UI
 */
sealed interface ExampleEvent {
    data class NavigateToDetail(val id: String) : ExampleEvent
    data class ShowSnackbar(val message: String) : ExampleEvent
    data class ShowError(val message: String) : ExampleEvent
}

data class ExampleItem(
    val id: String,
    val title: String,
    val description: String
)

// ==============================================================================
// COMPOSABLE
// ==============================================================================

@Composable
fun ExampleScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ExampleViewModel = hiltViewModel()
) {
    // Collect state with lifecycle (stops when not visible)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ExampleEvent.NavigateToDetail -> onNavigateToDetail(event.id)
                is ExampleEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is ExampleEvent.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }
    }

    ExampleScreenContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        snackbarHostState = snackbarHostState
    )
}

@Composable
fun ExampleScreenContent(
    uiState: ExampleUiState,
    onAction: (ExampleAction) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAction(ExampleAction.Refresh) }) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            uiState.error != null -> ErrorState(
                error = uiState.error,
                onRetry = { onAction(ExampleAction.LoadData) },
                modifier = Modifier.padding(padding)
            )
            else -> SuccessState(
                items = uiState.items,
                onItemClick = { id -> onAction(ExampleAction.ItemClicked(id)) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

// ==============================================================================
// REPOSITORY (Example)
// ==============================================================================

/*
interface ExampleRepository {
    suspend fun getItems(): List<ExampleItem>
    suspend fun deleteItem(id: String)
}
*/
