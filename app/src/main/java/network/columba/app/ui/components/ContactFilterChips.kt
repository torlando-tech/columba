package network.columba.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.api.model.NodeType

private val CONTACT_TYPE_OPTIONS: List<Pair<NodeType, Int>> =
    listOf(
        NodeType.PEER to R.string.announce_filter_aspect_peers,
        NodeType.NODE to R.string.announce_filter_aspect_sites,
        NodeType.PROPAGATION_NODE to R.string.announce_filter_aspect_relays,
        NodeType.PHONE to R.string.announce_filter_aspect_audio,
    )

/**
 * Single row of contact-type filter chips for the My Contacts tab (GH issue
 * #863), matching the announce stream's aspect chip row (see
 * [AnnounceFilterChips]) so both tabs of the Contacts screen filter the same
 * way.
 *
 * Chips are **exclusive**: exactly one filter is active at a time. Tapping a
 * chip selects only that type; tapping the active chip again — or tapping All
 * — restores the unfiltered list. An empty selection is "All"; that is also
 * the only state in which contacts that never announced (no `nodeType`, hence
 * no known aspect) remain visible.
 *
 * **Collapsed mode** (`expanded = false`, compact-height windows per GH issue
 * #922): the chips are replaced by the shared "Filtering: …" pill, shown only
 * when a filter is active. Tapping the pill fires [onExpandRequest] so the
 * host screen can flip its own `expanded` state (typically driven by the
 * Filter icon in the top bar).
 */
@Composable
fun ContactFilterChips(
    selectedTypes: Set<NodeType>,
    onTypesChange: (Set<NodeType>) -> Unit,
    expanded: Boolean = true,
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Exclusive contract: a single active type lights that chip; anything else
    // (empty or a multi-type state) reads as All, matching the selection logic
    // the handlers below can produce.
    val activeType = selectedTypes.singleOrNull()
    val activeLabelRes = activeType?.let { type -> CONTACT_TYPE_OPTIONS.firstOrNull { it.first == type }?.second }

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
                    item(key = "contact-type-all") {
                        FilterChip(
                            selected = activeType == null,
                            onClick = { onTypesChange(emptySet()) },
                            label = { Text(stringResource(R.string.announce_filter_all)) },
                        )
                    }
                    items(CONTACT_TYPE_OPTIONS, key = { "contact-type-${it.first.name}" }) { (type, labelRes) ->
                        FilterChip(
                            selected = activeType == type,
                            onClick = {
                                // Exclusive: tapping the active chip snaps back to All,
                                // tapping any other chip replaces the selection with it.
                                onTypesChange(if (activeType == type) emptySet() else setOf(type))
                            },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
            AnimatedVisibility(visible = !expanded && activeLabelRes != null) {
                ActiveFilterPillRow(
                    aspectLabelRes = activeLabelRes,
                    interfaceLabelRes = null,
                    onClick = onExpandRequest,
                )
            }
        }
    }
}
