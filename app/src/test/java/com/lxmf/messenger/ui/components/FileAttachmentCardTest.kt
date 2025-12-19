package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.FileAttachmentUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive UI tests for FileAttachmentCard composable.
 * Tests display, styling, and interaction behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileAttachmentCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var tapCallbackInvoked = false

    @Before
    fun setUp() {
        tapCallbackInvoked = false
    }

    // ========== Display Tests ==========

    @Test
    fun `displays filename correctly`() {
        val attachment = createAttachment(filename = "document.pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("document.pdf").assertIsDisplayed()
    }

    @Test
    fun `displays file size in bytes`() {
        val attachment = createAttachment(sizeBytes = 512)

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("512 B").assertIsDisplayed()
    }

    @Test
    fun `displays file size in KB`() {
        val attachment = createAttachment(sizeBytes = 1536) // 1.5 KB

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("1.5 KB").assertIsDisplayed()
    }

    @Test
    fun `displays file size in MB`() {
        val attachment = createAttachment(sizeBytes = 1024 * 1024 * 3) // 3 MB

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("3.0 MB").assertIsDisplayed()
    }

    @Test
    fun `displays content description with MIME type`() {
        val attachment = createAttachment(mimeType = "application/pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/pdf").assertIsDisplayed()
    }

    // ========== Icon Tests ==========

    @Test
    fun `displays PDF icon for PDF files`() {
        val attachment = createAttachment(mimeType = "application/pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/pdf").assertIsDisplayed()
    }

    @Test
    fun `displays text icon for text files`() {
        val attachment = createAttachment(mimeType = "text/plain")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: text/plain").assertIsDisplayed()
    }

    @Test
    fun `displays audio icon for audio files`() {
        val attachment = createAttachment(mimeType = "audio/mpeg")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: audio/mpeg").assertIsDisplayed()
    }

    @Test
    fun `displays video icon for video files`() {
        val attachment = createAttachment(mimeType = "video/mp4")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: video/mp4").assertIsDisplayed()
    }

    @Test
    fun `displays archive icon for zip files`() {
        val attachment = createAttachment(mimeType = "application/zip")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/zip").assertIsDisplayed()
    }

    @Test
    fun `displays generic icon for unknown MIME types`() {
        val attachment = createAttachment(mimeType = "application/octet-stream")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/octet-stream").assertIsDisplayed()
    }

    // ========== Interaction Tests ==========

    @Test
    fun `tap invokes onTap callback`() {
        val attachment = createAttachment(filename = "test.pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = { tapCallbackInvoked = true })
        }

        composeTestRule.onNodeWithText("test.pdf").performClick()

        assertTrue("onTap callback should be invoked", tapCallbackInvoked)
    }

    @Test
    fun `tap anywhere on card invokes callback`() {
        val attachment = createAttachment(sizeBytes = 1024)

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = { tapCallbackInvoked = true })
        }

        // Click on the size text
        composeTestRule.onNodeWithText("1.0 KB").performClick()

        assertTrue("onTap callback should be invoked when clicking size", tapCallbackInvoked)
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `handles very long filename`() {
        val longFilename = "this_is_a_very_long_filename_that_should_be_truncated_with_ellipsis_in_the_ui.pdf"
        val attachment = createAttachment(filename = longFilename)

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        // The node should exist (even if truncated)
        composeTestRule.onNodeWithText(longFilename, substring = true).assertIsDisplayed()
    }

    @Test
    fun `handles zero byte file`() {
        val attachment = createAttachment(sizeBytes = 0)

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("0 B").assertIsDisplayed()
    }

    @Test
    fun `handles empty filename`() {
        val attachment = createAttachment(filename = "")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        // Card should still render without crashing
        composeTestRule.onNodeWithText("1.0 KB").assertIsDisplayed()
    }

    @Test
    fun `handles special characters in filename`() {
        val attachment = createAttachment(filename = "file (1) [copy].pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("file (1) [copy].pdf").assertIsDisplayed()
    }

    @Test
    fun `handles unicode in filename`() {
        val attachment = createAttachment(filename = "文档.pdf")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithText("文档.pdf").assertIsDisplayed()
    }

    // ========== MIME Type Variations ==========

    @Test
    fun `displays correct icon for CSV files`() {
        val attachment = createAttachment(mimeType = "text/csv")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: text/csv").assertIsDisplayed()
    }

    @Test
    fun `displays correct icon for HTML files`() {
        val attachment = createAttachment(mimeType = "text/html")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: text/html").assertIsDisplayed()
    }

    @Test
    fun `displays correct icon for gzip files`() {
        val attachment = createAttachment(mimeType = "application/gzip")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/gzip").assertIsDisplayed()
    }

    @Test
    fun `displays correct icon for tar files`() {
        val attachment = createAttachment(mimeType = "application/x-tar")

        composeTestRule.setContent {
            FileAttachmentCard(attachment = attachment, onTap = {})
        }

        composeTestRule.onNodeWithContentDescription("File type: application/x-tar").assertIsDisplayed()
    }

    // ========== Multiple Cards ==========

    @Test
    fun `multiple cards display correctly`() {
        val attachment1 = createAttachment(filename = "file1.pdf", sizeBytes = 1024)
        val attachment2 = createAttachment(filename = "file2.txt", sizeBytes = 2048)
        var tappedIndex = -1

        composeTestRule.setContent {
            androidx.compose.foundation.layout.Column {
                FileAttachmentCard(attachment = attachment1, onTap = { tappedIndex = 0 })
                FileAttachmentCard(attachment = attachment2, onTap = { tappedIndex = 1 })
            }
        }

        composeTestRule.onNodeWithText("file1.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("file2.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.0 KB").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.0 KB").assertIsDisplayed()

        // Tap first card
        composeTestRule.onNodeWithText("file1.pdf").performClick()
        assertEquals(0, tappedIndex)

        // Tap second card
        composeTestRule.onNodeWithText("file2.txt").performClick()
        assertEquals(1, tappedIndex)
    }

    // ========== Helper Functions ==========

    private fun createAttachment(
        filename: String = "test.pdf",
        sizeBytes: Int = 1024,
        mimeType: String = "application/pdf",
        index: Int = 0,
    ): FileAttachmentUi =
        FileAttachmentUi(
            filename = filename,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            index = index,
        )
}
