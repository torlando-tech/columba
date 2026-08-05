package network.columba.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.audio.VoiceMessagePlayerState
import network.columba.app.audio.VoiceMessageRecordingState
import tech.torlando.lxst.recording.RecorderState
import java.util.concurrent.TimeUnit

@Composable
fun VoiceRecordingControls(
    state: VoiceMessageRecordingState,
    hasPermission: Boolean,
    permissionPermanentlyDenied: Boolean = false,
    isSupported: Boolean,
    onRequestPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !isSupported -> {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.attachment_voice_panel_unsupported),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.attachment_voice_panel_cancel))
                    }
                }
                !hasPermission -> {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(
                            if (permissionPermanentlyDenied) {
                                R.string.attachment_voice_panel_permission_settings
                            } else {
                                R.string.attachment_voice_panel_permission_needed
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = if (permissionPermanentlyDenied) onOpenPermissionSettings else onRequestPermission) {
                        Text(
                            stringResource(
                                if (permissionPermanentlyDenied) {
                                    R.string.attachment_voice_panel_open_settings
                                } else {
                                    R.string.attachment_voice_panel_request_permission
                                },
                            ),
                        )
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.attachment_voice_panel_cancel))
                    }
                }
                state.recorderState is RecorderState.Recording -> {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.attachment_voice_panel_cancel))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.attachment_voice_panel_recording),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(formatMs(state.elapsedMillis), style = MaterialTheme.typography.titleLarge)
                    }
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.attachment_voice_panel_stop))
                    }
                }
                state.recorderState == RecorderState.Finalizing -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.attachment_voice_panel_finalizing), style = MaterialTheme.typography.labelLarge)
                        Text(formatMs(state.elapsedMillis), style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.attachment_voice_panel_cancel))
                    }
                }
                else -> {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.attachment_voice_panel_ready), style = MaterialTheme.typography.labelLarge)
                        state.errorMessage?.let {
                            Text(
                                stringResource(R.string.attachment_voice_panel_error, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    FilledIconButton(onClick = onStart, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.attachment_voice_panel_start))
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.attachment_voice_panel_cancel))
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceDraftPreview(
    durationMillis: Long,
    state: VoiceMessagePlayerState,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        if (state.playing) {
                            stringResource(R.string.message_voice_pause)
                        } else {
                            stringResource(R.string.message_voice_play)
                        },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.attachment_voice_panel_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(
                        R.string.message_voice_progress,
                        formatMs(state.progressMs.toLong()),
                        formatMs(state.durationMs.toLong().takeIf { it > 0L } ?: durationMillis),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.attachment_voice_panel_remove))
            }
        }
        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (state.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (state.progressMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun VoiceMessageBubble(
    title: String,
    state: VoiceMessagePlayerState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        when {
            state.loading -> Text(stringResource(R.string.message_voice_loading))
            state.error == "unsupported" -> Text(stringResource(R.string.message_voice_unsupported))
            state.error != null -> Text(stringResource(R.string.message_voice_unavailable))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onToggle) {
                        Icon(
                            imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) stringResource(R.string.message_voice_pause) else stringResource(R.string.message_voice_play),
                        )
                    }
                    Text(stringResource(R.string.message_voice_progress, formatMs(state.progressMs.toLong()), formatMs(state.durationMs.toLong())))
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%d:%02d".format(total / 60, total % 60)
}
