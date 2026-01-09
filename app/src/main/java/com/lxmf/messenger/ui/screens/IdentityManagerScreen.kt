package com.lxmf.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.viewmodel.IdentityManagerUiState
import com.lxmf.messenger.viewmodel.IdentityManagerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Identity Manager screen for creating, managing, and switching identities.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: IdentityManagerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val identities by viewModel.identities.collectAsState()
    val activeIdentity by viewModel.activeIdentity.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSwitchDialog by remember { mutableStateOf<LocalIdentityEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<LocalIdentityEntity?>(null) }
    var showRenameDialog by remember { mutableStateOf<LocalIdentityEntity?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedImportUri by remember { mutableStateOf<Uri?>(null) }

    // File picker for import
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                selectedImportUri = it
                showImportDialog = true
            }
        }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle UI state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is IdentityManagerUiState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetUiState()
            }
            is IdentityManagerUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetUiState()
            }
            is IdentityManagerUiState.RequiresRestart -> {
                // No longer used - identity switch now restarts service without app restart
                // Kept for backwards compatibility, but should not be reached
            }
            is IdentityManagerUiState.ExportReady -> {
                // Launch share sheet
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, state.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(Intent.createChooser(shareIntent, "Export Identity"))
                viewModel.resetUiState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Navigate back")
                    }
                },
                actions = {
                    // Import identity button
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Download, "Import identity")
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                // Above bottom nav bar
                modifier = Modifier.padding(bottom = 80.dp),
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Always show content (don't replace with loading state)
            if (identities.isEmpty()) {
                EmptyState(
                    onCreateClick = { showCreateDialog = true },
                    onImportClick = { importLauncher.launch("*/*") },
                )
            } else {
                IdentityList(
                    identities = identities,
                    activeIdentity = activeIdentity,
                    onSwitchClick = { showSwitchDialog = it },
                    onRenameClick = { showRenameDialog = it },
                    onExportClick = { viewModel.exportIdentity(it.identityHash, it.filePath) },
                    onDeleteClick = { showDeleteDialog = it },
                )
            }

            // Floating Action Button positioned above bottom navigation
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 88.dp),
                // 88dp = bottom nav height + padding
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, "Create identity", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    // Show blocking dialog when loading (similar to interface changes dialog)
    if (uiState is IdentityManagerUiState.Loading) {
        ApplyingChangesDialog((uiState as IdentityManagerUiState.Loading).message)
    }

    // Dialogs
    if (showCreateDialog) {
        CreateIdentityDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { displayName ->
                viewModel.createNewIdentity(displayName)
                showCreateDialog = false
            },
        )
    }

    showSwitchDialog?.let { identity ->
        SwitchIdentityDialog(
            identity = identity,
            currentIdentity = activeIdentity,
            onDismiss = { showSwitchDialog = null },
            onConfirm = {
                viewModel.switchToIdentity(identity.identityHash)
                showSwitchDialog = null
            },
        )
    }

    showDeleteDialog?.let { identity ->
        DeleteIdentityDialog(
            identity = identity,
            onDismiss = { showDeleteDialog = null },
            onConfirm = {
                viewModel.deleteIdentity(identity.identityHash)
                showDeleteDialog = null
            },
        )
    }

    showRenameDialog?.let { identity ->
        RenameIdentityDialog(
            identity = identity,
            onDismiss = { showRenameDialog = null },
            onRename = { newName ->
                viewModel.renameIdentity(identity.identityHash, newName)
                showRenameDialog = null
            },
        )
    }

    if (showImportDialog && selectedImportUri != null) {
        ImportIdentityDialog(
            onDismiss = {
                showImportDialog = false
                selectedImportUri = null
            },
            onImport = { displayName ->
                selectedImportUri?.let { uri ->
                    viewModel.importIdentity(uri, displayName)
                }
                showImportDialog = false
                selectedImportUri = null
            },
        )
    }
}

/**
 * Blocking dialog shown while identity operations are in progress.
 * Similar to ApplyChangesDialog in InterfaceManagementScreen.
 */
@Composable
private fun ApplyingChangesDialog(message: String) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss - blocking */ },
        icon = {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        },
        title = { Text(message) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Restarting network service...",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This may take a few seconds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { /* No buttons - blocking */ },
    )
}

@Composable
private fun EmptyState(
    onCreateClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "No Identities Yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Create your first identity to start using Columba, or import an existing identity from a backup file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create New Identity")
            }
            OutlinedButton(onClick = onImportClick) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import from File")
            }
        }
    }
}

@Composable
private fun IdentityList(
    identities: List<LocalIdentityEntity>,
    activeIdentity: LocalIdentityEntity?,
    onSwitchClick: (LocalIdentityEntity) -> Unit,
    onRenameClick: (LocalIdentityEntity) -> Unit,
    onExportClick: (LocalIdentityEntity) -> Unit,
    onDeleteClick: (LocalIdentityEntity) -> Unit,
) {
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                // Extra padding for FAB, bottom nav, and dropdown menu
                bottom = 160.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(identities, key = { it.identityHash }) { identity ->
            IdentityCard(
                identity = identity,
                isActive = identity.identityHash == activeIdentity?.identityHash,
                onSwitchClick = { onSwitchClick(identity) },
                onRenameClick = { onRenameClick(identity) },
                onExportClick = { onExportClick(identity) },
                onDeleteClick = { onDeleteClick(identity) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityCard(
    identity: LocalIdentityEntity,
    isActive: Boolean,
    onSwitchClick: () -> Unit,
    onRenameClick: () -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!isActive) onSwitchClick() },
        elevation =
            CardDefaults.elevatedCardElevation(
                defaultElevation = if (isActive) 4.dp else 2.dp,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (isActive) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column {
                    Text(
                        identity.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        identity.identityHash,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (identity.lastUsedTimestamp > 0) {
                            "Last used: ${formatTimestamp(identity.lastUsedTimestamp)}"
                        } else {
                            "Never used"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (!isActive) {
                        DropdownMenuItem(
                            text = { Text("Switch to") },
                            onClick = {
                                showMenu = false
                                onSwitchClick()
                            },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            showMenu = false
                            onRenameClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        onClick = {
                            showMenu = false
                            onExportClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Upload, null) },
                    )
                    if (!isActive) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateIdentityDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a display name for this identity. You can use this to manage different personas or use cases.")
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g., Work, Personal, Anonymous") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(displayName) },
                enabled = displayName.isNotBlank(),
            ) {
                Text("CREATE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
    )
}

@Composable
private fun ImportIdentityDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text("Import Identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a display name for the imported identity.")
                Text(
                    "This will restore the identity from the backup file you selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g., Backup, Restored Identity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(displayName) },
                enabled = displayName.isNotBlank(),
            ) {
                Text("IMPORT")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
    )
}

@Composable
private fun SwitchIdentityDialog(
    identity: LocalIdentityEntity,
    currentIdentity: LocalIdentityEntity?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SwapHoriz, null) },
        title = { Text("Switch Identity?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Switching to \"${identity.displayName}\" will restart the network service.")
                Text("You will see only the conversations, contacts, and messages associated with this identity.")
                Spacer(Modifier.height(8.dp))
                Text("Current: ${currentIdentity?.displayName ?: "None"}", style = MaterialTheme.typography.bodySmall)
                Text("New: ${identity.displayName}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("SWITCH")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
    )
}

@Composable
private fun DeleteIdentityDialog(
    identity: LocalIdentityEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete Identity?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠️ This action cannot be undone", style = MaterialTheme.typography.titleSmall)
                Text("Deleting \"${identity.displayName}\" will permanently remove:")
                Text("• Identity keys")
                Text("• All conversations")
                Text("• All contacts")
                Text("• All messages")
                Spacer(Modifier.height(8.dp))
                Text("Export this identity first if you want to back it up.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("DELETE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
    )
}

@Composable
private fun RenameIdentityDialog(
    identity: LocalIdentityEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(identity.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a new display name:")
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Identity: ${identity.identityHash.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(newName) },
                enabled = newName.isNotBlank() && newName != identity.displayName,
            ) {
                Text("SAVE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000} minutes ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        diff < 604800_000 -> "${diff / 86400_000} days ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
