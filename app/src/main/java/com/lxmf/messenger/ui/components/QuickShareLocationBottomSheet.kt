package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.model.SharingDuration

/**
 * Simplified bottom sheet for sharing location with a single contact.
 * Used from the conversation screen where the target contact is already known.
 *
 * @param contactName Display name of the contact to share with
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param onStartSharing Callback when sharing is initiated with selected duration
 * @param sheetState The state of the bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickShareLocationBottomSheet(
    contactName: String,
    onDismiss: () -> Unit,
    onStartSharing: (duration: SharingDuration) -> Unit,
    sheetState: SheetState,
) {
    var selectedDuration by remember { mutableStateOf(SharingDuration.ONE_HOUR) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            // Title
            Text(
                text = "Share your location",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle with contact name
            Text(
                text = "with $contactName",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Duration label
            Text(
                text = "Duration:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Duration selection chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SharingDuration.entries.forEach { duration ->
                    FilterChip(
                        selected = selectedDuration == duration,
                        onClick = { selectedDuration = duration },
                        label = { Text(duration.displayText) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start Sharing button
            Button(
                onClick = { onStartSharing(selectedDuration) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Sharing")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
