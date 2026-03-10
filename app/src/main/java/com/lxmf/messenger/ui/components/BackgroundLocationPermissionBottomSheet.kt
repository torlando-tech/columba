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
import androidx.compose.material.icons.filled.ShareLocation
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
 * Material 3 bottom sheet that explains why background location ("Allow all the time")
 * is useful and lets the user choose whether to grant it.
 *
 * Shown after the user has already granted foreground location permission.
 * On Android 10+ (API 29+), background location must be requested separately.
 *
 * @param onDismiss Callback when the user declines (chooses "Not Now")
 * @param onRequestPermission Callback when the user agrees to grant background location
 * @param sheetState The state of the bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundLocationPermissionBottomSheet(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    sheetState: SheetState,
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
                    imageVector = Icons.Default.ShareLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "Background Location",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text =
                    "To share your location with contacts while the app is in the background, " +
                        "Columba needs the \"Allow all the time\" permission.\n\n" +
                        "This lets Columba continue sending your encrypted location to chosen " +
                        "contacts even when you switch to another app or lock your screen.\n\n" +
                        "On the next screen, please select \"Allow all the time\".",
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
                Button(onClick = onRequestPermission) {
                    Text("Continue")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
