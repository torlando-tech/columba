package network.columba.app.ui.components

import kotlin.math.max
import kotlin.math.min

/**
 * Maximum height (in physical pixels) of the message snapshot captured for the
 * reaction mode overlay. GPUs on supported devices cap hardware textures well
 * below this, so capturing a full-height bitmap of a very tall bubble can
 * silently fail (blank snapshot). Recording at most this many pixels keeps the
 * capture within the safe texture size on every supported device.
 */
const val OVERLAY_MAX_CAPTURE_HEIGHT_PX = 4096

/**
 * Minimum scale applied to the message preview in the pinned (overflow) layout.
 * The preview is shrunk to fit the space between the bars until shrinking it
 * further would make the text illegible; below this floor the preview is capped
 * and scrolls internally instead.
 */
const val OVERLAY_MIN_PREVIEW_SCALE = 0.35f

/**
 * Layout dimensions for the reaction mode overlay.
 * Contains all the measurements needed to calculate proper positioning.
 *
 * @property screenHeight The total screen height in pixels
 * @property emojiBarHeight Height of the emoji bar in pixels (typically ~56dp)
 * @property emojiBarGap Gap between emoji bar and message in pixels (typically ~76dp)
 * @property actionButtonsHeight Height of the action buttons in pixels (typically ~56dp)
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
 * Resolved on-screen layout for the reaction mode overlay.
 *
 * All values are in physical pixels. [messageFinalY] is the top of the message's
 * visible area, [messageContainerHeight] is the height of that area, and
 * [bitmapHeight] is the height of the message snapshot bitmap as captured.
 * [previewScale] scales the snapshot so the whole preview is visible: it is 1f
 * for messages that fit, and for the pinned (overflow) layout it shrinks the
 * preview to the space between the bars down to [OVERLAY_MIN_PREVIEW_SCALE].
 * When [messageScrollable] is true the scaled preview still exceeds the
 * viewport, which then scrolls to reveal the rest.
 *
 * The layout guarantees the emoji bar and the action buttons are always fully
 * on screen, and the message preview is never blank or pushed off screen.
 *
 * @property messageFinalY Top (Y) of the message's visible area
 * @property messageContainerHeight Visible height of the message area
 * @property bitmapHeight Height of the message snapshot bitmap as captured
 * @property emojiBarY Top (Y) of the emoji bar
 * @property actionButtonsY Top (Y) of the action buttons
 * @property isOverflow True when the pinned (top/bottom) layout is in use
 * @property messageScrollable True when the scaled preview is taller than the viewport
 * @property previewScale Scale factor applied to the snapshot preview (1f = full size)
 */
data class OverlayLayout(
    val messageFinalY: Float,
    val messageContainerHeight: Float,
    val bitmapHeight: Float,
    val emojiBarY: Float,
    val actionButtonsY: Float,
    val isOverflow: Boolean,
    val messageScrollable: Boolean,
    val previewScale: Float = 1f,
) {
    /** Height of the message preview after [previewScale] is applied. */
    val scaledPreviewHeight: Float
        get() = bitmapHeight * previewScale

    /**
     * True when the message, the emoji bar, and the action buttons are all
     * within the screen bounds.
     */
    fun fitsOnScreen(dimensions: OverlayLayoutDimensions): Boolean {
        return emojiBarY >= 0f &&
            emojiBarY + dimensions.emojiBarHeight <= dimensions.screenHeight &&
            actionButtonsY >= 0f &&
            actionButtonsY + dimensions.actionButtonsHeight <= dimensions.screenHeight &&
            messageFinalY >= 0f &&
            messageFinalY + messageContainerHeight <= dimensions.screenHeight
    }
}

/**
 * Resolves the on-screen layout for the reaction mode overlay.
 *
 * Messages that fit are centered at full size, with the emoji bar above and the
 * action buttons below. A message that does not fit pins the bars to the top
 * and bottom safe areas and shrinks the preview to the space between them, down
 * to [OVERLAY_MIN_PREVIEW_SCALE]. Only when the preview at that floor is still
 * taller than the space between the bars does it get capped and scroll
 * internally. The preview is therefore always visible and at a legible size for
 * the range of messages realistic on a phone (the capture is separately capped
 * at [OVERLAY_MAX_CAPTURE_HEIGHT_PX]).
 *
 * @param messageHeight The height of the message snapshot bitmap in pixels
 * @param dimensions Layout dimensions for the overlay
 */
fun calculateOverlayLayout(
    messageHeight: Int,
    dimensions: OverlayLayoutDimensions,
): OverlayLayout {
    if (messageHeight <= 0) {
        // Degenerate zero-size snapshot: keep the centered layout; nothing to cap.
        val y = dimensions.screenHeight / 2f
        return OverlayLayout(
            messageFinalY = y,
            messageContainerHeight = 0f,
            bitmapHeight = 0f,
            emojiBarY = (y - dimensions.emojiBarGap).coerceAtLeast(0f),
            actionButtonsY = (y + dimensions.actionButtonsGap).coerceAtMost(dimensions.screenHeight),
            isOverflow = false,
            messageScrollable = false,
            previewScale = 1f,
        )
    }

    // Centered placement: message in the middle, bars adjacent.
    val centerY = dimensions.screenHeight / 2f - messageHeight / 2f
    val centeredEmojiY = centerY - dimensions.emojiBarGap
    val centeredButtonsY = centerY + messageHeight + dimensions.actionButtonsGap
    val fitsCentered =
        centeredEmojiY >= dimensions.topPadding &&
            centeredButtonsY + dimensions.actionButtonsHeight <=
                dimensions.screenHeight - dimensions.bottomPadding
    if (fitsCentered) {
        return OverlayLayout(
            messageFinalY = centerY,
            messageContainerHeight = messageHeight.toFloat(),
            bitmapHeight = messageHeight.toFloat(),
            emojiBarY = centeredEmojiY,
            actionButtonsY = centeredButtonsY,
            isOverflow = false,
            messageScrollable = false,
            previewScale = 1f,
        )
    }

    // Pinned overflow layout: bars fixed to the safe-area edges, the preview
    // shrunk to fit the space between them (down to the minimum legible scale).
    val emojiY = dimensions.topPadding
    val buttonsY = dimensions.screenHeight - dimensions.bottomPadding - dimensions.actionButtonsHeight
    val messageTop = emojiY + dimensions.emojiBarHeight + dimensions.actionButtonsGap
    val messageBottom = buttonsY - dimensions.actionButtonsGap
    val viewportHeight = max(0f, messageBottom - messageTop)
    val fitScale =
        if (viewportHeight > 0f) min(1f, viewportHeight / messageHeight.toFloat())
        else OVERLAY_MIN_PREVIEW_SCALE
    val scale = max(OVERLAY_MIN_PREVIEW_SCALE, fitScale)
    val scaledHeight = messageHeight.toFloat() * scale
    return OverlayLayout(
        messageFinalY = messageTop,
        messageContainerHeight = viewportHeight,
        bitmapHeight = messageHeight.toFloat(),
        emojiBarY = emojiY,
        actionButtonsY = buttonsY,
        isOverflow = true,
        messageScrollable = scaledHeight > viewportHeight,
        previewScale = scale,
    )
}
