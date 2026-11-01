package network.columba.app.ui.screens

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.R
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.viewmodel.IdentityManagerUiState
import network.columba.app.viewmodel.IdentityManagerViewModel
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
                snackbarHostState.showSnackbar(state.message.resolve(context))
                viewModel.resetUiState()
            }
            is IdentityManagerUiState.Error -> {
                snackbarHostState.showSnackbar(state.message.resolve(context))
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
                title = { Text(stringResource(R.string.identity_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.identity_navigate_back))
                    }
                },
                actions = {
                    // Paste key button
                    IconButton(onClick = {
                        pasteKeyInitialValue = ""
                        showPasteKeyDialog = true
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.identity_paste_key_cd))
                    }
                    // Import identity button
                    IconButton(onClick = { showImportTypeDialog = true }) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.identity_import_identity_cd))
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.identity_create_identity_cd), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    // Show blocking dialog when loading (similar to interface changes dialog)
    if (uiState is IdentityManagerUiState.Loading) {
        ApplyingChangesDialog((uiState as IdentityManagerUiState.Loading).message.resolve(context))
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
            title = stringResource(R.string.identity_import_backup_title),
            description = stringResource(R.string.identity_import_backup_enter_name),
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
                    stringResource(R.string.identity_restarting_network_service),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.identity_may_take_few_seconds),
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
                stringResource(R.string.identity_no_identities_yet),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.identity_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.identity_create_new_identity))
            }
            OutlinedButton(onClick = onImportClick) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.identity_import_from_file))
            }
            OutlinedButton(onClick = onPasteKeyClick) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.identity_paste_key))
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
        // Deduplicate defensively to prevent LazyColumn key crash (#542)
        items(identities.distinctBy { it.identityHash }, key = { it.identityHash }) { identity ->
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
    val context = LocalContext.current
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
                        contentDescription = stringResource(R.string.identity_active),
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
                            stringResource(R.string.identity_last_used, formatTimestamp(context, identity.lastUsedTimestamp))
                        } else {
                            stringResource(R.string.identity_never_used)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.identity_more_options))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (!isActive) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_switch_to)) },
                            onClick = {
                                showMenu = false
                                onSwitchClick()
                            },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.identity_rename)) },
                        onClick = {
                            showMenu = false
                            onRenameClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.identity_export_as_file)) },
                        onClick = {
                            showMenu = false
                            onExportFileClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Upload, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.identity_copy_key_as_text)) },
                        onClick = {
                            showMenu = false
                            onExportTextClick()
                        },
                        leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
                    )
                    if (!isActive) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
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
        title = { Text(stringResource(R.string.identity_create_new_identity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_create_dialog_body))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.identity_display_name)) },
                    placeholder = { Text(stringResource(R.string.identity_create_placeholder)) },
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
                Text(stringResource(R.string.identity_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
            }
        },
    )
}

@Composable
private fun ImportIdentityDialog(
    title: String = stringResource(R.string.identity_import_title),
    description: String = stringResource(R.string.identity_import_dialog_desc),
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
                Text(stringResource(R.string.identity_import_enter_name))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.identity_display_name)) },
                    placeholder = { Text(stringResource(R.string.identity_import_placeholder)) },
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
                Text(stringResource(R.string.identity_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
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
        title = { Text(stringResource(R.string.identity_import_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_paste_key_desc))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.identity_base32_key_label)) },
                    placeholder = { Text(stringResource(R.string.identity_paste_key_placeholder)) },
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
                    label = { Text(stringResource(R.string.identity_display_name)) },
                    placeholder = { Text(stringResource(R.string.identity_paste_name_placeholder)) },
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
                Text(stringResource(R.string.identity_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
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
        title = { Text(stringResource(R.string.identity_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.identity_key_export_desc),
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
                        Text(stringResource(R.string.identity_copy))
                    }
                    OutlinedButton(
                        onClick = {
                            val shareIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, base32String)
                                }
                            context.startActivity(
                                Intent.createChooser(shareIntent, context.getString(R.string.identity_share_key_chooser)),
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
                        Text(stringResource(R.string.identity_share))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_done_action))
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
        title = { Text(stringResource(R.string.identity_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_import_choose))
                Button(
                    onClick = onImportFromFile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.identity_from_identity_file))
                }
                Button(
                    onClick = onImportFromBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.identity_from_sideband_backup))
                }
                Button(
                    onClick = onPasteKey,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.identity_paste_base32_key))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
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
        title = { Text(stringResource(R.string.identity_switch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_switch_body, identity.displayName))
                Text(stringResource(R.string.identity_switch_note))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.identity_switch_current, currentIdentity?.displayName ?: stringResource(R.string.identity_none)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.identity_switch_new, identity.displayName), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.identity_switch_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
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
        title = { Text(stringResource(R.string.identity_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_delete_cannot_undo), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.identity_delete_body, identity.displayName))
                Text(stringResource(R.string.identity_delete_item_keys))
                Text(stringResource(R.string.identity_delete_item_conversations))
                Text(stringResource(R.string.identity_delete_item_contacts))
                Text(stringResource(R.string.identity_delete_item_messages))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.identity_delete_export_hint), style = MaterialTheme.typography.bodySmall)
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
                Text(stringResource(R.string.identity_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
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
        title = { Text(stringResource(R.string.identity_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.identity_rename_enter))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.identity_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.identity_rename_hash, identity.identityHash.take(16)),
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
                Text(stringResource(R.string.identity_save_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel_action))
            }
        },
    )
}

private fun formatTimestamp(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> context.getString(R.string.time_relative_just_now)
        diff < 3600_000 -> context.getString(R.string.identity_time_minutes_ago, diff / 60_000)
        diff < 86400_000 -> context.getString(R.string.identity_time_hours_ago, diff / 3600_000)
        diff < 604800_000 -> context.getString(R.string.identity_time_days_ago, diff / 86400_000)
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
