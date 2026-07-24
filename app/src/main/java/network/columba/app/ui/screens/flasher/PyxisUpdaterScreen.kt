package network.columba.app.ui.screens.flasher

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.rns.host.usb.UsbDeviceInfo
import network.columba.app.viewmodel.PyxisUpdaterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyxisUpdaterScreen(
    onNavigateBack: () -> Unit,
    initialPackageUri: String? = null,
    viewModel: PyxisUpdaterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }

    val packagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(viewModel::loadPackage)
        }

    LaunchedEffect(initialPackageUri) {
        initialPackageUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?.let(viewModel::loadPackage)
    }

    BackHandler(enabled = state.isFlashing) {
        // Interrupting a ROM flash can leave the selected app slot incomplete.
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Update Pyxis") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !state.isFlashing,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PackageSection(
                packageName = state.packageName,
                packageVersion = state.packageVersion,
                firmwareSize = state.firmwareSize,
                packageError = state.packageError,
                isLoading = state.isLoadingPackage,
                enabled = !state.isFlashing,
                onPickPackage = { packagePicker.launch("*/*") },
            )

            WarningCard()

            DeviceSection(
                devices = state.connectedDevices,
                selectedDevice = state.selectedDevice,
                isRefreshing = state.isRefreshingDevices,
                permissionPending = state.permissionPending,
                permissionError = state.permissionError,
                enabled = !state.isFlashing,
                onRefresh = viewModel::refreshDevices,
                onSelect = viewModel::selectDevice,
            )

            if (state.isFlashing) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Installing update", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { state.flashProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.flashProgress}% — ${state.flashMessage}")
                        Text(
                            "Keep the USB cable connected and do not power off either device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.flashSucceeded) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Column {
                            Text("Update installed", style = MaterialTheme.typography.titleMedium)
                            Text("Pyxis was rebooted. Confirm its version and persistent data on the device.")
                        }
                    }
                }
            }

            Button(
                onClick = { showConfirmation = true },
                enabled = viewModel.canFlash(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Memory, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text("Flash Pyxis over USB")
            }

            Text(
                "This first version uses USB recovery flashing. Wireless phone-hosted OTA can be added after this path is physically validated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Install this Pyxis update?") },
            text = {
                Text(
                    "The package hashes and target layout were verified. Confirm that the package came from a trusted LXMF sender. " +
                        "The updater will write only OTA metadata and the application slot; NVS and LittleFS are not touched.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        viewModel.startFlash()
                    },
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    state.flashError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearFlashError,
            title = { Text("Pyxis update failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearFlashError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun PackageSection(
    packageName: String?,
    packageVersion: String?,
    firmwareSize: Int?,
    packageError: String?,
    isLoading: Boolean,
    enabled: Boolean,
    onPickPackage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pyxis update package", style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        Text("Verifying package...")
                    }
                }
                packageVersion != null -> {
                    Text(packageName ?: "Pyxis package")
                    Text("Version: $packageVersion")
                    firmwareSize?.let { Text("Application: ${formatBytes(it)}") }
                    Text(
                        "Target: T-Deck Plus / ESP32-S3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text("Choose a .pyxis.zip attachment or downloaded package.")
            }
            packageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = onPickPackage,
                enabled = enabled && !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (packageVersion == null) "Choose package" else "Choose another package")
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Development updater", style = MaterialTheme.typography.titleSmall)
                Text(
                    "SHA-256 protects package integrity, but this build does not yet verify a release signature. Only install packages received from a trusted identity.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeviceSection(
    devices: List<UsbDeviceInfo>,
    selectedDevice: UsbDeviceInfo?,
    isRefreshing: Boolean,
    permissionPending: Boolean,
    permissionError: String?,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSelect: (UsbDeviceInfo) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("USB device", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh, enabled = enabled && !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh USB devices")
                }
            }
            if (devices.isEmpty()) {
                Text("Connect the T-Deck Plus to the phone with a USB OTG/data cable, then refresh.")
            } else {
                devices.forEach { device ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onSelect(device) }
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedDevice?.deviceId == device.deviceId,
                            onClick = { onSelect(device) },
                            enabled = enabled,
                        )
                        Column {
                            Text(device.productName ?: device.deviceName)
                            Text(
                                "VID %04x · PID %04x · %s".format(device.vendorId, device.productId, device.driverType),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (permissionPending) Text("Waiting for USB permission...")
            permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun formatBytes(bytes: Int): String = "%.2f MiB".format(bytes / (1024.0 * 1024.0))
