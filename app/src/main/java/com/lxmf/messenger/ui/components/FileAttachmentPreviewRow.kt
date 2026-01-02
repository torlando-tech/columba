package com.lxmf.messenger.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.FileUtils

/**
 * Displays a horizontal scrolling row of pending file attachments before sending.
 *
 * Each attachment is shown as a compact chip/card with:
 * - File type icon (based on MIME type)
 * - Truncated filename
 * - Remove (X) button
 *
 * Also displays a total size indicator at the end of the row with visual feedback
 * when approaching or exceeding the maximum attachment size limit.
 *
 * @param attachments List of pending file attachments to display
 * @param totalSizeBytes Combined size of all attachments in bytes
 * @param onRemove Callback invoked when a file's remove button is clicked, providing the index
 * @param modifier Optional modifier for the component
 */
@Suppress("FunctionNaming")
@Composable
fun FileAttachmentPreviewRow(
    attachments: List<FileAttachment>,
    totalSizeBytes: Int,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Calculate size limit percentage for visual feedback
    val sizePercentage = totalSizeBytes.toFloat() / FileUtils.MAX_TOTAL_ATTACHMENT_SIZE
    val isNearLimit = sizePercentage >= 0.8f
    val isAtLimit = sizePercentage >= 1.0f

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Horizontal scrolling row of attachment chips
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Individual attachment chips
                attachments.forEachIndexed { index, attachment ->
                    FileAttachmentChip(
                        attachment = attachment,
                        onRemove = { onRemove(index) },
                    )
                }

                // Total size indicator
                TotalSizeIndicator(
                    totalSizeBytes = totalSizeBytes,
                    maxSizeBytes = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                    isNearLimit = isNearLimit,
                    isAtLimit = isAtLimit,
                )
            }
        }
    }
}

/**
 * Individual file attachment chip showing icon, filename, and remove button.
 */
@Suppress("FunctionNaming")
@Composable
private fun FileAttachmentChip(
    attachment: FileAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File type icon
            Icon(
                imageVector = FileUtils.getFileIconForMimeType(attachment.mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            // Filename and size
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = FileUtils.formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Remove button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${attachment.filename}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Total size indicator showing current usage vs. maximum limit.
 */
@Suppress("FunctionNaming")
@Composable
private fun TotalSizeIndicator(
    totalSizeBytes: Int,
    maxSizeBytes: Int,
    isNearLimit: Boolean,
    isAtLimit: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor =
        when {
            isAtLimit -> MaterialTheme.colorScheme.error
            isNearLimit -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    val backgroundColor =
        when {
            isAtLimit -> MaterialTheme.colorScheme.errorContainer
            isNearLimit -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
    ) {
        Text(
            text = "${FileUtils.formatFileSize(totalSizeBytes)} / ${FileUtils.formatFileSize(maxSizeBytes)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// Preview
@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(showBackground = true)
@Composable
private fun FileAttachmentPreviewRowPreview() {
    ColumbaTheme {
        // 50 KB
        val size50KB = 50 * 1024
        // 10 KB
        val size10KB = 10 * 1024
        // 100 KB
        val size100KB = 100 * 1024

        val sampleAttachments =
            listOf(
                FileAttachment(
                    filename = "document.pdf",
                    data = ByteArray(size50KB),
                    mimeType = "application/pdf",
                    sizeBytes = size50KB,
                ),
                FileAttachment(
                    filename = "very_long_filename_that_should_be_truncated.txt",
                    data = ByteArray(size10KB),
                    mimeType = "text/plain",
                    sizeBytes = size10KB,
                ),
                FileAttachment(
                    filename = "archive.zip",
                    data = ByteArray(size100KB),
                    mimeType = "application/zip",
                    sizeBytes = size100KB,
                ),
            )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Normal state - 160 KB
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = 160 * 1024,
                onRemove = {},
            )

            // Near limit state - 410 KB (~80%)
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = 410 * 1024,
                onRemove = {},
            )

            // At limit state - 520 KB (over limit)
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = 520 * 1024,
                onRemove = {},
            )

            // Single file
            FileAttachmentPreviewRow(
                attachments = listOf(sampleAttachments[0]),
                totalSizeBytes = 50 * 1024,
                onRemove = {},
            )
        }
    }
}

@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(showBackground = true)
@Composable
private fun FileAttachmentChipPreview() {
    ColumbaTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileAttachmentChip(
                attachment =
                    FileAttachment(
                        filename = "document.pdf",
                        data = ByteArray(50 * 1024),
                        mimeType = "application/pdf",
                        sizeBytes = 50 * 1024,
                    ),
                onRemove = {},
            )

            FileAttachmentChip(
                attachment =
                    FileAttachment(
                        filename = "song.mp3",
                        data = ByteArray(200 * 1024),
                        mimeType = "audio/mpeg",
                        sizeBytes = 200 * 1024,
                    ),
                onRemove = {},
            )

            FileAttachmentChip(
                attachment =
                    FileAttachment(
                        filename = "very_long_filename_that_should_definitely_be_truncated_here.txt",
                        data = ByteArray(5 * 1024),
                        mimeType = "text/plain",
                        sizeBytes = 5 * 1024,
                    ),
                onRemove = {},
            )
        }
    }
}
