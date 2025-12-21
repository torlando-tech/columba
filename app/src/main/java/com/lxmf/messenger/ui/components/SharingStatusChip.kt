package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Status chip shown on the map when actively sharing location.
 *
 * Displays "Sharing with X people" with a location icon and close button.
 *
 * @param sharingWithCount Number of contacts currently sharing with
 * @param onStopAllClick Callback when the stop (X) button is clicked
 * @param modifier Modifier for the chip
 */
@Composable
fun SharingStatusChip(
    sharingWithCount: Int,
    onStopAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = { /* Could open sharing management sheet in future */ },
        label = {
            Text(
                text = "Sharing with $sharingWithCount ${if (sharingWithCount == 1) "person" else "people"}",
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onStopAllClick,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop sharing",
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
        modifier = modifier,
    )
}
