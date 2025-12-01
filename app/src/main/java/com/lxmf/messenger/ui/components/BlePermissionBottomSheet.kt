package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Material 3 bottom sheet that explains Bluetooth permission requirements
 * and provides an action to request permissions.
 *
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param onRequestPermissions Callback when user grants permission request
 * @param sheetState The state of the bottom sheet
 * @param rationale Optional custom rationale text (defaults to BlePermissionManager rationale)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlePermissionBottomSheet(
    onDismiss: () -> Unit,
    onRequestPermissions: () -> Unit,
    sheetState: SheetState,
    rationale: String = getDefaultRationale(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "Bluetooth Permissions Required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Rationale text
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not Now")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onRequestPermissions) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

/**
 * Default rationale text explaining why Bluetooth permissions are needed.
 * Matches the rationale provided by BlePermissionManager.
 */
private fun getDefaultRationale(): String {
    return """
        Columba uses Bluetooth Low Energy (BLE) to communicate with nearby devices in a mesh network.

        To enable this functionality, we need the following permissions:

        • Bluetooth Scan: To discover nearby Reticulum peers
        • Bluetooth Connect: To establish connections with peers
        • Bluetooth Advertise: To broadcast your presence to other devices

        These permissions allow Columba to create a decentralized, off-grid communication network.
        """.trimIndent()
}
