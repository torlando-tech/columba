package network.columba.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.columba.app.R

/**
 * Attachment panel shown in place of the keyboard.
 *
 * Photo selection goes through the system Photo Picker (launched by
 * [onPhotosClick]), so the app never needs READ_MEDIA_IMAGES or any other
 * broad-storage permission — see Play's Photo and Video Permissions policy.
 * The remaining tiles open the document pickers (SAF) or the recorder.
 */
@Composable
fun AttachmentPanel(
    panelHeight: Dp,
    onPhotosClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    onVoiceClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(panelHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            // Photo picker entry point: a full-width tap target so the panel
            // stays a keyboard-height surface without an in-app photo grid.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(onClick = onPhotosClick)
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.attach_panel_choose_photos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // Action buttons row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onGalleryClick) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.attachment_gallery),
                    )
                }
                Text(
                    text = stringResource(R.string.attachment_gallery),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )

                FilledTonalIconButton(onClick = onFileClick) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.attachment_file),
                    )
                }
                Text(
                    text = stringResource(R.string.attachment_file),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalIconButton(onClick = onVoiceClick) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.attachment_voice_description),
                    )
                }
                Text(
                    text = stringResource(R.string.attachment_voice),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
