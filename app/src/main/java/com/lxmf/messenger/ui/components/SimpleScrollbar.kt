package com.lxmf.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A visual-only scrollbar modifier for LazyColumn.
 * Fades in when scrolling, fades out when idle.
 *
 * @param state The LazyListState of the LazyColumn
 * @param width Width of the scrollbar indicator
 * @param minThumbHeight Minimum height for the scrollbar thumb
 * @param endPadding Padding from the right edge
 * @param reverseLayout Set to true if the LazyColumn uses reverseLayout = true
 */
@Composable
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    minThumbHeight: Dp = 32.dp,
    endPadding: Dp = 2.dp,
    reverseLayout: Boolean = false,
): Modifier {
    val scrollbarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = if (state.isScrollInProgress) 150 else 1500,
        ),
        label = "scrollbarAlpha",
    )

    return this.drawWithContent {
        drawContent()

        val layoutInfo = state.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount

        if (alpha > 0f && visibleItems.isNotEmpty() && totalItems > visibleItems.size) {
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()

            // Calculate average item size from visible items
            val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size

            // Estimated total content height
            val totalContentHeight = avgItemSize * totalItems

            if (totalContentHeight > viewportHeight) {
                // Scrollbar thumb height proportional to visible ratio
                val thumbHeight = (viewportHeight / totalContentHeight * viewportHeight)
                    .coerceIn(minThumbHeight.toPx(), viewportHeight * 0.9f)

                // Calculate scroll fraction (0 = start, 1 = end)
                val scrollOffset = state.firstVisibleItemIndex * avgItemSize +
                    state.firstVisibleItemScrollOffset
                val maxScroll = totalContentHeight - viewportHeight
                val scrollFraction = (scrollOffset / maxScroll).coerceIn(0f, 1f)

                // For reversed layout, flip the scrollbar position
                val scrollbarY = if (reverseLayout) {
                    (1f - scrollFraction) * (viewportHeight - thumbHeight)
                } else {
                    scrollFraction * (viewportHeight - thumbHeight)
                }

                drawRoundRect(
                    color = scrollbarColor.copy(alpha = scrollbarColor.alpha * alpha),
                    topLeft = Offset(
                        x = size.width - width.toPx() - endPadding.toPx(),
                        y = scrollbarY,
                    ),
                    size = Size(width.toPx(), thumbHeight),
                    cornerRadius = CornerRadius(width.toPx() / 2f, width.toPx() / 2f),
                )
            }
        }
    }
}
