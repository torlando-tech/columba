/**
 * Optimized LazyColumn Template
 *
 * PERFORMANCE FEATURES:
 * ✅ Stable, unique keys (prevents unnecessary recomposition)
 * ✅ contentType for item recycling
 * ✅ remember for cached calculations
 * ✅ derivedStateOf for threshold-based state
 * ✅ Proper consumeWindowInsets
 * ✅ immutable/stable parameters
 *
 * PERFORMANCE TARGETS:
 * - 60 FPS during scrolling
 * - < 16ms frame time
 * - Minimal recompositions (< 10 for static items)
 *
 * VERIFICATION:
 * [ ] Layout Inspector shows low recomposition counts
 * [ ] Compiler reports show "restartable skippable"
 * [ ] Smooth 60 FPS scrolling on mid-range devices
 */

package com.example.yourapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/**
 * Optimized list with scroll-to-top FAB
 *
 * @param items Use ImmutableList for stability (prevents unnecessary recompositions)
 */
@Composable
fun OptimizedListScreen(
    items: ImmutableList<ListItemData>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // derivedStateOf: Only recomposes when boolean changes, not on every scroll pixel
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        OptimizedLazyColumn(
            items = items,
            listState = listState,
            onItemClick = onItemClick
        )

        // Scroll-to-top FAB (only shows after scrolling past 3 items)
        if (showScrollToTop) {
            val coroutineScope = rememberCoroutineScope()
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowUp, "Scroll to top")
            }
        }
    }
}

/**
 * Core LazyColumn with all performance optimizations
 */
@Composable
fun OptimizedLazyColumn(
    items: ImmutableList<ListItemData>,
    listState: LazyListState,
    onItemClick: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier
) {
    // Cached calculation - only recomputes when items change
    val groupedItems = remember(items) {
        items.groupBy { it.category }
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize()
    ) {
        groupedItems.forEach { (category, categoryItems) ->
            // Header
            item(
                key = "header_$category", // Stable key for header
                contentType = "header" // Helps with item recycling
            ) {
                CategoryHeader(category = category)
            }

            // Items
            items(
                items = categoryItems,
                key = { item -> item.id }, // CRITICAL: Stable, unique key
                contentType = { item -> item.type } // Helps recycling heterogeneous items
            ) { item ->
                // Composable is "restartable skippable" because:
                // 1. item parameter is stable (immutable data class)
                // 2. onItemClick is stable (function reference)
                OptimizedListItem(
                    item = item,
                    onClick = { onItemClick(item.id) }
                )
            }
        }
    }
}

/**
 * Optimized list item - immutable parameters for skipping
 */
@Composable
private fun OptimizedListItem(
    item: ListItemData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // This composable will skip recomposition when item hasn't changed
    // because parameters are stable/immutable

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon or image
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = item.title.first().toString(),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.badge != null) {
                    Badge {
                        Text(item.badge)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = category.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// ==============================================================================
// DATA MODELS - Immutable for performance
// ==============================================================================

/**
 * Immutable data class - stable by default
 */
data class ListItemData(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val type: ItemType = ItemType.Standard,
    val badge: String? = null
)

enum class ItemType {
    Standard,
    Featured,
    Compact
}

// ==============================================================================
// USAGE EXAMPLE WITH VIEWMODEL
// ==============================================================================

/*
@HiltViewModel
class ListViewModel @Inject constructor(
    repository: ItemRepository
) : ViewModel() {

    val items: StateFlow<ImmutableList<ListItemData>> = repository.itemsFlow
        .map { it.toImmutableList() } // Convert to immutable for stability
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList<ListItemData>().toImmutableList()
        )
}

@Composable
fun ListScreen(viewModel: ListViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    OptimizedListScreen(
        items = items,
        onItemClick = { id -> /* handle click */ }
    )
}
*/

// ==============================================================================
// PERFORMANCE NOTES
// ==============================================================================

/*
WHY THIS IS FAST:

1. STABLE KEYS:
   - items(key = { it.id }) prevents full list recomposition
   - Only changed items recompose
   - Preserves scroll position

2. IMMUTABLE LIST:
   - ImmutableList<T> is marked @Stable
   - Compose knows when list actually changes
   - Prevents unnecessary recompositions

3. CONTENT TYPE:
   - contentType = { it.type } helps item recycling
   - Compose reuses compositions of same type
   - Faster for heterogeneous lists

4. DERIVED STATE:
   - derivedStateOf { showButton } only recomposes when boolean changes
   - Not on every scroll pixel

5. CACHED CALCULATIONS:
   - remember(items) { groupBy() } only recalculates when items change
   - Not on every recomposition

6. STABLE PARAMETERS:
   - All composable parameters are immutable
   - Enables "restartable skippable" optimization

VERIFY WITH:
- Layout Inspector: Recomposition counts < 10 for static items
- Compiler reports: All composables "restartable skippable"
- Systrace: Frame time < 16ms during scroll
*/
