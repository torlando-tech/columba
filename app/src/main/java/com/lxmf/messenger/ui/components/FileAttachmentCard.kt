package com.lxmf.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.model.FileAttachmentUi
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.util.FileUtils

/**
 * Card component for displaying file attachments in messages.
 *
 * Displays a file attachment with:
 * - Type-specific icon based on MIME type
 * - Filename (truncated with ellipsis if too long)
 * - File size in human-readable format
 * - Ripple effect on tap
 *
 * Matches the styling of image attachments in the messaging UI with:
 * - 12dp rounded corners
 * - Max width of 268dp
 * - Material 3 theming
 *
 * @param attachment The file attachment to display
 * @param onTap Callback invoked when the user taps the card to open/save the file
 * @param modifier Optional modifier for additional customization
 */
@Suppress("FunctionNaming")
@Composable
fun FileAttachmentCard(
    attachment: FileAttachmentUi,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .widthIn(max = 268.dp)
                .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File type icon
            Icon(
                imageVector = FileUtils.getFileIconForMimeType(attachment.mimeType),
                contentDescription = "File type: ${attachment.mimeType}",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Filename and size
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = FileUtils.formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Preview of FileAttachmentCard with various file types.
 */
@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun FileAttachmentCardPreview() {
    ColumbaTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // PDF file (150 KB)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "document.pdf",
                        sizeBytes = 1024 * 150,
                        mimeType = "application/pdf",
                        index = 0,
                    ),
                onTap = {},
            )

            // Text file (2 KB)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "notes.txt",
                        sizeBytes = 2048,
                        mimeType = "text/plain",
                        index = 0,
                    ),
                onTap = {},
            )

            // Audio file (3 MB)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "recording.mp3",
                        sizeBytes = 1024 * 1024 * 3,
                        mimeType = "audio/mpeg",
                        index = 0,
                    ),
                onTap = {},
            )

            // ZIP archive (512 KB)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "archive.zip",
                        sizeBytes = 512 * 1024,
                        mimeType = "application/zip",
                        index = 0,
                    ),
                onTap = {},
            )

            // Long filename test (45 KB)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "very_long_filename_that_will_definitely_need_to_be_truncated_with_ellipsis.doc",
                        sizeBytes = 45 * 1024,
                        mimeType = "application/msword",
                        index = 0,
                    ),
                onTap = {},
            )

            // Generic file (256 B)
            FileAttachmentCard(
                attachment =
                    FileAttachmentUi(
                        filename = "data.bin",
                        sizeBytes = 256,
                        mimeType = "application/octet-stream",
                        index = 0,
                    ),
                onTap = {},
            )
        }
    }
}
