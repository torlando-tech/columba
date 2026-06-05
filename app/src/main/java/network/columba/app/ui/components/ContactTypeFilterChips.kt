package network.columba.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import network.columba.app.viewmodel.ContactTypeFilter

/**
 * Single collapsible row of contact-type filter chips for the My Contacts tab
 * (GH issue #863): All / Peers / Sites / Relays / Audio.
 *
 * Selection is **exclusive** — exactly one chip is active at a time. Tapping a
 * non-All chip selects only that type; tapping the active chip again snaps back
 * to All so users always have a no-hunt return path to the unfiltered list.
 *
 * Mirrors the announce-stream aspect row in [AnnounceFilterChips] so both
 * Contacts tabs read the same way, including the collapsed-state contract used
 * on compact-height windows (GH issue #922): when [expanded] is false the chip
 * row is hidden and a slim "active filter" pill replaces it — but only when a
 * filter is actually restricting the view (selection != All). Tapping the pill
 * fires [onExpandRequest] so the host can flip its own expand state (typically
 * driven by a Filter icon in the top bar).
 */
@Composable
fun ContactTypeFilterChips(
    selected: ContactTypeFilter,
    onSelectedChange: (ContactTypeFilter) -> Unit,
    expanded: Boolean = true,
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            AnimatedVisibility(visible = expanded) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ContactTypeFilter.entries.toList(), key = { it.name }) { filter ->
                        FilterChip(
                            selected = selected == filter,
                            onClick = {
                                // Tapping the active non-All chip snaps back to All.
                                val next =
                                    if (selected == filter && filter != ContactTypeFilter.ALL) {
                                        ContactTypeFilter.ALL
                                    } else {
                                        filter
                                    }
                                onSelectedChange(next)
                            },
                            label = { Text(filter.label) },
                        )
                    }
                }
            }
            AnimatedVisibility(visible = !expanded && selected != ContactTypeFilter.ALL) {
                ActiveContactFilterPillRow(
                    label = selected.label,
                    onClick = onExpandRequest,
                )
            }
        }
    }
}

/**
 * Slim "active filter" summary shown when [ContactTypeFilterChips] is collapsed.
 * The whole strip is one touch target that asks the host to expand the chip row.
 */
@Composable
private fun ActiveContactFilterPillRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "Active filter: $label. Tap to edit."
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Filtering: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
