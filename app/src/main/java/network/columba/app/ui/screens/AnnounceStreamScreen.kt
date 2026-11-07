package network.columba.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import network.columba.app.data.repository.Announce
import network.columba.app.viewmodel.AnnounceRowEdit
import network.columba.app.rns.api.model.NodeType
import network.columba.app.ui.components.AnnounceFilterChips
import network.columba.app.ui.components.LocalWindowSize
import network.columba.app.ui.components.NodeTypeBadge
import network.columba.app.ui.components.OtherBadge
import network.columba.app.ui.components.PeerCard
import network.columba.app.ui.components.SearchableTopAppBar
import network.columba.app.ui.components.simpleVerticalScrollbar
import network.columba.app.viewmodel.AnnounceStreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnounceStreamScreen(
    onPeerClick: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    onStartChat: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    initialFilterType: String? = null,
    viewModel: AnnounceStreamViewModel = hiltViewModel(),
) {
    val pagingItems = viewModel.announces.collectAsLazyPagingItems()
    val reachableCount by viewModel.reachableAnnounceCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    val selectedNodeTypes by viewModel.selectedNodeTypes.collectAsState()
    val showAudioAnnounces by viewModel.showAudioAnnounces.collectAsState()
    val selectedInterfaceTypes by viewModel.selectedInterfaceTypes.collectAsState()

    // Apply initial filter if provided (e.g., from relay settings "View All Relays...")
    LaunchedEffect(initialFilterType) {
        if (initialFilterType != null) {
            val nodeType = runCatching { NodeType.valueOf(initialFilterType) }.getOrNull()
            if (nodeType != null) {
                viewModel.updateSelectedNodeTypes(setOf(nodeType))
            }
        }
    }

    // Announce button state
    val isAnnouncing by viewModel.isAnnouncing.collectAsState()
    val announceSuccess by viewModel.announceSuccess.collectAsState()
    val announceError by viewModel.announceError.collectAsState()
    val context = LocalContext.current

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnnounce by remember { mutableStateOf<Announce?>(null) }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var announceToDelete by remember { mutableStateOf<Announce?>(null) }

    // Clear all announces dialog state
    var showClearAllDialog by remember { mutableStateOf(false) }

    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Filter-chip expand state — see ContactsScreen for the rationale (GH
    // issue #922). Keyed on heightSizeClass so rotation snaps to the default;
    // explicit toggle persists within a single orientation session.
    val heightSizeClass = LocalWindowSize.current.heightSizeClass
    var filtersExpanded by remember(heightSizeClass) {
        mutableStateOf(heightSizeClass != WindowHeightSizeClass.Compact)
    }

    // Scroll state and coroutine scope
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Track announces that arrived after the loaded stream was started. The list is frozen
    // while scrolled (GH #1166); the count drives the "new announces" affordance.
    val newAnnouncesCount by viewModel.newAnnounceCount.collectAsState()
    val windowRefreshPending by viewModel.windowRefreshPending.collectAsState()
    val rowEdits by viewModel.rowEdits.collectAsState()
    val hasVisibleRows = hasVisibleAnnounce(pagingItems, rowEdits)

    // Check if we're at the top of the list
    val isAtTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Reloading replaces every loaded page, so only do it while the viewport is at the top.
    LaunchedEffect(isAtTop, newAnnouncesCount, windowRefreshPending) {
        if (isAtTop && (newAnnouncesCount > 0 || windowRefreshPending)) {
            viewModel.refreshAnnounces()
        }
    }

    // Removing the last visible card leaves the loaded list unchanged but renders empty;
    // reset the viewport so later arrivals reload the list instead of arriving unseen.
    LaunchedEffect(hasVisibleRows) {
        if (!hasVisibleRows) {
            listState.scrollToItem(0)
        }
    }

    // Show toast for announce success/error
    LaunchedEffect(announceSuccess) {
        if (announceSuccess) {
            Toast.makeText(context, "Announce sent!", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(announceError) {
        announceError?.let { error ->
            Toast.makeText(context, "Error: $error", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            SearchableTopAppBar(
                title = "Discovered Nodes",
                subtitle = "$reachableCount nodes in range (active paths)",
                isSearching = isSearching,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.searchQuery.value = it },
                onSearchToggle = { isSearching = !isSearching },
                searchPlaceholder = "Search by name or hash...",
                additionalActions = {
                    // Filter chips toggle (matches ContactsScreen Network tab)
                    IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                        Icon(
                            imageVector =
                                if (filtersExpanded) {
                                    Icons.Default.FilterAltOff
                                } else {
                                    Icons.Default.FilterAlt
                                },
                            contentDescription =
                                if (filtersExpanded) "Hide filters" else "Show filters",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    // Announce button
                    IconButton(
                        onClick = { viewModel.triggerAnnounce() },
                        enabled = !isAnnouncing,
                    ) {
                        if (isAnnouncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "Announce now",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Clear All Announces",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearAllDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues),
        ) {
            AnnounceFilterChips(
                selectedNodeTypes = selectedNodeTypes,
                showAudio = showAudioAnnounces,
                selectedInterfaceTypes = selectedInterfaceTypes,
                onNodeTypesChange = viewModel::updateSelectedNodeTypes,
                onShowAudioChange = viewModel::updateShowAudioAnnounces,
                onInterfaceTypesChange = viewModel::updateSelectedInterfaceTypes,
                expanded = filtersExpanded,
                onExpandRequest = { filtersExpanded = true },
            )
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .simpleVerticalScrollbar(listState),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!hasVisibleRows) {
                        item {
                            EmptyAnnounceState(modifier = Modifier.fillParentMaxSize())
                        }
                    } else {
                        items(
                            count = pagingItems.itemCount,
                            key = { index -> pagingItems.peek(index)?.destinationHash ?: "loading_$index" },
                        ) { index ->
                            val announce = pagingItems[index]
                            val rowEdit = announce?.let { rowEdits[it.destinationHash] }
                            if (announce != null && rowEdit?.isDeleted != true) {
                                val favorite = rowEdit?.isFavorite
                                val displayed =
                                    if (favorite != null) announce.copy(isFavorite = favorite) else announce
                                Box {
                                    AnnounceCard(
                                        announce = displayed,
                                        onClick = {
                                            onPeerClick(displayed.destinationHash, displayed.peerName)
                                        },
                                        onFavoriteClick = {
                                            viewModel.toggleContact(displayed.destinationHash)
                                        },
                                        onLongPress = {
                                            contextMenuAnnounce = displayed
                                            showContextMenu = true
                                        },
                                    )

                                    // Show context menu for this announce
                                    if (showContextMenu && contextMenuAnnounce == displayed) {
                                        PeerContextMenu(
                                            expanded = true,
                                            onDismiss = { showContextMenu = false },
                                            announce = displayed,
                                            onToggleFavorite = {
                                                viewModel.toggleContact(displayed.destinationHash)
                                            },
                                            onStartChat = {
                                                onStartChat(displayed.destinationHash, displayed.peerName)
                                            },
                                            onViewDetails = {
                                                onPeerClick(displayed.destinationHash, displayed.peerName)
                                            },
                                            onDeleteAnnounce = {
                                                announceToDelete = displayed
                                                showDeleteDialog = true
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom spacing for navigation bar (fixed height since M3 NavigationBar consumes the insets)
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }

                if (!isAtTop && newAnnouncesCount > 0) {
                    NewAnnouncesFab(
                        count = newAnnouncesCount,
                        onClick = {
                            viewModel.refreshAnnounces()
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // Delete announce confirmation dialog
    val announceForDelete = announceToDelete
    if (showDeleteDialog && announceForDelete != null) {
        DeleteAnnounceDialog(
            peerName = announceForDelete.peerName,
            onConfirm = {
                viewModel.deleteAnnounce(announceForDelete.destinationHash)
                showDeleteDialog = false
                announceToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                announceToDelete = null
            },
        )
    }

    // Clear all announces confirmation dialog
    if (showClearAllDialog) {
        ClearAllAnnouncesDialog(
            onConfirm = {
                viewModel.deleteAllAnnounces()
                showClearAllDialog = false
            },
            onDismiss = {
                showClearAllDialog = false
            },
        )
    }
}

@androidx.compose.runtime.Stable
@Composable
fun AnnounceCard(
    announce: Announce,
    onClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    PeerCard(
        announce = announce,
        onClick = onClick,
        onFavoriteClick = onFavoriteClick,
        onLongPress = onLongPress,
        badgeContent = {
            // Show aspect-specific badge or node type badge
            when (announce.aspect) {
                "lxmf.delivery", "lxmf.propagation", "nomadnetwork.node", "lxst.telephony", null -> {
                    NodeTypeBadge(nodeType = announce.nodeType)
                }
                else -> OtherBadge()
            }
        },
    )
}

@Composable
fun PeerContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    announce: Announce,
    onToggleFavorite: () -> Unit,
    onStartChat: () -> Unit,
    onViewDetails: () -> Unit,
    onDeleteAnnounce: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 8.dp, y = 0.dp),
    ) {
        // Toggle favorite
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (announce.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (announce.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (announce.isFavorite) "Remove from Saved" else "Save Peer")
            },
            onClick = {
                onToggleFavorite()
                onDismiss()
            },
        )

        HorizontalDivider()

        // Start chat (only for LXMF delivery peers, not audio calls)
        if (announce.aspect == "lxmf.delivery") {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                    )
                },
                text = {
                    Text("Start Chat")
                },
                onClick = {
                    onStartChat()
                    onDismiss()
                },
            )
        }

        // View details
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                )
            },
            text = {
                Text("View Details")
            },
            onClick = {
                onViewDetails()
                onDismiss()
            },
        )

        HorizontalDivider()

        // Delete announce
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDeleteAnnounce()
                onDismiss()
            },
        )
    }
}

/**
 * Reusable announce stream content without the scaffold/app bar.
 * Can be embedded in other screens like ContactsScreen Network tab.
 *
 * [filtersExpanded] / [onExpandFiltersRequest] are hoisted up to the host
 * screen so the same state can drive a Filter icon in the host's top bar
 * (typical pattern: Filter icon toggles, the collapsed pill row also requests
 * expansion). See [AnnounceFilterChips] for the collapsed-state contract.
 */
@Composable
fun AnnounceStreamContent(
    viewModel: AnnounceStreamViewModel = hiltViewModel(),
    filtersExpanded: Boolean = true,
    onExpandFiltersRequest: () -> Unit = {},
    onPeerClick: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    onStartChat: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val pagingItems = viewModel.announces.collectAsLazyPagingItems()
    val selectedNodeTypes by viewModel.selectedNodeTypes.collectAsState()
    val showAudioAnnounces by viewModel.showAudioAnnounces.collectAsState()
    val selectedInterfaceTypes by viewModel.selectedInterfaceTypes.collectAsState()

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnnounce by remember { mutableStateOf<Announce?>(null) }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var announceToDelete by remember { mutableStateOf<Announce?>(null) }

    // Scroll state
    val listState = rememberLazyListState()

    // Track announces that arrived after the loaded stream was started. The list is frozen
    // while scrolled (GH #1166); the count drives the "new announces" affordance.
    val newAnnouncesCount by viewModel.newAnnounceCount.collectAsState()
    val windowRefreshPending by viewModel.windowRefreshPending.collectAsState()
    val rowEdits by viewModel.rowEdits.collectAsState()
    val hasVisibleRows = hasVisibleAnnounce(pagingItems, rowEdits)
    val coroutineScope = rememberCoroutineScope()

    val isAtTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Check loading state - only show spinner for initial load when list is empty
    // This prevents flickering when new announces arrive and trigger a refresh
    val isLoading = pagingItems.loadState.refresh is androidx.paging.LoadState.Loading

    // Reloading replaces every loaded page, so only do it while the viewport is at the top.
    LaunchedEffect(isAtTop, newAnnouncesCount, windowRefreshPending) {
        if (isAtTop && (newAnnouncesCount > 0 || windowRefreshPending)) {
            viewModel.refreshAnnounces()
        }
    }

    // Removing the last visible card leaves the loaded list unchanged but renders empty;
    // reset the viewport so later arrivals reload the list instead of arriving unseen.
    LaunchedEffect(hasVisibleRows) {
        if (!hasVisibleRows) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AnnounceFilterChips(
            selectedNodeTypes = selectedNodeTypes,
            showAudio = showAudioAnnounces,
            selectedInterfaceTypes = selectedInterfaceTypes,
            onNodeTypesChange = viewModel::updateSelectedNodeTypes,
            onShowAudioChange = viewModel::updateShowAudioAnnounces,
            onInterfaceTypesChange = viewModel::updateSelectedInterfaceTypes,
            expanded = filtersExpanded,
            onExpandRequest = onExpandFiltersRequest,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .simpleVerticalScrollbar(listState),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    !hasVisibleRows && isLoading && pagingItems.itemCount == 0 -> {
                        item {
                            LoadingNetworkState(modifier = Modifier.fillParentMaxSize())
                        }
                    }
                    !hasVisibleRows -> {
                        item {
                            EmptyAnnounceState(modifier = Modifier.fillParentMaxSize())
                        }
                    }
                    else -> {
                        items(
                            count = pagingItems.itemCount,
                            key = { index -> pagingItems.peek(index)?.destinationHash ?: "loading_$index" },
                        ) { index ->
                            val announce = pagingItems[index]
                            val rowEdit = announce?.let { rowEdits[it.destinationHash] }
                            if (announce != null && rowEdit?.isDeleted != true) {
                                val favorite = rowEdit?.isFavorite
                                val displayed =
                                    if (favorite != null) announce.copy(isFavorite = favorite) else announce
                                Box {
                                    AnnounceCard(
                                        announce = displayed,
                                        onClick = {
                                            onPeerClick(displayed.destinationHash, displayed.peerName)
                                        },
                                        onFavoriteClick = {
                                            viewModel.toggleContact(displayed.destinationHash)
                                        },
                                        onLongPress = {
                                            contextMenuAnnounce = displayed
                                            showContextMenu = true
                                        },
                                    )

                                    // Show context menu for this announce
                                    if (showContextMenu && contextMenuAnnounce == displayed) {
                                        PeerContextMenu(
                                            expanded = true,
                                            onDismiss = { showContextMenu = false },
                                            announce = displayed,
                                            onToggleFavorite = {
                                                viewModel.toggleContact(displayed.destinationHash)
                                            },
                                            onStartChat = {
                                                onStartChat(displayed.destinationHash, displayed.peerName)
                                            },
                                            onViewDetails = {
                                                onPeerClick(displayed.destinationHash, displayed.peerName)
                                            },
                                            onDeleteAnnounce = {
                                                announceToDelete = displayed
                                                showDeleteDialog = true
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom spacing for navigation bar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }

            if (!isAtTop && newAnnouncesCount > 0) {
                NewAnnouncesFab(
                    count = newAnnouncesCount,
                    onClick = {
                        viewModel.refreshAnnounces()
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp),
                )
            }
        }
    }

    // Delete announce confirmation dialog (sibling of Column — outside filter chip surface)
    val announceForDelete = announceToDelete
    if (showDeleteDialog && announceForDelete != null) {
        DeleteAnnounceDialog(
            peerName = announceForDelete.peerName,
            onConfirm = {
                viewModel.deleteAnnounce(announceForDelete.destinationHash)
                showDeleteDialog = false
                announceToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                announceToDelete = null
            },
        )
    }
}

@Composable
fun LoadingNetworkState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading network...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyAnnounceState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No nodes discovered yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Listening for announces...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Whether any loaded row is still visible. Rows removed by the user are kept in the paged
 * window (the stream is frozen) and only hidden, so itemCount alone cannot tell if the
 * list is empty.
 */
private fun hasVisibleAnnounce(
    pagingItems: LazyPagingItems<Announce>,
    rowEdits: Map<String, AnnounceRowEdit>,
): Boolean {
    for (index in 0 until pagingItems.itemCount) {
        val hash = pagingItems.peek(index)?.destinationHash
        if (hash == null || rowEdits[hash]?.isDeleted != true) return true
    }
    return false
}

private fun formatHash(hash: ByteArray): String {
    // Take first 8 bytes and format as hex
    return hash.take(8).joinToString("") { byte ->
        "%02x".format(byte)
    }
}

@Composable
fun DeleteAnnounceDialog(
    peerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text("Delete Announce?")
        },
        text = {
            Text("Remove $peerName from the list? They will reappear when they announce again.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ClearAllAnnouncesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text("Clear All Announces?")
        },
        text = {
            Text("This will remove all discovered nodes from the list, except those saved in My Contacts. Nodes will reappear when they announce again.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Clear All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Floating affordance shown while the announce stream is frozen and announces arrived.
 * Tapping it reloads from the newest row and returns the viewport to the top.
 */
@Composable
private fun NewAnnouncesFab(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Show new announces",
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
