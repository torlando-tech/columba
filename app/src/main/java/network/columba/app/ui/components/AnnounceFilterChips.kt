package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.columba.app.data.model.InterfaceType
import network.columba.app.reticulum.model.NodeType

private enum class AspectChip(
    val label: String,
) {
    PEER("Peers"),
    NODE("Sites"),
    RELAY("Relays"),
    AUDIO("Audio"),
}

private val ALL_ASPECTS = setOf(AspectChip.PEER, AspectChip.NODE, AspectChip.RELAY, AspectChip.AUDIO)

private val INTERFACE_OPTIONS: List<Pair<InterfaceType, String>> =
    listOf(
        InterfaceType.AUTO_INTERFACE to "Local",
        InterfaceType.TCP_CLIENT to "TCP",
        InterfaceType.ANDROID_BLE to "Bluetooth",
        InterfaceType.RNODE to "RNode",
        InterfaceType.UNKNOWN to "Other",
    )

/**
 * Two always-visible rows of filter chips for the announce stream:
 *   1. Aspect: All / Peers / Sites / Relays / Audio
 *   2. Interface: All / Local / TCP / Bluetooth / RNode / Other
 *
 * The "All" chip in each row represents "no filter active". Tapping it clears
 * the row; tapping any other chip deselects All. Note the two rows use different
 * underlying semantics (aspect = positive include, interface = restrict), but
 * both surface the same "All = unfiltered" UX. The aspect row prevents the user
 * from deselecting every chip (would otherwise produce a blank list with no
 * selected indicator) — the final tap snaps back to All.
 */
@Composable
fun AnnounceFilterChips(
    selectedNodeTypes: Set<NodeType>,
    showAudio: Boolean,
    selectedInterfaceTypes: Set<InterfaceType>,
    onNodeTypesChange: (Set<NodeType>) -> Unit,
    onShowAudioChange: (Boolean) -> Unit,
    onInterfaceTypesChange: (Set<InterfaceType>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeAspects: Set<AspectChip> =
        buildSet {
            if (selectedNodeTypes.contains(NodeType.PEER)) add(AspectChip.PEER)
            if (selectedNodeTypes.contains(NodeType.NODE)) add(AspectChip.NODE)
            if (selectedNodeTypes.contains(NodeType.PROPAGATION_NODE)) add(AspectChip.RELAY)
            if (showAudio) add(AspectChip.AUDIO)
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        androidx.compose.foundation.layout.Column {
            AspectChipRow(
                activeAspects = activeAspects,
                onAspectToggle = { aspect ->
                    toggleAspect(
                        aspect = aspect,
                        active = activeAspects,
                        selectedNodeTypes = selectedNodeTypes,
                        onNodeTypesChange = onNodeTypesChange,
                        onShowAudioChange = onShowAudioChange,
                    )
                },
                onAllClick = {
                    onNodeTypesChange(setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE))
                    onShowAudioChange(true)
                },
            )
            InterfaceChipRow(
                selected = selectedInterfaceTypes,
                onToggle = { type ->
                    val next =
                        if (selectedInterfaceTypes.contains(type)) {
                            selectedInterfaceTypes - type
                        } else {
                            selectedInterfaceTypes + type
                        }
                    onInterfaceTypesChange(next)
                },
                onAllClick = { onInterfaceTypesChange(emptySet()) },
            )
        }
    }
}

@Composable
private fun AspectChipRow(
    activeAspects: Set<AspectChip>,
    onAspectToggle: (AspectChip) -> Unit,
    onAllClick: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "aspect-all") {
            FilterChip(
                selected = activeAspects == ALL_ASPECTS,
                onClick = onAllClick,
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        items(AspectChip.entries.toList(), key = { "aspect-${it.name}" }) { aspect ->
            FilterChip(
                selected = activeAspects.contains(aspect),
                onClick = { onAspectToggle(aspect) },
                label = { Text(aspect.label) },
            )
        }
    }
}

@Composable
private fun InterfaceChipRow(
    selected: Set<InterfaceType>,
    onToggle: (InterfaceType) -> Unit,
    onAllClick: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "iface-all") {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = onAllClick,
                label = { Text("All") },
            )
        }
        items(INTERFACE_OPTIONS, key = { "iface-${it.first.name}" }) { (type, label) ->
            FilterChip(
                selected = selected.contains(type),
                onClick = { onToggle(type) },
                label = { Text(label) },
            )
        }
    }
}

private fun toggleAspect(
    aspect: AspectChip,
    active: Set<AspectChip>,
    selectedNodeTypes: Set<NodeType>,
    onNodeTypesChange: (Set<NodeType>) -> Unit,
    onShowAudioChange: (Boolean) -> Unit,
) {
    val isActive = active.contains(aspect)
    // If this tap would leave no aspect chip selected, snap back to All instead
    // of producing a blank list with no visual indicator of what's filtering it.
    if (isActive && active.size == 1) {
        onNodeTypesChange(setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE))
        onShowAudioChange(true)
        return
    }
    when (aspect) {
        AspectChip.PEER -> onNodeTypesChange(selectedNodeTypes.withToggled(NodeType.PEER, !isActive))
        AspectChip.NODE -> onNodeTypesChange(selectedNodeTypes.withToggled(NodeType.NODE, !isActive))
        AspectChip.RELAY ->
            onNodeTypesChange(selectedNodeTypes.withToggled(NodeType.PROPAGATION_NODE, !isActive))
        AspectChip.AUDIO -> onShowAudioChange(!isActive)
    }
}

private fun Set<NodeType>.withToggled(
    type: NodeType,
    include: Boolean,
): Set<NodeType> = if (include) this + type else this - type
