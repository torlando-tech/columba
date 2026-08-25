package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.ReactionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    // Real modern phone (480dp x 1040dp at xxxhdpi = 1440 x 3120 px). The default
    // Robolectric screen (320dp wide) is narrower than the ~384dp reaction bars, which
    // clips them horizontally and defeats the on-screen bounds assertions below.
    qualifiers = "w480dp-h1040dp-xxxhdpi",
)
class ReactionComponentsTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== ReactionPickerDialog TESTS ==========

    @Test
    fun `ReactionPickerDialog displays thumbs up emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDC4D").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog displays heart emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\u2764\uFE0F").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog displays laughing emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDE02").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog displays surprised emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDE2E").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog displays crying emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDE22").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog displays angry emoji`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDE21").assertIsDisplayed()
    }

    @Test
    fun `ReactionPickerDialog calls onReactionSelected with correct emoji when thumbs up tapped`() {
        var selectedEmoji: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = { selectedEmoji = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\uD83D\uDC4D").performClick()
        assertEquals("👍", selectedEmoji)
    }

    @Test
    fun `ReactionPickerDialog calls onReactionSelected with correct emoji when heart tapped`() {
        var selectedEmoji: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = { selectedEmoji = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("\u2764\uFE0F").performClick()
        assertEquals("❤️", selectedEmoji)
    }

    @Test
    fun `ReactionPickerDialog displays all six emojis`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        // Verify all 6 emojis are displayed
        REACTION_EMOJIS.forEach { emoji ->
            composeTestRule.onNodeWithText(emoji).assertIsDisplayed()
        }
    }

    @Test
    @Ignore("Dialog content not accessible in Robolectric - TODO: fix with DialogHost pattern")
    fun `ReactionPickerDialog displays add more button`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        // Verify the "+" button is displayed
        composeTestRule.onNodeWithContentDescription("More emojis").assertIsDisplayed()
    }

    @Test
    @Ignore("Dialog content not accessible in Robolectric - TODO: fix with DialogHost pattern")
    fun `ReactionPickerDialog shows full emoji picker when add button is tapped`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(
                    onReactionSelected = {},
                    onDismiss = {},
                )
            }
        }

        // Tap the "+" button
        composeTestRule.onNodeWithContentDescription("More emojis").performClick()

        // Verify the full emoji picker title is displayed
        composeTestRule.onNodeWithText("Choose a reaction").assertIsDisplayed()
    }

    // ========== FullEmojiPickerDialog TESTS ==========

    @Test
    fun `FullEmojiPickerDialog displays title`() {
        composeTestRule.setContent {
            MaterialTheme {
                FullEmojiPickerDialog(
                    onEmojiSelected = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Choose a reaction").assertIsDisplayed()
    }

    @Test
    @Ignore("Dialog content not accessible in Robolectric - TODO: fix with DialogHost pattern")
    fun `FullEmojiPickerDialog displays extended emojis`() {
        composeTestRule.setContent {
            MaterialTheme {
                FullEmojiPickerDialog(
                    onEmojiSelected = {},
                    onDismiss = {},
                )
            }
        }

        // Verify some of the extended emojis are displayed
        composeTestRule.onNodeWithText("😀").assertIsDisplayed()
        composeTestRule.onNodeWithText("🔥").assertIsDisplayed()
        composeTestRule.onNodeWithText("💯").assertIsDisplayed()
    }

    @Test
    @Ignore("Dialog content not accessible in Robolectric - TODO: fix with DialogHost pattern")
    fun `FullEmojiPickerDialog calls onEmojiSelected when emoji is tapped`() {
        var selectedEmoji: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                FullEmojiPickerDialog(
                    onEmojiSelected = { selectedEmoji = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("🔥").performClick()
        assertEquals("🔥", selectedEmoji)
    }

    // ========== EXTENDED_EMOJIS Constant Tests ==========

    @Test
    fun `EXTENDED_EMOJIS contains more than 100 emojis`() {
        assertTrue(EXTENDED_EMOJIS.size > 100)
    }

    @Test
    fun `EXTENDED_EMOJIS contains fire emoji`() {
        assertTrue(EXTENDED_EMOJIS.contains("🔥"))
    }

    @Test
    fun `EXTENDED_EMOJIS contains hundred points emoji`() {
        assertTrue(EXTENDED_EMOJIS.contains("💯"))
    }

    // ========== ReactionDisplayRow TESTS ==========

    @Test
    fun `ReactionDisplayRow does not display when reactions list is empty`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = emptyList(),
                    isFromMe = false,
                )
            }
        }

        // With empty reactions, nothing should be rendered
        // We can verify by checking that common emojis aren't displayed
        composeTestRule.onNodeWithText("👍").assertDoesNotExist()
    }

    @Test
    fun `ReactionDisplayRow displays single reaction emoji`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "👍",
                    senderHashes = listOf("sender1"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("👍").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays multiple different reactions`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "👍",
                    senderHashes = listOf("sender1"),
                ),
                ReactionUi(
                    emoji = "❤️",
                    senderHashes = listOf("sender2"),
                ),
                ReactionUi(
                    emoji = "😂",
                    senderHashes = listOf("sender3"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("👍").assertIsDisplayed()
        composeTestRule.onNodeWithText("❤️").assertIsDisplayed()
        composeTestRule.onNodeWithText("😂").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays count when reaction has multiple senders`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "👍",
                    senderHashes = listOf("sender1", "sender2", "sender3"),
                    count = 3,
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("👍").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow does not display count for single reaction`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "👍",
                    senderHashes = listOf("sender1"),
                    count = 1,
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("👍").assertIsDisplayed()
        // Count "1" should not be displayed for single reaction
        composeTestRule.onNodeWithText("1").assertDoesNotExist()
    }

    @Test
    fun `ReactionDisplayRow displays reaction for sent message (isFromMe true)`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "❤️",
                    senderHashes = listOf("sender1"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = true,
                )
            }
        }

        composeTestRule.onNodeWithText("❤️").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays reaction for received message (isFromMe false)`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "😮",
                    senderHashes = listOf("sender1"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("😮").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays reaction with large count`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "😂",
                    senderHashes = List(99) { "sender$it" },
                    count = 99,
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("😂").assertIsDisplayed()
        composeTestRule.onNodeWithText("99").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays complex emoji with ZWJ sequence`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "👨‍👩‍👧‍👦", // Family emoji with ZWJ
                    senderHashes = listOf("sender1"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("👨‍👩‍👧‍👦").assertIsDisplayed()
    }

    @Test
    fun `ReactionDisplayRow displays flag emoji`() {
        val reactions =
            listOf(
                ReactionUi(
                    emoji = "🇺🇸", // US flag
                    senderHashes = listOf("sender1"),
                ),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        composeTestRule.onNodeWithText("🇺🇸").assertIsDisplayed()
    }

    @Test
    @Ignore("Emoji rendering differs in Robolectric - TODO: investigate emoji font support")
    fun `ReactionDisplayRow displays all reactions when many are present`() {
        val reactions =
            listOf(
                ReactionUi(emoji = "👍", senderHashes = listOf("s1"), count = 1),
                ReactionUi(emoji = "❤️", senderHashes = listOf("s2", "s3"), count = 2),
                ReactionUi(emoji = "😂", senderHashes = listOf("s4"), count = 1),
                ReactionUi(emoji = "😮", senderHashes = listOf("s5", "s6", "s7"), count = 3),
                ReactionUi(emoji = "😢", senderHashes = listOf("s8"), count = 1),
                ReactionUi(emoji = "😡", senderHashes = listOf("s9", "s10"), count = 2),
            )

        composeTestRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = reactions,
                    isFromMe = false,
                )
            }
        }

        // Verify all emojis are displayed
        composeTestRule.onNodeWithText("👍").assertIsDisplayed()
        composeTestRule.onNodeWithText("❤️").assertIsDisplayed()
        composeTestRule.onNodeWithText("😂").assertIsDisplayed()
        composeTestRule.onNodeWithText("😮").assertIsDisplayed()
        composeTestRule.onNodeWithText("😢").assertIsDisplayed()
        composeTestRule.onNodeWithText("😡").assertIsDisplayed()

        // Verify counts are displayed (only for reactions with count > 1)
        composeTestRule.onNodeWithText("2", substring = false).assertIsDisplayed()
        composeTestRule.onNodeWithText("3", substring = false).assertIsDisplayed()
    }

    // ========== REACTION_EMOJIS Constant Tests ==========

    @Test
    fun `REACTION_EMOJIS contains exactly six emojis`() {
        assertEquals(6, REACTION_EMOJIS.size)
    }

    @Test
    fun `REACTION_EMOJIS contains thumbs up emoji`() {
        assertTrue(REACTION_EMOJIS.contains("👍"))
    }

    @Test
    fun `REACTION_EMOJIS contains heart emoji`() {
        assertTrue(REACTION_EMOJIS.contains("❤️"))
    }

    @Test
    fun `REACTION_EMOJIS contains expected emojis in order`() {
        val expectedEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "😡")
        assertEquals(expectedEmojis, REACTION_EMOJIS)
    }

    // ========== SelectableMessageText TESTS (issue #920) ==========

    @Test
    fun `SelectableMessageText displays the provided text`() {
        composeTestRule.setContent {
            MaterialTheme {
                SelectableMessageText(text = "The quick brown fox")
            }
        }

        composeTestRule.onNodeWithText("The quick brown fox").assertIsDisplayed()
    }

    @Test
    fun `SelectableMessageText displays multiline text`() {
        val multiline = "first line\nsecond line"

        composeTestRule.setContent {
            MaterialTheme {
                SelectableMessageText(text = multiline)
            }
        }

        composeTestRule.onNodeWithText(multiline).assertIsDisplayed()
    }

    @Test
    fun `SelectableMessageText renders empty string without crashing`() {
        // Guards the call-site gate in MessagingScreen that only opens the dialog for
        // non-blank content; if that gate regresses, the composable must still be robust.
        composeTestRule.setContent {
            MaterialTheme {
                SelectableMessageText(text = "")
            }
        }

        composeTestRule.onNodeWithText("non-existent").assertDoesNotExist()
    }

    // ========== calculateOverlayLayout TESTS (off-screen context menu on very long messages) ==========

    // Realistic modern phone: 480dp x 1040dp at 3x density = 1440 x 3120 px.
    private val phoneDimensions =
        OverlayLayoutDimensions(
            screenHeight = 3120f, // 1040dp
            emojiBarHeight = 168f, // 56dp
            emojiBarGap = 228f, // 76dp
            actionButtonsHeight = 168f, // 56dp
            actionButtonsGap = 36f, // 12dp
            topPadding = 144f, // 48dp
            bottomPadding = 144f, // 48dp
        )

    @Test
    fun `overlay layout keeps a small message centered with bars adjacent`() {
        val layout = calculateOverlayLayout(100, phoneDimensions)

        // Small message: centered, emoji bar 76dp above it, buttons 12dp below.
        assertFalse("Small message should not use the pinned overflow layout", layout.isOverflow)
        assertTrue(layout.fitsOnScreen(phoneDimensions))
        assertEquals(1560f - 50f, layout.messageFinalY, 0.5f)
        assertEquals(100f, layout.messageContainerHeight, 0.5f)
        assertEquals(layout.messageFinalY - 228f, layout.emojiBarY, 0.5f)
        assertEquals(layout.messageFinalY + 100f + 36f, layout.actionButtonsY, 0.5f)
        assertFalse("Uncapped message should not need to scroll", layout.messageScrollable)
    }

    @Test
    fun `overlay layout pins bars and scales the preview to fit for a long message`() {
        // A very long text bubble, taller than the space between the bars.
        val layout = calculateOverlayLayout(4000, phoneDimensions)

        assertTrue("Extremely long message should use the pinned overflow layout", layout.isOverflow)
        assertTrue("Emoji bar and action buttons must both be fully on screen", layout.fitsOnScreen(phoneDimensions))
        // Pinned to the safe-area edges.
        assertEquals(144f, layout.emojiBarY, 0.5f)
        assertEquals(3120f - 144f - 168f, layout.actionButtonsY, 0.5f)
        // The preview is scaled down so the whole thing fits between the bars.
        // viewport = (3120 - 144 - 144) - 168 - 168 - 2 * 36 = 2424; 2424 / 4000 = 0.606.
        val viewport = 2424f
        assertTrue("Long message should be scaled to fit, not scrolled", !layout.messageScrollable)
        assertEquals(2424f / 4000f, layout.previewScale, 0.005f)
        assertEquals(4000f * (2424f / 4000f), layout.scaledPreviewHeight, 1.0f)
        // The scaled preview must sit inside the viewport between the bars.
        assertTrue("Message top must be just below the emoji bar", layout.messageFinalY >= layout.emojiBarY + 168f)
        assertTrue("Scaled preview must fit inside the viewport", layout.scaledPreviewHeight <= viewport + 0.5f)
        assertTrue("Action buttons must end at or above the bottom edge", layout.actionButtonsY + 168f <= 3120f)
        assertTrue("Emoji bar must start at or below the top edge", layout.emojiBarY >= 0f)
    }

    @Test
    fun `overlay layout message is scrollable only when the scaled preview still overflows`() {
        val small = calculateOverlayLayout(100, phoneDimensions)
        assertFalse("Uncapped message should not be scrollable", small.messageScrollable)
        assertEquals(1f, small.previewScale, 0.001f)

        // 4000px scales to fit (0.604 > 0.35 floor) - no scroll needed.
        val scaledToFit = calculateOverlayLayout(4000, phoneDimensions)
        assertFalse("Message that fits after scaling should not be scrollable", scaledToFit.messageScrollable)

        // 6000px: fit scale 2424/6000 = 0.404 -> still above the floor, fits.
        val nearFloor = calculateOverlayLayout(6000, phoneDimensions)
        assertEquals(2424f / 6000f, nearFloor.previewScale, 0.005f)
        assertFalse(nearFloor.messageScrollable)

        // 9000px: fit scale 2424/9000 = 0.269 -> clamped to the 0.35 floor, so the
        // scaled preview (3150px) still overflows the 2424px viewport and scrolls.
        val capped = calculateOverlayLayout(9000, phoneDimensions)
        assertEquals(OVERLAY_MIN_PREVIEW_SCALE, capped.previewScale, 0.0001f)
        assertTrue("Message at the scale floor should be scrollable", capped.messageScrollable)
        assertTrue(capped.scaledPreviewHeight > capped.messageContainerHeight)
    }

    @Test
    fun `overlay layout never scales below the minimum legible scale`() {
        // Even for a message absurdly taller than any capture cap allows, the
        // scale never drops below the legibility floor.
        val layout = calculateOverlayLayout(100_000, phoneDimensions)
        assertTrue(layout.isOverflow)
        assertEquals(OVERLAY_MIN_PREVIEW_SCALE, layout.previewScale, 0.0001f)
        assertTrue(layout.messageScrollable)
        assertTrue(layout.fitsOnScreen(phoneDimensions))
    }

    @Test
    fun `overlay layout fits a message that is large but fits centered`() {
        // available = 2824, UI elements = 600 -> max centered message = 2224px
        val layout = calculateOverlayLayout(2224, phoneDimensions)

        assertFalse("Message that exactly fits centered should not overflow", layout.isOverflow)
        assertTrue(layout.fitsOnScreen(phoneDimensions))
        assertFalse(layout.messageScrollable)
    }

    @Test
    fun `overlay layout treats zero and negative message heights as non-overflow`() {
        val zero = calculateOverlayLayout(0, phoneDimensions)
        assertFalse(zero.isOverflow)
        assertTrue(zero.fitsOnScreen(phoneDimensions))

        val negative = calculateOverlayLayout(-50, phoneDimensions)
        assertFalse(negative.isOverflow)
        assertTrue(negative.fitsOnScreen(phoneDimensions))
    }

    @Test
    fun `overlay layout keeps a positive preview in a compact window`() {
        // Greptile regression: at 232dp (696px @3x) the fixed paddings, bars, and
        // gaps consume all the vertical space, collapsing the pinned preview
        // viewport to zero. The compact branch must re-pin the bars to the raw
        // screen edges and leave the message a positive, scrollable viewport so
        // the preview is never blank.
        val compact =
            OverlayLayoutDimensions(
                screenHeight = 696f, // 232dp
                emojiBarHeight = 168f, // 56dp
                emojiBarGap = 228f, // 76dp
                actionButtonsHeight = 168f, // 56dp
                actionButtonsGap = 36f, // 12dp
                topPadding = 144f, // 48dp
                bottomPadding = 144f, // 48dp
            )

        val layout = calculateOverlayLayout(2000, compact)

        assertTrue("Compact window should use the overflow layout", layout.isOverflow)
        assertTrue("Bars must stay fully on screen in a compact window", layout.fitsOnScreen(compact))
        // Bars re-pinned to the raw screen edges (safe-area paddings compressed).
        assertEquals(0f, layout.emojiBarY, 0.5f)
        assertEquals(696f - 168f, layout.actionButtonsY, 0.5f)
        // Positive preview viewport between the bars, and it scrolls.
        assertTrue("Compact window must leave a positive preview viewport", layout.messageContainerHeight > 0f)
        assertTrue(layout.messageScrollable)
        assertEquals(2000f * layout.previewScale, layout.scaledPreviewHeight, 0.5f)
    }

    @Test
    fun `overlay layout keeps a non-blank preview when the window is shorter than both bars`() {
        // Degenerate: 300px is shorter than the two 168px bars stacked, so no
        // real viewport exists even after compressing the paddings. The preview
        // must still be non-blank (full space between bars, scrollable) and the
        // bars must remain as on-screen as physically possible.
        val tiny =
            OverlayLayoutDimensions(
                screenHeight = 300f,
                emojiBarHeight = 168f,
                emojiBarGap = 228f,
                actionButtonsHeight = 168f,
                actionButtonsGap = 36f,
                topPadding = 144f,
                bottomPadding = 144f,
            )

        val layout = calculateOverlayLayout(2000, tiny)

        assertTrue("Degenerate window should use the overflow layout", layout.isOverflow)
        assertTrue("Preview must never be blank", layout.messageContainerHeight > 0f)
        assertTrue(layout.messageScrollable)
        assertTrue("Emoji bar must start at or below the top edge", layout.emojiBarY >= 0f)
        assertTrue("Emoji bar must end at or above the bottom edge", layout.emojiBarY + 168f <= 300f)
        assertTrue("Action buttons must start at or below the top edge", layout.actionButtonsY >= 0f)
        assertTrue("Action buttons must end at or above the bottom edge", layout.actionButtonsY + 168f <= 300f)
    }

    // ========== ReactionModeOverlay on-screen context menu TESTS ==========

    // A message taller than the test screen: long-pressing it must still show the
    // emoji bar and action buttons fully on screen (the reported bug: the context
    // menu went off screen for extremely long messages).
    private val oversizedMessageHeight = 100_000

    /**
     * Asserts the full context menu (emoji bar + every action button) is laid out
     * completely within the screen. Bounds-based (not assertIsDisplayed) so it
     * catches bars that exist in the tree but are offset off screen - the exact
     * symptom of the reported bug. The screen rectangle is read from the root
     * node's bounds so the check is independent of the test screen's density.
     */
    private fun assertContextMenuOnScreen() {
        val screen =
            composeTestRule
                .onNode(isRoot())
                .fetchSemanticsNode()
                .boundsInRoot

        // Emoji bar: the first quick reaction must be fully on screen.
        val emojiBounds =
            composeTestRule
                .onNodeWithText("\uD83D\uDC4D")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Emoji bar must start at or below top (screen=$screen): $emojiBounds", emojiBounds.top >= screen.top)
        assertTrue("Emoji bar must end at or above bottom (screen=$screen): $emojiBounds", emojiBounds.bottom <= screen.bottom)

        // Every action button must be fully on screen.
        for (label in listOf("Reply", "Copy", "Select text", "Details", "Delete")) {
            val bounds =
                composeTestRule
                    .onNodeWithContentDescription(label)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("$label must start at or below top (screen=$screen): $bounds", bounds.top >= screen.top)
            assertTrue("$label must end at or above bottom (screen=$screen): $bounds", bounds.bottom <= screen.bottom)
        }
    }

    @Test
    fun `reaction overlay keeps the context menu on screen for an extremely long message`() {
        // Realistic long-press path: the bubble snapshot bitmap is captured.
        val androidBitmap = android.graphics.Bitmap.createBitmap(300, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        androidBitmap.eraseColor(0xFF112233.toInt())
        val messageBitmap = androidBitmap.asImageBitmap()

        composeTestRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "test-message",
                    isFromMe = true,
                    isFailed = false,
                    messageBitmap = messageBitmap,
                    messageX = 100f,
                    messageY = 400f,
                    messageWidth = 300,
                    messageHeight = oversizedMessageHeight,
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onSelectText = {},
                    onViewDetails = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }

        assertContextMenuOnScreen()
    }

    @Test
    fun `reaction overlay keeps the context menu on screen when the snapshot bitmap is missing`() {
        // COLUMBA-20 guard: a recycled/invalid bitmap must not make the whole
        // context menu disappear.
        composeTestRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "test-message",
                    isFromMe = false,
                    isFailed = false,
                    messageBitmap = null,
                    messageX = 100f,
                    messageY = 400f,
                    messageWidth = 300,
                    messageHeight = oversizedMessageHeight,
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onSelectText = {},
                    onViewDetails = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }

        assertContextMenuOnScreen()
    }

    @Test
    fun `reaction overlay keeps the context menu on screen for a small message`() {
        val androidBitmap = android.graphics.Bitmap.createBitmap(300, 120, android.graphics.Bitmap.Config.ARGB_8888)
        androidBitmap.eraseColor(0xFF112233.toInt())
        val messageBitmap = androidBitmap.asImageBitmap()

        composeTestRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "test-message",
                    isFromMe = true,
                    isFailed = false,
                    messageBitmap = messageBitmap,
                    messageX = 100f,
                    messageY = 400f,
                    messageWidth = 300,
                    messageHeight = 120,
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onSelectText = {},
                    onViewDetails = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }

        assertContextMenuOnScreen()
    }

    @Test
    fun `reaction overlay falls back to message text when the snapshot bitmap is missing`() {
        // COLUMBA-20 / never-blank: when the snapshot is null the overlay must
        // still show the message content (truncated) in the preview area instead
        // of leaving a blank space between the bars.
        val body = "The quick brown fox jumps over the lazy dog. " + "Repeated content to make the message tall and non-blank. "
        composeTestRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "test-message",
                    isFromMe = false,
                    isFailed = false,
                    messageBitmap = null,
                    messageX = 100f,
                    messageY = 400f,
                    messageWidth = 300,
                    messageHeight = oversizedMessageHeight,
                    messageContent = body.repeat(40).trim(),
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onSelectText = {},
                    onViewDetails = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }

        // The fallback text is composed into the tree (the node holds the whole
        // repeated string, so match on the prefix). A tall scrollable text node
        // trips the visible-fraction check, so assertExists proves the fallback
        // path rendered content rather than leaving the area blank.
        composeTestRule
            .onNodeWithText("The quick brown fox jumps over the lazy dog.", substring = true)
            .assertExists()
        // The context menu is still fully on screen.
        assertContextMenuOnScreen()
    }
}
