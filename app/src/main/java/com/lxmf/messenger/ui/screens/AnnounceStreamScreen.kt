package com.lxmf.messenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.ui.components.Identicon
import com.lxmf.messenger.ui.theme.MeshConnected
import com.lxmf.messenger.ui.theme.MeshLimited
import com.lxmf.messenger.ui.theme.MeshOffline
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnounceStreamScreen(
    onPeerClick: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    onStartChat: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
    viewModel: AnnounceStreamViewModel = hiltViewModel(),
) {
    val pagingItems = viewModel.announces.collectAsLazyPagingItems()
    val reachableCount by viewModel.reachableAnnounceCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    val selectedNodeTypes by viewModel.selectedNodeTypes.collectAsState()
    val showAudioAnnounces by viewModel.showAudioAnnounces.collectAsState()

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnnounce by remember { mutableStateOf<Announce?>(null) }

    // Filter dialog state
    var showFilterDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Discovered Nodes",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "$reachableCount nodes in range (active paths)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        // Search icon
                        IconButton(onClick = { isSearching = !isSearching }) {
                            Icon(
                                imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (isSearching) "Close search" else "Search",
                            )
                        }

                        // Filter icon
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter node types",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )

                // Search bar
                AnimatedVisibility(visible = isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.searchQuery.value = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search by name or hash...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                    )
                }
            }
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
                            .consumeWindowInsets(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = pagingItems.itemKey { announce -> announce.destinationHash },
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
                                }
                                .padding(vertical = 4.dp),
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
                            }
                            .padding(vertical = 4.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnnounceCard(
    announce: Announce,
    onClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(end = 32.dp),
                // Extra padding for star button
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Identicon
                Identicon(
                    hash = announce.publicKey,
                    size = 56.dp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )

                // Node information
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Node name
                    Text(
                        text = announce.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Destination hash (abbreviated)
                    Text(
                        text = formatHashString(announce.destinationHash),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Time since last seen
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTimeSince(announce.lastSeenTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Badge and signal strength indicator
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    // Show aspect-specific badge or node type badge
                    when (announce.aspect) {
                        "call.audio" -> AudioBadge()
                        "lxmf.delivery", "lxmf.propagation", "nomadnetwork.node", null -> {
                            // Known aspects or no aspect - show node type badge
                            NodeTypeBadge(nodeType = announce.nodeType)
                        }
                        else -> {
                            // Unknown aspect - show "Other" badge
                            OtherBadge()
                        }
                    }

                    // Signal strength indicator
                    SignalStrengthIndicator(hops = announce.hops)
                }
            }

            // Star button overlay
            IconButton(
                onClick = onFavoriteClick,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (announce.isFavorite) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                } else {
                                    Color.Transparent
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (announce.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (announce.isFavorite) "Remove from saved" else "Save peer",
                        tint =
                            if (announce.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
fun PeerContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    announce: Announce,
    onToggleFavorite: () -> Unit,
    onStartChat: () -> Unit,
    onViewDetails: () -> Unit,
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

        HorizontalDivider()

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
    }
}

@Composable
fun SignalStrengthIndicator(
    hops: Int,
    modifier: Modifier = Modifier,
) {
    // Determine signal strength based on hops
    val (strength, color, description) =
        when {
            hops <= 1 -> Triple(3, MeshConnected, "Excellent")
            hops <= 3 -> Triple(2, MeshLimited, "Good")
            else -> Triple(1, MeshOffline, "Weak")
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Signal bars
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 1..3) {
                Box(
                    modifier =
                        Modifier
                            .width(6.dp)
                            .height((8 + i * 6).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= strength) color else Color.LightGray.copy(alpha = 0.3f)),
                )
            }
        }

        // Hop count
        Text(
            text = "$hops ${if (hops == 1) "hop" else "hops"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
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

@Composable
fun NodeTypeBadge(nodeType: String) {
    val (text, color) =
        when (nodeType) {
            "NODE" -> "Node" to MaterialTheme.colorScheme.tertiary
            "PEER" -> "Peer" to MaterialTheme.colorScheme.primary
            "PROPAGATION_NODE" -> "Relay" to MaterialTheme.colorScheme.secondary
            else -> "Node" to MaterialTheme.colorScheme.tertiary
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun AudioBadge() {
    val color = MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier,
    ) {
        Text(
            text = "Audio",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun OtherBadge() {
    val color = MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier,
    ) {
        Text(
            text = "Other",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatHash(hash: ByteArray): String {
    // Take first 8 bytes and format as hex
    return hash.take(8).joinToString("") { byte ->
        "%02x".format(byte)
    }
}

private fun formatHashString(hashString: String): String {
    // Take first 16 characters (8 bytes worth) of hex string
    return hashString.take(16)
}

private fun formatTimeSince(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMillis = now - timestamp
    val diffMinutes = diffMillis / (60 * 1000)
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "$diffMinutes ${if (diffMinutes == 1L) "minute" else "minutes"} ago"
        diffHours < 24 -> "$diffHours ${if (diffHours == 1L) "hour" else "hours"} ago"
        else -> "$diffDays ${if (diffDays == 1L) "day" else "days"} ago"
    }
}
