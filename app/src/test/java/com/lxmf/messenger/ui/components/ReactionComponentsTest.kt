package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.ReactionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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

    // ========== calculateMessageScaleForOverlay TESTS ==========

    // Common UI dimensions for tests (in pixels, simulating a typical phone at 3x density)
    private val testDimensions = OverlayLayoutDimensions(
        screenHeight = 2400f, // ~800dp at 3x density
        emojiBarHeight = 168f, // 56dp at 3x density
        emojiBarGap = 228f, // 76dp at 3x density
        actionButtonsHeight = 168f, // 56dp at 3x density
        actionButtonsGap = 36f, // 12dp at 3x density
        topPadding = 144f, // 48dp at 3x density
        bottomPadding = 144f, // 48dp at 3x density
    )

    @Test
    fun `calculateMessageScaleForOverlay returns 1f for small message that fits on screen`() {
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 300, // Small message
            dimensions = testDimensions,
        )

        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay returns scale less than 1 for large message`() {
        // Available height = 2400 - 144 - 144 = 2112
        // UI elements = 168 + 228 + 36 + 168 = 600
        // Max message height = 2112 - 600 = 1512
        // For a 2000px message, scale should be 1512/2000 = 0.756
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 2000, // Large message
            dimensions = testDimensions,
        )

        assertTrue("Scale should be less than 1 for large message", scale < 1f)
        assertTrue("Scale should be greater than minScale", scale >= 0.3f)
        assertEquals(0.756f, scale, 0.01f)
    }

    @Test
    fun `calculateMessageScaleForOverlay respects minimum scale for very large message`() {
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 10000, // Very large message
            dimensions = testDimensions,
        )

        assertEquals("Scale should be clamped to minScale", 0.3f, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay returns 1f for zero height message`() {
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 0,
            dimensions = testDimensions,
        )

        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay returns 1f for negative height message`() {
        val scale = calculateMessageScaleForOverlay(
            messageHeight = -100,
            dimensions = testDimensions,
        )

        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay with custom minScale`() {
        val customMinScale = 0.5f
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 10000, // Very large message
            dimensions = testDimensions,
            minScale = customMinScale,
        )

        assertEquals("Scale should be clamped to custom minScale", customMinScale, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay with message exactly at boundary`() {
        // Available height = 2400 - 144 - 144 = 2112
        // UI elements = 168 + 228 + 36 + 168 = 600
        // Max message height = 2112 - 600 = 1512
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 1512, // Exactly fits
            dimensions = testDimensions,
        )

        assertEquals("Message that exactly fits should have scale 1", 1f, scale, 0.001f)
    }

    @Test
    fun `calculateMessageScaleForOverlay with small screen`() {
        val smallScreenDimensions = testDimensions.copy(screenHeight = 1200f)
        val scale = calculateMessageScaleForOverlay(
            messageHeight = 800,
            dimensions = smallScreenDimensions,
        )

        assertTrue("Scale should be less than 1 on small screen", scale < 1f)
        assertTrue("Scale should be greater than minScale", scale >= 0.3f)
    }
}
