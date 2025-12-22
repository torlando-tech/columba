package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.ReplyPreviewUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplyComponentsTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== ReplyPreviewBubble TESTS ==========

    @Test
    fun `ReplyPreviewBubble displays sender name`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Alice",
            contentPreview = "Hello there!",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble displays content preview`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Bob",
            contentPreview = "This is a reply preview text",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("This is a reply preview text").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble displays image icon when hasImage is true`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Charlie",
            contentPreview = "",
            hasImage = true,
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Image").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble displays file icon when hasFileAttachment is true`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "David",
            contentPreview = "",
            hasFileAttachment = true,
            firstFileName = "document.pdf",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("File").assertIsDisplayed()
        composeTestRule.onNodeWithText("document.pdf").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble calls onClick when tapped`() {
        var clicked = false
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Eve",
            contentPreview = "Click me",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Eve").performClick()
        assertTrue("onClick should be called", clicked)
    }

    @Test
    fun `ReplyPreviewBubble displays Message placeholder for empty content`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Frank",
            contentPreview = "",
            hasImage = false,
            hasFileAttachment = false,
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Message").assertIsDisplayed()
    }

    // ========== ReplyInputBar TESTS ==========

    @Test
    fun `ReplyInputBar displays replying to sender name`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "George",
            contentPreview = "Original message",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Replying to George").assertIsDisplayed()
    }

    @Test
    fun `ReplyInputBar displays content preview`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Hannah",
            contentPreview = "This is the original message content",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        composeTestRule.onNodeWithText("This is the original message content").assertIsDisplayed()
    }

    @Test
    fun `ReplyInputBar calls onCancelReply when close button tapped`() {
        var cancelled = false
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Ivan",
            contentPreview = "Some content",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = { cancelled = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel reply").performClick()
        assertTrue("onCancelReply should be called", cancelled)
    }

    @Test
    fun `ReplyInputBar displays Photo text for image attachment`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Julia",
            contentPreview = "",
            hasImage = true,
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test
    fun `ReplyInputBar displays filename for file attachment`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Kate",
            contentPreview = "",
            hasFileAttachment = true,
            firstFileName = "report.xlsx",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        composeTestRule.onNodeWithText("report.xlsx").assertIsDisplayed()
    }

    // ========== SwipeableMessageBubble TESTS ==========

    @Test
    fun `SwipeableMessageBubble displays content`() {
        composeTestRule.setContent {
            MaterialTheme {
                SwipeableMessageBubble(
                    isFromMe = false,
                    onReply = {},
                ) {
                    Text("Test message content")
                }
            }
        }

        composeTestRule.onNodeWithText("Test message content").assertIsDisplayed()
    }

    @Test
    fun `SwipeableMessageBubble displays for sent message`() {
        composeTestRule.setContent {
            MaterialTheme {
                SwipeableMessageBubble(
                    isFromMe = true,
                    onReply = {},
                ) {
                    Text("Sent message")
                }
            }
        }

        composeTestRule.onNodeWithText("Sent message").assertIsDisplayed()
    }

    @Test
    fun `SwipeableMessageBubble displays for received message`() {
        composeTestRule.setContent {
            MaterialTheme {
                SwipeableMessageBubble(
                    isFromMe = false,
                    onReply = {},
                ) {
                    Text("Received message")
                }
            }
        }

        composeTestRule.onNodeWithText("Received message").assertIsDisplayed()
    }

    // ========== ReplyPreviewBubble Edge Cases ==========

    @Test
    fun `ReplyPreviewBubble displays for isFromMe true`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-789",
            senderName = "You",
            contentPreview = "Your original message",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = true,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("You").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your original message").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble displays both image and content`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Lisa",
            contentPreview = "Check out this photo!",
            hasImage = true,
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Image").assertIsDisplayed()
        composeTestRule.onNodeWithText("Check out this photo!").assertIsDisplayed()
    }

    @Test
    fun `ReplyPreviewBubble displays both file and content`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-123",
            senderName = "Mike",
            contentPreview = "Here's the document",
            hasFileAttachment = true,
            firstFileName = "invoice.pdf",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyPreviewBubble(
                    replyPreview = replyPreview,
                    isFromMe = false,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("File").assertIsDisplayed()
        composeTestRule.onNodeWithText("invoice.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("Here's the document").assertIsDisplayed()
    }

    @Test
    fun `ReplyInputBar displays content preview when no attachments`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Nancy",
            contentPreview = "Just a text message",
            hasImage = false,
            hasFileAttachment = false,
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Replying to Nancy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Just a text message").assertIsDisplayed()
    }

    @Test
    fun `ReplyInputBar hides file icon when no filename`() {
        val replyPreview = ReplyPreviewUi(
            messageId = "msg-456",
            senderName = "Oscar",
            contentPreview = "Some text",
            hasFileAttachment = true,
            firstFileName = null, // No filename
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReplyInputBar(
                    replyPreview = replyPreview,
                    onCancelReply = {},
                )
            }
        }

        // Should show content preview instead when no filename
        composeTestRule.onNodeWithText("Some text").assertIsDisplayed()
    }
}
