package network.columba.app.ui.components

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import network.columba.app.ui.model.FileAttachmentUi
import network.columba.app.util.FileAttachment
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for the attachment components: message attachment cards, pending
 * attachment previews, option sheets and the composer attachment panel.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== FileAttachmentCard ==========

    @Test
    fun `file attachment card pdf`() {
        composeRule.setContent {
            MaterialTheme {
                FileAttachmentCard(attachment = PDF_ATTACHMENT, onTap = {})
            }
        }
        TextGoldens.assertGolden("attachment_file_card_pdf", composeRule)
    }

    @Test
    fun `file attachment card image`() {
        composeRule.setContent {
            MaterialTheme {
                FileAttachmentCard(attachment = IMAGE_ATTACHMENT, onTap = {})
            }
        }
        TextGoldens.assertGolden("attachment_file_card_image", composeRule)
    }

    // ========== FileAttachmentOptionsSheet ==========

    @Test
    fun `file attachment options sheet plain`() {
        composeRule.setContent {
            MaterialTheme {
                FileAttachmentOptionsSheet(
                    filename = OPTIONS_FILENAME,
                    onOpenWith = {},
                    onSaveToDevice = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_file_options_sheet_plain", composeRule)
    }

    @Test
    fun `file attachment options sheet pyxis update`() {
        composeRule.setContent {
            MaterialTheme {
                FileAttachmentOptionsSheet(
                    filename = OPTIONS_FILENAME,
                    onOpenWith = {},
                    onSaveToDevice = {},
                    onUpdatePyxis = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_file_options_sheet_pyxis_update", composeRule)
    }

    // ========== FileAttachmentPreviewRow ==========

    @Test
    fun `file attachment preview row three files`() {
        composeRule.setContent {
            MaterialTheme {
                FileAttachmentPreviewRow(
                    attachments = PREVIEW_ATTACHMENTS,
                    totalSizeBytes = PREVIEW_TOTAL_BYTES,
                    onRemove = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_file_preview_row_three_files", composeRule)
    }

    // ========== ImageOptionsSheet ==========

    @Test
    fun `image options sheet`() {
        composeRule.setContent {
            MaterialTheme {
                ImageOptionsSheet(
                    onSaveToDevice = {},
                    onShare = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_image_options_sheet", composeRule)
    }

    // ========== AttachmentPanel ==========

    @Test
    fun `attachment panel with recent photos`() {
        composeRule.setContent {
            MaterialTheme {
                AttachmentPanel(
                    panelHeight = PANEL_HEIGHT,
                    recentPhotos = RECENT_PHOTOS,
                    hasMediaPermission = true,
                    onRequestMediaPermission = {},
                    onPhotoSelected = {},
                    onGalleryClick = {},
                    onFileClick = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_panel_with_recent_photos", composeRule)
    }

    @Test
    fun `attachment panel permission prompt`() {
        composeRule.setContent {
            MaterialTheme {
                AttachmentPanel(
                    panelHeight = PANEL_HEIGHT,
                    recentPhotos = RECENT_PHOTOS,
                    hasMediaPermission = false,
                    onRequestMediaPermission = {},
                    onPhotoSelected = {},
                    onGalleryClick = {},
                    onFileClick = {},
                )
            }
        }
        TextGoldens.assertGolden("attachment_panel_permission_prompt", composeRule)
    }

    private companion object {
        private val PDF_ATTACHMENT =
            FileAttachmentUi(
                filename = "quarterly-report.pdf",
                sizeBytes = 2_500_000,
                mimeType = "application/pdf",
                index = 0,
            )

        private val IMAGE_ATTACHMENT =
            FileAttachmentUi(
                filename = "team-photo.png",
                sizeBytes = 348_160,
                mimeType = "image/png",
                index = 0,
            )

        private const val OPTIONS_FILENAME = "quarterly-report.pdf"

        private val PREVIEW_ATTACHMENTS =
            listOf(
                FileAttachment(
                    filename = "quarterly-report.pdf",
                    data = ByteArray(2_500_000),
                    mimeType = "application/pdf",
                    sizeBytes = 2_500_000,
                ),
                FileAttachment(
                    filename = "meeting-notes.txt",
                    data = ByteArray(1_024),
                    mimeType = "text/plain",
                    sizeBytes = 1_024,
                ),
                FileAttachment(
                    filename = "site-snapshot.jpg",
                    data = ByteArray(512_000),
                    mimeType = "image/jpeg",
                    sizeBytes = 512_000,
                ),
            )

        private const val PREVIEW_TOTAL_BYTES = 2_500_000 + 1_024 + 512_000

        private val RECENT_PHOTOS =
            listOf(
                Uri.parse("content://media/external/images/media/1"),
                Uri.parse("content://media/external/images/media/2"),
                Uri.parse("content://media/external/images/media/3"),
            )

        private val PANEL_HEIGHT = 300.dp
    }
}
