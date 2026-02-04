/**
 * Edge-to-Edge Screen Template
 *
 * WHEN TO USE:
 * - Creating new full-screen UI
 * - Targeting Android 15+ (SDK 35) with mandatory edge-to-edge
 * - Need to handle keyboard (IME) automatically
 * - Using Material 3 components
 *
 * PREREQUISITES:
 * ✅ enableEdgeToEdge() called in Activity.onCreate()
 * ✅ android:windowSoftInputMode="adjustResize" in AndroidManifest.xml
 * ✅ Material 3 dependencies (androidx.compose.material3)
 * ✅ Activity Compose 1.9.0+ for enableEdgeToEdge()
 *
 * WHAT THIS HANDLES:
 * ✅ Status bar insets (via TopAppBar - automatic)
 * ✅ Navigation bar insets (via BottomBar/NavigationBar - automatic)
 * ✅ Keyboard (IME) insets (via imePadding modifier)
 * ✅ Proper inset consumption (prevents double padding)
 * ✅ Scrollable content with proper padding
 *
 * CUSTOMIZATION POINTS:
 * - Replace YourViewModel with your actual ViewModel
 * - Update screen title and navigation icon
 * - Replace placeholder content with your UI
 * - Add bottom bar (NavigationBar, BottomAppBar, or FAB)
 * - Customize TopAppBar actions
 *
 * TESTING CHECKLIST:
 * [ ] Tested on Android 15 device with gesture navigation
 * [ ] Tested with keyboard open/close cycles
 * [ ] Tested in landscape mode
 * [ ] Tested dark and light modes
 * [ ] Verified no content obscured by system bars
 */

package com.example.yourapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Screen-level composable with proper edge-to-edge setup.
 *
 * This is the entry point for your screen. It handles:
 * - ViewModel integration
 * - State collection with lifecycle awareness
 * - Navigation callbacks
 */
@Composable
fun YourScreen(
    onNavigateBack: () -> Unit,
    viewModel: YourViewModel = hiltViewModel()
) {
    // Collect state with lifecycle awareness (stops collecting when not visible)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Call the content composable with state and event handlers
    YourScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onAction = viewModel::onAction
    )
}

/**
 * Content composable - stateless, pure function of UiState.
 * Easily testable by passing mock state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourScreenContent(
    uiState: YourUiState,
    onNavigateBack: () -> Unit,
    onAction: (YourAction) -> Unit
) {
    Scaffold(
        // TopAppBar automatically handles status bar insets in Material 3
        topBar = {
            TopAppBar(
                title = { Text("Your Screen Title") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    // TODO: Add your action buttons here
                    // Example:
                    // IconButton(onClick = { onAction(YourAction.Refresh) }) {
                    //     Icon(Icons.Default.Refresh, "Refresh")
                    // }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },

        // OPTIONAL: Add bottom bar (NavigationBar, BottomAppBar, or FAB)
        // bottomBar = {
        //     NavigationBar {
        //         NavigationBarItem(...) // Automatically handles nav bar insets
        //     }
        // },

        // OPTIONAL: Add floating action button
        // floatingActionButton = {
        //     FloatingActionButton(
        //         onClick = { onAction(YourAction.Add) }
        //     ) {
        //         Icon(Icons.Default.Add, "Add")
        //     }
        // },

        // OPTIONAL: Snackbar host for showing messages
        // snackbarHost = { SnackbarHost(hostState = snackbarHostState) }

    ) { innerPadding ->
        // innerPadding contains the padding values from TopAppBar and BottomBar

        when {
            uiState.isLoading -> {
                // Loading state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.error != null -> {
                // Error state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Error: ${uiState.error}",
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { onAction(YourAction.Retry) }) {
                            Text("Retry")
                        }
                    }
                }
            }

            else -> {
                // Success state - show content
                LazyColumn(
                    modifier = Modifier
                        // CRITICAL: consumeWindowInsets prevents double padding
                        .consumeWindowInsets(innerPadding)
                        // CRITICAL: imePadding handles keyboard automatically
                        .imePadding(),
                    // Apply innerPadding as contentPadding for LazyColumn
                    contentPadding = innerPadding
                ) {
                    // TODO: Replace with your actual content
                    items(
                        items = uiState.items,
                        key = { item -> item.id } // IMPORTANT: Provide stable, unique key
                    ) { item ->
                        YourListItem(
                            item = item,
                            onItemClick = { onAction(YourAction.ItemClicked(item.id)) }
                        )
                    }
                }
            }
        }
    }
}

// ==============================================================================
// EXAMPLE DATA MODELS - Replace with your actual models
// ==============================================================================

/**
 * UI State - represents what the screen displays
 */
data class YourUiState(
    val items: List<YourItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * User Actions - events from the UI
 */
sealed interface YourAction {
    data class ItemClicked(val id: String) : YourAction
    data object Refresh : YourAction
    data object Retry : YourAction
    data object Add : YourAction
}

/**
 * Item data model
 */
data class YourItem(
    val id: String,
    val title: String,
    val description: String
)

/**
 * Example list item composable
 */
@Composable
fun YourListItem(
    item: YourItem,
    onItemClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onItemClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==============================================================================
// EXAMPLE VIEWMODEL - Replace with your actual ViewModel
// ==============================================================================

/*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YourViewModel @Inject constructor(
    private val repository: YourRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(YourUiState())
    val uiState: StateFlow<YourUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun onAction(action: YourAction) {
        when (action) {
            is YourAction.ItemClicked -> handleItemClick(action.id)
            is YourAction.Refresh -> loadData()
            is YourAction.Retry -> loadData()
            is YourAction.Add -> handleAdd()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = repository.getItems()
                _uiState.update { it.copy(items = items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun handleItemClick(id: String) {
        // Handle item click
    }

    private fun handleAdd() {
        // Handle add action
    }
}
*/

// ==============================================================================
// USAGE IN NAVIGATION
// ==============================================================================

/*
// In your Navigation setup (Compose Navigation):

NavHost(navController, startDestination = "home") {
    composable("your_screen") {
        YourScreen(
            onNavigateBack = { navController.navigateUp() }
        )
    }
}
*/

// ==============================================================================
// COMMON VARIATIONS
// ==============================================================================

/*
// VARIATION 1: With TextField (form screen)
LazyColumn(
    modifier = Modifier
        .consumeWindowInsets(innerPadding)
        .imePadding(), // Critical for keyboard
    contentPadding = innerPadding
) {
    item {
        OutlinedTextField(
            value = text,
            onValueChange = { onAction(YourAction.TextChanged(it)) },
            label = { Text("Enter text") }
        )
    }
}

// VARIATION 2: With pull-to-refresh
val pullToRefreshState = rememberPullToRefreshState()

PullToRefreshBox(
    isRefreshing = uiState.isLoading,
    onRefresh = { onAction(YourAction.Refresh) },
    state = pullToRefreshState,
    modifier = Modifier.padding(innerPadding)
) {
    LazyColumn(...) { ... }
}

// VARIATION 3: With bottom navigation
bottomBar = {
    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, item.label) },
                label = { Text(item.label) }
            )
        }
    }
}
*/
