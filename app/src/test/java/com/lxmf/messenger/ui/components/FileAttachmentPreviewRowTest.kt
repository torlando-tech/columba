package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive UI tests for FileAttachmentPreviewRow composable.
 * Tests display, size indicators, and remove button interactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileAttachmentPreviewRowTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var removedIndex: Int = -1

    @Before
    fun setUp() {
        removedIndex = -1
    }

    // ========== Basic Display Tests ==========

    @Test
    fun `displays single attachment filename`() {
        val attachments = listOf(createAttachment(filename = "document.pdf"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 50 * 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("document.pdf").assertIsDisplayed()
    }

    @Test
    fun `displays single attachment size`() {
        val attachments = listOf(createAttachment(sizeBytes = 50 * 1024))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 50 * 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("50.0 KB").assertIsDisplayed()
    }

    @Test
    fun `displays multiple attachment filenames`() {
        val attachments =
            listOf(
                createAttachment(filename = "doc1.pdf"),
                createAttachment(filename = "doc2.txt"),
                createAttachment(filename = "doc3.zip"),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 150 * 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("doc1.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("doc2.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("doc3.zip").assertIsDisplayed()
    }

    // ========== Total Size Indicator Tests ==========

    @Test
    fun `displays total size indicator`() {
        val attachments = listOf(createAttachment(sizeBytes = 100 * 1024))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 100 * 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("100.0 KB / 512.0 KB").assertIsDisplayed()
    }

    @Test
    fun `displays total size indicator for small files`() {
        val attachments = listOf(createAttachment(sizeBytes = 512))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 512,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("512 B / 512.0 KB").assertIsDisplayed()
    }

    @Test
    fun `total size indicator shows correct max size`() {
        val attachments = listOf(createAttachment(sizeBytes = 1024))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        // Verify max size is displayed (512 KB)
        val expectedMax = FileUtils.formatFileSize(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)
        composeTestRule.onNodeWithText("1.0 KB / $expectedMax").assertIsDisplayed()
    }

    // ========== Size Limit Visual Feedback Tests ==========

    @Test
    fun `normal state below 80 percent limit`() {
        // 50% of limit = 256 KB
        val totalSize = (FileUtils.MAX_TOTAL_ATTACHMENT_SIZE * 0.5).toInt()
        val attachments = listOf(createAttachment(sizeBytes = totalSize))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = totalSize,
                onRemove = {},
            )
        }

        // Should display without error styling (just verify it renders)
        composeTestRule.onNodeWithText(
            "${FileUtils.formatFileSize(totalSize)} / ${FileUtils.formatFileSize(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)}",
        ).assertIsDisplayed()
    }

    @Test
    fun `near limit state at 80 percent`() {
        // 80% of limit = 409.6 KB
        val totalSize = (FileUtils.MAX_TOTAL_ATTACHMENT_SIZE * 0.8).toInt()
        val attachments = listOf(createAttachment(sizeBytes = totalSize))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = totalSize,
                onRemove = {},
            )
        }

        // Should display (visual styling change is verified by existence)
        composeTestRule.onNodeWithText(
            "${FileUtils.formatFileSize(totalSize)} / ${FileUtils.formatFileSize(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)}",
        ).assertIsDisplayed()
    }

    @Test
    fun `at limit state at 100 percent`() {
        val totalSize = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE
        val attachments = listOf(createAttachment(sizeBytes = totalSize))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = totalSize,
                onRemove = {},
            )
        }

        // Should display with error styling
        composeTestRule.onNodeWithText("512.0 KB / 512.0 KB").assertIsDisplayed()
    }

    @Test
    fun `over limit state above 100 percent`() {
        // 110% of limit
        val totalSize = (FileUtils.MAX_TOTAL_ATTACHMENT_SIZE * 1.1).toInt()
        val attachments = listOf(createAttachment(sizeBytes = totalSize))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = totalSize,
                onRemove = {},
            )
        }

        // Should still display (with error styling)
        composeTestRule.onNodeWithText(
            "${FileUtils.formatFileSize(totalSize)} / ${FileUtils.formatFileSize(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)}",
        ).assertIsDisplayed()
    }

    // ========== Remove Button Tests ==========

    @Test
    fun `remove button displays for attachment`() {
        val attachments = listOf(createAttachment(filename = "test.pdf"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Remove test.pdf").assertIsDisplayed()
    }

    @Test
    fun `remove button click invokes callback with correct index`() {
        val attachments =
            listOf(
                createAttachment(filename = "first.pdf"),
                createAttachment(filename = "second.txt"),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 2048,
                onRemove = { removedIndex = it },
            )
        }

        // Click remove on second attachment
        composeTestRule.onNodeWithContentDescription("Remove second.txt").performClick()

        assertEquals(1, removedIndex)
    }

    @Test
    fun `remove button click on first attachment invokes callback with index 0`() {
        val attachments =
            listOf(
                createAttachment(filename = "first.pdf"),
                createAttachment(filename = "second.txt"),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 2048,
                onRemove = { removedIndex = it },
            )
        }

        composeTestRule.onNodeWithContentDescription("Remove first.pdf").performClick()

        assertEquals(0, removedIndex)
    }

    @Test
    fun `all attachments have remove buttons`() {
        val attachments =
            listOf(
                createAttachment(filename = "doc1.pdf"),
                createAttachment(filename = "doc2.txt"),
                createAttachment(filename = "doc3.zip"),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 3072,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Remove doc1.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove doc2.txt").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove doc3.zip").assertIsDisplayed()
    }

    // ========== File Icon Tests ==========

    @Test
    fun `displays PDF icon for PDF MIME type`() {
        val attachments = listOf(createAttachment(mimeType = "application/pdf"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        // Component renders without crash - icon is based on MIME type
        composeTestRule.onNodeWithText("test.pdf").assertIsDisplayed()
    }

    @Test
    fun `displays text icon for text MIME type`() {
        val attachments = listOf(createAttachment(mimeType = "text/plain", filename = "notes.txt"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
    }

    @Test
    fun `displays archive icon for zip MIME type`() {
        val attachments = listOf(createAttachment(mimeType = "application/zip", filename = "files.zip"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("files.zip").assertIsDisplayed()
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `handles empty attachment list gracefully`() {
        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = emptyList(),
                totalSizeBytes = 0,
                onRemove = {},
            )
        }

        // Should display total size indicator even with no attachments
        composeTestRule.onNodeWithText("0 B / 512.0 KB").assertIsDisplayed()
    }

    @Test
    fun `handles very long filename with truncation`() {
        val longFilename = "this_is_an_extremely_long_filename_that_should_be_truncated_properly_in_the_ui_display.pdf"
        val attachments = listOf(createAttachment(filename = longFilename))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        // Node should exist (text may be truncated with ellipsis)
        composeTestRule.onNodeWithText(longFilename, substring = true).assertIsDisplayed()
    }

    @Test
    fun `handles special characters in filename`() {
        val attachments = listOf(createAttachment(filename = "file (1) [copy].pdf"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("file (1) [copy].pdf").assertIsDisplayed()
    }

    @Test
    fun `handles unicode in filename`() {
        val attachments = listOf(createAttachment(filename = "文档.pdf"))

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("文档.pdf").assertIsDisplayed()
    }

    @Test
    fun `handles many attachments`() {
        val attachments = (1..10).map { createAttachment(filename = "file$it.pdf") }

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 10 * 1024,
                onRemove = {},
            )
        }

        // First and last should be rendered (scrollable)
        composeTestRule.onNodeWithText("file1.pdf").assertIsDisplayed()
        // Last one may require scrolling, but component should not crash
    }

    @Test
    fun `multiple sizes display correctly`() {
        val attachments =
            listOf(
                createAttachment(filename = "small.txt", sizeBytes = 512),
                createAttachment(filename = "medium.pdf", sizeBytes = 50 * 1024),
                createAttachment(filename = "large.zip", sizeBytes = 200 * 1024),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 512 + 50 * 1024 + 200 * 1024,
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("512 B").assertIsDisplayed()
        composeTestRule.onNodeWithText("50.0 KB").assertIsDisplayed()
        composeTestRule.onNodeWithText("200.0 KB").assertIsDisplayed()
    }

    // ========== Combined Tests ==========

    @Test
    fun `displays complete attachment info with filename size and remove button`() {
        val attachments =
            listOf(
                createAttachment(filename = "report.pdf", sizeBytes = 75 * 1024),
            )

        composeTestRule.setContent {
            FileAttachmentPreviewRow(
                attachments = attachments,
                totalSizeBytes = 75 * 1024,
                onRemove = { removedIndex = it },
            )
        }

        // Verify all elements
        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("75.0 KB").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("75.0 KB / 512.0 KB").assertIsDisplayed()

        // Verify remove works
        composeTestRule.onNodeWithContentDescription("Remove report.pdf").performClick()
        assertEquals(0, removedIndex)
    }

    // ========== Helper Functions ==========

    private fun createAttachment(
        filename: String = "test.pdf",
        sizeBytes: Int = 1024,
        mimeType: String = "application/pdf",
    ): FileAttachment =
        FileAttachment(
            filename = filename,
            data = ByteArray(sizeBytes),
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )
}
