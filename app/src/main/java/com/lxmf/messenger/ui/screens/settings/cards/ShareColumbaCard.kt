package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
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
fun ShareColumbaCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNavigateToApkSharing: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.share_columba_title),
        icon = Icons.Default.Share,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text = stringResource(R.string.share_columba_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = onNavigateToApkSharing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.share_columba_action))
        }
    }
}
