package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet that shows options for an image attachment.
 *
 * Provides two options:
 * - Save to device: Saves the image to a user-selected location
 * - Share: Shares the image via Android's share sheet
 *
 * @param onSaveToDevice Callback when "Save to device" is selected
 * @param onShare Callback when "Share" is selected
 * @param onDismiss Callback when the sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun ImageOptionsSheet(
    onSaveToDevice: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Header
            Text(
                text = "Image",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            HorizontalDivider()

            // Save to device option
            ListItem(
                headlineContent = { Text("Save to device") },
                supportingContent = { Text("Save to a folder on your device") },
                leadingContent = {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable { onSaveToDevice() },
            )

            // Share option
            ListItem(
                headlineContent = { Text("Share") },
                supportingContent = { Text("Share via another app") },
                leadingContent = {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable { onShare() },
            )
        }
    }
}
