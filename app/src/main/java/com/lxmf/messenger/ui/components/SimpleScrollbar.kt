package com.lxmf.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        animationSpec =
            tween(
                durationMillis = if (state.isScrollInProgress) 150 else 1500,
            ),
        label = "scrollbarAlpha",
    )

    // Compute thumb proportion and scroll fraction in composable scope so they can be animated.
    // This prevents jumps when totalItemsCount changes (infinite scroll loading more items).
    val thumbProportion by remember {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItems = layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems <= visibleItems.size) {
                1f
            } else {
                val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
                val totalContentHeight = avgItemSize * totalItems
                val viewportHeight =
                    (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
                (viewportHeight / totalContentHeight).coerceIn(0.01f, 1f)
            }
        }
    }

    val scrollFraction by remember {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItems = layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems <= visibleItems.size) {
                0f
            } else {
                val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
                val totalContentHeight = avgItemSize * totalItems
                val viewportHeight =
                    (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
                val scrollOffset =
                    state.firstVisibleItemIndex * avgItemSize +
                        state.firstVisibleItemScrollOffset
                val maxScroll = totalContentHeight - viewportHeight
                if (maxScroll <= 0f) 0f else (scrollOffset / maxScroll).coerceIn(0f, 1f)
            }
        }
    }

    val animatedThumbProportion by animateFloatAsState(
        targetValue = thumbProportion,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbarThumb",
    )

    val animatedScrollFraction by animateFloatAsState(
        targetValue = scrollFraction,
        animationSpec = tween(durationMillis = 100),
        label = "scrollbarPosition",
    )

    return this.drawWithContent {
        drawContent()

        if (alpha > 0f && animatedThumbProportion < 1f) {
            val viewportHeight = size.height

            val thumbHeight =
                (animatedThumbProportion * viewportHeight)
                    .coerceIn(minThumbHeight.toPx(), viewportHeight * 0.9f)

            val scrollbarY =
                if (reverseLayout) {
                    (1f - animatedScrollFraction) * (viewportHeight - thumbHeight)
                } else {
                    animatedScrollFraction * (viewportHeight - thumbHeight)
                }

            drawRoundRect(
                color = scrollbarColor.copy(alpha = scrollbarColor.alpha * alpha),
                topLeft =
                    Offset(
                        x = size.width - width.toPx() - endPadding.toPx(),
                        y = scrollbarY,
                    ),
                size = Size(width.toPx(), thumbHeight),
                cornerRadius = CornerRadius(width.toPx() / 2f, width.toPx() / 2f),
            )
        }
    }
}
