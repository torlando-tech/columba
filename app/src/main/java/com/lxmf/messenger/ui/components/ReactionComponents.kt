package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lxmf.messenger.ui.model.ReactionUi

/**
 * Standard emoji reactions available for selection.
 * Matches Signal-style reaction picker with 6 common emotions.
 */
val REACTION_EMOJIS = listOf(
    "\uD83D\uDC4D", // 👍 thumbs up
    "\u2764\uFE0F", // ❤️ red heart
    "\uD83D\uDE02", // 😂 face with tears of joy
    "\uD83D\uDE2E", // 😮 face with open mouth
    "\uD83D\uDE22", // 😢 crying face
    "\uD83D\uDE21", // 😡 angry face
)

/**
 * Modal dialog picker for selecting an emoji reaction.
 *
 * Displays a horizontal row of 6 emoji options (👍 ❤️ 😂 😮 😢 😡).
 * Provides haptic feedback when an emoji is selected.
 * Dismisses automatically when an emoji is selected or user taps outside.
 *
 * @param onReactionSelected Callback when an emoji is selected, receives the emoji string
 * @param onDismiss Callback when the dialog is dismissed without selection
 */
@Composable
fun ReactionPickerDialog(
    onReactionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                REACTION_EMOJIS.forEach { emoji ->
                    ReactionEmojiButton(
                        emoji = emoji,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReactionSelected(emoji)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Individual emoji button within the reaction picker.
 *
 * Displays a single emoji that can be tapped to select it.
 * Sized appropriately for easy touch targeting.
 *
 * @param emoji The emoji character to display
 * @param onClick Callback when this emoji is tapped
 */
@Composable
private fun ReactionEmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = emoji,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Horizontal row of reaction chips displayed below a message bubble.
 *
 * Shows each reaction emoji with its count as a compact chip.
 * Positioned appropriately for sent/received messages using alignment.
 * Chips are styled with Material3 colors that adapt to the message type.
 * Own reactions are highlighted with a distinct surfaceVariant color per Material Design 3.
 *
 * @param reactions List of reactions to display (emoji + count)
 * @param isFromMe Whether the parent message is from the current user (affects alignment)
 * @param myIdentityHash The current user's identity hash to identify own reactions
 */
@Composable
fun ReactionDisplayRow(
    reactions: List<ReactionUi>,
    isFromMe: Boolean,
    myIdentityHash: String?,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = if (isFromMe) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reactions.forEach { reaction ->
                // Check if the current user has reacted with this emoji
                val isOwnReaction = myIdentityHash != null &&
                    reaction.senderHashes.any { it.equals(myIdentityHash, ignoreCase = true) }

                ReactionChip(
                    reaction = reaction,
                    isOwnReaction = isOwnReaction,
                )
            }
        }
    }
}

/**
 * Individual reaction chip displaying an emoji with its count.
 *
 * Styled as a compact pill-shaped surface with the emoji and count.
 * Colors adapt based on whether the current user has reacted with this emoji.
 * Per Material Design 3: own reactions use surfaceVariant for visual distinction.
 *
 * @param reaction The reaction data (emoji and count)
 * @param isOwnReaction Whether the current user has reacted with this emoji
 */
@Composable
private fun ReactionChip(
    reaction: ReactionUi,
    isOwnReaction: Boolean,
    modifier: Modifier = Modifier,
) {
    // Material Design 3: Use surfaceVariant for own reactions (highlighted)
    // and surfaceContainerHigh for others' reactions (neutral)
    val backgroundColor = if (isOwnReaction) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val countColor = if (isOwnReaction) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.height(24.dp),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = reaction.emoji,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            if (reaction.count > 1) {
                Text(
                    text = reaction.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = countColor,
                )
            }
        }
    }
}
