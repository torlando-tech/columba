package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import network.columba.app.data.model.NotificationPrivacyLevel

/**
 * Exclusive selector for [NotificationPrivacyLevel].
 *
 * Radio buttons rather than a switch: there are three levels, and the per-level
 * descriptions are what make the privacy trade-off legible.
 */
@Composable
fun NotificationPrivacyChooser(
    selected: NotificationPrivacyLevel,
    onSelect: (NotificationPrivacyLevel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NotificationPrivacyLevel.entries.forEach { level ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = level == selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(level) },
                        )
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = level == selected,
                    // Row handles the click; null keeps a single accessibility target.
                    onClick = null,
                    enabled = enabled,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column {
                    Text(
                        text = stringResource(level.displayNameRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color =
                            if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Text(
                        text = stringResource(level.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
