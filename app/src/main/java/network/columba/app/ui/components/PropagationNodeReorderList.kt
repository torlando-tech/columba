@file:Suppress("MatchingDeclarationName") // Multi-declaration component: data class + helper fns + composable

package network.columba.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.util.formatRelativeTime
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
 * @property isActive Whether this is the currently-active relay (drives the
 *   "currently active" indicator and hides the redundant "Set active now" action).
 * @property failureReason Human-readable reason the relay was last seen unreachable
 *   (e.g. "No path to relay"), or null if it has never failed. Shown as a compact,
 *   non-dismissible warning on the row.
 * @property failureAtMs Epoch millis of the last recorded failure (for the "when"), or null.
 * @property failureCode Raw LXMF state code of the last failure (for the "which state"), or
 *   null. Decoded for display via [propagationFailureCodeLabel].
 */
data class PropagationNodeRow(
    val destinationHash: String,
    val displayName: String,
    val networkRestriction: String,
    val isActive: Boolean = false,
    val failureReason: String? = null,
    val failureAtMs: Long? = null,
    val failureCode: Int? = null,
)

/** Display label for a raw `NetworkRestriction` name. */
fun networkRestrictionLabel(restriction: String): String =
    when (restriction.uppercase()) {
        "WIFI_ONLY" -> "Wi-Fi only"
        "CELLULAR_ONLY" -> "Cellular only"
        else -> "Any"
    }

/** Cycle to the next restriction: ANY -> WIFI_ONLY -> CELLULAR_ONLY -> ANY. */
fun nextNetworkRestriction(current: String): String =
    when (current.uppercase()) {
        "ANY" -> "WIFI_ONLY"
        "WIFI_ONLY" -> "CELLULAR_ONLY"
        else -> "ANY"
    }

/**
 * Short, readable label for a raw LXMF failure state code (the
 * `preferred_propagation_nodes.lastFailureCode` column). Covers both backend code spaces: the
 * Python `0xf0+` `PR_*` terminals and the native `LXMRouter.PropagationTransferState` ordinals
 * (FAILED = 8, NO_PATH = 9, NO_LINK = 10). Unknown codes render as a hex token so the exact
 * value is never lost.
 */
fun propagationFailureCodeLabel(code: Int): String =
    when (code) {
        0xf0, 9 -> "NO_PATH"
        0xf1 -> "LINK_FAILED"
        0xf2 -> "TRANSFER_FAILED"
        0xf3 -> "NO_IDENTITY"
        0xf4 -> "NO_ACCESS"
        8 -> "FAILED"
        10 -> "NO_LINK"
        else -> "0x${Integer.toHexString(code)}"
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
 * [FilterChip], a priority badge, and trailing "Set active now" / "Remove" actions.
 *
 * The list is a bounded [Column] (propagation-node lists are small — a handful of
 * nodes) rather than a `LazyColumn`, which keeps offset->index math and gesture
 * handling simple. Material3 BOM 2026.05.01 does not ship a lazy-list drag-and-drop
 * API (`LazyListDroppable` / `LazyListDraggableItem` are not present in this BOM),
 * so reorder is a manual [Modifier.pointerInput] on the row's leading drag-handle
 * region only.
 *
 * Gesture-conflict guarantees (the fiddliest part of this UI, per spec §5):
 * - The drag gesture is scoped to the leading 40.dp drag-handle region of each row;
 *   the [FilterChip] and trailing actions are to the right of it, so a pointer-down
 *   on a chip/action never initiates a drag.
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
 * @param onSetActiveNow Force a row to become the active relay immediately
 *   (useful for testing the router push). No-op for the already-active row.
 */
@Composable
fun PropagationNodeReorderList(
    items: List<PropagationNodeRow>,
    onReorder: (orderedKeys: List<String>) -> Unit,
    onRestrictionChange: (destinationHash: String, newRestriction: String) -> Unit,
    onRemove: (destinationHash: String) -> Unit,
    onSetActiveNow: (destinationHash: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // key(row.destinationHash) gives each row a stable identity, so the drag
        // gesture modifier (pointerInput) and its measured height are preserved across
        // reorders/removals instead of being re-keyed to position. This is what makes a
        // consecutive drag (or a drag after a removal) compute its target index from the
        // current layout rather than a stale one.
        items.forEachIndexed { index, row ->
            key(row.destinationHash) {
                PropagationNodeRowContent(
                    row = row,
                    priorityPosition = index + 1,
                    index = index,
                    itemCount = items.size,
                    onReorderToNewIndex = { to ->
                        if (index != to) {
                            onReorder(reorderKeyOrder(items.map { it.destinationHash }, index, to))
                        }
                    },
                    onRestrictionChange = { onRestrictionChange(row.destinationHash, nextNetworkRestriction(it)) },
                    onRemove = { onRemove(row.destinationHash) },
                    onSetActiveNow = { onSetActiveNow(row.destinationHash) },
                )
            }
        }
    }
}

@Composable
private fun PropagationNodeRowContent(
    row: PropagationNodeRow,
    priorityPosition: Int,
    index: Int,
    itemCount: Int,
    onReorderToNewIndex: (to: Int) -> Unit,
    onRestrictionChange: (String) -> Unit,
    onRemove: () -> Unit,
    onSetActiveNow: () -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    // Measured in px by onSizeChanged (reports the full Card height).
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    // The drag gesture lives in `pointerInput(Unit)`, which runs its block ONCE and keeps the
    // original lambdas for the component's lifetime. A row keeps its identity across reorders
    // (stable `key(destinationHash)` in the parent), so on a consecutive drag the surviving
    // gesture would otherwise read the *first* composition's `index`/`itemCount`/
    // `onReorderToNewIndex` and save a wrong order. `rememberUpdatedState` gives a stable
    // reference whose `.value` is always the current recomposition's value, so the long-lived
    // gesture reads the live list state on release.
    val currentIndex by rememberUpdatedState(index)
    val currentItemCount by rememberUpdatedState(itemCount)
    val currentOnReorderToNewIndex by rememberUpdatedState(onReorderToNewIndex)
    // A real RNS destination hash is 32 hex chars; the de-risk fixtures use short
    // labels. Only show the short-hash line for real hashes to avoid clutter.
    val showShortHash = row.destinationHash.length >= 8

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
                            val target = (currentIndex + delta).coerceIn(0, currentItemCount - 1)
                            currentOnReorderToNewIndex(target)
                        }
                    },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (row.isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (isDragging) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 4.dp else 0.dp),
    ) {
        // Two rows: the identity (badge + name + failure reason) on top, the restriction
        // chip + action buttons on a second row. Putting the chip and buttons on their own
        // row gives the failure-reason line the full content width, so it isn't truncated to
        // "No path to re…" when a long node name is present.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle — the only draggable region (40.dp leading slot). The drag gesture
            // itself lives on the Card's leading region, so this visual box only needs to sit
            // in that slot; it spans both rows so it reads as one handle for the whole card.
            Box(
                modifier =
                    Modifier
                        .width(HANDLE_WIDTH)
                        .fillMaxHeight()
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

            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                // Row 1 — identity: priority badge + name + active/short-hash indicator.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = priorityPosition.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.isActive) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Current relay",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (showShortHash) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = row.destinationHash.take(8),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                // Compact, non-dismissible warning: why this relay was last seen unreachable
                // (the recorded LXMF state code, decoded) and when. Now on its own full-width
                // line, so the explanation isn't clipped. Auto-clears: the row is derived from
                // the DB and the failure is cleared on a successful sync (or expires via the
                // unreachable cooldown), so this line disappears on its own.
                if (row.failureReason != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            // Explanation + decoded state code + when, e.g.
                            // "No path to relay (NO_PATH) · 5 minutes ago".
                            append(row.failureReason)
                            val code = row.failureCode
                            if (code != null) {
                                append(" (")
                                append(propagationFailureCodeLabel(code))
                                append(")")
                            }
                            val at = row.failureAtMs
                            if (at != null) {
                                append(" · ")
                                append(formatRelativeTime(at))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("prop_node_failure_${row.destinationHash}"),
                    )
                }

                // Row 2 — controls: restriction chip + set-active + remove.
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Restriction chip (cycle: ANY -> WIFI_ONLY -> CELLULAR_ONLY -> ANY)
                    FilterChip(
                        selected = false,
                        onClick = { onRestrictionChange(row.networkRestriction) },
                        label = { Text(networkRestrictionLabel(row.networkRestriction)) },
                        modifier = Modifier.testTag("prop_node_chip_${row.destinationHash}"),
                    )

                    Spacer(modifier = Modifier.size(4.dp))

                    // Set-active-now (only when not already active).
                    if (!row.isActive) {
                        IconButton(
                            onClick = onSetActiveNow,
                            modifier = Modifier.testTag("prop_node_setactive_${row.destinationHash}"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Set active now",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Overflow — Remove.
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.testTag("prop_node_remove_${row.destinationHash}"),
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

/**
 * Draggable reorder handle applied to a row.
 *
 * The gesture lives only on the leading [HANDLE_WIDTH] region of the composable the
 * modifier is attached to. A pointer-down to the right of that width is left
 * unconsumed so the chip / actions handle it. While active, vertical motion is
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
                    // Down outside the handle region — leave it unconsumed so the chip,
                    // the trailing actions, and the parent verticalScroll own that touch.
                    return@awaitEachGesture
                }
                // The handle region owns this touch: consume the down and every subsequent
                // event for this pointer so the surrounding verticalScroll cannot claim it
                // mid-drag. A tap on the handle does nothing (no reorder); a vertical drag
                // reorders. This is what makes drag-vs-scroll unambiguous now that the
                // screen scrolls.
                down.consume()
                var totalOffset = 0f
                var lastY = down.position.y
                var active = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull()
                    if (change == null || !change.pressed) break
                    val dy = change.position.y - lastY
                    // Accumulate from the very first movement so a slow drag that crosses
                    // the slop threshold in small steps still activates. (Before this fix
                    // the check only tested a single event's dy, so stepwise drags under
                    // 8 dp each never started.)
                    totalOffset += dy
                    if (!active && abs(totalOffset) > slopPx) {
                        active = true
                        onDragStarted()
                    }
                    change.consume()
                    lastY = change.position.y
                } while (event.changes.any { it.pressed })
                if (active) {
                    onDragEnded(totalOffset)
                }
            }
        }
    }

private val HANDLE_WIDTH = 40.dp
