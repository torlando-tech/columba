package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.model.CodecProfile

/**
 * Dialog for selecting an audio codec profile before initiating a voice call.
 *
 * Displays all available codec profiles with their descriptions,
 * allowing the user to choose based on their network conditions.
 *
 * @param onDismiss Called when the dialog is dismissed without selection
 * @param onProfileSelected Called with the selected profile when user confirms
 */
@Composable
fun CodecSelectionDialog(
    onDismiss: () -> Unit,
    onProfileSelected: (CodecProfile) -> Unit,
) {
    var selectedProfile by remember { mutableStateOf(CodecProfile.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Call Quality") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Choose a codec profile based on your connection speed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(CodecProfile.entries) { profile ->
                        CodecProfileItem(
                            profile = profile,
                            isSelected = profile == selectedProfile,
                            isDefault = profile == CodecProfile.DEFAULT,
                            onSelect = { selectedProfile = profile },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onProfileSelected(selectedProfile) },
            ) {
                Text("Call")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Individual codec profile item with radio button selection.
 */
@Composable
private fun CodecProfileItem(
    profile: CodecProfile,
    isSelected: Boolean,
    isDefault: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isDefault) {
                    Text(
                        text = " (Recommended)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = profile.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
