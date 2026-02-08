package com.lxmf.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lxmf.messenger.ui.model.ReactionUi
import kotlinx.coroutines.launch

/**
 * Standard emoji reactions available for selection.
 * Matches Signal-style reaction picker with 6 common emotions: 👍 ❤️ 😂 😮 😢 😡
 */
val REACTION_EMOJIS =
    listOf(
        "\uD83D\uDC4D",
        "\u2764\uFE0F",
        "\uD83D\uDE02",
        "\uD83D\uDE2E",
        "\uD83D\uDE22",
        "\uD83D\uDE21",
    )

/**
 * Extended emoji list for the full emoji picker tray.
 * Contains a broader selection of commonly used emojis.
 * Curated list of common reaction emojis organized by category.
 */
val EXTENDED_EMOJIS =
    listOf(
        // Smileys & People: 😀😃😄😁😆😅😂🤣😊😇🙂🙃😉😌😍🥰😘😗😚😙🤗🤔🤨😐😑😶😏😒🙄😬😮😯😲😳🥺😦😧😨😰😥😢😭😱😖😣😞😓😩😪🤤😴😷🤒🤕🤢🤮🤧🥵🥶😵🤯🤠🥳😎🤓🧐😕😟🙁☹️😤😡😠🤬
        "\uD83D\uDE00",
        "\uD83D\uDE03",
        "\uD83D\uDE04",
        "\uD83D\uDE01",
        "\uD83D\uDE06",
        "\uD83D\uDE05",
        "\uD83D\uDE02",
        "\uD83E\uDD23",
        "\uD83D\uDE0A",
        "\uD83D\uDE07",
        "\uD83D\uDE42",
        "\uD83D\uDE43",
        "\uD83D\uDE09",
        "\uD83D\uDE0C",
        "\uD83D\uDE0D",
        "\uD83E\uDD70",
        "\uD83D\uDE18",
        "\uD83D\uDE17",
        "\uD83D\uDE1A",
        "\uD83D\uDE19",
        "\uD83E\uDD17",
        "\uD83E\uDD14",
        "\uD83E\uDD28",
        "\uD83D\uDE10",
        "\uD83D\uDE11",
        "\uD83D\uDE36",
        "\uD83D\uDE0F",
        "\uD83D\uDE12",
        "\uD83D\uDE44",
        "\uD83D\uDE2C",
        "\uD83D\uDE2E",
        "\uD83D\uDE2F",
        "\uD83D\uDE32",
        "\uD83D\uDE33",
        "\uD83E\uDD7A",
        "\uD83D\uDE26",
        "\uD83D\uDE27",
        "\uD83D\uDE28",
        "\uD83D\uDE30",
        "\uD83D\uDE25",
        "\uD83D\uDE22",
        "\uD83D\uDE2D",
        "\uD83D\uDE31",
        "\uD83D\uDE16",
        "\uD83D\uDE23",
        "\uD83D\uDE1E",
        "\uD83D\uDE13",
        "\uD83D\uDE29",
        "\uD83D\uDE2A",
        "\uD83E\uDD24",
        "\uD83D\uDE34",
        "\uD83D\uDE37",
        "\uD83E\uDD12",
        "\uD83E\uDD15",
        "\uD83E\uDD22",
        "\uD83E\uDD2E",
        "\uD83E\uDD27",
        "\uD83E\uDD75",
        "\uD83E\uDD76",
        "\uD83D\uDE35",
        "\uD83E\uDD2F",
        "\uD83E\uDD20",
        "\uD83E\uDD73",
        "\uD83D\uDE0E",
        "\uD83E\uDD13",
        "\uD83E\uDDD0",
        "\uD83D\uDE15",
        "\uD83D\uDE1F",
        "\uD83D\uDE41",
        "\uD83D\uDE2E",
        "\uD83D\uDE24",
        "\uD83D\uDE21",
        "\uD83D\uDE20",
        "\uD83E\uDD2C",
        // Gestures: 👍👎👊✊🤛🤜👏🙌👐🤲🤝🙏✌️🤞🤟🤘👌👈👉👆👇☝️✋🤚🖐️👋🤙💪
        "\uD83D\uDC4D",
        "\uD83D\uDC4E",
        "\uD83D\uDC4A",
        "\u270A",
        "\uD83E\uDD1B",
        "\uD83E\uDD1C",
        "\uD83D\uDC4F",
        "\uD83D\uDE4C",
        "\uD83D\uDC50",
        "\uD83E\uDD32",
        "\uD83E\uDD1D",
        "\uD83D\uDE4F",
        "\u270C\uFE0F",
        "\uD83E\uDD1E",
        "\uD83E\uDD1F",
        "\uD83E\uDD18",
        "\uD83D\uDC4C",
        "\uD83D\uDC48",
        "\uD83D\uDC49",
        "\uD83D\uDC46",
        "\uD83D\uDC47",
        "\u261D\uFE0F",
        "\u270B",
        "\uD83E\uDD1A",
        "\uD83D\uDD90\uFE0F",
        "\uD83D\uDC4B",
        "\uD83E\uDD19",
        "\uD83D\uDCAA",
        // Hearts & Love: ❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝
        "\u2764\uFE0F",
        "\uD83E\uDDE1",
        "\uD83D\uDC9B",
        "\uD83D\uDC9A",
        "\uD83D\uDC99",
        "\uD83D\uDC9C",
        "\uD83D\uDDA4",
        "\uD83E\uDD0D",
        "\uD83E\uDD0E",
        "\uD83D\uDC94",
        "\u2763\uFE0F",
        "\uD83D\uDC95",
        "\uD83D\uDC9E",
        "\uD83D\uDC93",
        "\uD83D\uDC97",
        "\uD83D\uDC96",
        "\uD83D\uDC98",
        "\uD83D\uDC9D",
        // Celebrations: 🎉🎊🎎🎁🎄🎆🎇✨🎈
        "\uD83C\uDF89",
        "\uD83C\uDF8A",
        "\uD83C\uDF8E",
        "\uD83C\uDF81",
        "\uD83C\uDF84",
        "\uD83C\uDF86",
        "\uD83C\uDF87",
        "\u2728",
        "\uD83C\uDF88",
        // Fire & Stars: 🔥⭐🌟💫⚡
        "\uD83D\uDD25",
        "\u2B50",
        "\uD83C\uDF1F",
        "\uD83D\uDCAB",
        "\u26A1",
        // Other common: 👋👀💀💩🤡👻👽🤖💥💯💤💬💡💎🌈☀️🌙❄️🌻🌹🌷
        "\uD83D\uDC4B",
        "\uD83D\uDC40",
        "\uD83D\uDC80",
        "\uD83D\uDCA9",
        "\uD83E\uDD21",
        "\uD83D\uDC7B",
        "\uD83D\uDC7D",
        "\uD83E\uDD16",
        "\uD83D\uDCA5",
        "\uD83D\uDCAF",
        "\uD83D\uDCA4",
        "\uD83D\uDCAC",
        "\uD83D\uDCA1",
        "\uD83D\uDC8E",
        "\uD83C\uDF08",
        "\u2600\uFE0F",
        "\uD83C\uDF19",
        "\u2744\uFE0F",
        "\uD83C\uDF3B",
        "\uD83C\uDF39",
        "\uD83C\uDF37",
    )

/**
 * Inline emoji bar that appears above a message on long-press (Signal-style).
 *
 * Unlike the dialog version, this is positioned inline within the message layout,
 * appearing directly above the message bubble. It shows immediately on long-press
 * without requiring an extra tap in a context menu.
 *
 * @param onReactionSelected Callback when an emoji is selected, receives the emoji string
 * @param onShowFullPicker Callback to show the full emoji picker dialog
 */
@Composable
fun InlineReactionBar(
    onReactionSelected: (String) -> Unit,
    onShowFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier =
                Modifier
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
            // "+" button to open full emoji picker
            AddMoreEmojiButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowFullPicker()
                },
            )
        }
    }
}

/**
 * Signal-style message action bar that appears above a message on long-press.
 *
 * Combines emoji reactions with action buttons in a unified UI.
 * This replaces the DropdownMenu approach to avoid touch event blocking issues.
 * The action bar includes:
 * - Row 1: Quick emoji reactions + "+" button for more emojis
 * - Row 2: Action buttons (Reply, Copy, and optional Retry/Details)
 *
 * @param onReactionSelected Callback when an emoji is selected
 * @param onShowFullPicker Callback to show the full emoji picker dialog
 * @param onReply Callback when Reply is tapped
 * @param onCopy Callback when Copy is tapped
 * @param onViewDetails Optional callback for View Details (all messages)
 * @param onRetry Optional callback for Retry (failed messages only)
 */
@Composable
fun MessageActionBar(
    onReactionSelected: (String) -> Unit,
    onShowFullPicker: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onViewDetails: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Row 1: Emoji reactions
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
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
                // "+" button to open full emoji picker
                AddMoreEmojiButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShowFullPicker()
                    },
                )
            }

            // Row 2: Action buttons
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Retry button (only for failed messages)
                if (onRetry != null) {
                    ActionButton(
                        icon = Icons.Default.Refresh,
                        label = "Retry",
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRetry()
                        },
                    )
                }

                // Reply button
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    label = "Reply",
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReply()
                    },
                )

                // Copy button
                ActionButton(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy",
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopy()
                    },
                )

                // Details button (shown for all messages)
                if (onViewDetails != null) {
                    ActionButton(
                        icon = Icons.Default.Info,
                        label = "Details",
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onViewDetails()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Individual action button for the message action bar.
 */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Modal dialog picker for selecting an emoji reaction.
 *
 * Displays a horizontal row of 6 emoji options (👍 ❤️ 😂 😮 😢 😡) plus a "+" button
 * to open the full emoji picker with more options.
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
    var showFullEmojiPicker by remember { mutableStateOf(false) }

    if (showFullEmojiPicker) {
        FullEmojiPickerDialog(
            onEmojiSelected = { emoji ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onReactionSelected(emoji)
            },
            onDismiss = {
                showFullEmojiPicker = false
            },
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier =
                        Modifier
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
                    // "+" button to open full emoji picker
                    AddMoreEmojiButton(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            showFullEmojiPicker = true
                        },
                    )
                }
            }
        }
    }
}

/**
 * Full emoji picker dialog with a grid of all available emojis.
 *
 * Displays a scrollable grid of emojis from [EXTENDED_EMOJIS].
 * Provides haptic feedback when an emoji is selected.
 *
 * @param onEmojiSelected Callback when an emoji is selected, receives the emoji string
 * @param onDismiss Callback when the dialog is dismissed without selection
 */
@Composable
fun FullEmojiPickerDialog(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = "Choose a reaction",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(EXTENDED_EMOJIS) { emoji ->
                        Surface(
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .semantics { role = Role.Button }
                                    .clickable {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEmojiSelected(emoji)
                                    },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = emoji,
                                    fontSize = 24.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "+" button to open the full emoji picker.
 *
 * @param onClick Callback when the button is tapped
 */
@Composable
private fun AddMoreEmojiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .size(48.dp)
                .semantics { role = Role.Button }
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "More emojis",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
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
        modifier =
            modifier
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
    myIdentityHash: String? = null,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        horizontalArrangement =
            if (isFromMe) {
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
                val isOwnReaction =
                    myIdentityHash != null &&
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
    val backgroundColor =
        if (isOwnReaction) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

    val countColor =
        if (isOwnReaction) {
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

/**
 * Signal-style reaction mode overlay.
 *
 * Displays a full-screen dimmed scrim with elevated emoji bar and action buttons
 * positioned to avoid overlap. The background is faded to 50% opacity while the
 * selected message and UI elements remain at full brightness.
 *
 * @param messageId ID of the selected message
 * @param isFromMe Whether the message is from the current user
 * @param isFailed Whether the message failed to send
 * @param onReactionSelected Callback when an emoji is selected
 * @param onShowFullPicker Callback to show the full emoji picker
 * @param onReply Callback for the reply action
 * @param onCopy Callback for the copy action
 * @param onViewDetails Optional callback for viewing message details (all messages)
 * @param onRetry Optional callback for retrying failed messages
 * @param onDismiss Callback when the overlay is dismissed
 * @param modifier Optional modifier for the overlay
 */
@Suppress("UnusedParameter") // messageId and isFailed reserved for future use (e.g., logging, analytics)
@Composable
fun ReactionModeOverlay(
    messageId: String,
    isFromMe: Boolean,
    isFailed: Boolean,
    messageBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    messageX: Float = 0f,
    messageY: Float = 0f,
    messageWidth: Int = 0,
    messageHeight: Int = 0,
    onReactionSelected: (String) -> Unit,
    onShowFullPicker: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onViewDetails: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** Called when dismiss animation starts */
    onDismissStarted: () -> Unit = {},
    /** Called when dismiss animation completes */
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    // Calculate screen dimensions and UI element sizes in pixels
    val layoutDimensions =
        OverlayLayoutDimensions(
            screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() },
            emojiBarHeight = with(density) { 56.dp.toPx() },
            emojiBarGap = with(density) { 76.dp.toPx() },
            actionButtonsHeight = with(density) { 56.dp.toPx() },
            actionButtonsGap = with(density) { 12.dp.toPx() },
            topPadding = with(density) { 48.dp.toPx() },
            bottomPadding = with(density) { 48.dp.toPx() },
        )

    // Calculate scale factor for large messages to ensure they fit on screen
    val messageScale =
        calculateMessageScaleForOverlay(
            messageHeight = messageHeight,
            dimensions = layoutDimensions,
        )

    // Scaled message dimensions
    val scaledMessageHeight = (messageHeight * messageScale).toInt()
    val scaledMessageWidth = (messageWidth * messageScale).toInt()

    // Calculate target position (center of screen vertically) using scaled height
    val targetY = (layoutDimensions.screenHeight / 2) - (scaledMessageHeight / 2)

    // Signal-style positioning: align based on message side, not message position
    // Left-aligned messages (received): UI elements at left margin
    // Right-aligned messages (sent): UI elements at right margin

    // Animated offset for message position
    val animatedOffsetY = remember { Animatable(messageY) }

    // Trigger animations on mount
    LaunchedEffect(Unit) {
        visible = true
        // Animate message to center
        animatedOffsetY.animateTo(
            targetValue = targetY,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
        )
    }

    // Function to handle dismiss with reverse animation
    val handleDismiss: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            onDismissStarted() // Show original message immediately
            scope.launch {
                // Start fade out immediately, then animate message back
                visible = false
                // Animate message back to original position (runs during fade out)
                animatedOffsetY.animateTo(
                    targetValue = messageY,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
                // Dismiss after animation completes
                onDismiss()
            }
        }
    }

    // Wrap callbacks to trigger dismiss animation after action
    val wrappedOnReactionSelected: (String) -> Unit = { emoji ->
        onReactionSelected(emoji)
        handleDismiss()
    }

    val wrappedOnShowFullPicker: () -> Unit = {
        onShowFullPicker()
        // Note: Don't dismiss here - full picker dialog handles its own dismiss
    }

    val wrappedOnReply: () -> Unit = {
        onReply()
        handleDismiss()
    }

    val wrappedOnCopy: () -> Unit = {
        onCopy()
        handleDismiss()
    }

    val wrappedOnViewDetails: (() -> Unit)? =
        onViewDetails?.let {
            {
                // Exit reaction mode immediately before navigating (no animation needed)
                // This ensures back navigation returns to chat, not the overlay
                onDismiss()
                it()
            }
        }

    val wrappedOnRetry: (() -> Unit)? =
        onRetry?.let {
            {
                it()
                handleDismiss()
            }
        }

    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            ),
        exit =
            fadeOut(
                animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
            ),
    ) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = handleDismiss,
                    ),
        ) {
            // Dimmed scrim background
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
            )

            // Message snapshot with animation
            // Guard against recycled/invalid underlying bitmap (COLUMBA-20)
            val validBitmap =
                messageBitmap?.let { bitmap ->
                    try {
                        val androidBitmap = bitmap.asAndroidBitmap()
                        if (!androidBitmap.isRecycled) bitmap else null
                    } catch (_: Exception) {
                        null
                    }
                }
            validBitmap?.let { bitmap ->
                // Use scaled dimensions for large messages
                val displayWidthDp = with(density) { scaledMessageWidth.toDp() }
                val displayHeightDp = with(density) { scaledMessageHeight.toDp() }

                // Calculate X offset to keep message aligned (adjust for width change due to scaling)
                val scaledMessageX =
                    if (isFromMe) {
                        // For sent messages (right-aligned), adjust X to keep right edge aligned
                        messageX + (messageWidth - scaledMessageWidth)
                    } else {
                        // For received messages (left-aligned), keep X the same
                        messageX
                    }

                // Message snapshot - keep at full opacity during animation
                Image(
                    bitmap = bitmap,
                    contentDescription = "Selected message",
                    modifier =
                        Modifier
                            .size(width = displayWidthDp, height = displayHeightDp)
                            .offset {
                                IntOffset(scaledMessageX.toInt(), animatedOffsetY.value.toInt())
                            }.alpha(1f) // Don't fade out with AnimatedVisibility
                            .clip(
                                RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomStart = if (isFromMe) 20.dp else 4.dp,
                                    bottomEnd = if (isFromMe) 4.dp else 20.dp,
                                ),
                            ),
                )

                // Emoji bar above message
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (animatedOffsetY.value - with(density) { 76.dp.toPx() }).toInt(),
                                )
                            },
                ) {
                    InlineReactionBar(
                        onReactionSelected = wrappedOnReactionSelected,
                        onShowFullPicker = wrappedOnShowFullPicker,
                        modifier =
                            Modifier
                                .align(if (isFromMe) Alignment.TopEnd else Alignment.TopStart)
                                .padding(horizontal = 16.dp),
                    )
                }

                // Action buttons below message (using scaled height)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (animatedOffsetY.value + scaledMessageHeight + with(density) { 12.dp.toPx() }).toInt(),
                                )
                            },
                ) {
                    MessageActionButtons(
                        onReply = wrappedOnReply,
                        onCopy = wrappedOnCopy,
                        onViewDetails = wrappedOnViewDetails,
                        onRetry = wrappedOnRetry,
                        modifier =
                            Modifier
                                .align(if (isFromMe) Alignment.TopEnd else Alignment.TopStart)
                                .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Action buttons for reaction mode overlay.
 * Displays a horizontal row of action buttons (Retry, Reply, Copy, View Details).
 *
 * @param onReply Callback for reply action
 * @param onCopy Callback for copy action
 * @param onViewDetails Optional callback for view details (shown for sent messages)
 * @param onRetry Optional callback for retry (shown for failed messages)
 * @param modifier Optional modifier for the buttons container
 */
@Composable
private fun MessageActionButtons(
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onViewDetails: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Retry button (failed messages only)
            if (onRetry != null) {
                ReactionModeActionButton(
                    icon = Icons.Default.Refresh,
                    label = "Retry",
                    onClick = onRetry,
                )
            }

            // Reply button (all messages)
            ReactionModeActionButton(
                icon = Icons.AutoMirrored.Filled.Reply,
                label = "Reply",
                onClick = onReply,
            )

            // Copy button (all messages)
            ReactionModeActionButton(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                onClick = onCopy,
            )

            // View Details button (shown for all messages)
            if (onViewDetails != null) {
                ReactionModeActionButton(
                    icon = Icons.Default.Info,
                    label = "Details",
                    onClick = onViewDetails,
                )
            }
        }
    }
}

/**
 * Individual action button for the reaction mode overlay.
 *
 * @param icon The icon to display
 * @param label The accessibility label
 * @param onClick Callback when the button is clicked
 * @param modifier Optional modifier
 */
@Composable
private fun ReactionModeActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}
