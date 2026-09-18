package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.columba.app.navigation.NavTab
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Settings card for configuring the bottom navigation bar.
 *
 * Users toggle which shortcuts appear and reorder them with up/down controls.
 * The Settings tab is pinned (always present, always last) and rendered as a
 * locked row. At most [NavTab.MAX_TABS] tabs total (including Settings) can be
 * active; the add-chips disable at capacity, and the last editable tab cannot
 * be removed so the bar is never just Settings.
 */
@Composable
fun BottomNavigationCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    tabs: List<NavTab>,
    onTabsChange: (List<NavTab>) -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Bottom Navigation",
        icon = Icons.Default.Sensors,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text = "Choose which shortcuts appear in the bottom bar (max ${NavTab.MAX_TABS}) and use the arrows to arrange them. Settings is always pinned to the end.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Editable list: every tab except the pinned Settings row.
        val editable = tabs.filter { it != NavTab.SETTINGS }
        val available = NavTab.CONFIGURABLE.filter { it !in editable }
        val atCapacity = editable.size >= NavTab.MAX_TABS - 1
        // Keep at least one shortcut besides Settings so the bar always has
        // somewhere to go besides configuration.
        val canRemoveAny = editable.size > 1

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            editable.forEachIndexed { index, tab ->
                TabRow(
                    tab = tab,
                    canMoveUp = index > 0,
                    canMoveDown = index < editable.lastIndex,
                    onMoveUp = {
                        onTabsChange(editable.toMutableList().also { list ->
                            list.removeAt(index)
                            list.add(index - 1, tab)
                        } + NavTab.SETTINGS)
                    },
                    onMoveDown = {
                        onTabsChange(editable.toMutableList().also { list ->
                            list.removeAt(index)
                            list.add(index + 1, tab)
                        } + NavTab.SETTINGS)
                    },
                    onRemove = if (canRemoveAny) {
                        { onTabsChange((editable - tab) + NavTab.SETTINGS) }
                    } else {
                        null
                    },
                )
            }

            // Pinned Settings row — no remove/reorder affordances.
            TabRow(
                tab = NavTab.SETTINGS,
                canMoveUp = false,
                canMoveDown = false,
                onMoveUp = {},
                onMoveDown = {},
                onRemove = null,
            )
        }

        if (available.isNotEmpty()) {
            Text(
                text = if (atCapacity) {
                    "Remove a tab to add another"
                } else {
                    "Add a shortcut:"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { tab ->
                    FilterChip(
                        selected = false,
                        enabled = !atCapacity,
                        onClick = {
                            onTabsChange((editable + tab) + NavTab.SETTINGS)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabRow(
    tab: NavTab,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(tab.label, style = MaterialTheme.typography.bodyLarge)
            if (tab == NavTab.SETTINGS) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Remove ${tab.label} from bottom bar",
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Move ${tab.label} up",
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Move ${tab.label} down",
            )
        }
    }
}
