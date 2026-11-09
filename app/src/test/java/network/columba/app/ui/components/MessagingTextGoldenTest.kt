package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.ui.model.ReplyPreviewUi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for messaging bubble components: markdown message text, the
 * swipe-to-reply bubble wrapper, and the reply preview / reply input bar.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== MarkdownMessageText ==========

    @Test
    fun `markdown message text received`() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownMessageText(markdown = GOLDEN_MARKDOWN, isFromMe = false)
            }
        }
        waitForRenderedText()
        TextGoldens.assertGolden("markdown_message_text_received", composeRule)
    }

    @Test
    fun `markdown message text sent`() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownMessageText(markdown = GOLDEN_MARKDOWN, isFromMe = true)
            }
        }
        waitForRenderedText()
        TextGoldens.assertGolden("markdown_message_text_sent", composeRule)
    }

    // ========== SwipeableMessageBubble ==========

    @Test
    fun `swipeable message bubble received`() {
        composeRule.setContent {
            MaterialTheme {
                SwipeableMessageBubble(isFromMe = false, onReply = {}) {
                    Text(GOLDEN_BUBBLE_TEXT)
                }
            }
        }
        TextGoldens.assertGolden("swipeable_message_bubble_received", composeRule)
    }

    @Test
    fun `swipeable message bubble sent`() {
        composeRule.setContent {
            MaterialTheme {
                SwipeableMessageBubble(isFromMe = true, onReply = {}) {
                    Text(GOLDEN_BUBBLE_TEXT)
                }
            }
        }
        TextGoldens.assertGolden("swipeable_message_bubble_sent", composeRule)
    }

    // ========== ReplyPreviewBubble ==========

    @Test
    fun `reply preview bubble`() {
        composeRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = GOLDEN_REPLY_PREVIEW,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("reply_preview_bubble", composeRule)
    }

    @Test
    fun `reply preview bubble deleted placeholder`() {
        composeRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = GOLDEN_REPLY_PREVIEW_DELETED,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("reply_preview_bubble_deleted", composeRule)
    }

    // ========== ReplyInputBar ==========

    @Test
    fun `reply input bar`() {
        composeRule.setContent {
            MaterialTheme {
                ReplyInputBar(replyPreview = GOLDEN_REPLY_PREVIEW, onCancelReply = {})
            }
        }
        TextGoldens.assertGolden("reply_input_bar", composeRule)
    }

    /**
     * The markdown renderer may finish parsing after the first idle frame;
     * wait until at least one text node exists so the golden never captures
     * the pre-parse (text-free) frame.
     */
    private fun waitForRenderedText() {
        val hasAnyText = SemanticsMatcher("HasText") { it.config.contains(SemanticsProperties.Text) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasAnyText).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** Bold + italic + link + inline code in one fixed document. */
        const val GOLDEN_MARKDOWN =
            "**Bold text** and *italic text* and a [link](https://columba.network) and `inline code`."

        const val GOLDEN_BUBBLE_TEXT = "Hello over LXMF"

        val GOLDEN_REPLY_PREVIEW =
            ReplyPreviewUi(
                messageId = "msg-golden-0001",
                senderName = "Alice",
                contentPreview = "The original message being quoted",
                hasImage = true,
                hasFileAttachment = true,
                firstFileName = "report.pdf",
            )

        val GOLDEN_REPLY_PREVIEW_DELETED =
            ReplyPreviewUi(
                messageId = "msg-golden-0002",
                senderName = "Bob",
                contentPreview = "",
                isDeletedPlaceholder = true,
            )
    }
}
