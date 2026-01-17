package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun IdentityCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onViewIdentity: () -> Unit,
    onManageIdentities: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Identity",
        icon = Icons.Default.Person,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description for "My Identity"
        Text(
            text = "View and share your identity, edit your display name, and manage QR codes for contact sharing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Description for "Identity Management"
        Text(
            text = "Create and manage multiple identities for different contexts (work, personal, anonymous).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Primary action - View My Identity
        Button(
            onClick = onViewIdentity,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("View My Identity")
        }

        // Secondary action - Manage Identities
        OutlinedButton(
            onClick = onManageIdentities,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Manage Identities")
        }
    }
}
