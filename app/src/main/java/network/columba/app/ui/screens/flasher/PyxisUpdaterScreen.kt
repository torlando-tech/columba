package network.columba.app.ui.screens.flasher

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.R
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
    val clipboardManager = LocalClipboardManager.current

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
                title = { Text(stringResource(R.string.pyxis_update_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !state.isFlashing,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pyxis_update_back),
                        )
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
                        Text(stringResource(R.string.pyxis_update_installing), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { state.flashProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.pyxis_update_progress, state.flashProgress, state.flashMessage))
                        Text(
                            stringResource(R.string.pyxis_update_keep_connected),
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
                            Text(stringResource(R.string.pyxis_update_installed), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.pyxis_update_installed_body))
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
                Text(stringResource(R.string.pyxis_update_flash_usb))
            }

            Text(
                stringResource(R.string.pyxis_update_usb_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.pyxis_update_confirm_title)) },
            text = {
                Text(stringResource(R.string.pyxis_update_confirm_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        viewModel.startFlash()
                    },
                ) {
                    Text(stringResource(R.string.pyxis_update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text(stringResource(R.string.pyxis_update_cancel)) }
            },
        )
    }

    state.flashError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearFlashError,
            title = { Text(stringResource(R.string.pyxis_update_failed_title)) },
            text = { SelectionContainer { Text(error) } },
            confirmButton = {
                TextButton(onClick = viewModel::clearFlashError) { Text(stringResource(R.string.pyxis_update_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { clipboardManager.setText(AnnotatedString(error)) }) {
                    Text(stringResource(R.string.pyxis_update_copy_error))
                }
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
            Text(stringResource(R.string.pyxis_update_package_title), style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        Text(stringResource(R.string.pyxis_update_package_verifying))
                    }
                }
                packageVersion != null -> {
                    Text(packageName ?: stringResource(R.string.pyxis_update_package_default_name))
                    Text(stringResource(R.string.pyxis_update_package_version, packageVersion))
                    firmwareSize?.let {
                        Text(stringResource(R.string.pyxis_update_package_application_size, it / (1024.0 * 1024.0)))
                    }
                    Text(
                        stringResource(R.string.pyxis_update_package_target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(stringResource(R.string.pyxis_update_package_prompt))
            }
            packageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = onPickPackage,
                enabled = enabled && !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (packageVersion == null) {
                            R.string.pyxis_update_package_choose
                        } else {
                            R.string.pyxis_update_package_choose_another
                        },
                    ),
                )
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
                Text(stringResource(R.string.pyxis_update_verify_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.pyxis_update_verify_body),
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
                Text(stringResource(R.string.pyxis_update_usb_device_title), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh, enabled = enabled && !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.pyxis_update_usb_refresh))
                }
            }
            if (devices.isEmpty()) {
                Text(stringResource(R.string.pyxis_update_usb_connect_prompt))
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
                                stringResource(
                                    R.string.pyxis_update_usb_device_details,
                                    device.vendorId,
                                    device.productId,
                                    device.driverType,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (permissionPending) Text(stringResource(R.string.pyxis_update_usb_permission_waiting))
            permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
