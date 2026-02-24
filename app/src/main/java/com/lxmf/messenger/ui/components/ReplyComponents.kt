package com.lxmf.messenger.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.model.ReplyPreviewUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipe threshold for triggering reply action.
 */
private val SWIPE_THRESHOLD = 72.dp

/**
 * Maximum swipe distance to prevent over-swiping.
 */
private val MAX_SWIPE = 100.dp

/**
 * Wrapper component that adds swipe-to-reply gesture to a message bubble.
 *
 * The swipe direction is toward the center:
 * - Received messages (isFromMe = false): swipe right
 * - Sent messages (isFromMe = true): swipe left
 *
 * When the swipe threshold is reached:
 * - Haptic feedback is triggered
 * - Reply icon appears behind the bubble
 * - Releasing triggers the onReply callback
 *
 * @param isFromMe Whether this is a sent message (affects swipe direction)
 * @param onReply Callback when swipe-to-reply is triggered
 * @param content The message bubble content to wrap
 */
@Composable
fun SwipeableMessageBubble(
    isFromMe: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current

    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val maxSwipePx = with(density) { MAX_SWIPE.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    var shouldTriggerReply by remember { mutableStateOf(false) }

    // Animate the return to center when released
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "swipe_offset",
    )

    // Calculate reply icon visibility based on swipe progress
    val swipeProgress = abs(animatedOffsetX) / thresholdPx
    val replyIconAlpha = (swipeProgress * 2).coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // Reply icon behind the bubble
        Box(
            modifier =
                Modifier
                    .alpha(replyIconAlpha)
                    .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "Reply",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Message bubble with swipe gesture
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                    .pointerInput(isFromMe) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                hasTriggeredHaptic = false
                                shouldTriggerReply = false
                            },
                            onDragEnd = {
                                if (shouldTriggerReply) {
                                    onReply()
                                }
                                offsetX = 0f
                                hasTriggeredHaptic = false
                                shouldTriggerReply = false
                            },
                            onDragCancel = {
                                offsetX = 0f
                                hasTriggeredHaptic = false
                                shouldTriggerReply = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                // Determine valid swipe direction
                                val newOffset = offsetX + dragAmount
                                val isValidDirection =
                                    if (isFromMe) {
                                        // Sent messages: swipe left (negative offset)
                                        newOffset <= 0
                                    } else {
                                        // Received messages: swipe right (positive offset)
                                        newOffset >= 0
                                    }

                                if (isValidDirection) {
                                    // Clamp to max swipe distance
                                    offsetX =
                                        if (isFromMe) {
                                            newOffset.coerceIn(-maxSwipePx, 0f)
                                        } else {
                                            newOffset.coerceIn(0f, maxSwipePx)
                                        }

                                    // Check if threshold reached for haptic feedback
                                    val absOffset = abs(offsetX)
                                    if (absOffset >= thresholdPx && !hasTriggeredHaptic) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        hasTriggeredHaptic = true
                                        shouldTriggerReply = true
                                    } else if (absOffset < thresholdPx) {
                                        shouldTriggerReply = false
                                    }
                                }
                            },
                        )
                    },
        ) {
            content()
        }
    }
}

/**
 * Reply preview displayed inside a message bubble.
 *
 * Shows a colored accent bar on the left with sender name and truncated content.
 * Includes icons for image/file attachments when present.
 * Clickable to jump to the original message.
 *
 * @param replyPreview The reply preview data to display
 * @param isFromMe Whether the current message is from the user (affects colors)
 * @param onClick Callback when the preview is tapped (for jump-to-original)
 */
@Composable
fun ReplyPreviewBubble(
    replyPreview: ReplyPreviewUi,
    isFromMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor =
        if (isFromMe) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.primary
        }

    val contentColor =
        if (isFromMe) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    val backgroundColor =
        if (isFromMe) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            // Accent bar on left
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .background(accentColor, RoundedCornerShape(2.dp)),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender name (hidden for deleted message placeholders)
                if (replyPreview.senderName.isNotEmpty()) {
                    Text(
                        text = replyPreview.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(2.dp))
                }

                // Content preview with attachment indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (replyPreview.hasImage) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Image",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (replyPreview.hasFileAttachment) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "File",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                        replyPreview.firstFileName?.let { filename ->
                            Text(
                                text = filename,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    if (replyPreview.contentPreview.isNotEmpty()) {
                        Text(
                            text = replyPreview.contentPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (!replyPreview.hasImage && !replyPreview.hasFileAttachment) {
                        Text(
                            text = "Message",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.5f),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reply input bar displayed above the message input when replying to a message.
 *
 * Shows "Replying to [name]" with a preview and a close button to cancel.
 *
 * @param replyPreview The reply preview data to display
 * @param onCancelReply Callback when the close button is tapped
 */
@Composable
fun ReplyInputBar(
    replyPreview: ReplyPreviewUi,
    onCancelReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reply icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            // Accent bar
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp),
                        ),
            )

            // Reply info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Replying to ${replyPreview.senderName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (replyPreview.hasImage) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Photo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (replyPreview.hasFileAttachment && replyPreview.firstFileName != null) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "File",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = replyPreview.firstFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (replyPreview.contentPreview.isNotEmpty()) {
                        Text(
                            text = replyPreview.contentPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Close button
            IconButton(
                onClick = onCancelReply,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
