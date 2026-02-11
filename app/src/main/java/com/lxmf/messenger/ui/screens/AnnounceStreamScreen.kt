package com.lxmf.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.ui.components.AudioBadge
import com.lxmf.messenger.ui.components.NodeTypeBadge
import com.lxmf.messenger.ui.components.OtherBadge
import com.lxmf.messenger.ui.components.PeerCard
import com.lxmf.messenger.ui.components.SearchableTopAppBar
import com.lxmf.messenger.ui.components.simpleVerticalScrollbar
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel
import kotlinx.coroutines.launch

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

    // Filter dialog state
    var showFilterDialog by remember { mutableStateOf(false) }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var announceToDelete by remember { mutableStateOf<Announce?>(null) }

    // Clear all announces dialog state
    var showClearAllDialog by remember { mutableStateOf(false) }

    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Scroll state and coroutine scope
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Track new announces that appear while scrolled down
    var newAnnouncesCount by remember { mutableIntStateOf(0) }

    // Check if we're at the top of the list
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Reset new announces count when scrolling to top
    LaunchedEffect(isAtTop) {
        if (isAtTop) {
            newAnnouncesCount = 0
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
                    // Filter button
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filter node types",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
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
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (pagingItems.itemCount == 0) {
                EmptyAnnounceState(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .simpleVerticalScrollbar(listState),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = { index ->
                            val announce = pagingItems.peek(index)
                            if (announce != null) "${announce.destinationHash}_$index" else "placeholder_$index"
                        },
                    ) { index ->
                        val announce = pagingItems[index]
                        if (announce != null) {
                            Box {
                                AnnounceCard(
                                    announce = announce,
                                    onClick = {
                                        onPeerClick(announce.destinationHash, announce.peerName)
                                    },
                                    onFavoriteClick = {
                                        viewModel.toggleContact(announce.destinationHash)
                                    },
                                    onLongPress = {
                                        contextMenuAnnounce = announce
                                        showContextMenu = true
                                    },
                                )

                                // Show context menu for this announce
                                if (showContextMenu && contextMenuAnnounce == announce) {
                                    PeerContextMenu(
                                        expanded = true,
                                        onDismiss = { showContextMenu = false },
                                        announce = announce,
                                        onToggleFavorite = {
                                            viewModel.toggleContact(announce.destinationHash)
                                        },
                                        onStartChat = {
                                            onStartChat(announce.destinationHash, announce.peerName)
                                        },
                                        onViewDetails = {
                                            onPeerClick(announce.destinationHash, announce.peerName)
                                        },
                                        onDeleteAnnounce = {
                                            announceToDelete = announce
                                            showDeleteDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Bottom spacing for navigation bar (fixed height since M3 NavigationBar consumes the insets)
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }

                // New announces indicator button
                if (newAnnouncesCount > 0) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                                newAnnouncesCount = 0
                            }
                        },
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = paddingValues.calculateTopPadding() + 8.dp),
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
                                contentDescription = "Scroll to top",
                            )
                            Text(
                                text = "$newAnnouncesCount",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    // Show filter dialog
    if (showFilterDialog) {
        NodeTypeFilterDialog(
            selectedTypes = selectedNodeTypes,
            showAudio = showAudioAnnounces,
            onDismiss = { showFilterDialog = false },
            onConfirm = { newSelection, newShowAudio ->
                viewModel.updateSelectedNodeTypes(newSelection)
                viewModel.updateShowAudioAnnounces(newShowAudio)
                showFilterDialog = false
            },
        )
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

@Composable
fun NodeTypeFilterDialog(
    selectedTypes: Set<NodeType>,
    showAudio: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<NodeType>, Boolean) -> Unit,
) {
    var tempSelection by remember { mutableStateOf(selectedTypes) }
    var tempShowAudio by remember { mutableStateOf(showAudio) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Filter Node Types",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Select which node types to display:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Checkbox for each node type
                NodeType.entries.forEach { nodeType ->
                    val (displayName, description) =
                        when (nodeType) {
                            NodeType.NODE -> "Node" to "Nomadnet nodes"
                            NodeType.PEER -> "Peer" to "Nodes you can message with"
                            NodeType.PROPAGATION_NODE -> "Relay" to "Relay/repeater nodes for signal propagation"
                        }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    tempSelection =
                                        if (tempSelection.contains(nodeType)) {
                                            tempSelection - nodeType
                                        } else {
                                            tempSelection + nodeType
                                        }
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(
                            checked = tempSelection.contains(nodeType),
                            onCheckedChange = { isChecked ->
                                tempSelection =
                                    if (isChecked) {
                                        tempSelection + nodeType
                                    } else {
                                        tempSelection - nodeType
                                    }
                            },
                            colors =
                                CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.tertiary,
                                    checkmarkColor = MaterialTheme.colorScheme.onTertiary,
                                ),
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Audio announces filter
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                tempShowAudio = !tempShowAudio
                            }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = tempShowAudio,
                        onCheckedChange = { tempShowAudio = it },
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.error,
                                checkmarkColor = MaterialTheme.colorScheme.onError,
                            ),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Audio",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Show audio call announces",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tempSelection, tempShowAudio) },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
                "call.audio" -> AudioBadge()
                "lxmf.delivery", "lxmf.propagation", "nomadnetwork.node", null -> {
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
 */
@Composable
fun AnnounceStreamContent(
    viewModel: AnnounceStreamViewModel = hiltViewModel(),
    onPeerClick: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    onStartChat: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val pagingItems = viewModel.announces.collectAsLazyPagingItems()

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnnounce by remember { mutableStateOf<Announce?>(null) }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var announceToDelete by remember { mutableStateOf<Announce?>(null) }

    // Scroll state
    val listState = rememberLazyListState()

    // Scroll position anchor — tracks which item the user is viewing by identity (not index).
    // When Room invalidates the PagingSource and items shift positions, we find our anchor
    // item in the new data and scroll back to it.
    var anchorHash by remember { mutableStateOf<String?>(null) }
    var anchorOffset by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val hash =
                if (index < pagingItems.itemCount) {
                    pagingItems.peek(index)?.destinationHash
                } else {
                    null
                }
            Triple(index, offset, hash)
        }.collect { (index, offset, currentHash) ->
            if (currentHash == null) return@collect
            val savedHash = anchorHash

            if (savedHash != null && savedHash != currentHash && index > 0) {
                // The item at our position changed identity — data refreshed under us.
                // Find where our anchor item moved to and restore position.
                val newIndex =
                    pagingItems.itemSnapshotList.items
                        .indexOfFirst { it.destinationHash == savedHash }
                if (newIndex >= 0) {
                    listState.scrollToItem(newIndex, anchorOffset)
                    return@collect // Don't update anchor yet — next emission will confirm
                }
            }

            // Normal tracking: save current position
            anchorHash = currentHash
            anchorOffset = offset
        }
    }

    // Check loading state - only show spinner for initial load when list is empty
    // This prevents flickering when new announces arrive and trigger a refresh
    val isLoading = pagingItems.loadState.refresh is androidx.paging.LoadState.Loading

    when {
        isLoading && pagingItems.itemCount == 0 -> {
            LoadingNetworkState(modifier = modifier.fillMaxSize())
        }
        !isLoading && pagingItems.itemCount == 0 -> {
            EmptyAnnounceState(modifier = modifier.fillMaxSize())
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier =
                    modifier
                        .fillMaxSize()
                        .simpleVerticalScrollbar(listState),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    count = pagingItems.itemCount,
                    key = { index ->
                        pagingItems.peek(index)?.destinationHash ?: "placeholder_$index"
                    },
                ) { index ->
                    val announce = pagingItems[index]
                    if (announce != null) {
                        Box {
                            AnnounceCard(
                                announce = announce,
                                onClick = {
                                    onPeerClick(announce.destinationHash, announce.peerName)
                                },
                                onFavoriteClick = {
                                    viewModel.toggleContact(announce.destinationHash)
                                },
                                onLongPress = {
                                    contextMenuAnnounce = announce
                                    showContextMenu = true
                                },
                            )

                            // Show context menu for this announce
                            if (showContextMenu && contextMenuAnnounce == announce) {
                                PeerContextMenu(
                                    expanded = true,
                                    onDismiss = { showContextMenu = false },
                                    announce = announce,
                                    onToggleFavorite = {
                                        viewModel.toggleContact(announce.destinationHash)
                                    },
                                    onStartChat = {
                                        onStartChat(announce.destinationHash, announce.peerName)
                                    },
                                    onViewDetails = {
                                        onPeerClick(announce.destinationHash, announce.peerName)
                                    },
                                    onDeleteAnnounce = {
                                        announceToDelete = announce
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    }
                }

                // Bottom spacing for navigation bar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
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
