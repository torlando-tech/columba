package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Extended emoji list for the full emoji picker tray.
 * Contains a broader selection of commonly used emojis.
 */
val EXTENDED_EMOJIS = listOf(
    // Smileys & People
    "\uD83D\uDE00", // 😀 grinning face
    "\uD83D\uDE03", // 😃 grinning face with big eyes
    "\uD83D\uDE04", // 😄 grinning face with smiling eyes
    "\uD83D\uDE01", // 😁 beaming face with smiling eyes
    "\uD83D\uDE06", // 😆 grinning squinting face
    "\uD83D\uDE05", // 😅 grinning face with sweat
    "\uD83D\uDE02", // 😂 face with tears of joy
    "\uD83E\uDD23", // 🤣 rolling on the floor laughing
    "\uD83D\uDE0A", // 😊 smiling face with smiling eyes
    "\uD83D\uDE07", // 😇 smiling face with halo
    "\uD83D\uDE42", // 🙂 slightly smiling face
    "\uD83D\uDE43", // 🙃 upside-down face
    "\uD83D\uDE09", // 😉 winking face
    "\uD83D\uDE0C", // 😌 relieved face
    "\uD83D\uDE0D", // 😍 smiling face with heart-eyes
    "\uD83E\uDD70", // 🥰 smiling face with hearts
    "\uD83D\uDE18", // 😘 face blowing a kiss
    "\uD83D\uDE17", // 😗 kissing face
    "\uD83D\uDE1A", // 😚 kissing face with closed eyes
    "\uD83D\uDE19", // 😙 kissing face with smiling eyes
    "\uD83E\uDD17", // 🤗 hugging face
    "\uD83E\uDD14", // 🤔 thinking face
    "\uD83E\uDD28", // 🤨 face with raised eyebrow
    "\uD83D\uDE10", // 😐 neutral face
    "\uD83D\uDE11", // 😑 expressionless face
    "\uD83D\uDE36", // 😶 face without mouth
    "\uD83D\uDE0F", // 😏 smirking face
    "\uD83D\uDE12", // 😒 unamused face
    "\uD83D\uDE44", // 🙄 face with rolling eyes
    "\uD83D\uDE2C", // 😬 grimacing face
    "\uD83D\uDE2E", // 😮 face with open mouth
    "\uD83D\uDE2F", // 😯 hushed face
    "\uD83D\uDE32", // 😲 astonished face
    "\uD83D\uDE33", // 😳 flushed face
    "\uD83E\uDD7A", // 🥺 pleading face
    "\uD83D\uDE26", // 😦 frowning face with open mouth
    "\uD83D\uDE27", // 😧 anguished face
    "\uD83D\uDE28", // 😨 fearful face
    "\uD83D\uDE30", // 😰 anxious face with sweat
    "\uD83D\uDE25", // 😥 sad but relieved face
    "\uD83D\uDE22", // 😢 crying face
    "\uD83D\uDE2D", // 😭 loudly crying face
    "\uD83D\uDE31", // 😱 face screaming in fear
    "\uD83D\uDE16", // 😖 confounded face
    "\uD83D\uDE23", // 😣 persevering face
    "\uD83D\uDE1E", // 😞 disappointed face
    "\uD83D\uDE13", // 😓 downcast face with sweat
    "\uD83D\uDE29", // 😩 weary face
    "\uD83D\uDE2A", // 😪 sleepy face
    "\uD83E\uDD24", // 🤤 drooling face
    "\uD83D\uDE34", // 😴 sleeping face
    "\uD83D\uDE37", // 😷 face with medical mask
    "\uD83E\uDD12", // 🤒 face with thermometer
    "\uD83E\uDD15", // 🤕 face with head-bandage
    "\uD83E\uDD22", // 🤢 nauseated face
    "\uD83E\uDD2E", // 🤮 face vomiting
    "\uD83E\uDD27", // 🤧 sneezing face
    "\uD83E\uDD75", // 🥵 hot face
    "\uD83E\uDD76", // 🥶 cold face
    "\uD83D\uDE35", // 😵 dizzy face
    "\uD83E\uDD2F", // 🤯 exploding head
    "\uD83E\uDD20", // 🤠 cowboy hat face
    "\uD83E\uDD73", // 🥳 partying face
    "\uD83D\uDE0E", // 😎 smiling face with sunglasses
    "\uD83E\uDD13", // 🤓 nerd face
    "\uD83E\uDDD0", // 🧐 face with monocle
    "\uD83D\uDE15", // 😕 confused face
    "\uD83D\uDE1F", // 😟 worried face
    "\uD83D\uDE41", // 🙁 slightly frowning face
    "\uD83D\uDE2E", // ☹️ frowning face (approximation)
    "\uD83D\uDE24", // 😤 face with steam from nose
    "\uD83D\uDE21", // 😡 pouting face
    "\uD83D\uDE20", // 😠 angry face
    "\uD83E\uDD2C", // 🤬 face with symbols on mouth
    // Gestures
    "\uD83D\uDC4D", // 👍 thumbs up
    "\uD83D\uDC4E", // 👎 thumbs down
    "\uD83D\uDC4A", // 👊 oncoming fist
    "\u270A", // ✊ raised fist
    "\uD83E\uDD1B", // 🤛 left-facing fist
    "\uD83E\uDD1C", // 🤜 right-facing fist
    "\uD83D\uDC4F", // 👏 clapping hands
    "\uD83D\uDE4C", // 🙌 raising hands
    "\uD83D\uDC50", // 👐 open hands
    "\uD83E\uDD32", // 🤲 palms up together
    "\uD83E\uDD1D", // 🤝 handshake
    "\uD83D\uDE4F", // 🙏 folded hands
    "\u270C\uFE0F", // ✌️ victory hand
    "\uD83E\uDD1E", // 🤞 crossed fingers
    "\uD83E\uDD1F", // 🤟 love-you gesture
    "\uD83E\uDD18", // 🤘 sign of the horns
    "\uD83D\uDC4C", // 👌 OK hand
    "\uD83D\uDC48", // 👈 backhand index pointing left
    "\uD83D\uDC49", // 👉 backhand index pointing right
    "\uD83D\uDC46", // 👆 backhand index pointing up
    "\uD83D\uDC47", // 👇 backhand index pointing down
    "\u261D\uFE0F", // ☝️ index pointing up
    "\u270B", // ✋ raised hand
    "\uD83E\uDD1A", // 🤚 raised back of hand
    "\uD83D\uDD90\uFE0F", // 🖐️ hand with fingers splayed
    "\uD83D\uDC4B", // 👋 waving hand
    "\uD83E\uDD19", // 🤙 call me hand
    "\uD83D\uDCAA", // 💪 flexed biceps
    // Hearts & Love
    "\u2764\uFE0F", // ❤️ red heart
    "\uD83E\uDDE1", // 🧡 orange heart
    "\uD83D\uDC9B", // 💛 yellow heart
    "\uD83D\uDC9A", // 💚 green heart
    "\uD83D\uDC99", // 💙 blue heart
    "\uD83D\uDC9C", // 💜 purple heart
    "\uD83D\uDDA4", // 🖤 black heart
    "\uD83E\uDD0D", // 🤍 white heart
    "\uD83E\uDD0E", // 🤎 brown heart
    "\uD83D\uDC94", // 💔 broken heart
    "\u2763\uFE0F", // ❣️ heart exclamation
    "\uD83D\uDC95", // 💕 two hearts
    "\uD83D\uDC9E", // 💞 revolving hearts
    "\uD83D\uDC93", // 💓 beating heart
    "\uD83D\uDC97", // 💗 growing heart
    "\uD83D\uDC96", // 💖 sparkling heart
    "\uD83D\uDC98", // 💘 heart with arrow
    "\uD83D\uDC9D", // 💝 heart with ribbon
    // Celebrations
    "\uD83C\uDF89", // 🎉 party popper
    "\uD83C\uDF8A", // 🎊 confetti ball
    "\uD83C\uDF8E", // 🎎 Japanese dolls
    "\uD83C\uDF81", // 🎁 wrapped gift
    "\uD83C\uDF84", // 🎄 Christmas tree
    "\uD83C\uDF86", // 🎆 fireworks
    "\uD83C\uDF87", // 🎇 sparkler
    "\u2728", // ✨ sparkles
    "\uD83C\uDF88", // 🎈 balloon
    // Fire & Stars
    "\uD83D\uDD25", // 🔥 fire
    "\u2B50", // ⭐ star
    "\uD83C\uDF1F", // 🌟 glowing star
    "\uD83D\uDCAB", // 💫 dizzy
    "\u26A1", // ⚡ high voltage
    // Other common
    "\uD83D\uDC4B", // 👋 waving hand
    "\uD83D\uDC40", // 👀 eyes
    "\uD83D\uDC80", // 💀 skull
    "\uD83D\uDCA9", // 💩 pile of poo
    "\uD83E\uDD21", // 🤡 clown face
    "\uD83D\uDC7B", // 👻 ghost
    "\uD83D\uDC7D", // 👽 alien
    "\uD83E\uDD16", // 🤖 robot
    "\uD83D\uDCA5", // 💥 collision
    "\uD83D\uDCAF", // 💯 hundred points
    "\uD83D\uDCA4", // 💤 zzz
    "\uD83D\uDCAC", // 💬 speech balloon
    "\uD83D\uDCA1", // 💡 light bulb
    "\uD83D\uDC8E", // 💎 gem stone
    "\uD83C\uDF08", // 🌈 rainbow
    "\u2600\uFE0F", // ☀️ sun
    "\uD83C\uDF19", // 🌙 crescent moon
    "\u2744\uFE0F", // ❄️ snowflake
    "\uD83C\uDF3B", // 🌻 sunflower
    "\uD83C\uDF39", // 🌹 rose
    "\uD83C\uDF37", // 🌷 tulip
)

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
            modifier = modifier
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
                            modifier = Modifier
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
        modifier = modifier
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
    myIdentityHash: String? = null,
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
