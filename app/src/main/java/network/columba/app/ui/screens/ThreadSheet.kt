package network.columba.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import network.columba.app.R
import network.columba.app.ui.components.ThreadRenameDialog
import network.columba.app.ui.model.ThreadRowUi
import network.columba.app.ui.model.ThreadSheetUiState

/** Expandable pinned sections of the sheet (spec §5.2 item 4). */
private enum class ThreadSection { DORMANT, ARCHIVED }

/**
 * Threads sheet (threads-spec §5.2-§5.4): the single thread control surface,
 * opened from the header pill as a full-height sheet. The partial anchor is
 * skipped: it would clip the list, the pinned sections and the undo snackbar
 * below the screen, and every one of them must stay reachable.
 *
 * Contents: search (title filter), pinned Main row, the active list, a pinned
 * `Dormant · N` / `Archived · N` footer expanded in place, swipe-to-archive
 * with an in-sheet undo snackbar, and long-press selection mode with batch
 * archive. All of it is local-only: titles and archive state never touch the
 * wire.
 *
 * The sheet is stateless with respect to thread data — the hosting screen
 * supplies [ThreadSheetUiState] from #1224's `ThreadUiModels` projection and
 * owns the callbacks; only presentation state (query, selection, expanded
 * section, open dialog) lives here.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ThreadSheet(
    state: ThreadSheetUiState,
    onDismiss: () -> Unit,
    onThreadClick: (threadIdHex: String) -> Unit,
    onMainClick: () -> Unit,
    onCreateThread: () -> Unit,
    onRename: (threadIdHex: String, newTitle: String?) -> Unit,
    onMarkRead: (threadIdHex: String) -> Unit,
    onArchive: (threadIdHexes: List<String>) -> Unit,
    onUnarchive: (threadIdHexes: List<String>) -> Unit,
    onReopenThread: (threadIdHex: String) -> Unit,
    onDelete: (threadIdHex: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedSection by remember { mutableStateOf<ThreadSection?>(null) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    val rowsById = remember(state.active, state.dormant, state.archived) {
        state.allRows.associateBy { it.threadIdHex }
    }
    val archivedIds = remember(state.archived) { state.archived.map { it.threadIdHex }.toSet() }
    val trimmedQuery = query.trim()
    val matchesQuery: (ThreadRowUi) -> Boolean = { row ->
        trimmedQuery.isEmpty() ||
            (row.displayTitle ?: context.getString(R.string.thread_untitled))
                .contains(trimmedQuery, ignoreCase = true)
    }

    /**
     * Archive/un-archive commit shared by the swipe, the row menu and the
     * selection bar: one repository call per batch, one snackbar, one undo.
     * The host switches the message target away from an archived open thread
     * before the archive lands, so the list never flashes Main (spec §5.3).
     */
    fun commit(rows: List<ThreadRowUi>, archive: Boolean) {
        if (rows.isEmpty()) return
        val ids = rows.map { it.threadIdHex }
        val wasCurrent = state.openTargetKnown && rows.any { it.threadIdHex == state.currentThreadIdHex }
        if (archive) onArchive(ids) else onUnarchive(ids)
        if (selection.isNotEmpty()) selection = selection - ids.toSet()
        val message =
            if (rows.size == 1) {
                val title = rows.first().displayTitle ?: context.getString(R.string.thread_untitled)
                val template =
                    if (archive) {
                        R.string.thread_archive_snackbar_single
                    } else {
                        R.string.thread_unarchive_snackbar_single
                    }
                context.getString(template, title)
            } else {
                val template =
                    if (archive) {
                        R.plurals.thread_archive_snackbar_batch
                    } else {
                        R.plurals.thread_unarchive_snackbar_batch
                    }
                context.resources.getQuantityString(template, rows.size, rows.size)
            }
        val reopenId = rows.singleOrNull()?.threadIdHex?.takeIf { wasCurrent }
        scope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = context.getString(R.string.thread_action_undo),
                    // Undo is the only way back from a batch archive, and the
                    // sheet's list plus the scrim leave the action easy to
                    // miss in four seconds: M3 wants an action snackbar to
                    // stay long enough to be used.
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) {
                if (archive) {
                    onUnarchive(ids)
                    if (reopenId != null) onReopenThread(reopenId)
                } else {
                    onArchive(ids)
                }
            }
        }
    }

    val archiveLabel = stringResource(R.string.thread_action_archive)
    val unarchiveLabel = stringResource(R.string.thread_action_unarchive)
    val markReadLabel = stringResource(R.string.thread_action_mark_read)
    val renameLabel = stringResource(R.string.thread_action_rename)
    val deleteLabel = stringResource(R.string.thread_action_delete)

    val renameTarget = renameTargetId?.let(rowsById::get)
    val deleteTarget = deleteTargetId?.let(rowsById::get)

    val rowComposable: @Composable (ThreadRowUi, Boolean, Modifier) -> Unit = { row, archivedSection, modifier ->
        ThreadSheetRowItem(
            row = row,
            modifier = modifier,
            selectionMode = selection.isNotEmpty(),
            checked = row.threadIdHex in selection,
            isCurrent = state.openTargetKnown && state.currentThreadIdHex == row.threadIdHex,
            archivedSection = archivedSection,
            onOpen = {
                onThreadClick(row.threadIdHex)
                onDismiss()
            },
            onToggleSelection = { selection = selection.toggle(row.threadIdHex) },
            onArchiveAction = { commit(listOf(row), archive = true) },
            onUnarchiveAction = { commit(listOf(row), archive = false) },
            onMarkRead = { onMarkRead(row.threadIdHex) },
            onRename = { renameTargetId = row.threadIdHex },
            onDelete = { deleteTargetId = row.threadIdHex },
            archiveLabel = archiveLabel,
            unarchiveLabel = unarchiveLabel,
            markReadLabel = markReadLabel,
            renameLabel = renameLabel,
            deleteLabel = deleteLabel,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The header replaces the drag handle; the sheet opens full height.
        dragHandle = null,
    ) {
        // Back leaves selection mode without dismissing the sheet (spec
        // §5.4), so it must not reach the sheet's own dismiss handler. The
        // handler lives in the sheet's content because the last registered
        // enabled callback wins: ModalBottomSheet's dismiss handler is
        // registered with the dialog, while a handler in ThreadSheet's body
        // (composed before the dialog) is registered earlier and loses.
        BackHandler(enabled = selection.isNotEmpty()) {
            selection = emptySet()
        }
        // Taps on the sheet's blank area (between the list and the pinned
        // sections) must not reach the scrim behind it: an unhandled tap would
        // dismiss the sheet. Child gestures consume first, so rows, swipe and
        // scrolling are unaffected.
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .pointerInput(Unit) { detectTapGestures { } },
        ) {
            when {
                selection.isNotEmpty() ->
                    ThreadSelectionBar(
                        count = selection.size,
                        singleSelectedRow = selection.singleOrNull()?.let(rowsById::get),
                        onExit = { selection = emptySet() },
                        onArchive = {
                            commit(state.allRows.filter { it.threadIdHex in selection }, archive = true)
                        },
                        onRename = { row ->
                            selection = emptySet()
                            renameTargetId = row.threadIdHex
                        },
                        onMarkRead = { row ->
                            selection = emptySet()
                            onMarkRead(row.threadIdHex)
                        },
                        renameLabel = renameLabel,
                        markReadLabel = markReadLabel,
                    )

                else ->
                    ThreadSheetHeader(
                        onClose = onDismiss,
                        onCreateThread = onCreateThread,
                    )
            }

            if (selection.isEmpty()) {
                ThreadSearchField(query = query, onQueryChange = { query = it })
            }

            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (trimmedQuery.isNotEmpty()) {
                    val results = state.active.filter(matchesQuery) + state.dormant.filter(matchesQuery) +
                        state.archived.filter(matchesQuery)
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        if (results.isEmpty()) {
                            item(key = "no-results") {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SearchOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.thread_search_no_results, trimmedQuery),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            items(results, key = { it.threadIdHex }) { row ->
                                rowComposable(row, row.threadIdHex in archivedIds, Modifier.animateItem())
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item(key = "main-row") {
                            ThreadSheetMainRow(
                                main = state.main,
                                isCurrent = state.openTargetKnown && state.currentThreadIdHex == null,
                                onOpen = {
                                    onMainClick()
                                    onDismiss()
                                },
                            )
                        }
                        if (state.active.isEmpty()) {
                            item(key = "active-empty") {
                                Text(
                                    text =
                                        if (state.isEmpty) {
                                            stringResource(R.string.thread_sheet_empty)
                                        } else {
                                            stringResource(R.string.thread_sheet_active_empty)
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                )
                            }
                        } else {
                            item(key = "active-header") {
                                ThreadSheetSectionHeader(stringResource(R.string.thread_sheet_section_active))
                            }
                            items(state.active, key = { it.threadIdHex }) { row ->
                                rowComposable(row, false, Modifier.animateItem())
                            }
                        }
                    }

                    val sectionRows =
                        when (expandedSection) {
                            ThreadSection.DORMANT -> state.dormant
                            ThreadSection.ARCHIVED -> state.archived
                            null -> null
                        }
                    // Keep the last non-empty rows while the section shrinks, so
                    // the collapse animates instead of the content vanishing on
                    // the same frame the section is collapsed (spec §5.5).
                    var lastSectionRows by remember { mutableStateOf<List<ThreadRowUi>>(emptyList()) }
                    LaunchedEffect(sectionRows) {
                        if (!sectionRows.isNullOrEmpty()) lastSectionRows = sectionRows
                    }
                    AnimatedVisibility(
                        visible = !sectionRows.isNullOrEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        val rows = sectionRows ?: lastSectionRows
                        if (rows.isNotEmpty()) {
                            Column {
                                HorizontalDivider()
                                LazyColumn(modifier = Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
                                    items(rows, key = { it.threadIdHex }) { row ->
                                        rowComposable(row, expandedSection == ThreadSection.ARCHIVED, Modifier.animateItem())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The snackbar lives in the sheet scaffold so a modal sheet can
            // never hide an undo (spec §5.5). Polite live region: the archive
            // result is announced without stealing focus (issue 07).
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )

            HorizontalDivider()
            if (state.dormant.isNotEmpty()) {
                ThreadSectionFooterRow(
                    title = stringResource(R.string.thread_sheet_section_dormant),
                    count = state.dormant.size,
                    expanded = expandedSection == ThreadSection.DORMANT,
                    onClick = {
                        expandedSection =
                            if (expandedSection == ThreadSection.DORMANT) null else ThreadSection.DORMANT
                    },
                )
            }
            if (state.archived.isNotEmpty()) {
                ThreadSectionFooterRow(
                    title = stringResource(R.string.thread_sheet_section_archived),
                    count = state.archived.size,
                    expanded = expandedSection == ThreadSection.ARCHIVED,
                    onClick = {
                        expandedSection =
                            if (expandedSection == ThreadSection.ARCHIVED) null else ThreadSection.ARCHIVED
                    },
                )
            }
        }
    }

    if (renameTarget != null) {
        ThreadRenameDialog(
            initialTitle = renameTarget.title.orEmpty(),
            onConfirm = { newTitle ->
                onRename(renameTarget.threadIdHex, newTitle)
                renameTargetId = null
            },
            onResetToAutomatic = {
                onRename(renameTarget.threadIdHex, null)
                renameTargetId = null
            },
            onDismiss = { renameTargetId = null },
        )
    }

    if (deleteTarget != null) {
        ThreadDeleteConfirmDialog(
            onConfirm = {
                onDelete(deleteTarget.threadIdHex)
                deleteTargetId = null
            },
            onDismiss = { deleteTargetId = null },
        )
    }
}

/** Actions a thread row's trailing `⋮` menu offers (built by the sheet). */
internal data class ThreadRowMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** One row wired to the sheet callbacks (active list, search results, pinned sections). */
@Composable
private fun ThreadSheetRowItem(
    row: ThreadRowUi,
    modifier: Modifier = Modifier,
    selectionMode: Boolean,
    checked: Boolean,
    isCurrent: Boolean,
    archivedSection: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onArchiveAction: () -> Unit,
    onUnarchiveAction: () -> Unit,
    onMarkRead: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    archiveLabel: String,
    unarchiveLabel: String,
    markReadLabel: String,
    renameLabel: String,
    deleteLabel: String,
) {
    val actions =
        buildList {
            add(ThreadRowMenuAction(renameLabel, ThreadRenameIcon, onClick = onRename))
            if (row.unreadCount > 0) {
                add(ThreadRowMenuAction(markReadLabel, ThreadMarkReadIcon, onClick = onMarkRead))
            }
            if (archivedSection) {
                add(ThreadRowMenuAction(unarchiveLabel, ThreadUnarchiveIcon, onClick = onUnarchiveAction))
            } else {
                add(ThreadRowMenuAction(archiveLabel, ThreadArchiveIcon, onClick = onArchiveAction))
            }
            add(ThreadRowMenuAction(deleteLabel, ThreadDeleteIcon, destructive = true, onClick = onDelete))
        }

    ThreadSheetRow(
        row = row,
        modifier = modifier,
        isCurrent = isCurrent,
        selectionMode = selectionMode,
        checked = checked,
        archived = archivedSection,
        archiveLabel = if (archivedSection) unarchiveLabel else archiveLabel,
        archiveIcon = if (archivedSection) ThreadUnarchiveIcon else ThreadArchiveIcon,
        menuActions = actions,
        onOpen = onOpen,
        onToggleSelection = onToggleSelection,
        onArchiveSwipe = if (archivedSection) onUnarchiveAction else onArchiveAction,
    )
}

/**
 * Sheet header (spec §5.2 item 1): `Threads`, close and `+ New`. The sheet is
 * a full-height management surface — the partial anchor would push the list,
 * the pinned sections and the undo snackbar below the screen.
 */
@Composable
private fun ThreadSheetHeader(
    onClose: () -> Unit,
    onCreateThread: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.thread_sheet_close),
            )
        }
        Text(
            text = stringResource(R.string.thread_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        IconButton(onClick = onCreateThread) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.thread_sheet_new_thread),
            )
        }
    }
}

/** Search field; filters thread titles (case-insensitive, leading/trailing blanks ignored). */
@Composable
private fun ThreadSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.thread_search_clear),
                    )
                }
            }
        },
        placeholder = { Text(stringResource(R.string.thread_search_hint)) },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * Selection-mode contextual bar (spec §5.3 / issue 05): exit, count,
 * `Archive` for the batch, and a `⋮` overflow for the single-selection
 * rename / mark-read actions.
 */
@Composable
private fun ThreadSelectionBar(
    count: Int,
    singleSelectedRow: ThreadRowUi?,
    onExit: () -> Unit,
    onArchive: () -> Unit,
    onRename: (ThreadRowUi) -> Unit,
    onMarkRead: (ThreadRowUi) -> Unit,
    renameLabel: String,
    markReadLabel: String,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.thread_selection_exit),
            )
        }
        Text(
            text = pluralStringResource(R.plurals.thread_selected_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    // The count is the only progress signal in selection mode;
                    // announce it when it changes (issue 07).
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = onArchive, enabled = count > 0) {
            Icon(
                imageVector = ThreadArchiveIcon,
                contentDescription = stringResource(R.string.thread_action_archive),
            )
        }
        val single = singleSelectedRow
        if (single != null) {
            Box(modifier = Modifier) {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.thread_more_actions),
                    )
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(ThreadRenameIcon, contentDescription = null) },
                        text = { Text(renameLabel) },
                        onClick = {
                            overflowOpen = false
                            onRename(single)
                        },
                    )
                    if (single.unreadCount > 0) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(ThreadMarkReadIcon, contentDescription = null) },
                            text = { Text(markReadLabel) },
                            onClick = {
                                overflowOpen = false
                                onMarkRead(single)
                            },
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

/** Adds/removes one id from the selection set. */
private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
