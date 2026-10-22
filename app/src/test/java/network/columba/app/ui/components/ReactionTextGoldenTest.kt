package network.columba.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.ui.model.ReactionUi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for the reaction components: inline bar, action bar, picker
 * dialogs, reaction display row, the reaction-mode overlay, and the
 * selectable-text surfaces.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    // Same modern-phone qualifiers as ReactionComponentsTest: the default
    // 320dp Robolectric screen clips the wide reaction bars.
    qualifiers = "w480dp-h1040dp-xxxhdpi",
)
class ReactionTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== InlineReactionBar ==========

    @Test
    fun `inline reaction bar`() {
        composeRule.setContent {
            MaterialTheme {
                InlineReactionBar(onReactionSelected = {}, onShowFullPicker = {})
            }
        }
        TextGoldens.assertGolden("inline_reaction_bar", composeRule)
    }

    // ========== MessageActionBar ==========

    @Test
    fun `message action bar default`() {
        composeRule.setContent {
            MaterialTheme {
                MessageActionBar(
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                )
            }
        }
        TextGoldens.assertGolden("message_action_bar_default", composeRule)
    }

    @Test
    fun `message action bar failed with retry and details`() {
        composeRule.setContent {
            MaterialTheme {
                MessageActionBar(
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onViewDetails = {},
                    onRetry = {},
                )
            }
        }
        TextGoldens.assertGolden("message_action_bar_failed", composeRule)
    }

    // ========== ReactionPickerDialog ==========

    @Test
    fun `reaction picker dialog`() {
        composeRule.setContent {
            MaterialTheme {
                ReactionPickerDialog(onReactionSelected = {}, onDismiss = {})
            }
        }
        TextGoldens.assertGolden("reaction_picker_dialog", composeRule)
    }

    // ========== FullEmojiPickerDialog ==========

    @Test
    fun `full emoji picker dialog`() {
        composeRule.setContent {
            MaterialTheme {
                FullEmojiPickerDialog(onEmojiSelected = {}, onDismiss = {})
            }
        }
        TextGoldens.assertGolden("full_emoji_picker_dialog", composeRule)
    }

    // ========== ReactionDisplayRow ==========

    @Test
    fun `reaction display row received`() {
        composeRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = GOLDEN_REACTIONS,
                    isFromMe = false,
                    myIdentityHash = MY_IDENTITY_HASH,
                )
            }
        }
        TextGoldens.assertGolden("reaction_display_row_received", composeRule)
    }

    @Test
    fun `reaction display row sent`() {
        composeRule.setContent {
            MaterialTheme {
                ReactionDisplayRow(
                    reactions = GOLDEN_REACTIONS,
                    isFromMe = true,
                    myIdentityHash = MY_IDENTITY_HASH,
                )
            }
        }
        TextGoldens.assertGolden("reaction_display_row_sent", composeRule)
    }

    // ========== ReactionModeOverlay ==========

    @Test
    fun `reaction mode overlay sent with snapshot`() {
        val androidBitmap = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
        androidBitmap.eraseColor(0xFF112233.toInt())
        composeRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "msg-golden-0001",
                    isFromMe = true,
                    isFailed = false,
                    messageBitmap = androidBitmap.asImageBitmap(),
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
        TextGoldens.assertGolden("reaction_mode_overlay_sent", composeRule)
    }

    @Test
    fun `reaction mode overlay received failed with text fallback`() {
        composeRule.setContent {
            MaterialTheme {
                ReactionModeOverlay(
                    messageId = "msg-golden-0002",
                    isFromMe = false,
                    isFailed = true,
                    messageBitmap = null,
                    messageX = 100f,
                    messageY = 400f,
                    messageWidth = 300,
                    messageHeight = 120,
                    messageContent = GOLDEN_OVERLAY_FALLBACK_TEXT,
                    onReactionSelected = {},
                    onShowFullPicker = {},
                    onReply = {},
                    onCopy = {},
                    onSelectText = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("reaction_mode_overlay_received_failed", composeRule)
    }

    // ========== SelectableMessageText / SelectableTextDialog ==========

    @Test
    fun `selectable message text`() {
        composeRule.setContent {
            MaterialTheme {
                SelectableMessageText(text = GOLDEN_SELECTABLE_TEXT)
            }
        }
        TextGoldens.assertGolden("selectable_message_text", composeRule)
    }

    @Test
    fun `selectable text dialog`() {
        composeRule.setContent {
            MaterialTheme {
                SelectableTextDialog(text = GOLDEN_SELECTABLE_TEXT, onDismiss = {})
            }
        }
        TextGoldens.assertGolden("selectable_text_dialog", composeRule)
    }

    private companion object {
        const val MY_IDENTITY_HASH = "aabbccdd00112233"

        val GOLDEN_REACTIONS =
            listOf(
                ReactionUi(
                    emoji = "\uD83D\uDC4D",
                    senderHashes = listOf(MY_IDENTITY_HASH, "peer-hash-2"),
                    count = 2,
                ),
                ReactionUi(
                    emoji = "\u2764\uFE0F",
                    senderHashes = listOf("peer-hash-3"),
                    count = 1,
                ),
            )

        const val GOLDEN_OVERLAY_FALLBACK_TEXT = "The quick brown fox jumps over the lazy dog."

        const val GOLDEN_SELECTABLE_TEXT =
            "Selectable message body for substring copying via the platform toolbar."
    }
}
