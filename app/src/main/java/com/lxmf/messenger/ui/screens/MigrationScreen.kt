package com.lxmf.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.migration.ExportResult
import com.lxmf.messenger.migration.ImportResult
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

    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
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
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, state.fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Data"))
            }
            is MigrationUiState.ImportPreview -> {
                pendingImportUri = state.fileUri
                showImportConfirmDialog = true
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
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Export Section
            ExportSection(
                exportPreview = exportPreview,
                uiState = uiState,
                exportProgress = exportProgress,
                onExport = { viewModel.exportData() },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Import Section
            ImportSection(
                uiState = uiState,
                importProgress = importProgress,
                onSelectFile = { importLauncher.launch("*/*") },
                onImportComplete = onImportComplete,
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
                pendingImportUri?.let { viewModel.importData(it) }
            },
            onDismiss = {
                showImportConfirmDialog = false
                viewModel.resetState()
            },
        )
    }
}

@Composable
private fun ExportSection(
    exportPreview: ExportResult?,
    uiState: MigrationUiState,
    exportProgress: Float,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
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
                        colors = CardDefaults.cardColors(
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
                            Text("${exportPreview.attachmentCount} attachments")
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
                        Text("Export complete! Share sheet opened.")
                    }
                }
                else -> {}
            }

            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is MigrationUiState.Exporting &&
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
        colors = CardDefaults.cardColors(
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
                    // Trigger the callback to handle service restart from MainActivity
                    // Use uiState as key and a flag to ensure this only fires once
                    var hasCalledImportComplete by remember { mutableStateOf(false) }
                    LaunchedEffect(uiState) {
                        if (!hasCalledImportComplete) {
                            hasCalledImportComplete = true
                            onImportComplete()
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
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
                                        "${uiState.result.interfacesImported} interfaces",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Restarting service...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                is MigrationUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
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
                enabled = uiState !is MigrationUiState.Exporting &&
                    uiState !is MigrationUiState.Importing,
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
                    colors = CardDefaults.cardColors(
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
                        Text("${preview.attachmentCount} attachments")
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
