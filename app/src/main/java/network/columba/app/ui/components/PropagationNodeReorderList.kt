@file:Suppress("MatchingDeclarationName") // Multi-declaration component: data class + helper fns + composable

package network.columba.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A display row in the propagation-node priority list.
 *
 * @property destinationHash Stable unique key for reorder + persistence.
 * @property displayName Short human label (nickname or shortened hash).
 * @property networkRestriction Raw `NetworkRestriction` name ("ANY", "WIFI_ONLY",
 *   "CELLULAR_ONLY"). `:data` cannot reference `:rns-api`, so the restriction is
 *   carried as a raw string and mapped to the enum on the `:app` side.
 */
data class PropagationNodeRow(
    val destinationHash: String,
    val displayName: String,
    val networkRestriction: String,
)

/** Display label for a raw `NetworkRestriction` name. */
fun networkRestrictionLabel(restriction: String): String =
    when (restriction.uppercase()) {
        "WIFI_ONLY" -> "Wi-Fi only"
        "CELLULAR_ONLY" -> "Cellular only"
        else -> "Any"
    }

/** Cycle to the next restriction: ANY → WIFI_ONLY → CELLULAR_ONLY → ANY. */
fun nextNetworkRestriction(current: String): String =
    when (current.uppercase()) {
        "ANY" -> "WIFI_ONLY"
        "WIFI_ONLY" -> "CELLULAR_ONLY"
        else -> "ANY"
    }

/** Move an element from index [from] to index [to] in a key list. */
fun reorderKeyOrder(keys: List<String>, from: Int, to: Int): List<String> =
    keys.toMutableList().apply {
        if (from in indices && to in 0..size) {
            val item = removeAt(from)
            add(to, item)
        }
    }

/**
 * Reorderable list of propagation-node rows with a per-row transport-restriction
 * [FilterChip] and a trailing "Remove" overflow.
 *
 * The list is a bounded [Column] (propagation-node lists are small — a handful of
 * nodes) rather than a `LazyColumn`, which keeps offset→index math and gesture
 * handling simple. Material3 BOM 2026.05.01 does not ship a lazy-list drag-and-drop
 * API (`LazyListDroppable` / `LazyListDraggableItem` are not present in this BOM),
 * so reorder is a manual [Modifier.pointerInput] on the row's leading drag-handle
 * region only.
 *
 * Gesture-conflict guarantees (the fiddliest part of this UI, per spec §5):
 * - The drag gesture is scoped to the leading 40.dp drag-handle region of each row;
 *   the [FilterChip] and "Remove" overflow are to the right of it, so a pointer-down
 *   on a chip/overflow never initiates a drag.
 * - [onReorder] is called exactly once per completed drag (on release), with the
 *   full new key order, never per-frame. The caller persists it via the DAO
 *   `reorder()` transaction.
 * - Vertical drag only: the gesture consumes vertical motion once active; a
 *   pointer-down outside the handle region is left unconsumed.
 *
 * @param items Current ordered list (typically from `observeOrdered()`).
 * @param onReorder Committed new key order, called once per drag-end.
 * @param onRestrictionChange Cycle the restriction for a row.
 * @param onRemove Remove a row by key.
 */
@Composable
fun PropagationNodeReorderList(
    items: List<PropagationNodeRow>,
    onReorder: (orderedKeys: List<String>) -> Unit,
    onRestrictionChange: (destinationHash: String, newRestriction: String) -> Unit,
    onRemove: (destinationHash: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, row ->
            PropagationNodeRowContent(
                row = row,
                index = index,
                itemCount = items.size,
                onReorderToNewIndex = { to ->
                    if (index != to) {
                        onReorder(reorderKeyOrder(items.map { it.destinationHash }, index, to))
                    }
                },
                onRestrictionChange = { onRestrictionChange(row.destinationHash, nextNetworkRestriction(it)) },
                onRemove = { onRemove(row.destinationHash) },
            )
        }
    }
}

@Composable
private fun PropagationNodeRowContent(
    row: PropagationNodeRow,
    index: Int,
    itemCount: Int,
    onReorderToNewIndex: (to: Int) -> Unit,
    onRestrictionChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    // Measured in px by onSizeChanged (reports the full Card height).
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height.toFloat() }
                .dragReorderHandle(
                    enabled = true,
                    density = density,
                    onDragStarted = { isDragging = true },
                    onDragEnded = { netOffsetPx ->
                        isDragging = false
                        if (rowHeightPx > 0f && netOffsetPx != 0f) {
                            val delta = (netOffsetPx / rowHeightPx).roundToInt()
                            val target = (index + delta).coerceIn(0, itemCount - 1)
                            onReorderToNewIndex(target)
                        }
                    },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isDragging) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 4.dp else 0.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle — the only draggable region (40.dp leading slot).
            Box(
                modifier =
                    Modifier
                        .size(HANDLE_WIDTH)
                        .testTag("prop_node_handle_${row.destinationHash}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Reorder ${row.displayName}",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Label
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )

            // Restriction chip (cycle: ANY → WIFI_ONLY → CELLULAR_ONLY → ANY)
            FilterChip(
                selected = false,
                onClick = { onRestrictionChange(row.networkRestriction) },
                label = { Text(networkRestrictionLabel(row.networkRestriction)) },
                modifier = Modifier.testTag("prop_node_chip_${row.destinationHash}"),
            )

            Spacer(modifier = Modifier.size(4.dp))

            // Overflow — Remove
            TextButton(
                onClick = onRemove,
                modifier = Modifier.testTag("prop_node_remove_${row.destinationHash}"),
            ) {
                Text("Remove")
            }
        }
    }
}

/**
 * Draggable reorder handle applied to a row.
 *
 * The gesture lives only on the leading [HANDLE_WIDTH] region of the composable the
 * modifier is attached to. A pointer-down to the right of that width is left
 * unconsumed so the chip / overflow handle it. While active, vertical motion is
 * consumed and the net offset is reported once on release via [onDragEnded].
 *
 * @param density Used to convert dp thresholds to px inside the gesture.
 * @param onDragStarted Fired once when the drag crosses the slop threshold.
 * @param onDragEnded Fired once on release with the net vertical offset (px).
 *   The caller combines it with the measured row height to compute the target
 *   index.
 */
private fun Modifier.dragReorderHandle(
    enabled: Boolean,
    density: androidx.compose.ui.unit.Density,
    onDragStarted: () -> Unit,
    onDragEnded: (netOffsetPx: Float) -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        this.pointerInput(Unit) {
            val handleWidthPx = with(density) { HANDLE_WIDTH.toPx() }
            val slopPx = with(density) { 8.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x > handleWidthPx) {
                    // Down outside the handle region — leave it unconsumed.
                    return@awaitEachGesture
                }
                var totalOffset = 0f
                var lastY = down.position.y
                var active = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull()
                    if (change == null || !change.pressed) break
                    val dy = change.position.y - lastY
                    if (!active && abs(totalOffset + dy) > slopPx) {
                        active = true
                        onDragStarted()
                    }
                    if (active) {
                        totalOffset += dy
                        change.consume()
                    }
                    lastY = change.position.y
                } while (event.changes.any { it.pressed })
                if (active) {
                    onDragEnded(totalOffset)
                }
            }
        }
    }

private val HANDLE_WIDTH = 40.dp
