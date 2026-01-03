package com.lxmf.messenger.ui.components

import kotlin.math.max

/**
 * Layout dimensions for the reaction mode overlay.
 * Contains all the measurements needed to calculate proper scaling and positioning.
 *
 * @property screenHeight The total screen height in pixels
 * @property emojiBarHeight Height of the emoji bar in pixels (typically ~56dp)
 * @property emojiBarGap Gap between emoji bar and message in pixels (typically ~76dp)
 * @property actionButtonsHeight Height of action buttons in pixels (typically ~56dp)
 * @property actionButtonsGap Gap between message and action buttons in pixels (typically ~12dp)
 * @property topPadding Padding for status bar etc. in pixels (typically ~48dp)
 * @property bottomPadding Padding for navigation bar etc. in pixels (typically ~48dp)
 */
data class OverlayLayoutDimensions(
    val screenHeight: Float,
    val emojiBarHeight: Float,
    val emojiBarGap: Float,
    val actionButtonsHeight: Float,
    val actionButtonsGap: Float,
    val topPadding: Float,
    val bottomPadding: Float,
)

/**
 * Calculates the scale factor for a message in the reaction mode overlay.
 *
 * When a message is very large (e.g., a long text or large image), the context menu
 * (emoji bar above and action buttons below) may be pushed off screen. This function
 * calculates how much to scale down the message so that everything fits on screen.
 *
 * @param messageHeight The original height of the message in pixels
 * @param dimensions Layout dimensions for the overlay
 * @param minScale Minimum scale factor (default 0.3 to keep content readable)
 * @return Scale factor between minScale and 1.0
 */
fun calculateMessageScaleForOverlay(
    messageHeight: Int,
    dimensions: OverlayLayoutDimensions,
    minScale: Float = 0.3f,
): Float {
    if (messageHeight <= 0) return 1f

    val availableHeight = dimensions.screenHeight - dimensions.topPadding - dimensions.bottomPadding
    val uiElementsHeight = dimensions.emojiBarHeight + dimensions.emojiBarGap +
        dimensions.actionButtonsGap + dimensions.actionButtonsHeight
    val totalHeightNeeded = uiElementsHeight + messageHeight

    return if (totalHeightNeeded > availableHeight) {
        val maxMessageHeight = max(0f, availableHeight - uiElementsHeight)
        (maxMessageHeight / messageHeight).coerceIn(minScale, 1f)
    } else {
        1f
    }
}
