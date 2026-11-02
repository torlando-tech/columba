package network.columba.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.model.ThreadMainRowUi
import network.columba.app.ui.model.ThreadRowUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-sheet rows (threads-spec §5.2/§5.3/§5.4).
 *
 * A thread row carries the swipe-to-archive affordance, the selection
 * checkbox, the unread badge and the trailing `⋮` action menu. The [Main]
 * row is a plain selectable row: Main is not a `threads` row, so it can be
 * neither archived (spec D6) nor renamed.
 */

/**
 * One thread row.
 *
 * [onArchiveLabel] doubles as the swipe panel label and as the TalkBack custom
 * action, so the gesture and its accessible equivalent can never diverge
 * (swipe is inaccessible without it — threads-spec §5.3).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreadSheetRow(
    row: ThreadRowUi,
    isCurrent: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    archived: Boolean,
    archiveLabel: String,
    archiveIcon: ImageVector,
    menuActions: List<ThreadRowMenuAction>,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onArchiveSwipe: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // confirmValueChange is consulted for every target change of the settle
    // animation, so a single gesture can report EndToStart more than once.
    // Fire the callback once per gesture and snap the row back: the host
    // removes archived rows, and an ignored callback must not leave the row
    // stuck in the dismissed state. The guard is also keyed to the row's
    // archive state so a row that survives the action (a search result, or the
    // Archived section) can be swiped again after the state flips.
    var archiveFired by remember(archived) { mutableStateOf(false) }
    val swipeState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart && !archiveFired) {
                    archiveFired = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onArchiveSwipe()
                }
                false
            },
        )

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        // A swipe during selection is almost always accidental (spec §5.3).
        gesturesEnabled = !selectionMode,
        backgroundContent = { SwipeArchivePanel(archiveLabel, archiveIcon) },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .semantics {
                        if (!selectionMode) {
                            customActions = listOf(CustomAccessibilityAction(archiveLabel) { onArchiveSwipe(); true })
                        }
                    }
                    .combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection()
                            } else {
                                onOpen()
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSelection()
                        },
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggleSelection() },
                )
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = row.displayTitle ?: stringResource(R.string.thread_untitled)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color =
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.thread_current_indicator),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = secondaryLine(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = relativeThreadTime(row.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.unreadCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                UnreadBadge(row.unreadCount)
            }
            if (!selectionMode && menuActions.isNotEmpty()) {
                ThreadRowMenu(menuActions)
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/** Pinned Main row: selectable, never archivable (spec D6), no `⋮`. */
@Composable
internal fun ThreadSheetMainRow(
    main: ThreadMainRowUi,
    isCurrent: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = null)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.thread_chip_main),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (main.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            if (main.preview.isNotEmpty()) {
                Text(
                    text = main.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (main.timestamp > 0L) {
            Text(
                text = relativeThreadTime(main.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (main.unreadCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            UnreadBadge(main.unreadCount, announce = true)
        }
    }
}

/** Trailing `⋮` menu; the row owns its expansion so the list keeps one state per row. */
@Composable
private fun ThreadRowMenu(actions: List<ThreadRowMenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.thread_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint =
                                if (action.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    },
                    text = {
                        Text(
                            text = action.label,
                            color =
                                if (action.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/** The revealed panel behind a swiped row. */
@Composable
private fun SwipeArchivePanel(
    label: String,
    icon: ImageVector,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 24.dp)
                // Revealed only while swiping; the row's custom accessibility
                // action is the spoken affordance, so the panel itself stays
                // decorative (its "Archive" label must not merge into the row).
                .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Pinned footer row: `Dormant · N` / `Archived · N` (spec §5.2 item 4). */
@Composable
internal fun ThreadSectionFooterRow(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = null)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Section label above a group of rows. */
@Composable
internal fun ThreadSheetSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Unread count badge, capped at `99+` like the chat list. */
@Composable
internal fun UnreadBadge(count: Int, announce: Boolean = false) {
    val description =
        if (announce) pluralStringResource(R.plurals.thread_unread_count, count, count) else null
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 7.dp, vertical = 1.dp)
                // The plain digits in a merged row read as a bare number; the
                // badge is announced only where no line already states the count.
                .clearAndSetSemantics {
                    if (description != null) contentDescription = description
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Secondary line: unread + time while unread, otherwise the last-message
 * preview (threads-spec §5.2 anatomy, matching the mockups).
 */
@Composable
private fun secondaryLine(row: ThreadRowUi): String {
    val time = relativeThreadTime(row.timestamp)
    if (row.unreadCount > 0) {
        return "${pluralStringResource(R.plurals.thread_unread_count, row.unreadCount, row.unreadCount)} · $time"
    }
    return row.preview.ifBlank { time }
}

/** Compact relative time used by sheet rows (`12m`, `2h`, `Tue`, `Mar 04`). */
@Composable
internal fun relativeThreadTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> stringResource(R.string.thread_time_now)
        diff < 3_600_000 -> stringResource(R.string.thread_time_minutes, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(R.string.thread_time_hours, (diff / 3_600_000).toInt())
        diff < 604_800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Shared dialog surface for the destructive thread delete (spec §5.3 keep-with-confirm). */
@Composable
internal fun ThreadDeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thread_delete_title)) },
        text = { Text(stringResource(R.string.thread_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.thread_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.thread_action_cancel))
            }
        },
    )
}

/** Icon set shared by the sheet's rename/delete menu items. */
internal val ThreadRenameIcon: ImageVector = Icons.Default.Edit
internal val ThreadDeleteIcon: ImageVector = Icons.Default.Delete
internal val ThreadArchiveIcon: ImageVector = Icons.Default.Archive
internal val ThreadUnarchiveIcon: ImageVector = Icons.Default.Unarchive
internal val ThreadMarkReadIcon: ImageVector = Icons.Default.MarkChatRead
