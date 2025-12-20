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
import androidx.compose.material.icons.filled.Speed
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
 * Shows a warning banner when files exceed the slow transfer threshold (1 MB),
 * indicating potential slow transfer on mesh networks.
 *
 * @param attachments List of pending file attachments to display
 * @param totalSizeBytes Combined size of all attachments in bytes
 * @param onRemove Callback invoked when a file's remove button is clicked, providing the index
 * @param maxSizeBytes Maximum allowed total attachment size in bytes (0 means unlimited)
 * @param modifier Optional modifier for the component
 */
@Suppress("FunctionNaming")
@Composable
fun FileAttachmentPreviewRow(
    attachments: List<FileAttachment>,
    totalSizeBytes: Int,
    onRemove: (Int) -> Unit,
    maxSizeBytes: Int = FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE,
    modifier: Modifier = Modifier,
) {
    // Calculate size limit percentage for visual feedback (only if limit is set)
    val sizePercentage = if (maxSizeBytes > 0) {
        totalSizeBytes.toFloat() / maxSizeBytes
    } else {
        0f // No limit, always show as 0%
    }
    val isNearLimit = sizePercentage >= 0.8f
    val isAtLimit = sizePercentage >= 1.0f

    // Show slow transfer warning for files over 1 MB
    val showSlowTransferWarning = FileUtils.exceedsSlowTransferThreshold(totalSizeBytes)

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
            // Slow transfer warning banner
            if (showSlowTransferWarning) {
                SlowTransferWarningBanner()
            }

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

                // Total size indicator (only show if there's a limit)
                if (maxSizeBytes > 0) {
                    TotalSizeIndicator(
                        totalSizeBytes = totalSizeBytes,
                        maxSizeBytes = maxSizeBytes,
                        isNearLimit = isNearLimit,
                        isAtLimit = isAtLimit,
                    )
                } else {
                    // Show just the current size when unlimited
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = FileUtils.formatFileSize(totalSizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Warning banner displayed when file attachments exceed the slow transfer threshold (1 MB).
 * Informs users that large files may transfer slowly on mesh networks.
 */
@Suppress("FunctionNaming")
@Composable
private fun SlowTransferWarningBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Large file transfer",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Files over 1 MB may transfer slowly on mesh networks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
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

// Previews
@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(showBackground = true)
@Composable
private fun FileAttachmentPreviewRowPreview() {
    ColumbaTheme {
        val size500KB = 500 * 1024
        val size100KB = 100 * 1024

        val sampleAttachments =
            listOf(
                FileAttachment(
                    filename = "document.pdf",
                    data = ByteArray(size500KB),
                    mimeType = "application/pdf",
                    sizeBytes = size500KB,
                ),
                FileAttachment(
                    filename = "spreadsheet.xlsx",
                    data = ByteArray(size100KB),
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    sizeBytes = size100KB,
                ),
            )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Normal state - 600 KB (no warning, under 1 MB)
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = 600 * 1024,
                maxSizeBytes = 8 * 1024 * 1024, // 8 MB limit
                onRemove = {},
            )

            // Near limit state (80% of 8 MB = 6.4 MB)
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = (6.4 * 1024 * 1024).toInt(),
                maxSizeBytes = 8 * 1024 * 1024,
                onRemove = {},
            )

            // Over 1 MB - shows slow transfer warning
            FileAttachmentPreviewRow(
                attachments = sampleAttachments,
                totalSizeBytes = (1.5 * 1024 * 1024).toInt(),
                maxSizeBytes = 8 * 1024 * 1024,
                onRemove = {},
            )
        }
    }
}

@Suppress("UnusedPrivateMember", "FunctionNaming")
@Preview(showBackground = true)
@Composable
private fun FileAttachmentPreviewRowWarningPreview() {
    ColumbaTheme {
        val size2MB = 2 * 1024 * 1024

        val largeAttachment =
            FileAttachment(
                filename = "large_video.mp4",
                data = ByteArray(size2MB),
                mimeType = "video/mp4",
                sizeBytes = size2MB,
            )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Large file with warning banner
            FileAttachmentPreviewRow(
                attachments = listOf(largeAttachment),
                totalSizeBytes = size2MB,
                maxSizeBytes = 8 * 1024 * 1024,
                onRemove = {},
            )

            // Unlimited mode (0 means no limit) - just shows size
            FileAttachmentPreviewRow(
                attachments = listOf(largeAttachment),
                totalSizeBytes = size2MB,
                maxSizeBytes = 0, // Unlimited
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
