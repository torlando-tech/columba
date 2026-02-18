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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextSnippet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
 *
 * @param prefilledBase32Key Optional Base32-encoded identity key from a share intent
 *        (e.g., Sideband "Send key to other app"). When provided, the paste key dialog
 *        is shown immediately with the key pre-populated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityManagerScreen(
    onNavigateBack: () -> Unit,
    prefilledBase32Key: String? = null,
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
    var showPasteKeyDialog by remember { mutableStateOf(false) }
    var pasteKeyInitialValue by remember { mutableStateOf("") }
    var showExportTextDialog by remember { mutableStateOf<String?>(null) }
    var showImportTypeDialog by remember { mutableStateOf(false) }
    var showBackupImportDialog by remember { mutableStateOf(false) }
    var selectedBackupUri by remember { mutableStateOf<Uri?>(null) }

    // File picker for raw identity import
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                selectedImportUri = it
                showImportDialog = true
            }
        }

    // File picker for Sideband backup import
    val backupImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                selectedBackupUri = it
                showBackupImportDialog = true
            }
        }

    // SAF file save launcher for identity export
    val exportSaveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { destinationUri: Uri? ->
            destinationUri?.let {
                viewModel.saveExportedIdentityToFile(it)
            }
        }

    // Auto-show paste dialog when navigated with a pre-filled key
    LaunchedEffect(prefilledBase32Key) {
        if (prefilledBase32Key != null) {
            pasteKeyInitialValue = prefilledBase32Key
            showPasteKeyDialog = true
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
            }
            is IdentityManagerUiState.ExportReady -> {
                // Launch SAF file save dialog for binary export
                exportSaveLauncher.launch("identity.rnsidentity")
                viewModel.resetUiState()
            }
            is IdentityManagerUiState.ExportTextReady -> {
                // Show the Base32 text export dialog
                showExportTextDialog = state.base32String
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
                    // Paste key button
                    IconButton(onClick = {
                        pasteKeyInitialValue = ""
                        showPasteKeyDialog = true
                    }) {
                        Icon(Icons.Default.ContentPaste, "Paste key")
                    }
                    // Import identity button
                    IconButton(onClick = { showImportTypeDialog = true }) {
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
                    onImportClick = { showImportTypeDialog = true },
                    onPasteKeyClick = {
                        pasteKeyInitialValue = ""
                        showPasteKeyDialog = true
                    },
                )
            } else {
                IdentityList(
                    identities = identities,
                    activeIdentity = activeIdentity,
                    onSwitchClick = { showSwitchDialog = it },
                    onRenameClick = { showRenameDialog = it },
                    onExportFileClick = { viewModel.exportIdentity(it.identityHash, it.filePath) },
                    onExportTextClick = {
                        viewModel.exportIdentityAsText(it.identityHash, it.filePath)
                    },
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

    if (showPasteKeyDialog) {
        PasteKeyDialog(
            initialKey = pasteKeyInitialValue,
            onDismiss = {
                showPasteKeyDialog = false
                pasteKeyInitialValue = ""
            },
            onImport = { base32Text, displayName ->
                viewModel.importIdentityFromBase32(base32Text, displayName)
                showPasteKeyDialog = false
                pasteKeyInitialValue = ""
            },
        )
    }

    showExportTextDialog?.let { base32String ->
        ExportTextDialog(
            base32String = base32String,
            onDismiss = { showExportTextDialog = null },
        )
    }

    if (showImportTypeDialog) {
        ImportTypeDialog(
            onDismiss = { showImportTypeDialog = false },
            onImportFromFile = {
                showImportTypeDialog = false
                importLauncher.launch("*/*")
            },
            onImportFromBackup = {
                showImportTypeDialog = false
                backupImportLauncher.launch("*/*")
            },
            onPasteKey = {
                showImportTypeDialog = false
                pasteKeyInitialValue = ""
                showPasteKeyDialog = true
            },
        )
    }

    if (showBackupImportDialog && selectedBackupUri != null) {
        ImportIdentityDialog(
            title = "Import from Sideband Backup",
            description = "Enter a display name for the identity extracted from the Sideband backup.",
            onDismiss = {
                showBackupImportDialog = false
                selectedBackupUri = null
            },
            onImport = { displayName ->
                selectedBackupUri?.let { uri ->
                    viewModel.importIdentityFromBackup(uri, displayName)
                }
                showBackupImportDialog = false
                selectedBackupUri = null
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
    onPasteKeyClick: () -> Unit,
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
            OutlinedButton(onClick = onPasteKeyClick) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Paste Key")
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
    onExportFileClick: (LocalIdentityEntity) -> Unit,
    onExportTextClick: (LocalIdentityEntity) -> Unit,
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
                onExportFileClick = { onExportFileClick(identity) },
                onExportTextClick = { onExportTextClick(identity) },
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
    onExportFileClick: () -> Unit,
    onExportTextClick: () -> Unit,
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
                        text = { Text("Export as File") },
                        onClick = {
                            showMenu = false
                            onExportFileClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Upload, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy Key as Text") },
                        onClick = {
                            showMenu = false
                            onExportTextClick()
                        },
                        leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
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
    title: String = "Import Identity",
    description: String = "This will restore the identity from the backup file you selected.",
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a display name for the imported identity.")
                Text(
                    description,
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

/**
 * Dialog for importing an identity by pasting a Base32-encoded key.
 *
 * Used for:
 * - Manual paste from clipboard
 * - Auto-populated from Sideband share intent
 */
@Composable
private fun PasteKeyDialog(
    initialKey: String,
    onDismiss: () -> Unit,
    onImport: (base32Text: String, displayName: String) -> Unit,
) {
    var keyText by remember { mutableStateOf(initialKey) }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContentPaste, null) },
        title = { Text("Import Key from Text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a Base32-encoded identity key (e.g., from Sideband).")
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Base32 Key") },
                    placeholder = { Text("Paste identity key here...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g., Sideband Identity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(keyText, displayName) },
                enabled = keyText.isNotBlank() && displayName.isNotBlank(),
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

/**
 * Dialog showing a Base32-encoded identity key with copy and share options.
 */
@Composable
private fun ExportTextDialog(
    base32String: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text("Identity Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This key can be imported in Sideband or another Columba instance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = base32String,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(base32String))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = {
                            val shareIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, base32String)
                                }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share Identity Key"),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("DONE")
            }
        },
    )
}

/**
 * Dialog to choose import type: raw identity file, Sideband backup, or paste key.
 */
@Composable
private fun ImportTypeDialog(
    onDismiss: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromBackup: () -> Unit,
    onPasteKey: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text("Import Identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose how to import your identity:")
                Button(
                    onClick = onImportFromFile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("From Identity File")
                }
                Button(
                    onClick = onImportFromBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("From Sideband Backup")
                }
                Button(
                    onClick = onPasteKey,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste Base32 Key")
                }
            }
        },
        confirmButton = {},
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
                Text("This action cannot be undone", style = MaterialTheme.typography.titleSmall)
                Text("Deleting \"${identity.displayName}\" will permanently remove:")
                Text("- Identity keys")
                Text("- All conversations")
                Text("- All contacts")
                Text("- All messages")
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
