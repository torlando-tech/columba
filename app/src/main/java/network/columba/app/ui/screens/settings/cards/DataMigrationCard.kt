package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.ui.components.CollapsibleSettingsCard

@Composable
fun DataMigrationCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNavigateToMigration: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.settings_migration_title),
        icon = Icons.Default.ImportExport,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = stringResource(R.string.settings_migration_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Action button
        OutlinedButton(
            onClick = onNavigateToMigration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.ImportExport,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_migration_export_import))
        }
    }
}
