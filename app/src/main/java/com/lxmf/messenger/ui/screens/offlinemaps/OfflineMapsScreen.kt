package com.lxmf.messenger.ui.screens.offlinemaps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.viewmodel.OfflineMapsViewModel
import com.lxmf.messenger.viewmodel.UpdateCheckResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToDownload: () -> Unit = {},
    viewModel: OfflineMapsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // File picker launcher for MBTiles import
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { viewModel.importMbtilesFile(it) }
        }

    // Show error in snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show import success in snackbar
    LaunchedEffect(state.importSuccessMessage) {
        state.importSuccessMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearImportSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Maps") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = !state.isImporting,
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Import MBTiles file",
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToDownload,
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Download new region",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.regions.isEmpty()) {
            EmptyOfflineMapsState(
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
                // Storage summary
                item {
                    StorageSummaryCard(
                        totalStorage = state.getTotalStorageString(),
                        regionCount = state.regions.size,
                    )
                }

                // Region list
                items(
                    state.regions,
                    key = { it.id },
                ) { region ->
                    OfflineMapRegionCard(
                        region = region,
                        onDelete = { viewModel.deleteRegion(region) },
                        isDeleting = state.isDeleting,
                        updateCheckResult = state.updateCheckResults[region.id],
                        onCheckForUpdates = { viewModel.checkForUpdates(region) },
                        onUpdateNow = {
                            // Navigate to download screen to re-download the region
                            // For now, just trigger the update check - full re-download TBD
                            viewModel.checkForUpdates(region)
                        },
                        onToggleDefault = {
                            if (region.isDefault) {
                                viewModel.clearDefaultRegion()
                            } else {
                                viewModel.setDefaultRegion(region.id)
                            }
                        },
                    )
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun StorageSummaryCard(
    totalStorage: String,
    regionCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
            Column {
                Text(
                    text = "Total Storage",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = totalStorage,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Regions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = regionCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun OfflineMapRegionCard(
    region: OfflineMapRegion,
    onDelete: () -> Unit,
    isDeleting: Boolean,
    updateCheckResult: UpdateCheckResult? = null,
    onCheckForUpdates: () -> Unit = {},
    onUpdateNow: () -> Unit = {},
    onToggleDefault: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            // Map icon
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .padding(end = 12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            // Region info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = region.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status, default badge, and size
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(status = region.status)
                    if (region.isDefault) {
                        Text(
                            text = "Default",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = region.getSizeString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Progress bar for downloading
                if (region.status == OfflineMapRegion.Status.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { region.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(region.downloadProgress * 100).toInt()}% - ${region.tileCount} tiles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Error message
                val errorMsg = region.errorMessage
                if (region.status == OfflineMapRegion.Status.ERROR && errorMsg != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                // Details
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${region.radiusKm} km radius - Zoom ${region.minZoom}-${region.maxZoom}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Version and update info
                if (region.status == OfflineMapRegion.Status.COMPLETE) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Show version or download date
                    val versionText =
                        region.tileVersion?.let { version ->
                            // Parse version like "20260107_001001_pt" to show as date
                            val dateStr = version.take(8)
                            val formattedDate =
                                runCatching {
                                    val year = dateStr.substring(0, 4)
                                    val month = dateStr.substring(4, 6)
                                    val day = dateStr.substring(6, 8)
                                    "$year-$month-$day"
                                }.getOrNull() ?: version
                            "Map data: $formattedDate"
                        } ?: region.completedAt?.let { "Downloaded ${formatDate(it)}" }

                    versionText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Update check button and status
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            updateCheckResult?.isChecking == true -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "Checking...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            updateCheckResult?.hasUpdate == true -> {
                                Icon(
                                    imageVector = Icons.Default.Update,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Update available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(
                                    onClick = { showUpdateDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Text("Update Now", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            updateCheckResult?.latestVersion != null && !updateCheckResult.hasUpdate -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = "Up to date",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            else -> {
                                TextButton(
                                    onClick = onCheckForUpdates,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Check for Updates", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // Action buttons column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Default map center toggle (star) - only for completed regions
                if (region.status == OfflineMapRegion.Status.COMPLETE) {
                    IconButton(
                        onClick = onToggleDefault,
                    ) {
                        Icon(
                            imageVector = if (region.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (region.isDefault) "Remove as default" else "Set as default",
                            tint = if (region.isDefault) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                // Delete button
                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !isDeleting,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Offline Map") },
            text = {
                Text("Are you sure you want to delete \"${region.name}\"? This will free up ${region.getSizeString()} of storage.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Update confirmation dialog
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Update Offline Map") },
            text = {
                Text(
                    "Download the latest map data for \"${region.name}\"? " +
                        "This will replace the current data and may take a few minutes.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        onUpdateNow()
                    },
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun StatusChip(
    status: OfflineMapRegion.Status,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when (status) {
            OfflineMapRegion.Status.PENDING -> "Pending" to MaterialTheme.colorScheme.tertiary
            OfflineMapRegion.Status.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
            OfflineMapRegion.Status.COMPLETE -> "Complete" to MaterialTheme.colorScheme.secondary
            OfflineMapRegion.Status.ERROR -> "Error" to MaterialTheme.colorScheme.error
        }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun EmptyOfflineMapsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Map,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Offline Maps",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Download or import map regions for offline use",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap + to download, or use the import button to load an MBTiles file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
