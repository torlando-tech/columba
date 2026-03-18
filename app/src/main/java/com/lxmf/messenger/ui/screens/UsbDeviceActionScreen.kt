package com.lxmf.messenger.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R

/**
 * Screen shown when a USB device is connected that isn't already configured.
 * Allows the user to choose between flashing firmware or configuring the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDeviceActionScreen(
    deviceName: String,
    onNavigateBack: () -> Unit,
    onFlashFirmware: () -> Unit,
    onConfigureRNode: () -> Unit,
    onConfigureTransport: () -> Unit,
    onDisableTransport: () -> Unit,
    isDisablingTransport: Boolean = false,
    disableTransportResult: Boolean? = null,
    onDismissDisableResult: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDisableConfirmation by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (showDisableConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmation = false },
            title = { Text(stringResource(R.string.usb_device_action_disable_transport_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.usb_device_action_disable_transport_confirm_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirmation = false
                        onDisableTransport()
                    },
                ) {
                        Text(stringResource(R.string.usb_device_action_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmation = false }) {
                        Text(stringResource(R.string.usb_device_action_cancel))
                }
            },
        )
    }

    // Progress dialog while disabling
    if (isDisablingTransport) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.usb_device_action_disabling_transport_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.usb_device_action_disabling_transport_message))
                }
            },
            confirmButton = {},
        )
    }

    // Result dialog
    if (disableTransportResult != null) {
        AlertDialog(
            onDismissRequest = onDismissDisableResult,
            title = { Text(if (disableTransportResult) stringResource(R.string.usb_device_action_transport_disabled_title) else stringResource(R.string.usb_device_action_error_title)) },
            text = {
                Text(
                    if (disableTransportResult) {
                        stringResource(R.string.usb_device_action_transport_disabled_message)
                    } else {
                        stringResource(R.string.usb_device_action_transport_disabled_error)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissDisableResult) {
                    Text(stringResource(R.string.usb_device_action_ok))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usb_device_action_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.usb_device_action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // USB icon
            Icon(
                imageVector = Icons.Default.Usb,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device name
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.usb_device_action_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Flash Firmware option
            ActionCard(
                icon = Icons.Default.Memory,
                title = stringResource(R.string.usb_device_action_flash_firmware_title),
                description = stringResource(R.string.usb_device_action_flash_firmware_description),
                onClick = onFlashFirmware,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configure RNode option
            ActionCard(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.usb_device_action_configure_rnode_title),
                description = stringResource(R.string.usb_device_action_configure_rnode_description),
                onClick = onConfigureRNode,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configure Transport option (standalone TNC config)
            ActionCard(
                icon = Icons.Default.Router,
                title = stringResource(R.string.usb_device_action_configure_transport_title),
                description = stringResource(R.string.usb_device_action_configure_transport_description),
                onClick = onConfigureTransport,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Disable Transport option
            ActionCard(
                icon = Icons.Default.SettingsInputAntenna,
                title = stringResource(R.string.usb_device_action_disable_transport_title),
                description = stringResource(R.string.usb_device_action_disable_transport_description),
                onClick = { showDisableConfirmation = true },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
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
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
