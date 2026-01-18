package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun PrivacyCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    blockUnknownSenders: Boolean,
    onBlockUnknownSendersChange: (Boolean) -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Privacy",
        icon = Icons.Default.Security,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = blockUnknownSenders,
                onCheckedChange = onBlockUnknownSendersChange,
            )
        },
    ) {
        // Description
        Text(
            text =
                if (blockUnknownSenders) {
                    "Only contacts can message you. Messages from unknown senders are silently discarded."
                } else {
                    "Anyone can send you messages, including unknown senders."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
