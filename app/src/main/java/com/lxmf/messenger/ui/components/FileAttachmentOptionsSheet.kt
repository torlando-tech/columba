package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet that shows options for a received file attachment.
 *
 * Provides two options:
 * - Open with: Opens the file using an external app via Intent chooser
 * - Save to device: Saves the file to a user-selected location
 *
 * @param filename The name of the file to display
 * @param onOpenWith Callback when "Open with..." is selected
 * @param onSaveToDevice Callback when "Save to device" is selected
 * @param onDismiss Callback when the sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun FileAttachmentOptionsSheet(
    filename: String,
    onOpenWith: () -> Unit,
    onSaveToDevice: () -> Unit,
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
            // Filename header
            Text(
                text = filename,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            HorizontalDivider()

            // Open with option
            ListItem(
                headlineContent = { Text("Open with...") },
                supportingContent = { Text("Open in another app") },
                leadingContent = {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable { onOpenWith() },
            )

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
        }
    }
}
