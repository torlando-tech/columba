package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.components.CollapsibleSettingsCard

@Composable
fun PrivacyCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    blockUnknownSenders: Boolean,
    onBlockUnknownSendersChange: (Boolean) -> Unit,
    allowCallsFromContactsOnly: Boolean,
    onAllowCallsFromContactsOnlyChange: (Boolean) -> Unit,
    blockedPeerCount: Int = 0,
    onNavigateToBlockedUsers: () -> Unit = {},
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.settings_privacy_title),
        icon = Icons.Default.Security,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Messages-from-contacts-only toggle row. Moved out of the card header
        // so it's visually equal-billed with the calls toggle below.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_messages_contacts_only),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
            )
            Switch(
                checked = blockUnknownSenders,
                onCheckedChange = onBlockUnknownSendersChange,
            )
        }
        Text(
            text =
                if (blockUnknownSenders) {
                    stringResource(R.string.settings_privacy_messages_blocked_hint)
                } else {
                    stringResource(R.string.settings_privacy_messages_open_hint)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Calls-from-contacts-only toggle row (independent of block_unknown_senders).
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_calls_contacts_only),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
            )
            Switch(
                checked = allowCallsFromContactsOnly,
                onCheckedChange = onAllowCallsFromContactsOnlyChange,
            )
        }
        Text(
            text =
                if (allowCallsFromContactsOnly) {
                    stringResource(R.string.settings_privacy_calls_blocked_hint)
                } else {
                    stringResource(R.string.settings_privacy_calls_open_hint)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Blocked Users navigation row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToBlockedUsers)
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_privacy_blocked_users),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            )
            if (blockedPeerCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(blockedPeerCount.toString())
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
