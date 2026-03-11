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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
            title = { Text("Disable Transport Mode?") },
            text = {
                Text(
                    "This will clear the saved radio configuration and reset the device. " +
                        "It will return to normal host-controlled mode.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirmation = false
                        onDisableTransport()
                    },
                ) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Progress dialog while disabling
    if (isDisablingTransport) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Disabling Transport") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Clearing configuration and resetting device...")
                }
            },
            confirmButton = {},
        )
    }

    // Result dialog
    if (disableTransportResult != null) {
        AlertDialog(
            onDismissRequest = onDismissDisableResult,
            title = { Text(if (disableTransportResult) "Transport Disabled" else "Error") },
            text = {
                Text(
                    if (disableTransportResult) {
                        "Transport mode has been disabled. The device will restart in normal mode."
                    } else {
                        "Failed to disable transport mode. Make sure the device is connected and try again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissDisableResult) {
                    Text("OK")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB Device Connected") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
                text = "What would you like to do?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Flash Firmware option
            ActionCard(
                icon = Icons.Default.Memory,
                title = "Flash Firmware",
                description = "Update or install RNode firmware on this device",
                onClick = onFlashFirmware,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configure RNode option
            ActionCard(
                icon = Icons.Default.Settings,
                title = "Configure RNode",
                description = "Set up this device as a Reticulum interface",
                onClick = onConfigureRNode,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configure Transport option (standalone TNC config)
            ActionCard(
                icon = Icons.Default.Router,
                title = "Configure Transport",
                description = "Set radio parameters for standalone transport mode",
                onClick = onConfigureTransport,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Disable Transport option
            ActionCard(
                icon = Icons.Default.SettingsInputAntenna,
                title = "Disable Transport",
                description = "Clear saved config and return to normal mode",
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
