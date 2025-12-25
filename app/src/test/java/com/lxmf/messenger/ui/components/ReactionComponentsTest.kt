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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
        val reactions = listOf(
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
    fun `ReactionDisplayRow displays all reactions when many are present`() {
        val reactions = listOf(
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
}
