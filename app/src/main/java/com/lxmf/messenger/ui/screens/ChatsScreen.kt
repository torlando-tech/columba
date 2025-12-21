package com.lxmf.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.repository.Conversation
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.ui.components.Identicon
import com.lxmf.messenger.ui.components.SearchableTopAppBar
import com.lxmf.messenger.ui.components.StarToggleButton
import com.lxmf.messenger.viewmodel.ChatsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onChatClick: (peerHash: String, peerName: String) -> Unit = { _, _ -> },
    onViewPeerDetails: (peerHash: String) -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    var isSearching by remember { mutableStateOf(false) }

    // Delete dialog state (context menu state is now per-card)
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Context for Toast notifications
    val context = LocalContext.current

    // Observe manual sync results and show Toast
    LaunchedEffect(Unit) {
        viewModel.manualSyncResult.collect { result ->
            val message =
                when (result) {
                    is SyncResult.Success -> "Sync complete"
                    is SyncResult.Error -> "Sync failed: ${result.message}"
                    is SyncResult.NoRelay -> "No relay configured"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            SearchableTopAppBar(
                title = "Chats",
                subtitle = "${conversations.size} ${if (conversations.size == 1) "conversation" else "conversations"}",
                isSearching = isSearching,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.searchQuery.value = it },
                onSearchToggle = { isSearching = !isSearching },
                searchPlaceholder = "Search conversations...",
                additionalActions = {
                    IconButton(
                        onClick = { viewModel.syncFromPropagationNode() },
                        enabled = !isSyncing,
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync messages",
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        if (conversations.isEmpty()) {
            EmptyChatsState(
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
                items(conversations, key = { it.peerHash }) { conversation ->
                    // Per-card state for context menu
                    val hapticFeedback = LocalHapticFeedback.current
                    var showMenu by remember { mutableStateOf(false) }
                    val isSaved by viewModel.isContactSaved(conversation.peerHash).collectAsState()

                    // Wrap card and menu in Box to anchor menu to card
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ConversationCard(
                            conversation = conversation,
                            isSaved = isSaved,
                            onClick = { onChatClick(conversation.peerHash, conversation.peerName) },
                            onLongPress = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            },
                            onStarClick = {
                                if (isSaved) {
                                    viewModel.removeFromContacts(conversation.peerHash)
                                    Toast.makeText(
                                        context,
                                        "Removed ${conversation.peerName} from Contacts",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    viewModel.saveToContacts(conversation)
                                    Toast.makeText(
                                        context,
                                        "Saved ${conversation.peerName} to Contacts",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )

                        // Context menu anchored to this card
                        ConversationContextMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            isSaved = isSaved,
                            onSaveToContacts = {
                                viewModel.saveToContacts(conversation)
                                showMenu = false
                                Toast.makeText(context, "Saved ${conversation.peerName} to Contacts", Toast.LENGTH_SHORT).show()
                            },
                            onRemoveFromContacts = {
                                viewModel.removeFromContacts(conversation.peerHash)
                                showMenu = false
                                Toast.makeText(context, "Removed ${conversation.peerName} from Contacts", Toast.LENGTH_SHORT).show()
                            },
                            onMarkAsUnread = {
                                viewModel.markAsUnread(conversation.peerHash)
                                showMenu = false
                                Toast.makeText(context, "Marked as unread", Toast.LENGTH_SHORT).show()
                            },
                            onDeleteConversation = {
                                showMenu = false
                                selectedConversation = conversation
                                showDeleteDialog = true
                            },
                            onViewDetails = {
                                showMenu = false
                                onViewPeerDetails(conversation.peerHash)
                            },
                        )
                    }
                }

                // Bottom spacing for navigation bar (fixed height since M3 NavigationBar consumes the insets)
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }

        // Delete confirmation dialog
        val conversationToDelete = selectedConversation
        if (showDeleteDialog && conversationToDelete != null) {
            DeleteConversationDialog(
                peerName = conversationToDelete.peerName,
                onConfirm = {
                    val deletedName = conversationToDelete.peerName
                    viewModel.deleteConversation(conversationToDelete.peerHash)
                    showDeleteDialog = false
                    selectedConversation = null
                    Toast.makeText(context, "Deleted conversation with $deletedName", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    showDeleteDialog = false
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationCard(
    conversation: Conversation,
    isSaved: Boolean = false,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onStarClick: () -> Unit = {},
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Identicon (reuse from AnnounceStreamScreen)
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    Identicon(
                        hash = conversation.peerPublicKey ?: conversation.peerHash.hexStringToByteArray(),
                        size = 56.dp,
                    )
                    // Unread badge (top-right)
                    if (conversation.unreadCount > 0) {
                        Badge(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // Saved contact badge (bottom-right)
                    if (isSaved) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Saved contact",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .align(Alignment.Center),
                            )
                        }
                    }
                }

                // Conversation info
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Peer name
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Last message preview
                    Text(
                        text = conversation.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (conversation.unreadCount > 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Timestamp
                Text(
                    text = formatTimestamp(conversation.lastMessageTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Top),
                )
            }

            // Star button overlay
            StarToggleButton(
                isStarred = isSaved,
                onClick = onStarClick,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
            )
        }
    }
}

@Composable
fun ConversationContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isSaved: Boolean,
    onSaveToContacts: () -> Unit,
    onRemoveFromContacts: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onDeleteConversation: () -> Unit,
    onViewDetails: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 8.dp, y = 0.dp),
    ) {
        // Save/Remove from contacts
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (isSaved) "Remove from Contacts" else "Save to Contacts")
            },
            onClick = {
                if (isSaved) {
                    onRemoveFromContacts()
                } else {
                    onSaveToContacts()
                }
            },
        )

        HorizontalDivider()

        // Mark as unread
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.MarkEmailUnread,
                    contentDescription = null,
                )
            },
            text = {
                Text("Mark as Unread")
            },
            onClick = onMarkAsUnread,
        )

        // View details
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                )
            },
            text = {
                Text("View Peer Details")
            },
            onClick = onViewDetails,
        )

        HorizontalDivider()

        // Delete conversation
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
                    text = "Delete Conversation",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDeleteConversation,
        )
    }
}

@Composable
fun DeleteConversationDialog(
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
            Text("Delete Conversation?")
        },
        text = {
            Text("Are you sure you want to delete your conversation with $peerName? This will permanently delete all messages.")
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
fun EmptyChatsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Messages from peers will appear here",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// Helper function to convert hex string to byte array (for identicon)
private fun String.hexStringToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

// Reuse timestamp formatting from MessagingScreen
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> {
            val minutes = (diff / 60_000).toInt()
            "${minutes}m"
        }
        diff < 86400_000 -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 604800_000 -> { // Less than a week
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
