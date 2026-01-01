package com.lxmf.messenger.ui.screens.offlinemaps

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lxmf.messenger.map.TileDownloadManager
import com.lxmf.messenger.viewmodel.DownloadWizardStep
import com.lxmf.messenger.viewmodel.OfflineMapDownloadViewModel
import com.lxmf.messenger.viewmodel.RadiusOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapDownloadScreen(
    onNavigateBack: () -> Unit = {},
    onDownloadComplete: () -> Unit = {},
    viewModel: OfflineMapDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCancelDialog by remember { mutableStateOf(false) }

    // Handle completion
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) {
            onDownloadComplete()
        }
    }

    // Show error in snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            DownloadWizardStep.LOCATION -> "Select Location"
                            DownloadWizardStep.RADIUS -> "Choose Area"
                            DownloadWizardStep.CONFIRM -> "Confirm Download"
                            DownloadWizardStep.DOWNLOADING -> "Downloading"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step == DownloadWizardStep.DOWNLOADING) {
                                showCancelDialog = true
                            } else if (state.step == DownloadWizardStep.LOCATION) {
                                onNavigateBack()
                            } else {
                                viewModel.previousStep()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (state.step == DownloadWizardStep.DOWNLOADING) {
                                Icons.Default.Close
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = if (state.step == DownloadWizardStep.DOWNLOADING) {
                                "Cancel"
                            } else {
                                "Back"
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state.step) {
                DownloadWizardStep.LOCATION -> LocationSelectionStep(
                    hasLocation = state.hasLocation,
                    latitude = state.centerLatitude,
                    longitude = state.centerLongitude,
                    onLocationSet = { lat, lon -> viewModel.setLocation(lat, lon) },
                    onCurrentLocationRequest = { location ->
                        viewModel.setLocationFromCurrent(location)
                    },
                    onNext = { viewModel.nextStep() },
                )

                DownloadWizardStep.RADIUS -> RadiusSelectionStep(
                    radiusOption = state.radiusOption,
                    minZoom = state.minZoom,
                    maxZoom = state.maxZoom,
                    estimatedTileCount = state.estimatedTileCount,
                    estimatedSize = state.getEstimatedSizeString(),
                    onRadiusChange = { viewModel.setRadiusOption(it) },
                    onZoomRangeChange = { min, max -> viewModel.setZoomRange(min, max) },
                    onNext = { viewModel.nextStep() },
                    onBack = { viewModel.previousStep() },
                )

                DownloadWizardStep.CONFIRM -> ConfirmDownloadStep(
                    latitude = state.centerLatitude ?: 0.0,
                    longitude = state.centerLongitude ?: 0.0,
                    radiusKm = state.radiusOption.km,
                    minZoom = state.minZoom,
                    maxZoom = state.maxZoom,
                    estimatedTileCount = state.estimatedTileCount,
                    estimatedSize = state.getEstimatedSizeString(),
                    name = state.name,
                    onNameChange = { viewModel.setName(it) },
                    onStartDownload = { viewModel.nextStep() },
                    onBack = { viewModel.previousStep() },
                )

                DownloadWizardStep.DOWNLOADING -> DownloadingStep(
                    progress = state.downloadProgress,
                    onCancel = { showCancelDialog = true },
                )
            }
        }
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Download?") },
            text = {
                Text("Are you sure you want to cancel the download? Progress will be lost.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelDownload()
                        onNavigateBack()
                    },
                ) {
                    Text("Cancel Download", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continue")
                }
            },
        )
    }
}

@Composable
fun LocationSelectionStep(
    hasLocation: Boolean,
    latitude: Double?,
    longitude: Double?,
    onLocationSet: (Double, Double) -> Unit,
    onCurrentLocationRequest: (Location) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isGettingLocation by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasPermission) {
            isGettingLocation = true
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            try {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token,
                ).addOnSuccessListener { location ->
                    isGettingLocation = false
                    if (location != null) {
                        onCurrentLocationRequest(location)
                    }
                }.addOnFailureListener {
                    isGettingLocation = false
                }
            } catch (e: SecurityException) {
                isGettingLocation = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Choose the center point for your offline map region.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Use current location button
        FilledTonalButton(
            onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            enabled = !isGettingLocation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isGettingLocation) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Use Current Location")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "- or -",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Manual coordinate entry
        var latText by remember(latitude) { mutableStateOf(latitude?.toString() ?: "") }
        var lonText by remember(longitude) { mutableStateOf(longitude?.toString() ?: "") }

        OutlinedTextField(
            value = latText,
            onValueChange = {
                latText = it
                val lat = it.toDoubleOrNull()
                val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null) {
                    onLocationSet(lat, lon)
                }
            },
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = lonText,
            onValueChange = {
                lonText = it
                val lat = latText.toDoubleOrNull()
                val lon = it.toDoubleOrNull()
                if (lat != null && lon != null) {
                    onLocationSet(lat, lon)
                }
            },
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (hasLocation) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Location set: ${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onNext,
            enabled = hasLocation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Next")
        }
    }
}

@Composable
fun RadiusSelectionStep(
    radiusOption: RadiusOption,
    minZoom: Int,
    maxZoom: Int,
    estimatedTileCount: Int,
    estimatedSize: String,
    onRadiusChange: (RadiusOption) -> Unit,
    onZoomRangeChange: (Int, Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Select Area Size",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Radius options
        Column(modifier = Modifier.selectableGroup()) {
            RadiusOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = radiusOption == option,
                            onClick = { onRadiusChange(option) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = radiusOption == option,
                        onClick = null,
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Zoom range
        Text(
            text = "Zoom Range",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Min zoom: $minZoom (less detail, smaller size)",
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = minZoom.toFloat(),
            onValueChange = { onZoomRangeChange(it.toInt(), maxZoom) },
            valueRange = 0f..14f,
            steps = 13,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Max zoom: $maxZoom (more detail, larger size)",
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = maxZoom.toFloat(),
            onValueChange = { onZoomRangeChange(minZoom, it.toInt()) },
            valueRange = 0f..14f,
            steps = 13,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Estimate card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "Estimated Download",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$estimatedTileCount tiles",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "~$estimatedSize",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
            ) {
                Text("Next")
            }
        }
    }
}

@Composable
fun ConfirmDownloadStep(
    latitude: Double,
    longitude: Double,
    radiusKm: Int,
    minZoom: Int,
    maxZoom: Int,
    estimatedTileCount: Int,
    estimatedSize: String,
    name: String,
    onNameChange: (String) -> Unit,
    onStartDownload: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Name Your Map",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Region Name") },
            placeholder = { Text("e.g., Home, Downtown, Trail") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Summary",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryRow("Location", "${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)}")
                SummaryRow("Radius", "$radiusKm km")
                SummaryRow("Zoom Range", "$minZoom - $maxZoom")
                SummaryRow("Tiles", "$estimatedTileCount")
                SummaryRow("Estimated Size", "~$estimatedSize")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This will download map tiles from OpenFreeMap for offline use. Make sure you're connected to Wi-Fi for large downloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = onStartDownload,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Download")
            }
        }
    }
}

@Composable
fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun DownloadingStep(
    progress: TileDownloadManager.DownloadProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Preparing download...")
        } else {
            val statusText = when (progress.status) {
                TileDownloadManager.DownloadProgress.Status.IDLE -> "Preparing..."
                TileDownloadManager.DownloadProgress.Status.CALCULATING -> "Calculating tiles..."
                TileDownloadManager.DownloadProgress.Status.DOWNLOADING -> "Downloading..."
                TileDownloadManager.DownloadProgress.Status.WRITING -> "Finalizing..."
                TileDownloadManager.DownloadProgress.Status.COMPLETE -> "Complete!"
                TileDownloadManager.DownloadProgress.Status.ERROR -> "Error"
                TileDownloadManager.DownloadProgress.Status.CANCELLED -> "Cancelled"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { progress.progress },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "${(progress.progress * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${progress.downloadedTiles} / ${progress.totalTiles} tiles",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (progress.currentZoom > 0) {
                Text(
                    text = "Zoom level ${progress.currentZoom}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (progress.failedTiles > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${progress.failedTiles} tiles failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val mbDownloaded = progress.bytesDownloaded / (1024.0 * 1024.0)
            Text(
                text = String.format("%.1f MB downloaded", mbDownloaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (progress.status == TileDownloadManager.DownloadProgress.Status.DOWNLOADING) {
                Spacer(modifier = Modifier.height(32.dp))

                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}
