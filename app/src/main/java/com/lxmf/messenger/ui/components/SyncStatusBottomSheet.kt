package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.SyncProgress

/**
 * Bottom sheet showing real-time sync progress with propagation node.
 *
 * Displays:
 * - Current sync state (path discovery, connecting, downloading)
 * - Progress percentage during download
 * - Success indicator when complete
 *
 * @param syncProgress Current sync progress state
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param sheetState The state of the bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun SyncStatusBottomSheet(
    syncProgress: SyncProgress,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            // Header with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Propagation Node Sync",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status content based on sync state
            when (syncProgress) {
                is SyncProgress.Idle -> {
                    SyncStateRow(
                        icon = { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "Ready",
                        subtitle = "Not currently syncing",
                    )
                }
                is SyncProgress.Starting -> {
                    SyncStateRow(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                        title = "Starting sync...",
                        subtitle = "Initiating connection to relay",
                    )
                }
                is SyncProgress.InProgress -> {
                    val stateName = syncProgress.stateName.replaceFirstChar { it.uppercase() }
                    val subtitle = getStateDescription(syncProgress.stateName)

                    SyncStateRow(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                        title = stateName,
                        subtitle = subtitle,
                    )

                    // Show progress bar if we have progress info
                    if (syncProgress.progress > 0f) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { syncProgress.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(syncProgress.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SyncProgress.Complete -> {
                    SyncStateRow(
                        icon = { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "Download complete",
                        subtitle = "Messages received",
                    )
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SyncStateRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun getStateDescription(stateName: String): String =
    when (stateName.lowercase()) {
        "path_requested" -> "Discovering network path to relay..."
        "link_establishing" -> "Establishing secure connection..."
        "link_established" -> "Connected, preparing request..."
        "request_sent" -> "Requested message list from relay..."
        "receiving", "downloading" -> "Downloading messages..."
        "complete" -> "Sync complete!"
        else -> "Processing..."
    }
