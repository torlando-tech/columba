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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun IdentityCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onViewIdentity: () -> Unit,
    onManageIdentities: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.identity_card_title),
        icon = Icons.Default.Person,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description for "My Identity"
        Text(
            text = stringResource(R.string.identity_card_view_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Description for "Identity Management"
        Text(
            text = stringResource(R.string.identity_card_manage_description),
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
            Text(stringResource(R.string.identity_card_view_my_identity))
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
            Text(stringResource(R.string.identity_card_manage_identities))
        }
    }
}
