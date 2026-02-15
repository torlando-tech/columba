package com.lxmf.messenger.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.migration.ExportResult
import com.lxmf.messenger.migration.MigrationPreview
import com.lxmf.messenger.viewmodel.MigrationUiState
import com.lxmf.messenger.viewmodel.MigrationViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Migration screen for exporting and importing app data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(
    onNavigateBack: () -> Unit,
    onImportComplete: () -> Unit = {},
    viewModel: MigrationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val exportPreview by viewModel.exportPreview.collectAsState()
    val includeAttachments by viewModel.includeAttachments.collectAsState()

    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportPassword by remember { mutableStateOf<String?>(null) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }
    var pendingImportComplete by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            // Permission result received, now complete the import flow
            showNotificationPermissionDialog = false
            if (pendingImportComplete) {
                pendingImportComplete = false
                onImportComplete()
            }
        }

    // SAF file save launcher for data export
    val exportSaveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { destinationUri: Uri? ->
            destinationUri?.let {
                viewModel.saveExportToFile(context.contentResolver, it)
            }
        }

    // Check if notification permission is needed (Android 13+ with notifications enabled in settings)
    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PermissionChecker.PERMISSION_GRANTED

    // File picker for import
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                viewModel.previewImport(it)
            }
        }

    // Handle state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is MigrationUiState.ExportComplete -> {
                val timestamp =
                    SimpleDateFormat(
                        "yyyy-MM-dd_HHmmss",
                        Locale.US,
                    ).format(Date())
                exportSaveLauncher.launch("columba_export_$timestamp.columba")
                viewModel.onExportSaveDialogLaunched()
            }
            is MigrationUiState.ExportSaved -> {
                snackbarHostState.showSnackbar("Export saved successfully")
                viewModel.resetState()
            }
            is MigrationUiState.ImportPreview -> {
                pendingImportUri = state.fileUri
                pendingImportPassword = state.password
                showImportConfirmDialog = true
            }
            is MigrationUiState.PasswordRequired, is MigrationUiState.WrongPassword -> {
                // Handled by dialogs below
            }
            is MigrationUiState.ImportComplete -> {
                snackbarHostState.showSnackbar(
                    "Import complete! ${state.result.identitiesImported} identities, " +
                        "${state.result.messagesImported} messages, " +
                        "${state.result.announcesImported} announces, " +
                        "${state.result.interfacesImported} interfaces imported.",
                )
            }
            is MigrationUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Migration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Navigate back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Export Section
            ExportSection(
                exportPreview = exportPreview,
                uiState = uiState,
                exportProgress = exportProgress,
                includeAttachments = includeAttachments,
                onIncludeAttachmentsChange = { viewModel.setIncludeAttachments(it) },
                onExport = { showExportPasswordDialog = true },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Import Section
            ImportSection(
                uiState = uiState,
                importProgress = importProgress,
                onSelectFile = { importLauncher.launch("*/*") },
                onImportComplete = {
                    // After import, check if notification permission is needed
                    if (needsNotificationPermission()) {
                        pendingImportComplete = true
                        showNotificationPermissionDialog = true
                    } else {
                        onImportComplete()
                    }
                },
            )

            // Bottom spacer for navigation bar
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Export Password Dialog
    if (showExportPasswordDialog) {
        PasswordDialog(
            title = "Encrypt Export",
            description = "Choose a password to protect your export file. " +
                "You will need this password to import the data on another device.",
            isConfirmMode = true,
            isWrongPassword = false,
            onConfirm = { password ->
                showExportPasswordDialog = false
                viewModel.exportData(password)
            },
            onDismiss = {
                showExportPasswordDialog = false
            },
        )
    }

    // Import Password Dialog (encrypted file detected)
    val currentState = uiState
    if (currentState is MigrationUiState.PasswordRequired || currentState is MigrationUiState.WrongPassword) {
        val fileUri = when (currentState) {
            is MigrationUiState.PasswordRequired -> currentState.fileUri
            is MigrationUiState.WrongPassword -> currentState.fileUri
            else -> null
        }
        if (fileUri != null) {
            PasswordDialog(
                title = "Encrypted Backup",
                description = "This backup file is encrypted. " +
                    "Enter the password that was used during export.",
                isConfirmMode = false,
                isWrongPassword = currentState is MigrationUiState.WrongPassword,
                onConfirm = { password ->
                    viewModel.previewImport(fileUri, password)
                },
                onDismiss = {
                    viewModel.resetState()
                },
            )
        }
    }

    // Import Confirmation Dialog
    if (showImportConfirmDialog && uiState is MigrationUiState.ImportPreview) {
        val preview = (uiState as MigrationUiState.ImportPreview).preview
        ImportConfirmDialog(
            preview = preview,
            onConfirm = {
                showImportConfirmDialog = false
                pendingImportUri?.let { viewModel.importData(it, pendingImportPassword) }
            },
            onDismiss = {
                showImportConfirmDialog = false
                viewModel.resetState()
            },
        )
    }

    // Blocking dialog while service restarts after import
    if (uiState is MigrationUiState.RestartingService) {
        RestartingServiceDialog()
    }

    // Notification permission dialog after import (Android 13+)
    if (showNotificationPermissionDialog) {
        NotificationPermissionDialog(
            onConfirm = {
                showNotificationPermissionDialog = false
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onDismiss = {
                // User declined, proceed without notifications
                showNotificationPermissionDialog = false
                if (pendingImportComplete) {
                    pendingImportComplete = false
                    onImportComplete()
                }
            },
        )
    }
}

@Composable
private fun ExportSection(
    exportPreview: ExportResult?,
    uiState: MigrationUiState,
    exportProgress: Float,
    includeAttachments: Boolean,
    onIncludeAttachmentsChange: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Export Data",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                "Export all your data (identities, messages, contacts, settings) " +
                    "to a file that can be imported into a new installation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            // Preview stats
            when (exportPreview) {
                is ExportResult.Success -> {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Data to export:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("${exportPreview.identityCount} identities")
                            Text("${exportPreview.messageCount} messages")
                            Text("${exportPreview.contactCount} contacts")
                            Text("${exportPreview.announceCount} announces")
                            Text("${exportPreview.interfaceCount} interfaces")
                            Text("${exportPreview.customThemeCount} custom themes")
                        }
                    }
                }
                is ExportResult.Error -> {
                    Text(
                        "Could not load preview: ${exportPreview.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                null -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            // Export progress
            when (uiState) {
                is MigrationUiState.Exporting -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${(exportProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is MigrationUiState.ExportComplete -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export complete! Save dialog opened.")
                    }
                }
                else -> {}
            }

            // Include attachments checkbox
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled =
                                uiState !is MigrationUiState.Exporting &&
                                    uiState !is MigrationUiState.Importing,
                        ) {
                            onIncludeAttachmentsChange(!includeAttachments)
                        },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeAttachments,
                    // Parent Row handles clicks
                    onCheckedChange = null,
                    enabled =
                        uiState !is MigrationUiState.Exporting &&
                            uiState !is MigrationUiState.Importing,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Include file attachments",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!includeAttachments) {
                        Text(
                            "Images and files won't be included in export",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                enabled =
                    uiState !is MigrationUiState.Exporting &&
                        uiState !is MigrationUiState.Importing,
            ) {
                if (uiState is MigrationUiState.Exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Export All Data")
            }
        }
    }
}

@Composable
private fun ImportSection(
    uiState: MigrationUiState,
    importProgress: Float,
    onSelectFile: () -> Unit,
    onImportComplete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Import Data",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                "Import data from a previous export. This will add identities, " +
                    "messages, and contacts from the backup file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            // Import progress
            when (uiState) {
                is MigrationUiState.Importing -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { importProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Importing... ${(importProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is MigrationUiState.ImportComplete -> {
                    // Trigger the callback to navigate away after import and restart complete
                    var hasCalledImportComplete by remember { mutableStateOf(false) }
                    LaunchedEffect(uiState) {
                        if (!hasCalledImportComplete) {
                            hasCalledImportComplete = true
                            onImportComplete()
                        }
                    }
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Import complete!",
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${uiState.result.identitiesImported} identities, " +
                                        "${uiState.result.messagesImported} messages, " +
                                        "${uiState.result.contactsImported} contacts, " +
                                        "${uiState.result.announcesImported} announces, " +
                                        "${uiState.result.interfacesImported} interfaces, " +
                                        "${uiState.result.customThemesImported} themes",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                is MigrationUiState.Error -> {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                uiState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                else -> {}
            }

            OutlinedButton(
                onClick = onSelectFile,
                modifier = Modifier.fillMaxWidth(),
                enabled =
                    uiState !is MigrationUiState.Exporting &&
                        uiState !is MigrationUiState.Importing &&
                        uiState !is MigrationUiState.RestartingService,
            ) {
                if (uiState is MigrationUiState.Importing ||
                    uiState is MigrationUiState.Loading
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Select Migration File")
            }
        }
    }
}

@Composable
private fun ImportConfirmDialog(
    preview: MigrationPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Data?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "This backup was created on:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dateFormat.format(Date(preview.exportedAt)),
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Data to import:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${preview.identityCount} identities")
                        if (preview.identityNames.isNotEmpty()) {
                            Text(
                                preview.identityNames.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${preview.conversationCount} conversations")
                        Text("${preview.messageCount} messages")
                        Text("${preview.contactCount} contacts")
                        Text("${preview.announceCount} announces")
                        Text("${preview.interfaceCount} interfaces")
                        Text("${preview.customThemeCount} custom themes")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Existing identities with the same ID will be skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Blocking dialog shown while service restarts after import.
 * Similar to ApplyChangesDialog in InterfaceManagementScreen.
 */
@Composable
private fun RestartingServiceDialog() {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss - blocking */ },
        icon = {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        },
        title = { Text("Restarting Service") },
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

/**
 * Dialog prompting for notification permission after backup restore.
 * On Android 13+, notification permission isn't restored with backup data,
 * so we need to request it explicitly.
 */
@Composable
private fun NotificationPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        },
        title = { Text("Enable Notifications?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Your backup data has been restored, including your notification preferences.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "To receive notifications for new messages, you'll need to grant notification permission.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        },
    )
}

/**
 * Dialog for entering (or creating + confirming) a password for export encryption / import decryption.
 *
 * @param title Dialog title
 * @param description Explanatory text shown below the title
 * @param isConfirmMode If true, shows a second "confirm password" field (used during export)
 * @param isWrongPassword If true, shows an error message (used during import retry)
 * @param onConfirm Called with the validated password
 * @param onDismiss Called when the dialog is cancelled
 */
@Composable
internal fun PasswordDialog(
    title: String,
    description: String,
    isConfirmMode: Boolean,
    isWrongPassword: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(if (isWrongPassword) "Incorrect password" else null) }

    val minLength = com.lxmf.messenger.migration.MigrationCrypto.MIN_PASSWORD_LENGTH

    fun validate(): Boolean {
        if (password.length < minLength) {
            errorMessage = "Password must be at least $minLength characters"
            return false
        }
        if (isConfirmMode && password != confirmPassword) {
            errorMessage = "Passwords do not match"
            return false
        }
        errorMessage = null
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isConfirmMode) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMessage = null
                        },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (validate()) {
                        onConfirm(password)
                    }
                },
                enabled = password.isNotEmpty(),
            ) {
                Text(if (isConfirmMode) "Export" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
