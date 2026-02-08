package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.ui.components.PeerCard
import com.lxmf.messenger.ui.components.SearchableTopAppBar
import com.lxmf.messenger.viewmodel.SavedPeersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPeersScreen(
    onPeerClick: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    viewModel: SavedPeersViewModel = hiltViewModel(),
) {
    val savedPeers by viewModel.savedPeers.collectAsState(initial = emptyList())
    val favoriteCount by viewModel.favoriteCount.collectAsState(initial = 0)
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearching by remember { mutableStateOf(false) }

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnnounce by remember { mutableStateOf<Announce?>(null) }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var announceToDelete by remember { mutableStateOf<Announce?>(null) }

    Scaffold(
        topBar = {
            SearchableTopAppBar(
                title = "Saved Peers",
                subtitle = "$favoriteCount ${if (favoriteCount == 1) "peer" else "peers"} saved",
                isSearching = isSearching,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.searchQuery.value = it },
                onSearchToggle = { isSearching = !isSearching },
                searchPlaceholder = "Search by name or hash...",
            )
        },
    ) { paddingValues ->
        if (savedPeers.isEmpty()) {
            EmptySavedPeersState(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    savedPeers,
                    key = { announce -> "saved_${announce.destinationHash}" },
                ) { announce ->
                    Box {
                        SavedPeerCard(
                            announce = announce,
                            onClick = {
                                onPeerClick(announce.destinationHash, announce.peerName)
                            },
                            onFavoriteClick = {
                                viewModel.toggleFavorite(announce.destinationHash)
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
                                    viewModel.toggleFavorite(announce.destinationHash)
                                },
                                onStartChat = {
                                    onPeerClick(announce.destinationHash, announce.peerName)
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
fun SavedPeerCard(
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
        // Always show filled star for saved peers
        showFavoriteToggle = false,
    )
}

@Composable
fun EmptySavedPeersState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.StarBorder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No saved peers yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap the star icon on any peer in the Announce Stream to save them here for quick access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
