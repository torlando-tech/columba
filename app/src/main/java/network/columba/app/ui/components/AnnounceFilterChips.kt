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
import androidx.compose.material3.FilterChipDefaults
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
import network.columba.app.data.model.InterfaceType
import network.columba.app.rns.api.model.NodeType

private enum class AspectChip(
    val label: String,
) {
    PEER("Peers"),
    NODE("Sites"),
    RELAY("Relays"),
    AUDIO("Audio"),
}

private val INTERFACE_OPTIONS: List<Pair<InterfaceType, String>> =
    listOf(
        InterfaceType.AUTO to "Local",
        InterfaceType.TCP_CLIENT to "TCP",
        InterfaceType.TCP_SERVER to "TCP Server",
        InterfaceType.BLE to "Bluetooth",
        InterfaceType.RNODE to "RNode",
        InterfaceType.UNKNOWN to "Other",
    )

/**
 * Two collapsible rows of filter chips for the announce stream:
 *   1. Aspect: All / Peers / Sites / Relays / Audio
 *   2. Interface: All / Local / TCP / TCP Server / Bluetooth / RNode / Other
 *
 * Chips are **exclusive** within each row — exactly one is active at a time
 * (see GH issue #862). Tapping any non-All chip selects only that one; tapping
 * All restores the unfiltered view; tapping the currently-active chip again
 * snaps back to All so users have a no-hunt return path. The two rows still
 * use different underlying semantics (aspect = positive include, interface =
 * restrict to set), but both surface a single-active-chip UX.
 *
 * The aspect row only lights up an individual chip when exactly one aspect
 * is active; every other shape (empty, full set, or a partial "messy" state
 * carried over from a pre-PR persisted ViewModel) falls back to the All chip
 * — otherwise multiple chips would light up alongside All and contradict the
 * exclusive contract. The user's next tap normalises state via the exclusive
 * select path.
 *
 * **Collapsed mode** (`expanded = false`, used on compact-height windows per
 * GH issue #922): the chip rows are hidden and a slim "active filter" pill
 * row replaces them — but only when at least one filter is restricting the
 * view. When both rows are All (unfiltered), collapsed mode renders nothing,
 * giving the announce list maximum vertical real estate in landscape. Tapping
 * the pill row fires [onExpandRequest] so the host screen can flip its own
 * `expanded` state (typically driven by a Filter icon in the top bar).
 */
@Composable
fun AnnounceFilterChips(
    selectedNodeTypes: Set<NodeType>,
    showAudio: Boolean,
    selectedInterfaceTypes: Set<InterfaceType>,
    onNodeTypesChange: (Set<NodeType>) -> Unit,
    onShowAudioChange: (Boolean) -> Unit,
    onInterfaceTypesChange: (Set<InterfaceType>) -> Unit,
    expanded: Boolean = true,
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeAspects: Set<AspectChip> =
        buildSet {
            if (selectedNodeTypes.contains(NodeType.PEER)) add(AspectChip.PEER)
            if (selectedNodeTypes.contains(NodeType.NODE)) add(AspectChip.NODE)
            if (selectedNodeTypes.contains(NodeType.PROPAGATION_NODE)) add(AspectChip.RELAY)
            if (showAudio) add(AspectChip.AUDIO)
        }

    // Resolve the active labels using the same single-active rule the chip
    // rows themselves use — anything that isn't "exactly one chip" reads as
    // "All" (no pill) so the collapsed state matches the expanded highlights.
    val activeAspectLabel = activeAspects.singleOrNull()?.label
    val activeInterfaceLabel =
        selectedInterfaceTypes.singleOrNull()?.let { type ->
            INTERFACE_OPTIONS.firstOrNull { it.first == type }?.second
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            AnimatedVisibility(visible = expanded) {
                Column {
                    AspectChipRow(
                        activeAspects = activeAspects,
                        onAspectSelect = { aspect ->
                            selectAspectExclusive(
                                aspect = aspect,
                                active = activeAspects,
                                onNodeTypesChange = onNodeTypesChange,
                                onShowAudioChange = onShowAudioChange,
                            )
                        },
                        onAllClick = {
                            onNodeTypesChange(
                                setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
                            )
                            onShowAudioChange(true)
                        },
                    )
                    InterfaceChipRow(
                        selected = selectedInterfaceTypes,
                        onSelect = { type ->
                            // Exclusive: tapping the only-active chip snaps back to All
                            // (empty); tapping any other chip replaces the selection
                            // with just this one.
                            val next =
                                if (selectedInterfaceTypes == setOf(type)) {
                                    emptySet()
                                } else {
                                    setOf(type)
                                }
                            onInterfaceTypesChange(next)
                        },
                        onAllClick = { onInterfaceTypesChange(emptySet()) },
                    )
                }
            }
            AnimatedVisibility(
                visible = !expanded && (activeAspectLabel != null || activeInterfaceLabel != null),
            ) {
                ActiveFilterPillRow(
                    aspectLabel = activeAspectLabel,
                    interfaceLabel = activeInterfaceLabel,
                    onClick = onExpandRequest,
                )
            }
        }
    }
}

/**
 * Slim "active filter" summary shown when [AnnounceFilterChips] is collapsed.
 * Tapping anywhere on the row asks the host to expand the chip rows; the
 * whole strip is one touch target on purpose — per-pill remove buttons would
 * fight the chip rows' exclusive contract.
 */
@Composable
private fun ActiveFilterPillRow(
    aspectLabel: String?,
    interfaceLabel: String?,
    onClick: () -> Unit,
) {
    val labels = listOfNotNull(aspectLabel, interfaceLabel)
    val joined = labels.joinToString(" • ")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "Active filters: $joined. Tap to edit."
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Filtering: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = joined,
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

@Composable
private fun AspectChipRow(
    activeAspects: Set<AspectChip>,
    onAspectSelect: (AspectChip) -> Unit,
    onAllClick: () -> Unit,
) {
    // Under the exclusive contract, the per-aspect chips light up only when
    // exactly one aspect is active. Any other shape — empty, the full set, or
    // a "messy" multi-not-full state inherited from a pre-PR persisted
    // ViewModel — falls back to "All highlighted". This keeps the row visually
    // consistent with the contract even for input states the chip handlers
    // themselves can't produce, and the user's first tap then normalises the
    // underlying state via the exclusive select path.
    val isAllActive = activeAspects.size != 1
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "aspect-all") {
            FilterChip(
                selected = isAllActive,
                onClick = onAllClick,
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        items(AspectChip.entries.toList(), key = { "aspect-${it.name}" }) { aspect ->
            FilterChip(
                selected = !isAllActive && activeAspects.contains(aspect),
                onClick = { onAspectSelect(aspect) },
                label = { Text(aspect.label) },
            )
        }
    }
}

@Composable
private fun InterfaceChipRow(
    selected: Set<InterfaceType>,
    onSelect: (InterfaceType) -> Unit,
    onAllClick: () -> Unit,
) {
    // Mirror the aspect row's robustness: only highlight an individual chip
    // when exactly one type is restricted. Empty (no restriction) and any
    // multi-type partial state from a pre-PR persisted ViewModel fall back
    // to "All highlighted" so the row stays visually consistent with the
    // exclusive contract.
    val isAllActive = selected.size != 1
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "iface-all") {
            FilterChip(
                selected = isAllActive,
                onClick = onAllClick,
                label = { Text("All") },
            )
        }
        items(INTERFACE_OPTIONS, key = { "iface-${it.first.name}" }) { (type, label) ->
            FilterChip(
                selected = !isAllActive && selected.contains(type),
                onClick = { onSelect(type) },
                label = { Text(label) },
            )
        }
    }
}

private fun selectAspectExclusive(
    aspect: AspectChip,
    active: Set<AspectChip>,
    onNodeTypesChange: (Set<NodeType>) -> Unit,
    onShowAudioChange: (Boolean) -> Unit,
) {
    // Tapping the only-active chip snaps back to All — gives users a return
    // path to the unfiltered view without hunting for the All chip.
    if (active == setOf(aspect)) {
        onNodeTypesChange(setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE))
        onShowAudioChange(true)
        return
    }
    // Otherwise: select only this aspect, deselect everything else (exclusive).
    when (aspect) {
        AspectChip.PEER -> {
            onNodeTypesChange(setOf(NodeType.PEER))
            onShowAudioChange(false)
        }
        AspectChip.NODE -> {
            onNodeTypesChange(setOf(NodeType.NODE))
            onShowAudioChange(false)
        }
        AspectChip.RELAY -> {
            onNodeTypesChange(setOf(NodeType.PROPAGATION_NODE))
            onShowAudioChange(false)
        }
        AspectChip.AUDIO -> {
            onNodeTypesChange(emptySet())
            onShowAudioChange(true)
        }
    }
}
