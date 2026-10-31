package network.columba.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import network.columba.app.R
import network.columba.app.ui.model.ThreadRowUi
import network.columba.app.ui.model.ThreadSheetUiState

/**
 * Thread sheet (threads-spec §6.3): Active / Dormant / Archived sections,
 * each row = title + last-message preview + unread count + timestamp.
 * Row tap opens the thread; long-press offers rename / archive /
 * un-archive / delete — all local-only (confirm dialog on delete; wire
 * history is never touched). "+ New" starts a thread from scratch (case 1).
 *
 * Stateless: the hosting screen supplies [ThreadSheetUiState] from its
 * ViewModel and handles the callbacks (both ChatsScreen and MessagingScreen
 * host this sheet).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ThreadSheet(
    state: ThreadSheetUiState,
    onDismiss: () -> Unit,
    onThreadClick: (threadIdHex: String) -> Unit,
    onCreateThread: () -> Unit,
    onRename: (threadIdHex: String, newTitle: String) -> Unit,
    onArchive: (threadIdHex: String) -> Unit,
    onUnarchive: (threadIdHex: String) -> Unit,
    onDelete: (threadIdHex: String) -> Unit,
) {
    var menuRow by remember { mutableStateOf<ThreadRowUi?>(null) }
    var renamingRow by remember { mutableStateOf<ThreadRowUi?>(null) }
    var deletingRow by remember { mutableStateOf<ThreadRowUi?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.thread_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCreateThread) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.thread_sheet_new_thread))
                }
            }

            if (state.isEmpty) {
                Text(
                    text = stringResource(R.string.thread_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (state.active.isNotEmpty()) {
                        item(key = "section-active") {
                            ThreadSheetSectionHeader(stringResource(R.string.thread_sheet_section_active))
                        }
                        items(state.active, key = { it.threadIdHex }) { row ->
                            ThreadSheetRow(
                                row = row,
                                onClick = { onThreadClick(row.threadIdHex) },
                                onLongPress = { menuRow = row },
                            )
                        }
                    }
                    if (state.dormant.isNotEmpty()) {
                        item(key = "section-dormant") {
                            ThreadSheetSectionHeader(stringResource(R.string.thread_sheet_section_dormant))
                        }
                        items(state.dormant, key = { it.threadIdHex }) { row ->
                            ThreadSheetRow(
                                row = row,
                                onClick = { onThreadClick(row.threadIdHex) },
                                onLongPress = { menuRow = row },
                            )
                        }
                    }
                    if (state.archived.isNotEmpty()) {
                        item(key = "section-archived") {
                            ThreadSheetSectionHeader(stringResource(R.string.thread_sheet_section_archived))
                        }
                        items(state.archived, key = { it.threadIdHex }) { row ->
                            ThreadSheetRow(
                                row = row,
                                onClick = { onThreadClick(row.threadIdHex) },
                                onLongPress = { menuRow = row },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    // Long-press management menu (rename / archive / un-archive / delete).
    val menuTarget = menuRow
    if (menuTarget != null) {
        val isArchived = state.archived.any { it.threadIdHex == menuTarget.threadIdHex }
        AlertDialog(
            onDismissRequest = { menuRow = null },
            title = {
                Text(
                    text = menuTarget.title ?: stringResource(R.string.thread_untitled),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column {
                    ThreadActionItem(
                        icon = Icons.Default.Edit,
                        label = stringResource(R.string.thread_action_rename),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            renamingRow = menuTarget
                            menuRow = null
                        },
                    )
                    if (isArchived) {
                        ThreadActionItem(
                            icon = Icons.Default.Unarchive,
                            label = stringResource(R.string.thread_action_unarchive),
                            tint = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                onUnarchive(menuTarget.threadIdHex)
                                menuRow = null
                            },
                        )
                    } else {
                        ThreadActionItem(
                            icon = Icons.Default.Archive,
                            label = stringResource(R.string.thread_action_archive),
                            tint = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                onArchive(menuTarget.threadIdHex)
                                menuRow = null
                            },
                        )
                    }
                    ThreadActionItem(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.thread_action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            deletingRow = menuTarget
                            menuRow = null
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuRow = null }) {
                    Text(stringResource(R.string.thread_action_cancel))
                }
            },
        )
    }

    // Rename dialog (local label only — titles never touch the wire).
    val renameTarget = renamingRow
    if (renameTarget != null) {
        ThreadRenameDialog(
            initialTitle = renameTarget.title.orEmpty(),
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) onRename(renameTarget.threadIdHex, newTitle.trim())
                renamingRow = null
            },
            onDismiss = { renamingRow = null },
        )
    }

    // Delete confirmation (local-only delete; spec §6.3).
    val deleteTarget = deletingRow
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deletingRow = null },
            title = { Text(stringResource(R.string.thread_delete_title)) },
            text = { Text(stringResource(R.string.thread_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(deleteTarget.threadIdHex)
                        deletingRow = null
                    },
                ) {
                    Text(
                        stringResource(R.string.thread_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingRow = null }) {
                    Text(stringResource(R.string.thread_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThreadSheetSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadSheetRow(
    row: ThreadRowUi,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title ?: stringResource(R.string.thread_untitled),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.unreadCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (row.unreadCount > 99) "99+" else row.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTimestamp(row.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = row.preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThreadRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thread_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.thread_rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.thread_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.thread_action_cancel))
            }
        },
    )
}

@Composable
private fun ThreadActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = label, color = tint)
        }
    }
}

/** Same relative-date rules as the chat list rows. */
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
